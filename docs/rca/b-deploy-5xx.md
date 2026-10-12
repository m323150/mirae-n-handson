# RCA: b-deploy-5xx

## 요약

| 항목 | 내용 |
|---|---|
| 무엇이 | `POST /api/distributions/{id}/redistribute` 가 학급 1(class 1)의 배포(`42` · `43`)에 대해 매번 500 을 돌려줬다. 500 39건, 모두 교사 단말 1대(`교사단말A`). 다른 경로 · 다른 학급은 정상 |
| 언제 | 2026-09-17 **13:46:38 ~ 14:34:45 KST**(48분). v1.4.2 배포(13:40:05 ~ 13:42:31) 4분 뒤 시작. 같은 예외의 전조 7건이 09:52:17 ~ 11:18:02 에 `distributions/41` 에서 있었다. 배포 재기동 중 502 8건(13:41:51 ~ 13:41:59)은 예고된 중단 범위다 |
| 직접 원인 | v1.4.2 가 재배포 응답에 학급 배포 이력을 넣으려고 `DistributionController.redistribute` 에서 `DistributionService.listByClass(classId)` 를 새로 호출했다(`DistributionController.java:40`). class 1 의 배포 목록 조회 결과에 **존재하지 않는 `Assignment 9` 를 가리키는 행**이 있어, 목록 조회가 `FetchNotFoundException` 으로 실패했다 |
| 확대 요인 | ① 재배포 자체는 별도 트랜잭션에서 먼저 커밋되고 그 뒤 목록 조회가 실패해, 교사는 실패를 보고 같은 요청을 반복했다 — 500 39건 모두 재배포가 실제로 반영됐다 ② 같은 고아 참조가 배포 4시간 전 전조(7건)로 이미 드러났지만 조치 · 경보가 없었다 ③ 롤백 계획(약 3분)이 있었지만 48분 동안 롤백 기록이 없다 ④ 테스트 · 스테이징은 시드 데이터(고아 행 없음)로만 확인했다 |
| 확신 수준 | **"v1.4.2 의 `listByClass` 호출 추가가 class 1 재배포를 500 으로 만든 직접 계기"는 높음**(배포 후 500 39/39 이 `listByClass` 경유, class 2 · 3 은 0건, 배포 전 같은 배포 18/18 성공). **"고아 행이 어느 배포 행이고 언제 · 어떻게 생겼는가"는 낮음** — 저장소 스키마에는 FK 가 있어 생길 수 없는 행이고, 배포 전 class 1 리포트(같은 쿼리)는 13:24:45 까지 성공했다(9절) |

> 작성 방식: 이전 회차에서 쓴 1 ~ 3.3 절의 수치 · 줄 번호를 이번 회차에 다시 확인(`awk` · `sed -n`)하고, 3.4 이후(반증 · 판정)와 4 ~ 9 절을 더했다. 코드 · 로그 · 스키마는 고치지 않았다.

대상 로그: `incident-logs/b-deploy-5xx/` (2026-09-17 하루치). 이하 `L/` = `incident-logs/b-deploy-5xx/`.

> 마스킹 별칭: `API-1` = 배포 대상 API 서버, `API-2` = 평소 정지된 대기 API 서버, `교사단말A` = 500 응답을 받은 재배포 요청 단말(교사 화면), `교사단말B` = 그 밖에 재배포를 요청한 교사 단말. 그 밖의 IP 는 `10.20.x.x` 로 적었다. 학생 식별자는 `***`, 이메일은 `[EMAIL]`, 토큰은 `[TOKEN]` 으로 적었다.

## 1. 수집 범위

