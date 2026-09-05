package com.vcampus.server.image;

import java.util.Objects;

public final class ShopImageException extends RuntimeException {
    private final String code;

    public ShopImageException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public ShopImageException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
