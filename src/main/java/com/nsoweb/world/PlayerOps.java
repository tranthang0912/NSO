package com.nsoweb.world;

import com.nsoweb.db.PlayerRepository;
import com.nsoweb.model.ItemInstance;
import com.nsoweb.model.PlayerCalculator;
import com.nsoweb.model.PlayerState;
import com.nsoweb.model.StaticData;
import com.nsoweb.model.StaticData.*;
import java.util.*;

/**
 * Xử lý các thao tác phát triển nhân vật Bước 3:
 * Tiềm năng, Kỹ năng, Hành trang, Trang bị, Rương đồ, Shop NPC và Nhiệm vụ chính tuyến.
 */
public final class PlayerOps {

    private static final int[] UMAYAKI_DESTINATIONS = {22, 1, 27, 72, 10, 17, 32, 38, 43, 48};

    private PlayerOps() {
    }

    public static void upPotential(ZoneClient client, GameZone zone, int index, int points) {
        PlayerState p = client.getPlayer();
        if (p.isDead || index < 0 || index > 3 || points <= 0) {
            return;
        }
        if (points > p.point) {
            zone.sendToast(client, "Không đủ điểm tiềm năng.");
            return;
        }
        p.potential[index] += points;
        p.point -= points;
        p.dirty = true;
        PlayerCalculator.recalculateStats(p, zone.sd);
        p.hp = Math.min(p.hp, p.maxHp);
        p.mp = Math.min(p.mp, p.maxMp);
        zone.sendSelfFullState(client);
        zone.broadcastPlayerStats(p);
    }

    public static void upSkill(ZoneClient client, GameZone zone, int templateId, int points) {
        PlayerState p = client.getPlayer();
        if (p.isDead || points <= 0) {
            return;
        }
        if (points > p.spoint) {
            zone.sendToast(client, "Không đủ điểm kỹ năng.");
            return;
        }
        Integer curPt = p.skills.get(templateId);
        if (curPt == null) {
            zone.sendToast(client, "Chưa học chiêu thức này.");
            return;
        }
        SkillTemplate tpl = zone.sd.skillTemplates.get(templateId);
        if (tpl == null) {
            return;
        }
        int nextPt = curPt + points;
        if (nextPt > tpl.maxPoint) {
            zone.sendToast(client, "Kỹ năng đã đạt cấp tối đa (" + tpl.maxPoint + ").");
            return;
        }
        SkillLevelData nextLv = tpl.getByPoint(nextPt);
        int reqLv = nextLv != null ? nextLv.level : 1;
        if (templateId == PlayerRepository.entrySkillForClass(p.classId)) {
            reqLv = Math.max(1, reqLv - 9);
        }
        if (reqLv > p.level) {
            zone.sendToast(client, "Cần đạt cấp " + reqLv + " để tăng kỹ năng này.");
            return;
        }
        p.skills.put(templateId, nextPt);
        p.spoint -= points;
        p.dirty = true;
        PlayerCalculator.recalculateStats(p, zone.sd);
        zone.sendSelfFullState(client);
        zone.sendToast(client, "Đã tăng " + tpl.name + " lên cấp " + nextPt + ".");
    }

    public static void setHotkey(ZoneClient client, GameZone zone, int slot, int skillId) {
        PlayerState p = client.getPlayer();
        if (slot < 0 || slot >= p.hotkeySkills.length) {
            return;
        }
        if (skillId >= 0 && !p.skills.containsKey(skillId)) {
            return;
        }
        p.hotkeySkills[slot] = skillId;
        if (skillId >= 0) {
            SkillTemplate tpl = zone.sd.skillTemplates.get(skillId);
            if (tpl != null && tpl.type != 0) {
                p.selectedSkillId = skillId;
            }
        }
        p.dirty = true;
        PlayerCalculator.recalculateStats(p, zone.sd);
        zone.sendSelfFullState(client);
    }

