판정: 반려

검증 대상: `upstream/main...HEAD -- modern characterization` (18개 파일, +2413/-3. 대상 경로를 건드린 커밋: 15560f0 · cb4ffb3 · 8df3da6 · 77660cd)   요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.

> 수행 방식: 3단계(체크리스트 대조)는 `reviewer`, 4단계(테스트 실행)는 `tester` Sub-agent 에 위임했고, 최종 판정은 메인 세션이 내렸다. 두 보고 사이에 사실이 엇갈린 지점은 없다(reviewer 가 지적한 테스트 누락을 tester 도 "부족"으로 판단하고 테스트를 추가함).

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 (단, 추가 테스트는 미커밋) | `cd modern/api && ./gradlew test` → exit 0, BUILD SUCCESSFUL, 66 통과 · 0 실패 · 0 건너뜀.<br>`cd characterization && npm test`(레거시) → exit 0, 3 파일 · 32 통과 · 0 실패.<br>`cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → exit 0, 31 통과 · 0 실패 · 1 건너뜀(`characterization/tests/example-units.test.js:18,21` `it.skipIf` — 설계상 건너뜀). 스냅샷 차이 없음.<br>`modern/web` 변경 없음 → 해당 없음.<br>66건에는 tester 가 이번에 추가한 테스트(아래 "tester 가 추가한 테스트")가 포함된다. 이 파일들은 작업 트리에만 있고 HEAD 에는 없으므로, 커밋 전까지 브랜치 자체는 이슈 #2 · #5 의 테스트 누락 상태다 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 · 개인정보 로깅 없음(`ItemSearchService.java:52` 는 페이지 · 건수만 기록), 읽기 전용 트랜잭션, SQL 은 Criteria/Specification 바인딩 |
| 요청 범위 이탈 없음 | **미충족** | 요청 = 엔드포인트 1개(`legacy/item-bank-php/search.php` → `GET /api/items/search`) 이관. 이 문장으로 설명되지 않는 변경: #1 `GET /api/units/item-counts`(커밋 77660cd, vendor pr-1), #4 `application.yml` 설정 변경 |
| 컨벤션 준수 | 충족 | 반려 사유 수준 위반 없음. 컨트롤러 → 서비스 계층, record `*Response` 반환, catch · `System.out` · 와일드카드 import 없음, 120자 초과 없음. 테스트 의무 누락(#2)은 tester 가 테스트를 추가해 해소(커밋 필요) |

> 대상 밖: `git status --porcelain` 에 커밋되지 않은 변경이 있다 — `.claude/skills/verify/SKILL.md`, `.gitignore`, `docs/rca/b-deploy-5xx.md`, `.claude/agents/`, `.claude/settings.json`, 그리고 tester 가 추가 · 수정한 테스트 4개 파일. 검증 대상(HEAD)에 포함되지 않는다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 (범위 미충족) | `modern/api/src/main/java/com/example/item/UnitController.java:25-29`, `UnitService.java:36-43`, `UnitItemCountResponse.java:1-9` | 커밋 77660cd(vendor pr-1)가 단원별 공개 문항 수 엔드포인트를 추가했다. `search.php` 이관과 무관하고 대응하는 레거시 화면도 없다(체크리스트 3) | 이 PR 에서 빼고 별도 요청 · PR 로 분리 |
| 2 | 경고 | `UnitService.java:36`, `UnitController.java:26` | HEAD 기준으로 새 public 서비스 메서드 · 새 엔드포인트에 테스트가 없다(`CLAUDE.md` §2 테스트). tester 가 `UnitServiceTest` · `UnitControllerTest` 에 테스트를 추가해 통과시켰으나 미커밋 | #1 에서 남기기로 하면 추가된 테스트를 커밋. 분리하면 그 PR 로 함께 이동 |
| 3 | 경고 | `UnitService.java:39` | 단원마다 `countByUnitIdAndStatus` 를 호출하는 N+1 쿼리. 한 트랜잭션이 단원 수만큼 쿼리를 실행하며 Hikari 풀은 5 | GROUP BY 집계 쿼리 1회로 변경(#1 분리 시 함께 처리) |
| 4 | 경고 (범위 미충족) | `modern/api/src/main/resources/application.yml:30-33` | `server.tomcat.relaxed-query-chars` 추가는 요청에 없는 설정 변경이고 서버 전체에 `" < > \ ^ ` { | }` 까지 허용한다. 인코딩되지 않은 `q[]=…` 레거시 호환이라는 근거는 있으나, MockMvc 는 Tomcat 을 거치지 않고 characterization 은 `URLSearchParams` 로 인코딩해 보내므로 테스트로 필요성이 검증되지 않는다. `datasource` · `hikari` 는 그대로라 `CLAUDE.md` §3 금지 항목은 아님 | 사람이 유지 · 축소(`[ ]` 만)를 결정. 축소하면 인코딩되지 않은 `\|` · `{` 요청이 레거시와 달리 400 이 되는 점을 함께 판단. 유지하면 이유와 영향 범위를 PR 설명에 기재 |
| 5 | 경고 | `modern/api/src/main/java/com/example/item/LegacyQueryParams.java:41` | 실제 요청 경로(`ItemController.java:36`, 쿼리 문자열이 있으면 `parse`)인 PHP `$_GET` 해석 200줄에 HEAD 기준 직접 테스트가 없다. 기존 MockMvc 테스트는 `.param()` 만 써서 이 분기를 타지 않고, characterization 에도 `q[]` · 중복 이름 케이스가 없다. tester 가 `LegacyQueryParamsTest`(10개) · `ItemControllerTest.searchItemsWithRawQueryUsesLegacyParsing` 를 추가해 통과시켰으나 미커밋 | 추가된 테스트를 커밋 |
| 6 | 제안 | `modern/api/src/test/java/com/example/item/ItemSearchServiceTest.java:28` | Spring 컨텍스트가 없는 Mockito 테스트에 `@ActiveProfiles("test")` — 효과 없음 | 제거 |
| 7 | 제안 | `modern/api/src/main/java/com/example/item/ItemController.java:34-37` | 원 쿼리 문자열 선택 · 해석 분기가 컨트롤러에 있다. 계층 위반은 아님 | 서비스나 별도 resolver 로 이동 검토 |

