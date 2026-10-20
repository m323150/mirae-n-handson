package com.example.item;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 원 쿼리 문자열 → 레거시 {@code search.php} 가 보는 파라미터 값. PHP 7.4 가 {@code $_GET} 을 만드는 방식
 * ({@code php_default_treat_data} · {@code php_register_variable_ex})을 옮기고, 배열이면 첫 값을 쓰는
 * {@code search.php:53-80} 의 규칙(BR-03)까지 적용해 이름마다 값 하나를 돌려준다.
 *
 * <ul>
 *   <li>{@code &} 로 나누고 빈 조각은 건너뛴다. {@code =} 가 없으면 값은 빈 문자열이다.</li>
 *   <li>이름 · 값 모두 {@code +} → 공백, {@code %XX} → 바이트로 푼다. 잘못된 {@code %} 는 글자 그대로 둔다.</li>
 *   <li>이름 앞 공백은 지우고, 첫 {@code [} 앞의 공백 · 점은 {@code _} 로 바꾼다. 이름은 NUL 앞까지만 쓴다.</li>
 *   <li>같은 이름이 다시 오면 나중 값이 이긴다(일반 값 · 배열 사이에서도).</li>
 *   <li>{@code name[]} · {@code name[key]} 는 배열이다. 첫 원소가 다시 배열이면 PHP 의 {@code (string)} 처럼
 *       {@code "Array"} 가 된다.</li>
 * </ul>
 */
final class LegacyQueryParams {

    /** PHP {@code max_input_vars} 기본값. 넘는 변수는 버린다. */
    static final int MAX_INPUT_VARS = 1000;
    /** PHP {@code max_input_nesting_level} 기본값. 넘으면 그 변수를 통째로 지운다. */
    static final int MAX_INPUT_NESTING_LEVEL = 64;
    /** PHP 에서 배열을 문자열로 바꾼 값. */
    private static final String ARRAY_AS_STRING = "Array";
    /** PHP 가 정수 키로 다루는 문자열: {@code 0} 또는 0 으로 시작하지 않는 정수. 다음 번호 매기기에 쓴다. */
    private static final Pattern INTEGER_KEY = Pattern.compile("0|-?[1-9][0-9]*");

    private LegacyQueryParams() {
    }

    /** 원 쿼리 문자열 → 이름마다 {@code search.php} 가 쓰는 문자열 하나. */
    static MultiValueMap<String, String> parse(String rawQuery) {
        PhpArray get = new PhpArray();
        int count = 0;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (++count > MAX_INPUT_VARS) {
                break;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            register(get, beforeNul(urlDecode(name)), urlDecode(value));
        }

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        get.entries.forEach((name, value) -> params.add(name, firstAsString(value)));
        return params;
    }

    /** {@code is_array($v) ? (string)reset($v) : (string)$v} */
    private static String firstAsString(Object value) {
        if (value instanceof PhpArray array) {
            if (array.entries.isEmpty()) {
                return "";
            }
            Object first = array.entries.values().iterator().next();
            return first instanceof PhpArray ? ARRAY_AS_STRING : (String) first;
        }
        return (String) value;
    }

    /** PHP {@code php_register_variable_ex} — 이름을 해석해 값을 넣는다. */
    private static void register(PhpArray top, String rawName, String value) {
        int start = 0;
        while (start < rawName.length() && rawName.charAt(start) == ' ') {
            start++;
        }
        String var = rawName.substring(start);

        StringBuilder base = new StringBuilder();
        int p = 0;
        boolean isArray = false;
        for (; p < var.length(); p++) {
            char c = var.charAt(p);
            if (c == ' ' || c == '.') {
                base.append('_');
            } else if (c == '[') {
                isArray = true;
                break;
            } else {
                base.append(c);
            }
        }
        if (base.isEmpty()) {
            return;
        }
        String baseName = base.toString();

        PhpArray table = top;
        String index = baseName;
        if (isArray) {
            int bracket = p;
            int level = 0;
            while (true) {
                if (++level > MAX_INPUT_NESTING_LEVEL) {
                    top.entries.remove(baseName);
                    return;
                }
                int keyStart = bracket + 1;
                String nextIndex;
                int close;
                if (keyStart < var.length() && var.charAt(keyStart) == ']') {
                    nextIndex = null;
                    close = keyStart;
                } else {
                    close = var.indexOf(']', keyStart);
                    if (close < 0) {
                        // 닫는 ] 가 없으면 배열이 아니다. 첫 단계면 [ 를 _ 로 바꾼 이름, 아니면 직전 키에 넣는다.
                        if (level == 1) {
                            index = baseName + "_" + var.substring(keyStart);
                        }
                        break;
                    }
                    nextIndex = var.substring(keyStart, close);
                }
                table = table.childArray(index);
                index = nextIndex;
                bracket = close + 1;
                if (bracket >= var.length() || var.charAt(bracket) != '[') {
                    break;
                }
            }
        }
        table.put(index, value);
    }

    /** PHP {@code php_url_decode} — {@code +} 는 공백, {@code %XX} 는 바이트, 나머지는 UTF-8 바이트 그대로. */
    static String urlDecode(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            if (c == '+') {
                bytes.write(' ');
                i++;
            } else if (c == '%' && i + 2 < encoded.length()
                && isHex(encoded.charAt(i + 1)) && isHex(encoded.charAt(i + 2))) {
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                bytes.write(high * 16 + low);
                i += 3;
            } else {
                int cp = encoded.codePointAt(i);
                bytes.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 이름은 C 문자열로 다루므로 NUL 앞까지만 쓴다. */
    private static String beforeNul(String value) {
        int nul = value.indexOf('\0');
        return nul < 0 ? value : value.substring(0, nul);
    }

    /** PHP 배열의 필요한 만큼만 — 넣은 순서 유지, 정수 키 다음 번호 매기기, 같은 키는 덮어쓰기. */
    private static final class PhpArray {

        private final Map<String, Object> entries = new LinkedHashMap<>();
        private long nextIndex;

        /** {@code key} 자리의 배열. 없거나 배열이 아니면 새 배열로 바꾼다. {@code key} 가 null 이면 끝에 붙인다. */
        PhpArray childArray(String key) {
            if (key != null && entries.get(key) instanceof PhpArray existing) {
                return existing;
            }
            PhpArray child = new PhpArray();
            put(key, child);
            return child;
        }

        void put(String key, Object value) {
            String actualKey = key == null ? String.valueOf(nextIndex) : key;
            entries.put(actualKey, value);
            if (INTEGER_KEY.matcher(actualKey).matches()) {
                BigInteger asInt = new BigInteger(actualKey);
                if (asInt.bitLength() < Long.SIZE && asInt.longValue() >= nextIndex) {
                    nextIndex = asInt.longValue() + 1;
                }
            }
        }
    }
}
