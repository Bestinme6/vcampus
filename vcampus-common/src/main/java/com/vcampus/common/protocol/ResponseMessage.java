package com.vcampus.common.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 应用服务器返回给客户端的不可变响应。
 *
 * @param requestId 与原请求相同的请求编号
 * @param success 业务操作是否成功
 * @param message 面向用户或调用方的结果说明
 * @param data 响应数据字符串表
 * @since 1.0
 */
public record ResponseMessage(
        String requestId,
        boolean success,
        String message,
        Map<String, String> data) {

    /**
     * 规范化响应文本并对数据表执行防御性复制。
     *
     * @param requestId 原请求编号
     * @param success 是否成功
     * @param message 结果说明，空值转换为空字符串
     * @param data 响应数据
     */
    public ResponseMessage {
        requestId = Objects.requireNonNull(requestId, "requestId");
        message = Objects.requireNonNullElse(message, "");
        data = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
    }

    /**
     * 创建成功响应。
     *
     * @param requestId 原请求编号
     * @param message 成功说明
     * @param data 响应数据
     * @return 成功响应
     */
    public static ResponseMessage success(String requestId, String message, Map<String, String> data) {
        return new ResponseMessage(requestId, true, message, data);
    }

    /**
     * 创建不携带业务数据的失败响应。
     *
     * @param requestId 原请求编号
     * @param message 失败原因
     * @return 失败响应
     */
    public static ResponseMessage failure(String requestId, String message) {
        return new ResponseMessage(requestId, false, message, Map.of());
    }
}
