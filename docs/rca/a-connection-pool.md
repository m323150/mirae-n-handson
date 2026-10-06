# RCA: a-connection-pool

## 요약

- **무엇이 · 언제:** 2026-09-16 **14:40:52 ~ 14:51:47 KST**(약 11분) 동안 `modern/api` 의 모든 경로가 500 · 502 · 504 를 냈다(5xx 186건). 전조(leak 경고)는 14:37:52 부터 있었다.
- **직접 원인:** 학급 리포트(`GET /api/classes/{id}/report`)가 `@Transactional` 안에서 CPU 서명 루프를 도는 동안 DB 커넥션을 15 ~ 21 초씩 쥐었다. 리포트 요청이 분당 0 ~ 2건에서 10 ~ 19건으로 늘자 커넥션 풀 5개가 리포트에 모두 묶였고, 다른 요청은 커넥션을 얻지 못했다.
- **확대 요인:** nginx 가 시간 초과 · upstream 차단으로 응답을 끊는 동안에도 앱은 리포트 처리를 계속했다. 예비 upstream(`API-2`)은 연결을 거부했다.
- **확신 수준: 중간.** 로그 집계 3종과 코드가 같은 결론을 가리키지만, 사고 중 보유 시간이 평소보다 길었던 이유(CPU 경합 여부)는 지표가 없어 확인하지 못했다(9절).

대상 로그: `incident-logs/a-connection-pool/` (2026-09-16 하루치)

> 마스킹 별칭: `API-1` = 응답한 API 서버, `API-2` = 연결을 거부한 예비 API 서버, `교사단말A` ~ `교사단말D` = 리포트 요청을 보낸 교사 단말 4대. 그 밖의 IP 는 `10.20.x.x` 로 적었다.

## 1. 수집 범위

> 이 절은 범위 확정만 다룬다. 원인 추정은 하지 않는다.
> 파일은 통째로 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk`/`cut` 집계로만 확인했다.

### 1.1 파일별 기록 기간 · 형식

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 (예) | 타임존 |
|---|---:|---|---|---|
| `app.log` | 5,371 (타임스탬프 줄 1,927 + 스택트레이스 연속 줄 3,444) | 06:43:56.753 ~ 15:29:51.760 | ISO-8601 `2026-09-16T06:43:56.753+09:00` | `+09:00` 명시 (1,927줄 전부) |
| `nginx-access.log` | 21,086 | 06:43:56 ~ 15:29:59 | `[16/Sep/2026:06:43:56 +0900]` | `+0900` 명시 |
| `nginx-error.log` | 168 | 09:12:03 ~ 14:51:47 | `2026/09/16 09:12:03` | **표기 없음** → KST 로 확인 (아래) |
| `mariadb-slow.log` | 21 (3건) | 09:14:09 ~ 14:37:52 (`# Time` 기준) | `# Time: 260916  5:37:52` + `SET timestamp=1789537062;` | **UTC** (아래) |

#### KST 변환 규칙

- `app.log`, `nginx-access.log`: 오프셋이 이미 +09:00 이므로 그대로 쓴다.
- `nginx-error.log`: 오프셋 표기가 없다. `nginx-error.log` 마지막 줄 `14:51:03 upstream timed out … GET /api/classes/3/report` 와 `nginx-access.log:19351` 의 `[16/Sep/2026:14:51:03 +0900] "GET /api/classes/3/report" 504` 가 같은 초 · 같은 클라이언트 · 같은 요청이므로 **KST 로 간주, 변환 없음**.
- `mariadb-slow.log`: `# Time: YYMMDD H:MM:SS` 는 **UTC** 이고 쿼리 **종료** 시각이다. **KST = `# Time` + 9시간.** `SET timestamp=<epoch>` 는 쿼리 **시작** 시각(epoch 초)이며 `TZ=Asia/Seoul date -d @<epoch>` 로 변환한다. 검증: `1789537062` → 14:37:42 KST, + `Query_time 9.81s` ≈ `# Time 5:37:52` + 9h = 14:37:52 KST.

| slow log 항목 | `# Time` (UTC) | 종료 KST | 시작 KST (`SET timestamp`) | Query_time |
|---|---|---|---|---:|
| 1 (`mariadb-slow.log:1`) | 0:14:09 | 09:14:09 | 09:14:07 | 2.32 s |
| 2 (`mariadb-slow.log:8`) | 2:02:42 | 11:02:42 | 11:02:40 | 2.04 s |
| 3 (`mariadb-slow.log:15`) | 5:37:52 | 14:37:52 | 14:37:42 | 9.81 s |

### 1.2 "이상" 줄 정의

| 파일 | 이상으로 센 줄 | 제외한 줄 |
|---|---|---|
| `app.log` | 레벨 `WARN` · `ERROR` 인 타임스탬프 줄 | `INFO`(404 · 400 처리 로그 등), 스택트레이스 연속 줄, `WARN … state conflict`(409, 아래 참고) |
| `nginx-access.log` | 상태 코드 5xx (500 · 502 · 504) | 4xx |
| `nginx-error.log` | `[warn]` · `[error]` | `[info]` 3건 |
| `mariadb-slow.log` | 3건 전부 | — |

- `app.log` 의 `WARN … state conflict`(409) 20건은 08:41 ~ 14:52 동안 20~60분에 1건꼴로 고르게 나타나는 **평소 수준**이라 집계에서 뺐다(장애 구간 안의 14:42 · 14:50 · 14:51 · 14:52 각 1건도 같은 빈도).
- 오류 유형별 건수(하루 전체):
  - `app.log`: `ERROR` 98 (`Connection is not available, request timed out` 49, `unhandled exception on …` 49), `WARN` 203 (`Connection leak detection triggered` 134, `SQL Error … SQLState: null` 49, `state conflict` 20)
  - `nginx-access.log`: 500 49, 502 82, 504 55 (참고: 404 1,250, 400 25, 409 20)
  - `nginx-error.log`: `no live upstreams` 68, `upstream timed out (110)` 55, `upstream server temporarily disabled` 26, `connect() failed (111: Connection refused)` 14, `upstream response is buffered to a temporary file` 2

### 1.3 분 단위 이상 건수 (KST)

이상 건수가 0인 분은 생략. 06:43 ~ 14:36 사이에는 아래 "평소 수준" 외 이상 줄이 없다.

| 분 | app WARN/ERROR (409 제외) | access 5xx (500/502/504) | nginx-error warn/error | slow log |
|---|---:|---:|---:|---|
| 09:12 | 0 | 0 | 1 (buffered to temp file) | |
| 09:14 | 0 | 0 | 0 | 1 (2.3 s) |
| 11:02 | 0 | 0 | 0 | 1 (2.0 s) |
| 13:05 | 0 | 0 | 1 (buffered to temp file) | |
| 14:37 | 1 | 0 | 0 | 1 (9.8 s, 종료 14:37:52) |
| 14:38 | 2 | 0 | 0 | |
| 14:39 | 3 | 0 | 0 | |
| 14:40 | 17 | 1 (1/0/0) | 0 | |
| 14:41 | 31 | 9 (6/0/3) | 3 | |
| 14:42 | 16 | 20 (2/13/5) | 20 | |
| 14:43 | 44 | 17 (10/2/5) | 8 | |
| 14:44 | 17 | 13 (2/5/6) | 15 | |
| 14:45 | 33 | 27 (8/14/5) | 22 | |
| 14:46 | 24 | 26 (4/16/6) | 25 | |
| 14:47 | 24 | 14 (5/6/3) | 10 | |
| 14:48 | 22 | 15 (4/6/5) | 15 | |
| 14:49 | 33 | 23 (7/8/8) | 20 | |
| 14:50 | 11 | 18 (0/11/7) | 22 | |
| 14:51 | 3 | 3 (0/1/2) | 3 | |
| 14:52 ~ 15:29 | 0 | 0 | 0 | |

경계 시각(초 단위):

| 신호 | 첫 줄 | 마지막 줄 |
|---|---|---|
| app `WARN` (`Connection leak detection triggered`) | 14:37:52.199 (`app.log:1243`) | 14:51:37.674 (`app.log:5251`) |
| app `ERROR` | 14:40:52.319 | 14:49:51.553 |
| access 5xx | 14:40:52 (500) | 14:51:47 (504) |
| nginx-error warn/error (14시대) | 14:41:06 | 14:51:47 |
| slow log 9.8 s 쿼리 | 시작 14:37:42 | 종료 14:37:52 |

참고 지표:

- 요청량: `nginx-access.log` 07:00 ~ 14:29 평균 약 40건/분, 14:30 ~ 15:00 34 ~ 58건/분으로 장애 구간에도 요청량 자체는 평소 범위 안이다.
- `app.log` 줄 수: 14:48 43줄, 14:49 59줄, 14:50 33줄, 14:51 16줄 → 14:52 이후 분당 1 ~ 3줄로 돌아온다.

### 1.4 평소 수준과 이상 구간 제안

- **평소 수준**: 5xx 0건/분, app `WARN`/`ERROR`(409 제외) 0건/분, nginx-error 하루 2건(`buffered to a temporary file`), slow log 2 ~ 2.3 s 쿼리 하루 2건, 409 는 20 ~ 60분에 1건.
- **이상 시작: 14:37:42 KST** — 9.8 s slow 쿼리 시작. 같은 초대(14:37:52)에 첫 `Connection leak detection` 경고가 나온다. 사용자 영향(5xx)은 **14:40:52** 부터.
- **이상 끝: 14:51:47 KST** — 마지막 5xx · 마지막 nginx `upstream timed out`. 14:52 ~ 15:29 에는 5xx · 경고 · 오류가 다시 0건이다.

| 구분 | 시작 | 끝 |
|---|---|---|
| 전조 구간 (경고만, 사용자 영향 없음) | 14:37:42 | 14:40:51 |
| 영향 구간 (5xx 발생) | 14:40:52 | 14:51:47 |

### 1.5 앞뒤 여유 제안