> 이 절은 범위 확정만 다룬다. 원인 추정은 하지 않는다.
> 파일은 통째로 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk`/`cut` 집계로만 확인했다(`deploy-history.md` 는 39줄 문서라 전체를 읽었다).

### 1.1 파일별 기록 기간 · 형식

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 (예) | 타임존 |
|---|---:|---|---|---|
| `app.log` | 2,938 (타임스탬프 줄 1,275 + 스택트레이스 연속 줄 1,663) | 09:00:04.878 ~ 14:39:57.905 | ISO-8601 `2026-09-17T09:00:04.878+09:00` | `+09:00` 명시 (1,275줄 전부) |
| `nginx-access.log` | 20,365 | 09:00:00 ~ 14:39:58 | `[17/Sep/2026:09:00:00 +0900]` (combined) | `+0900` 명시 (20,365줄 전부) |
| `deploy-history.md` | 39 | 2026-09-01 ~ 2026-09-17 13:42:31 (배포 4건) | `2026-09-17 13:40:05` | 문서 머리말(`L/deploy-history.md:3`)에 "모두 KST" 명시 |

#### KST 변환 규칙

- 세 파일 모두 KST 라 변환하지 않는다.
- `deploy-history.md` 의 KST 표기는 다른 파일로 맞춰 확인했다: v1.4.2 배포 구간 13:40:05 ~ 13:42:31(`L/deploy-history.md:7`) 안에 `app.log` 의 종료 · 기동 줄(13:41:48.214 `L/app.log:1240` ~ 13:42:00.304 `L/app.log:1264`, `Starting ItemBankApplication v1.4.2` `L/app.log:1245`)과 `nginx-access.log` 의 502 8건(13:41:51 ~ 13:41:59, `L/nginx-access.log:16865`–`16872`)이 들어간다.
- UTC · epoch 표기 파일은 없다.

### 1.2 "이상" 줄 정의

| 파일 | 이상으로 센 줄 | 제외한 줄 |
|---|---|---|
| `app.log` | 레벨 `ERROR` 인 타임스탬프 줄(46줄, 전부 `GlobalExceptionHandler … unhandled exception on …`) | `INFO`(404 · 400 처리, 재배포 성공, 기동 · 종료), 스택트레이스 연속 줄, `WARN … state conflict`(409, 아래) |
| `nginx-access.log` | 상태 코드 5xx (500 · 502) | 2xx, 4xx(404 969 · 409 35 · 400 29) |
| `deploy-history.md` | 이상 줄 개념 없음 — 배포 시각 · 변경 내용 참고 자료 | — |

- `WARN … state conflict: 마감된 과제는 재배포할 수 없습니다`(409) 35건은 `distributionId` 1 · 2 · 3 에 대해 시간당 3 ~ 9건(09시 4, 10시 8, 11시 8, 12시 9, 13시 3, 14시 3) 고르게 나타나 **평소 수준**으로 보고 집계에서 뺐다.
- 오류 건수(하루 전체):
  - `app.log`: `ERROR` 46 — 경로 `/api/distributions/41/redistribute` 6, `/api/distributions/41` 1, `/api/distributions/42/redistribute` 19, `/api/distributions/43/redistribute` 20. 46건 모두 `Caused by: org.hibernate.FetchNotFoundException: Entity com.example.assignment.Assignment with identifier value 9 does not exist` (`grep -c 'Caused by'` = 46, 식별자 값은 46건 모두 `9`).
  - `nginx-access.log`: 500 46, 502 8.
- `ERROR` 스택에서 처음 만나는 `DistributionService` 프레임(`awk` 로 ERROR 줄 다음 첫 프레임 추출):

  | 프레임 | 건수 | 시각 범위 |
  |---|---:|---|
  | `DistributionService.redistribute(DistributionService.java:51)` → `loadOrThrow(:65)` | 6 | 09:52:17 ~ 11:18:02 |
  | `DistributionService.getDistribution(DistributionService.java:32)` → `loadOrThrow(:65)` | 1 | 10:31:05 |
  | `DistributionService.listByClass(DistributionService.java:38)` | 39 | 13:46:38 ~ 14:34:45 |

### 1.3 분 단위 이상 건수 (KST)

이상 건수가 0인 분은 생략. 명령: `nginx-access.log` 는 `awk '$9==500 / $9==502 / $6=="\"POST" && $7~/redistribute/'`, `app.log` 는 `grep ' ERROR '` 을 분 단위로 합쳤다.

| 분 | app `ERROR` | access 500 | access 502 | 같은 분의 재배포 POST(전체) |
|---|---:|---:|---:|---:|
| 09:52 | 3 | 3 | | 4 |
| 10:31 | 1 | 1 | | 0 |
| 11:17 | 1 | 1 | | 1 |
| 11:18 | 2 | 2 | | 2 |
| 13:41 | | | 8 | 1 |
| 13:46 | 2 | 2 | | 2 |
| 13:47 | 1 | 1 | | 1 |
| 13:50 | 4 | 4 | | 5 |
| 13:52 | 1 | 1 | | 1 |
| 13:53 | 1 | 1 | | 1 |
| 13:55 | 3 | 3 | | 3 |
| 13:58 | 2 | 2 | | 2 |
| 14:02 | 2 | 2 | | 2 |
| 14:03 | 4 | 4 | | 4 |
| 14:06 | 1 | 1 | | 1 |
| 14:11 | 3 | 3 | | 3 |
| 14:15 | 1 | 1 | | 1 |
| 14:16 | 2 | 2 | | 2 |
| 14:22 | 3 | 3 | | 4 |
| 14:27 | 2 | 2 | | 2 |
| 14:28 | 1 | 1 | | 1 |
| 14:31 | 2 | 2 | | 2 |
| 14:32 | 1 | 1 | | 1 |
| 14:34 | 3 | 3 | | 3 |
| 합계 | 46 | 46 | 8 | — |

- 전체 요청량(`nginx-access.log`, 340분): 분당 최소 40 · 중앙값 59 · 최대 82건. 배포 전후 13:38 ~ 13:45 는 분당 58 ~ 76건이다.
- 시간대별 재배포 POST 결과(`awk` 로 시 단위 집계):

  | 시 | 200 | 409 | 500 |
  |---|---:|---:|---:|
  | 09 | 10 | 4 | 3 |
  | 10 | 12 | 8 | 0 |
  | 11 | 10 | 8 | 3 |
  | 12 | 20 | 9 | 0 |
  | 13 | 18 | 3 | 14 |
  | 14 | 2 | 3 | 25 |

### 1.4 평소 수준과 이상 구간

| 구간 (KST) | 성격 | 근거 |
|---|---|---|
| 09:00 ~ 09:52 | 평소 | 5xx 0건, app `ERROR` 0건 |
| 09:52:17 ~ 11:18:02 | **전조** | 500 7건(분당 1 ~ 3). 경로는 전부 `distributions/41`, 예외는 이후 구간과 같은 `Assignment … 9 does not exist` |
| 11:18 ~ 13:41:50 | 평소 | 5xx 0건 |
| 13:41:51 ~ 13:41:59 | **배포 중단** | 502 8건. 같은 시각 `app.log` 종료 → 기동 줄 |
| 13:46:38 ~ 14:34:45 | **영향** | 500 39건(분당 1 ~ 4, 0건인 분 포함 49분 동안). 경로는 `distributions/42` · `43` 의 재배포 POST |
| 14:34:46 ~ 14:39:58 | 로그 끝까지 5xx 0건 | 이 사이 재배포 POST 는 1건(14:35:26, 409, `L/nginx-access.log:20119`) |

### 1.5 앞뒤 여유와 최종 수집 범위

- **최종 수집 범위: 2026-09-17 09:00:00 ~ 14:39:58 KST (로그 전체).**
- 앞 여유: 전조(09:52)와 영향(13:46)이 같은 예외라, 전조를 포함하고 그 앞 평소 구간(52분)을 비교 기준으로 남겼다. 로그가 09:00 부터라 그 이전은 없다.
- 뒤 여유: 마지막 5xx(14:34:45) 뒤로 로그 끝까지 약 5분뿐이다. 회복인지, 요청이 멈춘 것인지 구분할 여유가 부족하다(재배포 POST 1건만 있음). 이후 로그는 없다.
- 배포 이력은 v1.4.2(당일) 외에 v1.4.1 · v1.4.0 · v1.3.9(09-01 ~ 09-14)도 참고로 남긴다. 해당 기간 로그는 없다.

### 1.6 개인정보 · 비밀값이 든 파일

| 파일 | 들어 있는 값 | 줄 수(`grep -c`) |
|---|---|---:|
| `nginx-access.log` | 클라이언트 IP(`10.20.` 대역, 18개) | 20,365 |
| `nginx-access.log` | 쿼리 문자열의 학생 식별자(`?student=***`) | 4,297 |
| `app.log` | 재배포 사유(`reason=`) 안의 학생 식별자 | 73 |
| `deploy-history.md` | 배포자 · 승인자 이메일 | 8 |
| `deploy-history.md` | 배포 명령의 인증 헤더 토큰 | 1 |
| `deploy-history.md` | 배포 · 대기 호스트 IP(`API-1`, `API-2`) | 2 |

- 비밀번호 · DB 접속 문자열은 세 파일 모두 0건이다(마스킹 규칙의 검사 패턴으로 `grep -ci`).

## 2. 타임라인

> 대상: 2026-09-17 **09:00:00 ~ 14:39:58 KST** (1절에서 정한 수집 범위). 세 파일을 KST 한 시간축으로 합쳤다. 변환한 파일은 없다.
> 시각 정밀도: `app.log` 는 ms, `nginx-access.log` 는 초, `deploy-history.md` 는 초 단위다. 파일 사이 몇 초 안의 사건은 **(선후 불확실)** 로 표시했다.
> 반복 메시지는 "첫 발생, 이후 N건"으로 묶었다. 원인 해석은 쓰지 않았다.

### 2.1 시간순 사건

| 시각 (KST) | 출처 (파일:줄) | 사건 요약 | 원문 발췌 (80자 이내) |
|---|---|---|---|
| 09:00:00 | `L/nginx-access.log:1` | `nginx-access.log` 첫 줄 | `10.20.x.x - - [17/Sep/2026:09:00:00 +0900] "GET /api/units/M5-3/items` |
| 09:00:04.878 | `L/app.log:1` | `app.log` 첫 줄 | `INFO … GlobalExceptionHandler : not found: 문항이 없습니다: id=47` |
| 09:52:17 | `L/nginx-access.log:3150`, `L/app.log:194` | **첫 500.** `교사단말A` 의 `POST /api/distributions/41/redistribute`. 이후 11:18:02 까지 `distributions/41` 에서 500 이 **모두 7건**(재배포 POST 6 · 조회 GET 1, 09:52 3건 · 10:31 1건 · 11:17 ~ 11:18 3건). **(선후 불확실 — nginx 와 app 사이)** | `"POST /api/distributions/41/redistribute HTTP/1.1" 500 176` |
| 09:52:17.335 | `L/app.log:195` | 예외 본문. 46건 모두 같은 식별자 값 `9` | ``JpaObjectRetrievalFailureException: Entity `…Assignment` with identifier value `9` `` |
| 09:52:17.335 | `L/app.log:205`, `:206`, `:214` | 스택: `DistributionController.redistribute(:38)` → `DistributionService.redistribute(:51)` → `loadOrThrow(:65)` | `at com.example.assignment.DistributionService.loadOrThrow(DistributionService.java:65)` |
| 10:31:05.909 | `L/app.log:451`, `L/app.log:463` | 조회 GET 에서도 같은 예외. 스택은 `getDistribution(:32)` → `loadOrThrow(:65)` | `ERROR … unhandled exception on /api/distributions/41` |
| 11:18:02.638 | `L/app.log:733` | 전조 구간 마지막 500 (`distributions/41`) | `ERROR … unhandled exception on /api/distributions/41/redistribute` |
| 11:18 ~ 13:41 | `nginx-access.log` · `app.log` (집계) | 5xx · app `ERROR` 0건. 재배포 POST 는 `distributions/42` · `43` · `44` 등에서 200 으로 끝났다 | — |
| 13:24:45 | `L/nginx-access.log:15713`, `L/app.log:1191` | 배포 전 마지막 class 1 리포트 요청. 200. 09:01 ~ 13:24 사이 class 1 리포트 17건 모두 200, 배포 후 class 1 리포트 요청은 0건 | `built class report for class 1: 3 distributions, 10 submissions` |
| 13:38 | `L/deploy-history.md:21` | v1.4.2 배포 승인 | `- 승인: [EMAIL] (13:38)` |
| 13:40:05 | `L/deploy-history.md:7` | **v1.4.2 배포 시작.** 대상 `API-1`. 변경 파일 `DistributionController.java` | `| v1.4.2 | 2026-09-17 13:40:05 | 2026-09-17 13:42:31 | [EMAIL] |` |
| — | `L/deploy-history.md:15`, `:16` | v1.4.2 변경 내용: 재배포 응답 `{distribution}` → `{distribution, history[]}`, `DistributionController.redistribute` 에서 재배포 후 `listByClass(classId)` 호출 추가 | `- 변경 지점: \`DistributionController.redistribute\` 에서 재배포 후 \`Distribut` |
| — | `L/deploy-history.md:18`, `:20` | 배포 방식: 단일 호스트 재기동(무중단 아님), 예상 중단 10초 안팎. 배포 명령은 `API-1` 대상, 인증 헤더 토큰 포함 | `-H "Authorization: Bearer [TOKEN]" -d '{"version":"1.4.2","host":"API-1"}'` |
| 13:41:20 | `L/nginx-access.log:16829`, `L/app.log:1238` | 배포 전 마지막 재배포 성공. `distribution 43 (assignment 6, class 1)` → 200 | `redistributed distribution 43 (assignment 6, class 1) reason=*** 요청 —` |
| 13:41:48.214 | `L/app.log:1240` | Tomcat graceful shutdown 시작 | `GracefulShutdown : Commencing graceful shutdown. Waiting for active requests` |
| 13:41:50.224 | `L/app.log:1244` | 커넥션 풀 종료 완료 | `HikariDataSource : itembank-pool - Shutdown completed.` |
| 13:41:51 | `L/nginx-access.log:16865` | **첫 502.** 이후 13:41:59(`L/nginx-access.log:16872`)까지 **8건**, 경로는 `/` · `/api/units` · `/api/items` · `/api/distributions` 조회. **(선후 불확실 — 앞뒤 app 종료 · 기동 줄과 수 초 차)** | `"GET /api/units/M5-2/items?student=*** HTTP/1.1" 502 157` |
| 13:41:55.902 | `L/app.log:1245` | 새 버전 기동 시작 | `Starting ItemBankApplication v1.4.2 using Java 21.0.4 with PID 1` |
| 13:41:55.906 | `L/app.log:1246` | 활성 프로필 없음, `default` 사용 | `No active profile set, falling back to 1 default profile: "default"` |
| 13:42:00.304 | `L/app.log:1264` | 기동 완료 | `Started ItemBankApplication in 4.921 seconds (process running for 5.488)` |
| 13:42:01 | `L/nginx-access.log:16873` | 기동 뒤 첫 200. 이후 13:46:37 까지 5xx 0건. **(선후 불확실 — 기동 완료 줄과 1초 차)** | `"GET /api/units HTTP/1.1" 200` |
| 13:42:10 | `L/nginx-access.log:16884`, `L/app.log:1266` | 배포 후 첫 재배포 POST. `distribution 7 (class 3)` → 200 | `redistributed distribution 7 (assignment 5, class 3)` |
| 13:42:31 | `L/deploy-history.md:7` | v1.4.2 배포 완료(이력상) | `| 2026-09-17 13:42:31 |` |
| 13:46:38.107 | `L/app.log:1274` | 재배포 성공 INFO. `distribution 42 (assignment 4, class 1)` | `redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` |
| 13:46:38.113 | `L/app.log:1275`, `L/nginx-access.log:17155` | **배포 후 첫 500.** 바로 앞 INFO 와 같은 스레드(`exec-1`), 6ms 뒤. 이후 14:34:45 까지 **500 39건**(`distributions/42` 19 · `43` 20), 전부 `교사단말A`, Referer `…/teacher/distributions/42`·`43`. **(선후 불확실 — nginx 와 app 사이)** | `ERROR … unhandled exception on /api/distributions/42/redistribute` |
| 13:46:38.113 | `L/app.log:1286`, `:1294`, `:1304` | 스택: `DistributionController.redistribute(:40)` → `DistributionService.listByClass(:38)` → `findByClassRoomIdOrderByDistributedAtAsc`. 원인 예외는 식별자 `9` | `at com.example.assignment.DistributionService.listByClass(DistributionService.java:38)` |
| 13:46 ~ 14:34 | `app.log` (집계) | 배포 후 `ERROR` 39건 모두 직전 타임스탬프 줄이 같은 distribution 의 `redistributed distribution …` INFO 다(`awk` 로 ERROR 직전 줄 확인, 39/39). 배포 전 `ERROR` 7건은 직전에 그 INFO 가 없다(0/6, GET 1건 제외) | — |
| 13:50:12.723 | `L/app.log:1396`, `L/nginx-access.log:17365` | `distributions/43` 의 첫 500 | `ERROR … unhandled exception on /api/distributions/43/redistribute` |
| 13:42 ~ 14:39 | `nginx-access.log` (집계) | 배포 후 재배포 POST 결과: class 1(`42` · `43`) 39건 전부 500, class 2 · 3(`5` · `7` · `8` · `44`) 6건 전부 200, `2` · `3` 4건 409 | — |
| 14:34:45 | `L/app.log:2887`, `L/nginx-access.log:20084` | **마지막 500** (`distributions/43`) | `"POST /api/distributions/43/redistribute HTTP/1.1" 500 176` |
| 14:35:26 | `L/nginx-access.log:20119`, `L/app.log:2925` | 마지막 재배포 POST. 409 | `state conflict: 마감된 과제는 재배포할 수 없습니다` |
| 14:39:57.905 | `L/app.log:2938` | `app.log` 마지막 줄 | `INFO … GlobalExceptionHandler : no handler for /favicon.ico` |
| 14:39:58 | `L/nginx-access.log:20365` | `nginx-access.log` 마지막 줄 | `10.20.x.x - - [17/Sep/2026:14:39:58 +0900] "GET /api/units HTTP/1.1" 200` |

