package com.example.item;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 서비스 — 레거시 {@code search.php} 의 이관. 잘못된 입력은 거부하지 않고 레거시와 같게 해석해
 * 검색한다(BR-04). 없는 단원 · 범위 밖 난이도도 오류가 아니라 0건이다.
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    /** 결과가 0건일 때 안내 문구(BR-25). */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemRepository itemRepository;

    public ItemSearchService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    /**
     * 검색. 목록은 한 페이지(20건), 건수는 같은 조건의 전체 건수다(BR-02 · BR-23).
     * 페이지 조회 뒤 그 id 들로 단원 · 태그를 한 번에 가져와 페이지 순서대로 행을 만든다.
     */
    public ItemSearchResponse search(MultiValueMap<String, String> params) {
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        PageRequest pageRequest = PageRequest.of(
            condition.page() - 1, ItemSearchCondition.PAGE_SIZE, condition.toSort());

        Page<Item> page = itemRepository.findAll(ItemSearchSpecifications.of(condition), pageRequest);
        List<Integer> ids = page.getContent().stream().map(Item::getId).toList();
        Map<Integer, Item> details = ids.isEmpty() ? Map.of()
            : itemRepository.findWithDetailsByIdIn(ids).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<ItemSearchRow> rows = ids.stream().map(details::get).map(ItemSearchRow::from).toList();

        long count = page.getTotalElements();
        log.debug("item search page {} returned {} of {} rows", condition.page(), rows.size(), count);
        return new ItemSearchResponse(HttpStatus.OK.value(), rows, count, count == 0 ? NO_RESULT_MESSAGE : null);
    }
}
