package com.vcampus.common.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RowCodec {
    private RowCodec() {
    }

    public static String encode(String... fields) {
        StringBuilder encoded = new StringBuilder();
        for (String field : fields) {
            String value = Objects.requireNonNullElse(field, "");
            encoded.append(value.length()).append(':').append(value);
        }
        return encoded.toString();
    }

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
