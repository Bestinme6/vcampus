package com.vcampus.common.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RequestMessage(String requestId, String action, Map<String, String> parameters) {
    public RequestMessage {
        requestId = requireText(requestId, "requestId");
        action = requireText(action, "action");
        parameters = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters")));
    }

    public static RequestMessage create(String action, Map<String, String> parameters) {
        return new RequestMessage(UUID.randomUUID().toString(), action, parameters);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

