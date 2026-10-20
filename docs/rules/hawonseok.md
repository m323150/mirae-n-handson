# 팀 공통 규칙 후보 — hawonseok

- 주 스택: spring / react / node.js / mariaDB / mysql
- 출처: `.claude/settings.json`(permissions · hooks), `hooks/block-dangerous.mjs`, `hooks/secret-scan.mjs`, `hooks/ask-risky.mjs`, `scripts/hook-node.sh` (2026-10-13 에 읽은 내용 기준)
- 적용 범위: 어느 스택에서든 뜻이 같은 규칙은 `공통`, 특정 빌드 · 패키지 도구 규칙은 `spring` · `react` · `node.js` · `mariaDB` · `mysql` 중에서 골랐다. npm 은 react 와 node.js 가 함께 쓰므로 둘 다 적었다. 내 스택에 해당하지 않는 규칙은 `해당 없음` 으로 두고 이유를 적었다.
- 외주 차이: 표의 규칙은 내부 · 외주 개발자에게 똑같이 건다. 외주에게 더 엄격하게 거는 것만 "외주 차이" 열에 적었고, 비어 있으면 내부와 같다. 기준은 아래 "외주 차이 기준" 절에 있다.
- 개인 설정인 `.claude/settings.local.json` 은 팀 규칙 후보가 아니라서 넣지 않았다.