| 구분 | 여유 | 수집 시각 (KST) | 근거 |
|---|---|---|---|
| 앞쪽 | **60분** | **13:37:00** 부터 | `Connection leak detection` 경고는 커넥션을 빌린 뒤 임계 시간이 지나야 찍히므로, 첫 경고 시각보다 앞선 사건을 담아야 한다(임계 값은 아직 확인 안 함 — 설정 확인 후 조정). 13:05 nginx 경고 · 14:02 ~ 14:33 409 건 등 직전 1시간 흐름을 같은 창에서 비교할 수 있다. |
| 뒤쪽 | **15분** | **15:07:00** 까지 | 마지막 5xx(14:51:47) 이후 0건이 15분 넘게 이어지는 것을 확인하기에 충분하다. 로그는 15:29 까지 있으므로 필요하면 끝까지 늘린다. |
| 비교 기준 구간 | — | 07:00 ~ 13:30 | 평소 요청량 · 오류율 · slow log 기준선 (09:14 · 11:02 slow 쿼리 포함) |

**최종 수집 범위: 2026-09-16 13:37:00 ~ 15:07:00 KST** (slow log 는 `# Time` 04:37:00 ~ 06:07:00 UTC, 단 범위 밖 2건도 기준선으로 함께 보관).

### 1.6 수집 시 주의

- `nginx-access.log` 쿼리 문자열과 `app.log` 일부 메시지에 학생 식별자가, `nginx-error.log` 에 `Authorization: Bearer …` 헤더 일부가 들어 있다. 이 문서와 이후 절에 원문 값을 옮기지 않고 마스킹한다.
- `app.log` 는 스택트레이스가 여러 줄로 이어지므로 줄 수 ≠ 이벤트 수다. 분 단위 집계는 타임스탬프 줄만 셌다.

## 2. 타임라인

> 대상: 2026-09-16 **13:37:00 ~ 15:07:00 KST** (1절에서 정한 수집 범위). 네 파일을 KST 한 시간축으로 합쳤다.
> 시각 변환: `mariadb-slow.log` 의 `# Time` 은 UTC 라 +9시간 했고, `SET timestamp` 는 epoch 를 KST 로 바꿨다. 나머지 파일은 그대로 썼다.
> 시각 정밀도: `app.log` 는 ms 단위이고 `nginx-access.log` · `nginx-error.log` · `mariadb-slow.log` 는 초 단위다. 파일이 달라 몇 초 안에 붙은 사건은 **(선후 불확실)** 로 표시했다.
> 원문의 학생 식별자와 토큰 값은 `***` 로 가렸다. 원인 해석은 쓰지 않았다.
> 같은 메시지가 반복되면 "첫 발생 시각, 이후 N건/분"으로 묶었다. 분별 건수는 표 아래 2.2 에 있다.

### 2.1 시간순 사건

