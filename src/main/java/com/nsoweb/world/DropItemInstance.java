package com.nsoweb.world;

import com.nsoweb.model.ItemInstance;
import java.util.LinkedHashMap;
import java.util.Map;

public class DropItemInstance {
    public final int uid;
    public final int x;
    public final int y;
    public final ItemInstance item; // itemId=12 -> Yen
    public final long ownerId; // -1 = anyone can pick
    public final long createdAt;

    public DropItemInstance(int uid, int x, int y, ItemInstance item, long ownerId) {
        this.uid = uid;
        this.x = x;
        this.y = y;
        this.item = item;
        this.ownerId = ownerId;
        this.createdAt = System.currentTimeMillis();
    }

    public Map<String, Object> toDto() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", uid);
        m.put("x", x);
        m.put("y", y);
        m.put("itemId", item.itemId);
        m.put("quantity", item.quantity);
        m.put("ownerId", ownerId);
        return m;
    }
}
