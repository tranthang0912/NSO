package com.nsoweb.world;

import com.google.gson.Gson;
import com.nsoweb.db.ClanRepository;
import com.nsoweb.db.ClanRepository.ClanInfoDto;
import com.nsoweb.model.ItemInstance;
import com.nsoweb.model.PlayerCalculator;
import com.nsoweb.model.PlayerState;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.ItemTemplate;
import com.nsoweb.model.StaticData.MonsterTemplate;
import com.nsoweb.model.StaticData.SkillLevelData;
import com.nsoweb.model.StaticData.SkillTemplate;
import com.nsoweb.model.StaticData.TileMapData;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Toàn bộ tính năng Bước 4:
 * - Cường hóa (GameData.java + Char.upgradeItem + Item.next) & Luyện đá
 * - Giao dịch trực tiếp nguyên tử giữa 2 người chơi
 * - PK Đồ sát & Lôi đài Solo 1v1 (Map 111)
 * - World Boss & Hang động (Wave quái -> Boss cuối -> Quà nhóm)
 * - Gia tộc (DB-backed) + Chat Gia tộc liên bản đồ
 */
public class EndgameOps {
    private static final Gson GSON = new Gson();
    private static final Logger log = LoggerFactory.getLogger(EndgameOps.class);

    // Hằng số chuẩn từ GameData.java (SRC NSOACE FIX)
    public static final int[] UP_CRYSTAL = {1, 4, 16, 64, 256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304};
    public static final int[] UP_CLOTHE = {4, 9, 33, 132, 177, 256, 656, 2880, 3968, 6016, 13440, 54144, 71680, 108544, 225280, 1032192};
    public static final int[] UP_ADORN = {6, 14, 50, 256, 320, 512, 1024, 5120, 6016, 9088, 19904, 86016, 108544, 166912, 360448, 1589248};
    public static final int[] UP_WEAPON = {18, 42, 132, 627, 864, 1360, 2816, 13824, 17792, 26880, 54016, 267264, 315392, 489472, 1032192, 4587520};
    public static final int[] COIN_UP_CRYSTAL = {10, 40, 160, 640, 2560, 10240, 40960, 163840, 655360, 1310720, 3932160, 11796480};
    public static final int[] COIN_UP_CLOTHE = {120, 270, 990, 3960, 5310, 7680, 19680, 86400, 119040, 180480, 403200, 1624320, 2150400, 3256320, 6758400, 10137600};
    public static final int[] COIN_UP_ADORN = {180, 420, 1500, 7680, 9600, 15360, 30720, 153600, 180480, 272640, 597120, 2580480, 3256320, 5007360, 10813440, 16220160};
    public static final int[] COIN_UP_WEAPON = {540, 1260, 3960, 18810, 25920, 40800, 84480, 414720, 533760, 806400, 1620480, 8017920, 9461760, 14684160, 22026240, 33039360};
    public static final int[] GOLD_UP = {1, 2, 3, 4, 5, 10, 15, 20, 50, 100, 150, 200, 300, 400, 500, 600};
    public static final int[] MAX_PERCENT = {80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5};

    public static final int ARENA_MAP_ID = 111;
    public static final long CLAN_CREATE_COST_XU = 2_000L;

    private final WorldManager world;
    private final ClanRepository clanRepo;
    private final StaticData sd;

    // Trade state
    private final Map<Long, PendingInvite> tradeInvites = new ConcurrentHashMap<>(); // targetId -> invite
    private final Map<Long, TradeSession> activeTrades = new ConcurrentHashMap<>(); // playerId -> session

    // Arena duel state
    private final Map<Long, PendingInvite> arenaInvites = new ConcurrentHashMap<>(); // targetId -> invite
    private final Map<Long, ArenaMatch> activeArenas = new ConcurrentHashMap<>(); // playerId -> match

    // Clan invite state
    private final Map<Long, PendingClanInvite> clanInvites = new ConcurrentHashMap<>(); // targetPlayerId -> invite

    // World Boss tracking
    public final List<WorldBossSlot> bossSlots = new ArrayList<>();
    private final AtomicInteger matchIdGen = new AtomicInteger(1);
    private volatile long lastEventTickAt = 0;

    public EndgameOps(WorldManager world, ClanRepository clanRepo) {
        this.world = world;
        this.clanRepo = clanRepo;
        this.sd = world.staticData;
        setupBossSlots();
    }

    private void setupBossSlots() {
        bossSlots.add(new WorldBossSlot(0, 115, 25, 25_000, 5, 960));   // Xich phien thien long - Rung truc Utra
        bossSlots.add(new WorldBossSlot(1, 114, 35, 45_000, 13, 480));  // Than tho - Rung go Kouji
        bossSlots.add(new WorldBossSlot(2, 116, 45, 70_000, 29, 760));  // Samurai chien tuong - Hang Aka
        bossSlots.add(new WorldBossSlot(3, 139, 55, 100_000, 41, 1020));// Hoa nguu vuong - Khu da do Akai
        bossSlots.add(new WorldBossSlot(4, 201, 65, 135_000, 61, 600)); // Ken Ken Vuong - Cua bien Kawaguchi
    }

    public void initWorldBosses() {
        long now = System.currentTimeMillis();
        for (WorldBossSlot slot : bossSlots) {
            spawnBossInSlot(slot, now, false);
        }
    }

    private void spawnBossInSlot(WorldBossSlot slot, long now, boolean announce) {
        GameMap gm = world.maps.get(slot.mapId);
        MonsterTemplate tpl = sd.monsters.get(slot.templateId);
        if (gm == null || tpl == null) {
            return;
        }
        GameZone z = gm.getZone(0);
        z.ensureInitialized();
        synchronized (z.mobs) {
            if (slot.mob != null && !slot.mob.dead) {
                return;
            }
            int x = Math.max(80, Math.min(gm.tileMap.pxw - 80, slot.spawnX));
            int y = gm.tileMap.collisionY(x, 48);
            int uid = z.mobUidGen.getAndIncrement();
            MobInstance mob = new MobInstance(uid, tpl, x, y, true, slot.bossLevel, slot.bossHp);
            mob.cantRespawn = true;
            mob.isWorldBoss = true;
            mob.worldBossIndex = slot.index;
            z.mobs.add(mob);
            slot.mob = mob;
            slot.zone = z;
            slot.respawnAt = 0;

            Map<String, Object> pkt = new LinkedHashMap<>();
            pkt.put("type", "mob_respawn");
            pkt.put("mob", mob.toDto());
            z.broadcast(pkt, -1);
        }
        if (announce) {
            broadcastGlobalToast("Boss Thế Giới [" + tpl.name + " Lv." + slot.bossLevel + "] đã xuất hiện tại " + gm.tileMap.name + "!");
        }
    }

    public void tickEvents(long now) {
        if (now - lastEventTickAt < 2000L) {
            return;
        }
        lastEventTickAt = now;

        // 1. Respawn World Bosses sau khi chết
        for (WorldBossSlot slot : bossSlots) {
            if (slot.mob != null && slot.mob.dead && slot.respawnAt > 0 && now >= slot.respawnAt) {
                synchronized (slot.zone.mobs) {
                    int x = Math.max(80, Math.min(slot.zone.gameMap.tileMap.pxw - 80, slot.spawnX));
                    int y = slot.zone.gameMap.tileMap.collisionY(x, 48);
                    slot.mob.resetStats(false);
                    slot.mob.x = x;
                    slot.mob.y = y;
                    slot.respawnAt = 0;
                    Map<String, Object> pkt = new LinkedHashMap<>();
                    pkt.put("type", "mob_respawn");
                    pkt.put("mob", slot.mob.toDto());
                    slot.zone.broadcast(pkt, -1);
                }
                MonsterTemplate tpl = sd.monsters.get(slot.templateId);
                if (tpl != null) {
                    broadcastGlobalToast("Boss [" + tpl.name + "] đã tái xuất tại " + slot.zone.gameMap.tileMap.name + "!");
                }
            }
        }
    }

