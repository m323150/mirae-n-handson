판정: 반려

검증 대상: `upstream/main...HEAD` (커밋 5개: 15560f0 ~ beae271, 48개 파일)   요청 범위: 레거시 엔드포인트 1개(`search.php` → `GET /api/items/search`)를 동작을 바꾸지 않고 modern 으로 이관.

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test` → BUILD SUCCESSFUL, 47개 실행 · 실패 0 · 오류 0 · 건너뜀 0.<br>`cd characterization && npm test`(레거시 :8081) → 3 파일 · 32 통과 · 0 실패.<br>`cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 31 통과 · 0 실패 · 1 건너뜀(`example-units.test.js:21` `it.skipIf(legacyOnly)` — 이번 diff 밖의 기존 케이스, item-bank 검색 케이스는 모두 통과). 실패 테스트 없음. `modern/web` 변경 없음 → 해당 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 없음, SQL 은 JPA Specification 파라미터 바인딩, 데이터 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | **미충족** | 요청 = 엔드포인트 1개 이관. 변경 파일 중 이 문장으로 설명되지 않는 것: #1 `modern/api/bin/**`(산출물 20개), #2 `application.yml` 설정 변경, #3 `CLAUDE.md` · `.claude/skills/{convention-check,document-module,impact}` · `docs/assignment/**` · `.python-version` · `.gitignore`(legacy bin 제외 2줄) |
| 컨벤션 준수 | 충족 | 위반 없음(아래 "컨벤션 점검 요약"). 컨벤션 위반 이슈 번호 없음 |

> 대상 밖: `git status --porcelain` 에 커밋되지 않은 새 파일 `.claude/skills/verify/` 가 있다. 검증 대상에 포함하지 않았다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/bin/main/com/example/item/*.class`(19개), `modern/api/bin/main/application.yml:1` | IDE/컴파일 산출물이 커밋됐다(`git ls-files modern/api/bin` 로 확인). `.gitignore:24-25` 는 `legacy/**/bin/` 만 제외하고 `modern/api/bin/` 은 제외하지 않는다. `bin/main/application.yml` 은 `src/main/resources/application.yml` 의 사본이라 이후 설정이 바뀌면 어긋난 사본이 남는다. 요청(엔드포인트 1개 이관)으로 설명되지 않는 파일이 diff 의 절반가량(48개 중 20개)이다 | `modern/api/bin/` 을 저장소에서 제거(`git rm -r --cached`)하고 `.gitignore` 에 추가. 만든 세션에서 사람과 수용 여부를 정한 뒤 수정 |
| 2 | 경고 | `modern/api/src/main/resources/application.yml:30-33` | 설정 파일 변경(`server.tomcat.relaxed-query-chars` 에 `" < > [ \ ] ^ \` { \| }` 허용). 체크리스트 3-4 는 설정 변경이 요청에 포함돼 있어야 한다고 본다. 요청 문장에는 없다. 또 이 설정은 `/api/items/search` 만이 아니라 서버 전체 URL 의 쿼리 문자 검증을 풀어, 다른 엔드포인트에도 적용된다. `datasource` · `hikari` 는 건드리지 않아 CLAUDE.md §3 의 금지 항목은 아님 | 레거시 `search.php?q[]=…` 호환에 필요한 변경인지 사람이 확인하고 승인. 승인되면 이관 이유를 PR 설명에 적고, 영향 범위(전 엔드포인트)를 명시 |
| 3 | 경고 | `CLAUDE.md`, `.claude/skills/convention-check/SKILL.md`, `.claude/skills/document-module/SKILL.md`, `.claude/skills/impact/SKILL.md`, `docs/assignment/*.md`, `.python-version`, `.gitignore:24-25` | `upstream/main` 기준 diff 라 이관과 무관한 선행 커밋(문서 · Skill · 환경 설정)이 함께 잡힌다. 체크리스트 3-2("변경 파일 목록의 모든 파일이 요청 문장으로 설명된다")를 충족하지 못한다. 내용 자체의 문제는 아니다 | 사람이 이 파일들을 이번 머지에 포함할지 결정. 분리하려면 이관 커밋(`cb4ffb3`)만 별도 PR 로 올리거나, 요청 범위를 "이관 + 1회차 문서 · Skill" 로 넓혀 다시 검증 |
| 4 | 경고 | `modern/api/src/main/java/com/example/item/LegacyQueryParams.java:281` | PHP `$_GET` 파싱을 옮긴 200줄짜리 클래스(`parse`, `register`, `urlDecode`)에 전용 단위 테스트가 없다. `ItemControllerTest` 는 서비스를 `@MockBean` 으로 대체하고, `ItemSearchConditionTest` 는 `param()` 만 직접 호출한다(`grep LegacyQueryParams modern/api/src/test` 결과 없음). 이중 인코딩 · 중첩 `[]` · `max_input_vars` 경계 · 잘못된 `%` 같은 분기는 characterization 케이스에 의존한다 | `LegacyQueryParamsTest` 추가: 같은 이름 반복 · `a[]=` 배열 · 중첩 배열(`"Array"`) · `%` 잘못된 인코딩 · 1000개 초과 변수 |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchSpecifications.java:97` | 키워드 `LIKE` 에서 `%` · `_` 를 이스케이프하지 않는 것은 BR-07 대로(레거시 동작 보존)다. 이관 중 고치지 않는 것이 맞고, 아래 "의심 동작" 목록으로만 남긴다 | 변경 없음. 의심 동작으로 보고 |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:140-151` | `param()` 은 `LegacyQueryParams` 를 거치지 않는 경로(쿼리 문자열 없이 파라미터만 들어오는 요청)의 근사다. 서블릿 컨테이너 밖에서만 쓰이는 분기라 실사용 가능성은 낮지만, 두 경로의 규칙이 이중으로 유지된다 | 쿼리 문자열이 없으면 파라미터도 없다는 가정이 맞는지 확인 후, 맞다면 분기 단순화 검토 |

