package com.vcampus.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MessageCodec {
    private static final int MAGIC = 0x5643414D;
    private static final short VERSION = 1;
    private static final byte REQUEST = 1;
    private static final byte RESPONSE = 2;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_MAP_ENTRIES = 128;

    private MessageCodec() {
    }

    public static void writeRequest(DataOutputStream output, RequestMessage request) throws IOException {
        writeHeader(output, REQUEST);
        writeString(output, request.requestId());
        writeString(output, request.action());
        writeMap(output, request.parameters());
        output.flush();
    }

    public static RequestMessage readRequest(DataInputStream input) throws IOException {
        readAndValidateHeader(input, REQUEST);
        String requestId = readString(input);
        String action = readString(input);
        return new RequestMessage(requestId, action, readMap(input));
    }

    public static void writeResponse(DataOutputStream output, ResponseMessage response) throws IOException {
        writeHeader(output, RESPONSE);
        writeString(output, response.requestId());
        output.writeBoolean(response.success());
        writeString(output, response.message());
        writeMap(output, response.data());
        output.flush();
    }

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