    // =========================================================
    // 1. CƯỜNG HÓA & LUYỆN ĐÁ (Kenshinto)
    // =========================================================
    public void upgradeItem(ZoneClient client, GameZone zone, String targetType, int targetIdx,
                            List<Integer> stoneBagIndices, boolean useInsurance, boolean useLuong) {
        PlayerState p = client.getPlayer();
        if (p.isDead || activeTrades.containsKey(p.id)) {
            return;
        }
        ItemInstance item;
        boolean equippedTarget = "equipped".equalsIgnoreCase(targetType);
        if (equippedTarget) {
            if (targetIdx < 0 || targetIdx >= 16) {
                return;
            }
            item = p.equipped[targetIdx];
        } else {
            if (targetIdx < 0 || targetIdx >= p.bag.size()) {
                return;
            }
            item = p.bag.get(targetIdx);
        }
        if (item == null) {
            return;
        }
        ItemTemplate tpl = sd.items.get(item.itemId);
        if (tpl == null || tpl.type > 9 || tpl.fashion > -1) {
            zone.sendToast(client, "Loại trang bị này không thể cường hóa.");
            return;
        }
        int upMax = maxUpgradeForLevel(tpl.level);
        if (item.upgrade >= upMax) {
            zone.sendToast(client, "Trang bị đã đạt cấp cường hóa tối đa (+" + upMax + ").");
            return;
        }

        if (stoneBagIndices == null || stoneBagIndices.isEmpty()) {
            zone.sendToast(client, "Hãy chọn ít nhất 1 viên Đá nâng cấp.");
            return;
        }
        if (stoneBagIndices.size() > 18) {
            zone.sendToast(client, "Chỉ được dùng tối đa 18 viên đá mỗi lần.");
            return;
        }

        // Đếm số lượng cần trừ ở mỗi ô hành trang
        Map<Integer, Integer> consumeCounts = new LinkedHashMap<>();
        long crystalPower = 0;
        for (Integer bIdx : stoneBagIndices) {
            if (bIdx == null || bIdx < 0 || bIdx >= p.bag.size()) {
                continue;
            }
            if (!equippedTarget && bIdx == targetIdx) {
                zone.sendToast(client, "Không thể chọn chính trang bị làm nguyên liệu.");
                return;
            }
            ItemInstance st = p.bag.get(bIdx);
            if (st == null || st.itemId < 0 || st.itemId > 11) {
                zone.sendToast(client, "Vật phẩm ở ô #" + bIdx + " không phải Đá nâng cấp.");
                return;
            }
            int already = consumeCounts.getOrDefault(bIdx, 0);
            if (already + 1 > st.quantity) {
                zone.sendToast(client, "Không đủ số lượng đá trong hành trang.");
                return;
            }
            consumeCounts.put(bIdx, already + 1);
            crystalPower += UP_CRYSTAL[st.itemId];
        }
        if (crystalPower <= 0) {
            zone.sendToast(client, "Hãy chọn Đá nâng cấp hợp lệ.");
            return;
        }

        // Bảo hiểm
        int bhBagIdx = -1;
        if (useInsurance) {
            for (int i = 0; i < p.bag.size(); i++) {
                ItemInstance cand = p.bag.get(i);
                if (cand.itemId == 242 && item.upgrade < 8) { bhBagIdx = i; break; }
                if (cand.itemId == 284 && item.upgrade < 12) { bhBagIdx = i; break; }
                if (cand.itemId == 285 && item.upgrade < 14) { bhBagIdx = i; break; }
                if (cand.itemId == 475 && item.upgrade < 16) { bhBagIdx = i; break; }
            }
            if (bhBagIdx < 0) {
                zone.sendToast(client, "Không tìm thấy bùa Bảo hiểm phù hợp cho cấp +" + item.upgrade + ".");
                return;
            }
        }

        // Tỉ lệ & chi phí theo loại (Clothe: 0,2,4,6,8 | Adorn: 3,5,7,9 | Weapon: 1)
        int curUp = item.upgrade;
        int percent;
        int coinCost;
        if (tpl.type == 1) {
            percent = (int) (crystalPower * 100L / UP_WEAPON[curUp]);
            coinCost = COIN_UP_WEAPON[curUp];
        } else if (tpl.type == 3 || tpl.type == 5 || tpl.type == 7 || tpl.type == 9) {
            percent = (int) (crystalPower * 100L / UP_ADORN[curUp]);
            coinCost = COIN_UP_ADORN[curUp];
        } else {
            percent = (int) (crystalPower * 100L / UP_CLOTHE[curUp]);
            coinCost = COIN_UP_CLOTHE[curUp];
        }

        int maxPct = MAX_PERCENT[curUp] - (curUp >= 14 ? 1 : 0);
        if (percent > maxPct) {
            percent = maxPct;
        }
        int goldCost = 0;
        if (useLuong) {
            percent = Math.min(100, (int) (percent * 1.5));
            goldCost = GOLD_UP[curUp];
        }
        percent = Math.max(0, percent);

        if (coinCost > (p.xu + p.yen)) {
            zone.sendToast(client, "Cần " + coinCost + " Yên/Xu để cường hóa.");
            return;
        }
        if (useLuong && p.luong < goldCost) {
            zone.sendToast(client, "Cần " + goldCost + " Lượng để dùng chế độ Cẩn thận.");
            return;
        }

        // Trừ tiền (ưu tiên Yên trước như bản gốc)
        if (goldCost > 0) {
            p.luong -= goldCost;
        }
        if (coinCost > p.yen) {
            long rem = coinCost - p.yen;
            p.yen = 0;
            p.xu -= rem;
        } else {
            p.yen -= coinCost;
        }

        // Trừ đá & bảo hiểm (từ chỉ số lớn xuống nhỏ để không lệch bagIdx)
        if (bhBagIdx >= 0) {
            consumeCounts.put(bhBagIdx, consumeCounts.getOrDefault(bhBagIdx, 0) + 1);
        }
        List<Integer> sortedIndices = new ArrayList<>(consumeCounts.keySet());
        sortedIndices.sort(Comparator.reverseOrder());
        for (int bIdx : sortedIndices) {
            int qtyNeed = consumeCounts.get(bIdx);
            ItemInstance st = p.bag.get(bIdx);
            if (st.quantity <= qtyNeed) {
                p.bag.remove(bIdx);
            } else {
                st.quantity -= qtyNeed;
            }
        }

        int roll = ThreadLocalRandom.current().nextInt(100);
        boolean success = roll < percent;
        int prevUp = item.upgrade;
        int nextUp = prevUp;
        if (success) {
            nextUp = prevUp + 1;
        } else if (!useInsurance && prevUp > 4) {
            if (prevUp >= 14) {
                nextUp = 14;
            } else if (prevUp >= 12) {
                nextUp = 12;
            } else {
                nextUp = (prevUp / 4) * 4;
            }
        }
        item.locked = true;
        applyUpgradeDelta(item, nextUp - prevUp);

        if (equippedTarget) {
            PlayerCalculator.recalculate(p, zone.sd);
            p.hp = Math.min(p.hp, p.maxHp);
            p.mp = Math.min(p.mp, p.maxMp);
            zone.broadcastPlayerStats(p);
        }
        p.dirty = true;
        world.savePlayerAsync(p);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", "upgrade_result");
        res.put("success", success);
        res.put("percent", percent);
        res.put("prevUpgrade", prevUp);
        res.put("newUpgrade", nextUp);
        res.put("itemName", tpl.name);
        client.sendJson(res);

        zone.sendToast(client, success
                ? "Cường hóa THÀNH CÔNG! " + tpl.name + " lên +" + nextUp + "!"
                : "Cường hóa thất bại (" + percent + "%). " + tpl.name + " về +" + nextUp + ".");
        zone.sendSelfFullState(client);
    }

    public static int maxUpgradeForLevel(int level) {
        return com.nsoweb.model.ItemRules.maxUpgradeForLevel(level);
    }