**로그에 없는 것** (`grep -nE 'Shutdown|Starting|Started|graceful|rollback'` 등으로 확인):
- 13:41:48 의 종료 · 기동 외에 애플리케이션 재시작 줄이 없다. 롤백(`v1.4.1` 재기동) 기록도 없다.
- `nginx-access.log` · `app.log` 에 배포 · 대기 호스트 IP(`API-1` · `API-2`)가 나오지 않는다. nginx 오류 로그는 이번 묶음에 없다.

### 2.2 재배포 POST 분포 (배포 전 / 후)

명령: `awk '$6=="\"POST"{…}' nginx-access.log` 로 시각(13:42:00 기준) · 단말 · distribution · 상태를 집계.

| distribution | class (`app.log` INFO) | 배포 전 (09:00 ~ 13:41) | 배포 후 (13:42 ~ 14:39) | 요청 단말 |
|---|---|---|---|---|
| 41 | (로그에 표시 없음) | 500 6 | — | `교사단말A` |
| 42 | 1 | 200 5 | **500 19** | `교사단말A` |
| 43 | 1 | 200 13 | **500 20** | `교사단말A` |
| 44 | 2 | 200 17 | 200 1 | `교사단말A` |
| 8 | 2 | 200 8 | 200 1 | `교사단말A` |
| 1 · 2 · 3 | — | 409 31 | 409 4 | `교사단말A` |
| 5 · 6 · 7 | 3 | 200 23 | 200 4 | `교사단말B` |