    public static void useItem(ZoneClient client, GameZone zone, int bagIdx) {
        PlayerState p = client.getPlayer();
        if (p.isDead || bagIdx < 0 || bagIdx >= p.bag.size()) {
            return;
        }
        ItemInstance item = p.bag.get(bagIdx);
        ItemTemplate tpl = zone.sd.items.get(item.itemId);
        if (tpl == null) {
            return;
        }
        if (item.isExpired()) {
            p.bag.remove(bagIdx);
            p.dirty = true;
            zone.sendSelfFullState(client);
            zone.sendToast(client, "Vật phẩm đã hết hạn sử dụng.");
            return;
        }
        boolean isOwnStarterWeapon = p.classId > 0
                && item.itemId == PlayerRepository.starterWeaponForClass(p.classId);
        if (p.level < tpl.level && !isOwnStarterWeapon) {
            zone.sendToast(client, "Cần trình độ cấp " + tpl.level + " mới dùng được.");
            return;
        }

        // 1. Trang bị (type 0..15)
        if (tpl.type >= 0 && tpl.type <= 15) {
            if ((tpl.gender == 0 || tpl.gender == 1) && tpl.gender != p.gender) {
                zone.sendToast(client, "Trang bị không phù hợp giới tính.");
                return;
            }
            ItemInstance prev = p.equipped[tpl.type];
            ItemInstance removed = p.bag.get(bagIdx);
            removed.locked = true;
            p.equipped[tpl.type] = removed;
            if (prev != null) {
                p.bag.set(bagIdx, prev);
            } else {
                p.bag.remove(bagIdx);
            }
            p.dirty = true;
            PlayerCalculator.recalculate(p, zone.sd);
            p.hp = Math.min(p.hp, p.maxHp);
            p.mp = Math.min(p.mp, p.maxMp);

            if (p.taskId == 2 && p.taskIndex == 0 && tpl.type == 1) {
                advanceTask(client, zone);
            }
            zone.broadcastPlayerAppearance(p);
            zone.broadcastPlayerStats(p);
            zone.sendSelfFullState(client);
            return;
        }

        // 2. Bình HP (type 16)
        if (tpl.type == 16) {
            if (p.hp >= p.maxHp) {
                zone.sendToast(client, "HP đã đầy.");
                return;
            }
            int param = hpPotionParam(item.itemId);
            int instantHeal = param * 2;
            p.hp = Math.min(p.maxHp, p.hp + instantHeal);
            p.hpHotParam = param;
            p.hpHotTicksLeft = 4;
            p.hpHotNextAt = System.currentTimeMillis() + 500L;
            consumeOneFromBag(p, bagIdx);
            p.dirty = true;
            zone.sendSelfFullState(client);
            zone.broadcastPlayerStats(p);
            return;
        }

        // 3. Bình MP (type 17)
        if (tpl.type == 17) {
            if (p.mp >= p.maxMp) {
                zone.sendToast(client, "MP đã đầy.");
                return;
            }
            int mpHeal = mpPotionParam(item.itemId);
            p.mp = Math.min(p.maxMp, p.mp + mpHeal);
            consumeOneFromBag(p, bagIdx);
            p.dirty = true;
            zone.sendSelfFullState(client);
            return;
        }

        // 4. Thức ăn hồi phục dài hạn (type 18)
        if (tpl.type == 18) {
            int[] foodCfg = foodEffectParam(item.itemId);
            p.foodHealParam = foodCfg[0];
            p.foodExpireAt = System.currentTimeMillis() + foodCfg[1] * 1000L;
            p.hp = Math.min(p.maxHp, p.hp + p.foodHealParam * 3);
            p.mp = Math.min(p.maxMp, p.mp + p.foodHealParam * 3);
            consumeOneFromBag(p, bagIdx);
            p.dirty = true;

            if (p.taskId == 3 && p.taskIndex == 1) {
                advanceTask(client, zone);
            }
            zone.sendToast(client, "Đã dùng " + tpl.name + " (hồi " + p.foodHealParam + " HP/MP định kỳ).");
            zone.sendSelfFullState(client);
            return;
        }

        // 5. Sách kỹ năng (type 27)
        if (tpl.type == 27) {
            learnSkillBook(client, zone, bagIdx, item);
            return;
        }

        zone.sendToast(client, "Chưa thể sử dụng vật phẩm " + tpl.name + ".");
    }

    public static void unequipItem(ZoneClient client, GameZone zone, int slot) {
        PlayerState p = client.getPlayer();
        if (p.isDead || slot < 0 || slot >= 16) {
            return;
        }
        ItemInstance eq = p.equipped[slot];
        if (eq == null) {
            return;
        }
        if (p.bag.size() >= p.bagCapacity) {
            zone.sendToast(client, "Hành trang đã đầy.");
            return;
        }
        p.equipped[slot] = null;
        p.bag.add(eq);
        p.dirty = true;
        PlayerCalculator.recalculate(p, zone.sd);
        p.hp = Math.min(p.hp, p.maxHp);
        p.mp = Math.min(p.mp, p.maxMp);
        zone.broadcastPlayerAppearance(p);
        zone.broadcastPlayerStats(p);
        zone.sendSelfFullState(client);
    }

