package com.nsoweb.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nsoweb.model.ItemInstance;
import com.nsoweb.model.PlayerCalculator;
import com.nsoweb.model.PlayerState;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.StoreDataItem;
import com.nsoweb.util.SecurityUtil;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerRepository {
    private static final int[] MALE_HEADS = {11, 26, 27, 28};
    private static final int[] FEMALE_HEADS = {2, 23, 24, 25};
    private static final Type BAG_TYPE = new TypeToken<List<ItemInstance>>() {}.getType();
    private static final Type EQUIP_TYPE = new TypeToken<ItemInstance[]>() {}.getType();
    private static final Type SKILL_MAP_TYPE = new TypeToken<Map<Integer, Integer>>() {}.getType();

    private final DbManager db;
    private final Gson gson = new Gson();
    private final AtomicLong saveSequence = new AtomicLong();
    private final Map<Long, Long> savedRevisions = new HashMap<>();

    public static final class SaveSnapshot {
        private final PlayerState state;
        private final long revision;
        private SaveSnapshot(PlayerState state, long revision) {
            this.state = state;
            this.revision = revision;
        }
    }

    // Called while the world lock is held. JDBC runs later against this detached copy.
    public SaveSnapshot snapshot(PlayerState p) {
        return new SaveSnapshot(gson.fromJson(gson.toJson(p), PlayerState.class), saveSequence.incrementAndGet());
    }

    public synchronized void saveSnapshot(SaveSnapshot snapshot, boolean online) throws SQLException {
        if (savedRevisions.getOrDefault(snapshot.state.id, 0L) >= snapshot.revision) return;
        persistPlayers(Collections.singletonList(snapshot.state), online, snapshot.revision);
    }

    // Clan writes use the same ordering barrier as full character saves.
    public synchronized void invalidateSnapshots(long playerId) {
        savedRevisions.put(playerId, saveSequence.incrementAndGet());
    }

    public PlayerRepository(DbManager db) {
        this.db = db;
    }

    public DbManager getDb() {
        return db;
    }

    public static class AuthResult {
        public final long userId;
        public final String username;
        public final long luong;

        public AuthResult(long userId, String username, long luong) {
            this.userId = userId;
            this.username = username;
            this.luong = luong;
        }
    }

    public static class CharSummary {
        public long id;
        public String name;
        public int gender;
        public int classId;
        public int headId;
        public int head;
        public int body;
        public int leg;
        public int weapon;
        public int level;
        public int mapId;
    }

    public AuthResult registerUser(String username, String password) throws SQLException {
        String cleanUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!cleanUser.matches("^[a-z0-9_]{3,24}$")) {
            throw new IllegalArgumentException("Tên tài khoản phải từ 3–24 ký tự (a-z, 0-9, _).");
        }
        if (password == null || password.length() < 4 || password.length() > 64) {
            throw new IllegalArgumentException("Mật khẩu phải từ 4–64 ký tự.");
        }
        String hash = SecurityUtil.hashPassword(password);
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement check = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                check.setString(1, cleanUser);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalArgumentException("Tên tài khoản đã tồn tại.");
                    }
                }
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO users (username, password_hash, luong) VALUES (?, ?, 500)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, cleanUser);
                ins.setString(2, hash);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) {
                        return new AuthResult(keys.getLong(1), cleanUser, 500);
                    }
                }
            }
        }
        throw new SQLException("Không thể tạo tài khoản.");
    }

    public AuthResult loginUser(String username, String password) throws SQLException {
        String cleanUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, password_hash, luong FROM users WHERE username = ?")) {
            ps.setString(1, cleanUser);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Tài khoản hoặc mật khẩu không chính xác.");
                }
                String hash = rs.getString("password_hash");
                if (!SecurityUtil.verifyPassword(password, hash)) {
                    throw new IllegalArgumentException("Tài khoản hoặc mật khẩu không chính xác.");
                }
                long userId = rs.getLong("id");
                try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    upd.setLong(1, userId);
                    upd.executeUpdate();
                }
                return new AuthResult(userId, rs.getString("username"), rs.getLong("luong"));
            }
        }
    }

    public List<CharSummary> listCharacters(long userId, StaticData sd) throws SQLException {
        List<CharSummary> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, gender, class_id, head_id, level, map_id, equipped FROM players WHERE user_id = ? ORDER BY id ASC")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CharSummary c = new CharSummary();
                    c.id = rs.getLong("id");
                    c.name = rs.getString("name");
                    c.gender = rs.getInt("gender");
                    c.classId = rs.getInt("class_id");
                    c.headId = rs.getInt("head_id");
                    c.level = rs.getInt("level");
                    c.mapId = rs.getInt("map_id");

                    PlayerState tmp = new PlayerState();
                    tmp.gender = c.gender;
                    tmp.originalHead = c.headId;
                    ItemInstance[] eq = gson.fromJson(rs.getString("equipped"), EQUIP_TYPE);
                    if (eq != null && eq.length == 16) {
                        tmp.equipped = eq;
                    }
                    PlayerCalculator.recalculateFashion(tmp, sd);
                    c.head = tmp.head;
                    c.body = tmp.body;
                    c.leg = tmp.leg;
                    c.weapon = tmp.weapon;
                    list.add(c);
                }
            }
        }
        return list;
    }

    public CharSummary createCharacter(long userId, String name, int gender, int headId, int classId, StaticData sd)
            throws SQLException {
        String cleanName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!cleanName.matches("^[a-z0-9_]{3,16}$")) {
            throw new IllegalArgumentException("Tên nhân vật phải 3–16 ký tự (a-z, 0-9, _).");
        }
        if (gender != 0 && gender != 1) {
            gender = 0;
        }
        int[] allowedHeads = (gender == 0) ? MALE_HEADS : FEMALE_HEADS;
        int validHead = allowedHeads[0];
        for (int h : allowedHeads) {
            if (h == headId) {
                validHead = h;
                break;
            }
        }
        if (classId < 0 || classId > 6) {
            classId = 1;
        }

        try (Connection conn = db.getConnection()) {
            try (PreparedStatement cnt = conn.prepareStatement("SELECT COUNT(*) FROM players WHERE user_id = ?")) {
                cnt.setLong(1, userId);
                try (ResultSet rs = cnt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= 3) {
                        throw new IllegalArgumentException("Mỗi tài khoản tối đa 3 nhân vật.");
                    }
                }
            }
            try (PreparedStatement dup = conn.prepareStatement("SELECT id FROM players WHERE name = ?")) {
                dup.setString(1, cleanName);
                try (ResultSet rs = dup.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalArgumentException("Tên nhân vật đã tồn tại.");
                    }
                }
            }

            PlayerState psInit = buildStarterState(userId, cleanName, gender, validHead, classId, sd);

            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, gender, class_id, head_id, level, exp, hp, mp, point, spoint, "
                    + "potential, map_id, pos_x, pos_y, save_map_id, xu, xu_in_box, yen, equipped, bag, box, skills, hotkey_skills) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setLong(1, userId);
                ins.setString(2, cleanName);
                ins.setInt(3, psInit.gender);
                ins.setInt(4, psInit.classId);
                ins.setInt(5, psInit.originalHead);
                ins.setInt(6, psInit.level);
                ins.setLong(7, psInit.exp);
                ins.setInt(8, psInit.hp);
                ins.setInt(9, psInit.mp);
                ins.setInt(10, psInit.point);
                ins.setInt(11, psInit.spoint);
                ins.setString(12, gson.toJson(psInit.potential));
                ins.setInt(13, psInit.mapId);
                ins.setInt(14, psInit.x);
                ins.setInt(15, psInit.y);
                ins.setInt(16, psInit.saveMapId);
                ins.setLong(17, psInit.xu);
                ins.setLong(18, psInit.xuInBox);
                ins.setLong(19, psInit.yen);
                ins.setString(20, gson.toJson(psInit.equipped));
                ins.setString(21, gson.toJson(psInit.bag));
                ins.setString(22, gson.toJson(psInit.box));
                ins.setString(23, gson.toJson(psInit.skills));
                ins.setString(24, gson.toJson(psInit.hotkeySkills));
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) {
                        psInit.id = keys.getLong(1);
                    }
                }
            }
            CharSummary sum = new CharSummary();
            sum.id = psInit.id;
            sum.name = psInit.name;
            sum.gender = psInit.gender;
            sum.classId = psInit.classId;
            sum.headId = psInit.originalHead;
            sum.head = psInit.head;
            sum.body = psInit.body;
            sum.leg = psInit.leg;
            sum.weapon = psInit.weapon;
            sum.level = psInit.level;
            sum.mapId = psInit.mapId;
            return sum;
        }
    }

    private PlayerState buildStarterState(long userId, String name, int gender, int head, int classId, StaticData sd) {
        PlayerState p = new PlayerState();
        p.userId = userId;
        p.name = name;
        p.gender = gender;
        p.originalHead = head;
        p.classId = classId;
        p.level = 1;
        p.exp = 0;
        p.point = 10;
        p.spoint = 1;
        p.potential = new int[]{15, 5, 5, 5};
        p.mapId = 22; // Làng Tone
        p.saveMapId = 22;
        p.x = 228;
        p.y = 192;
        p.xu = 5000;
        p.yen = 10000;

        // Kỹ năng cơ bản + chiêu nhập môn theo phái
        p.skills.put(0, 0);
        int entrySkill = entrySkillForClass(classId);
        if (entrySkill > 0) {
            p.skills.put(entrySkill, 1);
            p.selectedSkillId = entrySkill;
            p.hotkeySkills[0] = entrySkill;
            p.hotkeySkills[1] = 0;
        } else {
            p.selectedSkillId = 0;
            p.hotkeySkills[0] = 0;
        }

        // Trang bị vũ khí khởi đầu của phái
        int wpItemId = starterWeaponForClass(classId);
        if (wpItemId > 0) {
            ItemInstance wp = new ItemInstance(wpItemId, 1);
            wp.sys = p.getSys();
            wp.locked = true;
            // Lấy option gốc từ weapon_store nếu có
            for (StoreDataItem ws : sd.weaponStore) {
                if (ws.itemId == wpItemId) {
                    for (int[] o : ws.options) {
                        wp.options.add(new int[]{o[0], o[1]});
                    }
                    break;
                }
            }
            if (wp.options.isEmpty()) {
                wp.options.add(new int[]{0, 35});
                wp.options.add(new int[]{1, 35});
            }
            p.equipped[1] = wp;
        }

        // Dược phẩm & thức ăn cơ bản trong hành trang
        p.bag.add(new ItemInstance(13, 15)); // Bình HP cực tiểu
        p.bag.add(new ItemInstance(18, 15)); // Bình MP cực tiểu
        p.bag.add(new ItemInstance(23, 5));  // Cơm nắm
        p.bag.add(new ItemInstance(0, 5));   // Đá cấp 1 để thử đập đồ

        PlayerCalculator.recalculate(p, sd);
        p.hp = p.maxHp;
        p.mp = p.maxMp;
        return p;
    }

    public static int entrySkillForClass(int classId) {
        switch (classId) {
            case 1: return 1;  // Kiếm: Chiêu Hiyoko
            case 2: return 10; // Phi tiêu: Chiêu Otama
            case 3: return 19; // Kunai: Chiêu Yokobatan
            case 4: return 28; // Cung: Chiêu Uzusa
            case 5: return 37; // Đao: Chiêu Enchokuto
            case 6: return 46; // Quạt: Chiêu Ouchia
            default: return 0;
        }
    }

    public static int starterWeaponForClass(int classId) {
        switch (classId) {
            case 1: return 94;  // Bạch Hạc Kiếm
            case 2: return 114; // Phàm Ý Tiêu
            case 3: return 99;  // Hắc Nanh Dao
            case 4: return 109; // Minh Nguyệt Cung
            case 5: return 104; // Trảm Mã Đao
            case 6: return 119; // Phong Ý Quạt
            default: return 194; // Kiếm gỗ
        }
    }

    public PlayerState loadPlayer(long playerId, long expectedUserId, StaticData sd) throws SQLException {
        String sql = "SELECT p.*, u.luong, c.name AS clan_name FROM players p "
                + "JOIN users u ON u.id = p.user_id "
                + "LEFT JOIN clans c ON c.id = p.clan_id "
                + "WHERE p.id = ? AND p.user_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, expectedUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                PlayerState p = new PlayerState();
                p.id = rs.getLong("id");
                p.userId = rs.getLong("user_id");
                p.name = rs.getString("name");
                p.gender = rs.getInt("gender");
                p.classId = rs.getInt("class_id");
                p.originalHead = rs.getInt("head_id");
                p.level = rs.getInt("level");
                p.exp = rs.getLong("exp");
                p.hp = rs.getInt("hp");
                p.mp = rs.getInt("mp");
                p.point = rs.getInt("point");
                p.spoint = rs.getInt("spoint");
                int[] pot = gson.fromJson(rs.getString("potential"), int[].class);
                if (pot != null && pot.length == 4) {
                    p.potential = pot;
                }
                p.mapId = rs.getInt("map_id");
                p.x = rs.getInt("pos_x");
                p.y = rs.getInt("pos_y");
                p.saveMapId = rs.getInt("save_map_id");
                p.xu = rs.getLong("xu");
                p.xuInBox = rs.getLong("xu_in_box");
                p.yen = rs.getLong("yen");
                p.luong = rs.getLong("luong");
                p.bagCapacity = rs.getInt("bag_capacity");
                p.boxCapacity = rs.getInt("box_capacity");

                ItemInstance[] eq = gson.fromJson(rs.getString("equipped"), EQUIP_TYPE);
                if (eq != null && eq.length == 16) {
                    p.equipped = eq;
                }
                List<ItemInstance> bagList = gson.fromJson(rs.getString("bag"), BAG_TYPE);
                if (bagList != null) {
                    p.bag = bagList;
                }
                List<ItemInstance> boxList = gson.fromJson(rs.getString("box"), BAG_TYPE);
                if (boxList != null) {
                    p.box = boxList;
                }
                Map<Integer, Integer> sk = gson.fromJson(rs.getString("skills"), SKILL_MAP_TYPE);
                if (sk != null) {
                    p.skills = sk;
                }
                int[] hk = gson.fromJson(rs.getString("hotkey_skills"), int[].class);
                if (hk != null && hk.length == 5) {
                    p.hotkeySkills = hk;
                }
                p.selectedSkillId = (p.hotkeySkills[0] >= 0) ? p.hotkeySkills[0] : entrySkillForClass(p.classId);

                p.taskId = rs.getInt("task_id");
                p.taskIndex = rs.getInt("task_index");
                p.taskCount = rs.getInt("task_count");
                long cid = rs.getLong("clan_id");
                p.clanId = rs.wasNull() ? null : cid;
                p.clanName = rs.getString("clan_name");
                p.pkMode = rs.getInt("pk_mode");
                PlayerState.AutoSettings as = gson.fromJson(rs.getString("auto_settings"), PlayerState.AutoSettings.class);
                if (as != null) {
                    p.autoSettings = as;
                }

                PlayerCalculator.recalculate(p, sd);
                if (p.hp <= 0) {
                    p.hp = p.maxHp;
                }
                if (p.mp <= 0) {
                    p.mp = p.maxMp;
                }
                return p;
            }
        }
    }

    public void savePlayer(PlayerState p, boolean online) throws SQLException {
        savePlayersAtomically(Collections.singletonList(p), online);
    }

    public synchronized void savePlayersAtomically(List<PlayerState> players, boolean online) throws SQLException {
        persistPlayers(players, online, saveSequence.incrementAndGet());
    }

    private void persistPlayers(List<PlayerState> players, boolean online, long revision) throws SQLException {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (PlayerState player : players) writePlayer(conn, player, online);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
        for (PlayerState player : players) {
            savedRevisions.put(player.id, revision);
            player.dirty = false;
            player.lastSaveAt = System.currentTimeMillis();
        }
    }

    protected void writePlayer(Connection conn, PlayerState p, boolean online) throws SQLException {
        String sql = "UPDATE players SET class_id = ?, head_id = ?, level = ?, exp = ?, hp = ?, mp = ?, "
                + "point = ?, spoint = ?, potential = ?, map_id = ?, pos_x = ?, pos_y = ?, save_map_id = ?, "
                + "xu = ?, xu_in_box = ?, yen = ?, bag_capacity = ?, box_capacity = ?, "
                + "equipped = ?, bag = ?, box = ?, skills = ?, hotkey_skills = ?, "
                + "task_id = ?, task_index = ?, task_count = ?, clan_id = ?, pk_mode = ?, "
                + "auto_settings = ?, online = ?, last_save_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, p.classId);
            ps.setInt(2, p.originalHead);
            ps.setInt(3, p.level);
            ps.setLong(4, p.exp);
            ps.setInt(5, p.hp);
            ps.setInt(6, p.mp);
            ps.setInt(7, p.point);
            ps.setInt(8, p.spoint);
            ps.setString(9, gson.toJson(p.potential));
            ps.setInt(10, p.mapId);
            ps.setInt(11, p.x);
            ps.setInt(12, p.y);
            ps.setInt(13, p.saveMapId);
            ps.setLong(14, p.xu);
            ps.setLong(15, p.xuInBox);
            ps.setLong(16, p.yen);
            ps.setInt(17, p.bagCapacity);
            ps.setInt(18, p.boxCapacity);
            ps.setString(19, gson.toJson(p.equipped));
            ps.setString(20, gson.toJson(p.bag));
            ps.setString(21, gson.toJson(p.box));
            ps.setString(22, gson.toJson(p.skills));
            ps.setString(23, gson.toJson(p.hotkeySkills));
            ps.setInt(24, p.taskId);
            ps.setInt(25, p.taskIndex);
            ps.setInt(26, p.taskCount);
            if (p.clanId == null) {
                ps.setNull(27, Types.BIGINT);
            } else {
                ps.setLong(27, p.clanId);
            }
            ps.setInt(28, p.pkMode);
            ps.setString(29, gson.toJson(p.autoSettings));
            ps.setBoolean(30, online);
            ps.setLong(31, p.id);
            if (ps.executeUpdate() != 1) throw new SQLException("Player no longer exists");
            try (PreparedStatement ups = conn.prepareStatement("UPDATE users SET luong = ? WHERE id = ?")) {
                ups.setLong(1, p.luong);
                ups.setLong(2, p.userId);
                if (ups.executeUpdate() != 1) throw new SQLException("Account no longer exists");
            }
        }
    }
}