| 규칙 | 종류(deny / ask / allow / Hook) | 적용 범위(spring / react / node.js / mariaDB / mysql) | 막는 사고 또는 허용 이유 | 외주 차이 |
|---|---|---|---|---|
| `Bash(rm -rf *)` | deny | 공통 | 경로를 잘못 지정한 재귀 삭제로 작업 폴더 전체가 사라지는 사고 | |
| `Bash(sudo *)` | deny | 공통 | AI 가 관리자 권한으로 시스템 파일 · 패키지를 바꾸는 사고 | |
| `Bash(git push --force *)` · `Bash(git push -f *)` | deny | 공통 | 원격 브랜치를 덮어써 다른 사람 커밋이 사라지는 사고 | |
| `Bash(git reset --hard *)` | deny | 공통 | 커밋하지 않은 작업과 로컬 이력이 되돌릴 수 없게 사라지는 사고 | |
| `Bash(*prod-db*)` · `Bash(*prod.db*)` | deny | 공통 | 운영 DB 호스트에 직접 접속해 조회 · 변경하는 사고 | |
| `Bash(curl *)` · `Bash(wget *)` | deny | 공통 | 출처를 모르는 스크립트를 받아 실행하거나 내부 데이터를 외부로 보내는 사고 | |
| `Read(./.env)` · `Read(./.env.*)` · `Read(./secrets/**)` · `Read(**/*.pem)` · `Read(~/.ssh/**)` · `Read(~/.aws/**)` | deny | 공통 | 비밀 파일 내용이 대화 컨텍스트 · 로그에 남아 유출되는 사고 | 규칙은 같다. 추가로 실계정 비밀값을 외주 PC 에 배포하지 않고, 외주 전용 개발 계정만 준다 |
| `Edit(./.env)` · `Edit(./.env.*)` · `Edit(./secrets/**)` | deny | 공통 | 접속 정보 · 키를 AI 가 바꾸거나 덮어써 환경이 깨지는 사고 | |
| `mcp__github__delete_file` · `mcp__github__merge_pull_request` | deny | 공통 | 원격 저장소 파일 삭제, 리뷰를 거치지 않은 머지 | |
| `Bash(git push *)` | ask | 공통 | 원격 저장소로 나가는 변경을 사람이 한 번 확인 | 본인 작업 브랜치에만 push 한다. main · 보호 브랜치 push 는 GitHub 브랜치 보호(서버)로 막는다 |
| `mcp__github__create_pull_request` · `mcp__github__push_files` | ask | 공통 | 원격 저장소에 PR · 파일을 올리기 전에 사람이 확인 | `mcp__github__push_files` 는 deny. PR 을 거치지 않고 원격에 반영하는 경로라서 막는다 |
| `Bash(docker compose down *)` · `Bash(docker compose --profile *)` | ask | 공통 | 로컬 DB 컨테이너를 내리거나 다른 프로필을 띄워 개발 환경이 바뀌는 사고 | |
| `mcp__dbhub` | ask | 공통 | MCP 로 DB 에 쿼리를 보내기 전에 대상과 쿼리를 사람이 확인 | deny. DB 직접 조회는 개인정보 접근이고, ask 는 외주 본인이 승인하게 되므로 통제가 되지 않는다 |
| `mcp__dbhub-mssql` | ask | 해당 없음(MSSQL 전용) | MSSQL 에 쿼리를 보내기 전에 사람이 확인 | deny. `mcp__dbhub` 와 같은 이유 |
| `Bash(npm install *)` · `Bash(npm i *)` | ask | react · node.js | 이름이 비슷한 악성 패키지 · 취약 버전 설치, `package-lock.json` 변경 | deny. 의존성 추가 승인은 내부 담당자가 해야 하므로, 외주는 PR 에 추가 사유를 적고 내부가 반영한다 |
| `Bash(npm ci *)` | ask | react · node.js | lockfile 기준 설치라도 설치 스크립트가 실행되므로 사람이 확인 | |
| `Bash(pip install *)` | ask | 해당 없음(Python) | 검토하지 않은 Python 패키지 설치 | deny. `npm install` 과 같은 이유 |
| `Bash(./gradlew bootRun *)` | ask | spring | 앱이 기동되며 DB 접속 · 포트 점유 같은 부수 효과가 생기는 것을 사람이 확인 | |
| `Bash(npm test *)` · `Bash(npm run test *)` · `Bash(npm run lint *)` · `Bash(npm run typecheck *)` · `Bash(npm run build *)` | allow | react · node.js | 로컬에서만 도는 검증 · 빌드라 매번 확인할 필요가 없음 | |
| `Bash(./gradlew test *)` · `Bash(./gradlew check *)` · `Bash(./gradlew build *)` | allow | spring | 로컬 검증 · 빌드. 테스트 프로필이 H2 라서 외부 DB 에 접속하지 않음 | |
| `Bash(git status *)` · `Bash(git diff *)` · `Bash(git log *)` | allow | 공통 | 읽기 전용 조회 | |
| `Bash(git add *)` · `Bash(git commit *)` | allow | 공통 | 로컬 이력에만 남아 되돌릴 수 있음. 커밋 직전에 시크릿 탐지 Hook 이 검사함 | |
| `WebFetch(domain:docs.spring.io)` · `WebFetch(domain:docs.gradle.org)` | allow | spring | 공식 문서 조회 | |
| `WebFetch(domain:developer.mozilla.org)` | allow | react · node.js | 공식 문서 조회 | |
| `mcp__github__get_file_contents` · `mcp__github__list_pull_requests` · `mcp__github__pull_request_read` · `mcp__github__list_issues` | allow | 공통 | 원격 저장소 읽기 전용 조회 | |
| `mcp__item-bank` · `mcp__item-bank__search_items` | allow | 해당 없음(이 저장소 전용 MCP) | 문항 검색 MCP 의 읽기 전용 조회 | deny. 또는 마스킹 데이터만 가진 외주 전용 서버로 연결한다. 실데이터를 조회하면 외부 반출이 된다 |
| `hooks/block-dangerous.mjs` `RULES[0]` — rm -rf · -fr · -r -f · -Rf · --recursive --force | Hook | 공통 | 옵션 순서나 표기를 바꿔 `Bash(rm -rf *)` deny 를 피하는 재귀 강제 삭제 | |
| `hooks/block-dangerous.mjs` `RULES[1]` — DROP TABLE · DROP DATABASE(대소문자 무시) | Hook | 공통 | 테이블 · DB 삭제로 데이터를 복구할 수 없게 되는 사고 | |
| `hooks/block-dangerous.mjs` `RULES[2]` — prod-db · prod.db | Hook | 공통 | 운영 DB 호스트 접근(deny 의 패턴 매칭을 피하는 표기까지 정규식으로 차단) | |
| `hooks/secret-scan.mjs` — `git commit` 직전 스테이징 diff 의 AWS Access Key ID · 하드코딩된 password · PEM 개인키 | Hook | 공통 | 비밀값이 커밋 이력에 남아 지워도 남는 사고 | 로컬 Hook 에 더해 CI(서버) 시크릿 스캔 통과를 머지 조건으로 둔다. 로컬 Hook 은 끌 수 있다 |
| `hooks/ask-risky.mjs` — `git clean` 에 -f · -d 가 함께 있으면 ask | Hook | 공통 | 추적하지 않는 파일 · 디렉터리 일괄 삭제. **`settings.json` 에 등록돼 있지 않아 지금은 동작하지 않는다.** | |
| `scripts/hook-node.sh` — node 를 못 찾으면 명령 차단(fail-closed) | Hook | 공통 | node 가 PATH 에 없는 셸에서 Hook 이 조용히 꺼지는 사고 | |