    public static void bagToBox(ZoneClient client, GameZone zone, int bagIdx) {
        PlayerState p = client.getPlayer();
        if (bagIdx < 0 || bagIdx >= p.bag.size()) {
            return;
        }
        ItemInstance item = p.bag.get(bagIdx);
        ItemTemplate tpl = zone.sd.items.get(item.itemId);
        if (tpl != null && tpl.isUpToUp) {
            for (ItemInstance bx : p.box) {
                if (bx.itemId == item.itemId && bx.locked == item.locked && bx.expire == item.expire) {
                    bx.quantity += item.quantity;
                    p.bag.remove(bagIdx);
                    p.dirty = true;
                    zone.sendSelfFullState(client);
                    return;
                }
            }
        }
        if (p.box.size() >= p.boxCapacity) {
            zone.sendToast(client, "Rương đồ đã đầy.");
            return;
        }
        p.bag.remove(bagIdx);
        p.box.add(item);
        p.dirty = true;
        zone.sendSelfFullState(client);
    }

    public static void boxToBag(ZoneClient client, GameZone zone, int boxIdx) {
        PlayerState p = client.getPlayer();
        if (boxIdx < 0 || boxIdx >= p.box.size()) {
            return;
        }
        ItemInstance item = p.box.get(boxIdx);
        if (!zone.canAddToBag(p, item)) {
            zone.sendToast(client, "Hành trang đã đầy.");
            return;
        }
        p.box.remove(boxIdx);
        zone.addItemToBag(p, item);
        p.dirty = true;
        zone.sendSelfFullState(client);
    }

    public static void xuBox(ZoneClient client, GameZone zone, long deltaXu) {
        PlayerState p = client.getPlayer();
        if (deltaXu == 0) {
            return;
        }
        if (deltaXu > 0) {
            long amt = Math.min(deltaXu, p.xu);
            if (amt <= 0) {
                return;
            }
            p.xu -= amt;
            p.xuInBox += amt;
        } else {
            long amt = Math.min(-deltaXu, p.xuInBox);
            if (amt <= 0) {
                return;
            }
            p.xuInBox -= amt;
            p.xu += amt;
        }
        p.dirty = true;
        zone.sendSelfFullState(client);
    }

    public static void sortBag(ZoneClient client, GameZone zone) {
        PlayerState p = client.getPlayer();
        p.bag.sort(Comparator.comparingInt((ItemInstance i) -> i.itemId).thenComparingInt(i -> -i.upgrade));
        p.dirty = true;
        zone.sendSelfFullState(client);
    }

    public static void dropBagItem(ZoneClient client, GameZone zone, int bagIdx) {
        PlayerState p = client.getPlayer();
        if (bagIdx < 0 || bagIdx >= p.bag.size()) {
            return;
        }
        p.bag.remove(bagIdx);
        p.dirty = true;
        zone.sendSelfFullState(client);
    }

