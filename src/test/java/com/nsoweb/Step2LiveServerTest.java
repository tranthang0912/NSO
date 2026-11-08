package com.nsoweb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nsoweb.config.AppConfig;
import com.nsoweb.db.DbManager;
import com.nsoweb.db.PlayerRepository;
import com.nsoweb.db.StaticDataRepository;
import com.nsoweb.model.StaticData;
import com.nsoweb.net.WebServer;
import com.nsoweb.world.WorldManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class Step2LiveServerTest {

    static class WsClientRecorder implements WebSocket.Listener {
        final BlockingQueue<JsonObject> packets = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String full = partial.toString();
                partial.setLength(0);
                packets.offer(JsonParser.parseString(full).getAsJsonObject());
            }
            webSocket.request(1);
            return null;
        }

        JsonObject awaitType(String expectedType, int timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                long left = Math.max(50, deadline - System.currentTimeMillis());
                JsonObject pkt = packets.poll(left, TimeUnit.MILLISECONDS);
                if (pkt == null) {
                    continue;
                }
                if (expectedType.equals(pkt.get("type").getAsString())) {
                    return pkt;
                }
            }
            fail("Timed out waiting for WS packet type=" + expectedType);
            return null;
        }

        void clear() {
            packets.clear();
        }
    }

    @Test
    void liveHttpAndMultiPlayerWebSocketGameplayWorks() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        String dbUrl = "jdbc:h2:mem:nso_step2_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        AppConfig cfg = AppConfig.forTest(dbUrl, root.resolve("data"), root.resolve("sql"));

        try (DbManager db = new DbManager(cfg)) {
            db.initializeAndSeedIfNeeded(cfg);
            StaticData sd = new StaticDataRepository().loadAll(db, cfg);
            PlayerRepository repo = new PlayerRepository(db);
            WorldManager world = new WorldManager(cfg, sd, repo);
            world.start();
            WebServer server = new WebServer(cfg, sd, repo, world);
            server.start();

            try {
                int port = server.getPort();
                assertTrue(port > 0);
                String baseUrl = "http://127.0.0.1:" + port;
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

                // 1. Kiểm tra Web UI shell, manifest, assets cache & bootstrap
                HttpResponse<String> indexRes = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, indexRes.statusCode());
                assertTrue(indexRes.body().contains("NINJA SCHOOL"));

                HttpResponse<String> mapAssetRes = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/assets/maps/22.json")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, mapAssetRes.statusCode());
                assertTrue(mapAssetRes.headers().firstValue("Cache-Control").orElse("").contains("max-age=86400"));
                assertTrue(mapAssetRes.body().contains("Làng Tone"));

                HttpResponse<byte[]> bootRes = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/api/bootstrap")).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, bootRes.statusCode());

                // 2. Đăng ký 2 tài khoản & tạo 2 nhân vật khác giới/khác phái
                JsonObject u1 = postJson(http, baseUrl + "/api/auth/register", Map.of("username", "ninja_one", "password", "123456"));
                JsonObject u2 = postJson(http, baseUrl + "/api/auth/register", Map.of("username", "ninja_two", "password", "123456"));
                assertTrue(u1.get("ok").getAsBoolean());
                String token1 = u1.get("token").getAsString();
                String token2 = u2.get("token").getAsString();

                JsonObject c1 = postJsonAuth(http, baseUrl + "/api/chars/create", token1,
                        Map.of("name", "khang_kiem", "gender", 0, "headId", 11, "classId", 1));
                JsonObject c2 = postJsonAuth(http, baseUrl + "/api/chars/create", token2,
                        Map.of("name", "linh_tieu", "gender", 1, "headId", 23, "classId", 2));
                long char1Id = c1.getAsJsonObject("char").get("id").getAsLong();
                long char2Id = c2.getAsJsonObject("char").get("id").getAsLong();
                assertEquals(12, c1.getAsJsonObject("char").get("weapon").getAsInt()); // Bạch Hạc Kiếm
                assertEquals(15, c2.getAsJsonObject("char").get("weapon").getAsInt()); // Phàm Ý Tiêu

                // 3. Kết nối WebSocket đồng thời 2 người chơi
                WsClientRecorder rec1 = new WsClientRecorder();
                WsClientRecorder rec2 = new WsClientRecorder();
                WebSocket ws1 = http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?token=" + token1 + "&charId=" + char1Id), rec1)
                        .join();
                rec1.awaitType("welcome", 4000);
                JsonObject enter1 = rec1.awaitType("map_enter", 4000);
                assertEquals(22, enter1.get("mapId").getAsInt());

                WebSocket ws2 = http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?token=" + token2 + "&charId=" + char2Id), rec2)
                        .join();
                rec2.awaitType("welcome", 4000);
                rec2.awaitType("map_enter", 4000);
                JsonObject joinPkt = rec1.awaitType("player_join", 4000);
                assertEquals("linh_tieu", joinPkt.getAsJsonObject("player").get("name").getAsString());

                // 4. Kiểm tra đồng bộ di chuyển & chat map real-time
                ws1.sendText("{\"type\":\"move\",\"x\":320,\"y\":192,\"dir\":1,\"action\":1}", true).join();
                JsonObject moved = rec2.awaitType("player_move", 4000);
                assertEquals(320, moved.get("x").getAsInt());

                ws1.sendText("{\"type\":\"chat\",\"text\":\"Anh em qua Đồi Fumimen nào!\"}", true).join();
                JsonObject chat = rec2.awaitType("chat_msg", 4000);
                assertEquals("khang_kiem", chat.get("playerName").getAsString());

                // 5. Chuyển map qua Waypoint sang Đồi Fumimen (map 21) & đánh quái
                ws1.sendText("{\"type\":\"move\",\"x\":2805,\"y\":24,\"dir\":1,\"action\":0}", true).join();
                Thread.sleep(60);
                ws1.sendText("{\"type\":\"waypoint\"}", true).join();
                JsonObject map21 = rec1.awaitType("map_enter", 4000);
                assertEquals(21, map21.get("mapId").getAsInt());

                JsonArray mobs = map21.getAsJsonArray("mobs");
                assertTrue(mobs.size() > 0, "Đồi Fumimen phải có quái vật");
                JsonObject targetMob = mobs.get(0).getAsJsonObject();
                int mobUid = targetMob.get("uid").getAsInt();
                int mobX = targetMob.get("x").getAsInt();
                int mobY = targetMob.get("y").getAsInt();

                // Di chuyển lại gần quái & tấn công nhiều phát
                ws1.sendText("{\"type\":\"move\",\"x\":" + mobX + ",\"y\":" + mobY + ",\"dir\":1,\"action\":0}", true).join();
                Thread.sleep(40);
                rec1.clear();
                ws1.sendText("{\"type\":\"attack\",\"skillId\":1,\"targets\":[" + mobUid + "]}", true).join();

                JsonObject castPkt = rec1.awaitType("skill_cast", 4000);
                assertEquals(1, castPkt.get("skillId").getAsInt());
                JsonObject dmgPkt = rec1.awaitType("damage_popup", 4000);
                assertTrue(dmgPkt.get("expGain").getAsLong() > 0, "Đánh quái phải nhận EXP");

                // Đánh tiếp cho đến khi có quái chết hoặc nhận cập nhật stats
                JsonObject statsPkt = rec1.awaitType("self_stats", 4000);
                assertTrue(statsPkt.get("exp").getAsLong() > 0, "EXP phải tăng sau khi đánh");

                ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
                ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

                // 6. Xác nhận lưu DB khi thoát & giới hạn RAM < 200 MB
                Thread.sleep(150);
                var reloaded = repo.loadPlayer(char1Id, u1.getAsJsonObject("user").get("id").getAsLong(), sd);
                assertEquals(21, reloaded.mapId);
                assertTrue(reloaded.exp > 0);

                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                assertTrue(usedMb < 220, "RAM used should stay lightweight for Render Free, was " + usedMb + "MB");
            } finally {
                server.stop();
                world.shutdown();
            }
        }
    }

    private static JsonObject postJson(HttpClient http, String url, Object body) throws Exception {
        return postJsonAuth(http, url, null, body);
    }

    private static JsonObject postJsonAuth(HttpClient http, String url, String token, Object body) throws Exception {
        String json = new com.google.gson.Gson().toJson(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }
}
