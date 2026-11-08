package com.nsoweb.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Thực thi script SQL chuẩn PostgreSQL (tách câu lệnh an toàn bên ngoài chuỗi nháy đơn và comment).
 */
public final class SqlScriptRunner {

    private SqlScriptRunner() {
    }

    public static void executeScript(Connection conn, String sql) throws SQLException {
        List<String> statements = splitStatements(sql);
        boolean origAutoCommit = conn.getAutoCommit();
        // Own the transaction only when the caller has not opened one.
        if (origAutoCommit) conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String upper = trimmed.toUpperCase();
                if ("BEGIN".equals(upper) || "COMMIT".equals(upper)) {
                    continue;
                }
                st.execute(trimmed);
            }
            if (origAutoCommit) conn.commit();
        } catch (SQLException | RuntimeException e) {
            if (origAutoCommit) conn.rollback();
            throw e;
        } finally {
            if (origAutoCommit) conn.setAutoCommit(true);
        }
    }

    public static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int n = sql.length();
        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < n) ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                current.append(c);
                if (c == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                } else if (c == '\'') {
                    inString = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == ';') {
                String s = current.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }
}
