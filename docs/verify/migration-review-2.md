판정: 반려

검증 대상: `upstream/main...HEAD` (커밋 6개: 15560f0 ~ 8df3da6, 29개 파일)   요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test --rerun` → BUILD SUCCESSFUL, 47개 실행 · 실패 0 · 오류 0 · 건너뜀 0.<br>`cd characterization && npm test`(레거시 :8081) → 3 파일 · 32 통과 · 0 실패.<br>`cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 31 통과 · 0 실패 · 1 건너뜀(`characterization/tests/example-units.test.js:21` `it.skipIf(legacyOnly)` — 이번 diff 밖의 기존 케이스). 실패 테스트 없음. `modern/web` 변경 없음 → 해당 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 없음, SQL 은 JPA Criteria(파라미터 바인딩, `ItemSearchSpecifications.java:41-74`), 데이터 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | **미충족** | 요청 = 엔드포인트 1개 이관. 이 문장으로 설명되지 않는 변경이 남아 있다: #1 `application.yml` 설정 변경, #2 선행 커밋의 문서 · Skill · 환경 파일과 새로 추가된 `docs/verify/migration-review-1.md` |
| 컨벤션 준수 | 충족 | 위반 없음(아래 "컨벤션 점검 요약") |

> 대상 밖: `git status --porcelain` 에 커밋되지 않은 변경이 있다 — `.gitignore`(`modern/api/bin/` 한 줄 추가), 새 폴더 `.claude/skills/verify/`. 검증 대상에 포함하지 않았다.

## 1차 리뷰(`migration-review-1.md`) 대비 변화

| 1차 # | 상태 | 근거 |
|---|---|---|
| 1 `modern/api/bin/**` 커밋 | **해소** | `8df3da6` 에서 20개 삭제, `git diff --stat upstream/main...HEAD` 에 `modern/api/bin` 없음. 단 재발 방지용 `.gitignore` 줄은 아직 커밋되지 않음(확인 필요 참고) |
| 2 `application.yml` `relaxed-query-chars` | 미해소 | 사람 결정 대기 → 이번 이슈 1 |
| 3 선행 커밋으로 범위 확대 | 미해소 | `docs/verify/migration-review-1.md` 가 추가돼 오히려 1개 늘어남 → 이번 이슈 2 |
| 4 `LegacyQueryParams` 전용 테스트 없음 | 미해소 | 1차의 줄번호 `:281` 은 없는 줄(파일 200줄). 이번 이슈 3 에서 줄번호 정정 |
| 5 · 6 제안 | 유지 | 1차의 `ItemSearchSpecifications.java:95-99` · `:97` 은 없는 줄(파일 75줄). 실제 위치는 `:41-44` |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/resources/application.yml:30-33` | 설정 변경(`server.tomcat.relaxed-query-chars`)이 요청 문장에 없다(체크리스트 3-4). 서버 전체 URL 의 쿼리 문자 검증을 푼다. 동작 보존 테스트로는 필요성을 확인할 수 없다 — `characterization/lib/target.mjs:42-43` 이 `URLSearchParams` 로 `[` `]` 를 인코딩해 보내므로 이 설정 없이도 같은 결과일 수 있다. `datasource` · `hikari` 는 건드리지 않아 `CLAUDE.md` §3 금지 항목은 아님 | 사람이 유지 · 되돌림을 결정. 유지하면 이유(인코딩되지 않은 `q[]=…` 직접 입력 호환)와 영향 범위(전 엔드포인트)를 PR 설명에 적고, 인코딩하지 않은 `[` `]` 로 요청하는 테스트를 추가 |
| 2 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `.claude/skills/impact/SKILL.md:1`, `docs/assignment/ARCHITECTURE.md:1`, `docs/verify/migration-review-1.md:1`, `.python-version:1`, `.gitignore:24-25` | 이관과 무관한 선행 커밋(`96b0e6f` 문서 · Skill, `dd6d8d5`, `beae271`)과 1차 리뷰 결과 문서가 diff 에 잡힌다. 체크리스트 3-2("변경 파일 목록의 모든 파일이 요청 문장으로 설명된다") 미충족. 내용 자체의 결함은 아니다 | 사람이 이번 머지 범위를 정한다. 이관만 머지하려면 `cb4ffb3` · `8df3da6`(의 `bin` 삭제)만 따로 올리고, 함께 머지하려면 요청 범위를 "이관 + 1회차 문서 · Skill · 검증 기록"으로 넓혀 다시 검증 |
| 3 | 경고 | `modern/api/src/main/java/com/example/item/LegacyQueryParams.java:41` | PHP `$_GET` 파싱을 옮긴 200줄 클래스(`parse`, `register`, `urlDecode`, `PhpArray`)에 전용 단위 테스트가 없다(`grep -rn LegacyQueryParams modern/api/src/test` 결과 없음). `ItemControllerTest` 는 서비스를 `@MockBean` 으로 바꾸고 `ItemSearchConditionTest` 는 `param()` 만 부른다. 같은 이름 반복 · 중첩 배열 · 잘못된 `%` · `MAX_INPUT_VARS`(`:29`) · `MAX_INPUT_NESTING_LEVEL`(`:31`) 분기는 characterization 에도 케이스가 없다. `CLAUDE.md` §2 테스트 의무(서비스 메서드 · 엔드포인트)는 충족하므로 컨벤션 위반은 아님 | `LegacyQueryParamsTest` 추가: `a=1&a=2`, `a[]=1&a[]=2`, `a=1&a[]=2`, `a[][]=1`(→ `"Array"`), `%zz` · 끝의 `%4`, 이름의 공백 · 점 → `_`, 1001번째 변수 버림 |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:49` | 페이지 조회(`:44`)와 상세 조회(`:47`) 사이에 문항이 사라지면 `details::get` 이 `null` 을 돌려 `ItemSearchRow.from` 에서 NPE(→ 500)가 난다. `readOnly` 트랜잭션 + MariaDB 기본 격리수준(REPEATABLE READ)에서는 같은 스냅샷이라 실제로는 일어나기 어렵다 | 필요하면 `filter(Objects::nonNull)` 로 방어. 이관 동작에는 영향 없음 |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchSpecifications.java:41-44` | 키워드 `LIKE` 에서 `%` · `_` 를 이스케이프하지 않는다. BR-07 대로의 레거시 동작 보존이므로 이관 중 고치지 않는 것이 맞다 | 변경 없음. 의심 동작으로 보고 |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:140-151` | `param()` 의 근사 규칙과 `LegacyQueryParams` 의 PHP 규칙이 이중으로 유지된다. 컨트롤러는 쿼리 문자열이 있으면 항상 `LegacyQueryParams` 를 쓰므로(`ItemController.java:36-37`) 근사 분기는 쿼리 문자열 없는 요청에서만 쓰인다 | 쿼리 문자열이 없으면 파라미터도 없다는 가정을 확인한 뒤 분기 단순화 검토 |

