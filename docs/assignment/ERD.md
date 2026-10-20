# assignment-thymeleaf ERD — "새 배포" 화면

대상: `legacy/assignment-thymeleaf`. 모듈 파일 경로 앞의 `legacy/assignment-thymeleaf/src/main/` 은 생략하고, 모듈 밖 파일은 전체 경로로 적는다.

**분석 범위(제한)**: "새 배포" 화면(`GET /distributions/new`, `POST /distributions`)과 그 화면이 호출하는 코드만 다룬다. 범위 밖 SQL(배포 목록 `DistributionDao.findAllForList`, 재배포, 과제 목록)은 다루지 않는다. 자세한 범위는 `ARCHITECTURE.md` 머리말을 보라.

스키마 근거: `db/mariadb/init/01-schema.sql`. 시드 근거: `db/mariadb/init/02-seed.sql`. SELECT 확인은 2026-09-29 에 `localhost:3306` 의 `itembank` 에 `readonly` 계정으로 실행했다.

## 1. 테이블 · 뷰 목록

| 테이블 | 이 화면에서 | 주요 컬럼 | 키 · 제약 | 근거 |
|---|---|---|---|---|
| `assignment` | 읽기 | `id INT`, `title VARCHAR(200)`, `unit_id INT NOT NULL`, `due_at DATETIME NOT NULL`, `status CHAR(1) NOT NULL DEFAULT 'O'` | PK `id`(AUTO_INCREMENT 아님), FK `unit_id → unit.id` | `db/mariadb/init/01-schema.sql:82-90` |
| `unit` | 읽기(조인) | `id INT`, `code VARCHAR(16)`, `name VARCHAR(100)` | PK `id`, UNIQUE `code` | `db/mariadb/init/01-schema.sql:11-18` |
| `class` | 읽기 | `id INT`, `name VARCHAR(50)`, `teacher_id VARCHAR(20)` | PK `id`(AUTO_INCREMENT 아님) | `db/mariadb/init/01-schema.sql:75-80` |
| `distribution` | 읽기 · **쓰기(INSERT)** | `id INT`, `assignment_id INT`, `class_id INT`, `distributed_at DATETIME NOT NULL`, `redistributed TINYINT NOT NULL DEFAULT 0` | PK `id`(AUTO_INCREMENT 아님), FK 2개, 일반 인덱스 2개, **(assignment_id, class_id) UNIQUE 없음** | `db/mariadb/init/01-schema.sql:92-103` |
| `submission` | 사용 안 함 | — | FK `distribution_id → distribution.id` | `db/mariadb/init/01-schema.sql:105-115` |

- 뷰: DB 에 뷰는 `v_item_public` 하나뿐이고(`information_schema.VIEWS` 조회), 이 화면은 뷰를 쓰지 않는다.
- 문자셋 · 콜레이션: 네 테이블 모두 `utf8mb4_unicode_ci` 다(`01-schema.sql:80`, `:90`, `:103`, `:115`). 이 화면의 SQL 에는 문자열 비교 조건이 `a.status IN ('O','X','C')` 뿐이라(`java/com/example/assign/dao/AssignmentDao.java:36`) 콜레이션 영향은 그 조건에만 해당한다(`BUSINESS-RULES.md` BR-01 비고).

## 2. 관계

```mermaid
erDiagram
    unit ||--o{ assignment : "unit_id (FK fk_assignment_unit)"
    assignment ||--o{ distribution : "assignment_id (FK fk_distribution_assignment)"
    class ||--o{ distribution : "class_id (FK fk_distribution_class)"
    distribution ||--o{ submission : "distribution_id (FK, 이 화면 범위 밖)"

    unit {
        INT id PK
        VARCHAR code UK
        VARCHAR name
        TINYINT grade
    }
    assignment {
        INT id PK
        VARCHAR title
        INT unit_id FK
        DATETIME due_at
        CHAR status "스키마 주석 O/C, 코드는 O/X/C/D"
    }
    class {
        INT id PK
        VARCHAR name
        VARCHAR teacher_id
    }
    distribution {
        INT id PK "MAX(id)+1 로 채번"
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at
        TINYINT redistributed
    }
    submission {
        INT id PK
        INT distribution_id FK
        VARCHAR student_id
        DATETIME submitted_at
        DECIMAL score
    }
```

| 관계 | 종류 | 근거 |
|---|---|---|
| `assignment.unit_id → unit.id` | FK | `db/mariadb/init/01-schema.sql:89` |
| 〃 | 조인 `assignment a LEFT JOIN unit u ON u.id = a.unit_id` | `java/com/example/assign/dao/AssignmentDao.java:24` |
| `distribution.assignment_id → assignment.id` | FK | `db/mariadb/init/01-schema.sql:101` |
| 〃 | 상관 서브쿼리 `WHERE d.assignment_id = a.id` | `AssignmentDao.java:23` |
| `distribution.class_id → class.id` | FK | `db/mariadb/init/01-schema.sql:102` |
| (assignment_id, class_id) 조합 | **DB 제약 없음.** 앱이 COUNT 로 막는다 | `db/mariadb/init/01-schema.sql:98-100`, `java/com/example/assign/dao/DistributionDao.java:46-52` |

