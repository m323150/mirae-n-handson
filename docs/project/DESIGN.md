# 실전 프로젝트 설계 — 백엔드 / 하원석

## 1. 목표
레거시 문항 검색 `legacy/item-bank-php/search.php` 를 `GET /api/items/search` 로 옮긴 이관 코드가 Day 1-3 에서 만든 동작 보존 테스트 18케이스(`characterization/tests/item-bank.test.js`)를 레거시 · 새 API 양쪽에서 실패 0으로 통과하게 하고, 그 변경을 PR 로 올려 자동 리뷰 코멘트를 1회 받는다.

## 2. 재료
2026-10-20 에 `day3` 브랜치(HEAD `eaf8a81`)를 읽고 판정했다. "있음"은 파일이 있다는 뜻이고, 동작하는지는 실행해 보지 않았다(4절 참고).

| 재료 | 있음/없음 | 경로 |
|---|---|---|
| 이관 대상 레거시 엔드포인트 | 있음 | `legacy/item-bank-php/search.php` |
| 비즈니스 규칙 문서(케이스 근거 `BR-xx`) | 있음 | `docs/item-bank/BUSINESS-RULES.md`, `docs/item-bank/CROSS-CHECK.md` |
| 동작 보존 테스트 틀 | 있음 | `characterization/lib/target.mjs`, `characterization/lib/normalize.mjs`, `characterization/README.md` |
| 입력 케이스(Day 1-3, 18개) | 있음 | `characterization/tests/item-bank.test.js` |
| 기준 스냅샷 | 있음 | `characterization/__snapshots__/item-bank.test.js.snap` |
| 경로 대응표 `/search.php` → `/api/items/search` | 있음 | `characterization/lib/target.mjs` 의 `PATH_ALIASES` |
| 이관 코드(엔드포인트) | 있음 | `modern/api/src/main/java/com/example/item/ItemController.java:33` |
| 이관 코드(서비스 · 조건 · 파라미터 해석) | 있음 | `modern/api/src/main/java/com/example/item/` 의 `ItemSearchService.java`, `ItemSearchSpecifications.java`, `ItemSearchCondition.java`, `LegacyQueryParams.java`, `ItemSearchResponse.java`, `ItemSearchRow.java` |
| 이관 코드 단위 테스트 | 있음 | `modern/api/src/test/java/com/example/item/` 의 `ItemSearchServiceTest.java`, `ItemSearchConditionTest.java`, `ItemSearchRepositoryTest.java`, `LegacyQueryParamsTest.java`, `ItemControllerTest.java` |
| Skill — 머지 전 판정 | 있음 | `.claude/skills/verify/SKILL.md` |
| Skill — 컨벤션 점검 | 있음 | `.claude/skills/convention-check/SKILL.md` |
| Skill — 영향도 분석 | 있음 | `.claude/skills/impact/SKILL.md` |
| Sub-agent — 리뷰 · 테스트 | 있음 | `.claude/agents/reviewer.md`, `.claude/agents/tester.md` |
| Hook — 위험 명령 차단 · 시크릿 탐지(등록됨) | 있음 | `hooks/block-dangerous.mjs`, `hooks/secret-scan.mjs`, 등록: `.claude/settings.json` 의 `hooks.PreToolUse` |
| Hook — `git clean` 확인 | 있음(미등록) | `hooks/ask-risky.mjs` — `.claude/settings.json` 에 등록되어 있지 않다 |
| PR 자동 리뷰 워크플로 | 있음 | `.github/workflows/pr-review.yml` (`origin/main` 에도 있음) |
| 리뷰 기준 체크리스트 | 있음 | `docs/approval-checklist.md`(워크플로가 읽는 파일), `templates/approval-checklist.md`(verify Skill 이 읽는 파일) |
| 이전 판정 기록(반려) | 있음 | `docs/verify/migration-review-3.md` |
| DB · 레거시 실행 환경 | 있음 | `docker-compose.yml` — `mariadb`(프로필 `php` 포함), `item-bank`(프로필 `php`, 8081) |
| pipeline-samples | 있음(이 트랙에서 쓰지 않음) | `pipeline-samples/` — 배치 로그 · Terraform, 데이터 파이프라인 · IaC 트랙 재료 |
| 3회차에 받은 PR 주소 | 없음 | 저장소 문서에서 찾지 못했다. 4절 질문 1 |
| 이번 프로젝트용 브랜치 · PR | 없음 → 새로 만들 것 | 5절 3단계 |
| 이 설계 문서 | 없음 → 새로 만들 것 | `docs/project/DESIGN.md` (이 파일) |

