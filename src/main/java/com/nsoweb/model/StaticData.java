package com.nsoweb.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StaticData {

    public static class ClazzInfo {
        public int id;
        public String name;
    }

    public static class MapNpcSpawn {
        public int templateId;
        public int x;
        public int y;
        public int status;
    }

    public static class Waypoint {
        public int next;
        public int minX;
        public int minY;
        public int maxX;
        public int maxY;
        public int x;
        public int y;
    }

    public static class MobSpawnPoint {
        public int templateId;
        public int x;
        public int y;
        public boolean boss;
    }

    public static class TileMapData {
        public static final int T_TOP = 2;
        public static final int T_LEFT = 4;
        public static final int T_RIGHT = 8;
        public static final int T_WATERFLOW = 64;
        public static final int T_BRIDGE = 1024;
        public static final int T_SOLIDGROUND = 4096;

        public int id;
        public String name;
        public int tileId;
        public int bgId;
        public int mapType;
        public int zoneNumber;
        public int tmw;
        public int tmh;
        public int pxw;
        public int pxh;
        public int[] tiles;
        public int[] types;
        public List<MapNpcSpawn> npcs = new ArrayList<>();
        public List<Waypoint> waypoints = new ArrayList<>();
        public List<MobSpawnPoint> mobs = new ArrayList<>();

        public boolean tileTypeAt(int px, int py, int flag) {
            if (types == null || tmw <= 0 || tmh <= 0) {
                return false;
            }
            int tx = px / 24;
            int ty = py / 24;
            if (tx < 0 || ty < 0 || tx >= tmw || ty >= tmh) {
                return false;
            }
            return (types[ty * tmw + tx] & flag) == flag;
        }

        public int collisionY(int x, int y) {
            int cx = Math.max(0, Math.min(pxw - 1, x));
            int cy = Math.max(0, (y / 24) * 24);
            while (cy < pxh) {
                if (tileTypeAt(cx, cy, T_TOP) || tileTypeAt(cx, cy, T_BRIDGE) || tileTypeAt(cx, cy, T_WATERFLOW)) {
                    return cy;
                }
                cy += 24;
            }
            return Math.max(24, pxh - 24);
        }

        public Waypoint findWaypoint(int x, int y) {
            Waypoint best = null;
            int bestDist = Integer.MAX_VALUE;
            for (Waypoint way : waypoints) {
                if (x >= (way.minX - 100) && x <= (way.maxX + 100)
                        && y >= (way.minY - 100) && y <= (way.maxY + 100)) {
                    int cx = (way.minX + way.maxX) / 2;
                    int cy = (way.minY + way.maxY) / 2;
                    int dist = Math.abs(x - cx) + Math.abs(y - cy);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = way;
                    }
                }
            }
            return best;
        }
    }

    public static class MonsterTemplate {
        public int id;
        public String name;
        public int level;
        public boolean boss;
        public int type;
        public int hp;
        public int rangeMove;
        public int speed;
        public int typeFly;
        public int nImg;
        public String moveJson;
        public String attackJson;
        public String spritesJson;
        public String framesJson;
        public String sequenceJson;
        public String indexSplashJson;
    }

    public static class NpcTemplate {
        public int id;
        public String name;
        public int head;
        public int body;
        public int leg;
        public String[][] menu;
    }

    public static class ItemTemplate {
        public int id;
        public String name;
        public int type;
        public int gender;
        public String description;
        public int level;
        public int icon;
        public int part;
        public int fashion;
        public boolean isUpToUp;
        public int maxUpgrade;
        public int resaleYen;
    }

    public static class ItemOptionTemplate {
        public int id;
        public int type;
        public String name;
    }

    public static class SkillOption {
        public int id;
        public int param;
    }

    public static class SkillLevelData {
        public int id;
        public int templateId;
        public int maxFight;
        public int level;
        public int manaUse;
        public int cooldown;
        public int point;
        public int dx;
        public int dy;
        public List<SkillOption> options = new ArrayList<>();
    }

    public static class SkillTemplate {
        public int id;
        public int classId;
        public String name;
        public int maxPoint;
        public int type;
        public int icon;
        public String description;
        public List<SkillLevelData> levels = new ArrayList<>();

        public SkillLevelData getByPoint(int point) {
            for (SkillLevelData s : levels) {
                if (s.point == point) {
                    return s;
                }
            }
            return levels.isEmpty() ? null : levels.get(0);
        }
    }

    public static class StoreDataItem {
        public int id;
        public int itemId;
        public int sys;
        public int store;
        public boolean locked;
        public int coin;
        public int gold;
        public int yen;
        public long expire;
        public List<int[]> options = new ArrayList<>();
    }

    public static class TaskTemplate {
        public int taskId;
        public String name;
        public String detail;
        public String[] subnames;
        public int[] counts;
        public int levelRequire;
        public int[][] killMob;
        public int[] pickItem;
        public int[] npcs;
        public int[] maps;
    }

    public final Map<Integer, ClazzInfo> clazzMap = new HashMap<>();
    public final Map<Integer, TileMapData> tileMaps = new HashMap<>();
    public final Map<Integer, MonsterTemplate> monsters = new HashMap<>();
    public final Map<Integer, NpcTemplate> npcs = new HashMap<>();
    public final Map<Integer, ItemTemplate> items = new HashMap<>();
    public final Map<Integer, ItemOptionTemplate> itemOptions = new HashMap<>();
    public final Map<Integer, SkillTemplate> skillTemplates = new HashMap<>();
    public final Map<Integer, String> skillOptions = new HashMap<>();
    public final Map<Integer, String> stores = new HashMap<>();
    public final Map<Integer, List<StoreDataItem>> storeDataByStore = new HashMap<>();
    public final List<StoreDataItem> weaponStore = new ArrayList<>();
    public final Map<Integer, TaskTemplate> tasks = new HashMap<>();
    public final Map<String, String> others = new HashMap<>();

    // Dữ liệu client sprite (JSON thuần để gửi qua /api/bootstrap, tốn rất ít RAM)
    public final List<Map<String, Object>> njPartsRaw = new ArrayList<>();
    public final List<Map<String, Object>> njSkillsRaw = new ArrayList<>();
    public final List<Map<String, Object>> njArrowsRaw = new ArrayList<>();
    public final List<Map<String, Object>> njEffectsRaw = new ArrayList<>();
    public final List<Map<String, Object>> effectDataRaw = new ArrayList<>();
    public final List<Map<String, Object>> effectsRaw = new ArrayList<>();

    public long[] exps = new long[0];
}