    public static void applyUpgradeDelta(ItemInstance item, int delta) {
        if (delta == 0) {
            return;
        }
        item.upgrade += delta;
        if (item.options == null) {
            return;
        }
        for (int[] opt : item.options) {
            int id = opt[0];
            if (id == 6 || id == 7) {
                opt[1] += 15 * delta;
            } else if (id == 8 || id == 9 || id == 19) {
                opt[1] += 10 * delta;
            } else if (id == 10 || id == 11 || id == 12 || id == 13 || id == 14
                    || id == 15 || id == 17 || id == 18 || id == 20) {
                opt[1] += 5 * delta;
            } else if (id >= 21 && id <= 26) {
                opt[1] += 150 * delta;
            } else if (id == 16) {
                opt[1] += 3 * delta;
            }
        }
    }

    public void combineStones(ZoneClient client, GameZone zone, int stoneItemId, boolean useXu) {
        PlayerState p = client.getPlayer();
        if (p.isDead || stoneItemId < 0 || stoneItemId >= 11) {
            zone.sendToast(client, "Chỉ có thể luyện đá từ cấp 1 đến cấp 11.");
            return;
        }
        int available = 0;
        for (ItemInstance it : p.bag) {
            if (it.itemId == stoneItemId) {
                available += it.quantity;
            }
        }
        if (available < 4) {
            zone.sendToast(client, "Cần ít nhất 4 viên Đá cấp " + (stoneItemId + 1) + " để ghép lên cấp.");
            return;
        }
        long cost = COIN_UP_CRYSTAL[stoneItemId];
        if (useXu) {
            if (p.xu < cost) {
                zone.sendToast(client, "Cần " + cost + " Xu.");
                return;
            }
        } else {
            if ((p.yen + p.xu) < cost) {
                zone.sendToast(client, "Cần " + cost + " Yên/Xu.");
                return;
            }
        }
        if (p.bag.size() >= p.bagCapacity) {
            zone.sendToast(client, "Hành trang cần ít nhất 1 ô trống.");
            return;
        }

        // Trừ tiền
        if (useXu) {
            p.xu -= cost;
        } else if (cost > p.yen) {
            long rem = cost - p.yen;
            p.yen = 0;
            p.xu -= rem;
        } else {
            p.yen -= cost;
        }

        // Trừ 4 viên đá
        int remNeed = 4;
        boolean anyLocked = false;
        for (int i = p.bag.size() - 1; i >= 0 && remNeed > 0; i--) {
            ItemInstance it = p.bag.get(i);
            if (it.itemId == stoneItemId) {
                if (it.locked) {
                    anyLocked = true;
                }
                int take = Math.min(remNeed, it.quantity);
                it.quantity -= take;
                remNeed -= take;
                if (it.quantity <= 0) {
                    p.bag.remove(i);
                }
            }
        }

        ItemInstance nextStone = new ItemInstance(stoneItemId + 1, 1);
        nextStone.locked = !useXu || anyLocked;
        zone.addItemToBag(p, nextStone);
        p.dirty = true;
        world.savePlayerAsync(p);
        zone.sendToast(client, "Luyện thành công 1 Đá cấp " + (stoneItemId + 2) + "!");
        zone.sendSelfFullState(client);
    }

