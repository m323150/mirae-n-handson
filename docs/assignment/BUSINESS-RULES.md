# assignment-thymeleaf 비즈니스 규칙 후보 — "새 배포" 화면

대상: `legacy/assignment-thymeleaf`. 모듈 구조는 [ARCHITECTURE.md](ARCHITECTURE.md), 테이블은 [ERD.md](ERD.md) 를 따른다. 모듈 파일 경로 앞의 `legacy/assignment-thymeleaf/src/main/` 은 생략하고, 모듈 밖 파일은 전체 경로로 적는다. 줄번호만 있는 근거(`:62`)는 바로 앞에 적은 파일의 줄이다.

**분석 범위(제한)**: "새 배포" 화면(`GET /distributions/new`, `POST /distributions`)과 그 화면이 호출하는 코드만 분석했다. 배포 목록 · 재배포(`DistributionService.redistribute`, `java/com/example/assign/service/DistributionService.java:70-701`) · 과제 목록 화면의 규칙은 다루지 않는다.

분석 방법: `DistributionController.form` · `create`, `DistributionService.distribute`, DAO 7개 메서드, `distribution_form.html` 의 조건문 · SQL 과 `db/mariadb/init/01-schema.sql` 을 줄 단위로 읽었다. 실행 확인은 2026-09-29 에 실행 중인 로컬 컨테이너(`assignment` :8082, `mariadb` :3306)에 **`GET /distributions/new` 와 `readonly` 계정 SELECT 만** 보냈다. `POST /distributions` 는 `distribution` 에 INSERT 하므로 실행하지 않았다.

확신도 기준
- **확실**: 코드의 조건문 · SQL 로 동작을 확인했다.
- **확실 (실행 확인)**: 위에 더해 GET 요청 또는 SELECT 결과로 확인했다. 비고에 요청 · 쿼리를 적었다.
- **추정**: 이름 · 주석 · 문구 · 프레임워크 기본 동작으로 짐작했다.

작성 원칙
- 코드에 없는 규칙은 쓰지 않았다. 이 화면에는 배포 수정 · 삭제 · 취소 기능이 없다(`DistributionController` 의 매핑은 `@GetMapping` 2개, `@PostMapping` 2개뿐, `java/com/example/assign/web/DistributionController.java:32`, `:40`, `:49`, `:79`).
- 주석과 코드가 다르면 코드 기준으로 규칙을 쓰고 주석은 비고에 남겼다.

## 요약

| 구분 | 규칙 ID |
|---|---|
| 1. 폼 표시 (GET `/distributions/new`) | BR-01 ~ BR-05 |
| 2. 배포 검증 (POST `/distributions`) | BR-06 ~ BR-11 |
| 3. 저장 · 결과 | BR-12 ~ BR-14 |
| 4. 공통 (시각 · 상태 · 오류 · 입력) | BR-15 ~ BR-18 |

주의가 필요한 것
- **BR-15**: "현재 시각"이 `2026-09-15 00:00:00` 으로 고정돼 있다. 마감 판정(BR-03 · BR-08)과 배포 일시(BR-13)가 모두 이 값을 쓴다. 주석은 "2024-03 QA 기간 중 임시"라고 하지만 값은 2026년이다.
- **BR-16**: 상태 `C`(스키마 주석상 "마감")는 배포를 막지 않는다. 막는 기준은 `due_at` 뿐이다.
- **BR-03 · BR-08**: 마감 판정은 `isBefore`(엄격한 `<`)다. `due_at` 이 현재 시각과 **같으면** 배포할 수 있다.
- **BR-11**: 같은 과제 · 학급 조합은 앱의 COUNT 로만 막는다. DB UNIQUE 는 없다.

## 1. 폼 표시 (GET `/distributions/new`)

### BR-01 과제 선택 목록은 상태 O · X · C 만 보여 준다
- 규칙: `assignment.status` 가 `O`, `X`, `C` 중 하나인 과제만 선택 목록에 나온다. `D`(삭제)와 그 밖의 값은 나오지 않는다.
- 근거: `java/com/example/assign/dao/AssignmentDao.java:34-39`, `java/com/example/assign/web/DistributionController.java:42`
- 근거 코드:
  ```java
  public List<AssignmentRow> findAllForSelect() {
      String sql = BASE_SELECT
              + " WHERE a.status IN ('O', 'X', 'C') "
              + " ORDER BY a.due_at ASC, a.id ASC";
  ```
  ```java
  model.addAttribute("assignments", assignmentDao.findAllForSelect());
  ```
