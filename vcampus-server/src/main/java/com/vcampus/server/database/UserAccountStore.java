package com.vcampus.server.database;

import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.PasswordHasher.PasswordHash;

import java.sql.SQLException;
import java.util.Optional;

public interface UserAccountStore {
    Optional<UserAccount> findByUsername(String username) throws SQLException;

    void updateLastLogin(long userId) throws SQLException;

    boolean updatePassword(long userId, PasswordHash password, boolean forcePasswordChange)
            throws SQLException;
}