    public static void npcInteract(ZoneClient client, GameZone zone, int npcTemplateId) {
        PlayerState p = client.getPlayer();
        NpcTemplate npc = zone.sd.npcs.get(npcTemplateId);
        if (npc == null) {
            return;
        }

        // Kiểm tra xem NPC có phải mục tiêu bước nhiệm vụ hiện tại không
        boolean taskAdvanced = checkTaskOnNpcTalk(client, zone, npcTemplateId);

        Map<String, Object> dialog = new LinkedHashMap<>();
        dialog.put("type", "npc_dialog");
        dialog.put("npcId", npcTemplateId);
        dialog.put("npcName", npc.name);
        dialog.put("taskAdvanced", taskAdvanced);
        dialog.put("greeting", buildNpcGreeting(p, zone.sd, npcTemplateId, npc.name));

        List<Map<String, Object>> actions = new ArrayList<>();
        switch (npcTemplateId) {
            case 0: // Kanata
                actions.add(act("open_shop", "Cửa hàng Binh khí", 2));
                break;
            case 1: // Furoya
                if (p.gender == 0) {
                    actions.add(act("open_shop", "Nón Nam", 20));
                    actions.add(act("open_shop", "Áo Nam", 22));
                    actions.add(act("open_shop", "Găng tay Nam", 24));
                    actions.add(act("open_shop", "Quần Nam", 26));
                    actions.add(act("open_shop", "Giày Nam", 28));
                } else {
                    actions.add(act("open_shop", "Nón Nữ", 21));
                    actions.add(act("open_shop", "Áo Nữ", 23));
                    actions.add(act("open_shop", "Găng tay Nữ", 25));
                    actions.add(act("open_shop", "Quần Nữ", 27));
                    actions.add(act("open_shop", "Giày Nữ", 29));
                }
                break;
            case 2: // Ameji
                actions.add(act("open_shop", "Dây chuyền (Liên)", 16));
                actions.add(act("open_shop", "Nhẫn", 17));
                actions.add(act("open_shop", "Ngọc bội", 18));
                actions.add(act("open_shop", "Bùa (Phù)", 19));
                break;
            case 3: // Kiriko
                actions.add(act("open_shop", "Dược phẩm (Yên)", 7));
                actions.add(act("open_shop", "Dược phẩm (Xu)", 6));
                break;
            case 4: // Tabemono
                actions.add(act("open_shop", "Thức ăn (Yên)", 9));
                actions.add(act("open_shop", "Thức ăn (Xu)", 8));
                break;
            case 5: // Kamakura
                actions.add(act("open_box", "Mở Rương đồ", 0));
                actions.add(act("save_location", "Lưu tọa độ hồi sinh", p.mapId));
                break;
            case 6: // Kenshinto
                actions.add(act("open_upgrade", "Cường hóa & Luyện đá", 0));
                break;
            case 7:
            case 8: // Umayaki dịch chuyển giữa các làng/trường
                for (int mid : UMAYAKI_DESTINATIONS) {
                    TileMapData tm = zone.sd.tileMaps.get(mid);
                    if (tm != null && mid != p.mapId) {
                        actions.add(act("teleport", "Tới " + tm.name, mid));
                    }
                }
                break;
            case 9: // Cô Toyotomi
                if (p.classId == 0) {
                    actions.add(act("join_class", "Nhập học Kiếm", 1));
                    actions.add(act("join_class", "Nhập học Phi tiêu", 2));
                }
                actions.add(act("open_shop", "Mua sách võ công", 15));
                break;
            case 10: // Thầy Ookamesama
                if (p.classId == 0) {
                    actions.add(act("join_class", "Nhập học Kunai", 3));
                    actions.add(act("join_class", "Nhập học Cung", 4));
                }
                actions.add(act("open_shop", "Mua sách võ công", 15));
                break;
            case 11: // Thầy Kazeto
                if (p.classId == 0) {
                    actions.add(act("join_class", "Nhập học Đao", 5));
                    actions.add(act("join_class", "Nhập học Quạt", 6));
                }
                actions.add(act("open_shop", "Mua sách võ công", 15));
                break;
            case 12: // Tajima
                actions.add(act("task_status", "Kiểm tra Nhiệm vụ chính tuyến", p.taskId));
                break;
            case 24: // Okanechan
                actions.add(act("exchange", "Đổi 10 Lượng -> 100.000 Xu", 1));
                actions.add(act("exchange", "Đổi 10 Lượng -> 100.000 Yên", 2));
                actions.add(act("exchange", "Đổi 10.000 Yên -> 10.000 Xu", 3));
                break;
            case 25: // Rikudou (Hang động & Chiến trường / Boss)
                actions.add(act("open_events", "Săn Boss & Bảng Hang động", 0));
                actions.add(act("enter_cave", "Vào Động Bàn Tơ (Lv.1+)", 0));
                actions.add(act("enter_cave", "Vào Hang Tổ Ong (Lv.15+)", 1));
                actions.add(act("enter_cave", "Vào Tam Hợp Sơn Động (Lv.25+)", 2));
                actions.add(act("enter_cave", "Vào Thạch Không Vực (Lv.35+)", 3));
                break;
            case 26: // Goosho
                actions.add(act("open_shop", "Cửa hàng Goosho", 14));
                actions.add(act("open_shop", "Quầy sách võ công", 15));
                actions.add(act("open_shop", "Thời trang", 32));
                actions.add(act("open_clan", "Quản lý Gia tộc", 0));
                break;
            default:
                break;
        }
        dialog.put("actions", actions);
        client.sendJson(dialog);
    }

