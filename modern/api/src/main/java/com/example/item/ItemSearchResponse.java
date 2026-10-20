package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답. 동작 보존 테스트의 정규화 모양({@code status, rows, count, message})과 같다.
 *
 * @param status  HTTP 상태 코드(항상 200 — 잘못된 입력도 0건으로 응답한다)
 * @param rows    현재 페이지의 행(최대 20건)
 * @param count   조건에 맞는 전체 건수(페이지와 무관)
 * @param message 0건이면 "검색 결과가 없습니다", 아니면 {@code null}
 */
public record ItemSearchResponse(int status, List<ItemSearchRow> rows, long count, String message) {
}
