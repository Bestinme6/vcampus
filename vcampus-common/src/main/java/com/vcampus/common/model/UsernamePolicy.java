package com.vcampus.common.model;

public final class UsernamePolicy {
    private UsernamePolicy() {
    }

    public static boolean matchesExactly(String suppliedUsername, String storedUsername) {
        return suppliedUsername != null && suppliedUsername.equals(storedUsername);
    }
}
