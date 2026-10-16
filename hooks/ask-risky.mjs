// hooks/ask-risky.mjs

let input = "";

process.stdin.setEncoding("utf8");

process.stdin.on("data", (chunk) => {
  input += chunk;
});

process.stdin.on("end", () => {
  try {
    const event = JSON.parse(input);

    // Bash 도구가 실행하려는 명령어
    const command = event?.tool_input?.command ?? "";

    // git clean 계열 중 -f(force) + -d(directory 삭제)가 함께 있는 경우
    const isGitClean = /\bgit\s+clean\b/.test(command);
    const hasForce = /(?:^|\s)-[a-zA-Z]*f[a-zA-Z]*(?:\s|$)/.test(command);
    const hasDirectory = /(?:^|\s)-[a-zA-Z]*d[a-zA-Z]*(?:\s|$)/.test(command);

    if (isGitClean && hasForce && hasDirectory) {
      const response = {
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "ask",
          permissionDecisionReason:
            "git clean -fd 계열 명령은 Git이 추적하지 않는 파일과 디렉터리를 삭제할 수 있습니다. 정말 실행할까요?"
        }
      };

      // stdout에는 JSON만 출력
      process.stdout.write(JSON.stringify(response));
      process.exit(0);
    }

    // 위험 명령이 아니면 아무 출력 없이 정상 종료
    process.exit(0);

  } catch {
    // stdout을 오염시키지 않도록 아무것도 출력하지 않음
    process.exit(0);
  }
});