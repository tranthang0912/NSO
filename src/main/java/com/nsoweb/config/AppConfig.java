package com.nsoweb.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AppConfig {
    private static final Map<String, String> LOCAL_ENV = loadLocalEnv();
    public final int port;
    public final String databaseUrl;
    public final int dbPoolMax;
    public final boolean autoInitDb;
    public final Path dataDir;
    public final Path sqlDir;
    public final Path webDir;
    public final int autoSaveIntervalSec;

    private AppConfig(
            int port,
            String databaseUrl,
            int dbPoolMax,
            boolean autoInitDb,
            Path dataDir,
            Path sqlDir,
            Path webDir,
            int autoSaveIntervalSec) {
        this.port = port;
        this.databaseUrl = databaseUrl;
        this.dbPoolMax = dbPoolMax;
        this.autoInitDb = autoInitDb;
        this.dataDir = dataDir;
        this.sqlDir = sqlDir;
        this.webDir = webDir;
        this.autoSaveIntervalSec = autoSaveIntervalSec;
    }

    public static AppConfig fromEnv() {
        int port = parseInt(envOrDefault("PORT", "8080"), 8080);
        String dbUrl = envOrDefault("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/nso_web?sslmode=disable");
        int poolMax = Math.max(1, Math.min(5, parseInt(envOrDefault("DB_POOL_MAX", "5"), 5)));
        boolean autoInit = Boolean.parseBoolean(envOrDefault("AUTO_INIT_DB", "true"));
        Path dataDir = Paths.get(envOrDefault("NSO_DATA_DIR", "data")).toAbsolutePath().normalize();
        Path sqlDir = Paths.get(envOrDefault("NSO_SQL_DIR", "sql")).toAbsolutePath().normalize();
        Path webDir = Paths.get(envOrDefault("NSO_WEB_DIR", "web")).toAbsolutePath().normalize();
        int autoSaveSec = Math.max(30, Math.min(60, parseInt(envOrDefault("AUTO_SAVE_INTERVAL_SEC", "45"), 45)));
        return new AppConfig(port, dbUrl, poolMax, autoInit, dataDir, sqlDir, webDir, autoSaveSec);
    }

    public static AppConfig forTest(String databaseUrl, Path dataDir, Path sqlDir) {
        return new AppConfig(0, databaseUrl, 3, true, dataDir, sqlDir, Paths.get("web").toAbsolutePath(), 45);
    }

    public static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null) v = LOCAL_ENV.get(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    private static Map<String, String> loadLocalEnv() {
        Map<String, String> values = new HashMap<>();
        Path file = Paths.get(".env");
        if (!Files.isRegularFile(file)) return values;
        try {
            for (String line : Files.readAllLines(file)) {
                String clean = line.trim();
                if (clean.isEmpty() || clean.startsWith("#")) continue;
                int eq = clean.indexOf('=');
                if (eq <= 0) continue;
                String value = clean.substring(eq + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(clean.substring(0, eq).trim(), value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read local .env configuration", e);
        }
        return values;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
