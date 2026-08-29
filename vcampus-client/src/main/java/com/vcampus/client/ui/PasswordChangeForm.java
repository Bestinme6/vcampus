package com.vcampus.client.ui;

import java.util.Arrays;
import java.util.Optional;

final class PasswordChangeForm {
    private PasswordChangeForm() {
    }

    static Optional<String> validate(char[] current, char[] password, char[] confirmation) {
        if (current == null || current.length == 0) {
            return Optional.of("请输入当前临时密码");
        }
        if (password == null || password.length < 8 || password.length > 128) {
            return Optional.of("新密码长度必须为 8—128 位");
        }
        if (confirmation == null || !Arrays.equals(password, confirmation)) {
            return Optional.of("两次输入的新密码不一致");
        }
        if (Arrays.equals(current, password)) {
            return Optional.of("新密码不能与当前密码相同");
        }
        return Optional.empty();
    }
}
