package com.nsoweb.db;

import com.nsoweb.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

public class DbManager implements AutoCloseable {
    private final HikariDataSource dataSource;

    public DbManager(AppConfig config) {
        HikariConfig hc = new HikariConfig();
        String rawUrl = config.databaseUrl.trim();
        hc.setMaximumPoolSize(config.dbPoolMax);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(15000);
        hc.setIdleTimeout(120000);
        hc.setMaxLifetime(600000);
        hc.setPoolName("nso-web-pg");

        if (rawUrl.startsWith("jdbc:h2:")) {
            hc.setJdbcUrl(rawUrl);
            hc.setDriverClassName("org.h2.Driver");
        } else if (rawUrl.startsWith("jdbc:postgresql:")) {
            hc.setJdbcUrl(rawUrl);
            hc.setDriverClassName("org.postgresql.Driver");
        } else if (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://")) {
            configurePostgresUri(hc, rawUrl);
        } else {
            throw new IllegalArgumentException("Unsupported DATABASE_URL scheme");
        }

        this.dataSource = new HikariDataSource(hc);
    }

    private static void configurePostgresUri(HikariConfig hc, String rawUri) {
        String normalized = rawUri.startsWith("postgresql://")
                ? "postgres://" + rawUri.substring("postgresql://".length())
                : rawUri;
        URI uri = URI.create(normalized);
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("DATABASE_URL must contain a valid hostname");
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/defaultdb";
        }

        Properties dsProps = new Properties();
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(port).append(path);

        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            jdbcUrl.append('?').append(query);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = decode(pair.substring(0, eq));
                    String v = decode(pair.substring(eq + 1));
                    if ("sslmode".equalsIgnoreCase(k) && !"disable".equalsIgnoreCase(v)) {
                        dsProps.setProperty("ssl", "true");
                    }
                }
            }
        } else {
            // Mặc định bật SSL khi kết nối server từ xa (như Aiven)
            if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
                jdbcUrl.append("?sslmode=require");
                dsProps.setProperty("ssl", "true");
                dsProps.setProperty("sslmode", "require");
            }
        }

        hc.setDriverClassName("org.postgresql.Driver");
        hc.setJdbcUrl(jdbcUrl.toString());

        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                hc.setUsername(decode(userInfo.substring(0, colon)));
                hc.setPassword(decode(userInfo.substring(colon + 1)));
            } else {
                hc.setUsername(decode(userInfo));
            }
        }
        if (!dsProps.isEmpty()) {
            hc.setDataSourceProperties(dsProps);
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void initializeAndSeedIfNeeded(AppConfig config) throws SQLException, IOException {
        Path schemaPath = config.sqlDir.resolve("schema.postgres.sql");
        if (!Files.exists(schemaPath)) {
            throw new IOException("Missing PostgreSQL schema file: " + schemaPath);
        }
        try (Connection conn = getConnection()) {
            SqlScriptRunner.executeScript(conn, Files.readString(schemaPath, StandardCharsets.UTF_8));
            int mapCount = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM map");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    mapCount = rs.getInt(1);
                }
            }
            if (mapCount == 0) {
                Path seedPath = config.dataDir.resolve("seed_static.sql");
                if (!Files.exists(seedPath)) {
                    throw new IOException("Database is empty and missing seed file: " + seedPath);
                }
                SqlScriptRunner.executeScript(conn, Files.readString(seedPath, StandardCharsets.UTF_8));
            }
            // Refuse to start a half-seeded world from an earlier interrupted deployment.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM map), (SELECT COUNT(*) FROM item), (SELECT COUNT(*) FROM clazz)" );
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 177 || rs.getInt(2) != 1085 || rs.getInt(3) != 7) {
                    throw new SQLException("Incomplete NSO reference data; restore the static seed before starting the server");
                }
            }
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
