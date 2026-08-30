package com.vcampus.client.ui;

import com.vcampus.common.model.LibraryReturnCondition;

import java.util.Objects;

final class LibraryReturnFormPolicy {
    private LibraryReturnFormPolicy() {
    }

    static boolean requiresReason(LibraryReturnCondition condition) {
        return Objects.requireNonNull(condition) != LibraryReturnCondition.NORMAL;
    }

    static String label(LibraryReturnCondition condition) {
        return switch (Objects.requireNonNull(condition)) {
            case NORMAL -> "正常";
            case DAMAGED -> "破损";
            case LOST -> "遗失";
        };
    }

    static String missingReasonMessage(LibraryReturnCondition condition) {
        return switch (Objects.requireNonNull(condition)) {
            case NORMAL -> "";
            case DAMAGED -> "破损归还必须填写原因";
            case LOST -> "遗失关闭必须填写原因";
        };
    }

    static String confirmation(LibraryReturnCondition condition) {
        return switch (Objects.requireNonNull(condition)) {
            case NORMAL -> "确认归还该馆藏？";
            case DAMAGED -> "确认将该馆藏登记为破损并暂停流通？";
            case LOST -> "确认将该馆藏登记为遗失并关闭借阅？";
        };
    }
}