| 시각 (KST) | 출처 (파일:줄) | 사건 요약 | 원문 발췌 (80자 이내) |
|---|---|---|---|
| 13:37 ~ 14:37 | `nginx-access.log` (분 단위 집계) | 전체 요청량이 분당 32 ~ 56건이다. 07:00 ~ 14:29 평균(약 40건/분)과 같은 범위다. | — |
| 13:51:42 | `nginx-access.log:16793`, `app.log:1092` | 학급 리포트 조회(`GET /api/classes/{id}/report`)가 처음 나온다. **14:18:13(`nginx-access.log:17914`)까지 모두 8건, 분당 0 ~ 1건**이다. 14:19 ~ 14:37 에는 0건이다. | `"GET /api/classes/2/report HTTP/1.1" 200 274` |
| 13:51:51 | `nginx-access.log:16801`, `app.log:1094` | 재배포 POST(`/api/distributions/8/redistribute`)가 200 으로 끝났다. 같은 종류의 200 이 14:04:33(`nginx-access.log:17310`) · 14:19:38(`nginx-access.log:17977`)에도 있다. | `"POST /api/distributions/8/redistribute HTTP/1.1" 200 200` |
| 14:02:40 | `nginx-access.log:17230`, `app.log:1122` | 409 `state conflict`(마감된 과제 재배포)가 처음 나온다. 구간 안에서는 14:52:09 까지 **모두 8건**이다(14:02:40 · 14:11:38 · 14:14:03 · 14:33:45 · 14:42:42 · 14:50:22 · 14:51:52 · 14:52:09, 간격 17초 ~ 19분). 하루 전체로는 08:41 ~ 14:52 에 20건이다. | `WARN … state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=1` |
| 14:37:42 | `mariadb-slow.log:20` | 느린 쿼리가 **시작**됐다(`SET timestamp=1789537062`). `submission` 을 `distribution_id=5` 로 조회했다. | `select s1_0.id,…,s1_0.submitted_at from submission s1_0 where s1_0.distribution_id=5` |
| 14:37:52 | `mariadb-slow.log:15`, `:18` | 그 느린 쿼리가 **끝났다**(`# Time 5:37:52` UTC). 9.81 초, 검사 행 1,284,310개다. 이날 앞선 slow 항목 2건은 2.3 · 2.0 초, 검사 행 41,822 · 5개였다. **(선후 불확실 — 아래 `app.log:1243` 과 같은 초)** | `# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310` |
| 14:37:52.199 | `app.log:1243` (스택 `app.log:1253`–`1254`) | Hikari 의 첫 **`Connection leak detection` WARN** 이다. 커넥션은 `@6e8f14d3`, 스레드는 `exec-6`, 스택은 `ReportService.buildClassReport` ← `ReportController.classReport` 다. 이 경고는 이후 **14:51:37 까지 134건, 분당 1 ~ 14건** 나온다. | `WARN … ProxyLeakTask : Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:38:01 | `nginx-access.log:18801`, `app.log:1258`, `app.log:1259` | 리포트 조회가 200 으로 끝났다(app 기록 14:38:01.900). 바로 이어 14:38:01.902 에 같은 커넥션 `@6e8f14d3` 의 **`Previously reported leaked … returned to the pool (unleaked)` INFO** 가 처음 나온다. 이 INFO 는 이후 14:51:49 까지 134건이다. **(선후 불확실 — nginx 와 app 사이)** | `INFO … Previously reported leaked connection …@6e8f14d3 … was returned to the pool` |
| 14:38 ~ 14:41 | `nginx-access.log:18801` ~ (분 단위 집계) | **트래픽 변화: 리포트 조회 급증.** 분당 3(14:38) → 3(14:39) → 10(14:40) → 16(14:41)건이다. 전체 요청량은 분당 41 ~ 50건으로 평소 범위다. 14:3x ~ 14:5x 의 리포트 요청은 출처 IP 4개(`교사단말A` · `교사단말B` · `교사단말C` · `교사단말D`)에서 학급 1 · 2 · 3 으로 왔다. | `교사단말B - - [16/Sep/2026:14:38:01 +0900] "GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:29 | `nginx-access.log:18817`, `app.log:1278` | 재배포 POST(`/api/distributions/5/redistribute`)가 200 으로 끝났다. | `INFO … redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:38:51.641 | `app.log:1280` | leak 경고가 처음으로 **두 번째 커넥션** `@2ac19f07` 에서 나온다. | `Connection leak detection triggered for org.mariadb.jdbc.Connection@2ac19f07` |
| 14:39:50.132 | `app.log:1331` | leak 경고에 **세 번째 커넥션** `@71d4b8e2` 이 처음 나온다. | `Connection leak detection triggered for org.mariadb.jdbc.Connection@71d4b8e2` |
| 14:40:04.730 | `app.log:1351` | leak 경고에 **네 번째 커넥션** `@3f6c2a91` 이 처음 나온다. | `Connection leak detection triggered for org.mariadb.jdbc.Connection@3f6c2a91` |
| 14:40:12.901 | `app.log:1370` | leak 경고에 **다섯 번째 커넥션** `@5b0e9e0c` 이 처음 나온다. 이후 새로운 커넥션 ID 는 나오지 않는다(하루 전체 5종). | `Connection leak detection triggered for org.mariadb.jdbc.Connection@5b0e9e0c` |
| 14:40:52.319 | `app.log:1521`, `app.log:1522` | 첫 **풀 획득 타임아웃**이다. 이후 **14:49:51 까지 49건, 분당 1 ~ 10건** 나오고 `waiting` 값은 1 ~ 7 사이다. | `ERROR … itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 14:40:52 | `app.log:1523`, `nginx-access.log:18918` | **첫 5xx(500)** 다. `GET /api/units/M6-2/items` 에서 났다. 500 은 이후 49건이다. `unhandled exception` 은 이후 14:49:51 까지 49건이고 경로는 `/api/units/*/items` 19, `/api/units` 14, `/api/items/*` 7, `/api/classes/*/report` 5, `/api/distributions/*` 4 다. **(선후 불확실 — app 과 nginx 사이)** | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| 14:41:06 | `nginx-access.log:18933`, `nginx-error.log:6` | **첫 504** 이자 첫 **`upstream timed out`** 이다. `GET /api/classes/3/report` 에서 났다. 이후 **14:51:47 까지 55건, 분당 2 ~ 8건**이다. **(선후 불확실 — 두 파일 같은 초)** | `upstream timed out (110: Connection timed out) while reading response header from upstream` |
| 14:41:17.977 | `app.log:1690` | `/api/items/{id}` 에서 첫 `unhandled exception` 이 났다. | `ERROR … unhandled exception on /api/items/30` |
| 14:41:27.252 | `app.log:1864` | `/api/classes/{id}/report` 에서 첫 `unhandled exception` 이 났다. | `ERROR … unhandled exception on /api/classes/1/report` |
| 14:42:01 | `nginx-error.log:10`, `nginx-error.log:11`, `nginx-access.log:18970` | 첫 **`upstream server temporarily disabled`**(이후 26건, 분당 1 ~ 4건), 첫 **`connect() failed (111: Connection refused)`**(이후 14건, 분당 1 ~ 2건), **첫 502**(이후 82건)가 같은 초에 나온다. **(선후 불확실 — 세 줄 같은 초)** | `upstream server temporarily disabled while reading response header from upstream` |
| 14:42:04 | `nginx-error.log:12` | 첫 **`no live upstreams`** 다. 이후 **14:51:02 까지 68건, 분당 1 ~ 14건**으로 구간 안에서 가장 많은 오류 메시지다. | `no live upstreams while connecting to upstream, client: 10.20.x.x` |
| 14:42:42 | `nginx-access.log:18998`, `app.log:2174` | 409 `state conflict` 가 났다(위 14:02:40 묶음). | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=1` |
| 14:42:59.807 | `app.log:2262` | `/api/distributions/{id}` 에서 첫 `unhandled exception` 이 났다. | `ERROR … unhandled exception on /api/distributions/5` |
| 14:44:19 | `nginx-access.log:19078`, `app.log:2929` | 재배포 POST(`/api/distributions/8/redistribute`)가 200 으로 끝났다. | `redistributed distribution 8 (assignment 6, class 2) reason=보강 수업 후 재배포` |
| 14:45:06.991 | `app.log:3249` | 풀 획득 타임아웃의 `waiting` 이 이날 최댓값인 7 이 됐다. | `request timed out after 3000ms (total=5, active=5, idle=0, waiting=7)` |
| 14:49:51.552 | `app.log:4980`, `app.log:4981` | **마지막** 풀 획득 타임아웃 ERROR 이자 마지막 `unhandled exception` 이다. | `ERROR … itembank-pool - Connection is not available, request timed out after 3000ms` |
| 14:50:52 | `nginx-error.log:158`, `nginx-error.log:159` | **마지막** `temporarily disabled` 와 **마지막** `Connection refused` 다. **(선후 불확실 — 두 줄 같은 초)** | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:51:02 | `nginx-error.log:166` | **마지막** `no live upstreams` 다. | `no live upstreams while connecting to upstream, client: 10.20.x.x` |
| 14:51:37.674 | `app.log:5251` | **마지막** leak 경고다(커넥션 `@6e8f14d3`). | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:51:47 | `nginx-access.log:19381`, `nginx-error.log:168` | **마지막 5xx(504)** 이자 마지막 `upstream timed out` 이다. `GET /api/classes/3/report` 에서 났다. **(선후 불확실 — 두 파일 같은 초, 그리고 다음 줄과도 같은 초)** | `"GET /api/classes/3/report HTTP/1.1" 504 167` |
| 14:51:47 | `nginx-access.log:19382` | 마지막 5xx 직후의 200 응답이다. 이후 15:07 까지 5xx 는 0건이다. | `"GET /api/distributions/6 HTTP/1.1" 200 177` |
| 14:51:49.148 | `app.log:5267` | **마지막** `Previously reported leaked … (unleaked)` INFO 다. | `INFO … Previously reported leaked connection … was returned to the pool (unleaked)` |
| 14:52:09 | `nginx-access.log:19401`, `app.log:5269` | 409 `state conflict` 가 났다(14:02:40 묶음의 마지막, 바로 앞 14:51:52 는 `app.log:5268`). | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=4` |
| 14:52 ~ 15:06 | `nginx-access.log` · `app.log` (분 단위 집계) | 전체 요청량은 분당 35 ~ 56건이다. 5xx · app WARN/ERROR(409 제외) · nginx warn/error 는 0건이다. 리포트 조회는 14:59 에 2건, 15:01 · 15:04 에 1건씩으로 평소 빈도다. | — |

**로그에 없는 것** (구간 13:37 ~ 15:07, `grep -i 'reload|refresh|signal|Start|shutdown|HikariPool-|config'` 로 확인):
- 설정 재적재 · 애플리케이션 재시작 · nginx reload 줄이 없다.
- `nginx-error.log` 에는 구간 시작(13:37)부터 14:41:06 전까지 줄이 하나도 없다(직전 줄은 13:18:05 `[info]`, 구간 밖).
- `mariadb-slow.log` 에는 14:37:42 쿼리 외에 구간 안 항목이 없다.

### 2.2 반복 메시지 분별 건수 (KST, 0건인 분은 생략)

| 분 | leak WARN (`app`) | unleaked INFO (`app`) | 풀 타임아웃 ERROR (`app`) | `unhandled exception` (`app`) | 리포트 요청 (`access`) | 500 / 502 / 504 (`access`) | timed out (`nginx-error`) | no live upstreams | temporarily disabled | Connection refused |
|---|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|
| 14:37 | 1 | | | | | | | | | |
| 14:38 | 2 | 3 | | | 3 | | | | | |
| 14:39 | 3 | 3 | | | 3 | | | | | |
| 14:40 | 14 | 10 | 1 | 1 | 10 | 1 / 0 / 0 | | | | |
| 14:41 | 13 | 15 | 6 | 6 | 16 | 6 / 0 / 3 | 3 | | | |
| 14:42 | 10 | 10 | 2 | 2 | 15 | 2 / 13 / 5 | 5 | 11 | 2 | 2 |
| 14:43 | 14 | 15 | 10 | 10 | 19 | 10 / 2 / 5 | 5 | 1 | 1 | 1 |
| 14:44 | 11 | 11 | 2 | 2 | 14 | 2 / 5 / 6 | 6 | 4 | 4 | 1 |
| 14:45 | 9 | 8 | 8 | 8 | 15 | 8 / 14 / 5 | 5 | 12 | 3 | 2 |
| 14:46 | 12 | 13 | 4 | 4 | 19 | 4 / 16 / 6 | 6 | 14 | 3 | 2 |
| 14:47 | 9 | 9 | 5 | 5 | 12 | 5 / 6 / 3 | 3 | 5 | 1 | 1 |
| 14:48 | 10 | 10 | 4 | 4 | 14 | 4 / 6 / 5 | 5 | 5 | 4 | 1 |
| 14:49 | 12 | 13 | 7 | 7 | 16 | 7 / 8 / 8 | 8 | 6 | 4 | 2 |
| 14:50 | 11 | 9 | | | 10 | 0 / 11 / 7 | 7 | 9 | 4 | 2 |
| 14:51 | 3 | 5 | | | 5 | 0 / 1 / 2 | 2 | 1 | | |
| 합계 | 134 | 134 | 49 | 49 | 171 | 49 / 82 / 55 | 55 | 68 | 26 | 14 |

- 리포트 요청 열은 상태 코드와 관계없이 `GET /api/classes/{id}/report` 를 모두 셌다. 13:37 ~ 14:37 에는 **8건**(분당 0 ~ 1)이었다.
- `app.log` 의 `ReportService` `built class report` INFO 는 14:38 ~ 14:51 에 분당 3 ~ 15건이다. 모든 줄이 `3 distributions, 10 submissions` 로, 하루 내내 같은 값이다.
- leak WARN 134건의 스택은 전부 `ReportController.classReport` 를 지난다(`grep -A14` 집계).

## 3. 가설과 검증

> 이 절은 가설을 세우고(3.1 ~ 3.4), 로그로 확인할 수 있는 확인 방법을 실행해 가설마다 유지 · 기각 · 판단 불가를 표시했다(3.3 의 5번 열, 실행 기록은 3.5). 유지는 "반증 조건이 로그에서 확인되지 않았다"는 뜻이지 원인으로 확정했다는 뜻이 아니다.
> 근거 줄 번호는 이 문서(`docs/rca/a-connection-pool.md`)의 2절 타임라인 줄이다. 명령은 저장소 루트에서 실행한다고 가정하고, 로그 경로는 `incident-logs/a-connection-pool/` 를 `L/` 로 줄여 쓴다.

### 3.1 증상과 원인 구분

아래는 구간 안에서 가장 많이 나온 메시지지만 **증상**이다. 가설은 "왜 이 메시지가 나올 상태가 됐는가"를 설명해야 한다.

| 메시지 | 건수 | 이 메시지가 말해 주는 것 (원인 아님) |
|---|---:|---|
| `Connection leak detection triggered` | 134 | 커넥션 하나가 `leak-detection-threshold`(`modern/api/src/main/resources/application.yml:18`, 10000 ms) 보다 오래 빌려져 있었다 |
| `no live upstreams` | 68 | nginx 가 upstream 을 실패로 표시해 요청을 보낼 곳이 없었다 |
| `Connection is not available, request timed out after 3000ms` | 49 | 풀 5개가 모두 사용 중이어서 3초 안에 커넥션을 못 얻었다 |
| `upstream timed out (110)` | 55 | 앱이 nginx 의 읽기 제한 시간 안에 응답 헤더를 보내지 못했다 |

### 3.2 모든 가설이 함께 설명해야 할 관측

가설을 판정할 때 아래 관측을 설명하지 못하면 그 가설은 불완전하다.

- O1. 13:51 ~ 14:18 의 리포트 조회 8건은 leak 경고 없이 끝났다(`docs/rca/a-connection-pool.md:127`, `:161`).
- O2. leak 경고 134건의 스택은 전부 `ReportController.classReport` 를 지난다(`:189`).
- O3. 9.81 초 slow 쿼리가 첫 leak 경고와 같은 초에 끝났다(`:131`, `:132`). 이후 slow log 항목은 없다(`:164`).
- O4. 재시작 · reload 없이 14:51 ~ 14:52 에 스스로 회복했다(`:159`, `:161`–`:162`).
- O5. 풀 고갈만으로는 설명이 덜 되는 nginx `connect() failed (111: Connection refused)` 14건이 있다(`:145`, `:152`).
- O6. slow 쿼리의 `Rows_sent: 4` · `Rows_examined: 1,284,310` 과 `built class report` 의 `10 submissions` 고정값(`:131`, `:188`)이 같은 DB · 같은 호출자에서 나온 값인지 아직 모른다.

### 3.3 가설 표

| # | 계층 | 1. 가설 | 2. 지지 근거 (파일:줄) | 3. 반증 조건 (이것이 확인되면 가설이 틀림) | 4. 확인 방법 | 5. 로그 확인 결과 · 판정 (3.5 R 번호) |
|---|---|---|---|---|---|---|
| H1 | 애플리케이션 코드 | `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회를 마친 뒤에도 서명 루프(`sign`, SHA-256 200,000회)를 도는 동안 커넥션을 쥐고 있어서, 리포트 요청이 동시에 몰리자 5개 풀이 리포트 요청에 모두 묶였다. | `docs/rca/a-connection-pool.md:132`(첫 leak 스택이 `ReportService.buildClassReport`), `:136`–`:139`(14:38 ~ 14:40 에 leak 커넥션이 5종까지 늘어남 = 풀 크기), `:140`(`total=5, active=5, idle=0`), `:189`(leak 134건 전부 `classReport` 경유), `:159`(리포트 요청이 평소 빈도로 돌아온 뒤 회복) | (a) 커넥션별 보유 시간(= `unleaked` 시각 − leak 시각 + 10 s)이 같은 스레드의 `built class report` 로그 시각까지 이어지지 않고 DB 조회 직후에 끝난다. (b) 풀 타임아웃 ERROR 가 난 시각에 leak 상태(경고 후 아직 `unleaked` 전)인 커넥션이 5개 미만이다 — 즉 리포트 외의 무언가가 커넥션을 쥐고 있었다. (c) 테스트 프로필에서 리포트 요청 5건 이상을 동시에 보내도 풀 타임아웃이 재현되지 않는다. | 코드: `modern/api/src/main/java/com/example/assignment/ReportService.java:41`(`@Transactional`), `:69`–`:71`(트랜잭션 안 `sign` 호출), `:23`(`SIGNATURE_ROUNDS`). 보유 시간: `grep -nE 'leak detection triggered\|returned to the pool' L/app.log \| grep -oE '^[0-9T:.+-]+\|Connection@[0-9a-f]+' \| paste - -` 로 커넥션 ID 별 경고 · 반환 시각 짝을 만든다. 동시 보유 수: 같은 목록을 시각순으로 훑어 각 `request timed out` 시각(`grep -n 'request timed out' L/app.log`)에 열려 있는 leak 구간 수를 센다. `sign` 단독 소요: `ReportServiceTest` 에서 `ReportService.sign` 을 시간 측정(로컬, 운영 DB 접속 없음). | **유지** — (a) 134건 모두 같은 스레드의 `built class report` 직후 0 ~ 2 ms 안에 반환됐다(R1-2), (b) 풀 타임아웃 49건 모두 그 순간 리포트 요청이 쥔 커넥션이 5개였다(R1-3). 반증 조건 (a) · (b) 미충족, (c) 는 로그로 확인 불가. 단 O1(평소 단건은 10 s 미만)과 사고 중 보유 15.5 ~ 21.5 s 의 차이를 설명할 CPU 사용률 로그가 없다. |
| H2 | DB · 쿼리 | `submission` 을 `distribution_id=5` 로 읽는 쿼리가 실행 계획 문제나 데이터 증가로 128만 행을 훑어 느려졌고, 이것이 커넥션 보유 시간을 늘려 고갈의 출발점이 됐다. | `docs/rca/a-connection-pool.md:130`(14:37:42 `where s1_0.distribution_id=5` 시작), `:131`(9.81 s · 검사 행 1,284,310, 평소 slow 2.0 ~ 2.3 s), `:132`(같은 초에 첫 leak 경고), `:135`(14:38:29 같은 `distribution 5` 재배포) | (a) slow 쿼리를 실행한 세션이 `modern/api` 가 아니다(`# User@Host` 가 레거시 모듈 계정이거나 다른 호스트). (b) `long_query_time` 이 낮아(예: 2 s 이하) 14:38 이후 리포트 쿼리가 느렸다면 기록됐어야 하는데 없다(`:164`) — 즉 이후의 고갈 동안 쿼리 자체는 빨랐다. (c) `EXPLAIN` 에서 `idx_submission_distribution` 을 쓰고 `distribution_id=5` 행 수가 소수다. | `sed -n 15,21p L/mariadb-slow.log` 로 `# User@Host` · `# Thread_id` · `Rows_sent` 확인. 스키마: `db/mariadb/init/01-schema.sql:112`(`idx_submission_distribution`). 로컬 DB(localhost)에서 `EXPLAIN SELECT … FROM submission WHERE distribution_id=5 ORDER BY student_id;` 와 `SELECT COUNT(*) FROM submission WHERE distribution_id=5;`. 운영 `long_query_time` 값은 운영 담당자에게 `SHOW VARIABLES LIKE 'long_query_time'` 결과를 요청(이 저장소에서 운영 DB 에 직접 접속하지 않는다). | **기각**(사고 동안 보유 시간을 늘린 지속 원인으로서) — slow log 기록 기준은 2.04 s 이하인데(`mariadb-slow.log:11` 이 기록됨) 14:37:52 뒤에는 slow 항목이 0건이고, 그동안 133개 보유 구간도 15.5 ~ 21.5 s 였다(R2-1, R1-1). 반증 조건 (b) 충족. (a) 는 미충족: 호출자는 `app@API-1` = `modern/api` upstream 과 같은 호스트(R2-1, R4-1). 첫 보유(14:37:42 시작) 하나를 늘린 계기였는지는 판단하지 않았다. |
| H3 | 트래픽 | 특정 클라이언트(출처 IP 4개)가 학급 리포트를 짧은 간격으로 반복 호출(자동 새로고침 · 재시도)하면서 리포트 동시 요청 수가 평소의 10 ~ 19배로 늘었고, 504/502 를 받은 요청이 다시 재시도돼 부하가 스스로 커졌다. | `docs/rca/a-connection-pool.md:127`(평소 분당 0 ~ 1건), `:134`(14:38 ~ 14:41 분당 3 → 16건, IP 4개 · 학급 1 ~ 3), `:186`–`:187`(14:38 ~ 14:51 리포트 171건), `:142`·`:155`(504 가 리포트 경로에서 처음 · 마지막으로 남), `:159`(리포트 빈도가 평소로 돌아간 뒤 회복) | (a) 리포트 요청 증가가 첫 leak 경고(14:37:52)보다 **뒤**에 시작되고, 같은 IP·같은 URL 재호출이 직전 응답 5xx 직후에만 몰린다 — 즉 급증은 원인이 아니라 장애의 결과(재시도)다. (b) IP 별 요청 간격이 사람 조작 수준(수십 초 이상, 불규칙)이고 동시성이 2 이하로 유지된다. (c) 13:51 ~ 14:18 에도 같은 IP 에서 같은 수준의 동시 리포트 요청이 있었는데 문제가 없었다. | `awk '$4>="[16/Sep/2026:14:30" && $4<"[16/Sep/2026:14:53" && $7 ~ /\/report/ {print $1, substr($4,14,8), $9}' L/nginx-access.log` 로 IP · 초 · 상태 코드 나열 후 IP 별 간격과 5xx 직후 재요청 여부 확인. `awk '$7 ~ /\/report/ {print $1}' L/nginx-access.log \| sort \| uniq -c` 로 하루 전체 IP 분포 비교. User-Agent 필드(로그 형식에 있으면)로 브라우저 · 스크립트 구분. 학생 식별자가 쿼리 문자열에 있으므로 결과를 문서에 옮길 때 마스킹. | **유지** — 리포트 요청이 첫 5xx(14:40:52)보다 먼저 14:38 부터 늘었고(R3-2), IP 별 요청 간격 중앙값이 7.5 ~ 21 s 이며, referer 가 평소 `…/classes/N`(52건)에서 사고 중 `…/classes/N/report` 자체(171건)로 바뀌었다(R3-3, R3-4). 반증 조건 (a) · (b) · (c) 미충족. 단 "5xx 뒤 재시도로 커졌다"는 부분은 지지되지 않는다: 같은 학급 재요청 비율이 5xx 뒤 72 %(65/90), 200 뒤 70 %(54/77)로 차이가 없다(R3-5). |
| H4 | 인프라 (nginx · 프록시) | nginx 의 `proxy_read_timeout` 이 리포트 처리 시간보다 짧아 504 로 끊긴 뒤에도 앱은 그 요청을 끝까지 처리하며 커넥션을 쥐고 있었고, `max_fails`/`fail_timeout` 설정이 upstream 을 반복해서 "down" 으로 표시해 리포트와 무관한 경로까지 502(`no live upstreams`)로 넓혔다. | `docs/rca/a-connection-pool.md:142`(첫 504 · `upstream timed out`), `:145`(`temporarily disabled` · 첫 502 같은 초), `:146`(`no live upstreams` 68건), `:141`(500 경로가 `/api/units/*` 등 리포트 외 경로에도 분포), `:155`–`:157`(마지막 504 뒤 2초에 마지막 `unleaked`) | (a) nginx 설정의 `proxy_read_timeout` 이 관측된 커넥션 보유 시간보다 길다(504 가 앱 처리 중 끊김이 아님). (b) `upstream` 블록에 서버가 1개뿐이고 `max_fails=0` 이라 "disabled" 처리가 일어날 수 없는 설정이다(그런데도 로그가 있다면 설정이 다른 것). (c) 504 를 받은 요청에 대응하는 `app.log` 처리 로그(`built class report` 또는 예외)가 nginx 504 시각보다 **앞서** 끝나 있다. | nginx 설정은 이 저장소에 없다 → 운영 담당자에게 `nginx -T` 출력 중 `upstream` · `proxy_read_timeout` · `proxy_next_upstream` · `max_fails` 줄을 요청. 로그로 upstream 대상 확인: `grep -oE 'upstream: "[^"]+"' L/nginx-error.log \| sort \| uniq -c`. 504 와 앱 처리 시각 대조: `grep -n ' 504 ' L/nginx-access.log` 의 시각 · 학급 ID 와 `grep -n 'built class report' L/app.log` 를 학급 ID 기준으로 맞춘다. | **유지**(설정 값은 판단 불가) — 504 55건 전부 같은 학급의 `built class report` 가 504 **뒤** 0.3 ~ 3.5 s 에 찍혀, 앱은 nginx 가 끊은 뒤에도 처리를 계속했다(R4-3, 반증 조건 (c) 미충족). upstream 은 `API-1` · `API-2` 두 대이고 `temporarily disabled` 26건은 전부 `API-1`, `Connection refused` 14건은 전부 `API-2` 다(R4-1). 502 82건 중 50건이 리포트 외 경로다(R4-4). (a) · (b) 는 `proxy_read_timeout` · `max_fails` 값이 로그에 없어 판단 불가 — nginx 설정(`nginx -T`)이나 `$request_time` · `$upstream_response_time` 이 들어간 access log 가 있었다면 판단할 수 있었다. |
| H5 | 배포 · 설정 변경 | 장애 전에 배포된 변경(서명 반복 횟수 증가, Hikari `maximum-pool-size`·`connection-timeout` 축소, `@Transactional` 범위 변경 중 하나)이 리포트 처리 시간을 늘리거나 풀 여유를 줄여, 평소 트래픽 변동에도 고갈되는 상태를 만들었다. | `docs/rca/a-connection-pool.md:127`·`:128`(13:51 ~ 14:19 리포트 · 재배포는 정상), `:140`(관측된 풀 크기 `total=5`, 대기 3000 ms), `:145`·`:152`(앱 재기동을 의심하게 하는 `Connection refused`) — 다만 `:161`–`:162`(구간 안 재시작 · reload 줄 없음)는 이 가설에 불리한 관측이다 | (a) `app.log` 첫 줄(06:43:56) 이후 재기동 흔적이 하루 종일 없고, 그 이전 배포에서 `ReportService` · `application.yml` 이 바뀌지 않았다. (b) 06:43 기동 시 출력된 Hikari 설정 값이 장애 전후로 같고, 전날 이전 운영 값과도 같다. (c) `Connection refused` 시각에 `app.log` 가 같은 프로세스 · 스레드 이름으로 끊김 없이 기록되고 있다(프로세스는 살아 있었음). | 변경 이력: `git log --since=2026-09-01 -p -- modern/api/src/main/java/com/example/assignment/ReportService.java modern/api/src/main/resources/application.yml` (저장소 이력과 운영 배포 이력이 다를 수 있으므로 배포 기록도 요청). 기동 로그: `head -60 L/app.log \| grep -nE 'Started\|itembank-pool\|HikariPool\|Tomcat'`. 재기동 흔적: `grep -nE 'Started \|Shutting down\|Tomcat started' L/app.log`. `Connection refused` 시각 전후 앱 로그 연속성: `awk -F'T' '$2>="14:41:55" && $2<"14:42:10"' L/app.log \| head`. | **판단 불가(로그 부족)** — 하루 내내 기동 · 종료 줄 0건, PID 는 `1` 하나뿐이라 06:43 ~ 15:29 사이 재배포는 없었다(R5-1). 이 가설이 근거로 쓴 `Connection refused` 는 `API-1` 앱이 아니라 `API-2` 에서 났다(R4-1)로 이 근거는 사라졌다. 그러나 06:43 이전 배포 여부와 그때의 설정 값은 알 수 없고, 저장소 이력은 2026-09-22 부터라 9/16 이전 변경을 보여 주지 못한다(R5-2). 앱 **기동 로그**(Hikari 설정 출력 포함), **배포 이력**, **전날 이전 같은 로그**(평소 리포트 처리 시간 · leak 경고 유무 비교용)가 있었다면 판단할 수 있었다. |

### 3.4 가설 사이의 관계 (판정 아님)

- H1 · H2 는 "커넥션을 오래 쥔 것이 무엇인가"를 두고 경쟁하고, H3 은 "왜 이 시각에 동시 요청이 늘었나", H4 는 "왜 리포트 외 경로까지 영향이 넓어졌나"를 설명한다. 한 가설이 맞아도 다른 가설이 함께 맞을 수 있으므로, 판정은 가설마다 반증 조건을 따로 확인해서 한다.
- O1(평소 리포트 8건은 leak 경고 없음)은 H1 · H2 모두에 대해 "단일 요청만으로는 10 s 를 넘지 않았다"는 제약이 된다. H1 을 유지하려면 동시 실행 시 처리 시간이 늘어나는 이유(CPU 경합 등)가, H2 를 유지하려면 14:38 이후에도 쿼리가 느렸다는 증거가 필요하다.
- O5(`Connection refused`)는 H4 · H5 어느 쪽으로도 아직 설명되지 않았다. Tomcat accept 큐 포화 같은 다른 설명이 있을 수 있어 열어 둔다.

### 3.5 로그 확인 실행 기록

> 저장소 루트에서 `L=incident-logs/a-connection-pool` 로 두고 실행했다. 여러 줄을 짝지어야 하는 집계는 세션 임시 디렉터리의 Python 스크립트(`h1.py` · `h3.py` · `h4.py`, 저장소에 넣지 않음)로 했고, 스크립트가 하는 일을 함께 적었다. 결과 숫자는 출력 그대로다. 학생 식별자는 출력에 나오지 않았다.
> 커넥션 보유 시간은 `unleaked 시각 − leak 경고 시각 + 10 s`(`leak-detection-threshold`)로 추정했다. 하우스키퍼 주기 때문에 실제 획득 시각과 수백 ms 정도 어긋날 수 있다.

#### H1 (애플리케이션 코드)

| R | 명령 · 방법 | 결과 (출력 그대로) |
|---|---|---|
| R1-1 | `h1.py $L/app.log` — `leak detection triggered` 와 `returned to the pool` 을 (커넥션 ID, 스레드) 로 짝지음 | `pairs 134 unpaired leak 0 unpaired unleaked 0` / `hold s min/median/max 15.5 18.9 21.5` |
| R1-2 | 같은 스크립트 — 각 `unleaked` 직전에 같은 스레드의 `built class report` 가 1 s 안에 있는지 | `unleaked right after built-report (<1s, same thread): 134 / 134  max gap ms 2.0` |
| R1-3 | 같은 스크립트 — `SqlExceptionHelper` 의 `request timed out` ERROR 시각마다 열려 있는 리포트 보유 구간 수 | `timeouts 49 open report-held conns at timeout: [(5, 49)]` |
| R1-4 | `grep -m3 '/report' $L/nginx-access.log` (로그 형식 확인) | 필드에 `$request_time` 이 없다 → 평소 단건 리포트 처리 시간은 로그로 알 수 없다 |

#### H2 (DB · 쿼리)

| R | 명령 · 방법 | 결과 (출력 그대로) |
|---|---|---|
| R2-1 | `cat -n $L/mariadb-slow.log` | 3건 모두 `# User@Host: app[app] @  [API-1]`. 가장 짧은 기록 항목 `Query_time: 2.041903`(`:11`). 9.81 s 항목은 `Thread_id: [TOKEN]`, `Rows_sent: 4  Rows_examined: 1284310`(`:17`–`:18`). `# Time` 5:37:52 뒤 항목 없음 |
| R2-2 | (R1-1 첫 짝) leak `14:37:52.199` → 획득 추정 `14:37:42.2`, `built class report for class 3` `14:38:01.900` | slow 쿼리 시작(`SET timestamp=1789537062` = 14:37:42)과 첫 보유 시작이 같은 초. 쿼리 종료(14:37:52) 뒤 반환까지 약 9.9 s 가 더 걸렸다 |

#### H3 (트래픽)

| R | 명령 · 방법 | 결과 (출력 그대로) |
|---|---|---|
| R3-1 | `awk '$7 ~ /\/report/ {print $1}' $L/nginx-access.log \| sort \| uniq -c` (하루 전체) | `87 교사단말A` · `57 교사단말B` · `56 교사단말C` · `29 교사단말D` |
| R3-2 | `awk '$7 ~ /\/report/ {t=substr($4,14,5); if(t>="14:30"&&t<="14:53") print t}' $L/nginx-access.log \| uniq -c` | `14:38 3` · `14:39 3` · `14:40 10` · `14:41 16` · `14:42 15` · `14:43 19` · `14:44 14` · `14:45 15` · `14:46 19` · `14:47 12` · `14:48 14` · `14:49 16` · `14:50 10` · `14:51 5` |
| R3-3 | `awk -F'"' '$2 ~ /\/report/ …'` 로 14:37 ~ 14:52 의 4번째 따옴표 필드(referer) 집계 | `64 …/teacher/classes/1/report` · `32 …/classes/2/report` · `75 …/classes/3/report` (합 171). User-Agent 는 Firefox 130 102건 · Safari 17.6 69건 |
| R3-4 | 같은 방법으로 14:37 이전 referer 집계(학급 번호를 `N` 으로 치환) | `52 https://lms.example.com/teacher/classes/N` — `/report` 로 끝나는 referer 0건. 상태 코드 `200` 50 · `400` 2 |
| R3-5 | `h3.py $L/nginx-access.log` — 14:37:00 ~ 14:52:59 리포트 요청을 IP 별 시각순으로 놓고 간격 · 직전 응답별 같은 학급 재요청 비율 | `교사단말A n 67 gap s min/median/max 0.0 7.5 39.0` / `교사단말B n 40 … 1.0 21.0 42.0` / `교사단말C n 35 … 0.0 19.5 41.0` / `교사단말D n 29 … 1.0 20.0 41.0` / `next request same class: after 5xx 65 / 90 \| after 200 54 / 77` / `all gaps <=10s: 56 / 167` |
| R3-6 | `awk '$7 ~ /\/report/ {t=substr($4,14,8); if(t>="13:37"&&t<"14:37") print t,$1,$9}' $L/nginx-access.log` | 8건, 간격 최소 44 s(14:17:29 → 14:18:13), 그 밖에는 1분 30초 ~ 7분 |
| R3-7 | `awk '$7 ~ /\/report/ {…; n[$1]++; if($9>=500) e[$1]++}'` (14:37 ~ 14:52, IP 별 요청 / 5xx) | `교사단말A 67 / 40` · `교사단말B 40 / 18` · `교사단말C 35 / 16` · `교사단말D 29 / 18` |

#### H4 (인프라 · nginx)

| R | 명령 · 방법 | 결과 (출력 그대로) |
|---|---|---|
| R4-1 | `grep -E '\[(warn\|error)\]' $L/nginx-error.log` 를 메시지 종류 × `upstream:` 호스트로 집계 | `2 buffered … \| API-1:8080` · `14 connect() failed (111: Connection refused) \| API-2:8080` · `68 no live upstreams \| item_bank_api` · `26 upstream server temporarily disabled \| API-1:8080` · `55 upstream timed out (110) \| API-1:8080` |
| R4-2 | `grep -n 'API-2' $L/nginx-error.log \| sed -n '1p;$p'` | 첫 `nginx-error.log:11` 14:42:01, 마지막 `nginx-error.log:159` 14:50:52. 그 밖의 시각에는 `API-2` 가 나오지 않는다 |
| R4-3 | `h4.py $L/nginx-access.log $L/app.log` — 504 의 경로 집계, 리포트 504 마다 같은 학급의 `built class report` 가 504 뒤 30 s 안에 있는지 | `504 paths {'/api/classes/{id}/report': 55}` / `report 504 55 \| same-class built-report within 30s AFTER 504: 55 \| gap s min/max 0.327 3.472` |
| R4-4 | `awk '$9==502 {…경로 정규화…}' $L/nginx-access.log \| sort \| uniq -c` | `/api/classes/{id}/report 32` · `/api/items/{id} 13` · `/api/units 12` · `/api/units/{code}/items 12` · `/api/distributions/{id} 9` · `/ 2` · `/api/item/{id} 1` · `/robots.txt 1` (합 82) |
| R4-5 | `grep -oE 'server: [^,]+' $L/nginx-error.log \| sort \| uniq -c` | `167 server: api.lms.example.com` (가상 호스트 하나) |

#### H5 (배포 · 설정 변경)

| R | 명령 · 방법 | 결과 (출력 그대로) |
|---|---|---|
| R5-1 | `grep -cE 'Started \|Shutting down\|Tomcat started\|Tomcat initialized' $L/app.log` / `grep -oE '^2026\S+\s+[A-Z]+ [0-9]+ ---' $L/app.log \| awk '{print $3}' \| sort \| uniq -c` / `head -3 $L/app.log` | `0` / `1927 1` (타임스탬프 줄 전부 PID 1) / 첫 줄이 06:43:56 요청 처리 로그 — 기동 로그는 파일에 없다 |
| R5-2 | `git log --date=iso -- modern/api/src/main/java/com/example/assignment/ReportService.java modern/api/src/main/resources/application.yml` | `1260542 2026-09-22` · `cb4ffb3 2026-09-29` 두 건뿐 — 사고일(09-16) 이전 이력이 없다 |
| R5-3 | `grep -c 'leak-detection\|SIGNATURE' $L/app.log` | `0` — 실행 중 설정 값을 직접 보여 주는 줄이 없다. 관측된 값은 타임아웃 메시지의 `total=5` · `3000ms` 뿐이고 `modern/api/src/main/resources/application.yml:14`–`:15` 와 같다 |

#### 확인 뒤 바뀐 관측

- **O5(`Connection refused`)**: 14건 모두 두 번째 upstream `API-2:8080` 에서 났고, `API-2` 는 `API-1` 이 `temporarily disabled` 된 14:42:01 부터만 로그에 나온다(R4-1, R4-2). 앱(`API-1`)이 재기동한 흔적은 없다(R5-1). `API-2` 가 예비(`backup`) 서버인지, 원래 꺼져 있던 서버인지는 nginx 설정이 없어 판단하지 않았다.
- **O6**: slow 쿼리 호출자는 `app@API-1` 로 `modern/api` upstream 과 같은 호스트다(R2-1, R4-1). `Rows_sent: 4` 는 학급 3 의 배포 하나(`distribution_id=5`)분이고 `10 submissions` 는 배포 3개 합이라 서로 모순은 아니다. `Rows_examined: 1,284,310` 의 이유는 로그로 알 수 없다(실행 계획 · 테이블 통계 필요).
- **아직 설명되지 않은 것**: 평소 리포트 단건은 leak 경고(10 s)를 넘지 않았는데(O1) 사고 중에는 쿼리가 2 s 미만인데도 15.5 ~ 21.5 s 를 쥐었다(R1-1, R2-1). 이 차이를 설명하려면 앱 서버의 CPU 사용률 · GC 로그 · 스레드 덤프나, `$request_time` 이 있는 access log 가 필요하다.

### 3.6 판정

> 3.3 에서 **유지**된 H1 · H3 · H4 를 `modern/api` 설정 파일과, 로그에 찍힌 클래스의 코드와 대조했다. 설정값은 파일에서 읽은 값만 쓴다. 벤치마크 · 테스트 같은 별도 실행은 하지 않았다.

#### 3.6.1 로그 단서 → 코드 · 설정 위치

| 로그 단서 | 출처 (로그) | 찾은 위치 | 읽은 내용 |
|---|---|---|---|
| 풀 이름 `itembank-pool` | `app.log:1522` | `modern/api/src/main/resources/application.yml:10` | `pool-name: itembank-pool` |
| `total=5` | `app.log:1522` | `application.yml:14` | `maximum-pool-size: 5` |
| `request timed out after 3000ms` | `app.log:1522` | `application.yml:15` | `connection-timeout: 3000` |
| `ProxyLeakTask` (`Connection leak detection triggered`) | `app.log:1243` | `application.yml:18` | `leak-detection-threshold: 10000` (HikariCP 라이브러리 클래스이며 `modern/api` 코드가 아님) |
| 커넥션을 언제 돌려주는가 | — | `application.yml:22` | `open-in-view: false` → 요청 전체가 아니라 트랜잭션 범위에서만 커넥션을 쥔다 |
| `ReportController.classReport(ReportController.java:22)` | leak 스택 134건 + 500 스택 5건 = 139 | `modern/api/src/main/java/com/example/assignment/ReportController.java:20`–`:22` | `GET /{id}/report` → `return reportService.buildClassReport(id);` |
| `ReportService$$SpringCGLIB$$0.buildClassReport(<generated>)` · `TransactionInterceptor` · `JpaTransactionManager.doBegin` | `app.log:1243` 스택 | `ReportService.java:41`–`:42` | `@Transactional public ClassReport buildClassReport(...)` — 트랜잭션 시작 시점에 커넥션을 빌린다 |
| `ReportService : built class report for class …` | 190줄 | `ReportService.java:73`–`:74` | `sign(...)`(`:71`) 다음, 메서드 반환(= 커밋 · 커넥션 반환) 직전의 로그 |
| `GlobalExceptionHandler : unhandled exception on …` | 49건 | `modern/api/src/main/java/com/example/common/GlobalExceptionHandler.java:53`–`:56` | `Exception` 을 잡아 `log.error(...)` 한 뒤 500 으로 응답 → 풀 타임아웃이 500 으로 나간 경로 |
| `SqlExceptionHelper` | 98줄 | (Hibernate 라이브러리) | `modern/api` 코드 아님 |
| `DistributionService` (`redistributed …`, 409) | 29줄 | `DistributionService.java:49`–`:61` | 재배포 · 409 처리. 리포트 경로와 커넥션을 공유하는 다른 장기 작업은 없다 |

**배포 코드와 저장소 코드가 같은지:** 로그 스택의 줄 번호는 저장소 첫 커밋 `1260542` 와 맞는다. 확인한 줄은 `ItemController.getItem:25`, `listItemsByUnit:31`, `UnitController.listUnits:22`, `DistributionController.getDistribution:26`, `ReportController.classReport:22` 이고, 모두 `git show 1260542:<파일>` 로 봤다. 현재 HEAD 의 `ItemController` 는 이후 커밋 `cb4ffb3` 에서 검색 기능이 들어가 같은 메서드가 `:43` · `:49` 로 밀렸다. `1260542..HEAD` 사이에 `assignment` 패키지는 바뀌지 않았다. `application.yml` 은 `server.tomcat.relaxed-query-chars` 4줄만 추가됐고 `hikari` 설정은 그대로다. 그래서 아래 대조에 쓴 `ReportService` · `hikari` 설정은 사고 당시 실행된 것과 같다고 본다. 다만 `ReportService` 프레임 자체는 `<generated>` 라 줄 번호로는 확인하지 못했다.

#### 3.6.2 가설별 대조

| # | 대조 결과 | 근거 (파일:줄 인용) |
|---|---|---|
| H1 | **코드 · 설정이 가설을 지지한다** | ① 트랜잭션 범위: `ReportService.java:41` `@Transactional` 이 메서드 전체에 걸려 있다. DB 조회(`:43`–`:63`: 학급 1회 · 배포 1회 · 배포마다 제출 1회)가 끝난 뒤 같은 트랜잭션 안에서 `:71` `String signature = sign(payload.toString());` 를 부른다. ② 코드 스스로 이 상태를 적어 두었다: `:69`–`:70` `// TODO: 트랜잭션 밖으로 — … DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` ③ `sign` 은 I/O 없는 CPU 반복이다: `:85`–`:92` 에서 SHA-256 을 `SIGNATURE_ROUNDS` 번 다시 계산하고, `:23` 에 `static final int SIGNATURE_ROUNDS = 200_000;` 로 정해져 있다. ④ `application.yml:22` `open-in-view: false` 이므로 커넥션은 메서드가 반환할 때 돌아간다. 로그에서 `unleaked` 가 `built class report`(`:73`) 뒤 0 ~ 2 ms 에 134/134 찍힌 것(R1-2)과 맞는다. ⑤ 풀이 5개(`application.yml:14`)이고 대기는 3000 ms(`:15`)라, 리포트 요청 5개가 동시에 이 구간에 있으면 다른 모든 경로가 3초 뒤 실패한다. R1-3 에서 49/49 시점에 리포트 보유가 5개였다. ⑥ 리포트 동시 실행 수를 제한하는 코드(별도 풀 · 세마포어 · 요청 제한)는 `ReportController.java` · `ReportService.java` · `WebConfig.java` 에서 찾지 못했다. **설명되지 않은 부분:** 평소 단건은 10 s 를 넘지 않았는데(O1) 사고 중에는 15.5 ~ 21.5 s 를 쥐었다. 코드상 CPU 경합이면 늘어날 수 있는 구조지만, 실제로 그랬는지는 실행 측정 없이 확인하지 않았다. |
| H3 | **관련 코드를 찾지 못했다** | 요청을 보낸 화면의 referer 는 `https://lms.example.com/teacher/classes/N/report`(R3-3)인데, 이 화면 코드는 저장소에 없다. `grep -rn 'lms.example.com'` 결과 이 문서뿐이었다. `modern/web/src` 에도 리포트 호출 · `setInterval` · 재조회 코드가 없다(`grep -rniE 'report\|setInterval\|refetch' modern/web/src` 0건). `modern/api` 쪽에는 요청 수를 제한하는 코드가 없다(`ReportController.java:20`–`:23`). 하지만 이것은 "급증을 막지 못했다"는 뜻일 뿐이고, 급증 자체의 원인은 보여 주지 않는다. |
| H4 | **관련 코드를 찾지 못했다** (nginx 쪽) · 앱 쪽 하위 주장은 코드가 지지 | nginx 설정(`upstream item_bank_api`, `proxy_read_timeout`, `max_fails`)은 저장소에 없다. `grep -rniE 'upstream\|proxy_read_timeout\|max_fails\|10\.20\.x\.x'` 결과 git 원격 이름 `upstream` 을 설명하는 문서(`README.md:5` 등)만 나왔다. 앱 쪽 주장("nginx 가 끊어도 앱은 끝까지 처리한다")은 코드가 지지한다. `sign` 루프(`ReportService.java:88`–`:91`)에는 중단 · 취소 확인이 없고, 컨트롤러는 동기 메서드(`ReportController.java:21`–`:22`)이며, `application.yml:28`–`:33` 의 `server` 설정에는 `port` 와 `relaxed-query-chars` 뿐 요청 처리 시간 제한이 없다. |

#### 3.6.3 최종 판정

**채택: H1 — `ReportService.buildClassReport` 가 `@Transactional` 안에서 CPU 서명 루프를 돌며 커넥션을 쥐었다. 리포트 요청이 동시에 몰리자 5개 풀이 리포트 요청에 모두 묶였고, 다른 모든 경로가 커넥션을 얻지 못했다.**

- 로그 근거: leak 134건 모두 `ReportController.classReport` 경유(`:189`), 반환이 `built class report` 직후 134/134(R1-2), 풀 타임아웃 49/49 시점에 리포트 보유 5개(R1-3).
- 코드 · 설정 근거: `ReportService.java:41` · `:69`–`:71` · `:85`–`:92`, `application.yml:14` · `:15` · `:22`.

**확신 수준: 중간**

- 높게 볼 수 있는 이유
  - 로그 세 가지 집계(R1-1 ~ R1-3)가 예외 없이 이 구조를 가리킨다.
  - 코드에 문제 지점이 TODO 로 명시돼 있다.
  - 로그 줄 번호로 사고 당시 코드와 저장소 코드가 같다는 것을 확인했다.
- 높음으로 올리지 않은 이유
  - **(1)** 평소 단건은 10 s 를 넘지 않았는데 사고 중에는 쿼리가 2 s 미만인데도 15.5 ~ 21.5 s 를 쥐었다. 이 차이가 CPU 경합 때문이라는 것은 CPU · GC 지표가 없어 확인하지 못했다.
  - **(2)** 재현이나 소요 시간 측정을 하지 않았다(이번 지시 범위 밖).
  - **(3)** 첫 보유(14:37:42)에 겹친 9.8 s 쿼리가 왜 128만 행을 훑었는지는 설명되지 않았다.
  - **(4)** `ReportService` 프레임은 `<generated>` 라 이 클래스 자체의 줄 번호 일치는 확인하지 못했다.

**채택하지 않은 가설**

| # | 판정 | 사유 |
|---|---|---|
| H2 (DB · 쿼리) | 기각 | slow log 기준 2.04 s 이하에서 14:37:52 뒤 slow 항목이 0건인데, 133개 보유 구간이 계속 15.5 ~ 21.5 s 였다(R2-1, R1-1). 지속된 장애 동안 커넥션을 쥔 것은 쿼리가 아니었다. 스키마에도 `idx_submission_distribution`(`db/mariadb/init/01-schema.sql:112`)이 있어 평소 전수 검사를 할 구조가 아니다. 첫 보유 하나에 겹친 계기였을 가능성은 남지만 근본 원인으로는 기각한다. |
| H3 (트래픽) | 근본 원인으로는 기각 · 촉발 요인으로 남김 | 리포트 요청 급증(분당 0 ~ 1 → 10 ~ 19건)은 로그로 확인됐고, 급증 시점과 장애 시작도 맞는다(R3-2). 하지만 전체 요청량은 평소 범위였다(`:134`). 분당 10 ~ 19건 정도의 리포트 요청은 리포트 한 건이 커넥션을 수십 초 쥐는 구조(H1)가 아니면 풀 5개를 고갈시킬 수 없다. 급증 원인을 보여 줄 코드는 찾지 못했다. "5xx 뒤 재시도로 커졌다"는 부분은 R3-5 에서 지지되지 않았다. |
| H4 (nginx) | 근본 원인으로는 기각 · 영향 확대 요인으로 남김 | 504 뒤에도 앱이 처리를 계속한 것(R4-3)과 리포트 외 경로까지 퍼진 502(R4-4)는 영향을 넓힌 요인이다. 그러나 풀 고갈은 nginx 가 처음 경고를 낸 14:41:06 보다 먼저 시작됐다. 첫 풀 타임아웃은 14:40:52, 첫 leak 은 14:37:52 다. nginx 설정 파일은 저장소에 없어 `proxy_read_timeout` · `max_fails` 값은 대조하지 못했다. |
| H5 (배포 · 설정) | 판단 불가로 채택하지 않음 | 사고일 하루 동안 재기동 흔적이 없다(R5-1). 로그에 찍힌 풀 값(`total=5`, `3000ms`)은 `application.yml:14`–`:15` 와 같다. `Connection refused` 는 `API-2` 에서 났다(R4-1). 따라서 사고 중 설정이 바뀌었다는 근거는 없다. 06:43 이전 배포 이력과 사고일 이전 저장소 이력이 없어 "이전에 바뀌었다"는 부분은 반증도 확인도 못 했다. |

## 4. 원인

> 3.6 에서 채택한 H1 을 인과 순서로 쓴다. 문장 번호(C·B)는 5절 근거와 짝을 이룬다.

### 4.1 직접 원인 (사건의 사슬)

- **C1 (촉발)** 14:38 부터 리포트 화면에서 같은 리포트를 다시 불러오는 요청이 늘었다. 분당 0 ~ 2건이던 요청이 10 ~ 19건이 됐다. 같은 시각 전체 요청량은 평소 범위였다.
- **C2** 리포트 요청 하나는 `buildClassReport` 트랜잭션이 시작될 때 커넥션을 빌린다. DB 조회가 끝난 뒤에도 서명 루프가 끝날 때까지 그 커넥션을 돌려주지 않았다. 사고 중 보유 시간은 15.5 ~ 21.5 초였다.
- **C3** 14:40:25 무렵 리포트 요청 5개가 동시에 커넥션을 쥐어, 풀 5개가 모두 리포트에 묶였다(leak 경고 시각으로 추정).
- **C4** 리포트가 아닌 요청(문항 · 단원 · 배포)도 커넥션을 3초 기다린 뒤 실패해 500 이 됐다. 14:40:52 ~ 14:49:51 동안 49건이다.
- **C5 (확대)** nginx 는 응답이 늦은 리포트 요청을 504 로 끊었다. 그러나 앱은 그 요청을 끝까지 처리하며 커넥션을 계속 쥐었다.
- **C6 (확대)** nginx 가 `API-1` 을 일시 차단하자 예비 `API-2` 로 넘겼지만 `API-2` 는 연결을 거부했다. 그래서 리포트 외 경로까지 502(`no live upstreams`)가 됐다.
- **C7 (회복)** 14:51 에 리포트 요청이 줄자 쥐고 있던 커넥션이 돌아왔다. 마지막 5xx 는 14:51:47 이고 그 뒤로는 0건이다. 재기동 없이 회복됐다.

### 4.2 배경 원인 (사슬을 가능하게 한 조건)

- **B1** 트랜잭션 범위가 DB 작업보다 넓다. `@Transactional` 메서드 안에서 I/O 없는 CPU 루프(SHA-256 200,000회)를 돈다. 코드에도 TODO 로 적혀 있다.
- **B2** 풀이 작고 대기가 짧다(`maximum-pool-size: 5`, `connection-timeout: 3000`, 운영 값과 동일). 오래 쥐는 요청 5개면 서비스 전체가 멈춘다.
- **B3** 무거운 엔드포인트 하나가 풀 전체를 차지하지 못하게 막는 장치가 없다. 동시 실행 제한도, 결과 캐시도 없다.
- **B4** nginx 의 예비 upstream `API-2` 가 요청을 받을 수 없는 상태였다. 의도된 구성인지는 확인하지 못했다(9절).

## 5. 근거 로그 줄

> 로그 경로는 `incident-logs/a-connection-pool/` 이다. 발췌는 80자 이내로 줄였다.

| 문장 | 파일:줄 | 발췌 |
|---|---|---|
| C1 | `nginx-access.log:18801` | `[16/Sep/2026:14:38:01 +0900] "GET /api/classes/3/report …" 200 266 "…/teacher/classes/3/report"` |
| C1 | 3.5 R3-2 · R3-4 (집계) | 분당 `3 · 3 · 10 · 16 · 15 · 19 …`. 14:37 이전 referer `…/classes/N` 52건, 사고 중 `…/report` 171건. 14:37 이전 분당 최대 2건(12:20) |
| C2 | `app.log:1243`, `:1253`–`:1254` | `Connection leak detection triggered for …@6e8f14d3` / `at …ReportController.classReport(ReportController.java:22)` |
| C2 | `app.log:1258`–`:1259` | `14:38:01.900 … built class report for class 3` → `14:38:01.902 … returned to the pool (unleaked)` |
| C2 | 3.5 R1-1 · R1-2 (집계) | `hold s min/median/max 15.5 18.9 21.5` / `unleaked right after built-report … 134 / 134` |
| C2 | `ReportService.java:41`, `:69`–`:71` | `@Transactional` / `// TODO: 트랜잭션 밖으로 …` / `String signature = sign(payload.toString());` |
| C3 | `app.log:1370` | `14:40:12.901 … leak detection triggered for …@5b0e9e0c` (다섯 번째 · 마지막 새 커넥션) |
| C3 | 4절 재구성 (leak 시각 − 10 s ~ unleaked) | 동시 리포트 보유 수가 처음 5개가 된 시각 `14:40:25.820` |
| C4 | `app.log:1522` | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| C4 | `nginx-access.log:18918` | `14:40:52 "GET /api/units/M6-2/items …" 500 162` |
| C4 | `app.log:4980` | `14:49:51.552 … Connection is not available` (마지막) |
| C5 | `nginx-error.log:6`, `nginx-access.log:18933` | `14:41:06 upstream timed out (110 …)` / `"GET /api/classes/3/report …" 504` |
| C5 | `app.log:1637` | `14:41:07.422 … built class report for class 3` (504 뒤 처리 완료) |
| C5 | 3.5 R4-3 (집계) | `report 504 55 \| same-class built-report within 30s AFTER 504: 55` |
| C6 | `nginx-error.log:10`–`:12` | `14:42:01 upstream server temporarily disabled` / `connect() failed (111: Connection refused)` (`API-2`) / `14:42:04 no live upstreams` |
| C6 | `nginx-access.log:18970` | `14:42:01 "GET /api/classes/2/report …" 502 157` |
| C7 | `nginx-access.log:19381`–`:19382` | `14:51:47 "GET /api/classes/3/report" 504` → `"GET /api/distributions/6 …" 200 177` |
| C7 | `app.log:5267` | `14:51:49.148 … Previously reported leaked connection … returned to the pool` (마지막) |
| B1 | `ReportService.java:23`, `:85`–`:92` | `static final int SIGNATURE_ROUNDS = 200_000;` / SHA-256 반복 루프 |
| B2 | `application.yml:14`–`:15`, `:18` | `maximum-pool-size: 5` / `connection-timeout: 3000` / `leak-detection-threshold: 10000` |
| B3 | `ReportController.java:20`–`:22` | 동시 실행 제한 · 캐시 없이 `reportService.buildClassReport(id)` 를 바로 호출 |
| B4 | 3.5 R4-1 · R4-2 (집계) | `Connection refused` 14건 전부 `API-2:8080`, 14:42:01 ~ 14:50:52 에만 등장 |

## 6. 수정안 (지금 당장)

> 코드는 아직 고치지 않았다. 적용하려면 별도 작업으로 진행한다.

| # | 파일 | 무엇을 어떻게 |
|---|---|---|
| F1 | `modern/api/src/main/java/com/example/assignment/ReportService.java` | `buildClassReport`(`:41`)에서 `@Transactional` 을 뺀다. DB 조회 부분(`:43`–`:67`)은 같은 도메인의 새 서비스로 옮긴다. 예: `ReportDataService.loadClassReportData(Integer classId)`, `@Transactional(readOnly = true)`, 반환은 payload · 제출 수 · 평균을 담은 record. `buildClassReport` 는 그 결과를 받은 **뒤에** `sign(...)`(`:71`)을 부른다. 같은 클래스 안의 메서드 호출은 Spring 프록시를 거치지 않아 트랜잭션이 적용되지 않으므로, 반드시 별도 빈으로 나눈다. `:69`–`:70` TODO 는 지운다. |
| F2 | `modern/api/src/test/java/com/example/assignment/ReportServiceTest.java` 와 새 `ReportDataServiceTest.java` | CLAUDE.md 2절 기준에 맞춰 테스트를 둔다. 새 서비스 메서드는 Mockito 단위 테스트로 검증한다. `buildClassReport` 는 서명 전에 데이터 로드가 끝나는지, 응답 값이 바뀌지 않는지 검증한다. |
| F3 | `modern/api/src/main/resources/application.yml` | **바꾸지 않는다.** `hikari` 설정은 CLAUDE.md 3절이 변경을 금지하고 있다. 풀을 키워도 같은 구조에서 고갈 시점이 늦춰질 뿐이다. |

## 7. 재발 방지 (구조적 장치)

| # | 장치 | 막는 것 |
|---|---|---|
| P1 | CLAUDE.md 3절은 지금 `@Transactional` 안의 HTTP 클라이언트 호출만 금지한다. 이 규칙을 "`@Transactional` 메서드 안에서는 DB 작업만 한다. 외부 호출 · CPU 반복 · 파일 I/O 는 트랜잭션 밖에서 한다."로 넓힌다. 같은 항목을 `templates/approval-checklist.md` 의 컨벤션 기준에도 넣는다. | 트랜잭션 안의 장기 작업(B1)이 리뷰를 통과하는 것 |
| P2 | 위 규칙을 정적 검사로 강제한다. ArchUnit 같은 아키텍처 테스트 도입은 의존성 추가라 CLAUDE.md 3절에 따라 승인이 필요하다. 승인 전에는 `convention-check` 스킬 점검 항목에 넣는다. | 사람이 놓치는 경우 |
| P3 | 무거운 엔드포인트의 동시 실행 상한(bulkhead)을 둔다. 예: 리포트는 동시에 2건까지만 실행하고, 넘치면 즉시 503 을 돌려준다. 상한은 풀 크기보다 작게 잡는다. | 한 경로가 풀 전체를 차지하는 것(B3) |
| P4 | 리포트 결과를 학급 단위로 짧게 캐시한다. 같은 학급이면 결과가 같다(`built class report` 가 하루 내내 같은 값, `:188`). | 반복 새로고침(C1)이 그대로 DB · CPU 부하가 되는 것 |
| P5 | nginx 설정을 점검한다(인프라 담당). `proxy_read_timeout` 을 앱 처리 상한과 맞추고, 예비 upstream `API-2` 를 살리거나 구성에서 뺀다. 한 경로의 타임아웃이 서버 전체 차단(`max_fails`)으로 번지지 않게 한다. | 영향 확대(C5 · C6, B4) |
| P6 | 리포트 동시 요청 부하 테스트를 배포 전 검증에 넣는다. 예: 동시 6건에서 다른 경로가 500 을 내지 않는지 본다. | 같은 유형의 회귀가 배포되는 것 |

## 8. 모니터링 항목

> 이번 타임라인을 기준으로, 각 경보가 언제 울렸을지 계산했다. Hikari 지표는 이번에 수집되지 않았으므로 로그로 재구성한 추정값이다.

| 지표 | 임계값 (제안) | 평소 | 이번에 울렸을 시각 (KST) | 근거 |
|---|---|---|---|---|
| Hikari leak 경고 수 | 5분에 1건 이상 | 0 | **14:37:52** (사용자 영향 3분 전) | `app.log:1243` |
| 커넥션 1회 보유 시간 최댓값 | 10 s 초과 | 10 s 미만 (leak 경고 0건) | **14:38:02** (첫 반환, 보유 19.7 s) | `app.log:1259` |
| 엔드포인트별 요청 수: 리포트 | 1분에 5건 초과 | 분당 최대 2 | **14:40** 분 (10건) | 3.5 R3-2 |
| 풀 사용률 (active / max) | 100 % 가 15 s 이상 지속 | 미측정 | **≈14:40:41** (5/5 도달 14:40:25 + 15 s, 추정) | 5절 C3 재구성 |
| 풀 대기 스레드 수 (pending) | 0 초과가 5 s 이상 지속 | 미측정 | **늦어도 14:40:54** (첫 타임아웃 기준 대기 시작 14:40:49 + 5 s) | `app.log:1522` |
| 커넥션 획득 타임아웃 ERROR | 1건 이상 | 0 | **14:40:52** | `app.log:1522` |
| 5xx 비율 (nginx) | 1분에 5 % 초과 | 0 % | **14:41** 분 (9 / 42 = 21 %) | 3.5 집계 |
| nginx upstream 차단 · `no live upstreams` | 1건 이상 | 0 | **14:42:01** | `nginx-error.log:10` |
| 리포트 응답 시간 p95 | 5 s 초과 | 미측정 | 판단 불가 (`$request_time` 미기록) | 9절 |

- 위 네 줄(leak · 보유 시간 · 리포트 요청 수 · 풀 사용률)은 사용자 영향(14:40:52)보다 먼저 울린다. 앞의 두 지표는 3분 먼저다. 그 아래 지표들은 영향이 시작된 뒤에야 울린다.

## 9. 확인하지 못한 것

| 판단하지 못한 부분 | 없었던 로그 · 자료 | 다음을 위해 남길 것 |
|---|---|---|
| 평소 단건은 10 s 미만이었는데 사고 중에는 15.5 ~ 21.5 s 를 쥔 이유. CPU 경합인지 확인하지 못했다(O1). | 앱 서버 CPU 사용률 · GC 로그 · 스레드 덤프 | 호스트 CPU 지표, JVM GC 로그(`-Xlog:gc`), 풀 타임아웃이 날 때 스레드 덤프 |
| 리포트 요청별 실제 처리 시간, nginx `proxy_read_timeout` 값 | access log 에 `$request_time` · `$upstream_response_time` 없음, nginx 설정 없음 | nginx `log_format` 에 두 필드 추가, `nginx -T` 결과를 사고 기록에 첨부 |
| 예비 upstream `API-2` 가 왜 연결을 거부했는지, 의도된 구성인지 | `API-2` 의 로그 · 상태 | `API-2` 의 애플리케이션 · 시스템 로그, upstream 구성 문서 |
| 14:37:42 쿼리가 128만 행을 검사한 이유 | 실행 계획 · 테이블 통계 | 운영 DB `EXPLAIN` · `ANALYZE TABLE` 결과, `long_query_time` 설정 값 |
| 리포트 화면이 같은 리포트를 반복 호출한 이유(사용자 새로고침인지 화면 자동 갱신인지) | LMS 프런트엔드 코드 · 로그(저장소 밖) | LMS 팀 확인, 프런트 요청에 호출 사유 헤더 추가 검토 |
| 사고일 이전 배포 · 설정 변경 여부(H5) | 앱 기동 로그, 배포 이력, 사고일 이전 로그 | 기동 시 Hikari 설정 출력 보존, 배포 이력 기록, 로그 보존 기간 연장 |
| Hikari 풀 상태의 실측값(active · pending · 보유 시간). 이번에는 leak 경고로 추정했다. | Hikari 지표 | Micrometer · Actuator 지표 노출. 의존성 추가라 CLAUDE.md 3절에 따라 승인 필요. 승인 전 대안은 `ReportService` 처리 시간(ms)을 INFO 로그에 남기는 것 |

마스킹 적용: IP 52건(역할 별칭 49 · `10.20.x.x` 3) · 이메일 0건 · 학생 식별자 0건 · 토큰 · 세션 ID 1건 · 비밀번호 · 접속 문자열 0건
