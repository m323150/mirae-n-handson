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
}