- 재배포 POST 를 보낸 단말은 2대다(`교사단말A` 125건, `교사단말B` 27건). 500 46건은 전부 `교사단말A` 에서 나왔다.
- `app.log` 의 `redistributed distribution` INFO 는 시간대별 10 · 12 · 10 · 20 · 32 · 27건(09 ~ 14시)이고, 같은 시간 nginx 의 POST 200 은 10 · 12 · 10 · 20 · 18 · 2건이다.

## 3. 가설과 검증

> 3.3 가설 → 3.4 로그 반증(R) → 3.5 코드 · 설정 대조(C) → 3.6 판정 순서다. "유지"는 확정이 아니다.

### 3.1 증상과 원인 구분

- **가장 많이 나온 오류 메시지:** `GlobalExceptionHandler : unhandled exception on /api/distributions/{id}/redistribute` (45건) + 원인 예외 ``FetchNotFoundException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist`` (46건).
- 이 메시지는 **증상**으로 분리한다. 질문은 "왜 존재하지 않는 `Assignment 9` 를 읽게 됐고, 왜 13:46 부터 class 1 재배포마다 그것을 읽게 됐는가"이다.

### 3.2 모든 가설이 함께 설명해야 할 관측

| 번호 | 관측 | 근거 |
|---|---|---|
| O1 | 배포 후 500 39건은 전부 `POST /api/distributions/{42,43}/redistribute`(class 1)이고, 같은 기간 class 2 · 3 재배포 6건은 200 이다 | 2.2 표, `L/nginx-access.log:17155`, `:20084` |
| O2 | 같은 예외(`Assignment 9` 없음)가 **배포 4시간 전** 09:52 ~ 11:18 에도 `distributions/41` 에서 7건 났다. 그때 스택은 `loadOrThrow(:65)` 였고 배포 후는 `listByClass(:38)` 이다 | `L/app.log:194`, `:205`, `:1275`, `:1286` |
| O3 | 배포 후 500 39건은 모두 같은 스레드 · 같은 distribution 의 `redistributed distribution …` INFO 5 ~ 6ms 뒤에 났다(39/39). 배포 전 500 에는 그 INFO 가 없다 | `L/app.log:1274`–`1275`, 2.1 집계 |
| O4 | 컨트롤러 줄 번호가 배포 전 `DistributionController.java:38` → 배포 후 `:40` 으로 바뀌었고, 기동 줄에 `v1.4.2` 가 찍혔다 | `L/app.log:214`, `:1294`, `:1245` |
| O5 | 502 8건은 13:41:51 ~ 13:41:59 에만 있고, 앱 종료(13:41:48) ~ 기동 완료(13:42:00) 사이다. 그 뒤 첫 500 까지 4분 37초 동안 5xx 0건 | `L/nginx-access.log:16865`–`16872`, `L/app.log:1240`, `:1264` |
| O6 | 전체 요청량은 배포 전후 분당 58 ~ 76건으로 하루 범위(40 ~ 82) 안이다. 500 은 전부 단말 1대(`교사단말A`)에서, 2 ~ 4건씩 수십 초 안에 몰려 나왔다 | 1.3, `L/nginx-access.log:17155` 이후 집계 |
| O7 | 배포 전 class 1 의 `distributions/42` · `43` 재배포는 18건 모두 200 이었다(마지막 13:41:20) | `L/nginx-access.log:16829`, `L/app.log:1238` |
| O8 | 13시 · 14시에 `redistributed distribution` INFO 수가 nginx POST 200 수보다 14 · 25건 많다(= 같은 시간 500 수) | 2.2 |
| O9 | 배포 전 `GET /api/classes/1/report` 는 17건 모두 200 이고 `3 distributions` 로 끝났다(전조 직전 09:51:25 · 직후 09:57:26 · 마지막 13:24:45 포함) | `L/app.log:189`, `:333`, `:1191`, `L/nginx-access.log:15713` |

### 3.3 가설 표

