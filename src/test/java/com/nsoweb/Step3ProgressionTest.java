package com.nsoweb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nsoweb.Step2LiveServerTest.WsClientRecorder;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class Step3ProgressionTest {

    @Test
    void potentialSkillsEquipChestShopAndTasksWorkLive() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        String dbUrl = "jdbc:h2:mem:nso_step3_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
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
                String baseUrl = "http://127.0.0.1:" + server.getPort();
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

                JsonObject u1 = postJson(http, baseUrl + "/api/auth/register",
                        Map.of("username", "rpg_tester", "password", "123456"), null);
                String token = u1.get("token").getAsString();
                long userId = u1.getAsJsonObject("user").get("id").getAsLong();

                JsonObject c1 = postJson(http, baseUrl + "/api/chars/create",
                        Map.of("name", "hung_kiem", "gender", 0, "headId", 11, "classId", 1), token);
                long charId = c1.getAsJsonObject("char").get("id").getAsLong();

                WsClientRecorder rec = new WsClientRecorder();
                WebSocket ws = http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + server.getPort() + "/ws?token=" + token + "&charId=" + charId), rec)
                        .join();
                rec.awaitType("welcome", 4000);
                JsonObject enter = rec.awaitType("map_enter", 4000);
                JsonObject self0 = enter.getAsJsonObject("self");
                int baseMaxHp = self0.get("maxHp").getAsInt();
                int baseDmg = self0.get("damage").getAsInt();
                assertTrue(self0.get("point").getAsInt() >= 10);

                // 1. Nhiệm vụ chính tuyến #0 (Nhiệm vụ chào làng): nói chuyện tuần tự Kiriko(3)->Tabemono(4)->Kenshinto(6)->Okanechan(24)->Kamakura(5)->Umayaki(7)->Tajima(12)
                int[] npcs = {3, 4, 6, 24, 5, 7, 12};
                JsonObject afterTask = null;
                for (int npcId : npcs) {
                    rec.clear();
                    ws.sendText("{\"type\":\"npc_interact\",\"npcId\":" + npcId + "}", true).join();
                    afterTask = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                    JsonObject dlg = rec.awaitType("npc_dialog", 4000);
                    assertTrue(dlg.get("taskAdvanced").getAsBoolean(), "Phải tiến bước NV khi gặp NPC #" + npcId);
                }
                assertEquals(1, afterTask.get("taskId").getAsInt(), "Hoàn thành NV 0 phải sang NV 1");
                assertTrue(afterTask.get("level").getAsInt() >= 2, "Hoàn thành NV 0 phải lên cấp >= 2");

                // 2. Cộng điểm Tiềm năng (Thể lực +5, Sức mạnh +5)
                rec.clear();
                ws.sendText("{\"type\":\"up_potential\",\"index\":2,\"points\":5}", true).join();
                JsonObject st1 = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertTrue(st1.get("maxHp").getAsInt() > baseMaxHp, "Tăng Thể lực phải tăng MaxHP");

                rec.clear();
                ws.sendText("{\"type\":\"up_potential\",\"index\":0,\"points\":5}", true).join();
                JsonObject st2 = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertTrue(st2.get("damage").getAsInt() > baseDmg, "Tăng Sức mạnh phải tăng Tấn công");

                // 3. Cộng điểm Kỹ năng cho Chiêu Hiyoko (templateId=1) & Gán phím tắt
                int prevSpoint = st2.get("spoint").getAsInt();
                rec.clear();
                ws.sendText("{\"type\":\"up_skill\",\"templateId\":1,\"points\":1}", true).join();
                JsonObject st3 = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertEquals(prevSpoint - 1, st3.get("spoint").getAsInt());
                assertEquals(2, st3.getAsJsonObject("skills").get("1").getAsInt());

                rec.clear();
                ws.sendText("{\"type\":\"set_hotkey\",\"slot\":2,\"skillId\":1}", true).join();
                JsonObject st4 = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertEquals(1, st4.getAsJsonArray("hotkeySkills").get(2).getAsInt());

                // 4. Tháo & Mặc lại vũ khí (slot 1) -> kiểm tra ngoại hình & sát thương
                rec.clear();
                ws.sendText("{\"type\":\"unequip_item\",\"slot\":1}", true).join();
                JsonObject appUn = rec.awaitType("player_appearance", 4000).getAsJsonObject("player");
                assertEquals(15, appUn.get("weapon").getAsInt());
                JsonObject stUn = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertTrue(stUn.get("damage").getAsInt() < st2.get("damage").getAsInt());
                int bagIdxOfSword = stUn.getAsJsonArray("bag").size() - 1;

                rec.clear();
                ws.sendText("{\"type\":\"use_item\",\"bagIdx\":" + bagIdxOfSword + "}", true).join();
                JsonObject appEq = rec.awaitType("player_appearance", 4000).getAsJsonObject("player");
                assertEquals(12, appEq.get("weapon").getAsInt());

                // 5. Mở shop Kiriko (store 7 - Dược phẩm khóa Yên) & Mua Bình HP
                rec.clear();
                ws.sendText("{\"type\":\"open_shop\",\"storeId\":7}", true).join();
                JsonObject shop = rec.awaitType("shop_data", 4000);
                JsonArray shopItems = shop.getAsJsonArray("items");
                assertTrue(shopItems.size() > 0);

                rec.clear();
                ws.sendText("{\"type\":\"buy_item\",\"storeId\":7,\"index\":0,\"quantity\":2}", true).join();
                JsonObject afterBuy = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertTrue(afterBuy.get("yen").getAsLong() < afterTask.get("yen").getAsLong());

                // 6. Rương đồ (cất Xu & cất đồ vào rương)
                rec.clear();
                ws.sendText("{\"type\":\"xu_box\",\"delta\":1000}", true).join();
                JsonObject afterBoxXu = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertEquals(1000, afterBoxXu.get("xuInBox").getAsLong());

                rec.clear();
                ws.sendText("{\"type\":\"bag_to_box\",\"bagIdx\":0}", true).join();
                JsonObject afterBoxItem = rec.awaitType("self_state", 4000).getAsJsonObject("self");
                assertEquals(1, afterBoxItem.getAsJsonArray("box").size());

                // 7. Ngắt kết nối & kiểm tra DB lưu đầy đủ
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
                Thread.sleep(120);
                var reloaded = repo.loadPlayer(charId, userId, sd);
                assertEquals(1, reloaded.taskId);
                assertEquals(1000, reloaded.xuInBox);
                assertEquals(1, reloaded.box.size());
            } finally {
                server.stop();
                world.shutdown();
            }
        }
    }

    private static JsonObject postJson(HttpClient http, String url, Object body, String token) throws Exception {
        String json = new com.google.gson.Gson().toJson(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }
}
