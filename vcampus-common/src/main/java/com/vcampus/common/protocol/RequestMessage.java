package com.vcampus.common.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 客户端发往应用服务器的不可变请求。
 *
 * @param requestId 用于匹配响应的唯一请求编号
 * @param action 具有稳定业务前缀的动作名
 * @param parameters UTF-8 字符串参数表
 * @since 1.0
 */
public record RequestMessage(String requestId, String action, Map<String, String> parameters) {
    /**
     * 创建并校验请求，同时对参数表执行防御性复制。
     *
     * @param requestId 唯一请求编号，不能为空
     * @param action 业务动作名，不能为空
     * @param parameters 请求参数
     */
    public RequestMessage {
        requestId = requireText(requestId, "requestId");
        action = requireText(action, "action");
        parameters = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters")));
    }

    /**
     * 使用随机 UUID 创建新请求。
     *
     * @param action 业务动作名
     * @param parameters 请求参数
     * @return 带有新请求编号的请求
     */
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