| 가설 | 계층 | 내용 | 지지 근거 (`파일:줄번호`) | 반증 조건 (이것이 확인되면 틀림) | 확인 방법 |
|---|---|---|---|---|---|
| **H1** | 코드 (배포된 변경) | v1.4.2 가 재배포 경로에 `listByClass(classId)` 를 추가해, class 1 재배포 때마다 class 1 의 배포 목록을 `Assignment` 와 함께 읽게 됐고, 그 목록에 없는 `Assignment 9` 를 가리키는 행이 있어 조회가 실패한다. 재배포 자체는 그 전에 끝난다 | `L/deploy-history.md:16`, `L/app.log:1274`–`1275`, `:1286`, `:1294`; O1 · O3 · O4 · O7 | ① 배포 후 class 2 · 3 재배포에서도 같은 예외가 난다 ② 배포 후 `ERROR` 중 `listByClass` 를 지나지 않는 것이 있다 ③ 배포 전 class 1 재배포(`42` · `43`)에서도 500 이 있었다 ④ `listByClass` 쿼리가 `Assignment` 를 함께 읽지 않는다(그러면 조회 단계에서 이 예외가 날 수 없다) | R1 · R2 · R3, 코드 대조 C1 ~ C4 |
| **H2** | DB · 데이터 | `distribution` 에 `assignment` 에 없는 id `9` 를 가리키는 행(고아 참조)이 있다. 배포 전에는 그 행을 직접 열 때(`distributions/41`)만, 배포 후에는 그 행이 든 학급 목록을 읽을 때마다 드러난다 | `L/app.log:194`–`195`, `:205`, `:451`; O2 | ① `distributions/41` 이 class 1 이 아니다(그러면 class 1 에 다른 고아 행이 있어야 함) ② 운영 스키마에 `distribution.assignment_id → assignment.id` FK 가 있어 고아 행이 생길 수 없다 ③ `assignment` 에 id 9 가 있다 ④ 같은 class 1 목록 쿼리가 고아 행 없이 성공한 기록이 있다 | R4 · R5 · R6, `db/mariadb/init/` 대조 |
| **H3** | 트래픽 (확대 요인) | 원인이 아니라 **건수 · 부작용 확대 요인**: `교사단말A` 가 500 을 받고 같은 재배포를 사람 손으로 반복했고, 재배포는 매번 실제로 반영됐다 | O3 · O6 · O8, `L/nginx-access.log:17155` 이후 | ① 500 이 여러 단말에 퍼져 있다 ② 연속 500 간격이 일정하다(자동 재시도) ③ 500 이 난 요청에 대응하는 `redistributed` INFO 가 없다 | R7 · R8 |
| **H4** | 인프라 (재기동) | 무중단이 아닌 단일 호스트 재기동이 새 인스턴스를 비정상 상태(커넥션 · 캐시 · 대기 서버 `API-2` 전환 등)로 남겨 500 이 났다 | `L/deploy-history.md:18`, O5 | ① 500 이 특정 경로 · 학급에만 몰린다 ② 배포 전에도 같은 예외가 있었다 ③ 재기동 직후 수 분 동안 5xx 가 0건이다 ④ 기동 · 커넥션 오류 줄이 없다 | R9 · R10, O2 · O5 |
| **H5** | 배포 · 설정 | v1.4.2 와 함께 설정(활성 프로필 `default`, JPA 로딩 설정 등)이 바뀌어 없는 엔티티 참조를 다르게 처리하게 됐다 | `L/app.log:1246` | ① 배포 전 버전에서도 같은 예외가 났다 ② 변경 파일이 `DistributionController.java` 하나뿐이다 ③ 배포 전 기동 로그에도 같은 프로필 줄이 있다 | R1 · R11 · R12 |

### 3.4 반증 시도 (로그)

명령은 모두 `L/` 에서 실행했다. IP 가 나오는 출력은 단말 순번 · 별칭으로 바꿔 적었다.

| R | 확인한 것 | 명령 (요지) | 결과 |
|---|---|---|---|
| R1 | `ERROR` 46건이 처음 지나는 `DistributionService` · `DistributionController` 프레임 | `awk '/ ERROR /{…} want && /DistributionService\./{…}' app.log \| sort \| uniq -c` | 배포 후 39건 **전부** `listByClass(DistributionService.java:38)` · `DistributionController.java:40`. 배포 전 7건 전부 `loadOrThrow(DistributionService.java:65)` · 컨트롤러 `:38`(6) · `:26`(1) |
| R2 | 배포 후 class 2 · 3 재배포 결과 | `awk '$6=="\"POST" && $7~/redistribute/{…}' nginx-access.log` + `grep 'redistributed distribution' app.log` | 배포 후 `44`(class 2) 1 · `8`(class 2) 1 · `5`(class 3) 2 · `7`(class 3) 2 → **6건 모두 200** |
| R3 | 배포 전 class 1 재배포 결과 | R2 와 같은 집계 | 배포 전 `42` 5건 · `43` 13건 → **18건 모두 200**(마지막 13:41:20 `L/nginx-access.log:16829`) |
| R4 | `distributions/41` 의 학급 단서 | `grep -n 'distributions/41' nginx-access.log`, `grep -n 'distribution 41' app.log` | 7건(500) 뿐이고 Referer 는 `…/teacher/distributions/41` · `…/teacher/distributions` 이다. 41 은 로딩 단계에서 실패해 `redistributed` INFO(학급 표시)가 없다 → **41 의 학급은 로그로 알 수 없다** |
| R5 | `Assignment 9` 가 로그 어디에 나오는가 | `grep -c 'Caused by'`, `grep -n 'assignment 9\b' app.log` | 46건 모두 `identifier value 9`. `redistributed … (assignment 9, …)` 줄은 0건 |
| R6 | 같은 class 1 목록 쿼리가 성공한 기록 | `grep -n 'built class report' app.log`, `awk '$7~/^\/api\/classes/' nginx-access.log` | 배포 전 class 1 리포트 **17건 모두 200**, 모두 `3 distributions`. 전조 직전 09:51:25(`L/app.log:189`) · 직후 09:57:26(`L/app.log:333`) · 마지막 13:24:45(`L/app.log:1191`). 배포 후 class 1 리포트 요청 0건 |
| R7 | 500 · 재배포 POST 의 단말 분포와 간격 | `awk '$9==500{print $1}' \| sort \| uniq -c`, 배포 후 연속 500 간격 집계 | 500 46건은 단말 1대(`교사단말A`). 재배포 POST 단말은 2대(125 · 27). 배포 후 500 은 **13묶음**(30초 안 기준), 묶음당 1 ~ 6건, 묶음 안 간격 1 ~ 25초로 들쭉날쭉, 묶음 사이 2 ~ 5.5분 |
| R8 | 500 마다 재배포가 실제로 실행됐는가 | `awk` 로 `ERROR` 직전 타임스탬프 줄 · 스레드 비교 | 배포 후 **39/39** 이 같은 스레드의 `redistributed distribution …` INFO 바로 뒤. 배포 전 0/7 |
| R9 | 배포 후 5xx 경로 | `awk '$9>=500{…}' nginx-access.log` (13:42 이후) | `/api/distributions/{id}/redistribute` 39건뿐. 다른 경로 5xx 0건 |
| R10 | 기동 · 커넥션 오류 | `grep -ciE 'Connection refused\|HikariPool.*(timeout\|Exception)\|Failed to\|APPLICATION FAILED\|Exception in thread' app.log` | 29건 모두 `Failed to convert … "NaN"/"null"/"undefined" (/api/items/…)` (400). 기동 · 커넥션 오류 0건 |
| R11 | 기동 · 프로필 줄 | `grep -nE 'Starting ItemBank\|No active profile' app.log` | `L/app.log:1245`–`1246` 한 번뿐. 배포 전 버전의 기동 줄은 이번 로그에 없다 |
| R12 | v1.4.2 변경 파일 | `L/deploy-history.md:7` | `DistributionController.java` 하나 |

