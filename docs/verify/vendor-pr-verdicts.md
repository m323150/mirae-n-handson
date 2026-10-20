# 외주사 PR 판정 기록

판정 기준은 `templates/approval-checklist.md` 의 네 기준(테스트 통과 / 치명 이슈 0건 / 요청 범위 이탈 없음 / 컨벤션 준수)이다. 하나라도 미충족이면 반려.

| PR | 판정 | 미충족 기준 | 근거(파일:줄번호) | 외주사에 돌려보낼 수정 요청 |
|---|---|---|---|---|
| vendor pr-1 (`623aef0`, `HEAD~1..HEAD`) — 요청: `UnitService.countActiveItemsByUnit()` 추가(단원 목록과 같은 순서, 문항 0개 단원 포함) | 반려 | ① 테스트 통과(테스트 충분성)<br>② 요청 범위 이탈 없음<br>③ 컨벤션 준수 | **①③ 테스트 없음:** 새 public 서비스 메서드 `modern/api/src/main/java/com/example/item/UnitService.java:36` 과 새 엔드포인트 `UnitController.java:26-28` 에 대한 테스트가 diff 에 없다. 변경 3개 파일이 모두 `src/main` 이고 `UnitServiceTest` 의 테스트는 `listUnits*` 2개뿐이다(CLAUDE.md §2 "테스트": 새 public 서비스 메서드 · 새 엔드포인트마다 테스트 1개 이상). 기존 `./gradlew test` 는 47개 실행 · 실패 0 이지만 새 코드는 검증되지 않는다.<br>**② 범위 초과:** 요청은 서비스 메서드 1개인데 새 엔드포인트 `GET /api/units/item-counts`(`UnitController.java:25-29`)와 새 응답 DTO(`UnitItemCountResponse.java:4`)가 함께 들어왔다. | 1. `UnitServiceTest` 에 `countActiveItemsByUnit` 테스트 추가: 단원 순서(학년 → 코드)가 `listUnits` 와 같음, 문항 0개 단원이 0 으로 포함됨, `ItemStatus.ACTIVE` 로만 집계됨(`@DisplayName` 한국어, 메서드명 camelCase).<br>2. 엔드포인트와 DTO 를 유지하려면 요청 범위에 포함한다는 사람의 확인을 받고 `UnitControllerTest`(`@WebMvcTest` + `@MockBean`, `@ActiveProfiles("test")`)를 추가한다. 아니면 `UnitController.java:25-29`, `UnitItemCountResponse.java` 를 제거한다.<br>3. (제안) 단원마다 `countByUnitIdAndStatus` 를 호출하는 N+1(`UnitService.java:38-39`)을 `group by` 한 번으로 줄이는 방안 검토. 이 경우 단원 0건 처리를 유지해야 한다. |
| vendor PR (`77660cd`, `HEAD~1..HEAD`) — 요청: 외부 채점 서버 연동 클라이언트 추가 | 반려 | ① 테스트 통과(테스트 충분성)<br>② 요청 범위 이탈 없음<br>③ 컨벤션 준수 | **② 요청과 diff 불일치:** 변경 3개 파일은 `UnitController.java:25-29`(`GET /api/units/item-counts`), `UnitItemCountResponse.java:4`, `UnitService.java:36`(`countActiveItemsByUnit`) 뿐이며 단원별 공개 문항 수 통계다. 채점 서버를 호출하는 클라이언트(`RestClient` · `HttpClient` 등)는 diff 에 없다. 요청한 기능은 구현되지 않았고 요청하지 않은 기능이 들어왔다. 위 pr-1 행과 같은 diff 이므로 PR 설명이 잘못 붙었거나 다른 PR 이 전달된 것으로 보인다.<br>**①③ 테스트 없음:** 새 public 서비스 메서드 `UnitService.java:36` 과 새 엔드포인트 `UnitController.java:26-28` 에 대한 테스트가 없다(`src/test` 에 `countActiveItemsByUnit` · `item-counts` 참조 0건; CLAUDE.md §2 "테스트"). `./gradlew test` 는 47개 실행 · 실패 0 · 오류 0 · 건너뜀 0 이지만 새 코드는 검증되지 않는다. | 1. 이 PR 이 맞는지 외주사에 확인한다. 채점 서버 연동 클라이언트 PR 이 따로 있으면 그것을 다시 보내게 한다.<br>2. 이 diff 를 유지하려면 요청 범위에 포함한다는 사람의 확인을 받고, `UnitServiceTest` · `UnitControllerTest` 를 추가한다(pr-1 행의 수정 요청 1·2 와 같다).<br>3. 채점 클라이언트를 새로 만든다면 의존성 추가·`@Transactional` 안 HTTP 호출 금지·`localhost` 외 접속 금지 규칙(CLAUDE.md §3)을 지키고, 의존성 추가는 사전 승인을 받는다. |

## 판정 메모 — vendor pr-1

- 테스트: `cd modern/api && ./gradlew test` → BUILD SUCCESSFUL, 47개 실행 · 실패 0 · 오류 0 · 건너뜀 0. `modern/web` 변경 없음 → 해당 없음. 이관 코드가 아니므로 `characterization` 은 실행하지 않음.
- 치명 이슈 0건: 충족(리터럴 비밀값 · SQL 문자열 결합 · 데이터 삭제 없음).
- 컨벤션: 계층(컨트롤러 → 서비스), record DTO 반환, 로깅, 120자, import 는 이상 없음. 같은 `item` 도메인이라 `UnitService` 가 `ItemRepository` 를 주입한 것도 규칙 위반이 아니다. 미충족은 테스트 누락뿐이다.
- 대상 밖: 커밋되지 않은 `.gitignore` 변경과 `.claude/skills/verify/` 는 검증 대상에 포함하지 않았다.
