package com.nsoweb.model;

import com.nsoweb.model.StaticData.*;
import java.util.Map;

/**
 * Tính toán chỉ số (AbilityFromEquip), ngoại hình (FashionFromEquip) và EXP lên cấp chuẩn NSO.
 */
public final class PlayerCalculator {

    private PlayerCalculator() {
    }

    public static void recalculate(PlayerState p, StaticData sd) {
        recalculateFashion(p, sd);
        recalculateStats(p, sd);
    }

    public static void recalculateFashion(PlayerState p, StaticData sd) {
        ItemInstance wp = p.equipped[1];
        if (wp != null && sd.items.containsKey(wp.itemId)) {
            int part = sd.items.get(wp.itemId).part;
            p.weapon = (part >= 0) ? part : 15;
        } else {
            p.weapon = 15;
        }

        ItemInstance armor = p.equipped[2];
        if (armor != null && sd.items.containsKey(armor.itemId)) {
            int part = sd.items.get(armor.itemId).part;
            p.body = (part >= 0) ? part : (p.gender == 0 ? 10 : 1);
        } else {
            p.body = (p.gender == 0) ? 10 : 1;
        }

        ItemInstance pants = p.equipped[6];
        if (pants != null && sd.items.containsKey(pants.itemId)) {
            int part = sd.items.get(pants.itemId).part;
            p.leg = (part >= 0) ? part : (p.gender == 0 ? 9 : 0);
        } else {
            p.leg = (p.gender == 0) ? 9 : 0;
        }

        ItemInstance mask = p.equipped[11];
        if (mask != null && sd.items.containsKey(mask.itemId) && sd.items.get(mask.itemId).part >= 0) {
            p.head = sd.items.get(mask.itemId).part;
        } else {
            p.head = p.originalHead;
        }
    }

