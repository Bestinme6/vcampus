package com.vcampus.common.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCodecTest {
    @Test
    void requestRoundTripPreservesUnicodeAndParameters() throws IOException {
        RequestMessage expected = new RequestMessage(
                "request-1",
                Actions.AUTH_LOGIN,
                Map.of("username", "2026000001", "campus", "虚拟校园"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageCodec.writeRequest(new DataOutputStream(bytes), expected);
        RequestMessage actual = MessageCodec.readRequest(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals(expected, actual);
    }

    @Test
    void responseRoundTripPreservesResult() throws IOException {
        ResponseMessage expected = ResponseMessage.success(
                "request-2", "登录成功", Map.of("displayName", "演示学生"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageCodec.writeResponse(new DataOutputStream(bytes), expected);
        ResponseMessage actual = MessageCodec.readResponse(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals(expected, actual);
    }

    @Test
    void readerRejectsUnknownProtocolMagic() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x12345678);
        output.writeShort(1);
        output.writeByte(1);

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertThrows(ProtocolException.class, () -> MessageCodec.readRequest(input));
    }

    @Test
    void writerRejectsTooManyParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 0; index < 129; index++) {
            parameters.put("key-" + index, "value");
        }
        RequestMessage request = RequestMessage.create("test.limit", parameters);

        assertThrows(ProtocolException.class, () -> MessageCodec.writeRequest(
                new DataOutputStream(new ByteArrayOutputStream()), request));
    }
}
