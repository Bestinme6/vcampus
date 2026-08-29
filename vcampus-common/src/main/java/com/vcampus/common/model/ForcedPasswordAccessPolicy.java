package com.vcampus.common.model;

import com.vcampus.common.protocol.Actions;

import java.util.Set;

public final class ForcedPasswordAccessPolicy {
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            Actions.AUTH_CHANGE_PASSWORD,
            Actions.AUTH_LOGOUT,
            Actions.AUTH_SESSION);

    private ForcedPasswordAccessPolicy() {
    }

    public static boolean isAllowed(String action) {
        return ALLOWED_ACTIONS.contains(action);
    }
}
