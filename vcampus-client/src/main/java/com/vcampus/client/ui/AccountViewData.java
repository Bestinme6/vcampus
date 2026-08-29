package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AccountViewData {
    private AccountViewData() {
    }

    static AccountPage accounts(ResponseMessage response) {
        try {
            Map<String, String> data = response.data();
            int count = integer(data, "count");
            List<AccountRow> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                List<String> fields = RowCodec.decode(required(data, "row." + index));
                if (fields.size() != 8) {
                    throw new IllegalArgumentException();
                }
                rows.add(new AccountRow(
                        Long.parseLong(fields.get(0)),
                        fields.get(1),
                        fields.get(2),
                        UserRole.valueOf(fields.get(3)),
                        roles(fields.get(4)),
                        booleanValue(fields.get(5)),
                        booleanValue(fields.get(6)),
                        fields.get(7)));
            }
            return new AccountPage(
                    rows, integer(data, "page"), integer(data, "pageSize"), integer(data, "total"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("服务器返回的账号数据格式不正确", exception);
        }
    }

    static ReferenceData references(ResponseMessage response) {
        try {
            Map<String, String> data = response.data();
            return new ReferenceData(
                    referenceItems(data, "department"),
                    referenceItems(data, "major"),
                    referenceItems(data, "class"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("服务器返回的账号基础数据格式不正确", exception);
        }
    }

    private static List<ReferenceItem> referenceItems(Map<String, String> data, String type) {
        List<ReferenceItem> items = new ArrayList<>();
        for (int index = 0; index < integer(data, type + ".count"); index++) {
            String prefix = type + "." + index + ".";
            items.add(new ReferenceItem(
                    Long.parseLong(required(data, prefix + "id")),
                    Long.parseLong(required(data, prefix + "parentId")),
                    required(data, prefix + "code"),
                    required(data, prefix + "name"),
                    Integer.parseInt(required(data, prefix + "year"))));
        }
        return List.copyOf(items);
    }

    private static Set<UserRole> roles(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Set.of();
        }
        Set<UserRole> roles = new LinkedHashSet<>();
        for (String value : encoded.split(",")) {
            roles.add(UserRole.valueOf(value.trim()));
        }
        return Set.copyOf(roles);
    }

    private static int integer(Map<String, String> data, String key) {
        return Integer.parseInt(required(data, key));
    }

    private static boolean booleanValue(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException();
        }
        return Boolean.parseBoolean(value);
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    record AccountRow(
            long userId,
            String username,
            String displayName,
            UserRole baseIdentity,
            Set<UserRole> administrativeRoles,
            boolean enabled,
            boolean forcePasswordChange,
            String lastLoginAt) {
        AccountRow {
            administrativeRoles = Set.copyOf(administrativeRoles);
        }
    }

    record AccountPage(List<AccountRow> rows, int page, int pageSize, int total) {
        AccountPage { rows = List.copyOf(rows); }
    }

    record ReferenceItem(long id, long parentId, String code, String name, int year) {
        @Override
        public String toString() {
            return code + " · " + name;
        }
    }

    record ReferenceData(
            List<ReferenceItem> departments,
            List<ReferenceItem> majors,
            List<ReferenceItem> classes) {
        ReferenceData {
            departments = List.copyOf(departments);
            majors = List.copyOf(majors);
            classes = List.copyOf(classes);
        }
    }
}
