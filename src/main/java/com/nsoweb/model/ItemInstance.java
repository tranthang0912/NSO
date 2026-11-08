package com.nsoweb.model;

import java.util.ArrayList;
import java.util.List;

public class ItemInstance {
    public int itemId;
    public int quantity = 1;
    public int upgrade = 0;
    public int sys = 0;
    public boolean locked = false;
    public long expire = -1;
    public List<int[]> options = new ArrayList<>();

    public ItemInstance() {
    }

    public ItemInstance(int itemId, int quantity) {
        this.itemId = itemId;
        this.quantity = quantity;
    }

    public boolean isExpired() {
        return expire > 0 && System.currentTimeMillis() > expire;
    }
}