    // =========================================================
    // 2. GIAO DỊCH TRỰC TIẾP (Atomic Dual-Pane Trade)
    // =========================================================
    public void tradeInvite(ZoneClient client, long targetId) {
        PlayerState p = client.getPlayer();
        if (p.id == targetId || p.isDead) {
            return;
        }
        if (activeTrades.containsKey(p.id)) {
            world.sendToast(client, "Bạn đang trong giao dịch.");
            return;
        }
        ZoneClient target = world.onlinePlayers.get(targetId);
        if (target == null || target.getPlayer().isDead) {
            world.sendToast(client, "Người chơi không trực tuyến.");
            return;
        }
        if (world.getZoneFor(p.id) != world.getZoneFor(targetId)) {
            world.sendToast(client, "Hai người phải đứng cùng bản đồ & khu vực.");
            return;
        }
        if (activeTrades.containsKey(targetId)) {
            world.sendToast(client, target.getPlayer().name + " đang giao dịch với người khác.");
            return;
        }
        tradeInvites.put(targetId, new PendingInvite(p.id, p.name, 0, System.currentTimeMillis() + 30_000L));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "trade_request");
        req.put("fromId", p.id);
        req.put("fromName", p.name);
        target.sendJson(req);
        world.sendToast(client, "Đã gửi lời mời giao dịch tới " + target.getPlayer().name + ".");
    }

    public void tradeAccept(ZoneClient client, long fromId) {
        PlayerState me = client.getPlayer();
        PendingInvite inv = tradeInvites.remove(me.id);
        if (inv == null || inv.fromId != fromId || System.currentTimeMillis() > inv.expireAt) {
            world.sendToast(client, "Lời mời giao dịch đã hết hạn.");
            return;
        }
        ZoneClient partner = world.onlinePlayers.get(fromId);
        if (partner == null || activeTrades.containsKey(me.id) || activeTrades.containsKey(fromId)) {
            world.sendToast(client, "Không thể bắt đầu giao dịch.");
            return;
        }
        if (world.getZoneFor(me.id) != world.getZoneFor(fromId)) {
            world.sendToast(client, "Đối phương không còn ở trong khu vực này.");
            return;
        }
        TradeSession session = new TradeSession(partner, client);
        activeTrades.put(fromId, session);
        activeTrades.put(me.id, session);
        sendTradeState(session, "trade_open");
    }

    public void tradeUpdate(ZoneClient client, long xu, List<Integer> bagIndices) {
        TradeSession session = activeTrades.get(client.getPlayerId());
        if (session == null || session.finished) {
            return;
        }
        synchronized (session) {
            if (session.finished) {
                return;
            }
            TraderSide side = session.sideOf(client.getPlayerId());
            if (side.locked) {
                world.sendToast(client, "Bạn đã khóa giao dịch, không thể thay đổi vật phẩm.");
                return;
            }
            PlayerState p = client.getPlayer();
            if (xu < 0 || xu > p.xu || xu > 500_000_000L) {
                world.sendToast(client, "Số Xu giao dịch không hợp lệ.");
                return;
            }
            List<Integer> cleanIdx = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            if (bagIndices != null) {
                for (Integer idx : bagIndices) {
                    if (idx == null || !seen.add(idx) || idx < 0 || idx >= p.bag.size()) {
                        continue;
                    }
                    ItemInstance it = p.bag.get(idx);
                    if (it.locked || it.isExpired()) {
                        world.sendToast(client, "Không thể giao dịch vật phẩm đã khóa.");
                        return;
                    }
                    cleanIdx.add(idx);
                    if (cleanIdx.size() >= 12) {
                        break;
                    }
                }
            }
            side.xuOffer = xu;
            side.bagIndices = cleanIdx;
            side.offeredItems = new ArrayList<>();
            for (int idx : cleanIdx) side.offeredItems.add(GSON.toJson(p.bag.get(idx)));
            // Reset xác nhận của cả 2 nếu có cập nhật mới
            session.a.confirmed = false;
            session.b.confirmed = false;
            sendTradeState(session, "trade_state");
        }
    }

    public void tradeLock(ZoneClient client) {
        TradeSession session = activeTrades.get(client.getPlayerId());
        if (session == null || session.finished) {
            return;
        }
        synchronized (session) {
            if (session.finished) {
                return;
            }
            TraderSide side = session.sideOf(client.getPlayerId());
            side.locked = true;
            sendTradeState(session, "trade_state");
        }
    }

    public void tradeConfirm(ZoneClient client) {
        TradeSession session = activeTrades.get(client.getPlayerId());
        if (session == null || session.finished) {
            return;
        }
        synchronized (session) {
            if (session.finished) {
                return;
            }
            if (!session.a.locked || !session.b.locked) {
                world.sendToast(client, "Cả hai người chơi cần bấm Khóa giao dịch trước khi Xác nhận.");
                return;
            }
            session.sideOf(client.getPlayerId()).confirmed = true;
            if (!session.a.confirmed || !session.b.confirmed) {
                sendTradeState(session, "trade_state");
                return;
            }

            // Thực thi giao dịch nguyên tử
            PlayerState pa = session.a.client.getPlayer();
            PlayerState pb = session.b.client.getPlayer();

            if (world.onlinePlayers.get(pa.id) != session.a.client || world.onlinePlayers.get(pb.id) != session.b.client
                    || world.getZoneFor(pa.id) != world.getZoneFor(pb.id)) {
                abortTrade(session, "Người chơi không còn ở cùng khu vực.");
                return;
            }

            if (session.a.xuOffer < 0 || session.a.xuOffer > pa.xu || session.b.xuOffer < 0 || session.b.xuOffer > pb.xu) {
                abortTrade(session, "Số dư Xu không hợp lệ, giao dịch bị hủy.");
                return;
            }
            if (!validateBagTrade(pa, session.a, session.b.bagIndices.size())
                    || !validateBagTrade(pb, session.b, session.a.bagIndices.size())) {
                abortTrade(session, "Hành trang không đủ chỗ hoặc vật phẩm không hợp lệ.");
                return;
            }

            // Stage the exchange independently; publish it only after both saves commit.
            PlayerState nextA = GSON.fromJson(GSON.toJson(pa), PlayerState.class);
            PlayerState nextB = GSON.fromJson(GSON.toJson(pb), PlayerState.class);
            List<ItemInstance> aToB = extractBagItems(nextA, session.a.bagIndices);
            List<ItemInstance> bToA = extractBagItems(nextB, session.b.bagIndices);
            nextA.xu = Math.addExact(pa.xu - session.a.xuOffer, session.b.xuOffer);
            nextB.xu = Math.addExact(pb.xu - session.b.xuOffer, session.a.xuOffer);

            GameZone za = world.getZoneFor(pa.id);
            GameZone zb = world.getZoneFor(pb.id);
            for (ItemInstance it : bToA) {
                if (za != null) za.addItemToBag(nextA, it); else nextA.bag.add(it);
            }
            for (ItemInstance it : aToB) {
                if (zb != null) zb.addItemToBag(nextB, it); else nextB.bag.add(it);
            }
            try {
                world.playerRepo.savePlayersAtomically(List.of(nextA, nextB), true);
            } catch (SQLException e) {
                log.error("Failed to save players after trade", e);
                abortTrade(session, "Không thể lưu giao dịch. Tiền và vật phẩm chưa được chuyển.");
                return;
            }
            pa.bag = nextA.bag;
            pb.bag = nextB.bag;
            pa.xu = nextA.xu;
            pb.xu = nextB.xu;
            pa.dirty = pb.dirty = false;
            pa.lastSaveAt = pb.lastSaveAt = System.currentTimeMillis();
            session.finished = true;
            activeTrades.remove(pa.id);
            activeTrades.remove(pb.id);

            Map<String, Object> closePkt = new LinkedHashMap<>();
            closePkt.put("type", "trade_closed");
            closePkt.put("ok", true);
            closePkt.put("message", "Giao dịch hoàn tất!");
            session.a.client.sendJson(closePkt);
            session.b.client.sendJson(closePkt);

            if (za != null) za.sendSelfFullState(session.a.client);
            if (zb != null) zb.sendSelfFullState(session.b.client);
            world.sendToast(session.a.client, "Giao dịch thành công!");
            world.sendToast(session.b.client, "Giao dịch thành công!");
        }
    }

    private boolean validateBagTrade(PlayerState p, TraderSide side, int incomingCount) {
        List<Integer> indices = side.bagIndices;
        for (int i = 0; i < indices.size(); i++) {
            int idx = indices.get(i);
            if (idx < 0 || idx >= p.bag.size()) {
                return false;
            }
            ItemInstance it = p.bag.get(idx);
            if (it.locked || it.isExpired() || !GSON.toJson(it).equals(side.offeredItems.get(i))) {
                return false;
            }
        }
        return (p.bag.size() - indices.size() + incomingCount) <= p.bagCapacity;
    }

    private List<ItemInstance> extractBagItems(PlayerState p, List<Integer> indices) {
        List<Integer> sorted = new ArrayList<>(indices);
        sorted.sort(Comparator.reverseOrder());
        List<ItemInstance> out = new ArrayList<>();
        for (int idx : sorted) {
            out.add(p.bag.remove(idx));
        }
        Collections.reverse(out);
        return out;
    }

    public void tradeCancel(ZoneClient client) {
        TradeSession session = activeTrades.get(client.getPlayerId());
        if (session == null) {
            return;
        }
        synchronized (session) {
            abortTrade(session, client.getPlayer().name + " đã hủy giao dịch.");
        }
    }

    public boolean isTrading(long playerId) {
        return activeTrades.containsKey(playerId);
    }

    private void abortTrade(TradeSession session, String reason) {
        if (session.finished) {
            return;
        }
        session.finished = true;
        activeTrades.remove(session.a.client.getPlayerId());
        activeTrades.remove(session.b.client.getPlayerId());

        Map<String, Object> closePkt = new LinkedHashMap<>();
        closePkt.put("type", "trade_closed");
        closePkt.put("ok", false);
        closePkt.put("message", reason);
        session.a.client.sendJson(closePkt);
        session.b.client.sendJson(closePkt);
    }

    private void sendTradeState(TradeSession session, String pktType) {
        session.a.client.sendJson(buildTradeDto(pktType, session.a, session.b));
        session.b.client.sendJson(buildTradeDto(pktType, session.b, session.a));
    }

    private Map<String, Object> buildTradeDto(String pktType, TraderSide self, TraderSide partner) {
        PlayerState ps = self.client.getPlayer();
        List<ItemInstance> myItems = new ArrayList<>();
        for (int idx : self.bagIndices) {
            if (idx >= 0 && idx < ps.bag.size()) {
                myItems.add(ps.bag.get(idx));
            }
        }
        PlayerState pp = partner.client.getPlayer();
        List<ItemInstance> partnerItems = new ArrayList<>();
        for (int idx : partner.bagIndices) {
            if (idx >= 0 && idx < pp.bag.size()) {
                partnerItems.add(pp.bag.get(idx));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", pktType);
        m.put("partnerId", pp.id);
        m.put("partnerName", pp.name);
        m.put("myXu", self.xuOffer);
        m.put("myIndices", self.bagIndices);
        m.put("myItems", myItems);
        m.put("myLocked", self.locked);
        m.put("myConfirmed", self.confirmed);
        m.put("partnerXu", partner.xuOffer);
        m.put("partnerItems", partnerItems);
        m.put("partnerLocked", partner.locked);
        m.put("partnerConfirmed", partner.confirmed);
        return m;
    }

    // =========================================================
    // 3. PK & LÔI ĐÀI SOLO (Arena Map 111)
    // =========================================================
    public void setPkMode(ZoneClient client, GameZone zone, int pkMode) {
        PlayerState p = client.getPlayer();
        if (pkMode != 0 && pkMode != 3) {
            pkMode = 0;
        }
        p.pkMode = pkMode;
        p.dirty = true;
        zone.broadcastPlayerAppearance(p);
        zone.sendSelfFullState(client);
        zone.sendToast(client, pkMode == 3 ? "Đã bật chế độ PK Đồ Sát!" : "Đã chuyển sang chế độ Hòa bình.");
    }

    public void arenaChallenge(ZoneClient client, long targetId, long betXu) {
        PlayerState p = client.getPlayer();
        if (p.id == targetId || p.isDead) {
            return;
        }
        ZoneClient target = world.onlinePlayers.get(targetId);
        if (target == null || target.getPlayer().isDead) {
            world.sendToast(client, "Đối thủ hiện không sẵn sàng.");
            return;
        }
        if (activeArenas.containsKey(p.id) || activeArenas.containsKey(targetId)) {
            world.sendToast(client, "Một trong hai đang ở trong trận Lôi đài.");
            return;
        }
        long cleanBet = Math.max(0, Math.min(5_000_000L, betXu));
        if (p.xu < cleanBet) {
            world.sendToast(client, "Bạn không đủ Xu để đặt cược.");
            return;
        }
        arenaInvites.put(targetId, new PendingInvite(p.id, p.name, cleanBet, System.currentTimeMillis() + 30_000L));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "arena_invite");
        req.put("fromId", p.id);
        req.put("fromName", p.name);
        req.put("betXu", cleanBet);
        target.sendJson(req);
        world.sendToast(client, "Đã gửi lời thách đấu Lôi đài tới " + target.getPlayer().name + ".");
    }

    public void arenaAccept(ZoneClient client, long fromId) {
        PlayerState p2 = client.getPlayer();
        PendingInvite inv = arenaInvites.remove(p2.id);
        if (inv == null || inv.fromId != fromId || System.currentTimeMillis() > inv.expireAt) {
            world.sendToast(client, "Lời thách đấu Lôi đài đã hết hạn.");
            return;
        }
        ZoneClient c1 = world.onlinePlayers.get(fromId);
        if (c1 == null || activeArenas.containsKey(fromId) || activeArenas.containsKey(p2.id)) {
            world.sendToast(client, "Không thể bắt đầu trận đấu.");
            return;
        }
        PlayerState p1 = c1.getPlayer();
        long bet = inv.betXu;
        if (p1.xu < bet || p2.xu < bet) {
            world.sendToast(client, "Một trong hai người không đủ Xu đặt cược.");
            return;
        }
        p1.xu -= bet;
        p2.xu -= bet;
        p1.hp = p1.maxHp; p1.mp = p1.maxMp; p1.isDead = false;
        p2.hp = p2.maxHp; p2.mp = p2.maxMp; p2.isDead = false;
        p1.arenaTargetId = (int) p2.id;
        p2.arenaTargetId = (int) p1.id;

        ArenaMatch match = new ArenaMatch(matchIdGen.getAndIncrement(), c1, client, bet);
        activeArenas.put(p1.id, match);
        activeArenas.put(p2.id, match);

        world.changeMap(c1, ARENA_MAP_ID, 190, 264);
        world.changeMap(client, ARENA_MAP_ID, 540, 264);

        Map<String, Object> startPkt = new LinkedHashMap<>();
        startPkt.put("type", "arena_start");
        startPkt.put("matchId", match.matchId);
        startPkt.put("p1Id", p1.id);
        startPkt.put("p1Name", p1.name);
        startPkt.put("p2Id", p2.id);
        startPkt.put("p2Name", p2.name);
        startPkt.put("betXu", bet);
        c1.sendJson(startPkt);
        client.sendJson(startPkt);
        broadcastGlobalToast("Lôi đài Solo: " + p1.name + " vs " + p2.name + " chính thức bắt đầu!");
    }

    public void attackPlayer(ZoneClient attackerClient, GameZone zone, long targetId, int skillId) {
        PlayerState atk = attackerClient.getPlayer();
        if (atk.isDead || atk.id == targetId) {
            return;
        }
        ZoneClient targetClient = zone.players.get(targetId);
        if (targetClient == null) {
            return;
        }
        PlayerState def = targetClient.getPlayer();
        if (def.isDead) {
            return;
        }

        ArenaMatch match = activeArenas.get(atk.id);
        boolean inDuel = match != null && match.contains(atk.id) && match.contains(def.id) && !match.finished;
        if (!inDuel) {
            if (zone.isSafeMap() || zone.gameMap.tileMap.id == ARENA_MAP_ID) {
                zone.sendToast(attackerClient, "Không thể PK trong khu vực an toàn.");
                return;
            }
            if (atk.pkMode != 3 && def.pkMode != 3) {
                zone.sendToast(attackerClient, "Cần bật chế độ Đồ sát hoặc đối phương đang Đồ sát để PK.");
                return;
            }
        }

        Integer pt = atk.skills.get(skillId);
        if (pt == null) {
            skillId = atk.skills.containsKey(atk.selectedSkillId) ? atk.selectedSkillId : 0;
            pt = atk.skills.getOrDefault(skillId, 0);
        }
        SkillTemplate tpl = sd.skillTemplates.get(skillId);
        SkillLevelData sld = tpl != null ? tpl.getByPoint(pt) : null;
        if (sld == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long lastUse = atk.skillCooldowns.getOrDefault(skillId, 0L);
        long cd = Math.max(250L, sld.cooldown - 80L);
        if (now - lastUse < cd || atk.mp < sld.manaUse) {
            return;
        }

        int rangeX = Math.max(90, sld.dx + 140);
        int rangeY = Math.max(90, sld.dy + 130);
        if (Math.abs(def.x - atk.x) > rangeX || Math.abs(def.y - atk.y) > rangeY) {
            return;
        }

        atk.skillCooldowns.put(skillId, now);
        atk.mp = Math.max(0, atk.mp - sld.manaUse);
        atk.dir = (def.x >= atk.x) ? 1 : -1;
        atk.dirty = true;

        int minD = Math.max(1, atk.damage2);
        int maxD = Math.max(minD, atk.damage);
        int raw = (minD == maxD) ? minD : ThreadLocalRandom.current().nextInt(minD, maxD + 1);
        boolean crit = atk.fatal > ThreadLocalRandom.current().nextInt(1000);
        if (crit) {
            raw = raw * 2 + atk.fatalDame;
        }
        // PvP damage balance: trừ giảm sát thương & kháng
        raw -= def.dameDown;
        int dmg = Math.max(1, raw / 3);

        def.hp = Math.max(0, def.hp - dmg);
        def.dirty = true;
        if (def.hp <= 0) {
            def.isDead = true;
        }

        Map<String, Object> castPkt = new LinkedHashMap<>();
        castPkt.put("type", "skill_cast");
        castPkt.put("playerId", atk.id);
        castPkt.put("skillId", skillId);
        castPkt.put("x", atk.x);
        castPkt.put("y", atk.y);
        castPkt.put("dir", atk.dir);
        castPkt.put("targets", List.of());
        zone.broadcast(castPkt, -1);

        Map<String, Object> pvpPkt = new LinkedHashMap<>();
        pvpPkt.put("type", "pvp_hit");
        pvpPkt.put("attackerId", atk.id);
        pvpPkt.put("targetId", def.id);
        pvpPkt.put("damage", dmg);
        pvpPkt.put("crit", crit);
        pvpPkt.put("hp", def.hp);
        pvpPkt.put("maxHp", def.maxHp);
        pvpPkt.put("dead", def.isDead);
        zone.broadcast(pvpPkt, -1);

        zone.sendSelfStats(attackerClient);
        zone.sendSelfStats(targetClient);

        if (def.isDead) {
            if (inDuel) {
                finishArenaDuel(match, attackerClient, targetClient, "Hạ gục đối thủ trên Lôi đài!");
            } else {
                zone.broadcast(Map.of("type", "toast", "text", atk.name + " đã hạ gục " + def.name + "!"), -1);
            }
        }
    }

    public void finishArenaDuel(ArenaMatch match, ZoneClient winnerClient, ZoneClient loserClient, String reason) {
        synchronized (match) {
            if (match.finished) {
                return;
            }
            match.finished = true;
            PlayerState w = winnerClient.getPlayer();
            PlayerState l = loserClient.getPlayer();
            activeArenas.remove(w.id);
            activeArenas.remove(l.id);
            w.arenaTargetId = -1;
            l.arenaTargetId = -1;

            long prize = match.betXu * 2L + 2_000L;
            w.xu += prize;
            w.hp = w.maxHp; w.mp = w.maxMp; w.isDead = false;
            l.hp = l.maxHp; l.mp = l.maxMp; l.isDead = false;
            w.dirty = true;
            l.dirty = true;

            Map<String, Object> resPkt = new LinkedHashMap<>();
            resPkt.put("type", "arena_result");
            resPkt.put("winnerId", w.id);
            resPkt.put("winnerName", w.name);
            resPkt.put("loserId", l.id);
            resPkt.put("loserName", l.name);
            resPkt.put("prizeXu", prize);
            resPkt.put("reason", reason);
            winnerClient.sendJson(resPkt);
            loserClient.sendJson(resPkt);

            if (world.onlinePlayers.containsKey(w.id) && w.mapId == ARENA_MAP_ID) {
                world.changeMap(winnerClient, w.saveMapId > 0 ? w.saveMapId : 22, 228, 192);
            }
            if (world.onlinePlayers.containsKey(l.id) && l.mapId == ARENA_MAP_ID) {
                world.changeMap(loserClient, l.saveMapId > 0 ? l.saveMapId : 22, 228, 192);
            }
            broadcastGlobalToast(w.name + " đã chiến thắng " + l.name + " tại Lôi đài (+" + prize + " Xu)!");
        }
    }

    // =========================================================
    // 4. SĂN BOSS THẾ GIỚI & HANG ĐỘNG
    // =========================================================
    public void sendEventsState(ZoneClient client) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> bosses = new ArrayList<>();
        for (WorldBossSlot slot : bossSlots) {
            TileMapData tm = sd.tileMaps.get(slot.mapId);
            MonsterTemplate tpl = sd.monsters.get(slot.templateId);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("index", slot.index);
            b.put("name", tpl != null ? tpl.name : "Boss");
            b.put("level", slot.bossLevel);
            b.put("mapId", slot.mapId);
            b.put("mapName", tm != null ? tm.name : ("Map " + slot.mapId));
            boolean alive = slot.mob != null && !slot.mob.dead;
            b.put("alive", alive);
            b.put("hp", alive ? slot.mob.hp : 0);
            b.put("maxHp", slot.bossHp);
            b.put("respawnInSec", (!alive && slot.respawnAt > now) ? (slot.respawnAt - now) / 1000L : 0L);
            bosses.add(b);
        }

        List<Map<String, Object>> caves = List.of(
                Map.of("tier", 0, "mapId", 91, "name", "Động Bàn Tơ (Sơ cấp)", "minLevel", 1, "reward", "5.000 Xu + Đá 3 + Rương may mắn"),
                Map.of("tier", 1, "mapId", 94, "name", "Hang Tổ Ong (Trung cấp)", "minLevel", 15, "reward", "10.000 Xu + Đá 4 + Rương may mắn"),
                Map.of("tier", 2, "mapId", 105, "name", "Tam Hợp Sơn Động (Cao cấp)", "minLevel", 25, "reward", "15.000 Xu + Đá 5 + Bảo hiểm"),
                Map.of("tier", 3, "mapId", 114, "name", "Thạch Không Vực (Tổ đội)", "minLevel", 35, "reward", "25.000 Xu + Đá 6 + Rương tinh xảo")
        );

        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "events_data");
        pkt.put("bosses", bosses);
        pkt.put("caves", caves);
        client.sendJson(pkt);
    }

    public void warpToBoss(ZoneClient client, int bossIndex) {
        if (bossIndex < 0 || bossIndex >= bossSlots.size() || client.getPlayer().isDead) {
            return;
        }
        WorldBossSlot slot = bossSlots.get(bossIndex);
        TileMapData tm = sd.tileMaps.get(slot.mapId);
        if (tm == null) {
            return;
        }
        int sx = Math.max(80, Math.min(tm.pxw - 80, slot.spawnX - 100));
        int sy = tm.collisionY(sx, 48);
        world.changeMap(client, slot.mapId, sx, sy);
    }

    public void enterCave(ZoneClient client, int tier) {
        PlayerState p = client.getPlayer();
        if (p.isDead || tier < 0 || tier > 3) {
            return;
        }
        int[] caveMaps = {91, 94, 105, 114};
        int[] minLvs = {1, 15, 25, 35};
        if (p.level < minLvs[tier]) {
            world.sendToast(client, "Cần cấp " + minLvs[tier] + " mới có thể vào cửa ải Hang động này.");
            return;
        }
        int mapId = caveMaps[tier];
        GameMap gm = world.maps.get(mapId);
        if (gm == null) {
            return;
        }
        GameZone z = gm.getZone(0);
        z.ensureInitialized();
        synchronized (z.mobs) {
            if (!z.caveActive) {
                z.caveActive = true;
                z.caveWave = 1;
                z.caveTier = tier;
                z.caveStartedAt = System.currentTimeMillis();
                // Hồi sinh lại quái Wave 1 (cân bằng theo tier) và tắt tự hồi sinh
                int[] capHps = {450, 3_500, 9_000, 18_000};
                int[] capLvs = {10, 20, 32, 45};
                for (Iterator<MobInstance> it = z.mobs.iterator(); it.hasNext();) {
                    MobInstance m = it.next();
                    if (m.isCaveBoss) {
                        it.remove();
                    } else {
                        m.cantRespawn = true;
                        if (m.originalHp > capHps[tier]) {
                            m.customMaxHp = capHps[tier];
                            m.level = capLvs[tier];
                        }
                        m.resetStats(false);
                    }
                }
            }
        }
        int sx = Math.min(180, gm.tileMap.pxw / 2);
        int sy = gm.tileMap.collisionY(sx, 48);
        world.changeMap(client, mapId, sx, sy);
        world.sendToast(client, "Vào " + gm.tileMap.name + " (Đợt 1: Tiêu diệt toàn bộ quái để gọi Boss!).");
    }

    public void leaveCave(ZoneClient client) {
        PlayerState p = client.getPlayer();
        if (p.isDead) {
            return;
        }
        world.changeMap(client, p.saveMapId > 0 ? p.saveMapId : 22, 228, 192);
    }

    public void onMobKilled(ZoneClient killerClient, GameZone zone, MobInstance mob) {
        PlayerState killer = killerClient.getPlayer();
        long now = System.currentTimeMillis();

        // 1. World Boss chết
        if (mob.isWorldBoss && mob.worldBossIndex >= 0 && mob.worldBossIndex < bossSlots.size()) {
            WorldBossSlot slot = bossSlots.get(mob.worldBossIndex);
            slot.respawnAt = now + 300_000L; // Hồi sinh sau 5 phút
            broadcastGlobalToast(killer.name + " đã tiêu diệt Boss Thế Giới [" + mob.name + "] tại " + zone.gameMap.tileMap.name + "!");
            zone.spawnDrop(killer.id, mob.x - 16, mob.y, new ItemInstance(3 + Math.min(3, slot.index), 1)); // Đá cấp 4..7
            zone.spawnDrop(killer.id, mob.x + 16, mob.y, new ItemInstance(272, 1)); // Rương may mắn
            if (slot.index >= 1) {
                zone.spawnDrop(killer.id, mob.x, mob.y, new ItemInstance(242, 1)); // Bảo hiểm sơ cấp
            }
            return;
        }

        // 2. Hang động Wave progression
        if (zone.caveActive) {
            if (mob.isCaveBoss) {
                zone.caveActive = false;
                int tier = zone.caveTier;
                long bonusXu = (tier + 1L) * 5_000L;
                int stoneId = Math.min(6, tier + 2);
                int chestId = tier >= 3 ? 282 : 272;
                for (ZoneClient zc : zone.players.values()) {
                    PlayerState zp = zc.getPlayer();
                    zp.xu += bonusXu;
                    zp.yen += bonusXu;
                    zone.addItemToBag(zp, new ItemInstance(stoneId, 1));
                    zone.addItemToBag(zp, new ItemInstance(chestId, 1));
                    zp.dirty = true;
                    zone.sendToast(zc, "Chinh phục Hang động thành công! Nhận +" + bonusXu + " Xu/Yên, Đá cấp " + (stoneId + 1) + " & Rương báu!");
                    zone.sendSelfFullState(zc);
                    world.savePlayerAsync(zp);
                }
                broadcastGlobalToast("Nhóm chiến binh tại " + zone.gameMap.tileMap.name + " đã hạ gục Boss Hang Động!");
                return;
            }

            // Kiểm tra xem còn quái nào sống trong Đợt 1 không
            boolean anyAlive = false;
            synchronized (zone.mobs) {
                for (MobInstance m : zone.mobs) {
                    if (!m.dead && !m.isCaveBoss) {
                        anyAlive = true;
                        break;
                    }
                }
                if (!anyAlive && zone.caveWave == 1) {
                    zone.caveWave = 2;
                    int[] bossTplIds = {115, 114, 116, 138};
                    int[] bossHps = {2_500, 15_000, 35_000, 65_000};
                    int[] bossLvs = {12, 25, 35, 50};
                    int t = Math.max(0, Math.min(3, zone.caveTier));
                    MonsterTemplate tpl = sd.monsters.get(bossTplIds[t]);
                    if (tpl != null) {
                        int bx = zone.gameMap.tileMap.pxw / 2;
                        int by = zone.gameMap.tileMap.collisionY(bx, 48);
                        MobInstance boss = new MobInstance(zone.mobUidGen.getAndIncrement(), tpl, bx, by, true, bossLvs[t], bossHps[t]);
                        boss.cantRespawn = true;
                        boss.isCaveBoss = true;
                        zone.mobs.add(boss);
                        Map<String, Object> pkt = new LinkedHashMap<>();
                        pkt.put("type", "mob_respawn");
                        pkt.put("mob", boss.toDto());
                        zone.broadcast(pkt, -1);
                        zone.broadcast(Map.of("type", "toast", "text", "Đợt 2: Boss Hang Động [" + tpl.name + "] đã xuất hiện!"), -1);
                    }
                }
            }
        }
    }

    // =========================================================
    // 5. GIA TỘC (DB-Backed Clans + Clan Chat)
    // =========================================================
    public void sendClanInfo(ZoneClient client) {
        PlayerState p = client.getPlayer();
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "clan_data");
        try {
            if (p.clanId != null) {
                ClanInfoDto dto = clanRepo.loadClan(p.clanId, world.onlinePlayers.keySet());
                pkt.put("clan", dto);
            } else {
                pkt.put("clan", null);
            }
            pkt.put("clans", clanRepo.listClans());
        } catch (SQLException e) {
            log.error("Failed loading clan data", e);
            world.sendToast(client, "Không thể tải dữ liệu Gia tộc.");
            return;
        }
        client.sendJson(pkt);
    }

    public void createClan(ZoneClient client, GameZone zone, String clanName) {
        PlayerState p = client.getPlayer();
        if (p.clanId != null) {
            zone.sendToast(client, "Bạn đã có Gia tộc rồi.");
            return;
        }
        if (p.xu < CLAN_CREATE_COST_XU) {
            zone.sendToast(client, "Cần " + CLAN_CREATE_COST_XU + " Xu để thành lập Gia tộc.");
            return;
        }
        try {
            ClanInfoDto created = clanRepo.createClan(p, clanName, CLAN_CREATE_COST_XU);
            p.dirty = true;
            zone.broadcastPlayerAppearance(p);
            zone.sendSelfFullState(client);
            zone.sendToast(client, "Đã thành lập Gia tộc [" + created.name + "] thành công!");
            sendClanInfo(client);
        } catch (IllegalArgumentException ex) {
            zone.sendToast(client, ex.getMessage());
        } catch (SQLException ex) {
            log.error("Create clan error", ex);
            zone.sendToast(client, "Tên gia tộc không hợp lệ hoặc đã được sử dụng.");
        }
    }

    public void clanInvite(ZoneClient client, String targetNameOrId) {
        PlayerState leader = client.getPlayer();
        if (leader.clanId == null || leader.clanName == null) {
            world.sendToast(client, "Bạn chưa gia nhập Gia tộc nào.");
            return;
        }
        ZoneClient target = findOnlinePlayer(targetNameOrId);
        if (target == null || target.getPlayerId() == leader.id) {
            world.sendToast(client, "Không tìm thấy người chơi trực tuyến.");
            return;
        }
        if (target.getPlayer().clanId != null) {
            world.sendToast(client, target.getPlayer().name + " đã thuộc Gia tộc khác.");
            return;
        }
        clanInvites.put(target.getPlayerId(),
                new PendingClanInvite(leader.clanId, leader.clanName, leader.name, System.currentTimeMillis() + 30_000L));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "clan_invite");
        req.put("clanId", leader.clanId);
        req.put("clanName", leader.clanName);
        req.put("fromName", leader.name);
        target.sendJson(req);
        world.sendToast(client, "Đã gửi lời mời vào Gia tộc tới " + target.getPlayer().name + ".");
    }

    public void clanAccept(ZoneClient client, long clanId) {
        PlayerState p = client.getPlayer();
        PendingClanInvite inv = clanInvites.remove(p.id);
        if (inv == null || inv.clanId != clanId || System.currentTimeMillis() > inv.expireAt) {
            world.sendToast(client, "Lời mời vào Gia tộc đã hết hạn.");
            return;
        }
        if (p.clanId != null) {
            world.sendToast(client, "Bạn đã có Gia tộc rồi.");
            return;
        }
        try {
            clanRepo.addMember(inv.clanId, inv.clanName, p);
            p.dirty = true;
            GameZone z = world.getZoneFor(p.id);
            if (z != null) {
                z.broadcastPlayerAppearance(p);
                z.sendSelfFullState(client);
            }
            broadcastClanSystem(inv.clanId, p.name + " đã gia nhập Gia tộc!");
            sendClanInfo(client);
        } catch (SQLException e) {
            log.error("Accept clan error", e);
            world.sendToast(client, "Không thể gia nhập Gia tộc.");
        }
    }

    public void clanLeave(ZoneClient client) {
        PlayerState p = client.getPlayer();
        if (p.clanId == null) {
            return;
        }
        long oldClanId = p.clanId;
        String oldName = p.clanName;
        try {
            clanRepo.leaveClan(p.id, oldClanId);
            p.clanId = null;
            p.clanName = null;
            p.dirty = true;
            GameZone z = world.getZoneFor(p.id);
            if (z != null) {
                z.broadcastPlayerAppearance(p);
                z.sendSelfFullState(client);
            }
            broadcastClanSystem(oldClanId, p.name + " đã rời Gia tộc.");
            world.sendToast(client, "Bạn đã rời Gia tộc [" + oldName + "].");
            sendClanInfo(client);
        } catch (SQLException e) {
            log.error("Leave clan error", e);
        }
    }

    public void clanKick(ZoneClient client, long targetPlayerId) {
        PlayerState leader = client.getPlayer();
        if (leader.clanId == null || leader.id == targetPlayerId) {
            return;
        }
        try {
            ClanInfoDto info = clanRepo.loadClan(leader.clanId, world.onlinePlayers.keySet());
            if (info == null || !leader.name.equals(info.leaderName)) {
                world.sendToast(client, "Chỉ Tộc trưởng mới có quyền mời thành viên rời tộc.");
                return;
            }
            if (info.members.stream().noneMatch(member -> member.playerId == targetPlayerId)) {
                world.sendToast(client, "Người chơi không thuộc Gia tộc của bạn.");
                return;
            }
            clanRepo.removeMember(targetPlayerId);
            ZoneClient kicked = world.onlinePlayers.get(targetPlayerId);
            if (kicked != null) {
                kicked.getPlayer().clanId = null;
                kicked.getPlayer().clanName = null;
                kicked.getPlayer().dirty = true;
                GameZone z = world.getZoneFor(targetPlayerId);
                if (z != null) {
                    z.broadcastPlayerAppearance(kicked.getPlayer());
                    z.sendSelfFullState(kicked);
                }
                world.sendToast(kicked, "Bạn đã bị mời khỏi Gia tộc.");
            }
            sendClanInfo(client);
        } catch (SQLException e) {
            log.error("Kick clan member error", e);
        }
    }

    public void clanDonate(ZoneClient client, long amount) {
        PlayerState p = client.getPlayer();
        if (p.clanId == null || amount <= 0 || amount > p.xu) {
            world.sendToast(client, "Số Xu đóng góp không hợp lệ.");
            return;
        }
        try {
            clanRepo.donateCoin(p, amount);
            p.dirty = true;
            GameZone z = world.getZoneFor(p.id);
            if (z != null) {
                z.sendSelfFullState(client);
            }
            broadcastClanSystem(p.clanId, p.name + " đã đóng góp " + amount + " Xu vào quỹ Gia tộc!");
            sendClanInfo(client);
        } catch (SQLException e) {
            log.error("Donate clan error", e);
        }
    }

    public void clanSetAlert(ZoneClient client, String alert) {
        PlayerState p = client.getPlayer();
        if (p.clanId == null || alert == null) {
            return;
        }
        try {
            ClanInfoDto info = clanRepo.loadClan(p.clanId, world.onlinePlayers.keySet());
            if (info == null || !p.name.equals(info.leaderName)) {
                world.sendToast(client, "Chỉ Tộc trưởng mới sửa được thông báo.");
                return;
            }
            String clean = alert.trim();
            if (clean.length() > 140) {
                clean = clean.substring(0, 140);
            }
            clanRepo.updateAlert(p.clanId, clean);
            sendClanInfo(client);
        } catch (SQLException e) {
            log.error("Update clan alert error", e);
        }
    }

    public void clanChat(ZoneClient client, String text) {
        PlayerState p = client.getPlayer();
        if (p.clanId == null) {
            world.sendToast(client, "Bạn chưa có Gia tộc.");
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        String clean = text.trim();
        if (clean.length() > 140) {
            clean = clean.substring(0, 140);
        }
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "chat_msg");
        pkt.put("channel", "clan");
        pkt.put("clanId", p.clanId);
        pkt.put("playerId", p.id);
        pkt.put("playerName", p.name);
        pkt.put("text", clean);
        for (ZoneClient zc : world.onlinePlayers.values()) {
            if (Objects.equals(zc.getPlayer().clanId, p.clanId)) {
                zc.sendJson(pkt);
            }
        }
    }

    // =========================================================
    // 6. CÀI ĐẶT AUTO & CLEANUP LIFECYCLE
    // =========================================================
    public void saveAutoSettings(ZoneClient client, int hpPct, int mpPct, boolean pickupAll) {
        PlayerState p = client.getPlayer();
        p.autoSettings.hpThreshold = Math.max(0, Math.min(95, hpPct));
        p.autoSettings.mpThreshold = Math.max(0, Math.min(95, mpPct));
        p.autoSettings.pickupAll = pickupAll;
        p.dirty = true;
        GameZone z = world.getZoneFor(p.id);
        if (z != null) {
            z.sendSelfFullState(client);
        }
    }

    public void onPlayerDisconnect(ZoneClient client) {
        long pid = client.getPlayerId();
        tradeInvites.remove(pid);
        arenaInvites.remove(pid);
        clanInvites.remove(pid);

        TradeSession trade = activeTrades.get(pid);
        if (trade != null) {
            synchronized (trade) {
                abortTrade(trade, client.getPlayer().name + " đã mất kết nối.");
            }
        }
        ArenaMatch match = activeArenas.get(pid);
        if (match != null) {
            ZoneClient other = (match.p1.getPlayerId() == pid) ? match.p2 : match.p1;
            finishArenaDuel(match, other, client, client.getPlayer().name + " đã thoát trận.");
        }
    }

    public void onPlayerChangeMap(ZoneClient client, int nextMapId) {
        long pid = client.getPlayerId();
        TradeSession trade = activeTrades.get(pid);
        if (trade != null) {
            synchronized (trade) {
                abortTrade(trade, client.getPlayer().name + " đã chuyển bản đồ.");
            }
        }
        ArenaMatch match = activeArenas.get(pid);
        if (match != null && !match.finished && nextMapId != ARENA_MAP_ID) {
            ZoneClient other = (match.p1.getPlayerId() == pid) ? match.p2 : match.p1;
            finishArenaDuel(match, other, client, client.getPlayer().name + " rời khỏi Lôi đài.");
        }
    }

    private ZoneClient findOnlinePlayer(String targetNameOrId) {
        if (targetNameOrId == null || targetNameOrId.isBlank()) {
            return null;
        }
        try {
            long pid = Long.parseLong(targetNameOrId.trim());
            ZoneClient byId = world.onlinePlayers.get(pid);
            if (byId != null) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
        }
        for (ZoneClient zc : world.onlinePlayers.values()) {
            if (zc.getPlayer().name.equalsIgnoreCase(targetNameOrId.trim())) {
                return zc;
            }
        }
        return null;
    }

    private void broadcastGlobalToast(String msg) {
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "toast");
        pkt.put("text", msg);
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("type", "chat_msg");
        sys.put("channel", "system");
        sys.put("playerName", "Hệ thống");
        sys.put("text", msg);
        for (ZoneClient zc : world.onlinePlayers.values()) {
            zc.sendJson(pkt);
            zc.sendJson(sys);
        }
    }

    private void broadcastClanSystem(long clanId, String msg) {
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("type", "chat_msg");
        sys.put("channel", "clan");
        sys.put("clanId", clanId);
        sys.put("playerName", "Gia tộc");
        sys.put("text", msg);
        for (ZoneClient zc : world.onlinePlayers.values()) {
            if (Objects.equals(zc.getPlayer().clanId, clanId)) {
                zc.sendJson(sys);
            }
        }
    }

    // Inner state models
    private static class PendingInvite {
        final long fromId;
        final String fromName;
        final long betXu;
        final long expireAt;

        PendingInvite(long fromId, String fromName, long betXu, long expireAt) {
            this.fromId = fromId;
            this.fromName = fromName;
            this.betXu = betXu;
            this.expireAt = expireAt;
        }
    }

    private static class PendingClanInvite {
        final long clanId;
        final String clanName;
        final String fromName;
        final long expireAt;

        PendingClanInvite(long clanId, String clanName, String fromName, long expireAt) {
            this.clanId = clanId;
            this.clanName = clanName;
            this.fromName = fromName;
            this.expireAt = expireAt;
        }
    }

    private static class TraderSide {
        final ZoneClient client;
        long xuOffer = 0;
        List<Integer> bagIndices = new ArrayList<>();
        List<String> offeredItems = new ArrayList<>();
        boolean locked = false;
        boolean confirmed = false;

        TraderSide(ZoneClient client) {
            this.client = client;
        }
    }

    private static class TradeSession {
        final TraderSide a;
        final TraderSide b;
        boolean finished = false;

        TradeSession(ZoneClient ca, ZoneClient cb) {
            this.a = new TraderSide(ca);
            this.b = new TraderSide(cb);
        }

        TraderSide sideOf(long pid) {
            return a.client.getPlayerId() == pid ? a : b;
        }
    }

    public static class ArenaMatch {
        public final int matchId;
        public final ZoneClient p1;
        public final ZoneClient p2;
        public final long betXu;
        public boolean finished = false;

        public ArenaMatch(int matchId, ZoneClient p1, ZoneClient p2, long betXu) {
            this.matchId = matchId;
            this.p1 = p1;
            this.p2 = p2;
            this.betXu = betXu;
        }

        public boolean contains(long pid) {
            return p1.getPlayerId() == pid || p2.getPlayerId() == pid;
        }
    }

    public static class WorldBossSlot {
        public final int index;
        public final int templateId;
        public final int bossLevel;
        public final int bossHp;
        public final int mapId;
        public final int spawnX;
        public volatile MobInstance mob;
        public volatile GameZone zone;
        public volatile long respawnAt = 0;

        public WorldBossSlot(int index, int templateId, int bossLevel, int bossHp, int mapId, int spawnX) {
            this.index = index;
            this.templateId = templateId;
            this.bossLevel = bossLevel;
            this.bossHp = bossHp;
            this.mapId = mapId;
            this.spawnX = spawnX;
        }
    }
}