### 3.5 코드 · 설정 대조

로그의 클래스 · 메서드 · 줄 번호를 현재 저장소 코드와 맞췄다(배포된 v1.4.2 와 저장소가 같다는 전제는 줄 번호 일치로만 확인했다).

| C | 로그 | 코드 · 설정 `파일:줄번호` | 대조 결과 |
|---|---|---|---|
| C1 | `DistributionController.redistribute(DistributionController.java:40)` (`L/app.log:1294`) | `modern/api/src/main/java/com/example/assignment/DistributionController.java:38`(재배포 호출) · `:39`(v1.4.2 주석) · `:40`(`listByClass(redistributed.classId())`) | 일치. 배포 전 스택의 `:38`(`L/app.log:214`)은 재배포 호출 줄 |
| C2 | `DistributionService.listByClass(DistributionService.java:38)` (`L/app.log:1286`), `findByClassRoomIdOrderByDistributedAtAsc` (`L/app.log:1285`) | `DistributionService.java:36`(`@Transactional(readOnly = true)`) · `:38`(리포지토리 호출); `DistributionRepository.java:13`–`14`(`@EntityGraph(attributePaths = {"assignment", "classRoom"})`) | 목록 쿼리가 `assignment` 를 함께 읽는다. 예외는 결과 행 처리 중(`EntityInitializerImpl.resolveInstance`, `L/app.log:1306`) → H1 반증 ④ 해당 없음 |
| C3 | 재배포 INFO 뒤 500 (R8) | `DistributionService.java:49`(`@Transactional` 재배포) · `:55`(`markRedistributed`) · `:56`(INFO 로그); `application.yml:22`(`open-in-view: false`) | 재배포와 목록 조회가 **서로 다른 트랜잭션**이다. 재배포는 컨트롤러로 돌아오기 전에 커밋되고, 그 뒤 목록 조회가 실패한다 |
| C4 | `unhandled exception on …` → 500 | `modern/api/src/main/java/com/example/common/GlobalExceptionHandler.java:53`–`55` | `Exception` 처리기가 `ERROR` 로 기록하고 500 으로 바꾼다. 전용 처리 없음 |
| C5 | `loadOrThrow(DistributionService.java:65)` (`L/app.log:205`) | `DistributionService.java:65`(`findWithDetailsById`), `DistributionRepository.java:10`–`11`(같은 `@EntityGraph`) | 전조 7건은 41 한 행을 `Assignment` 와 함께 읽다 실패한 것 |
| C6 | class 1 리포트 성공 (R6) | `modern/api/src/main/java/com/example/assignment/ReportService.java:46` | 리포트도 **같은** `findByClassRoomIdOrderByDistributedAtAsc(classId)` 를 쓴다 |
| C7 | 고아 참조 | `db/mariadb/init/01-schema.sql:101`(`fk_distribution_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id)`), `02-seed.sql:119`–`125`(assignment id 1 ~ 6), `:130`–`138`(distribution id 1 ~ 8) | 저장소 스키마로 만든 DB 에서는 고아 행이 생길 수 없고, 시드에는 id 9 · 41 ~ 44 가 없다 |
| C8 | 배포 전 테스트 "24 tests 통과" (`L/deploy-history.md:17`) | `modern/api/src/test/java/com/example/assignment/` (`DistributionServiceTest.java` · `ReportServiceTest.java` 뿐) | `listByClass` 테스트, `DistributionController` 테스트가 없다. 새 응답 형식(`history`)을 검증하는 테스트가 없다 |

### 3.6 판정

| 가설 | 반증 조건 결과 | 판정 |
|---|---|---|
| **H1** | ① R2: class 2 · 3 6건 모두 200 — 해당 없음 ② R1: 39/39 `listByClass` 경유 — 해당 없음 ③ R3: 배포 전 18/18 200 — 해당 없음 ④ C2: `assignment` 를 함께 읽음 — 해당 없음 | **유지 → 채택(직접 계기)** |
| **H2** | ① R4: 41 의 학급 알 수 없음 — 판단 불가 ② C7: 저장소 스키마에는 FK 가 있음 — 운영 스키마는 확인 못 함(운영 DB 미접속) ③ R5: 예외 본문이 "id 9 없음"을 직접 말함 — 해당 없음 ④ **R6: 같은 class 1 목록 쿼리가 13:24:45 까지 17번 성공 — 반증 조건에 걸린다** | **부분 유지.** "class 1 목록 결과에 `Assignment 9` 를 가리키는 행이 있다"는 배포 후(13:46:38 ~) 사실로 유지. "그 행이 처음부터(전조 때부터) class 1 에 있었다"는 R6 과 충돌해 **판단 불가**. 행의 정체 · 생긴 경위도 판단 불가 |
| **H3** | ① R7: 단말 1대 — 해당 없음 ② R7: 간격 1 ~ 25초 들쭉날쭉, 13묶음 — 자동 재시도로 보기 어려움 ③ R8: 39/39 INFO 있음 — 해당 없음 | **유지 → 채택(확대 요인)** |
| **H4** | ① R9 · O1: class 1 재배포에만 몰림 — 걸림 ② O2: 배포 전에도 같은 예외 — 걸림 ③ O5: 재기동 뒤 4분 37초 5xx 0 — 걸림 ④ R10: 기동 · 커넥션 오류 0 — 걸림 | **기각.** 502 8건(13:41:51 ~ 59)만 재기동으로 설명된다(예고된 중단, `L/deploy-history.md:18`) |
| **H5** | ① O2 · R1: v1.4.1 시절에도 같은 예외 — 걸림 ② R12: 변경 파일 하나 — 걸림 ③ R11: 비교할 이전 기동 줄 없음 — 판단 불가 | **기각** (①②로 충분) |