## 외주 차이 기준

- **나누지 않는 것**: 파괴 명령, 강제 push, 운영 DB, 외부 전송, 시크릿 탐지처럼 AI 사고를 막는 규칙은 누가 쓰든 위험이 같으므로 한 벌로 둔다. 두 벌로 나누면 공통 규칙이 바뀔 때 두 곳을 고쳐야 하고 결국 서로 어긋난다.
- **ask 를 deny 로 바꾸는 것**: ask 는 키보드 앞에 있는 사람이 승인한다. 내부 승인이 필요한 행위(의존성 추가, DB 직접 조회, PR 없는 원격 반영)는 외주에게 ask 로 걸면 통제가 되지 않으므로 deny 로 바꾸고 PR 리뷰로 보낸다.
- **데이터 범위**: 개인정보(학생 식별자 · 이메일)와 실데이터 접근은 계약 · 보안 서약 범위를 따른다. 기본은 차단, 필요하면 마스킹 데이터를 쓴다.
- **강제 위치**
  - 공통 규칙: 저장소의 `.claude/settings.json`.
  - 외주 차이분: 외주 PC 의 managed settings(사용자가 바꿀 수 없는 관리형 설정). 저장소 설정은 각자 PC 에서 고치거나 Hook 을 끌 수 있어서 외주에게는 강제력이 없다. 배포 방식과 키 이름(`allowManagedHooksOnly` · `disableBypassPermissionsMode` 등)은 적용 전에 Claude Code 문서로 확인한다.
  - 우회되면 안 되는 것: GitHub 브랜치 보호, CI 시크릿 스캔, 머지 승인 같은 서버 쪽 장치.
- **머지 관문**: 외주 통제의 중심은 클라이언트 규칙보다 머지 전 판정이다. `templates/approval-checklist.md` 와 reviewer Sub-agent · `verify` Skill 로 외주 PR 을 판정한다.

## 추가 제안

내 스택(spring / react / node.js / mariaDB / mysql)에 필요한데 지금 없는 규칙이다.

