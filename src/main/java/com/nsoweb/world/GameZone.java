package com.nsoweb.world;

import com.nsoweb.model.ItemInstance;
import com.nsoweb.model.PlayerCalculator;
import com.nsoweb.model.PlayerState;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class GameZone {
    public final GameMap gameMap;
    public final int zoneId;
    public final StaticData sd;
    public final WorldManager world;

    public final Map<Long, ZoneClient> players = new ConcurrentHashMap<>();
    public final List<MobInstance> mobs = new CopyOnWriteArrayList<>();
    public final Map<Integer, DropItemInstance> drops = new ConcurrentHashMap<>();
    private final AtomicInteger dropUidGen = new AtomicInteger(1);
    public final AtomicInteger mobUidGen = new AtomicInteger(0);

    public volatile boolean caveActive = false;
    public volatile int caveWave = 1;
    public volatile int caveTier = 0;
    public volatile long caveStartedAt = 0;

    private volatile boolean initialized = false;
    private long lastWanderAt = 0;

    public GameZone(GameMap gameMap, int zoneId, WorldManager world) {
        this.gameMap = gameMap;
        this.zoneId = zoneId;
        this.sd = world.staticData;
        this.world = world;
    }

    public synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        synchronized (mobs) {
            for (MobSpawnPoint sp : gameMap.tileMap.mobs) {
                MonsterTemplate tpl = sd.monsters.get(sp.templateId);
                if (tpl == null) {
                    continue;
                }
                int sy = gameMap.tileMap.collisionY(sp.x, sp.y);
                MobInstance mob = new MobInstance(mobUidGen.getAndIncrement(), tpl, sp.x, sp.y > 0 ? Math.min(sp.y, sy) : sy, sp.boss);
                mobs.add(mob);
            }
        }
    }

    public MobInstance findMobByUid(int uid) {
        if (uid >= 0 && uid < mobs.size()) {
            MobInstance fast = mobs.get(uid);
            if (fast.uid == uid) {
                return fast;
            }
        }
        for (MobInstance m : mobs) {
            if (m.uid == uid) {
                return m;
            }
        }
        return null;
    }

    public boolean isSafeMap() {
        int id = gameMap.tileMap.id;
        return id == 1 || id == 10 || id == 17 || id == 22 || id == 27 || id == 32
                || id == 38 || id == 43 || id == 48 || id == 72 || id == 138;
    }

    public void join(ZoneClient client) {
        ensureInitialized();
        PlayerState p = client.getPlayer();
        TileMapData tm = gameMap.tileMap;
        p.mapId = tm.id;
        p.zoneId = zoneId;
        p.x = Math.max(24, Math.min(tm.pxw - 24, p.x));
        p.y = Math.max(24, Math.min(tm.pxh - 24, p.y));
        if (!isSafeMap() && tm.mapType == 0) {
            // Giữ điểm hồi sinh ở các làng/trường gần nhất (những map safe)
        } else if (isSafeMap()) {
            p.saveMapId = tm.id;
        }

        // Báo cho người chơi khác trong khu
        Map<String, Object> joinPkt = new LinkedHashMap<>();
        joinPkt.put("type", "player_join");
        joinPkt.put("player", toPublicPlayerDto(p));
        broadcast(joinPkt, p.id);

        players.put(p.id, client);

        // Gửi toàn bộ trạng thái khu về client
        List<Map<String, Object>> otherPlayers = new ArrayList<>();
        for (ZoneClient other : players.values()) {
            otherPlayers.add(toPublicPlayerDto(other.getPlayer()));
        }
        List<Map<String, Object>> mobDtos = new ArrayList<>(mobs.size());
        for (MobInstance m : mobs) {
            mobDtos.add(m.toDto());
        }
        List<Map<String, Object>> dropDtos = new ArrayList<>(drops.size());
        for (DropItemInstance d : drops.values()) {
            dropDtos.add(d.toDto());
        }

        Map<String, Object> enterPkt = new LinkedHashMap<>();
        enterPkt.put("type", "map_enter");
        enterPkt.put("mapId", tm.id);
        enterPkt.put("mapName", tm.name);
        enterPkt.put("zoneId", zoneId);
        enterPkt.put("zoneCount", gameMap.zones.size());
        enterPkt.put("safe", isSafeMap());
        enterPkt.put("self", toSelfPlayerDto(p));
        enterPkt.put("players", otherPlayers);
        enterPkt.put("mobs", mobDtos);
        enterPkt.put("drops", dropDtos);
        client.sendJson(enterPkt);
    }

    public void leave(ZoneClient client) {
        PlayerState p = client.getPlayer();
        players.remove(p.id, client);
        for (MobInstance m : mobs) {
            m.aggroPlayers.remove(p.id);
        }
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "player_leave");
        pkt.put("playerId", p.id);
        broadcast(pkt, p.id);
    }

    public void move(ZoneClient client, int x, int y, int dir, int action) {
        PlayerState p = client.getPlayer();
        if (p.isDead) {
            return;
        }
        TileMapData tm = gameMap.tileMap;
        int nx = Math.max(12, Math.min(tm.pxw - 12, x));
        int ny = Math.max(12, Math.min(tm.pxh - 12, y));
        p.x = nx;
        p.y = ny;
        p.dir = dir >= 0 ? 1 : -1;
        p.action = Math.max(0, Math.min(30, action));
        p.dirty = true;

        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "player_move");
        pkt.put("playerId", p.id);
        pkt.put("x", nx);
        pkt.put("y", ny);
        pkt.put("dir", p.dir);
        pkt.put("action", p.action);
        broadcast(pkt, p.id);
    }

    public void chat(ZoneClient client, String text) {
        if (text == null) {
            return;
        }
        String clean = text.trim();
        if (clean.isEmpty()) {
            return;
        }
        if (clean.length() > 140) {
            clean = clean.substring(0, 140);
        }
        PlayerState p = client.getPlayer();
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "chat_msg");
        pkt.put("channel", "map");
        pkt.put("playerId", p.id);
        pkt.put("playerName", p.name);
        pkt.put("text", clean);
        broadcast(pkt, -1);
    }

    public void attackMobs(ZoneClient client, int skillId, List<Integer> targetUids) {
        PlayerState p = client.getPlayer();
        if (p.isDead) {
            return;
        }
        Integer pt = p.skills.get(skillId);
        if (pt == null) {
            skillId = p.skills.containsKey(p.selectedSkillId) ? p.selectedSkillId : 0;
            pt = p.skills.getOrDefault(skillId, 0);
        }
        SkillTemplate tpl = sd.skillTemplates.get(skillId);
        if (tpl == null) {
            return;
        }
        SkillLevelData sld = tpl.getByPoint(pt);
        if (sld == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastUse = p.skillCooldowns.getOrDefault(skillId, 0L);
        long cd = Math.max(250L, sld.cooldown - 80L);
        if (now - lastUse < cd) {
            return;
        }
        if (p.mp < sld.manaUse) {
            sendToast(client, "Không đủ MP để dùng chiêu thức.");
            return;
        }

        if (p.selectedSkillId != skillId) {
            p.selectedSkillId = skillId;
            PlayerCalculator.recalculateStats(p, sd);
        }
        p.skillCooldowns.put(skillId, now);
        p.mp = Math.max(0, p.mp - sld.manaUse);
        p.dirty = true;

        int maxTargets = Math.max(1, sld.maxFight);
        int rangeX = Math.max(80, sld.dx + 130);
        int rangeY = Math.max(80, sld.dy + 120);

        List<MobInstance> hitMobs = new ArrayList<>();
        if (targetUids != null) {
            for (Integer uid : targetUids) {
                if (uid == null) {
                    continue;
                }
                MobInstance mob = findMobByUid(uid);
                if (mob == null || mob.dead) {
                    continue;
                }
                if (Math.abs(mob.x - p.x) <= rangeX && Math.abs(mob.y - p.y) <= rangeY) {
                    hitMobs.add(mob);
                    if (hitMobs.size() >= maxTargets) {
                        break;
                    }
                }
            }
        }

        // Nếu client gửi rỗng nhưng bấm đánh, tìm quái gần nhất trong tầm trước mặt
        if (hitMobs.isEmpty()) {
            MobInstance nearest = null;
            int bestDist = Integer.MAX_VALUE;
            for (MobInstance mob : mobs) {
                if (mob.dead) {
                    continue;
                }
                if (Math.abs(mob.x - p.x) <= rangeX && Math.abs(mob.y - p.y) <= rangeY) {
                    int d = Math.abs(mob.x - p.x) + Math.abs(mob.y - p.y);
                    if (d < bestDist) {
                        bestDist = d;
                        nearest = mob;
                    }
                }
            }
            if (nearest != null) {
                hitMobs.add(nearest);
            }
        }

        List<Integer> targetIdList = new ArrayList<>(hitMobs.size());
        for (MobInstance m : hitMobs) {
            targetIdList.add(m.uid);
        }

        Map<String, Object> castPkt = new LinkedHashMap<>();
        castPkt.put("type", "skill_cast");
        castPkt.put("playerId", p.id);
        castPkt.put("skillId", skillId);
        castPkt.put("x", p.x);
        castPkt.put("y", p.y);
        castPkt.put("dir", p.dir);
        castPkt.put("targets", targetIdList);
        broadcast(castPkt, -1);

        if (hitMobs.isEmpty()) {
            sendSelfStats(client);
            return;
        }

        long totalExpGained = 0;
        List<Map<String, Object>> hitResults = new ArrayList<>();
        List<MobInstance> killedMobs = new ArrayList<>();
        boolean anyDropOrLevel = false;

        for (MobInstance mob : hitMobs) {
            synchronized (mob) {
                if (mob.dead) {
                    continue;
                }
                int minD = Math.max(1, p.damage2);
                int maxD = Math.max(minD, p.damage);
                int dmg = (minD == maxD) ? minD : ThreadLocalRandom.current().nextInt(minD, maxD + 1);
                boolean crit = p.fatal > ThreadLocalRandom.current().nextInt(1000);
                if (crit) {
                    dmg = dmg * 2 + p.fatalDame;
                }

                // Khắc hệ (Hỏa > Phong > Băng > Hỏa nhẹ 10%)
                int sys = p.getSys();
                if ((sys == 1 && mob.sys == 3) || (sys == 3 && mob.sys == 2) || (sys == 2 && mob.sys == 1)) {
                    dmg += dmg / 10;
                }

                if (mob.templateId == 0) {
                    dmg = Math.max(dmg, mob.maxHp / 5);
                }

                int preHp = mob.hp;
                mob.hp = Math.max(0, mob.hp - dmg);
                int hpLost = Math.max(1, preHp - mob.hp);

                long expGain = computeMobExp(p, mob, hpLost);
                totalExpGained += expGain;

                if (mob.hp <= 0) {
                    mob.dead = true;
                    mob.respawnAt = now + (mob.boss ? 300_000L : 12_000L);
                    mob.aggroPlayers.clear();
                    killedMobs.add(mob);
                    if (mob.templateId != 0) {
                        spawnDropsOnKill(p, mob);
                    }
                    anyDropOrLevel = true;
                } else if (mob.templateId != 0) {
                    mob.aggroPlayers.put(p.id, now);
                    // Xoay hướng quái về người đánh
                    mob.dir = (p.x >= mob.x) ? 1 : -1;
                }

                Map<String, Object> hr = new LinkedHashMap<>();
                hr.put("uid", mob.uid);
                hr.put("damage", dmg);
                hr.put("crit", crit);
                hr.put("hp", mob.hp);
                hr.put("maxHp", mob.maxHp);
                hr.put("dead", mob.dead);
                hr.put("dir", mob.dir);
                hitResults.add(hr);
            }
        }

        if (totalExpGained > 0) {
            int levels = PlayerCalculator.addExp(p, totalExpGained, sd);
            if (levels > 0) {
                anyDropOrLevel = true;
                Map<String, Object> lvPkt = new LinkedHashMap<>();
                lvPkt.put("type", "level_up");
                lvPkt.put("playerId", p.id);
                lvPkt.put("level", p.level);
                broadcast(lvPkt, -1);
                PlayerOps.onLevelChanged(client, this);
                world.savePlayerAsync(p);
            }
        }

        for (MobInstance km : killedMobs) {
            PlayerOps.onMobKilled(client, this, km);
            world.endgame.onMobKilled(client, this, km);
        }

        Map<String, Object> dmgPkt = new LinkedHashMap<>();
        dmgPkt.put("type", "damage_popup");
        dmgPkt.put("attackerId", p.id);
        dmgPkt.put("skillId", skillId);
        dmgPkt.put("results", hitResults);
        dmgPkt.put("expGain", totalExpGained);
        broadcast(dmgPkt, -1);

        sendSelfStats(client);
        if (anyDropOrLevel) {
            p.dirty = true;
        }
    }

    private long computeMobExp(PlayerState p, MobInstance mob, int hpLost) {
        if (mob.templateId == 0) {
            return 1L;
        }
        int dLevel = Math.abs(mob.level - p.level);
        if (dLevel > 15) {
            return 1L;
        }
        int a = Math.max(1, p.level / 20);
        int b = hpLost * a;
        int c = Math.max(-45, (mob.level - p.level) * 5);
        long exp = ((b / 2L) + (b * c / 100L)) * world.expRate;
        if (mob.boss) {
            exp *= 6;
        } else if (mob.levelBoss == 1) {
            exp *= 2;
        } else if (mob.levelBoss == 2) {
            exp *= 4;
        }
        return Math.max(1L, exp);
    }

    private void spawnDropsOnKill(PlayerState killer, MobInstance mob) {
        if (drops.size() > 80) {
            return;
        }
        int dLevel = Math.abs(mob.level - killer.level);
        if (!mob.boss && dLevel > 15) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // Tỉ lệ rơi Yên (25%, boss rơi nhiều đống)
        if (mob.boss || rnd.nextInt(100) < 28) {
            int piles = mob.boss ? 5 : 1;
            for (int i = 0; i < piles; i++) {
                int yenQty = rnd.nextInt(mob.level * 15, (mob.level + 10) * 15 + 1);
                if (mob.boss) {
                    yenQty = 10000;
                } else if (mob.levelBoss == 1) {
                    yenQty *= 10;
                } else if (mob.levelBoss == 2) {
                    yenQty *= 20;
                }
                spawnDrop(killer.id, mob.x + rnd.nextInt(-22, 23), mob.y, new ItemInstance(12, yenQty));
            }
        }

        // Tỉ lệ rơi vật phẩm (18% HP/MP/Đá/Thức ăn; 3% trang bị)
        int roll = rnd.nextInt(100);
        if (mob.boss || roll < 22) {
            int itemId;
            if (roll < 3 && !sd.weaponStore.isEmpty()) {
                // Rơi ngẫu nhiên 1 món đồ trong cửa hàng phù hợp cấp
                List<StoreDataItem> candidates = new ArrayList<>();
                for (StoreDataItem w : sd.weaponStore) {
                    ItemTemplate it = sd.items.get(w.itemId);
                    if (it != null && it.level <= mob.level + 5) {
                        candidates.add(w);
                    }
                }
                if (!candidates.isEmpty()) {
                    StoreDataItem pick = candidates.get(rnd.nextInt(candidates.size()));
                    ItemInstance eq = new ItemInstance(pick.itemId, 1);
                    eq.sys = pick.sys;
                    for (int[] o : pick.options) {
                        eq.options.add(new int[]{o[0], o[1]});
                    }
                    spawnDrop(killer.id, mob.x + rnd.nextInt(-20, 21), mob.y, eq);
                    return;
                }
            }
            int typeRoll = rnd.nextInt(100);
            if (typeRoll < 35) {
                itemId = potionForLevel(mob.level, true);
            } else if (typeRoll < 65) {
                itemId = potionForLevel(mob.level, false);
            } else if (typeRoll < 85) {
                itemId = Math.min(4, mob.level / 15); // Đá cấp 1..5
            } else {
                itemId = 23 + Math.min(4, mob.level / 20); // Cơm nắm / thức ăn
            }
            ItemInstance it = new ItemInstance(itemId, 1);
            if (itemId < 12 && !mob.boss && mob.levelBoss == 0) {
                it.locked = true;
            }
            spawnDrop(killer.id, mob.x + rnd.nextInt(-20, 21), mob.y, it);
        }
    }

    private int potionForLevel(int level, boolean isHp) {
        int base = isHp ? 13 : 18;
        if (level >= 70) {
            return base + 4;
        }
        if (level >= 40) {
            return base + 2;
        }
        if (level >= 20) {
            return base + 1;
        }
        return base;
    }

    public void spawnDrop(long ownerId, int rawX, int rawY, ItemInstance item) {
        TileMapData tm = gameMap.tileMap;
        int x = Math.max(30, Math.min(tm.pxw - 30, rawX));
        int y = tm.collisionY(x, Math.max(0, rawY - 12));
        int uid = dropUidGen.getAndIncrement();
        DropItemInstance drop = new DropItemInstance(uid, x, y, item, ownerId);
        drops.put(uid, drop);

        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "drop_spawn");
        pkt.put("drop", drop.toDto());
        broadcast(pkt, -1);
    }

    public void pickupItem(ZoneClient client, int dropUid) {
        DropItemInstance drop = drops.get(dropUid);
        if (drop == null) {
            return;
        }
        PlayerState p = client.getPlayer();
        if (p.isDead) {
            return;
        }
        long now = System.currentTimeMillis();
        if (drop.ownerId != -1 && drop.ownerId != p.id && now - drop.createdAt < 15_000L) {
            sendToast(client, "Vật phẩm của người khác.");
            return;
        }
        if (Math.abs(p.x - drop.x) > 150 || Math.abs(p.y - drop.y) > 150) {
            return;
        }

        if (drop.item.itemId == 12) {
            if (!drops.remove(dropUid, drop)) {
                return;
            }
            p.yen += drop.item.quantity;
            p.dirty = true;
            sendToast(client, "Nhặt được " + drop.item.quantity + " Yên.");
        } else {
            if (!canAddToBag(p, drop.item)) {
                sendToast(client, "Hành trang đã đầy.");
                return;
            }
            if (!drops.remove(dropUid, drop)) {
                return;
            }
            addItemToBag(p, drop.item);
            p.dirty = true;
            ItemTemplate tpl = sd.items.get(drop.item.itemId);
            String name = tpl != null ? tpl.name : ("Vật phẩm #" + drop.item.itemId);
            sendToast(client, "Nhặt được " + name + ".");
        }

        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "drop_remove");
        pkt.put("uid", dropUid);
        pkt.put("pickerId", p.id);
        broadcast(pkt, -1);
        sendSelfFullState(client);
    }

    public boolean canAddToBag(PlayerState p, ItemInstance item) {
        ItemTemplate tpl = sd.items.get(item.itemId);
        if (tpl != null && tpl.isUpToUp) {
            for (ItemInstance exist : p.bag) {
                if (exist.itemId == item.itemId && exist.locked == item.locked && exist.expire == item.expire) {
                    return true;
                }
            }
        }
        return p.bag.size() < p.bagCapacity;
    }

    public void addItemToBag(PlayerState p, ItemInstance item) {
        ItemTemplate tpl = sd.items.get(item.itemId);
        if (tpl != null && tpl.isUpToUp) {
            for (ItemInstance exist : p.bag) {
                if (exist.itemId == item.itemId && exist.locked == item.locked && exist.expire == item.expire) {
                    exist.quantity += item.quantity;
                    return;
                }
            }
        }
        p.bag.add(item);
    }

    public void revive(ZoneClient client, boolean atSpot) {
        PlayerState p = client.getPlayer();
        if (!p.isDead) {
            return;
        }
        if (atSpot) {
            if (p.luong < 1) {
                sendToast(client, "Cần 1 Lượng để hồi sinh tại chỗ.");
                return;
            }
            p.luong -= 1;
            p.isDead = false;
            p.hp = p.maxHp;
            p.mp = p.maxMp;
            p.dirty = true;
            sendSelfFullState(client);
            broadcastPlayerStats(p);
        } else {
            p.isDead = false;
            p.hp = p.maxHp;
            p.mp = p.maxMp;
            p.dirty = true;
            int targetMap = p.saveMapId > 0 ? p.saveMapId : 22;
            world.changeMap(client, targetMap, 228, 192);
        }
    }

    public void tick(long now) {
        if (!initialized) {
            return;
        }
        if (players.isEmpty() && drops.isEmpty()) {
            // Vẫn hồi sinh quái chết nhanh nếu vừa mới chết trước khi người rời map
            for (MobInstance m : mobs) {
                if (m.dead && !m.cantRespawn && now >= m.respawnAt) {
                    m.resetStats(true);
                }
            }
            return;
        }

        // 1. Hồi sinh quái
        for (MobInstance mob : mobs) {
            if (mob.dead && !mob.cantRespawn && now >= mob.respawnAt) {
                synchronized (mob) {
                    if (mob.dead) {
                        mob.resetStats(true);
                        Map<String, Object> pkt = new LinkedHashMap<>();
                        pkt.put("type", "mob_respawn");
                        pkt.put("mob", mob.toDto());
                        broadcast(pkt, -1);
                    }
                }
            }
        }

        if (players.isEmpty()) {
            // Dọn vật phẩm quá hạn khi vắng người
            drops.entrySet().removeIf(e -> now - e.getValue().createdAt > 60_000L);
            return;
        }

        // 2. Di chuyển nhẹ & quái đánh người mỗi ~600ms
        if (now - lastWanderAt >= 600) {
            lastWanderAt = now;
            List<Map<String, Object>> movedMobs = new ArrayList<>();
            for (MobInstance mob : mobs) {
                if (mob.dead || mob.templateId == 0) {
                    continue;
                }
                // Quái đánh trả người chơi gây sát thương gần đây
                if (!mob.aggroPlayers.isEmpty() && now - mob.lastAttackAt >= 2600) {
                    mob.aggroPlayers.entrySet().removeIf(e -> now - e.getValue() > 15_000L || !players.containsKey(e.getKey()));
                    ZoneClient target = pickMobTarget(mob);
                    if (target != null) {
                        mob.lastAttackAt = now;
                        executeMobAttack(mob, target);
                        continue;
                    }
                }

                // Đi tuần nhẹ quanh originX
                if (mob.rangeMove > 0 && ThreadLocalRandom.current().nextInt(100) < 35) {
                    int step = ThreadLocalRandom.current().nextInt(-24, 25);
                    if (Math.abs(step) >= 6) {
                        int minX = Math.max(24, mob.originX - mob.rangeMove * 2);
                        int maxX = Math.min(gameMap.tileMap.pxw - 24, mob.originX + mob.rangeMove * 2);
                        int nx = Math.max(minX, Math.min(maxX, mob.x + step));
                        int ny = mob.typeFly > 0 ? mob.originY : gameMap.tileMap.collisionY(nx, Math.max(0, mob.y - 24));
                        if (nx != mob.x || ny != mob.y) {
                            mob.dir = (nx >= mob.x) ? 1 : -1;
                            mob.x = nx;
                            mob.y = ny;
                            Map<String, Object> mm = new LinkedHashMap<>();
                            mm.put("uid", mob.uid);
                            mm.put("x", mob.x);
                            mm.put("y", mob.y);
                            mm.put("dir", mob.dir);
                            movedMobs.add(mm);
                        }
                    }
                }
            }
            if (!movedMobs.isEmpty()) {
                Map<String, Object> movePkt = new LinkedHashMap<>();
                movePkt.put("type", "mob_move");
                movePkt.put("mobs", movedMobs);
                broadcast(movePkt, -1);
            }
        }

        // 3. Dọn vật phẩm quá hạn 60s
        for (Iterator<Map.Entry<Integer, DropItemInstance>> it = drops.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, DropItemInstance> en = it.next();
            if (now - en.getValue().createdAt > 60_000L) {
                int uid = en.getKey();
                it.remove();
                Map<String, Object> pkt = new LinkedHashMap<>();
                pkt.put("type", "drop_remove");
                pkt.put("uid", uid);
                pkt.put("pickerId", -1);
                broadcast(pkt, -1);
            }
        }

        // 4. Hồi phục HP từ bình thuốc (HoT 0.5s), thức ăn và hồi phục tự nhiên ở làng
        for (ZoneClient zc : players.values()) {
            PlayerState p = zc.getPlayer();
            if (p.isDead) {
                continue;
            }
            boolean healed = false;
            if (p.hpHotTicksLeft > 0 && now >= p.hpHotNextAt) {
                p.hpHotTicksLeft--;
                p.hpHotNextAt = now + 500L;
                if (p.hp < p.maxHp) {
                    p.hp = Math.min(p.maxHp, p.hp + p.hpHotParam);
                    healed = true;
                }
            }
            if (now - p.lastFoodTickAt >= 3000L) {
                p.lastFoodTickAt = now;
                if (p.foodHealParam > 0 && now < p.foodExpireAt) {
                    if (p.hp < p.maxHp || p.mp < p.maxMp) {
                        p.hp = Math.min(p.maxHp, p.hp + p.foodHealParam);
                        p.mp = Math.min(p.maxMp, p.mp + p.foodHealParam);
                        healed = true;
                    }
                } else if (isSafeMap() && (p.hp < p.maxHp || p.mp < p.maxMp)) {
                    p.hp = Math.min(p.maxHp, p.hp + Math.max(5, p.maxHp / 15));
                    p.mp = Math.min(p.maxMp, p.mp + Math.max(5, p.maxMp / 15));
                    healed = true;
                }
            }
            if (healed) {
                sendSelfStats(zc);
            }
        }
    }

    private ZoneClient pickMobTarget(MobInstance mob) {
        int maxDist = mob.boss ? 500 : 280;
        for (Long pid : mob.aggroPlayers.keySet()) {
            ZoneClient zc = players.get(pid);
            if (zc != null) {
                PlayerState p = zc.getPlayer();
                if (!p.isDead && Math.hypot(p.x - mob.x, p.y - mob.y) <= maxDist) {
                    return zc;
                }
            }
        }
        return null;
    }

    private void executeMobAttack(MobInstance mob, ZoneClient targetClient) {
        PlayerState p = targetClient.getPlayer();
        int raw = ThreadLocalRandom.current().nextInt(mob.damageOnPlayer2, mob.damageOnPlayer + 1);
        raw -= p.dameDown;
        if (mob.sys == 1) {
            raw -= p.resFire;
        } else if (mob.sys == 2) {
            raw -= p.resIce;
        } else if (mob.sys == 3) {
            raw -= p.resWind;
        }
        int exactly = ThreadLocalRandom.current().nextInt(mob.level * 10 + 100);
        int miss = ThreadLocalRandom.current().nextInt(Math.max(10, p.miss + 100));
        boolean missed = exactly < miss;
        int dmg = missed ? 0 : Math.max(1, raw / 5);

        mob.dir = (p.x >= mob.x) ? 1 : -1;
        if (dmg > 0) {
            p.hp = Math.max(0, p.hp - dmg);
            p.dirty = true;
            if (p.hp <= 0) {
                p.isDead = true;
            }
        }

        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "mob_attack");
        pkt.put("mobUid", mob.uid);
        pkt.put("dir", mob.dir);
        pkt.put("targetId", p.id);
        pkt.put("damage", missed ? -1 : dmg);
        pkt.put("hp", p.hp);
        pkt.put("maxHp", p.maxHp);
        pkt.put("dead", p.isDead);
        broadcast(pkt, -1);

        sendSelfStats(targetClient);
    }

    public void broadcast(Map<String, Object> pkt, long excludePlayerId) {
        for (ZoneClient c : players.values()) {
            if (excludePlayerId >= 0 && c.getPlayerId() == excludePlayerId) {
                continue;
            }
            c.sendJson(pkt);
        }
    }

    public void broadcastPlayerAppearance(PlayerState p) {
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "player_appearance");
        pkt.put("player", toPublicPlayerDto(p));
        broadcast(pkt, -1);
    }

    public void broadcastPlayerStats(PlayerState p) {
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "player_hp");
        pkt.put("playerId", p.id);
        pkt.put("hp", p.hp);
        pkt.put("maxHp", p.maxHp);
        pkt.put("mp", p.mp);
        pkt.put("maxMp", p.maxMp);
        pkt.put("level", p.level);
        pkt.put("dead", p.isDead);
        broadcast(pkt, -1);
    }

    public void sendSelfStats(ZoneClient client) {
        PlayerState p = client.getPlayer();
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "self_stats");
        pkt.put("hp", p.hp);
        pkt.put("maxHp", p.maxHp);
        pkt.put("mp", p.mp);
        pkt.put("maxMp", p.maxMp);
        pkt.put("level", p.level);
        pkt.put("exp", p.exp);
        pkt.put("point", p.point);
        pkt.put("spoint", p.spoint);
        pkt.put("damage", p.damage);
        pkt.put("damage2", p.damage2);
        pkt.put("miss", p.miss);
        pkt.put("exactly", p.exactly);
        pkt.put("fatal", p.fatal);
        pkt.put("xu", p.xu);
        pkt.put("yen", p.yen);
        pkt.put("luong", p.luong);
        pkt.put("isDead", p.isDead);
        client.sendJson(pkt);
    }

    public void sendSelfFullState(ZoneClient client) {
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "self_state");
        pkt.put("self", toSelfPlayerDto(client.getPlayer()));
        client.sendJson(pkt);
    }

    public void sendToast(ZoneClient client, String text) {
        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "toast");
        pkt.put("text", text);
        client.sendJson(pkt);
    }

    public static Map<String, Object> toPublicPlayerDto(PlayerState p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("name", p.name);
        m.put("gender", p.gender);
        m.put("classId", p.classId);
        m.put("level", p.level);
        m.put("hp", p.hp);
        m.put("maxHp", p.maxHp);
        m.put("head", p.head);
        m.put("body", p.body);
        m.put("leg", p.leg);
        m.put("weapon", p.weapon);
        m.put("x", p.x);
        m.put("y", p.y);
        m.put("dir", p.dir);
        m.put("action", p.action);
        m.put("isDead", p.isDead);
        m.put("pkMode", p.pkMode);
        m.put("clanName", p.clanName);
        return m;
    }

    public static Map<String, Object> toSelfPlayerDto(PlayerState p) {
        Map<String, Object> m = new LinkedHashMap<>(toPublicPlayerDto(p));
        m.put("originalHead", p.originalHead);
        m.put("mp", p.mp);
        m.put("maxMp", p.maxMp);
        m.put("exp", p.exp);
        m.put("point", p.point);
        m.put("spoint", p.spoint);
        m.put("potential", p.potential);
        m.put("damage", p.damage);
        m.put("damage2", p.damage2);
        m.put("basicAttack", p.basicAttack);
        m.put("dameDown", p.dameDown);
        m.put("miss", p.miss);
        m.put("exactly", p.exactly);
        m.put("fatal", p.fatal);
        m.put("fatalDame", p.fatalDame);
        m.put("resFire", p.resFire);
        m.put("resIce", p.resIce);
        m.put("resWind", p.resWind);
        m.put("mapId", p.mapId);
        m.put("zoneId", p.zoneId);
        m.put("saveMapId", p.saveMapId);
        m.put("xu", p.xu);
        m.put("xuInBox", p.xuInBox);
        m.put("yen", p.yen);
        m.put("luong", p.luong);
        m.put("bagCapacity", p.bagCapacity);
        m.put("boxCapacity", p.boxCapacity);
        m.put("equipped", p.equipped);
        m.put("bag", p.bag);
        m.put("box", p.box);
        m.put("skills", p.skills);
        m.put("hotkeySkills", p.hotkeySkills);
        m.put("selectedSkillId", p.selectedSkillId);
        m.put("taskId", p.taskId);
        m.put("taskIndex", p.taskIndex);
        m.put("taskCount", p.taskCount);
        m.put("clanId", p.clanId);
        m.put("autoSettings", p.autoSettings);
        return m;
    }
}
