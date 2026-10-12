package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 검색 Specification · 상세 조회 — H2(MariaDB 모드)에 실제 SQL 을 날린다.
 * 대소문자 무시(BR-09) · 한글 정렬 순서는 MariaDB 콜레이션에 달려 있어 여기서 확인하지 않는다(동작 보존 테스트 몫).
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, word, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 검수 중 문항", 3, ItemStatus.REVIEWING, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 분수 문항", 4, ItemStatus.DELETED, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 1, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    private ItemSearchResponse search(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        return new ItemSearchService(itemRepository).search(params);
    }

    @Test
    @DisplayName("조건 없음: 공개 문항 중 난이도 5 를 뺀 것, level DESC · id ASC")
    void searchWithoutConditionExcludesLevelFiveAndNonPublic() {
        ItemSearchResponse response = search();

        assertThat(response.count()).isEqualTo(3);
        assertThat(response.rows()).extracting(ItemSearchRow::title)
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈", "소수 곱셈");
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("난이도 5 명시: 비운 경우와 달리 난이도 5 포함")
    void searchWithLevelFiveIncludesLevelFive() {
        assertThat(search("level", "5").rows()).extracting(ItemSearchRow::title).containsExactly("난이도 5 분수");
    }

    @Test
    @DisplayName("키워드 · 단원 · 태그는 AND, 태그는 id 순으로 담긴다")
    void searchCombinesConditionsWithAnd() {
        ItemSearchResponse response = search("q", "분수", "unit", "M5-1", "tag", "계산");

        assertThat(response.rows()).extracting(ItemSearchRow::title)
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈");
        assertThat(response.rows().get(0).tags()).containsExactly("계산", "문장제");
        assertThat(response.rows().get(0).unit()).isEqualTo("M5-1");
    }

    @Test
    @DisplayName("키워드는 지문도 찾고, % 는 와일드카드로 쓴다")
    void searchKeywordMatchesStemAndTreatsPercentAsWildcard() {
        assertThat(search("q", "문제 본문").count()).isEqualTo(3);
        assertThat(search("q", "분%덧셈").rows()).extracting(ItemSearchRow::title)
            .containsExactly("분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("키워드의 _ 는 한 글자 와일드카드로 쓴다(BR-07)")
    void searchKeywordTreatsUnderscoreAsSingleCharWildcard() {
        assertThat(search("q", "분_가").rows()).extracting(ItemSearchRow::title)
            .containsExactly("분모가 같은 분수의 덧셈");
    }

    /**
     * 키워드 \ 검증용 문항 3개를 더 넣는다. 모두 공개 · 난이도 1.
     * 아래 [H2 기준] 테스트는 테스트 프로필(H2, MariaDB 모드)에서 \ 가 LIKE 이스케이프 문자로 쓰이지 않고 글자 그대로
     * 비교되는 현재 동작을 고정한다. MariaDB 는 기본 LIKE 이스케이프 문자가 \ 라서 결과가 다를 수 있다 —
     * 운영 DB 기준 동작은 characterization(새 API 대상) 으로 확인해야 한다.
     */
    private void seedBackslashItems() {
        Unit unit = entityManager.persist(ItemFixtures.unit(9, "T-1", "이스케이프 확인", 6));
        entityManager.persist(ItemFixtures.item(null, unit, "할인율 100% 문항", 1, ItemStatus.ACTIVE));
        entityManager.persist(ItemFixtures.item(null, unit, "경로 C:\\temp 문항", 1, ItemStatus.ACTIVE));
        entityManager.persist(ItemFixtures.item(null, unit, "백슬래시 끝\\", 1, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("[H2 기준] 키워드 \\% 는 이스케이프가 아니라 '글자 \\ + 와일드카드 %' — \\ 가 든 문항을 찾고 100% 문항은 못 찾는다")
    void searchKeywordBackslashPercentIsLiteralBackslashOnH2() {
        seedBackslashItems();

        assertThat(search("q", "\\%").rows()).extracting(ItemSearchRow::title)
            .containsExactlyInAnyOrder("경로 C:\\temp 문항", "백슬래시 끝\\");
    }

    @Test
    @DisplayName("[H2 기준] 키워드 \\\\ (백슬래시 두 개)는 글자 그대로 \\\\ 를 찾으므로 \\ 하나만 든 문항은 0건")
    void searchKeywordDoubleBackslashIsLiteralOnH2() {
        seedBackslashItems();

        ItemSearchResponse response = search("q", "\\\\");

        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
    }

    @Test
    @DisplayName("[H2 기준] 키워드 \\ 하나는 글자 \\ 를 찾는다(뒤 % 를 이스케이프하지 않는다)")
    void searchKeywordSingleBackslashIsLiteralOnH2() {
        seedBackslashItems();

        assertThat(search("q", "\\").rows()).extracting(ItemSearchRow::title)
            .containsExactlyInAnyOrder("경로 C:\\temp 문항", "백슬래시 끝\\");
    }

    @Test
    @DisplayName("[H2 기준] 키워드 C:\\temp 처럼 \\ 뒤에 일반 글자가 오는 경우")
    void searchKeywordBackslashBeforeOrdinaryCharOnH2() {
        seedBackslashItems();

        assertThat(search("q", "C:\\temp").rows()).extracting(ItemSearchRow::title)
            .containsExactly("경로 C:\\temp 문항");
    }

    @Test
    @DisplayName("태그의 % 는 글자 그대로 비교해 0건")
    void searchTagPercentIsLiteral() {
        ItemSearchResponse response = search("tag", "%");

        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
    }

    @Test
    @DisplayName("없는 단원 · 범위 밖 난이도 · int 범위를 넘는 난이도는 오류 없이 0건")
    void searchUnknownUnitOrOutOfRangeLevelReturnsEmpty() {
        assertThat(search("unit", "Z99-99").count()).isZero();
        assertThat(search("level", "6").count()).isZero();
        assertThat(search("level", "99999999999999999999").count()).isZero();
    }

    @Test
    @DisplayName("전체 페이지를 넘는 페이지: 건수는 그대로, 행은 없음, message 는 null")
    void searchBeyondLastPageKeepsCount() {
        ItemSearchResponse response = search("page", "2");

        assertThat(response.count()).isEqualTo(3);
        assertThat(response.rows()).isEmpty();
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("단원 정렬: 단원 코드 내림차순, 같은 단원은 난이도 내림차순")
    void searchSortByUnitDesc() {
        assertThat(search("sort", "unit", "dir", "desc").rows()).extracting(ItemSearchRow::title)
            .containsExactly("소수 곱셈", "분수 문장제", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("findWithDetailsByIdIn: 단원 · 태그가 한 번에 로드된다")
    void findWithDetailsByIdInLoadsUnitAndTags() {
        Page<Item> page = itemRepository.findAll(
            ItemSearchSpecifications.isPublic(), PageRequest.of(0, ItemSearchCondition.PAGE_SIZE));
        List<Integer> ids = page.getContent().stream().map(Item::getId).toList();
        entityManager.clear();

        List<Item> items = itemRepository.findWithDetailsByIdIn(ids);

        assertThat(items).hasSize(4);
        assertThat(items).allSatisfy(item -> {
            assertThat(Hibernate.isInitialized(item.getUnit())).isTrue();
            assertThat(Hibernate.isInitialized(item.getTags())).isTrue();
        });
    }
}
