package com.vcampus.server;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.UserRepository;
import com.vcampus.server.security.PasswordHasher;
import com.vcampus.server.security.PasswordHasher.PasswordHash;

import java.io.Console;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;

public final class AdminBootstrapMain {
    private AdminBootstrapMain() {
    }

    public static void main(String[] args) throws Exception {
        String username = read("VCAMPUS_BOOTSTRAP_USERNAME", "admin").trim();
        String displayName = read("VCAMPUS_BOOTSTRAP_DISPLAY_NAME", "系统管理员").trim();
        validateIdentity(username, displayName);
        RoleCompositionPolicy.requireValid(Set.of(UserRole.SUPER_ADMIN));

        char[] password = readPassword();
        try {
            PasswordHash passwordHash = new PasswordHasher().hash(password);
            UserRepository users = new UserRepository(
                    new ConnectionFactory(DatabaseConfig.fromEnvironment()));
            if (users.findByUsername(username).isPresent()) {
                throw new IllegalStateException("账号已存在：" + username);
            }
            long userId = users.createUser(
                    username,
                    displayName,
                    passwordHash,
                    false,
                    Set.of(UserRole.SUPER_ADMIN));
            System.out.printf("首个管理员创建成功：id=%d, username=%s%n", userId, username);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "管理员创建失败。请确认已执行 database/schema.sql 和 database/seed.sql，并检查数据库环境变量。",
                    exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] readPassword() {
        String environmentPassword = System.getenv("VCAMPUS_BOOTSTRAP_PASSWORD");
        if (environmentPassword != null && !environmentPassword.isBlank()) {
            return environmentPassword.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "当前控制台无法隐藏密码，请设置环境变量 VCAMPUS_BOOTSTRAP_PASSWORD 后重试");
        }
        char[] password = console.readPassword("请输入管理员密码（至少 8 位）：");
        if (password == null) {
            throw new IllegalStateException("未读取到管理员密码");
        }
        return password;
    }

    private static void validateIdentity(String username, String displayName) {
        if (!username.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new IllegalArgumentException("管理员账号只能包含字母、数字、点、下划线和连字符，长度为 3—64 位");
        }
        if (displayName.isBlank() || displayName.length() > 100) {
            throw new IllegalArgumentException("管理员显示名称长度必须为 1—100 位");
        }
    }

    private static String read(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
