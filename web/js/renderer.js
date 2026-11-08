const Z = 2; // Zoom 2x gốc NSO (mỗi tile = 48x48px)

export class GameRenderer {
  constructor(canvas, boot) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d', { alpha: false });
    this.boot = boot;

    this.mapsCache = new Map();
    this.mapPromises = new Map();
    this.images = new Map();
    this.missingImages = new Set();

    // Lookup maps từ /api/bootstrap cho truy cập O(1)
    this.mapMetaById = new Map((boot.maps || []).map(m => [m.id, m]));
    this.njPartById = new Map((boot.njParts || []).map(p => [p.id, p]));
    this.njSkillBySkillId = new Map();
    for (const s of (boot.njSkills || [])) {
      if (!this.njSkillBySkillId.has(s.skillId)) {
        this.njSkillBySkillId.set(s.skillId, s);
      }
    }
    this.njEffectById = new Map((boot.njEffects || []).map(e => [e.id, e]));
    this.njArrowById = new Map((boot.njArrows || []).map(a => [a.id, a]));
    this.itemById = boot.items || {};
    this.npcById = boot.npcs || {};
    this.monsterById = boot.monsters || {};
    this.bgFiles = new Set(boot.bgFiles || []);

    this.camX = 0;
    this.camY = 0;
    this.viewW = 960;
    this.viewH = 540;

