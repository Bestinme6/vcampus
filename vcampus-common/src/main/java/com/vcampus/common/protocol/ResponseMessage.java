package com.vcampus.common.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ResponseMessage(
        String requestId,
        boolean success,
        String message,
        Map<String, String> data) {

    public ResponseMessage {
        requestId = Objects.requireNonNull(requestId, "requestId");
        message = Objects.requireNonNullElse(message, "");
        data = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
    }

    public static ResponseMessage success(String requestId, String message, Map<String, String> data) {
        return new ResponseMessage(requestId, true, message, data);
    }

    public static ResponseMessage failure(String requestId, String message) {
        return new ResponseMessage(requestId, false, message, Map.of());
    }
}