| 규칙 | 종류 | 적용 범위 | 막는 사고 |
|---|---|---|---|
| `hooks/secret-scan.mjs` 에 YAML · properties 형태 패턴 추가(`password:` · `spring.datasource.password=` · JDBC URL 안의 `password=`) | Hook | spring | 지금의 `password\s*=\s*['"]` 패턴은 `application.yml` 의 `password: xxx` 나 따옴표 없는 properties 값을 잡지 못한다 |
| `hooks/secret-scan.mjs` 에 `.npmrc` 의 `_authToken` 패턴 추가 | Hook | react · node.js | npm 레지스트리 토큰이 커밋되는 사고 |
| `hooks/secret-scan.mjs` 가 `cd … && git commit` · `git -C <경로> commit` 도 검사 | Hook | 공통 | 지금은 명령이 `git commit` 으로 **시작할 때만** 검사해서, 앞에 다른 명령이 붙으면 시크릿 검사 없이 커밋된다(`git commit *` 은 allow) |
| `hooks/block-dangerous.mjs` 에 `TRUNCATE` · WHERE 없는 `DELETE FROM` · `ALTER TABLE … DROP` 규칙 추가 | Hook | 공통 | 데이터를 지우는 SQL 중 지금 막는 것은 DROP 뿐이다 |
| `mysql` · `mariadb` 클라이언트의 `-h` 값이 `localhost` · `127.0.0.1` 이 아니면 차단 | Hook | mariaDB · mysql | 지금은 호스트 이름에 `prod-db` 가 들어 있을 때만 막아서, 다른 이름의 운영 · 스테이징 DB 는 통과한다 |
| `hooks/ask-risky.mjs` 를 `settings.json` 의 PreToolUse(Bash) 에 등록 | Hook | 공통 | 파일은 있지만 등록하지 않아 `git clean -fd` 가 확인 없이 실행된다 |
| `Bash(npx *)` · `Bash(npm exec *)` | ask | react · node.js | 레지스트리에서 패키지를 받아 바로 실행한다(`curl` 후 실행과 같은 위험). npm install ask 를 우회한다 |
| `Bash(npm publish *)` | deny | react · node.js | 사내 패키지 · 코드를 공개 레지스트리에 올리는 사고 |
| `Read(~/.npmrc)` | deny | react · node.js | npm 토큰이 대화 컨텍스트에 남는 사고 |
| `Read(**/application-prod*.yml)` | deny | spring | 운영 프로필 접속 정보가 대화 컨텍스트에 남는 사고 |

## 주의

다른 스택에 그대로 걸면 일을 막을 수 있는 규칙이다.

- **`scripts/hook-node.sh` fail-closed (모든 스택)**: node 가 없는 개발 환경(php · Python 전용 데이터 환경 등)에서는 **모든 Bash 명령이 막힌다.** 팀 전체에 걸려면 node 설치를 전제 조건으로 공지하거나, 언어에 의존하지 않는 Hook 실행 방식이 필요하다. 외주 PC 도 마찬가지다.
- **`Bash(curl *)` · `Bash(wget *)` deny**: 로컬 API 확인(`curl localhost:8080/...`)까지 막는다. 엔드포인트를 확인하는 백엔드 개발자, 헬스체크 · API 수집 스크립트를 돌리는 데이터 · 인프라 쪽에 지장이 있다. localhost 만 허용하는 Hook 으로 바꾸는 것을 검토한다.
- **`hooks/block-dangerous.mjs` 의 문자열 매칭**: 명령 문자열 전체에서 패턴을 찾기 때문에, `rm -rf` · `DROP TABLE` · `prod-db` 가 **텍스트로만** 들어 있어도 막힌다(heredoc 으로 문서 작성, `grep 'DROP TABLE'`, 마이그레이션 SQL 파일 생성). DDL 과 운영 호스트 이름을 다루는 데이터 · 인프라 작업과 php 레거시 분석에서 오탐이 많을 것이다.
- **`Bash(*prod-db*)` · `Bash(*prod.db*)` deny**: 위와 같은 이유로, `prod-db` 라는 이름을 쓰는 terraform 모듈 · 문서를 Bash 로 검색 · 조회하는 작업까지 막는다.
- **`Bash(pip install *)` ask · `Bash(sudo *)` deny**: Python 데이터 작업은 가상환경 구성 · 시스템 패키지 설치가 잦아서 확인 요청과 차단이 자주 생긴다.
- **`mcp__item-bank` · `mcp__item-bank__search_items` allow, `mcp__dbhub-mssql` ask**: 이 저장소 전용 MCP 서버다. 다른 저장소에는 해당 서버가 없으므로 팀 공통 규칙에서는 빼고 프로젝트 설정에 둔다.
- **allow 의 빌드 도구 규칙(`gradlew` · `npm`)**: 해당 스택에만 배포한다. composer · terraform 에는 대응하는 allow 가 없어서 검증 명령마다 확인 요청이 뜬다. 반대로 `terraform apply` · `composer` 를 막는 규칙도 지금은 없다.
- **외주 `npm install` deny**: 외주가 의존성 추가를 검토하려고 로컬에서 시험 설치하는 것도 막힌다. 시험이 필요하면 내부 담당자가 대신 설치하거나, 승인된 lockfile 로 `npm ci` 만 하게 한다.
