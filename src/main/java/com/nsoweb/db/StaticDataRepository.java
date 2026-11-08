package com.nsoweb.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nsoweb.config.AppConfig;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Nạp toàn bộ dữ liệu tĩnh từ PostgreSQL vào RAM 1 lần khi khởi động server.
 */
public class StaticDataRepository {
    private final Gson gson = new Gson();

    public StaticData loadAll(DbManager db, AppConfig config) throws SQLException, IOException {
        StaticData sd = new StaticData();
        try (Connection conn = db.getConnection()) {
            loadClazz(conn, sd);
            loadItems(conn, sd);
            loadItemOptions(conn, sd);
            loadNpcs(conn, sd);
            loadMonsters(conn, sd);
            loadSkills(conn, sd);
            loadStores(conn, sd);
            loadTasks(conn, sd);
            loadOthers(conn, sd);
            loadClientSpriteTables(conn, sd);
            loadMaps(conn, sd, config);
        }
        return sd;
    }

    private void loadClazz(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM clazz ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ClazzInfo c = new ClazzInfo();
                c.id = rs.getInt("id");
                c.name = rs.getString("name");
                sd.clazzMap.put(c.id, c);
            }
        }
    }

    private void loadItems(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, type, gender, description, level, icon, part, fashion, is_up_to_up FROM item ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ItemTemplate it = new ItemTemplate();
                it.id = rs.getInt("id");
                it.name = rs.getString("name");
                it.type = rs.getInt("type");
                it.gender = rs.getInt("gender");
                it.description = rs.getString("description");
                it.level = rs.getInt("level");
                it.icon = rs.getInt("icon");
                it.part = rs.getInt("part");
                it.fashion = rs.getInt("fashion");
                it.isUpToUp = rs.getBoolean("is_up_to_up");
                it.maxUpgrade = com.nsoweb.model.ItemRules.maxUpgradeForLevel(it.level);
                it.resaleYen = com.nsoweb.model.ItemRules.resaleYen(it);
                sd.items.put(it.id, it);
            }
        }
    }

    private void loadItemOptions(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, type, name FROM item_option ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ItemOptionTemplate o = new ItemOptionTemplate();
                o.id = rs.getInt("id");
                o.type = rs.getInt("type");
                o.name = rs.getString("name");
                sd.itemOptions.put(o.id, o);
            }
        }
    }

    private void loadNpcs(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, head, body, leg, menu FROM npc ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                NpcTemplate n = new NpcTemplate();
                n.id = rs.getInt("id");
                n.name = rs.getString("name");
                n.head = rs.getInt("head");
                n.body = rs.getInt("body");
                n.leg = rs.getInt("leg");
                n.menu = gson.fromJson(rs.getString("menu"), String[][].class);
                sd.npcs.put(n.id, n);
            }
        }
    }

    private void loadMonsters(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, level, boss, type, hp, range_move, speed, type_fly, n_img, "
                + "move, attack, sprites, frames, sequence, index_splash FROM monster ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                MonsterTemplate m = new MonsterTemplate();
                m.id = rs.getInt("id");
                m.name = rs.getString("name");
                m.level = rs.getInt("level");
                m.boss = rs.getBoolean("boss");
                m.type = rs.getInt("type");
                m.hp = rs.getInt("hp");
                m.rangeMove = rs.getInt("range_move");
                m.speed = rs.getInt("speed");
                m.typeFly = rs.getInt("type_fly");
                m.nImg = rs.getInt("n_img");
                m.moveJson = rs.getString("move");
                m.attackJson = rs.getString("attack");
                m.spritesJson = rs.getString("sprites");
                m.framesJson = rs.getString("frames");
                m.sequenceJson = rs.getString("sequence");
                m.indexSplashJson = rs.getString("index_splash");
                sd.monsters.put(m.id, m);
            }
        }
    }

    private void loadSkills(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM skill_option ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sd.skillOptions.put(rs.getInt("id"), rs.getString("name"));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, class_id, name, max_point, type, icon, description FROM skill_template ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                SkillTemplate st = new SkillTemplate();
                st.id = rs.getInt("id");
                st.classId = rs.getInt("class_id");
                st.name = rs.getString("name");
                st.maxPoint = rs.getInt("max_point");
                st.type = rs.getInt("type");
                st.icon = rs.getInt("icon");
                st.description = rs.getString("description");
                sd.skillTemplates.put(st.id, st);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, template_id, max_fight, level, mana_use, cooldown, point, dx, dy, options "
                + "FROM skill ORDER BY template_id, point");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                SkillLevelData sl = new SkillLevelData();
                sl.id = rs.getInt("id");
                sl.templateId = rs.getInt("template_id");
                sl.maxFight = rs.getInt("max_fight");
                sl.level = rs.getInt("level");
                sl.manaUse = rs.getInt("mana_use");
                sl.cooldown = rs.getInt("cooldown");
                sl.point = rs.getInt("point");
                sl.dx = rs.getInt("dx");
                sl.dy = rs.getInt("dy");
                JsonArray arr = JsonParser.parseString(rs.getString("options")).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    SkillOption opt = new SkillOption();
                    opt.id = o.get("id").getAsInt();
                    opt.param = o.get("param").getAsInt();
                    sl.options.add(opt);
                }
                SkillTemplate parent = sd.skillTemplates.get(sl.templateId);
                if (parent != null) {
                    parent.levels.add(sl);
                }
            }
        }
    }

    private void loadStores(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM stores ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sd.stores.put(rs.getInt("id"), rs.getString("name"));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, item_id, sys, store, is_locked, coin, gold, yen, expire, options FROM store_data ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                StoreDataItem it = new StoreDataItem();
                it.id = rs.getInt("id");
                it.itemId = rs.getInt("item_id");
                it.sys = rs.getInt("sys");
                it.store = rs.getInt("store");
                it.locked = rs.getBoolean("is_locked");
                it.coin = rs.getInt("coin");
                it.gold = rs.getInt("gold");
                it.yen = rs.getInt("yen");
                it.expire = rs.getLong("expire");
                it.options.addAll(parseOptionList(rs.getString("options")));
                sd.storeDataByStore.computeIfAbsent(it.store, k -> new ArrayList<>()).add(it);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, template_id, sys, xu, luong, yen, expire, options FROM weapon_store ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                StoreDataItem it = new StoreDataItem();
                it.id = rs.getInt("id");
                it.itemId = rs.getInt("template_id");
                it.sys = rs.getInt("sys");
                it.store = 2;
                it.locked = false;
                it.coin = rs.getInt("xu");
                it.gold = rs.getInt("luong");
                it.yen = rs.getInt("yen");
                it.expire = rs.getLong("expire");
                it.options.addAll(parseOptionList(rs.getString("options")));
                sd.weaponStore.add(it);
            }
        }
    }

    private List<int[]> parseOptionList(String jsonStr) {
        List<int[]> list = new ArrayList<>();
        if (jsonStr == null || jsonStr.isBlank()) {
            return list;
        }
        JsonArray arr = JsonParser.parseString(jsonStr).getAsJsonArray();
        for (JsonElement el : arr) {
            if (el.isJsonArray()) {
                JsonArray pair = el.getAsJsonArray();
                list.add(new int[]{pair.get(0).getAsInt(), pair.get(1).getAsInt()});
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                list.add(new int[]{o.get("id").getAsInt(), o.get("param").getAsInt()});
            }
        }
        return list;
    }

    private void loadTasks(Connection conn, StaticData sd) throws SQLException {
        Map<Integer, int[]> taskNpcs = new HashMap<>();
        Map<Integer, int[]> taskMaps = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, npcs, maps FROM task ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                taskNpcs.put(id, gson.fromJson(rs.getString("npcs"), int[].class));
                taskMaps.put(id, gson.fromJson(rs.getString("maps"), int[].class));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT task_id, name, detail, subnames, counts, level_require, kill_mob, pick_item FROM task_template ORDER BY task_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TaskTemplate t = new TaskTemplate();
                t.taskId = rs.getInt("task_id");
                t.name = rs.getString("name");
                t.detail = rs.getString("detail");
                t.subnames = gson.fromJson(rs.getString("subnames"), String[].class);
                t.counts = gson.fromJson(rs.getString("counts"), int[].class);
                t.levelRequire = rs.getInt("level_require");
                t.killMob = gson.fromJson(rs.getString("kill_mob"), int[][].class);
                t.pickItem = gson.fromJson(rs.getString("pick_item"), int[].class);
                t.npcs = taskNpcs.getOrDefault(t.taskId, new int[0]);
                t.maps = taskMaps.getOrDefault(t.taskId, new int[0]);
                sd.tasks.put(t.taskId, t);
            }
        }
    }

    private void loadOthers(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, val FROM others ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("name");
                String val = rs.getString("val");
                sd.others.put(name, val);
                if ("exp".equals(name)) {
                    sd.exps = gson.fromJson(val, long[].class);
                }
            }
        }
    }

    private void loadClientSpriteTables(Connection conn, StaticData sd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, type, part FROM nj_part ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("type", rs.getInt("type"));
                m.put("part", JsonParser.parseString(rs.getString("part")));
                sd.njPartsRaw.add(m);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, skill_id, eff_id, num_eff, skill_stand, skill_fly FROM nj_skill ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("skillId", rs.getInt("skill_id"));
                m.put("effId", rs.getInt("eff_id"));
                m.put("numEff", rs.getInt("num_eff"));
                m.put("skillStand", JsonParser.parseString(rs.getString("skill_stand")));
                m.put("skillFly", JsonParser.parseString(rs.getString("skill_fly")));
                sd.njSkillsRaw.add(m);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, img_id FROM nj_arrow ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("imgId", JsonParser.parseString(rs.getString("img_id")));
                sd.njArrowsRaw.add(m);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, info FROM nj_effect ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("info", JsonParser.parseString(rs.getString("info")));
                sd.njEffectsRaw.add(m);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, type, icon FROM effect ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                m.put("type", rs.getInt("type"));
                m.put("icon", rs.getInt("icon"));
                sd.effectsRaw.add(m);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, sprites, frames, running, frame_char FROM effect_data ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("sprites", JsonParser.parseString(rs.getString("sprites")));
                m.put("frames", JsonParser.parseString(rs.getString("frames")));
                m.put("running", JsonParser.parseString(rs.getString("running")));
                sd.effectDataRaw.add(m);
            }
        }
    }

    private void loadMaps(Connection conn, StaticData sd, AppConfig config) throws SQLException, IOException {
        Path mapsDir = config.dataDir.resolve("assets").resolve("maps");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, npc, waypoint, monster, zone_number, tile_id, bg_id, map_type FROM map ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TileMapData tm = new TileMapData();
                tm.id = rs.getInt("id");
                tm.name = rs.getString("name");
                tm.zoneNumber = Math.max(1, rs.getInt("zone_number"));
                tm.tileId = rs.getInt("tile_id");
                tm.bgId = rs.getInt("bg_id");
                tm.mapType = rs.getInt("map_type");

                JsonArray npcArr = JsonParser.parseString(rs.getString("npc")).getAsJsonArray();
                for (JsonElement el : npcArr) {
                    JsonObject o = el.getAsJsonObject();
                    MapNpcSpawn n = new MapNpcSpawn();
                    n.templateId = o.get("templateId").getAsInt();
                    n.x = o.get("x").getAsInt();
                    n.y = o.get("y").getAsInt();
                    n.status = o.get("status").getAsInt();
                    tm.npcs.add(n);
                }

                JsonArray wpArr = JsonParser.parseString(rs.getString("waypoint")).getAsJsonArray();
                for (JsonElement el : wpArr) {
                    JsonObject o = el.getAsJsonObject();
                    Waypoint w = new Waypoint();
                    w.next = o.get("next").getAsInt();
                    w.minX = o.get("minX").getAsInt();
                    w.minY = o.get("minY").getAsInt();
                    w.maxX = o.get("maxX").getAsInt();
                    w.maxY = o.get("maxY").getAsInt();
                    w.x = o.get("x").getAsInt();
                    w.y = o.get("y").getAsInt();
                    tm.waypoints.add(w);
                }

                JsonArray mobArr = JsonParser.parseString(rs.getString("monster")).getAsJsonArray();
                for (JsonElement el : mobArr) {
                    JsonObject o = el.getAsJsonObject();
                    MobSpawnPoint ms = new MobSpawnPoint();
                    ms.templateId = o.get("templateId").getAsInt();
                    ms.x = o.get("x").getAsInt();
                    ms.y = o.get("y").getAsInt();
                    ms.boss = o.has("boss") && o.get("boss").getAsBoolean();
                    tm.mobs.add(ms);
                }

                Path mapJsonPath = mapsDir.resolve(tm.id + ".json");
                if (!Files.exists(mapJsonPath)) {
                    throw new IOException("Missing extracted map json: " + mapJsonPath);
                }
                JsonObject mObj = JsonParser.parseString(Files.readString(mapJsonPath, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                tm.tmw = mObj.get("tmw").getAsInt();
                tm.tmh = mObj.get("tmh").getAsInt();
                tm.pxw = tm.tmw * 24;
                tm.pxh = tm.tmh * 24;
                tm.tiles = gson.fromJson(mObj.get("tiles"), int[].class);
                tm.types = gson.fromJson(mObj.get("types"), int[].class);

                sd.tileMaps.put(tm.id, tm);
            }
        }
    }
}
