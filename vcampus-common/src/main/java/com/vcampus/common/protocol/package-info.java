/**
 * VCampus 客户端与服务端共享的 Socket 通信协议。
 *
 * <p>{@link com.vcampus.common.protocol.Actions} 定义动作名，
 * {@link com.vcampus.common.protocol.RequestMessage} 与
 * {@link com.vcampus.common.protocol.ResponseMessage} 定义消息结构，
 * {@link com.vcampus.common.protocol.MessageCodec} 负责长度前缀二进制编码。</p>
 *
 * <p>协议刻意不使用 Java 原生对象序列化，以保持输入边界明确并避免将运行时对象图暴露到网络。</p>
 *
 * @since 1.0
 */
package com.vcampus.common.protocol;
