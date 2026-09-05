package com.vcampus.client.fx;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClientSessionTest {
    @Test void rejectsFailedOrMalformedLoginRatherThanOpeningWorkspace() {
        assertThrows(IllegalArgumentException.class, () -> ClientSession.from(
                ResponseMessage.failure("r", "密码错误")));
        assertThrows(IllegalArgumentException.class, () -> ClientSession.from(ok("", "STUDENT", "false")));
        assertThrows(IllegalArgumentException.class, () -> ClientSession.from(ok("token", "STUDENT,TEACHER", "false")));
        assertThrows(IllegalArgumentException.class, () -> ClientSession.from(ok("token", "UNKNOWN", "false")));
    }
    @Test void preservesRequiredPasswordChangeAndDoesNotLeakTokenInToString() {
        var session = ClientSession.from(ok("private-token", "TEACHER,LIBRARY_ADMIN", "true"));
        assertTrue(session.requiresPasswordChange());
        assertEquals(UserRole.TEACHER, session.identity());
        assertFalse(session.toString().contains("private-token"));
        assertFalse(session.passwordChanged().requiresPasswordChange());
    }
    @Test void validatesConnectionBeforeAttemptingNetworkAndHonorsEnvironment() {
        assertThrows(IllegalArgumentException.class, () -> ServerConnection.parse("", "9090"));
        for (String port : new String[]{"0", "65536", "abc"}) {
            assertThrows(IllegalArgumentException.class, () -> ServerConnection.parse("localhost", port));
        }
        assertEquals(new ServerConnection("campus-host", 9100),
                ServerConnection.defaults(Map.of("VCAMPUS_HOST", "campus-host", "VCAMPUS_PORT", "9100")));
    }
    private static ResponseMessage ok(String token,String roles,String force) {
        return ResponseMessage.success("r", "ok", Map.of("sessionToken",token,"displayName","演示学生",
                "roles",roles,"forcePasswordChange",force));
    }
}