    public static void openShop(ZoneClient client, GameZone zone, int storeId) {
        List<StoreDataItem> source = (storeId == 2)
                ? zone.sd.weaponStore
                : zone.sd.storeDataByStore.getOrDefault(storeId, Collections.emptyList());

        List<Map<String, Object>> dtoList = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            StoreDataItem sdi = source.get(i);
            ItemTemplate tpl = zone.sd.items.get(sdi.itemId);
            if (tpl == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("storeItemId", sdi.id);
            m.put("itemId", sdi.itemId);
            m.put("name", tpl.name);
            m.put("icon", tpl.icon);
            m.put("level", tpl.level);
            m.put("gender", tpl.gender);
            m.put("type", tpl.type);
            m.put("coin", sdi.coin);
            m.put("yen", sdi.yen);
            m.put("gold", sdi.gold);
            m.put("locked", sdi.locked);
            m.put("isUpToUp", tpl.isUpToUp);
            m.put("options", sdi.options);
            dtoList.add(m);
        }

        Map<String, Object> pkt = new LinkedHashMap<>();
        pkt.put("type", "shop_data");
        pkt.put("storeId", storeId);
        pkt.put("storeName", zone.sd.stores.getOrDefault(storeId, "Cửa hàng #" + storeId));
        pkt.put("items", dtoList);
        client.sendJson(pkt);
    }

    public static void buyItem(ZoneClient client, GameZone zone, int storeId, int index, int quantity) {
        PlayerState p = client.getPlayer();
        if (quantity <= 0 || quantity > 99) {
            return;
        }
        List<StoreDataItem> source = (storeId == 2)
                ? zone.sd.weaponStore
                : zone.sd.storeDataByStore.getOrDefault(storeId, Collections.emptyList());
        if (index < 0 || index >= source.size()) {
            return;
        }
        StoreDataItem sdi = source.get(index);
        ItemTemplate tpl = zone.sd.items.get(sdi.itemId);
        if (tpl == null) {
            return;
        }
        int neededSlots = tpl.isUpToUp ? 1 : quantity;
        if (p.bag.size() + neededSlots > p.bagCapacity) {
            zone.sendToast(client, "Hành trang không đủ chỗ trống.");
            return;
        }
        long costXu = (long) sdi.coin * quantity;
        long costYen = (long) sdi.yen * quantity;
        long costLuong = (long) sdi.gold * quantity;
        if (costXu > p.xu || costYen > p.yen || costLuong > p.luong) {
            zone.sendToast(client, "Bạn không đủ tiền để mua vật phẩm này.");
            return;
        }

        p.xu -= costXu;
        p.yen -= costYen;
        p.luong -= costLuong;

        int loops = tpl.isUpToUp ? 1 : quantity;
        for (int i = 0; i < loops; i++) {
            ItemInstance bought = new ItemInstance(sdi.itemId, tpl.isUpToUp ? quantity : 1);
            bought.sys = sdi.sys;
            bought.locked = sdi.locked || costYen > 0 || costLuong > 0;
            if (sdi.expire > 0) {
                bought.expire = System.currentTimeMillis() + sdi.expire;
            }
            for (int[] opt : sdi.options) {
                bought.options.add(new int[]{opt[0], opt[1]});
            }
            zone.addItemToBag(p, bought);
        }

        if (sdi.itemId == 23 && p.taskId == 3 && p.taskIndex == 0) {
            p.taskCount += quantity;
            TaskTemplate tt = zone.sd.tasks.get(3);
            if (tt != null && p.taskCount >= tt.counts[0]) {
                advanceTask(client, zone);
            }
        }

        p.dirty = true;
        zone.sendToast(client, "Đã mua " + quantity + "x " + tpl.name + ".");
        zone.sendSelfFullState(client);
    }

    public static void sellItem(ZoneClient client, GameZone zone, int bagIdx, int quantity) {
        PlayerState p = client.getPlayer();
        if (bagIdx < 0 || bagIdx >= p.bag.size() || quantity <= 0) {
            return;
        }
        ItemInstance item = p.bag.get(bagIdx);
        ItemTemplate tpl = zone.sd.items.get(item.itemId);
        if (tpl == null || quantity > item.quantity) return;
        boolean mount = tpl.type >= 29 && tpl.type <= 33;
        if ((item.upgrade > 0 && !mount && tpl.type != 14 && tpl.type != 15)
                || tpl.type == 24 || tpl.type == 25 || tpl.type == 22 || tpl.type == 12) {
            zone.sendToast(client, "Không thể bán vật phẩm nhiệm vụ, quý giá hoặc trang bị đã nâng cấp.");
            return;
        }
        int qty = quantity;
        int unitPrice = resaleYen(tpl);
        long gain = (long) unitPrice * qty;
        p.yen += gain;
        if (qty >= item.quantity) {
            p.bag.remove(bagIdx);
        } else {
            item.quantity -= qty;
        }
        p.dirty = true;
        zone.sendToast(client, "Đã bán thu về " + gain + " Yên.");
        zone.sendSelfFullState(client);
    }

