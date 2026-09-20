package com.vcampus.common.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将一组字符串字段编码为可嵌套在协议参数中的长度前缀行。
 *
 * <p>每个字段使用“字符数:内容”表示，因此内容可安全包含分隔符、空格和换行。
 * 解码结果为不可变列表。</p>
 *
 * @since 1.0
 */
public final class RowCodec {
    private RowCodec() {
    }

    /**
     * 编码字段数组，空字段保留为空字符串。
     *
     * @param fields 待编码字段
     * @return 长度前缀格式的单行文本
     */
    public static String encode(String... fields) {
        StringBuilder encoded = new StringBuilder();
        for (String field : fields) {
            String value = Objects.requireNonNullElse(field, "");
            encoded.append(value.length()).append(':').append(value);
        }
        return encoded.toString();
    }

    /**
     * 解码一行长度前缀文本。
     *
     * @param encoded 编码文本
     * @return 按原顺序排列的不可变字段列表
     * @throws IllegalArgumentException 长度标记损坏或字段超出可用内容时抛出
     */
    public static List<String> decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        List<String> fields = new ArrayList<>();
        int cursor = 0;
        while (cursor < encoded.length()) {
            int separator = encoded.indexOf(':', cursor);
            if (separator < 0) {
                throw new IllegalArgumentException("Invalid encoded row length");
            }
            int length;
            try {
                length = Integer.parseInt(encoded.substring(cursor, separator));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid encoded row length", exception);
            }
            int valueStart = separator + 1;
            int valueEnd = valueStart + length;
            if (length < 0 || valueEnd > encoded.length()) {
                throw new IllegalArgumentException("Encoded row field exceeds available data");
            }
            fields.add(encoded.substring(valueStart, valueEnd));
            cursor = valueEnd;
        }
        return List.copyOf(fields);
    }
}
