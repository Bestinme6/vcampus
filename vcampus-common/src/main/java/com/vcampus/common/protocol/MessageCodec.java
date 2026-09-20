package com.vcampus.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端与服务端共用的长度前缀二进制消息编解码器。
 *
 * <p>每个消息都包含固定魔数、协议版本和消息类型，随后写入请求编号、动作、状态及字符串参数表。
 * 字符串按 UTF-8 编码，并在分配内存前检查长度与参数数量上限。</p>
 *
 * <p>本类不使用 Java 原生对象序列化，通信两端必须使用相同协议版本。</p>
 *
 * @since 1.0
 */
public final class MessageCodec {
    private static final int MAGIC = 0x5643414D;
    private static final short VERSION = 1;
    private static final byte REQUEST = 1;
    private static final byte RESPONSE = 2;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_MAP_ENTRIES = 128;

    private MessageCodec() {
    }

    /**
     * 将一个完整请求编码到输出流并刷新缓冲区。
     *
     * @param output 目标数据输出流
     * @param request 待发送的请求
     * @throws IOException 写入失败或请求包含无法编码的数据时抛出
     */
    public static void writeRequest(DataOutputStream output, RequestMessage request) throws IOException {
        writeHeader(output, REQUEST);
        writeString(output, request.requestId());
        writeString(output, request.action());
        writeMap(output, request.parameters());
        output.flush();
    }

    /**
     * 从输入流读取并校验一个请求。
     *
     * @param input 请求数据输入流
     * @return 解码后的不可变请求
     * @throws IOException 流中断、协议头无效或字段超出安全上限时抛出
     */
    public static RequestMessage readRequest(DataInputStream input) throws IOException {
        readAndValidateHeader(input, REQUEST);
        String requestId = readString(input);
        String action = readString(input);
        return new RequestMessage(requestId, action, readMap(input));
    }

    /**
     * 将一个完整响应编码到输出流并刷新缓冲区。
     *
     * @param output 目标数据输出流
     * @param response 待发送的响应
     * @throws IOException 写入失败或响应包含无法编码的数据时抛出
     */
    public static void writeResponse(DataOutputStream output, ResponseMessage response) throws IOException {
        writeHeader(output, RESPONSE);
        writeString(output, response.requestId());
        output.writeBoolean(response.success());
        writeString(output, response.message());
        writeMap(output, response.data());
        output.flush();
    }

    /**
     * 从输入流读取并校验一个响应。
     *
     * @param input 响应数据输入流
     * @return 解码后的不可变响应
     * @throws IOException 流中断、协议头无效或字段超出安全上限时抛出
     */
    public static ResponseMessage readResponse(DataInputStream input) throws IOException {
        readAndValidateHeader(input, RESPONSE);
        String requestId = readString(input);
        boolean success = input.readBoolean();
        String message = readString(input);
        return new ResponseMessage(requestId, success, message, readMap(input));
    }

    private static void writeHeader(DataOutputStream output, byte messageType) throws IOException {
        output.writeInt(MAGIC);
        output.writeShort(VERSION);
        output.writeByte(messageType);
    }

    private static void readAndValidateHeader(DataInputStream input, byte expectedType) throws IOException {
        if (input.readInt() != MAGIC) {
            throw new ProtocolException("Invalid VCampus protocol magic");
        }
        if (input.readShort() != VERSION) {
            throw new ProtocolException("Unsupported VCampus protocol version");
        }
        if (input.readByte() != expectedType) {
            throw new ProtocolException("Unexpected VCampus message type");
        }
    }

    private static void writeMap(DataOutputStream output, Map<String, String> values) throws IOException {
        if (values.size() > MAX_MAP_ENTRIES) {
            throw new ProtocolException("Too many message parameters");
        }
        output.writeInt(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static Map<String, String> readMap(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_MAP_ENTRIES) {
            throw new ProtocolException("Invalid message parameter count: " + size);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            values.put(readString(input), readString(input));
        }
        return values;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new ProtocolException("Message field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new ProtocolException("Invalid message field length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
