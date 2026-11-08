package com.nsoweb.world;

import com.nsoweb.model.StaticData.MonsterTemplate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MobInstance {
    public final int uid;
    public final int templateId;
    public final String name;
    public int level;
    public final int originalHp;
    public final int originX;
    public final int originY;
    public final int rangeMove;
    public final int speed;
    public final int type;
    public final int typeFly;

    public int x;
    public int y;
    public int dir = 1;
    public int sys = 1;
    public int hp;
    public int maxHp;
    public int levelBoss = 0; // 0=normal, 1=Tinh Anh, 2=Thu Linh, 3=Boss/Thu cuoi
    public boolean boss;
    public boolean dead = false;
    public long respawnAt = 0;
    public long lastAttackAt = 0;
    public int damageOnPlayer;
    public int damageOnPlayer2;
    public boolean cantRespawn = false;
    public int customMaxHp = 0;
    public boolean isWorldBoss = false;
    public boolean isCaveBoss = false;
    public int worldBossIndex = -1;

    public final Map<Long, Long> aggroPlayers = new ConcurrentHashMap<>();

    public MobInstance(int uid, MonsterTemplate tpl, int x, int y, boolean spawnBossFlag) {
        this(uid, tpl, x, y, spawnBossFlag, 0, 0);
    }

    public MobInstance(int uid, MonsterTemplate tpl, int x, int y, boolean spawnBossFlag, int customLevel, int customMaxHp) {
        this.uid = uid;
        this.templateId = tpl.id;
        this.name = tpl.name;
        this.level = customLevel > 0 ? customLevel : Math.max(1, tpl.level);
        this.originalHp = Math.max(1, tpl.hp);
        this.customMaxHp = customMaxHp;
        this.originX = x;
        this.originY = y;
        this.x = x;
        this.y = y;
        this.rangeMove = tpl.rangeMove;
        this.speed = Math.max(1, tpl.speed);
        this.type = tpl.type;
        this.typeFly = tpl.typeFly;
        this.boss = tpl.boss || spawnBossFlag;
        resetStats(false);
    }

    public void resetStats(boolean allowEliteRoll) {
        this.dead = false;
        this.x = originX;
        this.y = originY;
        this.sys = ThreadLocalRandom.current().nextInt(1, 4);
        this.aggroPlayers.clear();

        if (templateId == 0) {
            this.levelBoss = 0;
        } else if (boss) {
            this.levelBoss = 3;
        } else if (allowEliteRoll && level >= 10) {
            int r = ThreadLocalRandom.current().nextInt(100);
            if (r == 0) {
                this.levelBoss = 2; // Thu linh 1%
            } else if (r <= 3) {
                this.levelBoss = 1; // Tinh anh 3%
            } else {
                this.levelBoss = 0;
            }
        } else {
            this.levelBoss = 0;
        }

        if (customMaxHp > 0) {
            this.maxHp = customMaxHp;
            this.hp = customMaxHp;
            int dmg = Math.max(15, level * 5 + (level * level) / 4);
            this.damageOnPlayer = dmg;
            this.damageOnPlayer2 = Math.max(10, dmg - dmg / 8);
            return;
        }

        long scaledHp = originalHp;
        if (!boss) {
            if (levelBoss == 1) {
                scaledHp = (long) originalHp * 10L;
            } else if (levelBoss == 2) {
                scaledHp = (long) originalHp * 50L;
            }
        }
        this.maxHp = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, scaledHp));
        this.hp = this.maxHp;

        int dmg = (int) (level + (Math.pow(level, 2) / 5.0));
        if (boss) {
            dmg *= 15;
        } else if (levelBoss == 1) {
            dmg *= 2;
        } else if (levelBoss == 2) {
            dmg *= 3;
        }
        this.damageOnPlayer = Math.max(1, dmg);
        this.damageOnPlayer2 = Math.max(1, dmg - dmg / 10);
    }

    public Map<String, Object> toDto() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", uid);
        m.put("templateId", templateId);
        m.put("name", name);
        m.put("level", level);
        m.put("hp", hp);
        m.put("maxHp", maxHp);
        m.put("x", x);
        m.put("y", y);
        m.put("dir", dir);
        m.put("sys", sys);
        m.put("boss", boss);
        m.put("levelBoss", levelBoss);
        m.put("dead", dead);
        return m;
    }
}
