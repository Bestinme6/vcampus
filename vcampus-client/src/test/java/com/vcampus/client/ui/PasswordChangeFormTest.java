package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordChangeFormTest {
    @Test
    void acceptsDistinctMatchingPasswordOfValidLength() {
        assertTrue(PasswordChangeForm.validate(
                "temporary1".toCharArray(), "changed-pass".toCharArray(),
                "changed-pass".toCharArray()).isEmpty());
    }

    @Test
    void rejectsWrongConfirmation() {
        assertEquals("两次输入的新密码不一致", PasswordChangeForm.validate(
                "temporary1".toCharArray(), "changed-pass".toCharArray(),
                "another-pass".toCharArray()).orElseThrow());
    }

    @Test
    void rejectsShortOrLongNewPassword() {
        assertEquals("新密码长度必须为 8—128 位", PasswordChangeForm.validate(
                "temporary1".toCharArray(), "short".toCharArray(),
                "short".toCharArray()).orElseThrow());
        char[] longPassword = new char[129];
        java.util.Arrays.fill(longPassword, 'x');
        assertEquals("新密码长度必须为 8—128 位", PasswordChangeForm.validate(
                "temporary1".toCharArray(), longPassword, longPassword).orElseThrow());
    }

    @Test
    void rejectsNewPasswordEqualToCurrentPassword() {
        assertEquals("新密码不能与当前密码相同", PasswordChangeForm.validate(
                "temporary1".toCharArray(), "temporary1".toCharArray(),
                "temporary1".toCharArray()).orElseThrow());
    }
}
