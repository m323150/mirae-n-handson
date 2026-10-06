package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

/** 원 쿼리 문자열 → PHP {@code $_GET} 과 같은 값 — 레거시 search.php 가 보는 파라미터 해석. */
class LegacyQueryParamsTest {

    @Test
    @DisplayName("같은 이름이 다시 오면 나중 값이 이기고, 이름마다 값은 하나다")
    void sameNameLastValueWins() {
        MultiValueMap<String, String> params = LegacyQueryParams.parse("q=a&q=b");

        assertThat(params.get("q")).containsExactly("b");
    }

    @Test
    @DisplayName("name[] 배열은 첫 원소를 쓰고, 키는 [] 를 뗀 이름이다")
    void arrayParamUsesFirstElement() {
        MultiValueMap<String, String> params = LegacyQueryParams.parse("q[]=a&q[]=b");

        assertThat(params.get("q")).containsExactly("a");
        assertThat(params.containsKey("q[]")).isFalse();
    }

    @Test
    @DisplayName("일반 값과 배열이 섞이면 나중에 온 쪽이 이긴다")
    void scalarAndArrayLastOneWins() {
        assertThat(LegacyQueryParams.parse("q[]=a&q=b").getFirst("q")).isEqualTo("b");
        assertThat(LegacyQueryParams.parse("q=b&q[]=a").getFirst("q")).isEqualTo("a");
    }

    @Test
    @DisplayName("첫 원소가 다시 배열이면 PHP (string) 처럼 \"Array\" 가 된다")
    void nestedArrayBecomesArrayString() {
        assertThat(LegacyQueryParams.parse("q[x][y]=1").getFirst("q")).isEqualTo("Array");
    }

    @Test
    @DisplayName("+ 는 공백, %XX 는 UTF-8 바이트로 풀고, 잘못된 % 는 글자 그대로 둔다")
    void urlDecodeFollowsPhp() {
        assertThat(LegacyQueryParams.parse("q=%EB%B6%84%EC%88%98+%EB%8D%A7%EC%85%88").getFirst("q"))
            .isEqualTo("분수 덧셈");
        assertThat(LegacyQueryParams.parse("q=%zz").getFirst("q")).isEqualTo("%zz");
        assertThat(LegacyQueryParams.parse("q=50%").getFirst("q")).isEqualTo("50%");
    }

    @Test
    @DisplayName("= 가 없으면 빈 문자열, 빈 조각은 건너뛴다")
    void missingEqualsAndEmptyPiecesFollowPhp() {
        MultiValueMap<String, String> params = LegacyQueryParams.parse("&&unit&page=2&");

        assertThat(params.getFirst("unit")).isEmpty();
        assertThat(params.getFirst("page")).isEqualTo("2");
        assertThat(params.keySet()).containsExactly("unit", "page");
    }

    @Test
    @DisplayName("이름 앞 공백은 지우고, 이름 안의 공백 · 점은 _ 로, 닫히지 않은 [ 도 _ 로 바꾼다")
    void nameNormalizationFollowsPhp() {
        assertThat(LegacyQueryParams.parse("+q=1").getFirst("q")).isEqualTo("1");
        assertThat(LegacyQueryParams.parse("a.b=1").getFirst("a_b")).isEqualTo("1");
        assertThat(LegacyQueryParams.parse("a+b=1").getFirst("a_b")).isEqualTo("1");
        assertThat(LegacyQueryParams.parse("q[=1").getFirst("q_")).isEqualTo("1");
    }

    @Test
    @DisplayName("이름은 NUL 앞까지만 쓴다")
    void nameIsCutAtNul() {
        assertThat(LegacyQueryParams.parse("q%00x=1").getFirst("q")).isEqualTo("1");
    }

    @Test
    @DisplayName("max_input_vars(1000) 를 넘는 변수는 버린다")
    void variablesBeyondMaxInputVarsAreDropped() {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < LegacyQueryParams.MAX_INPUT_VARS; i++) {
            query.append("v").append(i).append("=x&");
        }
        query.append("q=late");

        MultiValueMap<String, String> params = LegacyQueryParams.parse(query.toString());

        assertThat(params).hasSize(LegacyQueryParams.MAX_INPUT_VARS);
        assertThat(params.containsKey("q")).isFalse();
    }

    @Test
    @DisplayName("배열 첫 원소가 빈 값이면 빈 문자열, 키가 있는 배열 name[k] 도 첫 원소를 쓴다")
    void arrayWithEmptyOrKeyedElementUsesFirstElement() {
        assertThat(LegacyQueryParams.parse("tag[]=").get("tag")).isEqualTo(List.of(""));
        assertThat(LegacyQueryParams.parse("tag[k]=계산").getFirst("tag")).isEqualTo("계산");
    }
}
