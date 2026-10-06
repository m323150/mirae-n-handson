# RCA: b-deploy-5xx

> **작성 범위(이번 회차):** 1 수집 범위 · 2 타임라인 · 3 가설과 검증 중 **가설 표(3.3)까지**만 썼다. 반증 실행(R 번호) · 코드 대조 · 판정, 요약, 4절 이후(원인 · 근거 · 수정안 · 재발 방지 · 모니터링 · 확인하지 못한 것)는 이번 회차에서 하지 않았다. 요약은 판정 뒤에 쓴다.

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
| `deploy-history.md` | 배포 명령의 `Authorization: Bearer …` 토큰 | 1 |
| `deploy-history.md` | 배포 · 대기 호스트 IP(`API-1`, `API-2`) | 2 |

- 비밀번호 · 접속 문자열(`password` · `jdbc:`)은 세 파일 모두 0건이다.

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

> 이번 회차는 가설 표(3.3)까지다. 반증 실행 · 판정 열은 비워 두었다.

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

### 3.3 가설 표

| 가설 | 계층 | 내용 | 지지 근거 (`파일:줄번호`) | 반증 조건 (이것이 확인되면 틀림) | 확인 방법 (명령 · 코드 위치) | 판정 |
|---|---|---|---|---|---|---|
| **H1** | 코드 (배포된 변경) | v1.4.2 가 `DistributionController.redistribute` 에서 재배포 뒤 `DistributionService.listByClass(classId)` 를 새로 부르게 했고, class 1 의 배포 목록을 읽을 때 존재하지 않는 `Assignment 9` 를 참조하는 행을 함께 읽어 예외가 난다. 재배포 자체는 그 전에 끝난다. | `L/deploy-history.md:16`, `L/app.log:1274`–`1275`, `:1286`, `:1294`; O1 · O3 · O4 · O7 | ① 배포 후 class 2 · 3 재배포에서도 같은 예외가 나온다 ② 배포 후 `ERROR` 스택 중 `listByClass` 를 지나지 않는 것이 있다 ③ 배포 전 class 1 재배포(`42` · `43`)에서도 500 이 있었다 ④ 코드의 `listByClass` 가 다른 class 행을 읽지 않는다 | `awk` 로 배포 후 `ERROR` 39건의 프레임 · distribution · class 대조; `modern/api/src/main/java/com/example/assignment/DistributionController.java` `redistribute`, `DistributionService.java:38` `listByClass`, Repository `findByClassRoomIdOrderByDistributedAtAsc` 와 `Distribution → Assignment` 연관 매핑 | (이번 회차 미실시) |
| **H2** | DB · 데이터 | `distribution` 중 적어도 1행(`distributions/41` 로 추정되는 행)이 `assignment` 에 없는 id `9` 를 가리키는 고아 참조다. 배포 전에는 그 행을 직접 열 때만 터졌고, 배포 후에는 같은 class 목록을 읽을 때마다 터진다. | `L/app.log:194`–`195`, `:205`, `:451`; O2 | ① `distributions/41` 의 class 가 1 이 아니다(그러면 class 1 목록에 `Assignment 9` 를 가리키는 다른 행이 있어야 함) ② 스키마에 `distribution.assignment_id → assignment.id` FK 가 있어 고아 행이 생길 수 없다 ③ `assignment` 에 id 9 가 존재한다 | `db/mariadb/init/` 의 스키마 · 시드에서 FK · id 9 확인(운영 DB 접속 안 함); `L/app.log` · `L/nginx-access.log` 에서 `distributions/41` 의 class 단서 검색 | (이번 회차 미실시) |
| **H3** | 트래픽 (클라이언트 재시도) | 오류 원인이 아니라 **건수 확대 요인**으로서: `교사단말A` 가 500 을 받고 같은 재배포를 반복해 500 이 39건으로 늘었고, 재배포는 매번 실행됐다. | O3 · O6 · O8, `L/nginx-access.log:17155` 이후 | ① 500 이 여러 단말에 고르게 퍼져 있다 ② 같은 distribution 의 연속 500 사이 간격이 사람 재시도로 보기 어렵게 일정하다(자동 재시도) — 이 경우 H3 은 "사람 재시도"에서 "클라이언트 자동 재시도"로 바뀐다 ③ 500 이 난 요청에 대응하는 `redistributed` INFO 가 없다 | `awk` 로 `교사단말A` 의 POST 간격 · distribution 별 연속 횟수 집계; `app.log` 의 INFO ↔ ERROR 쌍 수 대조 | (이번 회차 미실시) |
| **H4** | 인프라 (배포 과정 · 재기동) | 무중단이 아닌 단일 호스트 재기동이 새 인스턴스를 비정상 상태(커넥션 · 캐시 · 대기 서버 `API-2` 전환 등)로 남겨 500 이 났다. | `L/deploy-history.md:18`, O5 | ① 500 이 특정 경로 · class 에만 몰리고 다른 경로는 정상이다(O1) ② 배포 전에도 같은 예외가 있었다(O2) ③ 재기동 직후 4분 37초 동안 5xx 가 0건이다(O5) | 배포 후 경로별 5xx 집계; `app.log` 에서 커넥션 · 풀 · 기동 오류 줄 검색 | (이번 회차 미실시) |
| **H5** | 배포 · 설정 | v1.4.2 와 함께 설정이 바뀌어(활성 프로필 `default`, JPA 연관 로딩 방식 등) 없는 엔티티 참조 처리 방식이 달라졌다. | `L/app.log:1246` (`No active profile set`) | ① 배포 전 버전에서도 같은 `FetchNotFoundException` 이 났다(O2) ② 배포 전 기동 로그에도 같은 프로필 줄이 있다 ③ 변경 파일이 `DistributionController.java` 하나뿐이다(`L/deploy-history.md:7`) | 배포 이력의 변경 파일 목록과 `application.yml` 비교; 이전 기동 로그가 있으면 프로필 줄 비교(이번 묶음에는 없음) | (이번 회차 미실시) |

- H1 과 H2 는 배타적이지 않다(H2 가 조건, H1 이 그 조건을 매 요청에 드러낸 변경일 수 있다). H3 은 원인이 아니라 확대 요인 가설이다. 관계 정리와 판정은 다음 회차(3.4 이후)에서 한다.

---

마스킹 적용(첫머리 별칭 설명 줄 제외): IP 26건(역할 별칭 23 · 10.20.x.x 3) · 이메일 2건 · 학생 식별자 3건 · 토큰 · 세션 ID 1건 · 비밀번호 · 접속 문자열 0건
