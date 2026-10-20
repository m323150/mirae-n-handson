package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    @Test
    @DisplayName("search: 페이지 순서대로 행을 만들고, 전체 건수와 message=null 을 돌려준다")
    @SuppressWarnings("unchecked")
    void searchKeepsPageOrderAndTotalCount() {
        Item first = ItemFixtures.item(18, unit, "분수의 뺄셈 문장제", 4, ItemStatus.ACTIVE,
            ItemFixtures.tag(2, "문장제"), ItemFixtures.tag(4, "오답률높음"));
        Item second = ItemFixtures.item(12, unit, "대분수의 덧셈", 3, ItemStatus.ACTIVE, ItemFixtures.tag(1, "계산"));
        Page<Item> page = new PageImpl<>(List.of(first, second), Pageable.ofSize(20), 25);
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(itemRepository.findWithDetailsByIdIn(List.of(18, 12))).thenReturn(List.of(second, first));

        ItemSearchResponse response = itemSearchService.search(new LinkedMultiValueMap<>());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.count()).isEqualTo(25);
        assertThat(response.message()).isNull();
        assertThat(response.rows()).extracting(ItemSearchRow::id).containsExactly(18, 12);
        assertThat(response.rows().get(0).unit()).isEqualTo("M5-1");
        assertThat(response.rows().get(0).tags()).containsExactly("문장제", "오답률높음");
    }

    @Test
    @DisplayName("search: 0건이면 스냅샷과 같은 안내 문구, 상세 조회는 하지 않는다")
    @SuppressWarnings("unchecked")
    void searchWithNoResultReturnsMessage() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("unit", "Z99-99");
        ItemSearchResponse response = itemSearchService.search(params);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemRepository, never()).findWithDetailsByIdIn(anyList());
    }

    @Test
    @DisplayName("search: 페이지 보정값 · 크기 20 · 정렬이 저장소에 그대로 전달된다")
    @SuppressWarnings("unchecked")
    void searchPassesClampedPageAndSort() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("page", "1000");
        params.add("sort", "title");
        params.add("dir", "desc");

        itemSearchService.search(params);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(998);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort())
            .isEqualTo(ItemSearchCondition.from(params).toSort());
    }
}