- 확신도: 확실 (실행 확인)
- 비고: `GET http://localhost:8082/distributions/new` 결과 `O` 4건 · `C` 2건, 6건이 모두 나왔다(`SELECT status, COUNT(*) FROM assignment GROUP BY status` 결과와 같음). `X` · `D` 행은 DB 에 없어 실행으로는 확인하지 못했다. 콜레이션이 `utf8mb4_unicode_ci`(`db/mariadb/init/01-schema.sql:90`)라서 대소문자를 구분하지 않는다. `SELECT _utf8mb4'x' COLLATE utf8mb4_unicode_ci IN ('O','X','C')` 는 `1` 을 돌려준다. 즉 소문자 `x` 도 목록에 나오지만, 연장 예외 판정(BR-03 · BR-08)은 Java/Thymeleaf 에서 대소문자를 구분하므로 연장으로 보지 않는다. 현재 DB 에 대문자 O/X/C/D 가 아닌 값은 0건이다(`SELECT COUNT(*) FROM assignment WHERE BINARY status NOT IN ('O','X','C','D')`). 범위 밖인 과제 목록 템플릿에는 `R`(검수중) 표시가 있다(`resources/templates/assignments.html:26`). `R` 과제는 이 필터에 걸려 선택 목록에 나오지 않는다(코드 판단, `R` 행이 없어 실행 확인은 없음).

### BR-02 과제 선택 목록은 마감 빠른 순, 같으면 id 순
- 규칙: 선택 목록은 `due_at` 오름차순으로, `due_at` 이 같으면 `id` 오름차순으로 정렬한다.
- 근거: `java/com/example/assign/dao/AssignmentDao.java:37`
- 근거 코드:
  ```java
  + " ORDER BY a.due_at ASC, a.id ASC";
  ```
- 확신도: 확실 (실행 확인)
- 비고: GET 결과 옵션 순서는 1, 2, 3, 4, **6, 5** 다. 과제 6(마감 09-30)이 과제 5(10-02)보다 앞에 온다. `due_at` 이 같은 과제가 시드에 없어서 두 번째 정렬 키(`id`)는 실행으로 확인하지 못했다.

### BR-03 마감이 지난 과제는 선택할 수 없다 (연장 X 는 예외)
- 규칙: `dueAt` 이 현재 시각(`now`)보다 **앞이고**(`isBefore`) 상태가 `'X'` 가 아니면 그 옵션에 `disabled` 가 붙는다. `dueAt == now` 이면 선택할 수 있다.
- 근거: `resources/templates/distribution_form.html:13-16`, `java/com/example/assign/web/DistributionController.java:44`
- 근거 코드:
  ```html
  <!-- 마감 지난 과제는 선택 불가 (연장 상태 제외) -->
  <option th:each="a : ${assignments}"
          th:value="${a.id}"
          th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
  ```
  ```java
  model.addAttribute("now", AppClock.now());
  ```
- 확신도: 확실 (실행 확인)
- 비고: GET 결과 과제 1 · 2 · 3 이 `disabled="disabled"`, 과제 4 · 5 · 6 은 선택할 수 있었다. 과제 3(마감 `2026-09-10 23:59`)이 막히고 과제 4(`2026-09-25 23:59`)가 열려 있는 것은 `now` 가 실제 오늘(09-29)이 아니라 고정값 `2026-09-15 00:00:00` 이기 때문이다(BR-15). `SELECT id, due_at < '2026-09-15 00:00:00' FROM assignment` 결과(1·2·3 = 1)와 같다. `dueAt == now` 경계와 `X` 예외는 해당 행이 없어 실행으로 확인하지 못했다. `due_at` 은 `NOT NULL` 이라(`db/mariadb/init/01-schema.sql:86`) `a.dueAt != null` 은 항상 참이다. 이것은 **화면 제한일 뿐이다.** 서버 검사는 BR-08 에서 따로 한다.