    /** Ported from SRC NSOACE FIX: Item.initYen(), preserving NSO item categories. */
    public static int resaleYen(ItemTemplate tpl) {
        return com.nsoweb.model.ItemRules.resaleYen(tpl);
    }

    public static void teleportMap(ZoneClient client, GameZone zone, int targetMapId) {
        PlayerState p = client.getPlayer();
        boolean allowed = false;
        for (int mid : UMAYAKI_DESTINATIONS) {
            if (mid == targetMapId) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return;
        }
        TileMapData tm = zone.sd.tileMaps.get(targetMapId);
        if (tm == null) {
            return;
        }
        int spawnX = (targetMapId == 22) ? 228 : Math.min(260, tm.pxw / 2);
        int spawnY = tm.collisionY(spawnX, 48);
        zone.world.changeMap(client, targetMapId, spawnX, spawnY);
    }

    public static void exchangeLuong(ZoneClient client, GameZone zone, int option) {
        PlayerState p = client.getPlayer();
        if (option == 1) {
            if (p.luong < 10) {
                zone.sendToast(client, "Cần ít nhất 10 Lượng.");
                return;
            }
            p.luong -= 10;
            p.xu += 100_000;
            zone.sendToast(client, "Đã đổi 10 Lượng lấy 100.000 Xu.");
        } else if (option == 2) {
            if (p.luong < 10) {
                zone.sendToast(client, "Cần ít nhất 10 Lượng.");
                return;
            }
            p.luong -= 10;
            p.yen += 100_000;
            zone.sendToast(client, "Đã đổi 10 Lượng lấy 100.000 Yên.");
        } else if (option == 3) {
            if (p.yen < 10_000) {
                zone.sendToast(client, "Cần ít nhất 10.000 Yên.");
                return;
            }
            p.yen -= 10_000;
            p.xu += 10_000;
            zone.sendToast(client, "Đã đổi 10.000 Yên lấy 10.000 Xu.");
        }
        p.dirty = true;
        zone.sendSelfFullState(client);
    }

    public static void saveLocation(ZoneClient client, GameZone zone) {
        PlayerState p = client.getPlayer();
        if (zone.isSafeMap()) {
            p.saveMapId = p.mapId;
            p.dirty = true;
            zone.sendToast(client, "Đã lưu tọa độ hồi sinh tại " + zone.gameMap.tileMap.name + ".");
        } else {
            zone.sendToast(client, "Chỉ có thể lưu tọa độ ở Làng hoặc Trường.");
        }
    }

    public static void joinClass(ZoneClient client, GameZone zone, int classId) {
        PlayerState p = client.getPlayer();
        if (p.classId != 0) {
            zone.sendToast(client, "Bạn đã chọn môn phái rồi.");
            return;
        }
        if (classId < 1 || classId > 6) {
            return;
        }
        p.classId = classId;
        int entry = PlayerRepository.entrySkillForClass(classId);
        p.skills.putIfAbsent(entry, 1);
        p.selectedSkillId = entry;
        p.hotkeySkills[0] = entry;
        p.dirty = true;
        PlayerCalculator.recalculate(p, zone.sd);
        zone.sendToast(client, "Chúc mừng bạn đã nhập học thành công!");
        zone.sendSelfFullState(client);
    }

    // ==========================================
    // HỆ THỐNG NHIỆM VỤ CHÍNH TUYẾN
    // ==========================================
    public static boolean checkTaskOnNpcTalk(ZoneClient client, GameZone zone, int npcTemplateId) {
        PlayerState p = client.getPlayer();
        TaskTemplate tt = zone.sd.tasks.get(p.taskId);
        if (tt == null || p.taskIndex >= tt.subnames.length) {
            return false;
        }
        if (tt.levelRequire > p.level) {
            return false;
        }
        // Không tiến bước qua talk nếu bước hiện tại yêu cầu đếm (mua/giết/nhặt > 0)
        if (tt.counts != null && p.taskIndex < tt.counts.length && tt.counts[p.taskIndex] > 0) {
            return false;
        }
        // Bước đầu tiên nếu đang kẹt ở levelRequire -> tự động qua bước tiếp nếu đủ cấp
        String sub = tt.subnames[p.taskIndex];
        if (sub.startsWith("Trình độ đạt cấp") && p.level >= tt.levelRequire) {
            advanceTask(client, zone);
            return true;
        }
        int npcIdx = p.taskIndex + 1;
        if (tt.npcs != null && npcIdx < tt.npcs.length && tt.npcs[npcIdx] == npcTemplateId) {
            advanceTask(client, zone);
            return true;
        }
        return false;
    }

