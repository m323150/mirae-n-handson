// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 케이스 설계 근거: docs/item-bank/BUSINESS-RULES.md (케이스 이름 앞의 [BR-xx] 가 겨냥한 규칙 ID)
// 시드 전제: db/mariadb/init/02-seed.sql 을 바꾸지 않은 DB. 공개(A) 문항 중 난이도 1~4 가 정확히 20건이다.
//
// 규칙
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 모듈의 레거시 기본 주소).
//   테스트 코드에 주소를 직접 적지 않는다.
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

function search(params) {
  return fetchNormalized(MODULE, PATH, params);
}

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('[BR-02·08·10·12] 단원 M5-1 + 난이도 3 — 두 조건의 AND 결합', async () => {
      expect(await search({ unit: 'M5-1', level: '3' })).toMatchSnapshot();
    });

    it('[BR-05·13·20·21·26] 키워드 분수 + 태그 계산 + 제목 내림차순 — 동순위 id 오름차순, 태그 쉼표 연결', async () => {
      expect(await search({ q: '분수', tag: '계산', sort: 'title', dir: 'desc' })).toMatchSnapshot();
    });

    it('[BR-01·05] 검수중(R) 문항 제목 키워드 + 난이도 3 — 비공개 문항은 제목이 맞아도 빠짐', async () => {
      expect(await search({ q: '검수중', level: '3' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('[BR-11·12·17] 난이도 상한 5 명시 — 비운 경우와 달리 난이도 5 포함', async () => {
      expect(await search({ level: '5' })).toMatchSnapshot();
    });

    it('[BR-04·12] 난이도 상한 바로 바깥 6 — 경고 후 level = 6 으로 조회', async () => {
      expect(await search({ level: '6' })).toMatchSnapshot();
    });

    it('[BR-12] 난이도 05 — 정규식 불일치지만 정수 변환으로 5', async () => {
      expect(await search({ level: '05' })).toMatchSnapshot();
    });

    it('[BR-05·06] 키워드 101자(한글) — 100자로 자름', async () => {
      expect(await search({ q: '가'.repeat(101) })).toMatchSnapshot();
    });

    it('[BR-15·16] 태그 51자 — 50자로 자르고 미등록 태그로 조회', async () => {
      expect(await search({ tag: 'a'.repeat(51) })).toMatchSnapshot();
    });

    it('[BR-24] 페이지 하한 바로 바깥 0 — 1페이지로 보정', async () => {
      expect(await search({ page: '0' })).toMatchSnapshot();
    });

    it('[BR-24] 페이지 상한 바로 바깥 1000 — 999페이지로 보정', async () => {
      expect(await search({ page: '1000' })).toMatchSnapshot();
    });

    it('[BR-11·23·24·25] 조건 없음 2페이지 — 결과가 딱 20건(한 페이지)일 때 다음 페이지', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('[BR-11·17·23·25] 파라미터 전체 누락 — 기본 검색', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('[BR-05·08·11·13·19·24] 모든 파라미터 빈 문자열', async () => {
      expect(
        await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' }),
      ).toMatchSnapshot();
    });

    it('[BR-05] 공백만 있는 키워드 + 난이도 1 — 앞뒤 공백 제거 후 빈 값', async () => {
      expect(await search({ q: '   ', level: '1' })).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('[BR-24] 음수 페이지 -1 — 숫자가 아닌 값으로 보고 1페이지', async () => {
      expect(await search({ page: '-1' })).toMatchSnapshot();
    });

    it('[BR-04·10] 없는 단원 코드 Z99-99 — 형식은 맞지만 미등록', async () => {
      expect(await search({ unit: 'Z99-99' })).toMatchSnapshot();
    });

    it('[BR-09] 소문자 단원 코드 m5-1 — DB 비교는 대소문자 무시', async () => {
      expect(await search({ unit: 'm5-1' })).toMatchSnapshot();
    });

    it('[BR-07·14·18·19] 키워드 · 태그에 % + 알 수 없는 정렬 price · 방향 up', async () => {
      expect(await search({ q: '%', tag: '%', sort: 'price', dir: 'up' })).toMatchSnapshot();
    });
  });
});
