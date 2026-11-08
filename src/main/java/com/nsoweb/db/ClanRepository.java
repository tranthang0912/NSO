package com.nsoweb.db;

import com.nsoweb.model.PlayerState;
import java.sql.*;
import java.util.*;

public class ClanRepository {
    private final DbManager db;
    private final PlayerRepository playerRepo;

    public ClanRepository(PlayerRepository playerRepo) {
        this.playerRepo = playerRepo;
        this.db = playerRepo.getDb();
    }

    public static class ClanMemberDto {
        public long playerId;
        public String name;
        public int classId;
        public int level;
        public int memberType; // 4=Toc truong, 3=Toc pho, 0=Thanh vien
        public int pointClan;
        public boolean online;
    }

    public static class ClanInfoDto {
        public long id;
        public String name;
        public String leaderName;
        public String assistName;
        public String alert;
        public long coin;
        public int level;
        public int exp;
        public List<ClanMemberDto> members = new ArrayList<>();
    }

    public ClanInfoDto createClan(PlayerState leader, String clanName, long costXu) throws SQLException {
        playerRepo.invalidateSnapshots(leader.id);
        if (costXu < 0 || leader.xu < costXu) throw new IllegalArgumentException("Không đủ Xu tạo gia tộc.");
        String clean = clanName == null ? "" : clanName.trim();
        if (clean.length() < 2 || clean.length() > 24) {
            throw new IllegalArgumentException("Tên gia tộc phải từ 2 đến 24 ký tự.");
        }
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement chk = conn.prepareStatement("SELECT id FROM clans WHERE LOWER(name) = LOWER(?)")) {
                    chk.setString(1, clean);
                    try (ResultSet rs = chk.executeQuery()) {
                        if (rs.next()) {
                            throw new IllegalArgumentException("Tên gia tộc đã tồn tại.");
                        }
                    }
                }
                long clanId;
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO clans (name, leader_name, alert, coin, level, exp) VALUES (?, ?, ?, 0, 1, 0)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ins.setString(1, clean);
                    ins.setString(2, leader.name);
                    ins.setString(3, "Chào mừng gia nhập " + clean + "!");
                    ins.executeUpdate();
                    try (ResultSet keys = ins.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Failed to get generated clan id");
                        }
                        clanId = keys.getLong(1);
                    }
                }
                try (PreparedStatement mem = conn.prepareStatement(
                        "INSERT INTO clan_members (clan_id, player_id, player_name, class_id, level, member_type, point_clan) VALUES (?, ?, ?, ?, ?, 4, 0)")) {
                    mem.setLong(1, clanId);
                    mem.setLong(2, leader.id);
                    mem.setString(3, leader.name);
                    mem.setInt(4, leader.classId);
                    mem.setInt(5, leader.level);
                    mem.executeUpdate();
                }
                try (PreparedStatement up = conn.prepareStatement("UPDATE players SET clan_id = ?, xu = ? WHERE id = ?")) {
                    up.setLong(1, clanId);
                    up.setLong(2, leader.xu - costXu);
                    up.setLong(3, leader.id);
                    up.executeUpdate();
                }
                conn.commit();
                leader.xu -= costXu;
                leader.clanId = clanId;
                leader.clanName = clean;
                return loadClan(clanId, Set.of(leader.id));
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException) {
                    throw (SQLException) e;
                }
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new SQLException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void addMember(long clanId, String clanName, PlayerState member) throws SQLException {
        playerRepo.invalidateSnapshots(member.id);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM clan_members WHERE player_id = ?")) {
                    del.setLong(1, member.id);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO clan_members (clan_id, player_id, player_name, class_id, level, member_type, point_clan) VALUES (?, ?, ?, ?, ?, 0, 0)")) {
                    ins.setLong(1, clanId);
                    ins.setLong(2, member.id);
                    ins.setString(3, member.name);
                    ins.setInt(4, member.classId);
                    ins.setInt(5, member.level);
                    ins.executeUpdate();
                }
                try (PreparedStatement up = conn.prepareStatement("UPDATE players SET clan_id = ? WHERE id = ?")) {
                    up.setLong(1, clanId);
                    up.setLong(2, member.id);
                    up.executeUpdate();
                }
                conn.commit();
                member.clanId = clanId;
                member.clanName = clanName;
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof SQLException ? (SQLException) e : new SQLException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public String leaveClan(long playerId, long clanId) throws SQLException {
        playerRepo.invalidateSnapshots(playerId);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int myType = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT member_type FROM clan_members WHERE clan_id = ? AND player_id = ?")) {
                    ps.setLong(1, clanId);
                    ps.setLong(2, playerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            myType = rs.getInt(1);
                        }
                    }
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM clan_members WHERE player_id = ?")) {
                    del.setLong(1, playerId);
                    del.executeUpdate();
                }
                try (PreparedStatement up = conn.prepareStatement("UPDATE players SET clan_id = NULL WHERE id = ?")) {
                    up.setLong(1, playerId);
                    up.executeUpdate();
                }

                String newLeaderName = null;
                try (PreparedStatement rem = conn.prepareStatement(
                        "SELECT player_id, player_name FROM clan_members WHERE clan_id = ? ORDER BY member_type DESC, level DESC LIMIT 1")) {
                    rem.setLong(1, clanId);
                    try (ResultSet rs = rem.executeQuery()) {
                        if (!rs.next()) {
                            try (PreparedStatement dc = conn.prepareStatement("DELETE FROM clans WHERE id = ?")) {
                                dc.setLong(1, clanId);
                                dc.executeUpdate();
                            }
                        } else if (myType == 4) {
                            long newLeaderId = rs.getLong("player_id");
                            newLeaderName = rs.getString("player_name");
                            try (PreparedStatement ul = conn.prepareStatement(
                                    "UPDATE clan_members SET member_type = 4 WHERE player_id = ?")) {
                                ul.setLong(1, newLeaderId);
                                ul.executeUpdate();
                            }
                            try (PreparedStatement uc = conn.prepareStatement(
                                    "UPDATE clans SET leader_name = ? WHERE id = ?")) {
                                uc.setString(1, newLeaderName);
                                uc.setLong(2, clanId);
                                uc.executeUpdate();
                            }
                        }
                    }
                }
                conn.commit();
                return newLeaderName;
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof SQLException ? (SQLException) e : new SQLException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void removeMember(long playerId) throws SQLException {
        playerRepo.invalidateSnapshots(playerId);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM clan_members WHERE player_id = ?")) {
                del.setLong(1, playerId);
                del.executeUpdate();
            }
            try (PreparedStatement up = conn.prepareStatement("UPDATE players SET clan_id = NULL WHERE id = ?")) {
                up.setLong(1, playerId);
                up.executeUpdate();
            }
            conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public void donateCoin(PlayerState player, long xuAmount) throws SQLException {
        playerRepo.invalidateSnapshots(player.id);
        if (player.clanId == null || xuAmount <= 0 || xuAmount > player.xu || xuAmount > 500_000_000L)
            throw new IllegalArgumentException("Số Xu đóng góp không hợp lệ.");
        long clanId = player.clanId;
        long playerId = player.id;
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE clans SET coin = coin + ?, exp = exp + ? WHERE id = ?")) {
                ps.setLong(1, xuAmount);
                ps.setInt(2, (int) Math.max(1, xuAmount / 100));
                ps.setLong(3, clanId);
                if (ps.executeUpdate() != 1) throw new SQLException("Clan no longer exists");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE clan_members SET point_clan = point_clan + ? WHERE clan_id = ? AND player_id = ?")) {
                ps.setInt(1, (int) Math.max(1, xuAmount / 100));
                ps.setLong(2, clanId);
                ps.setLong(3, playerId);
                if (ps.executeUpdate() != 1) throw new SQLException("Player is not a member of this clan");
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET xu = ? WHERE id = ?")) {
                ps.setLong(1, player.xu - xuAmount);
                ps.setLong(2, player.id);
                if (ps.executeUpdate() != 1) throw new SQLException("Player no longer exists");
            }
            conn.commit();
            player.xu -= xuAmount;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public void updateAlert(long clanId, String alert) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE clans SET alert = ? WHERE id = ?")) {
            ps.setString(1, alert);
            ps.setLong(2, clanId);
            ps.executeUpdate();
        }
    }

    public ClanInfoDto loadClan(long clanId, Set<Long> onlineIds) throws SQLException {
        String sql = "SELECT id, name, leader_name, assist_name, alert, coin, level, exp FROM clans WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ClanInfoDto dto = new ClanInfoDto();
                dto.id = rs.getLong("id");
                dto.name = rs.getString("name");
                dto.leaderName = rs.getString("leader_name");
                dto.assistName = rs.getString("assist_name");
                dto.alert = rs.getString("alert");
                dto.coin = rs.getLong("coin");
                dto.level = rs.getInt("level");
                dto.exp = rs.getInt("exp");

                String mSql = "SELECT player_id, player_name, class_id, level, member_type, point_clan "
                        + "FROM clan_members WHERE clan_id = ? ORDER BY member_type DESC, level DESC";
                try (PreparedStatement mps = conn.prepareStatement(mSql)) {
                    mps.setLong(1, clanId);
                    try (ResultSet mrs = mps.executeQuery()) {
                        while (mrs.next()) {
                            ClanMemberDto m = new ClanMemberDto();
                            m.playerId = mrs.getLong("player_id");
                            m.name = mrs.getString("player_name");
                            m.classId = mrs.getInt("class_id");
                            m.level = mrs.getInt("level");
                            m.memberType = mrs.getInt("member_type");
                            m.pointClan = mrs.getInt("point_clan");
                            m.online = onlineIds != null && onlineIds.contains(m.playerId);
                            dto.members.add(m);
                        }
                    }
                }
                return dto;
            }
        }
    }

    public List<Map<String, Object>> listClans() throws SQLException {
        String sql = "SELECT c.id, c.name, c.leader_name, c.level, COUNT(m.id) AS member_count "
                + "FROM clans c LEFT JOIN clan_members m ON m.clan_id = c.id "
                + "GROUP BY c.id, c.name, c.leader_name, c.level ORDER BY c.level DESC, member_count DESC LIMIT 20";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("name", rs.getString("name"));
                m.put("leaderName", rs.getString("leader_name"));
                m.put("level", rs.getInt("level"));
                m.put("memberCount", rs.getInt("member_count"));
                out.add(m);
            }
        }
        return out;
    }
}
