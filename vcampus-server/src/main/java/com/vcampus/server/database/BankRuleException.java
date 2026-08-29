package com.vcampus.server.database;

import java.sql.SQLException;

public final class BankRuleException extends SQLException {
    public BankRuleException(String message) {
        super(message);
    }
}