    public static void onMobKilled(ZoneClient client, GameZone zone, MobInstance mob) {
        PlayerState p = client.getPlayer();
        TaskTemplate tt = zone.sd.tasks.get(p.taskId);
        if (tt == null || p.taskIndex >= tt.subnames.length) {
            return;
        }
        if (tt.levelRequire > p.level) {
            return;
        }
        int required = (tt.counts != null && p.taskIndex < tt.counts.length) ? tt.counts[p.taskIndex] : -1;
        if (required <= 0) {
            return;
        }

        // Trường hợp 1: Nhiệm vụ giết đúng loại quái
        if (tt.killMob != null && p.taskIndex < tt.killMob.length && tt.killMob[p.taskIndex][0] == mob.templateId) {
            p.taskCount++;
            if (p.taskCount >= required) {
                advanceTask(client, zone);
            } else {
                zone.sendSelfFullState(client);
            }
            return;
        }

        // Trường hợp 2: Nhiệm vụ nhặt vật phẩm rơi từ quái cùng tầm map/cấp
        if (tt.pickItem != null && p.taskIndex < tt.pickItem.length && tt.pickItem[p.taskIndex] > 0) {
            if (mob.level >= Math.max(1, tt.levelRequire - 3)) {
                p.taskCount++;
                if (p.taskCount >= required) {
                    advanceTask(client, zone);
                } else {
                    zone.sendSelfFullState(client);
                }
            }
        }
    }

    public static void onLevelChanged(ZoneClient client, GameZone zone) {
        PlayerState p = client.getPlayer();
        TaskTemplate tt = zone.sd.tasks.get(p.taskId);
        if (tt == null || p.taskIndex >= tt.subnames.length) {
            return;
        }
        String sub = tt.subnames[p.taskIndex];
        if (sub.startsWith("Trình độ đạt cấp") && p.level >= tt.levelRequire) {
            advanceTask(client, zone);
        }
    }

    public static void onMapEntered(ZoneClient client, GameZone zone, int mapId) {
        PlayerState p = client.getPlayer();
        TaskTemplate tt = zone.sd.tasks.get(p.taskId);
        if (tt == null || p.taskIndex >= tt.subnames.length || tt.levelRequire > p.level) {
            return;
        }
        int mapIdx = p.taskIndex + 1;
        if (tt.maps != null && mapIdx < tt.maps.length && tt.maps[mapIdx] == mapId
                && tt.counts != null && tt.counts[p.taskIndex] == -1
                && (tt.npcs == null || tt.npcs[mapIdx] == -2)) {
            advanceTask(client, zone);
        }
    }

    public static void advanceTask(ZoneClient client, GameZone zone) {
        PlayerState p = client.getPlayer();
        TaskTemplate tt = zone.sd.tasks.get(p.taskId);
        if (tt == null) {
            return;
        }
        p.taskIndex++;
        p.taskCount = 0;

        // Bỏ qua các bước quiz rỗng "" trong DB NSO
        while (p.taskIndex < tt.subnames.length && tt.subnames[p.taskIndex].isBlank()) {
            p.taskIndex++;
        }

        if (p.taskIndex >= tt.subnames.length) {
            // Hoàn thành toàn bộ nhiệm vụ hiện tại -> thưởng EXP + Yên + chuyển sang task kế tiếp
            long rewardExp = Math.max(200L, (p.taskId + 1) * 450L);
            long rewardYen = Math.max(500L, (p.taskId + 1) * 800L);
            int lvGain = PlayerCalculator.addExp(p, rewardExp, zone.sd);
            p.yen += rewardYen;
            String finishedName = tt.name;
            p.taskId++;
            p.taskIndex = 0;
            p.taskCount = 0;

            TaskTemplate nextTt = zone.sd.tasks.get(p.taskId);
            if (nextTt != null && nextTt.subnames.length > 0
                    && nextTt.subnames[0].startsWith("Trình độ đạt cấp")
                    && p.level >= nextTt.levelRequire) {
                p.taskIndex = 1;
            }
            p.dirty = true;
            zone.world.savePlayerAsync(p);
            zone.sendToast(client, "Hoàn thành " + finishedName + " (+" + rewardExp + " EXP, +" + rewardYen + " Yên)!");
            if (lvGain > 0) {
                zone.broadcastPlayerStats(p);
            }
        } else {
            p.dirty = true;
            zone.sendToast(client, "Nhiệm vụ cập nhật: " + tt.subnames[p.taskIndex]);
        }
        zone.sendSelfFullState(client);
    }