**채택 가설로도 설명되지 않는 것** (9절에 다시 적는다)
- R6: class 1 리포트가 같은 쿼리로 13:24:45 까지 성공했다. H1 · H2 대로라면 고아 행은 13:24:45 ~ 13:46:38 사이에 class 1 목록에 들어왔거나, 리포트와 재배포 이력이 다른 데이터를 봤어야 한다. 로그에 그 사이 쓰기 기록은 재배포 INFO(`assignment_id` 를 바꾸지 않음, `DistributionService.java:55`) 외에 없다.
- 리포트의 `3 distributions` 는 class 1 에 `42` · `43` 이 있다는 INFO 와 함께 보면 class 1 에 그 외 행이 많아야 1개라는 뜻이다. 이 수치가 맞는지 로그만으로 확인할 수 없다.

## 4. 원인

### 4.1 직접 원인 사슬

1. 운영 DB 의 `distribution` 에 `assignment` 에 없는 id `9` 를 가리키는 행이 있다(9 ~ 14시 예외 46건 모두 `Assignment … 9 does not exist`). 이 행은 09:52 ~ 11:18 에 `distributions/41` 을 직접 열 때 7번 드러났다.
2. 13:40:05 ~ 13:42:31 v1.4.2 배포로 `POST /api/distributions/{id}/redistribute` 가 재배포 뒤 `listByClass(classId)` 를 부르게 됐다(`DistributionController.java:40`).
3. `listByClass` 는 학급의 배포 목록을 `Assignment` 와 함께 읽는다(`DistributionRepository.java:13`–`14`). 13:46:38 부터 class 1 목록 결과에 1 의 행이 들어 있어 매번 `FetchNotFoundException` → `GlobalExceptionHandler` → 500.
4. 재배포(별도 트랜잭션)는 이미 커밋된 뒤라, 교사 화면은 실패를 받고도 데이터는 바뀌었다(39/39).

### 4.2 배경 원인

- **데이터 무결성:** 저장소 스키마는 FK 로 고아 행을 막지만(`01-schema.sql:101`), 운영 데이터에는 고아 참조가 있다. 운영 스키마가 저장소와 다르거나 FK 를 우회한 쓰기가 있었을 수 있다(확인 못 함). 같은 DB 를 레거시 PHP · Thymeleaf 모듈도 쓴다(CLAUDE.md 4절).
- **전조 방치:** 같은 예외가 배포 4시간 전에 7번 났지만 경보 · 조치 기록이 없다.
- **검증 범위:** 배포 전 확인은 단위 테스트(컨트롤러 · `listByClass` 테스트 없음, C8)와 시드 데이터 스테이징 3건(`L/deploy-history.md:17`)뿐이다. 시드에는 고아 행 · 41 ~ 44 가 없다(C7).
- **트랜잭션 경계:** 쓰기(재배포)와 응답용 읽기(이력)가 다른 트랜잭션이라, 읽기 실패가 "실패 응답 + 쓰기 반영"을 만든다(C3).
- **배포 시점 · 방식:** 수업 중 반영 요청(`L/deploy-history.md:14`)으로 수업 시간에 무중단이 아닌 방식으로 배포했고, 롤백 계획(`:22`, 약 3분)이 있었지만 48분 동안 실행 기록이 없다.

## 5. 근거 로그 줄

| 원인 문장 | 근거 `파일:줄번호` | 발췌 |
|---|---|---|
| 고아 참조 `Assignment 9` (전조) | `L/app.log:194`, `:195` | `unhandled exception on /api/distributions/41/redistribute` / ``Entity `…Assignment` with identifier value `9` does not exist`` |
| 전조는 단건 로딩에서 실패 | `L/app.log:204`, `:205` | `$Proxy141.findWithDetailsById(Unknown Source)` / `DistributionService.loadOrThrow(DistributionService.java:65)` |
| 전조 단말 · 경로 | `L/nginx-access.log:3150` | `"POST /api/distributions/41/redistribute HTTP/1.1" 500 176` |
| v1.4.2 변경 지점 | `L/deploy-history.md:16` | `` `DistributionController.redistribute` 에서 재배포 후 `DistributionService.listByClass`… `` |
| 새 버전 기동 | `L/app.log:1245` | `Starting ItemBankApplication v1.4.2 using Java 21.0.4 with PID 1` |
| 재배포는 성공 후 커밋 | `L/app.log:1274` | `redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` |
| 6ms 뒤 같은 스레드에서 500 | `L/app.log:1275` | `ERROR … [nio-8080-exec-1] … unhandled exception on /api/distributions/42/redistribute` |
| 실패 지점은 학급 목록 조회 | `L/app.log:1285`, `:1286`, `:1294` | `$Proxy141.findByClassRoomIdOrderByDistributedAtAsc` / `listByClass(DistributionService.java:38)` / `DistributionController.java:40` |
| 원인 예외 · 행 처리 중 | `L/app.log:1304`, `:1306` | `Caused by: org.hibernate.FetchNotFoundException: Entity …Assignment… value 9` / `EntityInitializerImpl.resolveInstance` |
| 배포 후 첫 · 마지막 500 | `L/nginx-access.log:17155`, `:20084` | `"POST /api/distributions/42/redistribute HTTP/1.1" 500 176` / `…/43/redistribute … 500 176` |
| 배포 직전 같은 경로 성공 | `L/nginx-access.log:16829`, `L/app.log:1238` | `"POST /api/distributions/43/redistribute HTTP/1.1" 200 183` |
| 재기동 중 502 (예고된 중단) | `L/nginx-access.log:16865`, `L/app.log:1240`, `:1264` | `… 502 157` / `Commencing graceful shutdown.` / `Started ItemBankApplication in 4.921 seconds` |

## 6. 수정안 (코드는 고치지 않음)

| # | 파일 · 대상 | 무엇을 어떻게 | 비고 |
|---|---|---|---|
| F1 | 운영 배포 | **즉시:** v1.4.1 로 롤백(`L/deploy-history.md:22`). 재배포 경로가 다시 `listByClass` 를 부르지 않아 class 1 재배포 500 이 멈춘다 | `distributions/41` 의 500(전조)은 롤백으로 안 없어진다 |
| F2 | 운영 DB (`distribution` · `assignment`) | DBA 가 고아 행을 조회한다: `SELECT d.id, d.class_id, d.assignment_id FROM distribution d LEFT JOIN assignment a ON a.id = d.assignment_id WHERE a.id IS NULL;` 와 `SHOW CREATE TABLE distribution;` 을 `01-schema.sql:101` 과 비교. 결과에 따라 `assignment 9` 복구 또는 해당 행 정리 | 운영 DB 작업은 이 리포트 범위 밖. 스키마 · 데이터 변경은 CLAUDE.md 3절 승인 절차를 따른다 |
| F3 | `DistributionService.java` · `DistributionController.java:38`–`40` | 재배포와 이력 조회를 서비스의 한 `@Transactional` 메서드(예: `redistributeWithHistory`)로 묶고 컨트롤러는 그것만 부른다. 이력 조회가 실패하면 재배포도 롤백돼 "500 인데 반영됨"이 없어진다 | 고아 행이 있으면 여전히 500 이다 — F2 와 함께 |
| F4 | `modern/api/src/test/java/com/example/assignment/` | `DistributionController` `@WebMvcTest`(재배포 응답의 `history` 형식), `listByClass` Mockito 테스트, `findByClassRoomIdOrderByDistributedAtAsc` `@DataJpaTest` 추가 | CLAUDE.md 2절 테스트 규칙. 새 엔드포인트 동작을 테스트 없이 배포한 공백(C8)을 메운다 |
| F5 | 고아 행을 무시하는 우회(`@NotFound(action = IGNORE)`, 내부 조인으로 행 제외 등) | **권하지 않는다.** 응답에서 배포 행이 조용히 빠져 데이터 문제를 숨긴다 | 대안으로만 적는다 |
| F6 | 교사 화면(재배포 버튼) | 500 을 받으면 "재배포가 반영됐을 수 있음"을 알리고, 재시도 전에 배포 상태를 다시 조회한다 | 교사 화면 코드가 이 저장소에 있는지는 확인하지 않았다 |

