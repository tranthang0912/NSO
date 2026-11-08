package com.nsoweb.world;

import com.nsoweb.model.StaticData.TileMapData;
import java.util.ArrayList;
import java.util.List;

public class GameMap {
    public final TileMapData tileMap;
    public final List<GameZone> zones = new ArrayList<>();

    public GameMap(TileMapData tileMap, WorldManager world) {
        this.tileMap = tileMap;
        // Theo giới hạnRender Free (4-5 người chơi): chỉ giữ 1-2 khu/map cho nhẹ RAM
        int zoneCount = Math.max(1, Math.min(2, tileMap.zoneNumber));
        for (int i = 0; i < zoneCount; i++) {
            zones.add(new GameZone(this, i, world));
        }
    }

    public GameZone getZone(int zoneId) {
        if (zoneId < 0 || zoneId >= zones.size()) {
            return zones.get(0);
        }
        return zones.get(zoneId);
    }

    public GameZone pickBestZone() {
        for (GameZone z : zones) {
            if (z.players.size() < 15) {
                return z;
            }
        }
        return zones.get(0);
    }
}