## 컨벤션 점검 요약 (`CLAUDE.md` §2 · §3 기준, 변경된 `modern/api/src` 대상)

- 컨트롤러는 서비스만 호출(`ItemController.java:36-37` → `ItemSearchService.search`), Repository · SQL 직접 호출 없음, try-catch 없음. 반환 타입은 record DTO(`ItemSearchResponse`, 행은 `ItemSearchRow`), 엔티티 노출 없음.
- 변경된 `.java` 에 `catch` · `System.out` · `System.err` · `printStackTrace` · 인자 없는 `now()` · HTTP 클라이언트 · 와일드카드 import · `@Disabled` 없음(grep 결과 0건), 120자 초과 줄 없음(awk 결과 0건).
- 로거는 `LoggerFactory.getLogger(ItemSearchService.class)`, 로그 인자는 페이지 번호 · 건수뿐(`ItemSearchService.java:52`).
- 경계값은 `static final` 상수(`ItemSearchCondition.java:35-47` `LEVEL_MIN` · `LEVEL_MAX` · `PAGE_SIZE` · `PAGE_MIN` · `PAGE_MAX` 등).
- 테스트: 새 서비스 메서드 `search` → `ItemSearchServiceTest`(Mockito, 3개), 새 엔드포인트 → `ItemControllerTest`(`@WebMvcTest` + `@MockBean`, 2개), 쿼리 → `ItemSearchRepositoryTest`(`@DataJpaTest`, 8개). Spring 테스트에 `@ActiveProfiles("test")`, `@DisplayName` 한국어.
- 의존성(`build.gradle`) · `db/` 변경 없음. `characterization/` 은 이번 브랜치에서 새로 추가된 테스트 · 스냅샷이고 기존 파일 수정 없음.

## 의심 동작 (레거시와 같게 옮긴 것, 고치지 않음)

- 난이도를 비우면 난이도 5 문항이 빠진다(`level < 5`, BR-11) — 레거시 `legacy/item-bank-php/search.php:214-215`, 이관 `ItemSearchSpecifications.java:54-55`. *1차 리뷰에 없던 항목.*
- 키워드의 `%` · `_` 를 와일드카드로 그대로 쓴다(BR-07) — `ItemSearchSpecifications.java:41-44`.
- 잘못된 난이도(`6`, `abc`)도 오류가 아니라 PHP `(int)` 값으로 조회하고 0건을 돌려준다(BR-04 · BR-12) — 레거시 `search.php:223-229`, 이관 `ItemSearchCondition.java:157-166`.

## 확인 필요

- 이번 머지 범위를 "이관 1건"으로 볼지, 선행 커밋 · 검증 기록까지 포함할지 사람이 정해 주세요. 이슈 2 의 판정이 달라지고, 이슈 1 이 승인되면 "요청 범위 이탈 없음"이 충족될 수 있습니다.
- `application.yml` 의 `relaxed-query-chars` 를 유지할지 사람 확인이 필요합니다(이슈 1).
- `.gitignore` 의 `modern/api/bin/` 줄이 커밋되지 않았습니다. 커밋하지 않으면 IDE 가 다시 만든 `modern/api/bin/` 이 또 커밋될 수 있습니다.
- 새 API 대상 characterization 은 이미 떠 있던 `localhost:8080` 서버로 돌렸습니다. `8df3da6` 은 `src` 를 바꾸지 않았으므로 1차 리뷰 때와 같은 코드여야 하지만, 그 서버가 `HEAD` 로 빌드된 것인지는 확인하지 못했습니다.
- 이 리뷰는 1차 리뷰 이슈 1 을 수정한 것과 **같은 세션**에서 실행했습니다. `templates/verification-loop.md` §1 의 "만든 쪽과 검증하는 쪽 분리"를 지키려면 새 세션에서 한 번 더 돌리는 것이 좋습니다.
