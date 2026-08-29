package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountViewDataTest {
    @Test
    void parsesAccountPageAndAdministrativeRoles() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", "1");
        data.put("pageSize", "8");
        data.put("total", "2");
        data.put("count", "2");
        data.put("row.0", RowCodec.encode(
                "31", "2026000031", "学生甲", "STUDENT", "FORUM_ADMIN,LIBRARY_ADMIN",
                "true", "false", ""));
        data.put("row.1", RowCodec.encode(
                "32", "T0000032", "教师乙", "TEACHER", "ACADEMIC_ADMIN",
                "false", "true", "2026-08-26T08:00:00Z"));

        AccountViewData.AccountPage page = AccountViewData.accounts(
                ResponseMessage.success("request", "查询成功", data));

        assertEquals(2, page.total());
        assertEquals(UserRole.STUDENT, page.rows().get(0).baseIdentity());
        assertEquals(Set.of(UserRole.FORUM_ADMIN, UserRole.LIBRARY_ADMIN),
                page.rows().get(0).administrativeRoles());
        assertEquals("", page.rows().get(0).lastLoginAt());
        assertEquals(UserRole.TEACHER, page.rows().get(1).baseIdentity());
        assertEquals("2026-08-26T08:00:00Z", page.rows().get(1).lastLoginAt());
    }

    @Test
    void parsesHierarchicalReferenceData() {
        Map<String, String> data = new LinkedHashMap<>();
        putReference(data, "department", 0, "10", "0", "CS", "计算机学院", "0");
        putReference(data, "major", 0, "20", "10", "SE", "软件工程", "0");
        putReference(data, "class", 0, "100", "20", "SE2601", "软件工程2601班", "2026");
        data.put("department.count", "1");
        data.put("major.count", "1");
        data.put("class.count", "1");

        AccountViewData.ReferenceData references = AccountViewData.references(
                ResponseMessage.success("request", "基础数据加载成功", data));

        assertEquals("计算机学院", references.departments().getFirst().name());
        assertEquals(10L, references.majors().getFirst().parentId());
        assertEquals(2026, references.classes().getFirst().year());
    }

    @Test
    void rejectsMalformedAccountRowsWithReadableMessage() {
        ResponseMessage response = ResponseMessage.success("request", "查询成功", Map.of(
                "page", "1", "pageSize", "8", "total", "1", "count", "1",
                "row.0", RowCodec.encode("not-a-number", "user")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> AccountViewData.accounts(response));

        assertEquals("服务器返回的账号数据格式不正确", exception.getMessage());
    }

    private void putReference(
            Map<String, String> data, String type, int index,
            String id, String parentId, String code, String name, String year) {
        String prefix = type + "." + index + ".";
        data.put(prefix + "id", id);
        data.put(prefix + "parentId", parentId);
        data.put(prefix + "code", code);
        data.put(prefix + "name", name);
        data.put(prefix + "year", year);
    }
}
