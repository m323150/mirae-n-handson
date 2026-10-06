package com.example.item;

/** {@code GET /api/units/item-counts} 응답 항목 — 단원별 공개 문항 수. */
public record UnitItemCountResponse(String code, String name, Integer grade, long activeItemCount) {

    static UnitItemCountResponse of(Unit unit, long activeItemCount) {
        return new UnitItemCountResponse(unit.getCode(), unit.getName(), unit.getGrade(), activeItemCount);
    }
}