### BR-04 과제 옵션 문구에 마감 · 연장 표시를 붙인다
- 규칙: 옵션 문구는 `<id>. <제목> (<단원코드>, 마감 MM-dd HH:mm)` 이고, 마감이 지났으면 상태가 `X` 일 때 ` [연장]`, 아니면 ` [마감]` 을 덧붙인다.
- 근거: `resources/templates/distribution_form.html:17-18`
- 근거 코드:
  ```html
  th:text="${a.id} + '. ' + ${a.title} + ' (' + ${a.unitCode} + ', 마감 ' + ${#temporals.format(a.dueAt, 'MM-dd HH:mm')} + ')'
           + (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"
  ```
- 확신도: 확실 (실행 확인)
- 비고: GET 결과 `1. 분수의 덧셈 연습 (M5-1, 마감 08-20 23:59) [마감]`, `4. 분수의 나눗셈 도전 (M6-1, 마감 09-25 23:59)`. 날짜에 연도가 없다. `[연장]` 표시는 `X` 행이 없어 확인하지 못했다. 표시 기준은 `due_at` 이고 상태 `C` 와는 무관하다(BR-16).

### BR-05 학급 선택 목록은 모든 학급을 id 순으로 보여 준다
- 규칙: `class` 테이블의 모든 행을 조건 없이 `id` 오름차순으로 보여 주고, 문구는 `<학급명> (<teacher_id>)` 다. 교사별 · 학년별 필터나 "이미 배포된 학급" 제외는 없다.
- 근거: `java/com/example/assign/dao/ClassDao.java:21`, `resources/templates/distribution_form.html:25`
- 근거 코드:
  ```java
  return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
  ```html
  <option th:each="c : ${classes}" th:value="${c.id}" th:text="${c.name} + ' (' + ${c.teacherId} + ')'"></option>
  ```
- 확신도: 확실 (실행 확인)
- 비고: GET 결과 `5학년 1반 (teacher-01)`, `5학년 2반 (teacher-01)`, `6학년 1반 (teacher-02)` 3건. `SELECT * FROM class` 와 같다. 과제 학년(단원)과 학급 학년이 맞는지 검사하지 않는다.

## 2. 배포 검증 (POST `/distributions`)

### BR-06 검사 순서: 과제 존재 → 마감 → 학급 존재 → 삭제 상태 → 중복
- 규칙: POST 는 아래 순서로 검사하고, 처음 실패한 검사의 메시지 하나만 보여 준다. ① 과제 존재(컨트롤러) ② 마감(컨트롤러) ③ 과제 존재 · 학급 존재(서비스) ④ 삭제 상태(서비스) ⑤ 중복(서비스).
- 근거: `java/com/example/assign/web/DistributionController.java:53-67`, `java/com/example/assign/service/DistributionService.java:48-61`
- 근거 코드:
  ```java
  AssignmentRow a = assignmentDao.findById(assignmentId);
  if (a == null) {
  ...
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
  ...
      long id = distributionService.distribute(assignmentId, classId);
  ```
  ```java
  AssignmentRow a = assignmentDao.findById(assignmentId);
  if (a == null) {
  ...
  ClassRow c = classDao.findById(classId);
  if (c == null) {
  ...
  if ("D".equals(a.getStatus())) {
  ...
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
  ```
- 확신도: 확실
- 비고: 결과로, 마감이 지나고 `X` 가 아닌 `D` 과제는 "삭제" 메시지가 아니라 "마감" 메시지를 받는다. 없는 학급 + 마감 지난 과제도 "마감" 메시지다. POST 를 실행하지 않았으므로 실행 확인은 없다. 관련: BR-07 ~ BR-11.

### BR-07 없는 과제는 배포할 수 없다
- 규칙: `assignmentId` 에 해당하는 `assignment` 행이 없으면 flash 오류 "존재하지 않는 과제입니다." 와 함께 `/distributions/new` 로 돌아간다.
- 근거: `java/com/example/assign/web/DistributionController.java:53-57`, `java/com/example/assign/dao/AssignmentDao.java:41-48`
- 근거 코드:
  ```java
  AssignmentRow a = assignmentDao.findById(assignmentId);
  if (a == null) {
      ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
      return "redirect:/distributions/new";
  }
  ```
- 확신도: 확실
- 비고: `findById` 에는 상태 조건이 없다(`AssignmentDao.java:42` `BASE_SELECT + " WHERE a.id = ?"`). 따라서 선택 목록에 없는 `D` 과제도 여기서는 "존재함"으로 통과한다(BR-10 이 막음). 서비스에도 같은 검사가 있고 메시지에 id 가 붙는다(`DistributionService.java:49-51`, `"존재하지 않는 과제입니다. (id=" + assignmentId + ")"`). 컨트롤러가 먼저 걸러서 정상 흐름에서는 서비스 메시지가 나오지 않는다.

### BR-08 마감이 지난 과제는 새로 배포할 수 없다 (연장 X 는 예외)
- 규칙: `dueAt.isBefore(now)` 이고 상태가 `"X"` 가 아니면 flash 오류 `마감(yyyy-MM-dd HH:mm)이 지난 과제는 배포할 수 없습니다. [<제목>]` 과 함께 `/distributions/new` 로 돌아간다. `dueAt == now` 이거나 상태가 `"X"`(대문자) 이면 통과한다.
- 근거: `java/com/example/assign/web/DistributionController.java:58-65`, `java/com/example/assign/AppClock.java:12`, `AppClock.java:22-27`
- 근거 코드:
  ```java
  LocalDateTime now = AppClock.now();
  // 마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
      if (!"X".equals(a.getStatus())) {
          ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");
          return "redirect:/distributions/new";
      }
  }
  ```
  ```java
  public static final DateTimeFormatter FMT_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  ```
- 확신도: 확실
- 비고: 이 검사는 **컨트롤러에만 있다.** `DistributionService.distribute` 에는 마감 검사가 없어서(`DistributionService.java:47-68`) 서비스를 직접 호출하면 우회된다. 화면의 `disabled`(BR-03)는 요청을 조작하면 우회되지만 이 서버 검사는 남는다. 주석("연장(X) 상태만 예외")과 코드는 일치한다. `"X".equals` 는 대소문자를 구분한다(BR-01 비고). 현재 시각은 고정값이다(BR-15). POST 미실행.

### BR-09 없는 학급에는 배포할 수 없다
- 규칙: `classId` 에 해당하는 `class` 행이 없으면 `IllegalArgumentException("존재하지 않는 학급입니다. (id=<classId>)")` 이 나고, 컨트롤러가 그 메시지를 flash 오류로 보여 주며 `/distributions/new` 로 돌아간다.
- 근거: `java/com/example/assign/service/DistributionService.java:52-55`, `java/com/example/assign/web/DistributionController.java:69-71`
- 근거 코드:
  ```java
  ClassRow c = classDao.findById(classId);
  if (c == null) {
      throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  }
  ```
  ```java
  } catch (IllegalArgumentException | IllegalStateException e) {
      ra.addFlashAttribute("error", e.getMessage());
      return "redirect:/distributions/new";
  ```
- 확신도: 확실
- 비고: 조회한 `c` 는 존재 검사에만 쓰고 그 뒤에는 쓰지 않는다. POST 미실행.

### BR-10 삭제된(D) 과제는 배포할 수 없다
- 규칙: 과제 상태가 `"D"` 이면 `IllegalStateException("삭제된 과제는 배포할 수 없습니다.")` 이 나고, flash 오류로 `/distributions/new` 에 돌아간다.
- 근거: `java/com/example/assign/service/DistributionService.java:56-58`
- 근거 코드:
  ```java
  if ("D".equals(a.getStatus())) {
      throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  }
  ```
- 확신도: 확실
- 비고: `D` 과제는 선택 목록에 나오지 않지만(BR-01), POST 로 id 를 직접 보내면 여기까지 온다. 마감이 지났으면 BR-08 이 먼저 막는다(BR-06). 대소문자를 구분하므로 소문자 `d` 는 막지 않는다. 스키마 주석에는 `D` 값이 없다(`db/mariadb/init/01-schema.sql:87` `-- O=진행 C=마감`). DB 에 `D` 행이 없어 실행 확인은 없다.

### BR-11 같은 과제를 같은 학급에 두 번 배포할 수 없다
- 규칙: `distribution` 에 같은 (`assignment_id`, `class_id`) 행이 1건 이상 있으면 `IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.")` 이 나고, flash 오류로 돌아간다.
- 근거: `java/com/example/assign/service/DistributionService.java:59-61`, `java/com/example/assign/dao/DistributionDao.java:46-52`
- 근거 코드:
  ```java
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
      throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
  }
  ```
  ```java
  // 같은 학급 + 같은 과제 는 한 건만 존재해야 한다 (UNIQUE 제약은 없음, 여기서 막는다)
  public int countByAssignmentAndClass(long assignmentId, long classId) {
      Integer n = jdbc.queryForObject(
              "SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?",
  ```
- 확신도: 확실
- 비고: COUNT 에는 `redistributed` 조건이 없어서 재배포 이력 행도 센다. 주석은 "한 건만 존재해야 한다"고 하지만, 시드에 이미 (과제 2, 학급 1) 조합이 2건 있다(배포 3 · 4, `db/mariadb/init/02-seed.sql:133-134`). 이 행들은 재배포가 만든 것으로 보인다(`02-seed.sql:128` 주석, 재배포 코드는 범위 밖). DB UNIQUE 가 없는 것은 `SHOW INDEX FROM distribution` 으로 확인했다(ERD.md 2절). 트랜잭션은 `REPEATABLE-READ` 이고 COUNT 는 잠금 없는 일반 SELECT 라서 동시 요청 두 건이 모두 통과할 수 있어 보인다. 실행 확인은 하지 않았다(ERD.md 4절).

## 3. 저장 · 결과

### BR-12 배포 id 는 MAX(id)+1 로 정한다
- 규칙: 새 배포 id 는 `distribution` 의 최대 id + 1 이다. 행이 없으면 1 이다.
- 근거: `java/com/example/assign/dao/DistributionDao.java:54-57`, `java/com/example/assign/service/DistributionService.java:62`, `db/mariadb/init/01-schema.sql:93`
- 근거 코드:
  ```java
  public long nextId() {
      Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
      return (max == null ? 0L : max.longValue()) + 1L;
  }
  ```
  ```sql
  id             INT      NOT NULL,
  ```
- 확신도: 확실
- 비고: 컬럼이 `AUTO_INCREMENT` 가 아니라서(`01-schema.sql:93`) 앱이 채번한다. 현재 `MAX(id)=8` 이므로 다음 id 는 9 다(SELECT 확인, INSERT 는 미실행). 동시 요청이면 같은 id 로 PK 충돌이 날 수 있고, 이 경우 `DataAccessException` 으로 BR-17 경로를 탄다(추정, 미확인).

### BR-13 배포 일시는 AppClock 값, 재배포 플래그는 0 으로 저장한다
- 규칙: INSERT 할 때 `distributed_at = AppClock.now()`, `redistributed = 0` 으로 저장한다. 영향 행 수가 1 이 아니면 `IllegalStateException("배포 저장에 실패했습니다.")` 이 난다.
- 근거: `java/com/example/assign/service/DistributionService.java:63-66`, `java/com/example/assign/dao/DistributionDao.java:59-63`
- 근거 코드:
  ```java
  int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
  if (n != 1) {
      throw new IllegalStateException("배포 저장에 실패했습니다.");
  }
  ```
  ```java
  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  id, assignmentId, classId, Timestamp.valueOf(distributedAt));
  ```
- 확신도: 확실
- 비고: 현재 설정에서 `distributed_at` 은 항상 `2026-09-15 00:00:00` 이 된다(BR-15). 단일 행 INSERT 는 성공하면 1 을, 실패하면 예외를 돌려주므로 `n != 1` 분기가 실행될 경로는 보이지 않는다(추정).

### BR-14 성공하면 배포 목록으로, 실패하면 폼으로 돌아간다
- 규칙: 배포가 저장되면 flash 메시지 `배포가 등록되었습니다. (배포 #<id>)` 와 함께 `/distributions` 로 리다이렉트한다. 검증 실패 · 서비스 예외는 flash `error` 와 함께 `/distributions/new` 로 리다이렉트한다.
- 근거: `java/com/example/assign/web/DistributionController.java:66-76`, `resources/templates/layout.html:28-30`
- 근거 코드:
  ```java
  try {
      long id = distributionService.distribute(assignmentId, classId);
      ra.addFlashAttribute("message", "배포가 등록되었습니다. (배포 #" + id + ")");
  } catch (IllegalArgumentException | IllegalStateException e) {
  ...
  return "redirect:/distributions";
  ```
  ```html
  <p class="msg" th:if="${message}" th:text="${message}"></p>
  <p class="err" th:if="${error}" th:text="${error}"></p>
  ```
- 확신도: 확실
- 비고: 실패 후 폼으로 돌아가면 선택했던 과제 · 학급 값이 유지되지 않는다(flash 에 입력값을 담지 않음, `DistributionController.java:55`, `:62`, `:70`, `:73`). POST 미실행.

## 4. 공통 (시각 · 상태 · 오류 · 입력)

### BR-15 현재 시각은 2026-09-15 00:00:00 으로 고정돼 있다
- 규칙: 시스템 속성 `app.clock` 이 `"system"` 이 아니면 `AppClock.now()` 는 항상 `2026-09-15 00:00:00` 을 돌려준다. `-Dapp.clock=system` 일 때만 실제 시각(나노초 0)을 쓴다.
- 근거: `java/com/example/assign/AppClock.java:8-9`, `AppClock.java:14-20`
- 근거 코드:
  ```java
  // 2024-03 QA 기간 중 임시로 고정. 운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)
  private static final String FIXED = "2026-09-15 00:00:00";
  ```
  ```java
  public static LocalDateTime now() {
      String sys = System.getProperty("app.clock");
      if (sys != null && sys.equals("system")) {
          return LocalDateTime.now().withNano(0);
      }
      return LocalDateTime.parse(FIXED, FMT);
  }
  ```
- 확신도: 확실 (실행 확인)
- 비고: 주석은 "2024-03 QA 기간"이라고 하지만 값은 2026-09-15 다(주석과 코드 불일치). 컨테이너 실행 명령에 `app.clock` 이 없다(`legacy/assignment-thymeleaf/Dockerfile:11`). 실행 확인: 2026-09-29 의 `GET /distributions/new` 에서 과제 4(마감 09-25)가 선택 가능했다. 실제 시각이었다면 막혔어야 한다(BR-03). 단위 테스트도 고정값을 기대한다(`legacy/assignment-thymeleaf/src/test/java/com/example/assign/AppClockTest.java:12`). 이관할 때 이 고정값을 옮길지 사람이 정해야 한다.

### BR-16 상태 C(마감)는 배포를 막지 않는다
- 규칙: 새 배포 화면과 POST 검사는 상태 `C` 를 보지 않는다. `C` 과제라도 `due_at` 이 현재 시각 이후이면 선택 · 배포할 수 있다.
- 근거: `java/com/example/assign/dao/AssignmentDao.java:36`, `resources/templates/distribution_form.html:16`, `java/com/example/assign/web/DistributionController.java:60-61`, `java/com/example/assign/service/DistributionService.java:56`, `db/mariadb/init/01-schema.sql:87`
- 근거 코드:
  ```java
  + " WHERE a.status IN ('O', 'X', 'C') "
  ```
  ```html
  th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
  ```
  ```java
  if ("D".equals(a.getStatus())) {
  ```
  ```sql
  status  CHAR(1)      NOT NULL DEFAULT 'O',        -- O=진행 C=마감
  ```
- 확신도: 확실 (코드 동작) / "C = 마감"이라는 의미는 추정(스키마 주석)
- 비고: 상태를 비교하는 곳은 `'X'`(연장)와 `'D'`(삭제)뿐이다. 시드의 `C` 과제 1 · 2 는 `due_at` 도 지나서 결과적으로 막혀 있다. 그래서 "C 면 막힌다"로 오해하기 쉽다. `C` 이면서 `due_at` 이 미래인 행이 없어 실행 확인은 없다.

### BR-17 DB 오류: 배포 중에는 폼 오류 문구, 그 밖에는 DB 오류 화면
- 규칙: `distribute` 실행 중 `DataAccessException` 이 나면 flash 오류 "DB 오류로 배포에 실패했습니다." 와 함께 폼으로 돌아간다. GET 폼 조회나 POST 첫 과제 조회(`try` 밖)에서 나면 `DbErrorAdvice` 가 `db_error` 화면에 상세 메시지를 보여 준다.
- 근거: `java/com/example/assign/web/DistributionController.java:72-74`, `java/com/example/assign/web/DbErrorAdvice.java:11-16`, `resources/templates/db_error.html:7-8`
- 근거 코드:
  ```java
  } catch (DataAccessException e) {
      ra.addFlashAttribute("error", "DB 오류로 배포에 실패했습니다.");
      return "redirect:/distributions/new";
  ```
  ```java
  @ExceptionHandler(DataAccessException.class)
  public String dbError(DataAccessException e, Model model) {
      System.out.println("[DB-ERROR] " + e.getClass().getSimpleName() + " : " + e.getMostSpecificCause().getMessage());
      model.addAttribute("menu", "");
      model.addAttribute("detail", e.getMostSpecificCause().getMessage());
      return "db_error";
  ```
- 확신도: 확실
- 비고: 컨트롤러의 `DataAccessException` catch 에는 로그가 없다(`DistributionController.java:72-74`). `DbErrorAdvice` 는 SQL 오류 원문을 화면(`db_error.html:8` `th:text="${detail}"`)과 표준출력에 그대로 낸다. HTTP 상태 코드는 지정하지 않아 200 으로 보인다(추정, 미확인). DB 를 멈춰야 재현되므로 실행 확인하지 않았다.

### BR-18 입력 형식: 두 값 모두 필수 숫자
- 규칙: 화면에서는 두 `select` 가 `required` 라 빈 값을 막는다. 서버에서는 `assignmentId` · `classId` 가 필수 `long` 파라미터라서, 없거나 숫자가 아니면 컨트롤러 메서드에 들어오기 전에 Spring 이 요청을 거부한다.
- 근거: `resources/templates/distribution_form.html:11`, `:23`, `java/com/example/assign/web/DistributionController.java:50-51`
- 근거 코드:
  ```html
  <select id="assignmentId" name="assignmentId" required>
  ```
  ```java
  public String create(@RequestParam("assignmentId") long assignmentId,
                       @RequestParam("classId") long classId,
  ```
- 확신도: 추정
- 비고: 모듈에는 `MissingServletRequestParameterException` · `MethodArgumentTypeMismatchException` 처리기가 없다(`DbErrorAdvice` 는 `DataAccessException` 만 처리함). Spring Boot 기본 오류 응답(400)이 나갈 것으로 추정한다. POST 라서 실행 확인하지 않았다. 음수 · 0 id 는 형식 검사를 통과하고 BR-07 · BR-09 존재 검사에서 걸린다.

## 5. 미확인

- `X`(연장) · `D`(삭제) 상태 행이 DB 에 없어서, 이 값이 들어간 BR-01 · BR-03 · BR-04 · BR-08 · BR-10 분기는 코드로만 확인했다. 이 값을 누가 쓰는지는 이 화면 범위 밖이다.
- `POST /distributions` 는 한 번도 실행하지 않았다(INSERT 발생). BR-06 ~ BR-14 · BR-18 의 실제 응답 · 리다이렉트 · flash 문구는 코드로만 확인했다.
- 동시 요청에서 BR-11(중복)과 BR-12(id 채번)가 깨지는지는 확인하지 않았다.
- `dueAt == now` 경계(BR-03 · BR-08)는 Java `isBefore` 의 의미로 판단했고, 마감이 정확히 `2026-09-15 00:00:00` 인 행이 없어 실행 확인하지 못했다.

## 6. 표본 점검 추천

사람이 직접 다시 확인할 규칙 3개다. 실행 확인은 GET · SELECT 만 쓴다(`POST /distributions` 금지).

| # | 규칙 | 고른 이유 |
|---|---|---|
| 1 | **BR-08** 마감 지난 과제 배포 금지 | **가장 그럴듯한 규칙.** 코드가 짧고 주석과도 일치해서 검증 없이 넘어가기 쉽다. 그런데 결과를 바꾸는 세부가 세 가지 있다. ① `isBefore` 라서 마감 시각과 같으면 통과한다. ② 기준 시각이 고정값 2026-09-15 다(BR-15). ③ 검사가 컨트롤러에만 있다. |
| 2 | **BR-11** 같은 과제 · 학급 중복 배포 금지 | **주석과 코드 · 데이터가 다르다.** 주석은 "한 건만 존재해야 한다"고 하지만, DB 제약은 없고 시드에 이미 같은 조합이 2건 있다. |
| 3 | **BR-16** 상태 C(마감)는 배포를 막지 않음 | **추정이 섞인 규칙.** "C = 마감"은 스키마 주석에서 가져온 의미다. 시드 데이터에서는 C 과제가 모두 마감일도 지나 있어서 "C 라서 막힌다"로 오해하기 쉽다. |

### 1. BR-08 확인 방법
- 재독할 줄: `java/com/example/assign/web/DistributionController.java:58-65`에서 `isBefore` 인지, `!` 부정의 위치, `"X".equals` 를 본다. `java/com/example/assign/AppClock.java:9`, `:14-20`에서 `now` 의 출처를 본다. `java/com/example/assign/service/DistributionService.java:47-68`에서 마감 검사가 **없는지** 본다.
- GET: `curl -s http://localhost:8082/distributions/new | grep -A1 '<option value="[34]"'` 를 실행한다. 과제 3(09-10 마감)은 `disabled`, 과제 4(09-25 마감)는 선택 가능해야 한다. 오늘(09-29) 기준이면 4 도 막혀야 하므로, 이 결과가 고정 시각의 증거다. 폼(BR-03)과 POST(BR-08)는 같은 식을 쓰므로 폼 결과로 판정식을 교차 확인할 수 있다.
- SELECT: `SELECT id, due_at, status, due_at < '2026-09-15 00:00:00' AS past FROM assignment ORDER BY id;` 에서 `past=1` 인 행(1 · 2 · 3)이 GET 의 `disabled` 행과 같아야 한다.

### 2. BR-11 확인 방법
- 재독할 줄: `java/com/example/assign/dao/DistributionDao.java:46-52`에서 COUNT 조건에 `redistributed` 가 없는지 본다. `java/com/example/assign/service/DistributionService.java:59-61`에서 `> 0` 비교를 본다. `db/mariadb/init/01-schema.sql:98-102`에서 UNIQUE 가 없는지 본다.
- SELECT:
  - `SELECT assignment_id, class_id, COUNT(*) FROM distribution GROUP BY assignment_id, class_id HAVING COUNT(*) > 1;` 결과로 (2, 1) 2건이 나와야 한다. 주석 "한 건만"과 다르다.
  - `SHOW INDEX FROM distribution;` 결과로 복합 UNIQUE 가 없어야 한다.

### 3. BR-16 확인 방법
- 재독할 줄: 아래 네 곳에서 상태 비교가 `'X'` · `'D'` 뿐이고 `'C'` 비교가 없는지 본다.
  - `java/com/example/assign/dao/AssignmentDao.java:36`
  - `resources/templates/distribution_form.html:16`, `:18`
  - `java/com/example/assign/web/DistributionController.java:61`
  - `java/com/example/assign/service/DistributionService.java:56`
- 다음 두 곳도 확인한다.
  - `grep -rn "'C'\|\"C\"" legacy/assignment-thymeleaf/src/main` 를 실행한다. 범위 안 파일에서는 `AssignmentDao.java:36` 한 줄만 나와야 한다. 2026-09-29 실행 결과 나머지 3건은 모두 범위 밖이다: `resources/templates/assignments.html:26`(과제 목록에서 `C` 를 '종료'로 표시하고, `R` 을 '검수중'으로 표시함), `DistributionService.java:190`, `:466`(재배포 코드). 과제 목록이 `C` 를 '마감'이 아니라 '종료'로 부르는 점은 의미를 추정할 때 참고한다.
  - `db/mariadb/init/01-schema.sql:87` 주석에서 C 의 의미를 본다.
- SELECT: `SELECT id, status, due_at, due_at >= '2026-09-15 00:00:00' AS future FROM assignment WHERE status = 'C';` 결과로 `future=0` 만 나온다. 즉 "C 이면서 마감 전" 표본이 없어서 GET 으로는 판별할 수 없다. 이 점을 확인하고 의미(C 가 배포를 막아야 하는지)는 업무 담당자에게 묻는다.