    // Hiệu ứng runtime trên client
    this.activeSkillAnims = []; // { playerId, skillId, x, y, dir, airborne, targets, startAt }
    this.activeProjectiles = []; // { fromX, fromY, toX, toY, imgIds, startAt, duration }
    this.floatingPopups = [];   // { x, y, text, color, scale, startAt, duration }
    this.playerChats = new Map(); // playerId -> { text, expireAt }
    this.hitMobFlash = new Map(); // mobUid -> expireAt
    this.levelUpAnims = []; // { playerId, startAt }
  }

  getImage(url) {
    if (this.missingImages.has(url)) return null;
    let entry = this.images.get(url);
    if (entry) {
      this.images.delete(url);
      this.images.set(url, entry);
    }
    if (!entry) {
      const img = new Image();
      entry = { img, loaded: false, error: false };
      img.decoding = 'async';
      img.onload = () => {
        entry.loaded = true;
      };
      img.onerror = () => {
        entry.error = true;
        this.missingImages.add(url);
      };
      img.src = url;
      this.images.set(url, entry);
      if (this.images.size > 512) this.images.delete(this.images.keys().next().value);
    }
    return entry.loaded ? entry.img : null;
  }

  async loadMap(mapId) {
    if (this.mapsCache.has(mapId)) {
      const cached = this.mapsCache.get(mapId);
      this.mapsCache.delete(mapId);
      this.mapsCache.set(mapId, cached);
      return cached;
    }
    if (this.mapPromises.has(mapId)) {
      return this.mapPromises.get(mapId);
    }
    const p = fetch(`/assets/maps/${mapId}.json`, { signal: AbortSignal.timeout(15000) })
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then(data => {
        this.mapsCache.set(mapId, data);
        if (this.mapsCache.size > 12) this.mapsCache.delete(this.mapsCache.keys().next().value);
        this.getImage(`/assets/img/map/tile/${data.tileId}.png`);
        for (let layer = 0; layer < 4; layer++) {
          const name = `bg${layer}${data.bgId ?? 0}.png`;
          if (this.bgFiles.has(name)) this.getImage(`/assets/img/map/bg/${name}`);
        }
        this.mapPromises.delete(mapId);
        return data;
      })
      .catch(err => {
        this.mapPromises.delete(mapId);
        throw err;
      });
    this.mapPromises.set(mapId, p);
    return p;
  }

  prefetchNeighbors(map) {
    if (navigator.connection?.saveData) return;
    const neighbors = [...new Set((map.waypoints || []).map(w => w.next))]
      .filter(id => id >= 0 && this.mapMetaById.has(id)).slice(0, 3);
    const warm = async () => {
      for (const id of neighbors) {
        try { await this.loadMap(id); } catch { /* Retry normally if the player enters it. */ }
      }
    };
    if ('requestIdleCallback' in window) window.requestIdleCallback(warm, { timeout: 2000 });
    else setTimeout(warm, 300);
  }

  resize(width, height) {
    // Tính kích thước logic nét, tối đa ~1280px chiều rộng để chạy 60fps trên cả điện thoại yếu
    const maxW = 1120;
    const minW = 680;
    const aspect = width / Math.max(1, height);
    let lw = Math.min(maxW, Math.max(minW, Math.round(width)));
    let lh = Math.round(lw / aspect);
    if (lh > 720) {
      lh = 720;
      lw = Math.round(lh * aspect);
    }
    if (this.canvas.width !== lw || this.canvas.height !== lh) {
      this.canvas.width = lw;
      this.canvas.height = lh;
    }
    this.viewW = lw;
    this.viewH = lh;
  }

  addChatBubble(playerId, text) {
    this.playerChats.set(playerId, {
      text,
      expireAt: performance.now() + 4500
    });
  }

  triggerSkillAnim(player, skillId, targetMobs) {
    const now = performance.now();
    this.activeSkillAnims.push({
      playerId: player.id,
      skillId,
      x: player.x,
      y: player.y,
      dir: player.dir || 1,
      airborne: player.action === 2 || player.action === 3,
      startAt: now
    });

    const njs = this.njSkillBySkillId.get(skillId) || this.njSkillBySkillId.get(0);
    if (njs && targetMobs && targetMobs.length > 0) {
      const seq = (!player.onGround && njs.skillFly && njs.skillFly.length)
        ? njs.skillFly
        : (njs.skillStand || []);
      let arrowId = 0;
      for (const st of seq) {
        if (st.arrowId > 0) {
          arrowId = st.arrowId;
          break;
        }
      }
      if (arrowId > 0) {
        const arrObj = this.njArrowById.get(arrowId);
        if (arrObj && arrObj.imgId && arrObj.imgId.length) {
          for (const tm of targetMobs) {
            this.activeProjectiles.push({
              fromX: player.x,
              fromY: player.y - 22,
              toX: tm.x,
              toY: tm.y - 16,
              imgIds: arrObj.imgId,
              startAt: now + 60,
              duration: 240
            });
          }
        }
      }
    }
  }

  addPopup(x, y, text, color = '#ffffff', scale = 1) {
    this.floatingPopups.push({
      x,
      y,
      text: String(text),
      color,
      scale,
      startAt: performance.now(),
      duration: 950
    });
  }

  triggerLevelUp(playerId) {
    this.levelUpAnims.push({
      playerId,
      startAt: performance.now()
    });
  }

  render(worldState, nowMs) {
    const ctx = this.ctx;
    ctx.imageSmoothingEnabled = false;
    const map = worldState.map;
    const self = worldState.self;
    if (!map || !self) {
      ctx.fillStyle = '#0d1526';
      ctx.fillRect(0, 0, this.viewW, this.viewH);
      return;
    }

    const mapPxW = map.pxw * Z;
    const mapPxH = map.pxh * Z;

    // Camera mượt bám theo nhân vật
    const targetCamX = Math.max(0, Math.min(Math.max(0, mapPxW - this.viewW), self.x * Z - this.viewW * 0.5));
    const targetCamY = Math.max(0, Math.min(Math.max(0, mapPxH - this.viewH), self.y * Z - this.viewH * 0.56));
    this.camX += (targetCamX - this.camX) * 0.25;
    this.camY += (targetCamY - this.camY) * 0.25;
    const cx = Math.round(this.camX);
    const cy = Math.round(this.camY);

    // 1. Nền trời & parallax background
    this.drawBackground(ctx, map, cx, cy, mapPxW, mapPxH);

    ctx.save();
    ctx.translate(-cx, -cy);

    // 2. Vật thể map lớp sau (behind / betwen)
    this.drawMapObjects(ctx, map.behind, cx, cy);
    this.drawMapObjects(ctx, map.betwen, cx, cy);

    // 3. Tiles bản đồ (chỉ vẽ vùng nhìn thấy để tối ưu 60fps & RAM cực thấp)
    this.drawMapTiles(ctx, map, cx, cy);

    // 4. Waypoints (Cổng chuyển map)
    this.drawWaypoints(ctx, map, nowMs);

    // 5. NPCs
    this.drawNpcs(ctx, map, nowMs);

    // 6. Vật phẩm rơi trên đất
    this.drawDrops(ctx, worldState.drops, nowMs);

    // 7. Quái vật
    this.drawMobs(ctx, worldState.mobs, worldState.selectedMobUid, nowMs);

    // 8. Người chơi khác & Bản thân
    const allPlayers = [];
    for (const p of worldState.players.values()) {
      if (p.id !== self.id) allPlayers.push(p);
    }
    allPlayers.sort((a, b) => a.y - b.y);
    for (const p of allPlayers) {
      this.drawPlayerEntity(ctx, p, false, nowMs);
    }
    this.drawPlayerEntity(ctx, self, true, nowMs);

    // 9. Vật thể map lớp trước (front)
    this.drawMapObjects(ctx, map.front, cx, cy);

    // 10. Hiệu ứng chiêu thức, phi tiêu/chưởng & số sát thương bay lên
    this.drawSkillEffects(ctx, worldState, nowMs);
    this.drawPopups(ctx, nowMs);

    ctx.restore();
  }

  drawBackground(ctx, map, camX, camY, mapPxW, mapPxH) {
    const bgId = map.bgId ?? 0;
    const skyColors = getSkyPalette(bgId, map.type);
    const grad = ctx.createLinearGradient(0, 0, 0, this.viewH);
    grad.addColorStop(0, skyColors[0]);
    grad.addColorStop(1, skyColors[1]);
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, this.viewW, this.viewH);

    // Vẽ tối đa 4 lớp parallax bg3 -> bg0 (từ xa tới gần) nếu file có tồn tại
    const factors = [
      { layer: 3, p: 0.12, bottomPct: 0.92 },
      { layer: 2, p: 0.22, bottomPct: 0.96 },
      { layer: 1, p: 0.35, bottomPct: 0.98 },
      { layer: 0, p: 0.48, bottomPct: 1.0 }
    ];
    for (const f of factors) {
      const fname = `bg${f.layer}${bgId}.png`;
      if (!this.bgFiles.has(fname)) continue;
      const img = this.getImage(`/assets/img/map/bg/${fname}`);
      if (!img) continue;
      const w = img.width;
      const h = img.height;
      if (w <= 0 || h <= 0) continue;

      const offsetX = Math.floor((camX * f.p) % w);
      const y = Math.round(this.viewH * f.bottomPct - h);
      for (let x = -offsetX; x < this.viewW; x += w) {
        ctx.drawImage(img, x, y);
      }
      // Lấp phần dưới chân bg bằng màu đáy nếu cần cho lớp 0/1
      if (y + h < this.viewH && f.layer === 0) {
        ctx.fillStyle = skyColors[2] || '#1c2b1e';
        ctx.fillRect(0, y + h, this.viewW, this.viewH - (y + h));
      }
    }
  }

  drawMapTiles(ctx, map, camX, camY) {
    const tileImg = this.getImage(`/assets/img/map/tile/${map.tileId}.png`);
    if (!tileImg) return;

    const tilePx = 24 * Z; // 48px
    const startCol = Math.max(0, Math.floor(camX / tilePx));
    const endCol = Math.min(map.tmw - 1, Math.ceil((camX + this.viewW) / tilePx));
    const startRow = Math.max(0, Math.floor(camY / tilePx));
    const endRow = Math.min(map.tmh - 1, Math.ceil((camY + this.viewH) / tilePx));

    for (let r = startRow; r <= endRow; r++) {
      const rowOffset = r * map.tmw;
      const dy = r * tilePx;
      for (let c = startCol; c <= endCol; c++) {
        const tid = map.tiles[rowOffset + c];
        if (!tid || tid <= 0) continue;
        const sy = (tid - 1) * tilePx;
        if (sy + tilePx <= tileImg.height) {
          ctx.drawImage(tileImg, 0, sy, tilePx, tilePx, c * tilePx, dy, tilePx, tilePx);
        }
      }
    }
  }

  drawMapObjects(ctx, list, camX, camY) {
    if (!list || !list.length) return;
    const tilePx = 24 * Z;
    for (const obj of list) {
      const img = this.getImage(`/assets/img/object/${obj.id}.png`);
      if (!img) continue;
      const dx = Math.round((obj.x + 0.5) * tilePx - img.width * 0.5);
      const dy = Math.round((obj.y + 1) * tilePx - img.height);
      if (dx + img.width < camX || dx > camX + this.viewW || dy + img.height < camY || dy > camY + this.viewH) {
        continue;
      }
      ctx.drawImage(img, dx, dy);
    }
  }

  drawWaypoints(ctx, map, nowMs) {
    if (!map.waypoints || !map.waypoints.length) return;
    const pulse = 0.65 + 0.35 * Math.sin(nowMs * 0.005);
    for (const wp of map.waypoints) {
      const wx = ((wp.minX + wp.maxX) * 0.5) * Z;
      const wy = ((wp.minY + wp.maxY) * 0.5) * Z;
      const meta = this.mapMetaById.get(wp.next);
      const label = meta ? meta.name : `Map #${wp.next}`;

      // Cổng ánh sáng xanh lam đặc trưng NSO
      ctx.save();
      const grad = ctx.createRadialGradient(wx, wy, 4, wx, wy, 36);
      grad.addColorStop(0, `rgba(90, 225, 255, ${0.85 * pulse})`);
      grad.addColorStop(0.6, `rgba(40, 150, 255, ${0.35 * pulse})`);
      grad.addColorStop(1, 'rgba(40, 150, 255, 0)');
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.ellipse(wx, wy, 30, 44, 0, 0, Math.PI * 2);
      ctx.fill();

      // Mũi tên chỉ hướng nổi phía trên cổng
      const bob = Math.sin(nowMs * 0.007) * 5;
      drawLabelPill(ctx, wx, wy - 44 + bob, `⇄ ${label}`, '#e0f7ff', 'rgba(8, 28, 52, 0.85)');
      ctx.restore();
    }
  }

  drawNpcs(ctx, map, nowMs) {
    if (!map.npcs || !map.npcs.length) return;
    const breath = Math.floor(nowMs / 450) % 2;
    for (const n of map.npcs) {
      const tpl = this.npcById[n.templateId];
      if (!tpl) continue;
      const sx = n.x * Z;
      const sy = n.y * Z;
      if (sx < this.camX - 100 || sx > this.camX + this.viewW + 100) continue;

      this.drawNinjaParts(ctx, sx, sy, 1, breath, tpl.head, tpl.body, tpl.leg, -1);
      drawNameText(ctx, sx, sy - 92, tpl.name, '#ffd54f');
    }
  }

  drawDrops(ctx, drops, nowMs) {
    if (!drops) return;
    const bob = Math.sin(nowMs * 0.006) * 2;
    for (const d of drops.values()) {
      const sx = d.x * Z;
      const sy = d.y * Z + bob;
      const tpl = this.itemById[d.itemId];
      const iconId = d.itemId === 12 ? 942 : (tpl ? tpl.icon : 0);
      const img = iconId > 0 ? this.getImage(`/assets/img/small/${iconId}.png`) : null;
      if (img) {
        ctx.drawImage(img, Math.round(sx - img.width * 0.5), Math.round(sy - img.height));
      } else {
        ctx.fillStyle = d.itemId === 12 ? '#ffd700' : '#7ef9a9';
        ctx.beginPath();
        ctx.arc(sx, sy - 10, 7, 0, Math.PI * 2);
        ctx.fill();
      }
      const label = d.itemId === 12 ? `${d.quantity} Yên` : (tpl ? tpl.name : `#${d.itemId}`);
      drawNameText(ctx, sx, sy - (img ? img.height + 6 : 22), label, d.itemId === 12 ? '#ffe066' : '#a8ffb4');
    }
  }

  drawMobs(ctx, mobs, selectedMobUid, nowMs) {
    if (!mobs) return;
    for (const m of mobs.values()) {
      if (m.dead) continue;
      const tpl = this.monsterById[m.templateId];
      if (!tpl) continue;

      const sx = m.x * Z;
      const sy = m.y * Z;
      if (sx < this.camX - 180 || sx > this.camX + this.viewW + 180) continue;

      const flashUntil = this.hitMobFlash.get(m.uid) || 0;
      const isFlashing = nowMs < flashUntil;

      ctx.save();
      if (isFlashing) {
        ctx.filter = 'brightness(1.9) contrast(1.2)';
      }

      if (tpl.sprites && tpl.sprites.length > 0 && tpl.frames && tpl.frames.length > 0) {
        // Boss / Quái dùng sprite atlas (frame 0..n)
        const sheet = this.getImage(`/assets/img/mob/${m.templateId}_0.png`);
        if (sheet) {
          const seq = (tpl.move && tpl.move.length) ? tpl.move : [0];
          const frameIdx = seq[Math.floor(nowMs / 180) % seq.length] % tpl.frames.length;
          const parts = tpl.frames[frameIdx] || tpl.frames[0];
          ctx.save();
          ctx.translate(sx, sy);
          ctx.scale(m.dir >= 0 ? 1 : -1, 1);
          for (const part of parts) {
            const spr = tpl.sprites[part.id];
            if (!spr) continue;
            ctx.save();
            const px = part.dx * Z;
            const py = part.dy * Z;
            const sw = spr.w * Z;
            const sh = spr.h * Z;
            if (part.flip === 1) {
              ctx.translate(px + sw, py);
              ctx.scale(-1, 1);
              ctx.drawImage(sheet, spr.x * Z, spr.y * Z, sw, sh, 0, 0, sw, sh);
            } else {
              ctx.drawImage(sheet, spr.x * Z, spr.y * Z, sw, sh, px, py, sw, sh);
            }
            ctx.restore();
          }
          ctx.restore();
        }
      } else {
        // Quái thường dùng từng file {templateId}_{frame}.png
        const nImg = Math.max(1, tpl.nImg || 1);
        const frame = Math.floor(nowMs / 210) % nImg;
        const img = this.getImage(`/assets/img/mob/${m.templateId}_${frame}.png`)
          || this.getImage(`/assets/img/mob/${m.templateId}_0.png`);
        if (img) {
          ctx.save();
          ctx.translate(sx, sy);
          ctx.scale(m.dir >= 0 ? 1 : -1, 1);
          ctx.drawImage(img, Math.round(-img.width * 0.5), -img.height);
          ctx.restore();
        }
      }
      ctx.restore();

      // Vòng chọn mục tiêu
      if (selectedMobUid === m.uid) {
        ctx.save();
        ctx.strokeStyle = '#ff4d4d';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.ellipse(sx, sy + 2, 22, 7, 0, 0, Math.PI * 2);
        ctx.stroke();
        ctx.restore();
      }

      // Tên + HP bar trên đầu quái
      let nameColor = '#ffffff';
      let prefix = '';
      if (m.boss) {
        nameColor = '#ff5252';
        prefix = '♛ ';
      } else if (m.levelBoss === 2) {
        nameColor = '#ffab40';
        prefix = '★ ';
      } else if (m.levelBoss === 1) {
        nameColor = '#ce93d8';
        prefix = '◆ ';
      }
      drawNameText(ctx, sx, sy - 66, `${prefix}${tpl.name} Lv.${m.level}`, nameColor);

      const barW = 48;
      const barH = 5;
      const hpRatio = Math.max(0, Math.min(1, m.hp / Math.max(1, m.maxHp)));
      ctx.fillStyle = 'rgba(0,0,0,0.65)';
      ctx.fillRect(sx - barW / 2 - 1, sy - 58, barW + 2, barH + 2);
      ctx.fillStyle = m.boss ? '#ff1744' : '#ef5350';
      ctx.fillRect(sx - barW / 2, sy - 57, Math.round(barW * hpRatio), barH);
    }
  }

  drawPlayerEntity(ctx, p, isSelf, nowMs) {
    const sx = Math.round(p.x * Z);
    const sy = Math.round(p.y * Z);

    if (p.isDead) {
      // Mộ / hồn khi tử trận
      ctx.save();
      ctx.globalAlpha = 0.6;
      this.drawNinjaParts(ctx, sx, sy, p.dir || 1, 0, p.head, p.body, p.leg, p.weapon);
      ctx.restore();
      drawNameText(ctx, sx, sy - 92, `${p.name} (Ngất)`, '#9e9e9e');
      return;
    }

    // Kiểm tra xem nhân vật có đang trong chuỗi animation đánh chiêu không
    let cf = 0;
    const anim = this.findLatestSkillAnim(p.id, nowMs);
    if (anim) {
      const elapsed = nowMs - anim.startAt;
      const njs = this.njSkillBySkillId.get(anim.skillId) || this.njSkillBySkillId.get(0);
      const seq = (anim.airborne && njs?.skillFly?.length) ? njs.skillFly : (njs?.skillStand || []);
      const stepIdx = Math.floor(elapsed / 65);
      if (seq.length > 0 && stepIdx < seq.length) {
        cf = seq[stepIdx].status ?? 14;
      }
    }

    if (cf === 0) {
      if (p.action === 2) {
        cf = 7; // nhảy lên
      } else if (p.action === 3) {
        cf = 9; // rơi xuống
      } else if (p.action === 1) {
        cf = 2 + (Math.floor(nowMs / 95) % 5); // chạy 2..6
      } else {
        cf = Math.floor(nowMs / 380) % 2; // đứng thở 0..1
      }
    }

    this.drawNinjaParts(ctx, sx, sy, p.dir || 1, cf, p.head, p.body, p.leg, p.weapon);

    // Hiệu ứng lên cấp
    for (const lu of this.levelUpAnims) {
      if (lu.playerId === p.id) {
        const t = (nowMs - lu.startAt) / 1300;
        if (t < 1) {
          ctx.save();
          ctx.globalAlpha = 1 - t;
          const gr = ctx.createLinearGradient(sx, sy - 160, sx, sy + 10);
          gr.addColorStop(0, 'rgba(255,241,118,0)');
          gr.addColorStop(0.5, 'rgba(255,213,79,0.75)');
          gr.addColorStop(1, 'rgba(255,160,0,0.9)');
          ctx.fillStyle = gr;
          ctx.fillRect(sx - 28, sy - 150, 56, 155);
          ctx.restore();
        }
      }
    }

    // Tên + Bang hội + HP
    const tag = p.clanName ? `[${p.clanName}] ` : '';
    const color = isSelf ? '#80ffdb' : (p.pkMode > 0 ? '#ff6b6b' : '#ffffff');
    drawNameText(ctx, sx, sy - 92, `${tag}${p.name} (${p.level})`, color);

    const barW = 46;
    const hpRatio = Math.max(0, Math.min(1, (p.hp || 0) / Math.max(1, p.maxHp || 1)));
    ctx.fillStyle = 'rgba(0,0,0,0.6)';
    ctx.fillRect(sx - barW / 2 - 1, sy - 83, barW + 2, 5);
    ctx.fillStyle = isSelf ? '#26de81' : '#4ecdc4';
    ctx.fillRect(sx - barW / 2, sy - 82, Math.round(barW * hpRatio), 3);

    // Bóng thoại Chat trên đầu
    const chat = this.playerChats.get(p.id);
    if (chat) {
      if (nowMs > chat.expireAt) {
        this.playerChats.delete(p.id);
      } else {
        drawChatBubble(ctx, sx, sy - 110, chat.text);
      }
    }
  }

  drawNinjaParts(ctx, cx, cy, dir, frameIdx, headId, bodyId, legId, weaponId) {
    const charinfo = this.boot.charinfo;
    if (!charinfo || !charinfo.length) return;
    const cf = Math.max(0, Math.min(charinfo.length - 1, frameIdx));
    const entries = charinfo[cf];
    // entries: [0]=head, [1]=leg, [2]=body, [3]=weapon => [partFrameIdx, ox, oy]

    ctx.save();
    ctx.translate(cx, cy);
    ctx.scale(dir >= 0 ? 1 : -1, 1);

    // Thứ tự vẽ chuẩn: chân -> vũ khí (sau lưng khi đứng/chạy) -> thân -> đầu
    this.drawSinglePart(ctx, legId, entries[1]);
    if (weaponId >= 0 && weaponId !== 15 && entries[3][0] > 0) {
      this.drawSinglePart(ctx, weaponId, entries[3]);
    }
    this.drawSinglePart(ctx, bodyId, entries[2]);
    this.drawSinglePart(ctx, headId, entries[0]);

    ctx.restore();
  }

  drawSinglePart(ctx, partId, entry) {
    if (partId < 0 || !entry) return;
    const njPart = this.njPartById.get(partId);
    if (!njPart || !njPart.part) return;
    const pIdx = entry[0];
    if (pIdx < 0 || pIdx >= njPart.part.length) return;
    const p = njPart.part[pIdx];
    if (!p || !p.id || p.id <= 0) return;

    const img = this.getImage(`/assets/img/small/${p.id}.png`);
    if (!img) return;
    const dx = (entry[1] + p.dx) * Z;
    const dy = (-entry[2] + p.dy) * Z;
    ctx.drawImage(img, dx, dy);
  }

  findLatestSkillAnim(playerId, nowMs) {
    let found = null;
    for (let i = this.activeSkillAnims.length - 1; i >= 0; i--) {
      const a = this.activeSkillAnims[i];
      if (a.playerId === playerId && nowMs - a.startAt < 650) {
        found = a;
        break;
      }
    }
    return found;
  }

  drawSkillEffects(ctx, worldState, nowMs) {
    // 1. Hiệu ứng từ nj_skill & nj_effect
    this.activeSkillAnims = this.activeSkillAnims.filter(a => nowMs - a.startAt < 700);
    for (const a of this.activeSkillAnims) {
      const p = worldState.players.get(a.playerId) || (worldState.self?.id === a.playerId ? worldState.self : null);
      const px = (p ? p.x : a.x) * Z;
      const py = (p ? p.y : a.y) * Z;
      const dir = p ? (p.dir || 1) : a.dir;

      const njs = this.njSkillBySkillId.get(a.skillId) || this.njSkillBySkillId.get(0);
      if (!njs) continue;
      const seq = (a.airborne && njs.skillFly?.length) ? njs.skillFly : (njs.skillStand || []);
      const stepIdx = Math.floor((nowMs - a.startAt) / 65);
      if (stepIdx >= seq.length) continue;
      const st = seq[stepIdx];

      const effList = [
        [st.effS0Id, st.e0dx || 0, st.e0dy || 0],
        [st.effS1Id, st.e1dx || 0, st.e1dy || 0],
        [st.effS2Id, st.e2dx || 0, st.e2dy || 0]
      ];
      for (const [eid, edx, edy] of effList) {
        if (!eid || eid <= 0) continue;
        const nje = this.njEffectById.get(eid);
        if (!nje || !nje.info || !nje.info.length) continue;
        const f = nje.info[stepIdx % nje.info.length];
        if (!f || !f.id || f.id <= 0) continue;
        const img = this.getImage(`/assets/img/small/${f.id}.png`);
        if (!img) continue;
        ctx.save();
        ctx.translate(px, py);
        ctx.scale(dir >= 0 ? 1 : -1, 1);
        ctx.drawImage(img, (edx + f.dx) * Z - img.width * 0.5, (edy + f.dy) * Z - img.height * 0.5);
        ctx.restore();
      }
    }

    // 2. Đường đạn / Phi tiêu / Chưởng bay (nj_arrow)
    this.activeProjectiles = this.activeProjectiles.filter(pr => nowMs < pr.startAt + pr.duration);
    for (const pr of this.activeProjectiles) {
      if (nowMs < pr.startAt) continue;
      const t = (nowMs - pr.startAt) / pr.duration;
      const x = (pr.fromX + (pr.toX - pr.fromX) * t) * Z;
      const y = (pr.fromY + (pr.toY - pr.fromY) * t) * Z;
      const frameImgId = pr.imgIds[Math.floor(nowMs / 45) % pr.imgIds.length];
      const img = frameImgId ? this.getImage(`/assets/img/small/${frameImgId}.png`) : null;
      if (img) {
        ctx.save();
        ctx.translate(x, y);
        if (pr.toX < pr.fromX) ctx.scale(-1, 1);
        ctx.drawImage(img, -img.width * 0.5, -img.height * 0.5);
        ctx.restore();
      } else {
        ctx.fillStyle = '#40e0ff';
        ctx.beginPath();
        ctx.arc(x, y, 6, 0, Math.PI * 2);
        ctx.fill();
      }
    }

    this.levelUpAnims = this.levelUpAnims.filter(l => nowMs - l.startAt < 1300);
  }

  drawPopups(ctx, nowMs) {
    this.floatingPopups = this.floatingPopups.filter(p => nowMs - p.startAt < p.duration);
    ctx.save();
    ctx.textAlign = 'center';
    for (const pp of this.floatingPopups) {
      const t = (nowMs - pp.startAt) / pp.duration;
      const sy = pp.y * Z - 54 - t * 44;
      const sx = pp.x * Z;
      ctx.globalAlpha = t > 0.7 ? Math.max(0, (1 - t) / 0.3) : 1;
      const size = Math.round(15 * pp.scale);
      ctx.font = `700 ${size}px 'Segoe UI', sans-serif`;
      ctx.lineWidth = 3;
      ctx.strokeStyle = 'rgba(0,0,0,0.85)';
      ctx.strokeText(pp.text, sx, sy);
      ctx.fillStyle = pp.color;
      ctx.fillText(pp.text, sx, sy);
    }
    ctx.restore();
  }
}

