package com.vcampus.common.protocol;

import java.io.IOException;

public final class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }
}