## 7. 재발 방지

| 장치 | 막는 것 |
|---|---|
| 운영 · 스테이징 · 저장소 스키마 일치 점검(FK 존재 여부 포함)을 배포 파이프라인에 넣는다 | FK 없이 운영돼 고아 행이 생기는 것 |
| 주기적 무결성 점검 쿼리(F2 의 LEFT JOIN, 0건이 아니면 경보) | 고아 행이 수 시간 방치되는 것(이번 전조 4시간) |
| 배포 전 스모크: 마스킹한 운영 데이터 사본에서 변경된 경로를 **모든 학급**에 대해 호출 | 시드 데이터에서만 통과하는 검증(C7 · C8) |
| 배포 후 10분 관찰 창 + 자동 롤백 기준(변경 엔드포인트 5xx ≥ 1) | 48분 동안 롤백 없이 방치되는 것 |
| 쓰기와 응답용 읽기를 한 트랜잭션으로(F3), 재배포 요청에 멱등 키 | 실패 응답 뒤 반복 재시도로 같은 쓰기가 여러 번 반영되는 것(39건) |
| 수업 시간 배포 금지 또는 무중단(대기 호스트 `API-2` 활용) 배포 | 수업 중 502 · 장애 노출 |
| 리뷰 체크리스트: "응답에 새 조회를 붙이면, 그 조회가 실패할 데이터가 운영에 있는가" | 기존 데이터 결함을 새 경로가 매 요청 드러내는 변경 |

## 8. 모니터링 항목

| 지표 | 임계값 | 평소 | 이번에 울렸을 시각 (KST) | 근거 |
|---|---|---|---|---|
| nginx 500 건수, 경로별 5분 합 (배포 창 제외) | ≥ 1 | 0 (09:00 ~ 09:52, 11:18 ~ 13:46) | **09:52:17**(전조), **13:46:38** | `L/nginx-access.log:3150`, `:17155` |
| app `ERROR … unhandled exception` 건수, 5분 합 | ≥ 1 | 0 (같은 구간) | 09:52:17, 13:46:38 | `L/app.log:194`, `:1275` |
| app 로그 `FetchNotFoundException` · `does not exist` 문자열 | ≥ 1 이면 즉시 (데이터 무결성 경보) | 0 | 09:52:17 | `L/app.log:195` |
| 배포 후 10분 안 변경 엔드포인트(`/redistribute`) 5xx | ≥ 1 이면 롤백 판단 | 해당 없음(배포 시점 지표) | 13:46:38 (배포 완료 13:42:31 + 4분) | `L/deploy-history.md:7`, `L/nginx-access.log:17155` |
| 시간당 `redistributed distribution` INFO 수 − nginx 재배포 POST 2xx 수 | ≠ 0 | 0 (09 ~ 12시 INFO 10·12·10·20건 = 2xx 10·12·10·20건) | 13:46:38 (13시 차이 14, 14시 25) | 2.2, `L/app.log:1274` |
| 같은 단말 · 같은 엔드포인트 5xx 1분 내 연속 | ≥ 3 | 0 | 09:52:41 (전조 세 번째) | `L/nginx-access.log:3174` |
| nginx 502, 배포 창 밖 | ≥ 1 | 0 | 이번에는 울리지 않음(13:41:51 ~ 59 은 배포 창 안, 예고된 중단) | `L/nginx-access.log:16865`–`16872` |
| 무결성 점검 쿼리(고아 `distribution` 행 수) | ≥ 1 | 기대값 0 (실측 없음) | 확인 못 함 — 운영 DB 미접속 | 7절 |

## 9. 확인하지 못한 것

**판단하지 못한 부분**
- 고아 행의 정체: `distributions/41` 이 `Assignment 9` 를 가리키는 것은 전조 스택(C5)으로 확인했지만, 41 의 학급과 배포 후 class 1 목록에서 걸린 행이 41 인지는 로그에 없다(R4).
- R6 과의 충돌: class 1 리포트는 같은 쿼리(`ReportService.java:46`)로 13:24:45 까지 17번 성공했다. 고아 행이 13:24:45 ~ 13:46:38 사이에 class 1 목록에 들어왔는지(예: 같은 DB 를 쓰는 레거시 모듈의 쓰기), 리포트 로그의 `3 distributions` 가 실제와 다른지 판단하지 못했다.
- 운영에서 FK 가 있는데도 고아 행이 있는 이유(운영 스키마 미확인).
- 14:34:45 이후 5xx 0건이 회복인지, 요청이 멈춘 것인지: 그 뒤 재배포 POST 는 1건(14:35:26, 409, `L/nginx-access.log:20119`)뿐이고 롤백 기록이 없다. 14:39:58 이후 상태는 모른다.
- 500 39건에 대응해 실제로 반영된 재배포 39건의 부작용(학생 알림 · 마감 계산 등).

**없었던 자료**
- 운영 DB 스키마 · `distribution`/`assignment` 데이터(운영 DB 는 접속하지 않음), DB 감사 · binlog.
- nginx `error.log`, upstream 주소(`$upstream_addr`), nginx ↔ app 요청 ID 상관값.
- v1.4.1 시절의 기동 로그 · 설정, 배포 파이프라인 로그, 롤백 판단 기록, 교사 화면 클라이언트 로그.
- 레거시 모듈(같은 DB)의 쓰기 로그.

**다음을 위해 남길 것**
- nginx · app 에 공통 요청 ID 를 찍고, nginx `error.log` 와 `$upstream_addr` 를 함께 보관한다.
- 배포 이력에 "배포 후 관찰 결과 · 롤백 판단 시각"을 적는다.
- 7절 무결성 점검 쿼리 결과를 장애 묶음에 함께 남긴다.

---

마스킹 적용(첫머리 별칭 설명 줄 제외): IP 27건(역할 별칭 25 [API-1 5 · API-2 4 · 교사단말A 14 · 교사단말B 2] · 10.20.x.x 2) · 이메일 2건 · 학생 식별자 3건 · 토큰 · 세션 ID 1건 · 비밀번호 · 접속 문자열 0건