    private static void learnSkillBook(ZoneClient client, GameZone zone, int bagIdx, ItemInstance item) {
        PlayerState p = client.getPlayer();
        int templateId = resolveSkillTemplateFromBook(item.itemId, p.classId);
        if (templateId <= 0) {
            zone.sendToast(client, "Sách võ công này chưa hỗ trợ cho môn phái hiện tại.");
            return;
        }
        SkillTemplate st = zone.sd.skillTemplates.get(templateId);
        if (st == null) {
            zone.sendToast(client, "Không tìm thấy chiêu thức tương ứng.");
            return;
        }
        if (st.classId != 0 && st.classId != p.classId) {
            zone.sendToast(client, "Sách võ công thuộc môn phái khác.");
            return;
        }
        if (p.skills.containsKey(templateId)) {
            zone.sendToast(client, "Bạn đã học chiêu thức " + st.name + " rồi.");
            return;
        }
        p.skills.put(templateId, 1);
        for (int i = 0; i < p.hotkeySkills.length; i++) {
            if (p.hotkeySkills[i] < 0) {
                p.hotkeySkills[i] = templateId;
                break;
            }
        }
        consumeOneFromBag(p, bagIdx);
        p.dirty = true;
        PlayerCalculator.recalculateStats(p, zone.sd);
        zone.sendToast(client, "Lĩnh ngộ thành công chiêu thức " + st.name + "!");
        zone.sendSelfFullState(client);
    }

    private static int resolveSkillTemplateFromBook(int itemId, int classId) {
        if (itemId >= 40 && itemId <= 93) {
            return itemId - 39;
        }
        switch (itemId) {
            case 311: return 55;
            case 312: return 56;
            case 313: return 57;
            case 314: return 58;
            case 315: return 59;
            case 316: return 60;
            case 375: return 61;
            case 376: return 62;
            case 377: return 63;
            case 378: return 64;
            case 379: return 65;
            case 380: return 66;
            case 547: return 66 + Math.max(1, classId);
            default: return -1;
        }
    }

    private static void consumeOneFromBag(PlayerState p, int bagIdx) {
        ItemInstance item = p.bag.get(bagIdx);
        if (item.quantity > 1) {
            item.quantity--;
        } else {
            p.bag.remove(bagIdx);
        }
    }

    private static int hpPotionParam(int itemId) {
        switch (itemId) {
            case 13: return 25;
            case 14: return 90;
            case 15: return 230;
            case 16: return 400;
            case 17: return 650;
            case 565: return 1500;
            default: return 50;
        }
    }

    private static int mpPotionParam(int itemId) {
        switch (itemId) {
            case 18: return 150;
            case 19: return 500;
            case 20: return 1000;
            case 21: return 2000;
            case 22: return 3500;
            case 566: return 5000;
            default: return 150;
        }
    }

    private static int[] foodEffectParam(int itemId) {
        switch (itemId) {
            case 23: return new int[]{3, 1800};
            case 24: return new int[]{20, 1800};
            case 25: return new int[]{30, 1800};
            case 26: return new int[]{40, 1800};
            case 27: return new int[]{50, 1800};
            case 29: return new int[]{60, 1800};
            case 30: return new int[]{60, 259200};
            default: return new int[]{25, 1800};
        }
    }

    private static String buildNpcGreeting(PlayerState p, StaticData sd, int npcId, String name) {
        TaskTemplate tt = sd.tasks.get(p.taskId);
        if (tt != null && p.taskIndex < tt.subnames.length) {
            return name + ": Chào nhẫn giả " + p.name + "! (NV hiện tại: " + tt.name + " - " + tt.subnames[p.taskIndex] + ")";
        }
        return name + ": Chào mừng nhẫn giả " + p.name + " ghé thăm!";
    }

    private static Map<String, Object> act(String action, String label, int param) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("label", label);
        m.put("param", param);
        return m;
    }
}