## tester 가 추가한 테스트 (미커밋)

- `modern/api/src/test/java/com/example/item/UnitServiceTest.java` (수정): `@Mock ItemRepository` 추가, `countActiveItemsByUnitKeepsUnitOrder` · `countActiveItemsByUnitIncludesZeroCountUnit` · `countActiveItemsByUnitCountsOnlyActiveStatus` · `countActiveItemsByUnitEmpty`
- `modern/api/src/test/java/com/example/item/UnitControllerTest.java` (신규): `listUnitsReturnsArray` · `getItemCountsReturnsArray` · `getItemCountsEmptyReturnsEmptyArray` · `getItemCountsServiceFailureReturns500`
- `modern/api/src/test/java/com/example/item/LegacyQueryParamsTest.java` (신규): `sameNameLastValueWins` · `arrayParamUsesFirstElement` · `scalarAndArrayLastOneWins` · `nestedArrayBecomesArrayString` · `urlDecodeFollowsPhp` · `missingEqualsAndEmptyPiecesFollowPhp` · `nameNormalizationFollowsPhp` · `nameIsCutAtNul` · `variablesBeyondMaxInputVarsAreDropped` · `arrayWithEmptyOrKeyedElementUsesFirstElement`
- `modern/api/src/test/java/com/example/item/ItemControllerTest.java` (수정): `searchItemsWithRawQueryUsesLegacyParsing`

## 레거시 대조 (`legacy/item-bank-php/search.php`)

일치: 배열 파라미터 첫 값(`:53-80`), trim · 100자(`:85-87`), LIKE 와일드카드 미이스케이프(`:94-97`), 단원 코드 비교(`:109-118`), 난이도 분기(`:214-229`), 태그 EXISTS · 50자(`:235-247`), 정렬 6종 · 기본 방향(`:266-327`), 페이지 보정(`:336-349`), 공개 문항 뷰 `status='A'`(`db/mariadb/init/01-schema.sql:52-70`), 0건 문구(`:657-658`). 레거시 버그를 "고친" 곳 없음.

`characterization/tests/item-bank.test.js` · `__snapshots__/item-bank.test.js.snap` 은 베이스라인 커밋 15560f0 에서만 추가됐고 이관 커밋(cb4ffb3 · 8df3da6 · 77660cd)에서 바뀌지 않았다.

## 의심 동작 (레거시와 같게 옮김 — 별도 보고)

1. `search.php:213-216` — 주석은 "1~5 모두 포함"이나 난이도를 비우면 `level < 5` 라 난이도 5 가 빠진다.
2. `search.php:94-97` — 키워드 `%` · `_` 미이스케이프로 와일드카드 동작.
3. `search.php:224-228` — 1~5 밖 난이도를 `(int)` 변환해 조회(`"abc"` → 0, `"1e3"` → 1000).
4. `search.php:337` — 페이지 값을 trim 하지 않아 `" 2"` 가 1페이지.
5. `search.php:295-297` — 단원 정렬의 2차 기준 `level DESC` 가 dir 과 무관하게 고정.
6. `search.php:657` — 마지막 페이지를 넘는 요청은 행 0개여도 0건 안내 문구가 나오지 않음.
7. `search.php:115-118` — 소문자 단원 코드도 그대로 조회해 결과가 DB 콜레이션에 달림.

## 확인 필요

- #1: item-counts 를 이 PR 에서 분리할지(분리하면 #2 · #3 은 이 PR 과 무관해짐).
- #4: Tomcat 설정을 유지 · 축소 · 되돌림 중 무엇으로 할지.
- LIKE 백슬래시: MariaDB 는 LIKE 기본 이스케이프가 `\` 다. Hibernate 가 ESCAPE 절을 붙이는지에 따라 `q=\` 처리가 레거시와 다를 수 있다(`ItemSearchSpecifications.java:42-43`). 생성 SQL 확인과 `q=\` 케이스가 필요.
- 응답에서 빠진 정보: 레거시 화면의 입력 경고 목록(`search.php:88` 등, 출력 `:652`) · 조건 요약 · 페이지 링크가 응답에 없다. 정규화 모양 `{status, rows, count, message}` 가 계약이면 문제없으나 의도 확인 필요.
- 오류 응답: 레거시는 쿼리 실패 시 200 과 "검색 중 오류가 발생했습니다"(`search.php:726-729`), modern 은 `GlobalExceptionHandler` 를 거쳐 500. 의도된 차이인지 확인 필요.
- 베이스라인 스냅샷이 레거시 대상으로 생성됐는지는 git 기록만으로 검증할 수 없다(레거시 대상 실행이 32건 모두 통과해 정합성은 간접 확인됨).
