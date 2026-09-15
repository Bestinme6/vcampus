package com.vcampus.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountSecurityFormTest {
    @Test
    void acceptsAChangedPasswordWithMatchingConfirmation() {
        assertTrue(AccountSecurityForm.validate(
                "current-password".toCharArray(),
                "new-password-123".toCharArray(),
                "new-password-123".toCharArray()).isEmpty());
    }

    @Test
    void rejectsMissingWrongLengthMismatchedAndUnchangedPasswords() {
        assertEquals("请输入当前密码", AccountSecurityForm.validate(
                new char[0], "new-password-123".toCharArray(), "new-password-123".toCharArray()).orElseThrow());
        assertEquals("新密码长度必须为 8—128 位", AccountSecurityForm.validate(
                "current-password".toCharArray(), "short".toCharArray(), "short".toCharArray()).orElseThrow());
        assertEquals("两次输入的新密码不一致", AccountSecurityForm.validate(
                "current-password".toCharArray(), "new-password-123".toCharArray(),
                "different-password".toCharArray()).orElseThrow());
        assertEquals("新密码不能与当前密码相同", AccountSecurityForm.validate(
                "same-password".toCharArray(), "same-password".toCharArray(),
                "same-password".toCharArray()).orElseThrow());
    }
}