## 3. 범위
### 한다
1. `search.php` → `GET /api/items/search` 하나만 대상으로, 기존 18케이스를 레거시(8081) · 새 API(8080)에 돌려 실패 0을 확인한다. 실패가 있으면 이관 코드(`modern/api/.../item/ItemSearch*`, `LegacyQueryParams`, `ItemController`)만 고친다.
2. 1의 결과를 브랜치 하나에 커밋하고 `origin` 에 PR 을 올린다.
3. PR 에서 `Claude Auto Review` 워크플로의 코멘트를 1회 받고 그 주소를 기록한다.

### 하지 않는다
- **다른 엔드포인트 이관(`units.php`, `register.php`, assignment · grade 모듈)** — 범위가 엔드포인트 1개로 정해져 있다.
- **케이스 추가 · 수정, 스냅샷 재생성** — 입력 케이스는 Day 1-3 것을 그대로 쓰기로 했고, CLAUDE.md §5 가 테스트 · 스냅샷 수정을 금지한다.
- **레거시 의심 동작 고치기(예: 난이도 6 을 경고 후 그대로 조회)** — CLAUDE.md §5 에 따라 레거시와 같게 옮기고 "의심 동작"으로만 보고한다.
- **`modern/web` 변경** — 백엔드 트랙이다.
- **의존성 · DB 스키마 · 시드 · `application.yml` 의 `datasource`·`hikari` 변경** — CLAUDE.md §3 에 따라 승인이 필요하고, 40분 안에 승인 절차까지 넣을 수 없다.
- **`legacy/` 수정** — CLAUDE.md §3 금지 사항이다.

## 4. 미해결 질문
1. **자동 리뷰를 받을 PR 이 어느 것인가?** "3회차에 받은 PR 주소"가 저장소 문서에 없다. `gh pr list --repo m323150/mirae-n-handson --state all` 에는 `#1 Practice/bad pr`(`practice/bad-pr`, OPEN) 하나만 나온다. 이 PR 에 push 해서 리뷰를 다시 받을지, 새 PR 을 열지 정해야 한다.
2. **`origin` 저장소에 `CLAUDE_CODE_OAUTH_TOKEN` 시크릿이 등록되어 있는가?** 워크플로 파일은 있지만 시크릿 등록 여부와 이전 실행 성공 여부는 확인하지 못했다.
3. **지금 HEAD 기준으로 characterization 이 통과하는가?** 이번에는 실행하지 않았다. `docs/verify/migration-review-3.md` 에 레거시 32 통과 · 새 API 31 통과 · 1 건너뜀 기록이 있으나, 그 뒤 커밋(`eaf8a81` upstream 병합 포함)이 들어온 상태는 확인하지 않았다.
4. **이전 반려 사유를 이번 PR 에서 어떻게 처리하나?** `docs/verify/migration-review-3.md` 이슈 #1 `GET /api/units/item-counts`(search 이관과 무관)와 #4 `application.yml` 의 `server.tomcat.relaxed-query-chars` 가 아직 코드에 있다. PR base 를 `main` 으로 두면 이 변경들이 diff 에 들어가 리뷰에서 범위 이탈로 지적될 수 있다. 빼기 · 유지 중 무엇을 할지 사람이 정해야 한다.
5. **PR base 브랜치는 `main` 인가?** `main..HEAD` 에 커밋 18개가 있어 Day 1-3 변경 전체가 diff 에 들어간다.
6. **로컬에서 `docker compose --profile php up -d` 로 8081 레거시와 MariaDB 를 띄울 수 있는가?** 실행 환경은 확인하지 않았다.

