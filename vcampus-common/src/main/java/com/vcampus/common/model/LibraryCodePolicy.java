package com.vcampus.common.model;

import java.util.Locale;

public final class LibraryCodePolicy {
    private LibraryCodePolicy() {
    }

    public static String normalizeIsbn(String value) {
        if (value == null) {
            throw invalidIsbn();
        }
        String normalized = value.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!(isValidIsbn10(normalized) || isValidIsbn13(normalized))) {
            throw invalidIsbn();
        }
        return normalized;
    }

    public static String requireValidBarcode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("B[0-9]{9}")) {
            throw new IllegalArgumentException("馆藏条码格式不正确");
        }
        return normalized;
    }

    private static boolean isValidIsbn10(String value) {
        if (!value.matches("[0-9]{9}[0-9X]")) {
            return false;
        }
        int sum = 0;
        for (int index = 0; index < value.length(); index++) {
            int digit = value.charAt(index) == 'X' ? 10 : value.charAt(index) - '0';
            sum += (index + 1) * digit;
        }
        return sum % 11 == 0;
    }

    private static boolean isValidIsbn13(String value) {
        if (!value.matches("[0-9]{13}")) {
            return false;
        }
        int sum = 0;
        for (int index = 0; index < 12; index++) {
            int digit = value.charAt(index) - '0';
            sum += digit * (index % 2 == 0 ? 1 : 3);
        }
        int checkDigit = (10 - sum % 10) % 10;
        return checkDigit == value.charAt(12) - '0';
    }

    private static IllegalArgumentException invalidIsbn() {
        return new IllegalArgumentException("ISBN 格式或校验位不正确");
    }
}
