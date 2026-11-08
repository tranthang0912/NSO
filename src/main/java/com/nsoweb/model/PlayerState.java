package com.nsoweb.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerState {
    public long id;
    public long userId;
    public String name;
    public int gender; // 0=Nam, 1=Nu
    public int classId; // 0..6
    public int originalHead;
    public int level = 1;
    public long exp = 0;

    public int hp = 100;
    public int maxHp = 100;
    public int mp = 100;
    public int maxMp = 100;

    public int point = 0;   // Diem tiem nang
    public int spoint = 0;  // Diem ky nang
    public int[] potential = new int[]{15, 5, 5, 5}; // 0=SucManh, 1=ThanPhap, 2=TheLuc, 3=Chakra

    // Derived combat stats
    public int damage;
    public int damage2;
    public int basicAttack;
    public int dameDown;
    public int miss;
    public int exactly;
    public int fatal;
    public int fatalDame;
    public int resFire;
    public int resIce;
    public int resWind;

    // Visual parts (nj_part IDs)
    public int head = 11;
    public int body = 10;
    public int leg = 9;
    public int weapon = 15;

    // World position & state
    public int mapId = 22;
    public int zoneId = 0;
    public int x = 228;
    public int y = 192;
    public int dir = 1; // 1 right, -1 left
    public int action = 0; // 0 idle, 1 run, 2 jump, 3 fall
    public int saveMapId = 22;
    public boolean isDead = false;

    // Economy
    public long xu = 2000;
    public long xuInBox = 0;
    public long yen = 5000;
    public long luong = 0;

    // Inventory & equipment
    public int bagCapacity = 30;
    public int boxCapacity = 30;
    public ItemInstance[] equipped = new ItemInstance[16];
    public List<ItemInstance> bag = new ArrayList<>();
    public List<ItemInstance> box = new ArrayList<>();

    // Skills: templateId -> point
    public Map<Integer, Integer> skills = new HashMap<>();
    public int[] hotkeySkills = new int[]{-1, -1, -1, -1, -1};
    public int selectedSkillId = 0;
    public transient Map<Integer, Long> skillCooldowns = new HashMap<>();

    // Progression & social
    public int taskId = 0;
    public int taskIndex = 0;
    public int taskCount = 0;
    public Long clanId = null;
    public String clanName = null;
    public int pkMode = 0; // 0=normal, 3=do sat
    public int arenaTargetId = -1;
    public AutoSettings autoSettings = new AutoSettings();

    public transient long lastSaveAt = System.currentTimeMillis();
    public transient long lastFoodTickAt = System.currentTimeMillis();
    public transient int foodHealParam = 0;
    public transient long foodExpireAt = 0;
    public transient int hpHotParam = 0;
    public transient int hpHotTicksLeft = 0;
    public transient long hpHotNextAt = 0;
    public transient boolean dirty = false;

    public static class AutoSettings {
        public int hpThreshold = 50;
        public int mpThreshold = 35;
        public boolean pickupAll = true;
    }

    public int getSideClass() {
        return (classId == 2 || classId == 4 || classId == 6) ? 1 : 0;
    }

    public int getSys() {
        if (classId == 1 || classId == 2) {
            return 1; // Hoa
        }
        if (classId == 3 || classId == 4) {
            return 2; // Thuy / Bang
        }
        if (classId == 5 || classId == 6) {
            return 3; // Phong
        }
        return 0;
    }
}
