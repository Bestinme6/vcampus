package com.vcampus.common.model;

import com.vcampus.common.protocol.Actions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedPasswordAccessPolicyTest {
    @Test
    void forcedPasswordSessionOnlyUsesSessionPasswordAndLogoutActions() {
        assertTrue(ForcedPasswordAccessPolicy.isAllowed(Actions.AUTH_CHANGE_PASSWORD));
        assertTrue(ForcedPasswordAccessPolicy.isAllowed(Actions.AUTH_LOGOUT));
        assertTrue(ForcedPasswordAccessPolicy.isAllowed(Actions.AUTH_SESSION));
        assertFalse(ForcedPasswordAccessPolicy.isAllowed(Actions.STUDENT_GET_SELF));
        assertFalse(ForcedPasswordAccessPolicy.isAllowed(Actions.ACCOUNT_SEARCH));
    }
}