// Helper vẽ preview nhân vật trên màn hình Chọn / Tạo nhân vật
export function renderCharPreview(canvas, boot, imgLoader, look, timeMs) {
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  const w = canvas.width;
  const h = canvas.height;
  ctx.clearRect(0, 0, w, h);

  // Nền bệ đứng mini
  const g = ctx.createRadialGradient(w / 2, h * 0.84, 4, w / 2, h * 0.84, 46);
  g.addColorStop(0, 'rgba(72, 219, 251, 0.35)');
  g.addColorStop(1, 'rgba(72, 219, 251, 0)');
  ctx.fillStyle = g;
  ctx.beginPath();
  ctx.ellipse(w / 2, h * 0.84, 40, 12, 0, 0, Math.PI * 2);
  ctx.fill();

  const charinfo = boot.charinfo;
  if (!charinfo || !charinfo.length) return;
  const njPartById = new Map((boot.njParts || []).map(p => [p.id, p]));
  const cf = Math.floor(timeMs / 400) % 2;
  const entries = charinfo[cf];

  ctx.save();
  ctx.translate(w / 2, h * 0.82);

  const order = [
    [look.leg, entries[1]],
    [look.weapon, entries[3]],
    [look.body, entries[2]],
    [look.head, entries[0]]
  ];
  for (const [partId, entry] of order) {
    if (partId == null || partId < 0 || partId === 15 && partId === look.weapon && njPartById.get(15)?.type !== 3) continue;
    if (partId === look.weapon && entry[0] === 0) continue;
    const njPart = njPartById.get(partId);
    if (!njPart || !njPart.part) continue;
    const p = njPart.part[entry[0]];
    if (!p || !p.id || p.id <= 0) continue;
    const img = imgLoader(`/assets/img/small/${p.id}.png`);
    if (!img) continue;
    ctx.drawImage(img, (entry[1] + p.dx) * Z, (-entry[2] + p.dy) * Z);
  }
  ctx.restore();
}