- `unit_id` 는 `NOT NULL` + FK 라서(`01-schema.sql:85`, `:89`) `LEFT JOIN unit` 이 짝 없는 행을 만들 수 없다. 실제로는 INNER JOIN 과 결과가 같다.
- `SHOW INDEX FROM distribution` 결과: `PRIMARY(id)`, `idx_distribution_assignment(assignment_id)`, `idx_distribution_class(class_id)`, 셋 뿐이고 복합 UNIQUE 는 없다.
- 시드에는 이미 같은 (과제 2, 학급 1) 조합이 2건 있다(배포 3 · 4, `db/mariadb/init/02-seed.sql:133-134`). 시드 주석은 이를 재배포 이력이라고 한다(`02-seed.sql:128`). 중복 검사가 COUNT > 0 이라 이 조합의 새 배포는 막힌다(`BUSINESS-RULES.md` BR-11).

## 3. 읽기 · 쓰기 위치

| 파일:줄 | 메서드 | 테이블 | R/W | SQL 요지 | 호출하는 쪽 |
|---|---|---|---|---|---|
| `java/com/example/assign/dao/AssignmentDao.java:21-24`, `:34-39` | `findAllForSelect` | `assignment`, `unit`, `distribution` | R | `BASE_SELECT … WHERE a.status IN ('O','X','C') ORDER BY a.due_at ASC, a.id ASC` | `DistributionController.java:42` (GET 폼) |
| `AssignmentDao.java:21-24`, `:41-48` | `findById` | `assignment`, `unit`, `distribution` | R | `BASE_SELECT WHERE a.id = ?` (없으면 `null`) | `DistributionController.java:53`, `DistributionService.java:48` (POST) |
| `java/com/example/assign/dao/ClassDao.java:20-22` | `findAll` | `class` | R | ``SELECT id, name, teacher_id FROM `class` ORDER BY id ASC`` | `DistributionController.java:43` (GET 폼) |
| `ClassDao.java:24-30` | `findById` | `class` | R | ``… FROM `class` WHERE id = ?`` (없으면 `null`) | `DistributionService.java:52` (POST) |
| `java/com/example/assign/dao/DistributionDao.java:47-52` | `countByAssignmentAndClass` | `distribution` | R | `SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?` | `DistributionService.java:59` (POST) |
| `DistributionDao.java:54-57` | `nextId` | `distribution` | R | `SELECT COALESCE(MAX(id), 0) FROM distribution` 에 +1 | `DistributionService.java:62` (POST) |
| `DistributionDao.java:59-63` | `insert` | `distribution` | **W** | `INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)` | `DistributionService.java:63` (POST) |

- `BASE_SELECT` 의 `class_cnt`(`AssignmentDao.java:23`, 과제별 배포 학급 수)는 매 조회마다 계산되지만 `distribution_form.html` 은 이 값을 쓰지 않는다(템플릿이 참조하는 속성: `a.id`, `a.title`, `a.unitCode`, `a.dueAt`, `a.status` — `resources/templates/distribution_form.html:15-18`).
- 이 화면에서 쓰기는 `distribution` INSERT 1곳뿐이다. `assignment` · `class` · `unit` · `submission` 에는 쓰지 않는다.
- `distributed_at` 에는 DB `NOW()` 가 아니라 `AppClock.now()` 값이 들어간다(`DistributionService.java:63`, `DistributionDao.java:62`). 현재 설정에서는 항상 `2026-09-15 00:00:00` 이다(`java/com/example/assign/AppClock.java:9`, `:19`).

### SELECT 확인 결과 (2026-09-29, 시드와 동일)

| 조회 | 결과 |
|---|---|
| `SELECT status, COUNT(*) FROM assignment GROUP BY status` | `C` 2건, `O` 4건. **`X`(연장) · `D`(삭제) 상태 행은 없다.** |
| `SELECT … FROM distribution` | 8건, `MAX(id)=8` → 다음 `nextId()` 는 9 |
| `SELECT * FROM class` | 3건(id 1~3) |
| `SELECT @@tx_isolation` | `REPEATABLE-READ` |

## 4. 미확인

- **`status` 값의 정의.** 스키마 주석은 `O=진행 C=마감` 두 값만 적는다(`db/mariadb/init/01-schema.sql:87`). 코드는 `X`(연장, `DistributionController.java:59-61`)와 `D`(삭제, `DistributionService.java:56`, `AssignmentDao.java:26`)도 쓴다. 시드와 현재 DB 에 `X` · `D` 행이 없어서 SELECT 로는 확인할 수 없었다. 누가 `X` · `D` 로 바꾸는지는 이 화면 범위 안에 없다.
- **INSERT 실제 동작.** 쓰기를 실행해 확인하지 않는다는 원칙에 따라 `POST /distributions` 는 실행하지 않았다. `nextId` → `insert` 가 실제로 id 9 를 쓰는지, FK 위반 시 어떤 메시지가 나오는지는 코드로만 판단했다.
- **동시 배포.** `MAX(id)+1` 채번과 COUNT 중복 검사는 두 요청이 겹치면 PK 충돌 또는 중복 행을 만들 수 있어 보인다(REPEATABLE-READ 에서 일반 SELECT 는 잠금을 걸지 않음). 실행으로 확인하지 않았다.
- `distributed_at` · `due_at` 의 시간대: DB `time_zone=SYSTEM`(`KST`), 앱은 `LocalDateTime` 을 그대로 `Timestamp` 로 넘긴다(`DistributionDao.java:62`). 컨테이너 JVM 시간대는 확인하지 않았으나, 현재는 `AppClock` 고정값이라 JVM 시간대가 결과에 영향을 주지 않는다.
