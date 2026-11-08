package com.nsoweb.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nsoweb.config.AppConfig;
import com.nsoweb.db.PlayerRepository;
import com.nsoweb.db.PlayerRepository.AuthResult;
import com.nsoweb.db.PlayerRepository.CharSummary;
import com.nsoweb.model.PlayerState;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.*;
import com.nsoweb.util.SecurityUtil;
import com.nsoweb.util.SecurityUtil.TokenClaims;
import com.nsoweb.world.WorldManager;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebServer {
    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final Gson GSON = new Gson();

    private final AppConfig config;
    private final StaticData staticData;
    private final PlayerRepository playerRepo;
    private final WorldManager world;

    private final byte[] bootstrapJsonBytes;
    private final byte[] bootstrapGzipBytes;

    private Undertow server;
    private int actualPort;

    public WebServer(AppConfig config, StaticData staticData, PlayerRepository playerRepo, WorldManager world)
            throws IOException {
        this.config = config;
        this.staticData = staticData;
        this.playerRepo = playerRepo;
        this.world = world;

        String bootJson = buildBootstrapJson();
        this.bootstrapJsonBytes = bootJson.getBytes(StandardCharsets.UTF_8);
        this.bootstrapGzipBytes = gzip(this.bootstrapJsonBytes);
        log.info("Cached /api/bootstrap payload: raw={} KB, gzip={} KB",
                bootstrapJsonBytes.length / 1024, bootstrapGzipBytes.length / 1024);
    }

    public void start() {
        HttpHandler wsHandler = Handlers.websocket(this::onWebSocketConnect);
        HttpHandler rootHandler = exchange -> {
            String path = exchange.getRequestPath();
            if ("/ws".equals(path)) {
                wsHandler.handleRequest(exchange);
                return;
            }
            if (exchange.isInIoThread()) {
                exchange.dispatch(() -> handleHttp(exchange));
            } else {
                handleHttp(exchange);
            }
        };

        this.server = Undertow.builder()
                .addHttpListener(config.port, "0.0.0.0")
                .setIoThreads(2)
                .setWorkerThreads(8)
                .setHandler(rootHandler)
                .build();
        this.server.start();
        InetSocketAddress addr = (InetSocketAddress) server.getListenerInfo().get(0).getAddress();
        this.actualPort = addr.getPort();
        log.info("NSO Web Server listening on port {}", actualPort);
    }

    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    public int getPort() {
        return actualPort;
    }

    private void handleHttp(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        try {
            if ("/api/health".equals(path) && Methods.GET.equals(exchange.getRequestMethod())) {
                handleHealth(exchange);
                return;
            }
            if ("/api/bootstrap".equals(path) && Methods.GET.equals(exchange.getRequestMethod())) {
                handleBootstrap(exchange);
                return;
            }
            if ("/api/auth/register".equals(path) && Methods.POST.equals(exchange.getRequestMethod())) {
                handleRegister(exchange);
                return;
            }
            if ("/api/auth/login".equals(path) && Methods.POST.equals(exchange.getRequestMethod())) {
                handleLogin(exchange);
                return;
            }
            if ("/api/chars".equals(path) && Methods.GET.equals(exchange.getRequestMethod())) {
                handleListChars(exchange);
                return;
            }
            if ("/api/chars/create".equals(path) && Methods.POST.equals(exchange.getRequestMethod())) {
                handleCreateChar(exchange);
                return;
            }
            if (path.startsWith("/assets/")) {
                handleStaticFile(exchange, config.dataDir.resolve("assets"), path.substring("/assets/".length()), true);
                return;
            }
            String webRel = "/".equals(path) ? "index.html" : path.substring(1);
            handleStaticFile(exchange, config.webDir, webRel, false);
        } catch (IllegalArgumentException e) {
            sendJsonError(exchange, StatusCodes.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("HTTP error on {} {}: {}", exchange.getRequestMethod(), path, e.getMessage(), e);
            sendJsonError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Lỗi máy chủ nội bộ.");
        }
    }

    private void handleHealth(HttpServerExchange ex) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("maps", world.maps.size());
        body.put("onlinePlayers", world.onlinePlayers.size());
        body.put("ramUsedMb", usedMb);
        body.put("ramMaxMb", maxMb);
        sendJsonOk(ex, body);
    }

    private void handleBootstrap(HttpServerExchange ex) {
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        ex.getResponseHeaders().put(Headers.CACHE_CONTROL, "public, max-age=3600");
        String ae = ex.getRequestHeaders().getFirst(Headers.ACCEPT_ENCODING);
        if (ae != null && ae.toLowerCase(Locale.ROOT).contains("gzip")) {
            ex.getResponseHeaders().put(Headers.CONTENT_ENCODING, "gzip");
            ex.setStatusCode(StatusCodes.OK);
            ex.getResponseSender().send(ByteBuffer.wrap(bootstrapGzipBytes));
        } else {
            ex.setStatusCode(StatusCodes.OK);
            ex.getResponseSender().send(ByteBuffer.wrap(bootstrapJsonBytes));
        }
    }

    private void handleRegister(HttpServerExchange ex) throws Exception {
        JsonObject req = readJsonBody(ex);
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";
        AuthResult auth = playerRepo.registerUser(username, password);
        String token = SecurityUtil.issueToken(auth.userId, auth.username);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("token", token);
        res.put("user", Map.of("id", auth.userId, "username", auth.username, "luong", auth.luong));
        sendJsonOk(ex, res);
    }

    private void handleLogin(HttpServerExchange ex) throws Exception {
        JsonObject req = readJsonBody(ex);
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";
        AuthResult auth = playerRepo.loginUser(username, password);
        String token = SecurityUtil.issueToken(auth.userId, auth.username);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("token", token);
        res.put("user", Map.of("id", auth.userId, "username", auth.username, "luong", auth.luong));
        sendJsonOk(ex, res);
    }

    private void handleListChars(HttpServerExchange ex) throws Exception {
        TokenClaims claims = authenticateBearer(ex);
        if (claims == null) {
            sendJsonError(ex, StatusCodes.UNAUTHORIZED, "Phiên đăng nhập hết hạn.");
            return;
        }
        List<CharSummary> chars = playerRepo.listCharacters(claims.userId, staticData);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("chars", chars);
        sendJsonOk(ex, res);
    }

    private void handleCreateChar(HttpServerExchange ex) throws Exception {
        TokenClaims claims = authenticateBearer(ex);
        if (claims == null) {
            sendJsonError(ex, StatusCodes.UNAUTHORIZED, "Phiên đăng nhập hết hạn.");
            return;
        }
        JsonObject req = readJsonBody(ex);
        String name = req.has("name") ? req.get("name").getAsString() : "";
        int gender = req.has("gender") ? req.get("gender").getAsInt() : 0;
        int headId = req.has("headId") ? req.get("headId").getAsInt() : (gender == 0 ? 11 : 2);
        int classId = req.has("classId") ? req.get("classId").getAsInt() : 1;

        CharSummary created = playerRepo.createCharacter(claims.userId, name, gender, headId, classId, staticData);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("char", created);
        sendJsonOk(ex, res);
    }

    private void handleStaticFile(HttpServerExchange ex, Path root, String relPath, boolean longCache) throws IOException {
        Path normRoot = root.toAbsolutePath().normalize();
        Path target = normRoot.resolve(relPath).normalize();
        if (!target.startsWith(normRoot) || !Files.exists(target) || Files.isDirectory(target)) {
            ex.setStatusCode(StatusCodes.NOT_FOUND);
            ex.getResponseSender().send("Not found");
            return;
        }

        long size = Files.size(target);
        long mtime = Files.getLastModifiedTime(target).toMillis();
        String etag = "\"" + size + "-" + mtime + "\"";
        String ifNoneMatch = ex.getRequestHeaders().getFirst(Headers.IF_NONE_MATCH);
        if (etag.equals(ifNoneMatch)) {
            ex.setStatusCode(StatusCodes.NOT_MODIFIED);
            ex.endExchange();
            return;
        }

        ex.getResponseHeaders().put(Headers.ETAG, etag);
        if (longCache) {
            ex.getResponseHeaders().put(Headers.CACHE_CONTROL, "public, max-age=86400, immutable");
        } else {
            ex.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
        }
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, guessContentType(target));

        byte[] data = Files.readAllBytes(target);
        if (relPath.endsWith(".json")) {
            String ae = ex.getRequestHeaders().getFirst(Headers.ACCEPT_ENCODING);
            if (ae != null && ae.toLowerCase(Locale.ROOT).contains("gzip") && data.length > 512) {
                byte[] gz = gzip(data);
                ex.getResponseHeaders().put(Headers.CONTENT_ENCODING, "gzip");
                ex.setStatusCode(StatusCodes.OK);
                ex.getResponseSender().send(ByteBuffer.wrap(gz));
                return;
            }
        }
        ex.setStatusCode(StatusCodes.OK);
        ex.getResponseSender().send(ByteBuffer.wrap(data));
    }

    private void onWebSocketConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        String queryString = exchange.getQueryString();
        channel.setIdleTimeout(60000);
        channel.getWorker().execute(() -> initializeSession(queryString, channel));
    }

    private void initializeSession(String queryString, WebSocketChannel channel) {
        try {
            Map<String, String> params = parseQueryParams(queryString);
            String token = params.get("token");
            String charIdRaw = params.get("charId");

            TokenClaims claims = SecurityUtil.verifyToken(token);
            if (claims == null || charIdRaw == null) {
                sendWsErrorAndClose(channel, "AUTH_INVALID", "Token hoặc nhân vật không hợp lệ.");
                return;
            }
            long charId = Long.parseLong(charIdRaw);
            ClientSession session = world.openSession(claims.userId, charId,
                    player -> {
                        ClientSession created = new ClientSession(channel, player, world);
                        created.sendJson(Map.of("type", "welcome", "playerId", player.id,
                                "serverTime", System.currentTimeMillis()));
                        return created;
                    });
            channel.getReceiveSetter().set(new AbstractReceiveListener() {
                @Override
                protected long getMaxTextBufferSize() { return 16 * 1024; }

                @Override
                protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage message) {
                    session.receive(message.getData());
                }

                @Override
                protected void onError(WebSocketChannel ch, Throwable error) {
                    session.enqueue(() -> world.leaveWorld(session));
                }
            });
            channel.addCloseTask(ch -> session.enqueue(() -> world.leaveWorld(session)));
            if (!channel.isOpen()) {
                session.enqueue(() -> world.leaveWorld(session));
                return;
            }
            channel.resumeReceives();

        } catch (IllegalArgumentException e) {
            sendWsErrorAndClose(channel, "SESSION_REJECTED", e.getMessage());
        } catch (Exception e) {
            log.warn("WebSocket connect failed: {}", e.getMessage());
            sendWsErrorAndClose(channel, "CONNECT_ERROR", "Không thể kết nối máy chủ.");
        }
    }

    private void sendWsErrorAndClose(WebSocketChannel channel, String code, String msg) {
        try {
            String err = GSON.toJson(Map.of("type", "error", "code", code, "message", msg));
            WebSockets.sendText(err, channel, null);
            WebSockets.sendClose(1008, msg, channel, null);
        } catch (Exception ignored) {
        }
    }

    private TokenClaims authenticateBearer(HttpServerExchange ex) {
        String authHeader = ex.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return SecurityUtil.verifyToken(authHeader.substring("Bearer ".length()).trim());
    }

    private JsonObject readJsonBody(HttpServerExchange ex) throws IOException {
        ex.startBlocking();
        byte[] bytes = ex.getInputStream().readNBytes(64 * 1024);
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private void sendJsonOk(HttpServerExchange ex, Object obj) {
        byte[] bytes = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        ex.setStatusCode(StatusCodes.OK);
        ex.getResponseSender().send(ByteBuffer.wrap(bytes));
    }

    private void sendJsonError(HttpServerExchange ex, int status, String msg) {
        byte[] bytes = GSON.toJson(Map.of("ok", false, "error", msg)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        ex.setStatusCode(status);
        ex.getResponseSender().send(ByteBuffer.wrap(bytes));
    }

    private String buildBootstrapJson() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();

        Path charinfoPath = config.dataDir.resolve("charinfo.json");
        if (Files.exists(charinfoPath)) {
            root.put("charinfo", JsonParser.parseString(Files.readString(charinfoPath, StandardCharsets.UTF_8)));
        }

        // Map metadata nhẹ (không gửi tile arrays để tiết kiệm băng thông, map json tải lazy khi qua cổng)
        List<Map<String, Object>> mapSummaries = new ArrayList<>(staticData.tileMaps.size());
        for (TileMapData tm : staticData.tileMaps.values()) {
            Map<String, Object> ms = new LinkedHashMap<>();
            ms.put("id", tm.id);
            ms.put("name", tm.name);
            ms.put("tileId", tm.tileId);
            ms.put("bgId", tm.bgId);
            ms.put("mapType", tm.mapType);
            ms.put("zoneNumber", Math.max(1, Math.min(2, tm.zoneNumber)));
            mapSummaries.add(ms);
        }
        root.put("maps", mapSummaries);
        root.put("clazz", staticData.clazzMap);
        root.put("items", staticData.items);
        root.put("itemOptions", staticData.itemOptions);
        root.put("skillTemplates", staticData.skillTemplates);
        root.put("skillOptions", staticData.skillOptions);
        root.put("npcs", staticData.npcs);
        root.put("tasks", staticData.tasks);
        root.put("exps", staticData.exps);
        root.put("njParts", staticData.njPartsRaw);
        root.put("njSkills", staticData.njSkillsRaw);
        root.put("njArrows", staticData.njArrowsRaw);
        root.put("njEffects", staticData.njEffectsRaw);
        root.put("effectData", staticData.effectDataRaw);
        root.put("effects", staticData.effectsRaw);

        // Chỉ gửi các field monster mà client cần để render (tránh gửi chuỗi JSON lồng nhau dư thừa)
        Map<Integer, Object> monstersDto = new LinkedHashMap<>();
        for (MonsterTemplate m : staticData.monsters.values()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.id);
            mm.put("name", m.name);
            mm.put("level", m.level);
            mm.put("boss", m.boss);
            mm.put("type", m.type);
            mm.put("nImg", m.nImg);
            if (m.spritesJson != null && !"[]".equals(m.spritesJson)) {
                mm.put("sprites", JsonParser.parseString(m.spritesJson));
                mm.put("frames", JsonParser.parseString(m.framesJson));
                mm.put("move", JsonParser.parseString(m.moveJson));
                mm.put("attack", JsonParser.parseString(m.attackJson));
            }
            monstersDto.put(m.id, mm);
        }
        root.put("monsters", monstersDto);

        // Danh sách ảnh nền có thật để client không tải file không tồn tại (zero 404)
        Set<String> bgFiles = new TreeSet<>();
        Path bgDir = config.dataDir.resolve("assets").resolve("img").resolve("map").resolve("bg");
        if (Files.isDirectory(bgDir)) {
            try (var stream = Files.list(bgDir)) {
                stream.filter(Files::isRegularFile).forEach(f -> bgFiles.add(f.getFileName().toString()));
            }
        }
        root.put("bgFiles", bgFiles);

        return GSON.toJson(root);
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static String guessContentType(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json") || name.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.length / 4);
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(raw);
        }
        return baos.toByteArray();
    }
}
