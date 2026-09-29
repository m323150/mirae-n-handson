# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

답변과 문서는 한국어로 쓴다. 코드 식별자 · 명령 · 파일 경로는 원문 그대로 둔다.

대상 코드: `modern/api`(Spring Boot 3.3 · Java 21 · Gradle wrapper · MariaDB 10.11)와 `modern/web`(React 18 · TypeScript · Vite · Vitest). 레거시 모듈(`legacy/`)을 이 스택으로 이관하는 실습 저장소다.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — 테스트 프로필은 H2 라서 DB 컨테이너 없이 통과한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemControllerTest'                      # 클래스 하나
cd modern/api && ./gradlew test --tests 'com.example.item.ItemControllerTest.getItemReturnsJson'   # 메서드 하나
cd modern/api && ./gradlew build

# modern/web
cd modern/web && npm ci                                          # 설치는 npm ci 만 쓴다
cd modern/web && npm run dev                                     # http://localhost:5173
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx   # 파일 하나
cd modern/web && npm run build
```

- `modern/api` 를 고쳤으면 `./gradlew test` 를, `modern/web` 을 고쳤으면 `npm run lint && npm run typecheck && npm test` 를 실행하고 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 적는다.
- 위 명령 중 하나라도 실패한 상태에서는 작업을 "완료"라고 보고하지 않는다.

## 2. 코딩 컨벤션

### modern/api — 계층
- **컨트롤러는 서비스를 통해 데이터에 접근한다.** 컨트롤러에 Repository를 주입하거나, Repository 메서드 또는 SQL을 직접 실행하지 않는다.
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`)이고, 한 도메인 안에서 Controller → Service → Repository → 엔티티 순으로만 호출한다.
- 서비스가 다른 도메인의 데이터를 쓸 때는 그 도메인의 Service 를 주입받는다. 다른 도메인의 Repository 를 주입하지 않는다.
- 컨트롤러 메서드의 반환 타입은 record DTO(`*Response`)다. `@Entity` 클래스를 반환 타입이나 그 필드로 쓰지 않는다.

### modern/api — 예외 · 로깅
- **예외를 빈 `catch` 블록으로 삼키지 않는다.** 잡은 예외는 SLF4J로 기록하고 다시 던지거나 도메인 예외로 변환해 던진다. Java 코드에서 `System.out.println`, `System.err.println`, `printStackTrace()`를 사용하지 않는다.
  - 판정 기준: 모든 `catch` 블록에 SLF4J 로그 호출과 `throw` 가 둘 다 있어야 한다.
- 예외 → HTTP 응답 변환은 `common/GlobalExceptionHandler` 에서만 한다. 컨트롤러 메서드 안에 try-catch 를 두지 않는다.
- 상태 코드 매핑: `NotFoundException` · 없는 경로 404, 검증 · 타입 변환 실패 400, `IllegalStateException` 409, 그 밖의 예외 500.
- 로거는 `org.slf4j.LoggerFactory.getLogger(<클래스>.class)` 로 만든다.
- 로그 메시지와 인자에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다.
- 로그 레벨은 `GlobalExceptionHandler` 와 같게 쓴다: 404 · 400 은 `INFO`, 409 는 `WARN`, 500 은 예외 객체를 인자로 넘긴 `ERROR`.

### modern/api — 시각 · 상수 · 형식
- 서비스 클래스는 현재 시각을 생성자로 주입받은 `java.time.Clock` 으로 얻는다(`LocalDateTime.now(clock)`). 인자 없는 `now()` 를 서비스에서 호출하지 않는다.
- 난이도 상 · 하한(1, 5) 같은 도메인 경계값은 `static final` 상수로 선언해 쓴다.
- 들여쓰기 4칸, 한 줄 120자 이내, 와일드카드 import(`import x.*;`) 금지.

### modern/api — 테스트
- 새 public 서비스 메서드와 새 엔드포인트마다 테스트 메서드를 1개 이상 추가한다. 기존 동작을 바꾸면 그 동작을 검증하는 테스트를 추가하거나 고친다.
- 컨트롤러는 `@WebMvcTest` + `@MockBean`, 서비스는 Mockito 단위 테스트, 리포지토리 쿼리는 `@DataJpaTest`(H2)로 검증한다.
- Spring 컨텍스트를 쓰는 테스트 클래스에는 `@ActiveProfiles("test")` 를 붙인다. 테스트가 `localhost:3306` MariaDB 에 접속하지 않는다.
- 테스트 메서드 이름은 확인하는 동작을 camelCase 로 쓰고(예: `getItemMissingReturns404`), `@DisplayName` 에 한국어 설명을 붙인다.

### modern/web
- HTTP 요청은 `src/api/client.ts` 의 `getJson` 을 거친다. `client.ts` 밖에서 `fetch` 를 호출하지 않는다.
- 엔드포인트별 호출 함수는 `src/api/items.ts`, 응답 타입은 `src/api/types.ts` 에 둔다. 응답 타입의 필드명은 백엔드 JSON 필드명과 같게 쓴다.
- 조회 훅은 `src/hooks/useApiQuery.ts` 의 `useApiQuery` 를 감싸서 만들고, 컴포넌트는 이 훅으로만 서버 데이터를 읽는다.
- 컴포넌트는 함수 컴포넌트로 쓴다. 클래스 컴포넌트 · `React.FC` · `defaultProps` 를 쓰지 않는다.
- 새 컴포넌트를 추가하면 같은 폴더에 `<이름>.test.tsx` 를 만든다. 테스트는 `src/test/mockFetch.ts` · `fixtures.ts` 로 fetch 를 가로채고 실제 `localhost:8080` 을 호출하지 않는다.
- Testing Library 조회는 `getByRole` · `getByLabelText` 를 먼저 쓴다. `getByTestId` 는 두 방법으로 찾을 수 없을 때만 쓴다.

