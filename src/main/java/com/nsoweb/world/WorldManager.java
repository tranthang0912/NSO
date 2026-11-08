package com.nsoweb.world;

import com.nsoweb.config.AppConfig;
import com.nsoweb.db.ClanRepository;
import com.nsoweb.db.PlayerRepository;
import com.nsoweb.model.PlayerState;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.TileMapData;
import com.nsoweb.model.StaticData.Waypoint;
import java.util.*;
import java.util.concurrent.*;
import java.sql.SQLException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldManager {
    private static final Logger log = LoggerFactory.getLogger(WorldManager.class);

    public final AppConfig config;
    public final StaticData staticData;
    public final PlayerRepository playerRepo;
    public final ClanRepository clanRepo;
    public final EndgameOps endgame;
    public final int expRate;

    public final Map<Integer, GameMap> maps = new HashMap<>();
    public final Map<Long, ZoneClient> onlinePlayers = new ConcurrentHashMap<>();
    public final Map<Long, GameZone> playerZones = new ConcurrentHashMap<>();
    private final Map<Long, PlayerState> pendingSaves = new HashMap<>();
    private boolean stopping;
    private final ExecutorService persistence = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nso-save-worker");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "nso-world-loop");
        t.setDaemon(true);
        return t;
    });

    public WorldManager(AppConfig config, StaticData staticData, PlayerRepository playerRepo) {
        this.config = config;
        this.staticData = staticData;
        this.playerRepo = playerRepo;
        int rate = 2;
        String envRate = System.getenv("EXP_RATE");
        if (envRate != null && !envRate.isBlank()) {
            try {
                rate = Math.max(1, Integer.parseInt(envRate.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        this.expRate = rate;

        for (TileMapData tm : staticData.tileMaps.values()) {
            maps.put(tm.id, new GameMap(tm, this));
        }
        this.clanRepo = new ClanRepository(playerRepo);
        this.endgame = new EndgameOps(this, clanRepo);
        log.info("Initialized {} maps (1-2 zones/map).", maps.size());
    }

    public void start() {
        endgame.initWorldBosses();
        scheduler.scheduleAtFixedRate(this::tickWorld, 200, 200, TimeUnit.MILLISECONDS);
        long saveSec = Math.max(15, config.autoSaveIntervalSec);
        scheduler.scheduleAtFixedRate(this::autoSaveAll, saveSec, saveSec, TimeUnit.SECONDS);
    }

    public void sendToast(ZoneClient client, String text) {
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "toast");
        pkt.put("text", text);
        client.sendJson(pkt);
    }

    public synchronized void shutdown() {
        stopping = true;
        scheduler.shutdownNow();
        persistence.shutdown();
        for (ZoneClient zc : new ArrayList<>(onlinePlayers.values())) {
            try {
                leaveWorld(zc);
            } catch (Exception e) {
                log.warn("Error disconnecting player {} during shutdown", zc.getPlayerId(), e);
            }
        }
        retryPendingSaves();
        try {
            if (!persistence.awaitTermination(10, TimeUnit.SECONDS)) persistence.shutdownNow();
        } catch (InterruptedException e) {
            persistence.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void tickWorld() {
        if (stopping) return;
        long now = System.currentTimeMillis();
        try {
            endgame.tickEvents(now);
        } catch (Exception e) {
            log.error("World event tick failed", e);
        }
        // Chỉ tick những zone đang có người hoặc có drop/mob vừa chết
        for (GameMap gm : maps.values()) {
            for (GameZone z : gm.zones) {
                try {
                    z.tick(now);
                } catch (Exception e) {
                    log.error("Zone tick error map={} zone={}", gm.tileMap.id, z.zoneId, e);
                }
            }
        }
    }

    private synchronized void autoSaveAll() {
        if (stopping) return;
        retryPendingSaves();
        long now = System.currentTimeMillis();
        for (ZoneClient zc : onlinePlayers.values()) {
            PlayerState p = zc.getPlayer();
            if (p.dirty || (now - p.lastSaveAt) >= config.autoSaveIntervalSec * 1000L) {
                savePlayerAsync(p);
            }
        }
    }

    public synchronized void savePlayerAsync(PlayerState p) {
        if (stopping) return;
        ZoneClient current = onlinePlayers.get(p.id);
        if (current == null || current.getPlayer() != p) return;
        PlayerRepository.SaveSnapshot snapshot = playerRepo.snapshot(p);
        persistence.execute(() -> {
            try {
                playerRepo.saveSnapshot(snapshot, true);
            } catch (Exception e) {
                log.error("Async save failed for player {} (will retry on next autosave)", p.name, e);
            }
        });
    }

    // A user owns one active character, as in the original NSO account/session model.
    // Loading and admission share the same lock as disconnect and all game mutations.
    public synchronized <T extends ZoneClient> T openSession(long userId, long characterId,
            Function<PlayerState, T> factory) throws SQLException {
        ensureAccountAvailable(userId);
        for (PlayerState pending : new ArrayList<>(pendingSaves.values())) {
            if (pending.userId == userId) {
                playerRepo.savePlayer(pending, false);
                pendingSaves.remove(pending.id);
            }
        }
        PlayerState p = playerRepo.loadPlayer(characterId, userId, staticData);
        if (p == null) throw new IllegalArgumentException("Không tìm thấy nhân vật.");
        T session = factory.apply(p);
        enterWorld(session);
        return session;
    }

    private void ensureAccountAvailable(long userId) {
        if (stopping) throw new IllegalStateException("Máy chủ đang khởi động lại.");
        for (ZoneClient current : onlinePlayers.values()) {
            if (current.getPlayer().userId == userId) {
                throw new IllegalArgumentException("Tài khoản đang có nhân vật online. Hãy thoát phiên cũ trước.");
            }
        }
    }

    private void retryPendingSaves() {
        for (PlayerState p : new ArrayList<>(pendingSaves.values())) {
            try {
                playerRepo.savePlayer(p, false);
                pendingSaves.remove(p.id);
            } catch (SQLException e) {
                log.error("Retry disconnected player save failed: {}", p.name, e);
            }
        }
    }

    public synchronized void enterWorld(ZoneClient client) {
        PlayerState p = client.getPlayer();
        ensureAccountAvailable(p.userId);

        GameMap gm = maps.getOrDefault(p.mapId, maps.get(22));
        if (gm == null) {
            throw new IllegalStateException("Default map 22 not found");
        }
        p.mapId = gm.tileMap.id;
        GameZone zone = gm.pickBestZone();
        onlinePlayers.put(p.id, client);
        playerZones.put(p.id, zone);
        zone.join(client);
    }

    public synchronized void leaveWorld(ZoneClient client) {
        PlayerState p = client.getPlayer();
        if (onlinePlayers.get(p.id) != client) return;
        endgame.onPlayerDisconnect(client);
        onlinePlayers.remove(p.id, client);
        GameZone z = playerZones.remove(p.id);
        if (z != null) {
            z.leave(client);
        }
        try {
            playerRepo.savePlayer(p, false);
        } catch (Exception e) {
            p.dirty = true;
            pendingSaves.put(p.id, p);
            log.error("Save on disconnect failed for player {}", p.name, e);
        }
    }

    public synchronized void enterWaypoint(ZoneClient client) {
        if (onlinePlayers.get(client.getPlayerId()) != client) return;
        PlayerState p = client.getPlayer();
        GameZone currentZone = playerZones.get(p.id);
        if (currentZone == null || p.isDead) {
            return;
        }
        Waypoint wp = currentZone.gameMap.tileMap.findWaypoint(p.x, p.y);
        if (wp == null) {
            return;
        }
        int nextId = wp.next;
        int nextX = wp.x;
        int nextY = wp.y;
        if (nextId < 0) {
            // Cổng Làng Cổ từ map -1 -> map 140..143
            nextId = 140;
            nextX = 1011;
            nextY = 720;
        }
        changeMap(client, nextId, nextX, nextY);
    }

    public synchronized void changeMap(ZoneClient client, int mapId, int targetX, int targetY) {
        if (onlinePlayers.get(client.getPlayerId()) != client) return;
        GameMap nextMap = maps.get(mapId);
        if (nextMap == null) {
            return;
        }
        endgame.onPlayerChangeMap(client, mapId);
        PlayerState p = client.getPlayer();
        GameZone prevZone = playerZones.get(p.id);
        if (prevZone != null) {
            prevZone.leave(client);
        }
        p.mapId = mapId;
        p.x = targetX > 0 ? targetX : Math.min(240, nextMap.tileMap.pxw / 2);
        p.y = targetY > 0 ? targetY : nextMap.tileMap.collisionY(p.x, 48);
        p.dirty = true;

        GameZone nextZone = nextMap.pickBestZone();
        playerZones.put(p.id, nextZone);
        nextZone.join(client);
        PlayerOps.onMapEntered(client, nextZone, mapId);
    }

    public synchronized void changeZone(ZoneClient client, int targetZoneId) {
        if (onlinePlayers.get(client.getPlayerId()) != client) return;
        PlayerState p = client.getPlayer();
        GameMap gm = maps.get(p.mapId);
        if (gm == null || targetZoneId < 0 || targetZoneId >= gm.zones.size()) {
            return;
        }
        GameZone prev = playerZones.get(p.id);
        GameZone next = gm.getZone(targetZoneId);
        if (prev == next) {
            return;
        }
        endgame.tradeCancel(client);
        if (prev != null) {
            prev.leave(client);
        }
        playerZones.put(p.id, next);
        next.join(client);
    }

    public GameZone getZoneFor(long playerId) {
        return playerZones.get(playerId);
    }
}
