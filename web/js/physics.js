export const TILE = {
  T_EMPTY: 0,
  T_TOP: 2,
  T_LEFT: 4,
  T_RIGHT: 8,
  T_TREE: 16,
  T_WATERFALL: 32,
  T_WATERFLOW: 64,
  T_OUTSIDE: 256,
  T_BRIDGE: 1024,
  T_SOLIDGROUND: 4096,
  T_BOTTOM: 8192,
  T_DIE: 16384
};

export function tileTypeAt(map, px, py) {
  if (!map || !map.types) return 0;
  const tx = Math.floor(px / 24);
  const ty = Math.floor(py / 24);
  if (tx < 0 || ty < 0 || tx >= map.tmw || ty >= map.tmh) return TILE.T_SOLIDGROUND;
  return map.types[ty * map.tmw + tx] || 0;
}

export function isStandableType(t) {
  return (t & (TILE.T_TOP | TILE.T_BRIDGE | TILE.T_WATERFLOW)) !== 0;
}

export function collisionY(map, px, py) {
  if (!map || !map.types) return py;
  const cx = Math.max(12, Math.min(map.pxw - 12, Math.floor(px)));
  let cy = Math.max(0, Math.floor(py / 24) * 24);
  while (cy < map.pxh) {
    if (isStandableType(tileTypeAt(map, cx, cy))) {
      return cy;
    }
    cy += 24;
  }
  return Math.max(24, map.pxh - 24);
}

export function hasLowerPlatform(map, px, fromY) {
  if (!map || !map.types) return false;
  let cy = Math.floor(fromY / 24) * 24 + 24;
  while (cy < map.pxh - 24) {
    if (isStandableType(tileTypeAt(map, px, cy))) {
      return true;
    }
    cy += 24;
  }
  return false;
}

export function findWaypointNear(map, px, py) {
  if (!map || !map.waypoints) return null;
  let best = null;
  let bestDist = Infinity;
  for (const wp of map.waypoints) {
    if (
      px >= wp.minX - 20 &&
      px <= wp.maxX + 20 &&
      py >= wp.minY - 24 &&
      py <= wp.maxY + 24
    ) {
      const cx = (wp.minX + wp.maxX) * 0.5;
      const cy = (wp.minY + wp.maxY) * 0.5;
      const d = Math.abs(px - cx) + Math.abs(py - cy);
      if (d < bestDist) {
        bestDist = d;
        best = wp;
      }
    }
  }
  return best;
}

export function stepPlayerPhysics(state, input, map, dtMs) {
  if (!map) return;
  const dt60 = Math.min(2.2, Math.max(0.2, dtMs / 16.6667));
  const speedX = 3.15;

  if (state.isDead) {
    state.vx = 0;
    state.vy = 0;
    return;
  }

  let moveX = 0;
  if (input.left) moveX -= 1;
  if (input.right) moveX += 1;

  if (moveX !== 0) {
    state.dir = moveX > 0 ? 1 : -1;
    state.vx = moveX * speedX;
  } else {
    state.vx = 0;
  }

  // Nhảy / Nhảy đúp / Rớt xuống tầng dưới
  if (input.jumpPressed) {
    input.jumpPressed = false;
    if (input.down && state.onGround && hasLowerPlatform(map, state.x, state.y)) {
      state.y += 6;
      state.vy = 2.0;
      state.onGround = false;
      state.jumpsLeft = 1;
    } else if (state.jumpsLeft > 0) {
      state.vy = state.onGround ? -10.2 : -9.0;
      state.onGround = false;
      state.jumpsLeft -= 1;
    }
  }

  // Vận tốc rơi + trọng lực
  state.vy = Math.min(10.5, state.vy + 0.46 * dt60);

  // Di chuyển ngang + kiểm tra tường đứng
  const nextX = state.x + state.vx * dt60;
  const minX = 24;
  const maxX = Math.max(24, map.pxw - 24);
  let clampedX = Math.max(minX, Math.min(maxX, nextX));

  const bodyCheckY = state.y - 12;
  const tWall = tileTypeAt(map, clampedX + state.dir * 8, bodyCheckY);
  const isStrictWall =
    ((tWall & TILE.T_TOP) === 0) &&
    (((state.dir < 0) && (tWall & TILE.T_LEFT) !== 0) ||
      ((state.dir > 0) && (tWall & TILE.T_RIGHT) !== 0));
  if (!isStrictWall) {
    state.x = clampedX;
  }

  // Di chuyển dọc + chạm sàn one-way (T_TOP / T_BRIDGE / T_WATERFLOW)
  const prevY = state.y;
  const rawNextY = prevY + state.vy * dt60;
  let landed = false;

  if (state.vy >= 0) {
    const startRow = Math.max(0, Math.floor((prevY - 1) / 24));
    const endRow = Math.min(map.tmh - 1, Math.floor(rawNextY / 24));
    for (let r = startRow; r <= endRow; r++) {
      const floorY = r * 24;
      if (prevY <= floorY + 6 && rawNextY >= floorY) {
        const t = tileTypeAt(map, state.x, floorY);
        if (isStandableType(t)) {
          state.y = floorY;
          state.vy = 0;
          state.onGround = true;
          state.jumpsLeft = 2;
          landed = true;
          break;
        }
      }
    }
  }

  if (!landed) {
    state.y = Math.max(24, Math.min(map.pxh - 24, rawNextY));
    state.onGround = false;
    if (state.y >= map.pxh - 24) {
      state.vy = 0;
      state.onGround = true;
      state.jumpsLeft = 2;
    }
  }

  // Cập nhật hành động đồng bộ (0=đứng, 1=chạy, 2=nhảy, 3=rơi)
  if (!state.onGround) {
    state.action = state.vy < 0 ? 2 : 3;
  } else if (Math.abs(state.vx) > 0.1) {
    state.action = 1;
  } else {
    state.action = 0;
  }
}
