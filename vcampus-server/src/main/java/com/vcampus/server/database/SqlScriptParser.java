package com.vcampus.server.database;

import java.util.ArrayList;
import java.util.List;

/** Splits the repository's MySQL scripts, without requiring a mysql CLI. */
final class SqlScriptParser {
    private SqlScriptParser() { }

    static List<String> parse(String source) {
        List<String> statements = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                    sql.append(' ');
                }
            } else if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                    sql.append(' ');
                }
            } else if (quote != 0) {
                sql.append(c);
                if (c == '\\' && quote != '`' && next != 0) {
                    sql.append(next);
                    i++;
                } else if (c == quote) {
                    if (next == quote) {
                        sql.append(next);
                        i++;
                    } else {
                        quote = 0;
                    }
                }
            } else if (c == '#' || (c == '-' && next == '-'
                    && (i + 2 == source.length() || Character.isWhitespace(source.charAt(i + 2))))) {
                lineComment = true;
                sql.append(' ');
            } else if (c == '/' && next == '*') {
                if (i + 2 < source.length() && source.charAt(i + 2) == '!') {
                    throw new IllegalArgumentException("初始化脚本不支持 MySQL 可执行注释");
                }
                blockComment = true;
                i++;
                sql.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                sql.append(c);
            } else if (c == ';') {
                append(statements, sql);
            } else {
                sql.append(c);
            }
        }
        if (quote != 0 || blockComment) {
            throw new IllegalArgumentException("初始化脚本存在未闭合的引号或块注释");
        }
        append(statements, sql);
        return List.copyOf(statements);
    }

    private static void append(List<String> statements, StringBuilder sql) {
        String statement = sql.toString().trim();
        sql.setLength(0);
        if (statement.matches("(?is)^DELIMITER\\b.*")) {
            throw new IllegalArgumentException("初始化脚本不支持 DELIMITER 指令");
        }
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }
}