## 컨벤션 점검 요약 (CLAUDE.md §2 · §3 기준, 변경된 `modern/api` 대상)

- 컨트롤러는 서비스만 호출(`ItemController` → `ItemSearchService`), Repository · SQL 직접 호출 없음. 반환 타입은 record DTO(`ItemSearchResponse`), 엔티티 노출 없음.
- `catch` 블록 · try-catch · `System.out`/`printStackTrace` 없음. 로그는 SLF4J `LoggerFactory.getLogger(ItemSearchService.class)`, 로그 인자에 학생 식별자 · 이메일 · 토큰 없음.
- 서비스에서 `now()` 호출 없음. 경계값은 `static final` 상수(`LEVEL_MIN/MAX`, `PAGE_SIZE`, `PAGE_MAX` 등).
- 추가된 `src` 줄 중 120자 초과 없음, 와일드카드 import 없음.
- 테스트: 새 서비스 메서드(`search`) · 엔드포인트(`/api/items/search`)마다 테스트 있음. `@DataJpaTest` · Mockito · `@WebMvcTest` 구분을 지키고 Spring 테스트에 `@ActiveProfiles("test")` 있음, `@DisplayName` 한국어, `@Disabled` 없음.
- `characterization/` 의 테스트 · 스냅샷은 이번 브랜치에서 새로 추가된 것이고, 기존 파일을 고쳐 통과시킨 흔적은 없음(`src` 의 `ItemController` · `ItemRepository` 외 기존 파일 수정 없음).
- 의존성 · DB(`db/`) 변경 없음(`build.gradle` 은 diff 에 없음).

## 의심 동작 (레거시와 같게 옮긴 것, 고치지 않음)

- 키워드의 `%` · `_` 를 와일드카드로 그대로 쓴다(BR-07) — `ItemSearchSpecifications.java:95-99`.
- 잘못된 난이도(`6`, `abc`)도 오류가 아니라 PHP `(int)` 값으로 조회하고 0건을 돌려준다(BR-04 · BR-12) — `ItemSearchCondition.java:157-166`.

## 확인 필요

- 요청 범위를 "이관 1건"으로만 볼지, 선행 커밋(문서 · Skill · `CLAUDE.md`)까지 포함할지 사람이 정해 주세요. 이슈 3 의 판정이 달라집니다.
- `application.yml` 의 `relaxed-query-chars` 가 전 엔드포인트에 적용되는 것을 허용할지 사람 확인이 필요합니다(이슈 2).
- 새 API 대상 characterization 은 이미 떠 있던 `localhost:8080` 서버로 돌렸습니다. 그 서버가 `HEAD` 코드로 빌드된 것인지는 확인하지 못했습니다(`/api/items/search` 가 응답하는 것까지만 확인). 머지 전에 `HEAD` 로 재기동해 한 번 더 실행해 주세요.
- 레거시 서버(`localhost:8081`)가 시드 변경 없는 DB 를 쓰는지(`item-bank.test.js` 상단 전제)는 확인하지 않았습니다. 스냅샷 비교가 통과했으므로 문제는 없어 보입니다.
