package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 검색 파라미터 해석 — 레거시 search.php 의 buildSearchQuery 와 같은 값이 나와야 한다. */
class ItemSearchConditionTest {

    private static MultiValueMap<String, String> params(String... keyValues) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.add(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("파라미터가 없으면 기본값: 조건 없음, level < 5, 기본 정렬, 1페이지")
    void emptyParamsUseLegacyDefaults() {
        ItemSearchCondition condition = ItemSearchCondition.from(params());

        assertThat(condition.keyword()).isEmpty();
        assertThat(condition.unitCode()).isEmpty();
        assertThat(condition.tag()).isEmpty();
        assertThat(condition.levelFilter().mode()).isEqualTo(ItemSearchCondition.LevelFilter.Mode.BELOW_MAX);
        assertThat(condition.page()).isEqualTo(1);
        assertThat(condition.toSort()).isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("난이도: 1~5 한 자리는 그 값, 그 밖은 PHP (int) 변환 값(BR-12)")
    void levelUsesPhpIntCast() {
        assertThat(ItemSearchCondition.parseLevel("3").level()).isEqualTo(OptionalInt.of(3));
        assertThat(ItemSearchCondition.parseLevel("05").level()).isEqualTo(OptionalInt.of(5));
        assertThat(ItemSearchCondition.parseLevel("3a").level()).isEqualTo(OptionalInt.of(3));
        assertThat(ItemSearchCondition.parseLevel("4.9").level()).isEqualTo(OptionalInt.of(4));
        assertThat(ItemSearchCondition.parseLevel("5e0").level()).isEqualTo(OptionalInt.of(5));
        assertThat(ItemSearchCondition.parseLevel("1e3").level()).isEqualTo(OptionalInt.of(1000));
        assertThat(ItemSearchCondition.parseLevel("abc").level()).isEqualTo(OptionalInt.of(0));
        assertThat(ItemSearchCondition.parseLevel("-1").level()).isEqualTo(OptionalInt.of(-1));
        assertThat(ItemSearchCondition.parseLevel("6").level()).isEqualTo(OptionalInt.of(6));
    }

    @Test
    @DisplayName("PHP (int): long 범위를 넘으면 최댓값으로 맞추고, 난이도 조건은 0건 조건이 된다")
    void levelOverflowCapsLikePhp() {
        assertThat(ItemSearchCondition.phpIntCast("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntCast("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(ItemSearchCondition.phpIntCast(" 3")).isEqualTo(3);
        assertThat(ItemSearchCondition.parseLevel("99999999999999999999").level()).isEmpty();
    }

    @Test
    @DisplayName("페이지: 숫자가 아니면 1, 0 은 1, 999 초과는 999, 끝 줄바꿈 하나는 허용(BR-24)")
    void pageIsClampedLikeLegacy() {
        assertThat(ItemSearchCondition.parsePage("2")).isEqualTo(2);
        assertThat(ItemSearchCondition.parsePage("0")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage("-1")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage("")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage("abc")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage(" 2")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage("2\n")).isEqualTo(2);
        assertThat(ItemSearchCondition.parsePage("1000")).isEqualTo(999);
        assertThat(ItemSearchCondition.parsePage("99999999999999999999")).isEqualTo(999);
    }

    @Test
    @DisplayName("키워드 · 태그: PHP trim 후 글자 수로 자른다 — 키워드 100자, 태그 50자(BR-06 · BR-15)")
    void keywordAndTagAreTrimmedAndTruncated() {
        ItemSearchCondition condition = ItemSearchCondition.from(
            params("q", " " + "가".repeat(101) + " ", "tag", "a".repeat(51)));

        assertThat(condition.keyword()).isEqualTo("가".repeat(100));
        assertThat(condition.tag()).isEqualTo("a".repeat(50));
    }

    @Test
    @DisplayName("PHP trim 은 전각 공백을 지우지 않고, 글자 수는 코드포인트로 센다")
    void phpTrimKeepsFullWidthSpaceAndCountsCodePoints() {
        assertThat(ItemSearchCondition.phpTrim("\t 분수 \n")).isEqualTo("분수");
        assertThat(ItemSearchCondition.phpTrim("　")).isEqualTo("　");
        assertThat(ItemSearchCondition.truncate("😀".repeat(3), 2)).isEqualTo("😀😀");
    }

    @Test
    @DisplayName("같은 이름이 여러 번 오면 마지막 값, name[] 배열이면 첫 값(BR-03)")
    void multiValueParamsFollowPhp() {
        assertThat(ItemSearchCondition.param(params("q", "a", "q", "b"), "q", "")).isEqualTo("b");
        assertThat(ItemSearchCondition.param(params("q[]", "a", "q[]", "b"), "q", "")).isEqualTo("a");
        assertThat(ItemSearchCondition.param(params(), "page", "1")).isEqualTo("1");
    }

    @Test
    @DisplayName("정렬: 방향을 비우면 title 오름차순 · level 내림차순, unit 은 level DESC 를 2차 기준으로(BR-20 · BR-21)")
    void sortFollowsLegacyDirectionRules() {
        assertThat(ItemSearchCondition.from(params("sort", "TITLE", "dir", "desc")).toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("title"), Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "level")).toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "unit", "dir", "asc")).toSort())
            .isEqualTo(Sort.by(Sort.Order.asc("unit.code"), Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "created", "dir", "up")).toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("모르는 정렬 기준은 기본 정렬(BR-18)")
    void unknownSortFallsBackToDefault() {
        ItemSearchCondition condition = ItemSearchCondition.from(params("sort", "price", "dir", "up"));

        assertThat(condition.sort()).isEmpty();
        assertThat(condition.dir()).isEmpty();
        assertThat(condition.toSort()).isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
    }
}
