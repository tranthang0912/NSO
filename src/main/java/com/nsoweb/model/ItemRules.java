package com.nsoweb.model;

/** Rules ported from SRC NSOACE FIX: Item.initYen and ItemTemplate.getUpMax. */
public final class ItemRules {
    private ItemRules() {}
    public static int maxUpgradeForLevel(int level) {
        if (level < 10) return 0;
        if (level < 20) return 4;
        if (level < 40) return 8;
        if (level < 50) return 12;
        if (level < 60) return 14;
        return 16;
    }
    public static int resaleYen(StaticData.ItemTemplate tpl) {
        int base = tpl.level / 10 * 100;
        if (base == 0) base = 60;
        if (tpl.type == 0 || tpl.type == 2 || tpl.type == 4 || tpl.type == 6 || tpl.type == 8) return base * 3;
        if (tpl.type == 3 || tpl.type == 5 || tpl.type == 7 || tpl.type == 9) return base * 4;
        if (tpl.type == 1) return base * 5;
        if ((tpl.type >= 0 && tpl.type <= 15) || (tpl.type >= 29 && tpl.type <= 38)) return 5;
        return 0;
    }
}
