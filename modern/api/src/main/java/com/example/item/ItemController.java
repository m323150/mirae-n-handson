package com.example.item;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 API. 컨트롤러는 서비스만 호출하고, 예외는 {@code GlobalExceptionHandler} 가 처리한다.
 */
@RestController
@RequestMapping("/api")
public class ItemController {

    private final ItemService itemService;
    private final ItemSearchService itemSearchService;

    public ItemController(ItemService itemService, ItemSearchService itemSearchService) {
        this.itemService = itemService;
        this.itemSearchService = itemSearchService;
    }

    /**
     * {@code GET /api/items/search} — 문항 검색(레거시 {@code search.php}).
     * 파라미터 이름은 레거시와 같다: q, unit, level, tag, sort, dir, page. 값 해석은 서비스가 한다.
     * 같은 이름 · 배열 이름({@code q[]})이 섞였을 때 PHP 와 같은 값을 고르려면 순서가 필요해서, 원 쿼리 문자열이
     * 있으면 그것을 PHP {@code $_GET} 방식으로 읽는다. 없으면(쿼리 문자열 없이 파라미터만 있는 요청) 서블릿 파라미터를 쓴다.
     */
    @GetMapping("/items/search")
    public ItemSearchResponse searchItems(
        @RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
        String rawQuery = request.getQueryString();
        return itemSearchService.search(rawQuery == null ? params : LegacyQueryParams.parse(rawQuery));
    }

    /** {@code GET /api/items/{id}} — 문항 단건. */
    @GetMapping("/items/{id}")
    public ItemResponse getItem(@PathVariable Integer id) {
        return itemService.getItem(id);
    }

    /** {@code GET /api/units/{code}/items} — 단원의 공개 문항 목록. */
    @GetMapping("/units/{code}/items")
    public List<ItemResponse> listItemsByUnit(@PathVariable String code) {
        return itemService.listActiveItemsByUnit(code);
    }
}