    public static void recalculateStats(PlayerState p, StaticData sd) {
        int maxOptId = 205;
        int[] opts = new int[maxOptId];

        for (ItemInstance eq : p.equipped) {
            if (eq == null || eq.isExpired()) {
                continue;
            }
            for (int[] pair : eq.options) {
                int optId = pair[0];
                int param = pair[1];
                if (optId < 0 || optId >= maxOptId) {
                    continue;
                }
                ItemOptionTemplate tpl = sd.itemOptions.get(optId);
                if (tpl != null && tpl.type >= 3 && tpl.type <= 7) {
                    boolean active = (tpl.type == 3 && eq.upgrade >= 4)
                            || (tpl.type == 4 && eq.upgrade >= 8)
                            || (tpl.type == 5 && eq.upgrade >= 12)
                            || (tpl.type == 6 && eq.upgrade >= 14)
                            || (tpl.type == 7 && eq.upgrade >= 16);
                    if (!active) {
                        continue;
                    }
                }
                opts[optId] += param;
            }
        }

        int[] skillOpts = new int[80];
        SkillTemplate selTpl = sd.skillTemplates.get(p.selectedSkillId);
        Integer selPt = p.skills.get(p.selectedSkillId);
        if (selTpl != null && selPt != null && selPt >= 0) {
            SkillLevelData sld = selTpl.getByPoint(selPt);
            if (sld != null) {
                for (SkillOption so : sld.options) {
                    if (so.id >= 0 && so.id < skillOpts.length) {
                        skillOpts[so.id] += so.param;
                    }
                }
            }
        }

        int[] supportOpts = new int[80];
        for (Map.Entry<Integer, Integer> e : p.skills.entrySet()) {
            SkillTemplate tpl = sd.skillTemplates.get(e.getKey());
            if (tpl != null && tpl.type == 0 && e.getValue() > 0) {
                SkillLevelData sld = tpl.getByPoint(e.getValue());
                if (sld != null) {
                    for (SkillOption so : sld.options) {
                        if (so.id >= 0 && so.id < supportOpts.length) {
                            supportOpts[so.id] += so.param;
                        }
                    }
                }
            }
        }

        int[] pot = p.potential;
        p.exactly = pot[1] + (pot[1] * opts[58] / 100) + opts[57] + opts[12];
        p.miss = (pot[1] + opts[57] + (pot[1] * opts[58] / 100)) * 150 / 100;

        int potentialDame;
        if (p.getSideClass() == 0) {
            potentialDame = pot[0] + opts[57] + (pot[0] * opts[58] / 100);
            p.basicAttack = potentialDame + opts[38] + (potentialDame * skillOpts[11] / 100);
            p.damage = p.basicAttack + opts[0] + opts[21] + opts[23] + opts[25]
                    + (potentialDame * opts[8] / 100) + potentialDame;
        } else {
            potentialDame = pot[3] + opts[57] + (pot[3] * opts[58] / 100);
            p.basicAttack = potentialDame + opts[38] + (potentialDame * skillOpts[11] / 100);
            p.damage = p.basicAttack + opts[1] + opts[22] + opts[24] + opts[26]
                    + (potentialDame * opts[9] / 100) + potentialDame;
        }

        p.damage += opts[73] + opts[87] + opts[76] + ((potentialDame * 4) * opts[94] / 100);
        p.damage += supportOpts[8] + supportOpts[6] + supportOpts[9] + supportOpts[7] + supportOpts[1];
        for (int i = 2; i <= 10; i++) {
            p.damage += skillOpts[i];
        }
        // Chiêu hỗ trợ tăng sát thương theo % tấn công cơ bản (id 11 nếu skill chủ động không đủ)
        if (skillOpts[11] == 0 && supportOpts[11] > 0) {
            p.damage += p.basicAttack * supportOpts[11] / 100;
        }
        p.damage2 = Math.max(1, p.damage - p.damage / 10);
        p.damage = Math.max(p.damage2, p.damage);

        int basicHp = (pot[2] + opts[57] + (pot[2] * opts[58] / 100)) * 10;
        p.maxHp = basicHp + (basicHp * (opts[31] + opts[61] + supportOpts[17]) / 100);
        p.maxHp += opts[6] + opts[32] + opts[77] + opts[82] + opts[125] + opts[158] + opts[188];
        p.maxHp += (p.maxHp * opts[128] / 100) + (p.maxHp * opts[160] / 100);
        if (p.maxHp <= 0) {
            p.maxHp = 50;
        }
        if (p.hp <= 0 && !p.isDead) {
            p.hp = p.maxHp;
        }
        if (p.hp > p.maxHp) {
            p.hp = p.maxHp;
        }

        int basicMp = (pot[3] + opts[57] + (pot[3] * opts[58] / 100)) * 10;
        p.maxMp = basicMp + (basicMp * (opts[28] + opts[60] + supportOpts[18]) / 100);
        p.maxMp += opts[7] + opts[19] + opts[29] + opts[83] + opts[117] + opts[159] + opts[189];
        if (p.maxMp <= 0) {
            p.maxMp = 50;
        }
        if (p.mp > p.maxMp) {
            p.mp = p.maxMp;
        }

        p.dameDown = opts[47] + opts[74] + opts[80] + opts[124];
        p.miss += opts[5] + opts[17] + opts[62] + opts[68] + opts[78] + opts[84] + opts[115] + supportOpts[13];
        p.exactly += opts[10] + opts[18] + opts[75] + opts[86] + opts[116] + supportOpts[12];
        p.resFire = opts[2] + opts[11] + opts[33] + opts[70] + opts[36] + supportOpts[19] + supportOpts[20];
        p.resIce = opts[3] + opts[12] + opts[34] + opts[71] + opts[36] + supportOpts[19] + supportOpts[21];
        p.resWind = opts[4] + opts[13] + opts[35] + opts[72] + opts[36] + supportOpts[19] + supportOpts[22];
        p.fatal = opts[14] + opts[37] + opts[69] + opts[92] + opts[114] + supportOpts[14];
        p.fatalDame = opts[105];
    }

    public static int computeLevel(long totalExp, long[] exps) {
        long rem = Math.max(0, totalExp);
        for (int i = 0; i < exps.length; i++) {
            if (rem < exps[i]) {
                return Math.max(1, i);
            }
            rem -= exps[i];
        }
        return Math.max(1, exps.length - 1);
    }

    public static int addExp(PlayerState p, long gain, StaticData sd) {
        if (gain <= 0) {
            return 0;
        }
        p.exp += gain;
        int newLevel = computeLevel(p.exp, sd.exps);
        int levelsGained = newLevel - p.level;
        if (levelsGained > 0) {
            for (int lv = p.level; lv < newLevel; lv++) {
                if (p.classId == 0) {
                    p.potential[0] += 2;
                    p.potential[1] += 1;
                    p.potential[2] += 1;
                    p.potential[3] += 1;
                } else {
                    if (lv <= 69) {
                        p.point += 10;
                    } else if (lv <= 79) {
                        p.point += 20;
                    } else if (lv <= 89) {
                        p.point += 30;
                    } else if (lv <= 99) {
                        p.point += 40;
                    } else if (lv <= 130) {
                        p.point += 50;
                    } else if (lv <= 145) {
                        p.point += 60;
                    } else {
                        p.point += 70;
                    }
                    p.spoint += 1;
                }
            }
            p.level = newLevel;
            recalculate(p, sd);
            p.hp = p.maxHp;
            p.mp = p.maxMp;
        }
        p.dirty = true;
        return levelsGained;
    }
}
