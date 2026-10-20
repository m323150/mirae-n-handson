package com.example.item;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 조건. 레거시 {@code legacy/item-bank-php/search.php} 의 {@code buildSearchQuery} 가 GET 파라미터를
 * 해석하는 방식을 그대로 옮긴다. 잘못된 값도 거부하지 않고 레거시와 같은 값으로 바꿔 검색한다(BR-04).
 *
 * @param keyword     앞뒤 공백을 뺀 키워드. 비어 있으면 조건 없음
 * @param unitCode    앞뒤 공백을 뺀 단원 코드. 대소문자를 바꾸지 않는다(BR-09)
 * @param levelFilter 난이도 조건
 * @param tag         앞뒤 공백을 뺀 태그 이름. 비어 있으면 조건 없음
 * @param sort        정렬 기준(id · title · unit · level · created). 모르는 값이면 빈 문자열
 * @param dir         정렬 방향(asc · desc). 그 밖의 값이면 빈 문자열
 * @param page        1 ~ 999 로 보정한 페이지 번호
 */
record ItemSearchCondition(
    String keyword,
    String unitCode,
    LevelFilter levelFilter,
    String tag,
    String sort,
    String dir,
    int page) {

    /** 키워드 최대 글자 수(BR-06). */
    static final int KEYWORD_MAX_LENGTH = 100;
    /** 태그 이름 최대 글자 수(BR-15). */
    static final int TAG_MAX_LENGTH = 50;
    /** 난이도 하한. */
    static final int LEVEL_MIN = 1;
    /** 난이도 상한. 난이도를 비우면 이 값 미만만 검색한다(BR-11). */
    static final int LEVEL_MAX = 5;
    /** 한 페이지 문항 수(BR-23). */
    static final int PAGE_SIZE = 20;
    /** 페이지 번호 하한 · 기본값(BR-24). */
    static final int PAGE_MIN = 1;
    /** 페이지 번호 상한(BR-24). */
    static final int PAGE_MAX = 999;

    /** PHP {@code trim()} 이 지우는 문자: 공백 · \t · \n · \r · \0 · \x0B. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";
    /** PHP 숫자 문자열 앞에서 건너뛰는 공백: 공백 · \t · \n · \r · \v · \f. */
    private static final String PHP_NUMERIC_LEADING_WHITESPACE = " \t\n\r\u000B\f";
    /** 레거시 {@code /^[1-5]$/}. 앞뒤 공백을 뺀 값에 쓰므로 {@code $} 의 끝 줄바꿈 허용은 의미가 없다. */
    private static final Pattern LEVEL_PATTERN = Pattern.compile("[" + LEVEL_MIN + "-" + LEVEL_MAX + "]");
    /** 레거시 {@code /^[0-9]+$/}. PHP 의 {@code $} 는 끝 줄바꿈 하나를 허용한다. */
    private static final Pattern PAGE_PATTERN = Pattern.compile("([0-9]+)\n?");
    /** PHP 숫자 문자열의 앞부분: 부호, 정수 또는 소수, 지수. */
    private static final Pattern PHP_NUMBER_PREFIX =
        Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private static final String SORT_ID = "id";
    private static final String SORT_TITLE = "title";
    private static final String SORT_UNIT = "unit";
    private static final String SORT_LEVEL = "level";
    private static final String SORT_CREATED = "created";
    private static final String DIR_ASC = "asc";
    private static final String DIR_DESC = "desc";
    private static final List<String> SORT_KEYS = List.of(SORT_ID, SORT_TITLE, SORT_UNIT, SORT_LEVEL, SORT_CREATED);

    /**
     * 난이도 조건.
     *
     * @param mode  조건 종류
     * @param level {@link Mode#EQUAL} 일 때 비교할 값. {@code int} 범위를 넘으면 비어 있다(→ 0건)
     */
    record LevelFilter(Mode mode, OptionalInt level) {

        enum Mode {
            /** 난이도를 비움 — {@code level < 5}(BR-11). */
            BELOW_MAX,
            /** {@code level = ?}(BR-12). */
            EQUAL
        }

        static LevelFilter belowMax() {
            return new LevelFilter(Mode.BELOW_MAX, OptionalInt.empty());
        }

        static LevelFilter equalTo(long value) {
            boolean fitsInt = value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            return new LevelFilter(Mode.EQUAL, fitsInt ? OptionalInt.of((int) value) : OptionalInt.empty());
        }
    }

    /** GET 파라미터 → 검색 조건. */
    static ItemSearchCondition from(MultiValueMap<String, String> params) {
        String keyword = truncate(phpTrim(param(params, "q", "")), KEYWORD_MAX_LENGTH);
        String unitCode = phpTrim(param(params, "unit", ""));
        LevelFilter levelFilter = parseLevel(phpTrim(param(params, "level", "")));
        String tag = truncate(phpTrim(param(params, "tag", "")), TAG_MAX_LENGTH);

        String sort = asciiLower(phpTrim(param(params, "sort", "")));
        if (!SORT_KEYS.contains(sort)) {
            sort = "";
        }
        String dir = asciiLower(phpTrim(param(params, "dir", "")));
        if (!dir.equals(DIR_ASC) && !dir.equals(DIR_DESC)) {
            dir = "";
        }

        int page = parsePage(param(params, "page", String.valueOf(PAGE_MIN)));
        return new ItemSearchCondition(keyword, unitCode, levelFilter, tag, sort, dir, page);
    }

    /** 정렬(BR-17 ~ BR-21). 방향을 주지 않았을 때의 기본 방향은 레거시 분기 조건과 같다. */
    Sort toSort() {
        Sort.Order idAsc = Sort.Order.asc("id");
        Sort.Order levelDesc = Sort.Order.desc("level");
        return switch (sort) {
            case SORT_ID -> Sort.by(dir.equals(DIR_DESC) ? Sort.Order.desc("id") : idAsc);
            case SORT_TITLE -> Sort.by(order(dir.equals(DIR_DESC), "title"), idAsc);
            case SORT_UNIT -> Sort.by(order(dir.equals(DIR_DESC), "unit.code"), levelDesc, idAsc);
            case SORT_LEVEL -> Sort.by(order(!dir.equals(DIR_ASC), "level"), idAsc);
            case SORT_CREATED -> Sort.by(order(!dir.equals(DIR_ASC), "createdAt"), idAsc);
            default -> Sort.by(levelDesc, idAsc);
        };
    }

    private static Sort.Order order(boolean descending, String property) {
        return descending ? Sort.Order.desc(property) : Sort.Order.asc(property);
    }

    /**
     * 이름의 값 고르기(BR-03). 원 쿼리 문자열이 있으면 {@link LegacyQueryParams} 가 이미 PHP 규칙으로 이름마다
     * 값 하나를 골라 두므로 그 값을 쓴다. 그 밖의 경우의 근사: {@code name=a&name=b} 는 마지막 값,
     * {@code name[]=a&name[]=b} 처럼 배열로 오면 첫 값, 둘 다 오면 배열이 아닌 쪽.
     */
    static String param(MultiValueMap<String, String> params, String name, String defaultValue) {
        List<String> plain = params.get(name);
        if (plain != null && !plain.isEmpty()) {
            return nullToEmpty(plain.get(plain.size() - 1));
        }
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (entry.getKey().startsWith(name + "[") && !entry.getValue().isEmpty()) {
                return nullToEmpty(entry.getValue().get(0));
            }
        }
        return defaultValue;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 난이도(BR-11 · BR-12). 빈 값은 {@code level < 5}, {@code 1~5} 한 자리는 그 값, 그 밖은 PHP {@code (int)} 값. */
    static LevelFilter parseLevel(String level) {
        if (level.isEmpty()) {
            return LevelFilter.belowMax();
        }
        if (LEVEL_PATTERN.matcher(level).matches()) {
            return LevelFilter.equalTo(Integer.parseInt(level));
        }
        return LevelFilter.equalTo(phpIntCast(level));
    }

    /** 페이지(BR-24). 숫자가 아니면 1, 1 보다 작으면 1, 999 보다 크면 999. 앞뒤 공백은 지우지 않는다. */
    static int parsePage(String pageRaw) {
        Matcher matcher = PAGE_PATTERN.matcher(pageRaw);
        if (!matcher.matches()) {
            return PAGE_MIN;
        }
        BigInteger value = new BigInteger(matcher.group(1));
        if (value.compareTo(BigInteger.valueOf(PAGE_MAX)) > 0) {
            return PAGE_MAX;
        }
        return Math.max(value.intValue(), PAGE_MIN);
    }

    /**
     * PHP 7.4 의 {@code (int)$string}. 앞 공백을 건너뛰고 앞부분의 숫자만 읽는다({@code "3a"} → 3,
     * {@code "4.9"} → 4, {@code "1e3"} → 1000). 숫자로 시작하지 않으면 0, long 범위를 넘으면 최댓값 · 최솟값.
     */
    static long phpIntCast(String value) {
        int start = 0;
        while (start < value.length() && PHP_NUMERIC_LEADING_WHITESPACE.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        Matcher matcher = PHP_NUMBER_PREFIX.matcher(value).region(start, value.length());
        if (!matcher.lookingAt()) {
            return 0;
        }
        String number = matcher.group();
        boolean integerForm = number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0;
        if (integerForm) {
            BigDecimal exact = new BigDecimal(number);
            return exact.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE
                : exact.compareTo(LONG_MIN) < 0 ? Long.MIN_VALUE
                : exact.longValueExact();
        }
        double parsed = Double.parseDouble(number);
        if (Double.isInfinite(parsed) || Double.isNaN(parsed)) {
            return 0;
        }
        // 범위를 넘으면 (long) 이 최댓값 · 최솟값으로 맞추고, 범위 안이면 소수점 아래를 버린다 — PHP 와 같다.
        return (long) parsed;
    }

    /** PHP {@code trim()} — 공백 · \t · \n · \r · \0 · \x0B 만 지운다(전각 공백은 남는다). */
    static String phpTrim(String value) {
        int begin = 0;
        int end = value.length();
        while (begin < end && PHP_TRIM_CHARS.indexOf(value.charAt(begin)) >= 0) {
            begin++;
        }
        while (end > begin && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(begin, end);
    }

    /** PHP {@code mb_substr($v, 0, $max, 'UTF-8')} — 글자(코드포인트) 수 기준으로 자른다. */
    static String truncate(String value, int maxLength) {
        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    /** PHP {@code strtolower()} — ASCII 대문자만 소문자로 바꾼다. */
    private static String asciiLower(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return sb.toString();
    }
}