function drawNameText(ctx, x, y, text, color) {
  ctx.save();
  ctx.font = "600 13px 'Segoe UI', sans-serif";
  ctx.textAlign = 'center';
  ctx.lineWidth = 3;
  ctx.strokeStyle = 'rgba(0,0,0,0.85)';
  ctx.strokeText(text, x, y);
  ctx.fillStyle = color;
  ctx.fillText(text, x, y);
  ctx.restore();
}

function drawLabelPill(ctx, x, y, text, color, bgColor) {
  ctx.save();
  ctx.font = "600 12px 'Segoe UI', sans-serif";
  const tw = ctx.measureText(text).width;
  const pw = tw + 14;
  const ph = 22;
  ctx.fillStyle = bgColor;
  roundRect(ctx, x - pw / 2, y - ph / 2, pw, ph, 10);
  ctx.fill();
  ctx.strokeStyle = 'rgba(110, 225, 255, 0.55)';
  ctx.lineWidth = 1;
  ctx.stroke();
  ctx.fillStyle = color;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, x, y + 0.5);
  ctx.restore();
}

function drawChatBubble(ctx, x, y, text) {
  ctx.save();
  ctx.font = "500 13px 'Segoe UI', sans-serif";
  const maxW = 190;
  const lines = wrapText(ctx, text, maxW);
  const lineH = 16;
  let boxW = 0;
  for (const l of lines) boxW = Math.max(boxW, ctx.measureText(l).width);
  boxW += 16;
  const boxH = lines.length * lineH + 10;
  const bx = x - boxW / 2;
  const by = y - boxH;

  ctx.fillStyle = 'rgba(255, 255, 255, 0.94)';
  ctx.strokeStyle = '#222f3e';
  ctx.lineWidth = 1.5;
  roundRect(ctx, bx, by, boxW, boxH, 8);
  ctx.fill();
  ctx.stroke();

  // Đuôi bóng thoại
  ctx.beginPath();
  ctx.moveTo(x - 5, y);
  ctx.lineTo(x + 5, y);
  ctx.lineTo(x, y + 6);
  ctx.closePath();
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = '#111827';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (let i = 0; i < lines.length; i++) {
    ctx.fillText(lines[i], x, by + 5 + i * lineH);
  }
  ctx.restore();
}

function wrapText(ctx, text, maxW) {
  const words = text.split(' ');
  const lines = [];
  let cur = '';
  for (const w of words) {
    const test = cur ? `${cur} ${w}` : w;
    if (ctx.measureText(test).width > maxW && cur) {
      lines.push(cur);
      cur = w;
    } else {
      cur = test;
    }
  }
  if (cur) lines.push(cur);
  return lines.slice(0, 3);
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function getSkyPalette(bgId, mapType) {
  if (mapType === 2 || bgId >= 15) {
    // Hang động / Tối
    return ['#0c1220', '#1c2541', '#141a29'];
  }
  if (bgId === 4 || bgId === 5 || bgId === 10 || bgId === 11) {
    // Tuyết / Núi cao
    return ['#6ba6d8', '#cbe5f6', '#dcebf5'];
  }
  if (bgId === 1 || bgId === 8) {
    // Hoàng hôn / Rừng thu
    return ['#2b5876', '#4e4376', '#263238'];
  }
  // Trời xanh ban ngày mặc định
  return ['#4ea5e0', '#aee2ff', '#2a4d2c'];
}