## 5. 단계

### 1단계 — 기준선 확인 (코드 수정 없음, 약 10분)
- **할 일**: DB · 레거시를 띄워 레거시 대상 테스트를 돌리고, 새 API 를 띄워 같은 테스트를 대상만 바꿔 돌린다. `./gradlew test` 도 돌린다. 실패 케이스 이름과 스냅샷 차이를 그대로 적는다.
- **만들거나 고칠 파일**: 없음. 결과는 이 문서 4절 질문 3 의 답으로 적는다.
- **확인 방법**:
  ```bash
  docker compose --profile php up -d
  cd characterization && npm ci && npm test                                   # 레거시 8081
  cd modern/api && ./gradlew bootRun                                          # 다른 터미널
  cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test       # 새 API 8080
  cd modern/api && ./gradlew test
  ```
  결과 줄의 `passed` · `failed` · `skipped` 숫자를 기록한다. `git status --short characterization` 출력이 비어 있어야 한다.

### 2단계 — 차이 해소 (1단계에서 8080 실패가 0이면 건너뜀, 약 15분)
- **할 일**: 실패한 케이스마다 (a) 규칙 차이 (b) 형식 차이 (c) 데이터 · 환경 차이로 나누고, (a) · (b) 는 이관 코드를 고친다. 고친 동작마다 단위 테스트를 추가하거나 고친다. 근거는 `legacy/item-bank-php/search.php:<줄>` 로 적는다.
- **만들거나 고칠 파일**: 1단계 결과에 따라 아래 중에서만 고른다. `modern/api/src/main/java/com/example/item/` 의 `ItemSearchService.java` · `ItemSearchSpecifications.java` · `ItemSearchCondition.java` · `LegacyQueryParams.java` · `ItemController.java`, 그리고 같은 이름의 `*Test.java`.
- **확인 방법**:
  ```bash
  cd modern/api && ./gradlew test                                             # BUILD SUCCESSFUL, 실패 0
  cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test       # failed 0
  git diff --stat -- characterization                                         # 출력 없음
  ```

### 3단계 — PR 과 자동 리뷰 (약 15분)
- **할 일**: 4절 질문 1 · 4 · 5 의 답에 따라 브랜치를 정해 커밋 · push 하고 PR 을 연다. 워크플로가 끝나면 코멘트를 확인하고 주소를 이 문서 6절에 적는다.
- **만들거나 고칠 파일**: 새 브랜치(이름은 질문 1 의 답에 따름), PR 본문.
- **확인 방법**:
  ```bash
  gh pr checks <PR번호> --repo m323150/mirae-n-handson            # review 작업이 pass
  gh pr view <PR번호> --repo m323150/mirae-n-handson --comments   # Claude 리뷰 코멘트 1개 이상
  gh pr diff <PR번호> --repo m323150/mirae-n-handson --name-only  # 3절 "한다" 범위 밖 파일이 없는지 눈으로 확인
  ```

## 6. 완성 기준 (Day 4-1에서 점검)
- [ ] `cd characterization && npm test`(레거시 8081)의 결과가 `failed 0` 이고, `item-bank.test.js` 의 18케이스가 모두 `passed` 에 들어 있다.
- [ ] `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` 의 결과가 `failed 0` 이고, `skipped` 는 `example-units.test.js` 의 1건뿐이다.
- [ ] `git diff --stat main...HEAD -- characterization` 의 출력이 비어 있다(테스트 · 스냅샷을 고치지 않았다).
- [ ] `cd modern/api && ./gradlew test` 가 `BUILD SUCCESSFUL` 이고 실패가 0건이다.
- [ ] 대상 PR 에 `Claude Auto Review` 워크플로가 남긴 코멘트가 1개 이상 있고, 그 코멘트 주소가 이 줄 끝에 적혀 있다: `<코멘트 주소>`