### 이관
- 레거시 규칙을 `modern/` 으로 옮길 때는 근거를 `파일:줄번호` 로 답변에 적는다(예: `legacy/item-bank-php/search.php:214`).

## 3. 금지 사항

- `legacy/` 는 분석 · 이관 대상이며 허락 없이 수정하지 않는다.
- **의존성 추가와 DB 스키마·시드 변경은 변경 이유와 대안을 제시하고 승인받은 뒤 수행한다.** DB 변경에는 테이블·컬럼·인덱스 변경과 마이그레이션 파일 추가를 포함한다.
  - 대상 파일: `modern/api/build.gradle` 의 `dependencies`, `modern/web/package.json` 의 `dependencies` · `devDependencies`, `db/` 아래 전부.
- `modern/api/src/main/resources/application.yml` 의 `datasource` 접속 정보와 `hikari` 설정을 바꾸지 않는다.
- 운영 계정 · 토큰 · 키를 코드나 설정 파일에 리터럴로 넣지 않는다. 실습용 더미 값(`app-pass`)은 예외다.
- 호스트 이름이 `prod-db` 이거나 `localhost` 가 아닌 DB 에 접속하는 설정 · 명령을 만들지 않는다.
- `@Transactional` 메서드 안에서 `RestTemplate` · `RestClient` · `WebClient` · `java.net.http.HttpClient` 를 호출하지 않는다.
- `package-lock.json` 을 손으로 고치거나 지우지 않는다. `npm install` 대신 `npm ci` 를 쓴다.
- `.env` 로 시작하는 파일을 만들거나 읽지 않는다.
- `modern/web` 코드에 `any` 타입 · `as unknown as` · `@ts-ignore` · `console.log` · `dangerouslySetInnerHTML` 을 쓰지 않는다. `eslint-disable` 주석을 쓰면 같은 줄에 사유를 적는다.
- 요청에 없는 파일은 포맷팅 · import 정리를 포함해 고치지 않는다. 고쳤다면 파일마다 이유를 답변에 적는다.
- `characterization/` 의 테스트 파일과 `__snapshots__/` 을 고쳐서 이관 결과를 통과시키지 않는다. 이관 코드가 기존 스냅샷을 깨면 먼저 사람에게 알린다.

## 4. 아키텍처 안내

### modern/api
```
src/main/java/com/example/
├── item/         문항 · 단원 · 태그 (ItemController, UnitController, *Service, *Repository, 엔티티)
├── assignment/   과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*)
├── common/       GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig, RootController
└── config/       WebConfig — /api/** 에 대해 http://localhost:5173 출처의 GET 만 허용(CORS)
src/main/resources/application.yml       기본 프로필 = 로컬 MariaDB(itembank), 포트 8080
src/test/resources/application-test.yml  테스트 프로필 = H2 (MariaDB 모드, ddl-auto create-drop)
```
- 엔드포인트: `GET /`, `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`. 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.
- 오류 응답은 `ErrorResponse(status, error, message, path, timestamp)` 한 모양이다.
- 공개 문항은 `status = 'A'` 로 판단한다(삭제 플래그가 아님).
- Hikari 풀은 운영 값과 같게 작다(`maximum-pool-size: 5`, `connection-timeout: 3000`). 트랜잭션을 오래 쥐는 코드는 풀 고갈로 이어진다.

### modern/web
- 데이터 흐름: 컴포넌트 → `src/hooks/use*.ts` → `useApiQuery` → `src/api/items.ts` → `getJson` → `modern/api`.
- `useApiQuery(key, load)` 는 `idle | loading | success | error` 판별 유니언을 돌려주고, key 변경 · 언마운트 때 `AbortSignal` 로 이전 요청을 취소한다. 비-2xx 응답은 `ApiError` 로 던져져 한국어 오류 문구로 바뀐다.
- API 기본 주소는 `VITE_API_BASE`(기본 `http://localhost:8080`). 빈 문자열로 두면 상대 경로를 쓰고 `vite.config.ts` 의 `/api` 프록시가 8080 으로 넘긴다.

### 데이터 · 이관
- MariaDB `itembank` 스키마 · 시드는 `db/mariadb/init/`. 레거시 PHP · Thymeleaf 모듈과 `modern/api` 가 같은 DB 를 쓴다.
- 도메인 용어: 문항 `item` · 단원 `unit`(예: `M5-1`) · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 `STU-<숫자>`.
- 이관 결과는 `characterization/` 의 스냅샷 테스트로 레거시와 비교한다. 사용법은 `characterization/README.md`.

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization/` 에서 `npm test` 가 전부 통과하기 전에는 "완료"라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스 이름과 스냅샷 차이를 그대로 보고한다. "거의 됐다"처럼 요약해서 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일(`__snapshots__/`)을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 작업을 멈추고 사람에게 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않고 레거시와 같게 옮긴다. 그런 동작은 "의심 동작" 목록으로 따로 보고한다.
