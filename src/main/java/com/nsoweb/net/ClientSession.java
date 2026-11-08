package com.nsoweb.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nsoweb.model.PlayerState;
import com.nsoweb.world.GameZone;
import com.nsoweb.world.PlayerOps;
import com.nsoweb.world.WorldManager;
import com.nsoweb.world.ZoneClient;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientSession implements ZoneClient {
    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);
    private static final Gson GSON = new Gson();

    private final WebSocketChannel channel;
    private final PlayerState player;
    private final WorldManager world;
    private final Queue<Runnable> incoming = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();

    public ClientSession(WebSocketChannel channel, PlayerState player, WorldManager world) {
        this.channel = channel;
        this.player = player;
        this.world = world;
    }

    @Override
    public long getPlayerId() {
        return player.id;
    }

    @Override
    public PlayerState getPlayer() {
        return player;
    }

    @Override
    public void sendJson(Map<String, Object> packet) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            String json = GSON.toJson(packet);
            WebSockets.sendText(json, channel, null);
        } catch (Exception e) {
            log.debug("Failed to send packet to {}: {}", player.name, e.getMessage());
        }
    }

    public void onMessage(String rawText) {
        synchronized (world) {
            if (world.onlinePlayers.get(player.id) != this) return;
            handleMessage(rawText);
        }
    }

    // Preserve packet/close ordering while keeping JDBC work off Undertow's I/O threads.
    public void enqueue(Runnable action) {
        incoming.add(action);
        queued.incrementAndGet();
        if (draining.compareAndSet(false, true)) channel.getWorker().execute(this::drain);
    }

    public void receive(String text) {
        if (queued.get() >= 128) {
            WebSockets.sendClose(1008, "Too many pending commands", channel, null);
            return;
        }
        enqueue(() -> onMessage(text));
    }

    private void drain() {
        try {
            Runnable action;
            while ((action = incoming.poll()) != null) {
                queued.decrementAndGet();
                try { action.run(); }
                catch (Exception e) { log.warn("Session action failed for {}", player.name, e); }
            }
        } finally {
            draining.set(false);
            if (!incoming.isEmpty() && draining.compareAndSet(false, true)) channel.getWorker().execute(this::drain);
        }
    }

    private void handleMessage(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        try {
            JsonObject msg = JsonParser.parseString(rawText).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";
            if (world.endgame.isTrading(player.id) && !Set.of(
                    "ping", "chat", "clan_chat", "trade_update", "trade_lock", "trade_confirm", "trade_cancel",
                    "move", "waypoint", "change_zone", "get_events", "clan_info").contains(type)) {
                world.sendToast(this, "Hãy kết thúc giao dịch trước khi thực hiện thao tác này.");
                return;
            }
            GameZone zone = world.getZoneFor(player.id);

            switch (type) {
                case "ping": {
                    Map<String, Object> pong = new LinkedHashMap<>();
                    pong.put("type", "pong");
                    pong.put("ts", System.currentTimeMillis());
                    sendJson(pong);
                    break;
                }
                case "move": {
                    if (zone != null) {
                        int x = msg.has("x") ? msg.get("x").getAsInt() : player.x;
                        int y = msg.has("y") ? msg.get("y").getAsInt() : player.y;
                        int dir = msg.has("dir") ? msg.get("dir").getAsInt() : player.dir;
                        int action = msg.has("action") ? msg.get("action").getAsInt() : 0;
                        zone.move(this, x, y, dir, action);
                    }
                    break;
                }
                case "chat": {
                    if (zone != null && msg.has("text")) {
                        zone.chat(this, msg.get("text").getAsString());
                    }
                    break;
                }
                case "attack": {
                    if (zone != null) {
                        int skillId = msg.has("skillId") ? msg.get("skillId").getAsInt() : player.selectedSkillId;
                        List<Integer> targets = new ArrayList<>();
                        if (msg.has("targets") && msg.get("targets").isJsonArray()) {
                            JsonArray arr = msg.getAsJsonArray("targets");
                            for (JsonElement el : arr) {
                                targets.add(el.getAsInt());
                            }
                        }
                        zone.attackMobs(this, skillId, targets);
                    }
                    break;
                }
                case "pickup": {
                    if (zone != null && msg.has("uid")) {
                        zone.pickupItem(this, msg.get("uid").getAsInt());
                    }
                    break;
                }
                case "waypoint": {
                    world.enterWaypoint(this);
                    break;
                }
                case "change_zone": {
                    if (msg.has("zoneId")) {
                        world.changeZone(this, msg.get("zoneId").getAsInt());
                    }
                    break;
                }
                case "revive": {
                    if (zone != null) {
                        boolean atSpot = msg.has("atSpot") && msg.get("atSpot").getAsBoolean();
                        zone.revive(this, atSpot);
                    }
                    break;
                }
                case "up_potential": {
                    if (zone != null && msg.has("index") && msg.has("points")) {
                        PlayerOps.upPotential(this, zone, msg.get("index").getAsInt(), msg.get("points").getAsInt());
                    }
                    break;
                }
                case "up_skill": {
                    if (zone != null && msg.has("templateId")) {
                        int pts = msg.has("points") ? msg.get("points").getAsInt() : 1;
                        PlayerOps.upSkill(this, zone, msg.get("templateId").getAsInt(), pts);
                    }
                    break;
                }
                case "set_hotkey": {
                    if (zone != null && msg.has("slot") && msg.has("skillId")) {
                        PlayerOps.setHotkey(this, zone, msg.get("slot").getAsInt(), msg.get("skillId").getAsInt());
                    }
                    break;
                }
                case "use_item": {
                    if (zone != null && msg.has("bagIdx")) {
                        PlayerOps.useItem(this, zone, msg.get("bagIdx").getAsInt());
                    }
                    break;
                }
                case "unequip_item": {
                    if (zone != null && msg.has("slot")) {
                        PlayerOps.unequipItem(this, zone, msg.get("slot").getAsInt());
                    }
                    break;
                }
                case "bag_to_box": {
                    if (zone != null && msg.has("bagIdx")) {
                        PlayerOps.bagToBox(this, zone, msg.get("bagIdx").getAsInt());
                    }
                    break;
                }
                case "box_to_bag": {
                    if (zone != null && msg.has("boxIdx")) {
                        PlayerOps.boxToBag(this, zone, msg.get("boxIdx").getAsInt());
                    }
                    break;
                }
                case "xu_box": {
                    if (zone != null && msg.has("delta")) {
                        PlayerOps.xuBox(this, zone, msg.get("delta").getAsLong());
                    }
                    break;
                }
                case "sort_bag": {
                    if (zone != null) {
                        PlayerOps.sortBag(this, zone);
                    }
                    break;
                }
                case "drop_bag_item": {
                    if (zone != null && msg.has("bagIdx")) {
                        PlayerOps.dropBagItem(this, zone, msg.get("bagIdx").getAsInt());
                    }
                    break;
                }
                case "npc_interact": {
                    if (zone != null && msg.has("npcId")) {
                        PlayerOps.npcInteract(this, zone, msg.get("npcId").getAsInt());
                    }
                    break;
                }
                case "open_shop": {
                    if (zone != null && msg.has("storeId")) {
                        PlayerOps.openShop(this, zone, msg.get("storeId").getAsInt());
                    }
                    break;
                }
                case "buy_item": {
                    if (zone != null && msg.has("storeId") && msg.has("index")) {
                        int qty = msg.has("quantity") ? msg.get("quantity").getAsInt() : 1;
                        PlayerOps.buyItem(this, zone, msg.get("storeId").getAsInt(), msg.get("index").getAsInt(), qty);
                    }
                    break;
                }
                case "sell_item": {
                    if (zone != null && msg.has("bagIdx")) {
                        int qty = msg.has("quantity") ? msg.get("quantity").getAsInt() : 1;
                        PlayerOps.sellItem(this, zone, msg.get("bagIdx").getAsInt(), qty);
                    }
                    break;
                }
                case "teleport_map": {
                    if (zone != null && msg.has("mapId")) {
                        PlayerOps.teleportMap(this, zone, msg.get("mapId").getAsInt());
                    }
                    break;
                }
                case "exchange_luong": {
                    if (zone != null && msg.has("option")) {
                        PlayerOps.exchangeLuong(this, zone, msg.get("option").getAsInt());
                    }
                    break;
                }
                case "join_class": {
                    if (zone != null && msg.has("classId")) {
                        PlayerOps.joinClass(this, zone, msg.get("classId").getAsInt());
                    }
                    break;
                }
                case "save_location": {
                    if (zone != null) {
                        PlayerOps.saveLocation(this, zone);
                    }
                    break;
                }
                // ==========================================
                // BƯỚC 4: CƯỜNG HÓA, GIAO DỊCH, PK/LÔI ĐÀI, BOSS/HANG ĐỘNG, GIA TỘC, AUTO
                // ==========================================
                case "upgrade_item": {
                    if (zone != null) {
                        String targetType = msg.has("targetType") ? msg.get("targetType").getAsString() : "bag";
                        int targetIdx = msg.has("targetIdx") ? msg.get("targetIdx").getAsInt() : 0;
                        List<Integer> stones = new ArrayList<>();
                        if (msg.has("stoneIndices") && msg.get("stoneIndices").isJsonArray()) {
                            for (JsonElement el : msg.getAsJsonArray("stoneIndices")) {
                                stones.add(el.getAsInt());
                            }
                        }
                        boolean useIns = msg.has("useInsurance") && msg.get("useInsurance").getAsBoolean();
                        boolean useLuong = msg.has("useLuong") && msg.get("useLuong").getAsBoolean();
                        world.endgame.upgradeItem(this, zone, targetType, targetIdx, stones, useIns, useLuong);
                    }
                    break;
                }
                case "combine_stones": {
                    if (zone != null && msg.has("stoneItemId")) {
                        boolean useXu = msg.has("useXu") && msg.get("useXu").getAsBoolean();
                        world.endgame.combineStones(this, zone, msg.get("stoneItemId").getAsInt(), useXu);
                    }
                    break;
                }
                case "trade_invite": {
                    if (msg.has("targetId")) {
                        world.endgame.tradeInvite(this, msg.get("targetId").getAsLong());
                    }
                    break;
                }
                case "trade_accept": {
                    if (msg.has("fromId")) {
                        world.endgame.tradeAccept(this, msg.get("fromId").getAsLong());
                    }
                    break;
                }
                case "trade_update": {
                    long xu = msg.has("xu") ? msg.get("xu").getAsLong() : 0L;
                    List<Integer> idxs = new ArrayList<>();
                    if (msg.has("bagIndices") && msg.get("bagIndices").isJsonArray()) {
                        for (JsonElement el : msg.getAsJsonArray("bagIndices")) {
                            idxs.add(el.getAsInt());
                        }
                    }
                    world.endgame.tradeUpdate(this, xu, idxs);
                    break;
                }
                case "trade_lock": {
                    world.endgame.tradeLock(this);
                    break;
                }
                case "trade_confirm": {
                    world.endgame.tradeConfirm(this);
                    break;
                }
                case "trade_cancel": {
                    world.endgame.tradeCancel(this);
                    break;
                }
                case "set_pk": {
                    if (zone != null && msg.has("pkMode")) {
                        world.endgame.setPkMode(this, zone, msg.get("pkMode").getAsInt());
                    }
                    break;
                }
                case "arena_challenge": {
                    if (msg.has("targetId")) {
                        long bet = msg.has("betXu") ? msg.get("betXu").getAsLong() : 0L;
                        world.endgame.arenaChallenge(this, msg.get("targetId").getAsLong(), bet);
                    }
                    break;
                }
                case "arena_accept": {
                    if (msg.has("fromId")) {
                        world.endgame.arenaAccept(this, msg.get("fromId").getAsLong());
                    }
                    break;
                }
                case "attack_player": {
                    if (zone != null && msg.has("targetId")) {
                        int sk = msg.has("skillId") ? msg.get("skillId").getAsInt() : player.selectedSkillId;
                        world.endgame.attackPlayer(this, zone, msg.get("targetId").getAsLong(), sk);
                    }
                    break;
                }
                case "get_events": {
                    world.endgame.sendEventsState(this);
                    break;
                }
                case "warp_boss": {
                    if (msg.has("bossIndex")) {
                        world.endgame.warpToBoss(this, msg.get("bossIndex").getAsInt());
                    }
                    break;
                }
                case "enter_cave": {
                    int tier = msg.has("tier") ? msg.get("tier").getAsInt() : 0;
                    world.endgame.enterCave(this, tier);
                    break;
                }
                case "leave_cave": {
                    world.endgame.leaveCave(this);
                    break;
                }
                case "clan_info": {
                    world.endgame.sendClanInfo(this);
                    break;
                }
                case "create_clan": {
                    if (zone != null && msg.has("name")) {
                        world.endgame.createClan(this, zone, msg.get("name").getAsString());
                    }
                    break;
                }
                case "clan_invite": {
                    if (msg.has("target")) {
                        world.endgame.clanInvite(this, msg.get("target").getAsString());
                    }
                    break;
                }
                case "clan_accept": {
                    if (msg.has("clanId")) {
                        world.endgame.clanAccept(this, msg.get("clanId").getAsLong());
                    }
                    break;
                }
                case "clan_leave": {
                    world.endgame.clanLeave(this);
                    break;
                }
                case "clan_kick": {
                    if (msg.has("playerId")) {
                        world.endgame.clanKick(this, msg.get("playerId").getAsLong());
                    }
                    break;
                }
                case "clan_donate": {
                    if (msg.has("amount")) {
                        world.endgame.clanDonate(this, msg.get("amount").getAsLong());
                    }
                    break;
                }
                case "clan_alert": {
                    if (msg.has("alert")) {
                        world.endgame.clanSetAlert(this, msg.get("alert").getAsString());
                    }
                    break;
                }
                case "clan_chat": {
                    if (msg.has("text")) {
                        world.endgame.clanChat(this, msg.get("text").getAsString());
                    }
                    break;
                }
                case "save_auto_settings": {
                    int hp = msg.has("hpThreshold") ? msg.get("hpThreshold").getAsInt() : 50;
                    int mp = msg.has("mpThreshold") ? msg.get("mpThreshold").getAsInt() : 35;
                    boolean pick = !msg.has("pickupAll") || msg.get("pickupAll").getAsBoolean();
                    world.endgame.saveAutoSettings(this, hp, mp, pick);
                    break;
                }
                default:
                    log.debug("Unknown WS message type '{}' from player {}", type, player.name);
            }
        } catch (Exception e) {
            log.warn("Error handling WS message from {}: {}", player.name, e.getMessage());
        }
    }
}
