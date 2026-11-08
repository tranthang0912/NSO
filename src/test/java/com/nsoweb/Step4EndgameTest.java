package com.nsoweb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nsoweb.Step2LiveServerTest.WsClientRecorder;
import com.nsoweb.config.AppConfig;
import com.nsoweb.db.DbManager;
import com.nsoweb.db.PlayerRepository;
import com.nsoweb.model.ItemInstance;
import com.nsoweb.model.StaticData;
import com.nsoweb.db.StaticDataRepository;
import com.nsoweb.net.WebServer;
import com.nsoweb.world.GameZone;
import com.nsoweb.world.MobInstance;
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

public class Step4EndgameTest {

    @Test
    void upgradeTradeArenaBossCaveClanAndAutoPersistWorkLive() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        String dbUrl = "jdbc:h2:mem:nso_step4_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
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
                        Map.of("username", "boss_alpha", "password", "123456"), null);
                String tok1 = u1.get("token").getAsString();
                long uid1 = u1.getAsJsonObject("user").get("id").getAsLong();

                JsonObject u2 = postJson(http, baseUrl + "/api/auth/register",
                        Map.of("username", "boss_beta", "password", "123456"), null);
                String tok2 = u2.get("token").getAsString();
                long uid2 = u2.getAsJsonObject("user").get("id").getAsLong();

                JsonObject c1 = postJson(http, baseUrl + "/api/chars/create",
                        Map.of("name", "alpha_kiem", "gender", 0, "headId", 11, "classId", 1), tok1);
                long pid1 = c1.getAsJsonObject("char").get("id").getAsLong();

                JsonObject c2 = postJson(http, baseUrl + "/api/chars/create",
                        Map.of("name", "beta_tieu", "gender", 1, "headId", 2, "classId", 2), tok2);
                long pid2 = c2.getAsJsonObject("char").get("id").getAsLong();

                WsClientRecorder rec1 = new WsClientRecorder();
                WebSocket ws1 = http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + server.getPort() + "/ws?token=" + tok1 + "&charId=" + pid1), rec1)
                        .join();
                WsClientRecorder rec2 = new WsClientRecorder();
                WebSocket ws2 = http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + server.getPort() + "/ws?token=" + tok2 + "&charId=" + pid2), rec2)
                        .join();

                rec1.awaitType("map_enter", 4000);
                rec2.awaitType("map_enter", 4000);

                // 1. Luyện đá (4 viên Đá cấp 1 [bagIdx=3, qty=5] -> 1 Đá cấp 2) + Cường hóa (+1 bằng nhiều viên Đá cấp 1)
                rec1.clear();
                ws1.sendText("{\"type\":\"combine_stones\",\"stoneItemId\":0,\"useXu\":false}", true).join();
                JsonObject afterCombine = rec1.awaitType("self_state", 4000).getAsJsonObject("self");
                assertEquals(1, countItemInBag(afterCombine.getAsJsonArray("bag"), 0), "Còn 1 Đá cấp 1");
                assertEquals(1, countItemInBag(afterCombine.getAsJsonArray("bag"), 1), "Phải nhận được 1 Đá cấp 2");

                // Cấp thêm Đá cấp 4 cho alpha để tỉ lệ đập +0 -> +1 đạt max 80% trên vũ khí và thử nâng cấp
                var liveP1 = world.onlinePlayers.get(pid1).getPlayer();
                liveP1.bag.add(new ItemInstance(3, 1)); // Đá cấp 4 (id=3) power=64 >= UP_WEAPON[0]=18 -> 80%
                int stone4Idx = liveP1.bag.size() - 1;
                int dmgBeforeUp = liveP1.damage;
                boolean hitUpgrade = false;
                for (int attempt = 0; attempt < 5 && !hitUpgrade; attempt++) {
                    if (attempt > 0) {
                        liveP1.bag.add(new ItemInstance(3, 1));
                        stone4Idx = liveP1.bag.size() - 1;
                    }
                    rec1.clear();
                    ws1.sendText("{\"type\":\"upgrade_item\",\"targetType\":\"equipped\",\"targetIdx\":1,\"stoneIndices\":[" + stone4Idx + "],\"useInsurance\":false,\"useLuong\":false}", true).join();
                    JsonObject upRes = rec1.awaitType("upgrade_result", 4000);
                    rec1.awaitType("self_state", 4000);
                    if (upRes.get("success").getAsBoolean()) {
                        assertEquals(1, upRes.get("newUpgrade").getAsInt());
                        assertTrue(liveP1.damage > dmgBeforeUp, "Đập đồ thành công phải tăng sát thương");
                        hitUpgrade = true;
                    }
                }
                assertTrue(hitUpgrade, "Phải cường hóa thành công trong tối đa 5 lần ở tỉ lệ 80%");

                // 2. Giao dịch trực tiếp nguyên tử giữa alpha & beta
                // alpha mua 1 Bình HP không khóa ở shop Dược phẩm Xu (storeId=6, index=0) -> cộng dồn vào ô 0 (không khóa)
                rec1.clear();
                ws1.sendText("{\"type\":\"buy_item\",\"storeId\":6,\"index\":0,\"quantity\":1}", true).join();
                JsonObject afterBuyUnlocked = rec1.awaitType("self_state", 4000).getAsJsonObject("self");
                assertEquals(16, countItemInBag(afterBuyUnlocked.getAsJsonArray("bag"), 13));
                int unlockedHpBagIdx = -1;
                JsonArray bagArr = afterBuyUnlocked.getAsJsonArray("bag");
                for (int bi = 0; bi < bagArr.size(); bi++) {
                    JsonObject itObj = bagArr.get(bi).getAsJsonObject();
                    if (itObj.get("itemId").getAsInt() == 13 && !itObj.get("locked").getAsBoolean()) {
                        unlockedHpBagIdx = bi;
                        break;
                    }
                }
                assertTrue(unlockedHpBagIdx >= 0, "Phải tìm thấy Bình HP không khóa để giao dịch");
                long xu1BeforeTrade = afterBuyUnlocked.get("xu").getAsLong();

                rec2.clear();
                ws2.sendText("{\"type\":\"trade_accept\",\"fromId\":" + pid1 + "}", true).join(); // Trước đó chưa invite, test invite flow
                rec2.clear();
                ws1.sendText("{\"type\":\"trade_invite\",\"targetId\":" + pid2 + "}", true).join();
                JsonObject trReq = rec2.awaitType("trade_request", 4000);
                assertEquals(pid1, trReq.get("fromId").getAsLong());

                rec1.clear();
                rec2.clear();
                ws2.sendText("{\"type\":\"trade_accept\",\"fromId\":" + pid1 + "}", true).join();
                rec1.awaitType("trade_open", 4000);
                rec2.awaitType("trade_open", 4000);

                rec1.clear();
                rec2.clear();
                ws1.sendText("{\"type\":\"trade_update\",\"xu\":400,\"bagIndices\":[" + unlockedHpBagIdx + "]}", true).join();
                rec1.awaitType("trade_state", 4000);
                JsonObject trState2 = rec2.awaitType("trade_state", 4000);
                assertEquals(400, trState2.get("partnerXu").getAsLong());
                assertEquals(1, trState2.getAsJsonArray("partnerItems").size());

                rec1.clear();
                rec2.clear();
                ws1.sendText("{\"type\":\"trade_lock\"}", true).join();
                ws2.sendText("{\"type\":\"trade_lock\"}", true).join();
                Thread.sleep(60);

                rec1.clear();
                rec2.clear();
                ws1.sendText("{\"type\":\"trade_confirm\"}", true).join();
                ws2.sendText("{\"type\":\"trade_confirm\"}", true).join();
                JsonObject closed1 = rec1.awaitType("trade_closed", 4000);
                JsonObject closed2 = rec2.awaitType("trade_closed", 4000);
                assertTrue(closed1.get("ok").getAsBoolean());
                assertTrue(closed2.get("ok").getAsBoolean());

                JsonObject st1Tr = rec1.awaitType("self_state", 4000).getAsJsonObject("self");
                JsonObject st2Tr = rec2.awaitType("self_state", 4000).getAsJsonObject("self");
                assertEquals(xu1BeforeTrade - 400, st1Tr.get("xu").getAsLong());
                assertEquals(5400, st2Tr.get("xu").getAsLong());

                // Kiểm tra DB đã lưu giao dịch ngay lập tức
                var reloaded2 = repo.loadPlayer(pid2, uid2, sd);
                assertEquals(5400, reloaded2.xu);

                // 3. Gia tộc (Tạo gia tộc + Mời + Chat gia tộc xuyên bản đồ)
                rec1.clear();
                ws1.sendText("{\"type\":\"create_clan\",\"name\":\"HoaBiNgan\"}", true).join();
                JsonObject clanData1 = rec1.awaitType("clan_data", 4000);
                assertEquals("HoaBiNgan", clanData1.getAsJsonObject("clan").get("name").getAsString());
                long clanId = clanData1.getAsJsonObject("clan").get("id").getAsLong();

                rec2.clear();
                ws1.sendText("{\"type\":\"clan_invite\",\"target\":\"beta_tieu\"}", true).join();
                JsonObject clanInv = rec2.awaitType("clan_invite", 4000);
                assertEquals(clanId, clanInv.get("clanId").getAsLong());

                rec2.clear();
                ws2.sendText("{\"type\":\"clan_accept\",\"clanId\":" + clanId + "}", true).join();
                JsonObject clanData2 = rec2.awaitType("clan_data", 4000);
                assertEquals(2, clanData2.getAsJsonObject("clan").getAsJsonArray("members").size());

                // 4. Lôi đài Solo 1v1 (Map 111) + PvP hit + Kết thúc trận
                rec2.clear();
                ws1.sendText("{\"type\":\"arena_challenge\",\"targetId\":" + pid2 + ",\"betXu\":300}", true).join();
                JsonObject aInv = rec2.awaitType("arena_invite", 4000);
                assertEquals(300, aInv.get("betXu").getAsLong());

                rec1.clear();
                rec2.clear();
                ws2.sendText("{\"type\":\"arena_accept\",\"fromId\":" + pid1 + "}", true).join();
                JsonObject m111 = rec1.awaitType("map_enter", 4000);
                assertEquals(111, m111.get("mapId").getAsInt());
                assertEquals(111, rec2.awaitType("map_enter", 4000).get("mapId").getAsInt());
                JsonObject aStart1 = rec1.awaitType("arena_start", 4000);
                rec2.awaitType("arena_start", 4000);
                assertTrue(aStart1.get("matchId").getAsInt() > 0);

                // Đưa 2 đấu thủ đứng gần nhau trong Lôi đài & đánh 1v1
                var p1Arena = world.onlinePlayers.get(pid1).getPlayer();
                var p2Arena = world.onlinePlayers.get(pid2).getPlayer();
                ws1.sendText("{\"type\":\"move\",\"x\":" + (p2Arena.x - 30) + ",\"y\":" + p2Arena.y + ",\"dir\":1,\"action\":0}", true).join();
                Thread.sleep(40);
                p2Arena.hp = 20; // Đảm bảo 1-2 đòn kết thúc nhanh
                rec1.clear();
                rec2.clear();
                ws1.sendText("{\"type\":\"attack_player\",\"targetId\":" + pid2 + ",\"skillId\":1}", true).join();
                JsonObject pvp = rec1.awaitType("pvp_hit", 4000);
                assertTrue(pvp.get("damage").getAsInt() > 0);
                JsonObject aRes = rec1.awaitType("arena_result", 4000);
                assertEquals(pid1, aRes.get("winnerId").getAsLong());
                assertEquals(2600, aRes.get("prizeXu").getAsLong());
                rec1.awaitType("map_enter", 4000);
                rec2.awaitType("map_enter", 4000);

                // 5. Boss Thế Giới & Hang động (Wave 1 -> Wave 2 Boss -> Quà nhóm)
                rec1.clear();
                ws1.sendText("{\"type\":\"get_events\"}", true).join();
                JsonObject evData = rec1.awaitType("events_data", 4000);
                assertTrue(evData.getAsJsonArray("bosses").size() >= 5);
                assertEquals(4, evData.getAsJsonArray("caves").size());

                rec1.clear();
                ws1.sendText("{\"type\":\"warp_boss\",\"bossIndex\":0}", true).join();
                JsonObject bossMap = rec1.awaitType("map_enter", 4000);
                assertEquals(5, bossMap.get("mapId").getAsInt());
                boolean foundLiveBoss = false;
                for (var el : bossMap.getAsJsonArray("mobs")) {
                    if (el.getAsJsonObject().get("boss").getAsBoolean() && !el.getAsJsonObject().get("dead").getAsBoolean()) {
                        foundLiveBoss = true;
                        break;
                    }
                }
                assertTrue(foundLiveBoss, "Map 5 phải có World Boss đang sống");

                // Vào Hang động Tier 0 (Map 91) và test Chat Gia Tộc liên map (alpha ở 91, beta ở 22)
                rec1.clear();
                ws1.sendText("{\"type\":\"enter_cave\",\"tier\":0}", true).join();
                JsonObject caveEnter = rec1.awaitType("map_enter", 4000);
                assertEquals(91, caveEnter.get("mapId").getAsInt());

                rec2.clear();
                ws1.sendText("{\"type\":\"clan_chat\",\"text\":\"Tap trung san boss ne ae!\"}", true).join();
                JsonObject clanMsgOnBeta = rec2.awaitType("chat_msg", 4000);
                assertEquals("clan", clanMsgOnBeta.get("channel").getAsString());
                assertTrue(clanMsgOnBeta.get("text").getAsString().contains("san boss"));

                // Dọn toàn bộ quái Đợt 1 trong Hang 91 -> gọi Boss Đợt 2 -> hạ Boss Đợt 2 nhận thưởng
                GameZone caveZone = world.getZoneFor(pid1);
                MobInstance lastWave1 = null;
                for (MobInstance m : caveZone.mobs) {
                    if (!m.dead && !m.isCaveBoss) {
                        if (lastWave1 != null) {
                            lastWave1.hp = 0;
                            lastWave1.dead = true;
                        }
                        lastWave1 = m;
                    }
                }
                assertNotNull(lastWave1);
                lastWave1.hp = 1;
                p1Arena.x = lastWave1.x;
                p1Arena.y = lastWave1.y;
                p1Arena.skillCooldowns.clear();
                rec1.clear();
                ws1.sendText("{\"type\":\"attack\",\"skillId\":1,\"targets\":[" + lastWave1.uid + "]}", true).join();
                JsonObject wave2BossPkt = rec1.awaitType("mob_respawn", 4000);
                int caveBossUid = wave2BossPkt.getAsJsonObject("mob").get("uid").getAsInt();
                assertTrue(wave2BossPkt.getAsJsonObject("mob").get("boss").getAsBoolean());

                MobInstance caveBoss = caveZone.findMobByUid(caveBossUid);
                assertNotNull(caveBoss);
                caveBoss.hp = 1;
                p1Arena.x = caveBoss.x;
                p1Arena.y = caveBoss.y;
                p1Arena.skillCooldowns.clear();
                long xuPreCave = p1Arena.xu;
                rec1.clear();
                ws1.sendText("{\"type\":\"attack\",\"skillId\":1,\"targets\":[" + caveBossUid + "]}", true).join();
                JsonObject afterCave = rec1.awaitType("self_state", 4000).getAsJsonObject("self");
                assertTrue(afterCave.get("xu").getAsLong() >= xuPreCave + 5000, "Phải nhận thưởng Xu chinh phục Hang động");

                // 6. Lưu cấu hình Auto & xác minh DB
                ws1.sendText("{\"type\":\"save_auto_settings\",\"hpThreshold\":65,\"mpThreshold\":40,\"pickupAll\":true}", true).join();
                Thread.sleep(80);
                ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
                ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
                Thread.sleep(150);

                var finalP1 = repo.loadPlayer(pid1, uid1, sd);
                assertEquals(clanId, finalP1.clanId);
                assertEquals("HoaBiNgan", finalP1.clanName);
                assertEquals(65, finalP1.autoSettings.hpThreshold);
                assertEquals(40, finalP1.autoSettings.mpThreshold);
            } finally {
                server.stop();
                world.shutdown();
            }
        }
    }

    private static int countItemInBag(JsonArray bag, int itemId) {
        int sum = 0;
        for (var el : bag) {
            JsonObject it = el.getAsJsonObject();
            if (it.get("itemId").getAsInt() == itemId) {
                sum += it.get("quantity").getAsInt();
            }
        }
        return sum;
    }

    private static JsonObject postJson(HttpClient http, String url, Object body, String token) throws Exception {
        String json = new com.google.gson.Gson().toJson(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "HTTP error on " + url + ": " + resp.body());
        return com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
