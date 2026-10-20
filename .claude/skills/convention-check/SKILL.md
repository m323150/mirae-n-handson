---
name: convention-check
description: 변경된 코드(또는 지정한 경로)가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지키는지 예/아니오로 점검하고 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 있나 봐 줘" 같은 요청에 쓴다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

## 반드시 지킬 것
- 코드를 직접 고치지 않는다. 보고만 한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기
- `$ARGUMENTS` 가 있으면 그 경로(파일 · 디렉터리)만 본다. 디렉터리는 Glob 으로 펼친다.
- 없으면 `git status --porcelain` 과 `git diff --name-only`, `git diff --cached --name-only` 를 합친다.
  `??`(아직 add 하지 않은 새 파일)도 포함하고, 삭제된 파일(`D`)은 뺀다.
- `legacy/`, `characterization/`, `bin/`, `node_modules/`, `build/` 아래는 대상에서 뺀다.
- `.java` 는 항목 1~6, `modern/web` 의 `.ts` · `.tsx` 는 항목 7~8 만 적용한다.
- 변경 파일은 `git diff` 로 바뀐 줄을 먼저 보고, 판정에 필요하면 Read 로 파일 전체를 읽는다.

## 2. 점검 항목 (예 = 통과)
1. [api 계층] `*Controller` 에 Repository 주입이 없고, Repository 메서드 · SQL 을 직접 실행하지 않으며,
   컨트롤러 메서드 안에 `try`-`catch` 가 없는가?
2. [api 계층] 컨트롤러 메서드의 반환 타입과 그 필드에 `@Entity` 클래스가 없는가? (record `*Response` 여야 한다)
3. [api 예외] 모든 `catch` 블록에 SLF4J 로그 호출과 `throw` 가 둘 다 있는가?
   `System.out.println` · `System.err.println` · `printStackTrace()` 가 없는가?
4. [api 시각] 서비스 클래스에서 인자 없는 `now()` 를 호출하지 않는가? (`LocalDateTime.now(clock)` 만 허용)
5. [api 형식] 와일드카드 import(`import x.*;`)가 없고, 모든 줄이 120자 이내인가?
6. [api 트랜잭션] `@Transactional` 메서드 안에서 `RestTemplate` · `RestClient` · `WebClient` ·
   `java.net.http.HttpClient` 를 호출하지 않는가?
7. [web 데이터] `src/api/client.ts` 밖에서 `fetch` 를 호출하지 않는가?
8. [web 금지 구문] `any` 타입 · `as unknown as` · `@ts-ignore` · `console.log` · `dangerouslySetInnerHTML` 이 없고,
   `eslint-disable` 주석마다 같은 줄에 사유가 있는가?

Grep 은 후보를 찾는 데만 쓰고, 판정은 해당 줄을 Read 로 확인한 뒤 한다(주석 · 문자열 속 일치는 위반이 아니다).

## 3. 출력 형식 (이 순서로 고정)

### 판정 요약
| # | 항목 | 판정 |
|---|---|---|
| 1 | 컨트롤러 → Repository 직접 접근 · try-catch | 통과 / 위반 N건 / 확인 필요 / 해당 없음 |
(1~8 모두 한 줄씩)

점검 대상 파일: N개 (목록)

### 위반 목록
| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/.../ItemController.java:42` | #1 컨트롤러가 `ItemRepository` 를 직접 호출 | `ItemService` 에 메서드를 두고 그것을 호출 |

- 위반이 없으면 표 대신 "위반 없음" 한 줄을 쓴다.
- "확인 필요" 항목은 표 아래에 무엇을 더 봐야 판정할 수 있는지 한 줄씩 적는다.
