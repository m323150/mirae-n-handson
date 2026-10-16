# 배치 · 데이터마트 점검 리포트

**요약: 점검 기간 2026-09-08 ~ 2026-09-17(10일), 이상 7건(4개 날짜: 09-09 · 09-12 · 09-15 · 09-17)**

- 대상: `pipeline-samples/batch-logs/*.log`, `pipeline-samples/datamart-counts.csv`
- 대상 job(day_open planned 기준): `build_item_stats`, `export_grades`, `extract_submissions`, `load_datamart`, `build_report_cache`
- 학생 식별자 · 이메일 · 토큰 값은 리포트에 옮기지 않았다.

## 이상 목록

> "영향 추정" 열은 로그 · CSV 근거에서 추론한 것이며 확인된 사실이 아니다. 원인은 로그에 찍혀 있지 않으면 "확인 필요"로 둔다.

| # | 날짜 | job | 점검 항목 | 근거 | 영향 추정 | 다음 확인 사항 |
|---|---|---|---|---|---|---|
| 1 | 2026-09-09 | build_item_stats | 실행 여부(종료 기록 없음) | `batch-logs/2026-09-09.log:1` day_open planned 에 포함 · `batch-logs/2026-09-09.log:2` run_id=bis-20260910-0030-5192 status=START, `:4` progress=25% rows=3109 가 마지막 기록. SUCCESS 등 종료 줄 없음. CSV 에 `2026-09-09,build_item_stats` 행 없음(같은 날 다른 job 4건은 `datamart-counts.csv:7`~`:10` 에 있음) | `dm.item_stats_daily` 의 2026-09-09 분이 적재되지 않았을 가능성 | 원인 확인 필요: 해당 시각 batch-runner 프로세스(pid 40655) 종료 여부 · 시스템 로그, `dm.item_stats_daily` 의 2026-09-09 행 존재 여부, 재실행 이력 |
| 2 | 2026-09-12 | export_grades | 지연 | `batch-logs/2026-09-12.log:16` run_id=exg-20260913-0100-4cdb elapsed=963s — 평소 319s(중앙값)의 3.02배 | 건수는 정상(`datamart-counts.csv:22` `2026-09-12,export_grades,8257,2026-09-13T01:16:04+09:00`). 후속 job 시작(01:30) 전에 끝나 연쇄 지연은 로그상 보이지 않음 | 원인 확인 필요: 로그에 원인 기록 없음. 같은 시간대 원천 DB · 출력 대상 부하 지표 |
| 3 | 2026-09-15 | extract_submissions | 지연 | `batch-logs/2026-09-15.log:20` run_id=ext-20260916-0130-526f elapsed=1668s — 평소 680.5s의 2.45배. `:19` WARN note="source read slow: grade-db … replica lag 412s"(원인 후보로 로그에 기록됨, 확정 아님) | 종료(01:57:50)가 load_datamart 시작(02:00:03) 직전이라 후속 job 여유가 2분 남짓으로 줄었음 | grade-db 복제 지연 지표로 로그 note 가 실제 원인인지 확인 |
| 4 | 2026-09-15 | load_datamart | 중복 실행 | SUCCESS 2회: `batch-logs/2026-09-15.log:40` run_id=ldm-20260916-0200-edf5 trigger=cron(START `:21`), `:42` run_id=ldm-20260916-0258-8963 trigger=manual(START `:32`). `:30` rerun requested force=true reason="SLA 알림 — 멈춘 것으로 보고 재실행", `:31` 기존 run 이 lock 보유 중인데 action=ignored reason="--force", `:33` mode=append, `:43` lock=release_skipped | 두 run 이 같은 날짜를 `mode=append` 로 `dm.daily_submission_fact` 에 각각 적재 → 중복 행 적재 추정(#5 건수와 일치) | 2026-09-15 분 중복 행 정리 여부, 재실행 절차(실행 중 job 확인 · --force 사용 기준), 잠금 상태 정리 여부 |
| 5 | 2026-09-15 | load_datamart | 적재 건수 | `datamart-counts.csv:39` `2026-09-15,load_datamart,369064,2026-09-16T03:15:31+09:00` — 직전 7일 평균 184617.4 대비 +99.91%. 두 run 의 rows=184532(`batch-logs/2026-09-15.log:40` · `:42`) 합 369064 와 같음. `:47` dq-check 도 rows=369064 avg_7d=184617 deviation=+99% 경고 | 데이터마트에 2026-09-15 제출 데이터가 2배로 잡혀 있을 가능성. `:45` build_report_cache 가 input_rows=369064 로 불일치 경고를 남겨 리포트 캐시(`datamart-counts.csv:40`)도 영향 범위일 수 있음 | 중복 제거 후 건수 재확인, 2026-09-15 기반 리포트 캐시 재생성 필요 여부 |
| 6 | 2026-09-15 | load_datamart | 지연 | `batch-logs/2026-09-15.log:40` run_id=ldm-20260916-0200-edf5(cron) elapsed=4357s — 평소 923s의 4.72배. `:27` note="elapsed 40m exceeds p95 17m", `:28` sla-monitor status=NOT_FINISHED sla=02:45. 수동 run(`:42`, 1008s, 1.09배)은 기준 이내 | SLA(02:45) 초과 → 재실행 판단(#4)의 계기가 된 것으로 보임 | 원인 확인 필요: 로그에 지연 원인 기록 없음. 앞단 extract_submissions 지연(#3)과의 관련성, 02:00~03:12 적재 대상 DB 부하 |
| 7 | 2026-09-17 | build_item_stats | 적재 건수 | `datamart-counts.csv:46` `2026-09-17,build_item_stats,7284,2026-09-18T00:33:24+09:00` — 직전 7일 평균 12453.1 대비 −41.51%. 로그 `batch-logs/2026-09-17.log:7`~`:8` rows=7284 status=SUCCESS elapsed=202s(정상 범위) | job 은 SUCCESS 라 실패 알림 없이 문항 통계가 평소보다 적게 적재됐을 가능성 | 원인 확인 필요: 원천 `itembank.item_response` 의 2026-09-17 응답 건수(원천 감소인지 누락인지) |

## 계산 방식

- **적재 건수**: `datamart-counts.csv` 에서 job 별로 날짜순 정렬해, 각 날짜 직전의 최대 7개 행 평균과 비교했다. 편차 = (당일 − 평균) / 평균 × 100, ±30% 초과 또는 0건이면 이상.
  - 2026-09-08 은 직전 데이터가 없어 다섯 job 모두 건수 판정에서 뺐다.
  - 직전 데이터가 7일보다 적어 있는 만큼으로 평균을 낸 경우: 09-09~09-14(job 별 1~6일). `build_item_stats` 는 09-09 행이 없어 09-10 평균이 1일(09-08), 09-15 평균이 6일로 계산됐다.
  - 09-16 · 09-17 `load_datamart` 평균(211253.0 · 211493.4)에는 중복 적재된 09-15 값 369064 가 포함돼 있다. 그대로 계산해도 편차가 −12.48% · −12.74% 로 기준 이내다.
- **소요 시간**: job 별 전체 실행(점검 기간의 모든 SUCCESS run)의 중앙값을 평소 소요 시간으로 쓰고, 2배 초과면 이상.
  - 중앙값: build_item_stats 213s(9회) · export_grades 319s(10회) · extract_submissions 680.5s(10회) · load_datamart 923s(11회, 09-15 수동 run 포함) · build_report_cache 195.5s(10회).
  - `build_report_cache` 는 로그에 `elapsed` 필드가 없어 같은 run_id 의 START ~ SUCCESS 시각 차로 계산했다(178~247s, 최대 1.26배).
- **실행 여부 · 중복**: 매일 day_open planned 목록(10일 모두 같은 5개 job)과 각 job 의 START · SUCCESS 줄을 run_id 기준으로 대조했다.
- 모든 계산은 awk 로 했고 중간 결과는 세션 스크래치패드에 두었다.

## 이상 없음으로 판정한 날짜

- 2026-09-08 — 실행 여부 · 중복 · 지연은 이상 없음. **적재 건수는 직전 데이터가 없어 판정하지 못함.**
- 2026-09-10 — 이상 없음(건수 평균은 직전 2일 기준, `build_item_stats` 는 1일 기준).
- 2026-09-11 — 이상 없음(건수 평균은 직전 3일 기준, `build_item_stats` 는 2일 기준).
- 2026-09-13 — 이상 없음(건수 평균은 직전 5일 기준, `build_item_stats` 는 4일 기준).
- 2026-09-14 — 이상 없음(건수 평균은 직전 6일 기준, `build_item_stats` 는 5일 기준).
- 2026-09-16 — 이상 없음(`load_datamart` 평균에 09-15 중복 값 포함, 위 계산 방식 참고).

참고: 이상 날짜 중 2026-09-09 의 `build_item_stats` 는 종료 기록과 CSV 행이 없어 건수 · 지연을 판정하지 못했다. 같은 날 나머지 4개 job 은 이상 없음.
