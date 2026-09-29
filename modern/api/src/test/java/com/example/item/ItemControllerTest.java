package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.common.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/** 컨트롤러 슬라이스 — 서비스는 목, 예외 → HTTP 변환은 GlobalExceptionHandler 가 담당. */
@WebMvcTest(ItemController.class)
@ActiveProfiles("test")
class ItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemService itemService;

    @MockBean
    private ItemSearchService itemSearchService;

    @Test
    @DisplayName("GET /api/items/search → 200, {status, rows, count, message} 모양이고 행에는 5개 필드만")
    void searchItemsReturnsNormalizedShape() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(
            200, List.of(new ItemSearchRow(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음"))), 1, null));

        mockMvc.perform(get("/api/items/search").param("unit", "M5-1").param("level", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.message").value(nullValue()))
            .andExpect(jsonPath("$.rows[0].id").value(12))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.rows[0].length()").value(5))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/items/search 숫자가 아닌 page · 없는 단원도 400/404 가 아니라 200, 파라미터는 그대로 전달")
    void searchItemsWithOddParamsReturns200() throws Exception {
        when(itemSearchService.search(any())).thenReturn(
            new ItemSearchResponse(200, List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("unit", "Z99-99").param("page", "abc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));

        ArgumentCaptor<MultiValueMap<String, String>> params = ArgumentCaptor.captor();
        verify(itemSearchService).search(params.capture());
        assertThat(params.getValue().getFirst("unit")).isEqualTo("Z99-99");
        assertThat(params.getValue().getFirst("page")).isEqualTo("abc");
    }

    @Test
    @DisplayName("GET /api/items/{id} → 200, 문항 JSON")
    void getItemReturnsJson() throws Exception {
        when(itemService.getItem(3)).thenReturn(
            new ItemResponse(3, "분모가 다른 분수의 덧셈", "1/2 + 1/3 = ?", "M5-1", 4, "A", List.of("계산", "오답률높음")));

        mockMvc.perform(get("/api/items/3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(3))
            .andExpect(jsonPath("$.unit").value("M5-1"))
            .andExpect(jsonPath("$.level").value(4))
            .andExpect(jsonPath("$.tags[0]").value("계산"))
            .andExpect(jsonPath("$.tags[1]").value("오답률높음"));
    }

    @Test
    @DisplayName("GET /api/items/{id} 없는 문항 → 404, 공통 오류 응답")
    void getItemMissingReturns404() throws Exception {
        when(itemService.getItem(404)).thenThrow(new NotFoundException("문항이 없습니다: id=404"));

        mockMvc.perform(get("/api/items/404"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("문항이 없습니다: id=404"))
            .andExpect(jsonPath("$.path").value("/api/items/404"));
    }

    @Test
    @DisplayName("GET /api/items/{id} 숫자가 아닌 id → 400")
    void getItemWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/api/items/abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /api/units/{code}/items → 200, 배열")
    void listItemsByUnitReturnsArray() throws Exception {
        when(itemService.listActiveItemsByUnit("M5-1")).thenReturn(List.of(
            new ItemResponse(2, "난이도 5 문항", "본문", "M5-1", 5, "A", List.of()),
            new ItemResponse(1, "난이도 2 문항", "본문", "M5-1", 2, "A", List.of("계산"))));

        mockMvc.perform(get("/api/units/M5-1/items").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(2))
            .andExpect(jsonPath("$[1].tags[0]").value("계산"));
    }
}
