package com.vcampus.server.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void hashesUseRandomSaltAndVerifyWithoutStoringPlaintext() {
        PasswordHasher hasher = new PasswordHasher();
        char[] password = "CourseDemo#2026".toCharArray();
        try {
            PasswordHasher.PasswordHash first = hasher.hash(password);
            PasswordHasher.PasswordHash second = hasher.hash(password);

            assertNotEquals(first.hash(), second.hash());
            assertNotEquals(first.salt(), second.salt());
            assertTrue(hasher.verify(password, first.hash(), first.salt()));
            assertFalse(hasher.verify("wrong-password".toCharArray(), first.hash(), first.salt()));
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
