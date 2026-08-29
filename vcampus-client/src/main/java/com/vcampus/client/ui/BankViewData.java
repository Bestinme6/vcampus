package com.vcampus.client.ui;

import com.vcampus.common.model.BankAccessPolicy;
import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerDirection;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BankViewData {
    private static final String MALFORMED = "服务器返回的银行数据格式不正确";

    private BankViewData() {
    }

    static boolean showAdminTabs(Set<UserRole> roles) {
        return BankAccessPolicy.canManage(roles);
    }

    static boolean canTransfer(BankAccountStatus status) {
        return status == BankAccountStatus.ACTIVE;
    }

    static AccountView account(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            return new AccountView(positiveLong(required(data, "accountId")),
                    required(data, "username"), required(data, "displayName"),
                    money(required(data, "balance")),
                    BankAccountStatus.valueOf(required(data, "status")),
                    Instant.parse(required(data, "updatedAt")));
        } catch (ResponseFailure failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    static AccountPage accountPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            PageHeader header = header(data);
            List<AccountRow> rows = new ArrayList<>(header.count());
            for (int index = 0; index < header.count(); index++) {
                List<String> fields = row(data, index, 8);
                rows.add(new AccountRow(positiveLong(fields.get(0)), positiveLong(fields.get(1)),
                        fields.get(2), fields.get(3), money(fields.get(4)),
                        BankAccountStatus.valueOf(fields.get(5)), Instant.parse(fields.get(6)),
                        Instant.parse(fields.get(7))));
            }
            return new AccountPage(rows, header.page(), header.pageSize(), header.total());
        } catch (ResponseFailure failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    static LedgerPage ledgerPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            PageHeader header = header(data);
            List<LedgerRow> rows = new ArrayList<>(header.count());
            for (int index = 0; index < header.count(); index++) {
                List<String> fields = row(data, index, 11);
                rows.add(new LedgerRow(positiveLong(fields.get(0)), positiveLong(fields.get(1)),
                        BankLedgerType.valueOf(fields.get(2)),
                        BankLedgerDirection.valueOf(fields.get(3)), money(fields.get(4)),
                        money(fields.get(5)), fields.get(6), nullablePositiveLong(fields.get(7)),
                        nullablePositiveLong(fields.get(8)), fields.get(9),
                        Instant.parse(fields.get(10))));
            }
            return new LedgerPage(rows, header.page(), header.pageSize(), header.total());
        } catch (ResponseFailure failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    static ResponseMessage requireSuccess(ResponseMessage response) {
        if (response == null) throw new IllegalArgumentException(MALFORMED);
        if (!response.success()) throw new IllegalArgumentException(response.message());
        return response;
    }

    private static Map<String, String> data(ResponseMessage response) {
        if (response == null) throw new IllegalArgumentException("null response");
        if (!response.success()) throw new ResponseFailure(response.message());
        return response.data();
    }

    private static PageHeader header(Map<String, String> data) {
        int page = positiveInt(required(data, "page"));
        int pageSize = positiveInt(required(data, "pageSize"));
        int total = nonNegativeInt(required(data, "total"));
        int count = nonNegativeInt(required(data, "count"));
        if (count > pageSize || count > total) throw new IllegalArgumentException("invalid count");
        return new PageHeader(page, pageSize, total, count);
    }

    private static List<String> row(Map<String, String> data, int index, int size) {
        List<String> fields = RowCodec.decode(required(data, "row." + index));
        if (fields.size() != size) throw new IllegalArgumentException("invalid row size");
        return fields;
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static BigDecimal money(String value) {
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.signum() < 0 || parsed.scale() > 2) throw new IllegalArgumentException("invalid money");
        return parsed.setScale(2);
    }

    private static int positiveInt(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException("invalid positive int");
        return parsed;
    }

    private static int nonNegativeInt(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException("invalid non-negative int");
        return parsed;
    }

    private static long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) throw new IllegalArgumentException("invalid positive long");
        return parsed;
    }

    private static Long nullablePositiveLong(String value) {
        return value == null || value.isBlank() ? null : positiveLong(value);
    }

    private static IllegalArgumentException malformed(RuntimeException exception) {
        return new IllegalArgumentException(MALFORMED, exception);
    }

    record AccountView(long accountId, String username, String displayName, BigDecimal balance,
                       BankAccountStatus status, Instant updatedAt) {
    }

    record AccountRow(long accountId, long userId, String username, String displayName,
                      BigDecimal balance, BankAccountStatus status, Instant createdAt,
                      Instant updatedAt) {
    }

    record LedgerRow(long id, long accountId, BankLedgerType type,
                     BankLedgerDirection direction, BigDecimal amount, BigDecimal balanceAfter,
                     String referenceNo, Long counterpartyUserId, Long operatorUserId,
                     String description, Instant createdAt) {
    }

    record AccountPage(List<AccountRow> rows, int page, int pageSize, int total) {
        AccountPage { rows = List.copyOf(rows); }
    }

    record LedgerPage(List<LedgerRow> rows, int page, int pageSize, int total) {
        LedgerPage { rows = List.copyOf(rows); }
    }

    private record PageHeader(int page, int pageSize, int total, int count) {
    }

    private static final class ResponseFailure extends RuntimeException {
        private ResponseFailure(String message) { super(message); }
    }
}
