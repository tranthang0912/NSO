package com.nsoweb.world;

import com.nsoweb.model.PlayerState;
import java.util.Map;

public interface ZoneClient {
    long getPlayerId();

    PlayerState getPlayer();

    void sendJson(Map<String, Object> packet);
}
