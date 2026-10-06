package com.example.item;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 단원 컨트롤러 슬라이스 — 서비스는 목, 예외 → HTTP 변환은 GlobalExceptionHandler 가 담당. */
@WebMvcTest(UnitController.class)
@ActiveProfiles("test")
class UnitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UnitService unitService;

    @Test
    @DisplayName("GET /api/units → 200, 단원 배열")
    void listUnitsReturnsArray() throws Exception {
        when(unitService.listUnits()).thenReturn(List.of(
            new UnitResponse(1, "M5-1", "분수의 덧셈과 뺄셈", 5),
            new UnitResponse(4, "M6-1", "분수의 나눗셈", 6)));

        mockMvc.perform(get("/api/units").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].code").value("M5-1"))
            .andExpect(jsonPath("$[1].grade").value(6));
    }

    @Test
    @DisplayName("GET /api/units/item-counts → 200, 서비스 순서대로 {code, name, grade, activeItemCount} 배열")
    void getItemCountsReturnsArray() throws Exception {
        when(unitService.countActiveItemsByUnit()).thenReturn(List.of(
            new UnitItemCountResponse("M5-1", "분수의 덧셈과 뺄셈", 5, 3L),
            new UnitItemCountResponse("M6-1", "분수의 나눗셈", 6, 0L)));

        mockMvc.perform(get("/api/units/item-counts").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].code").value("M5-1"))
            .andExpect(jsonPath("$[0].name").value("분수의 덧셈과 뺄셈"))
            .andExpect(jsonPath("$[0].grade").value(5))
            .andExpect(jsonPath("$[0].activeItemCount").value(3))
            .andExpect(jsonPath("$[0].length()").value(4))
            .andExpect(jsonPath("$[1].code").value("M6-1"))
            .andExpect(jsonPath("$[1].activeItemCount").value(0));
    }

    @Test
    @DisplayName("GET /api/units/item-counts 단원이 없으면 → 200, 빈 배열")
    void getItemCountsEmptyReturnsEmptyArray() throws Exception {
        when(unitService.countActiveItemsByUnit()).thenReturn(List.of());

        mockMvc.perform(get("/api/units/item-counts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/units/item-counts 서비스 예외 → 500, 공통 오류 응답")
    void getItemCountsServiceFailureReturns500() throws Exception {
        when(unitService.countActiveItemsByUnit()).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/api/units/item-counts"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.path").value("/api/units/item-counts"));
    }
}
