package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernamePolicyTest {
    @Test
    void loginUsernameMustMatchStoredCaseExactly() {
        assertTrue(UsernamePolicy.matchesExactly("admin", "admin"));
        assertFalse(UsernamePolicy.matchesExactly("ADMin", "admin"));
        assertFalse(UsernamePolicy.matchesExactly("admin", "Admin"));
    }

    @Test
    void nullUsernamesNeverMatch() {
        assertFalse(UsernamePolicy.matchesExactly(null, "admin"));
        assertFalse(UsernamePolicy.matchesExactly("admin", null));
    }
}
