import { stepPlayerPhysics, findWaypointNear } from '/js/physics.js';
import { GameRenderer, renderCharPreview } from '/js/renderer.js';
import { UiPanels } from '/js/ui_panels.js';

const Z = 2;
const MALE_HEADS = [11, 26, 27, 28];
const FEMALE_HEADS = [2, 23, 24, 25];
const CLASS_LABELS = {
  1: 'Kiếm',
  2: 'Phi tiêu',
  3: 'Kunai',
  4: 'Cung',
  5: 'Đao',
  6: 'Quạt'
};
const STARTER_WEAPON_ITEM = { 1: 94, 2: 114, 3: 99, 4: 109, 5: 104, 6: 119 };

// State ứng dụng
let boot = null;
let authMode = 'login';
let token = localStorage.getItem('nso_token') || '';
let accountUser = localStorage.getItem('nso_user') || '';

let createFormState = { gender: 0, headId: 11, classId: 1 };
let charListCache = [];
let previewAnimHandle = null;

// State trong game
let ws = null;
let reconnectTimer = null;
let reconnectAttempts = 0;
let renderer = null;
let uiPanels = null;
let gameLoopHandle = null;
let pingTimer = null;
let lastPingSentAt = 0;
let lastMoveSyncAt = 0;
let lastWaypointAt = 0;
let lastSyncedSnapshot = '';
let touchEnabled = ('ontouchstart' in window) || navigator.maxTouchPoints > 0;

// State Auto-Play & Wake Lock (Bước 4)
let autoEnabled = false;
let wakeLockSentinel = null;
let autoSkillCursor = 0;
let lastAutoThinkAt = 0;
let lastAutoPotAt = 0;
let lastAutoAttackAt = 0;
let lastAutoPosCheckAt = 0;
let lastAutoX = 0;
let manualMoveHoldUntil = 0;
let selectedTargetPlayer = null;

const worldState = {
  mapId: -1,
  zoneId: 0,
  zoneCount: 1,
  safe: true,
  map: null,
  self: null,
  players: new Map(), // id -> remote entity (có targetX/Y để nội suy mượt)
  mobs: new Map(),    // uid -> mob dto (có targetX/Y)
  drops: new Map(),   // uid -> drop dto
  selectedMobUid: null
};

const inputState = {
  left: false,
  right: false,
  up: false,
  down: false,
  jumpPressed: false
};

// DOM Elements
const $ = id => document.getElementById(id);
const canvas = $('game-canvas');

async function init() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }

  bindAuthUi();
  bindCharCreateUi();
  bindHudAndInput();
  updateOrientationHint();
  window.addEventListener('resize', () => {
    updateOrientationHint();
    if (renderer) renderer.resize(window.innerWidth, window.innerHeight);
  });

  try {
    const [healthRes, bootRes] = await Promise.all([
      fetch('/api/health').then(r => r.json()),
      fetch('/api/bootstrap').then(r => r.json())
    ]);
    boot = bootRes;
    $('server-status').textContent =
      `Máy chủ sẵn sàng • ${healthRes.maps} bản đồ • ${healthRes.onlinePlayers} đang online`;
    renderHairAndClassPills();
    startCharPreviewLoop();
  } catch (e) {
    $('server-status').textContent = 'Không thể tải dữ liệu máy chủ. Hãy tải lại trang.';
    return;
  }

  if (token) {
    const ok = await tryLoadCharScreen();
    if (!ok) {
      localStorage.removeItem('nso_token');
      token = '';
    }
  }
}

function bindAuthUi() {
  $('tab-login').onclick = () => switchAuthTab('login');
  $('tab-register').onclick = () => switchAuthTab('register');
  $('auth-form').onsubmit = async e => {
    e.preventDefault();
    const username = $('auth-username').value.trim();
    const password = $('auth-password').value;
    showFormError('auth-error', '');
    $('auth-submit').disabled = true;
    try {
      const url = authMode === 'login' ? '/api/auth/login' : '/api/auth/register';
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      });
      const data = await res.json();
      if (!res.ok || !data.ok) {
        throw new Error(data.error || 'Thao tác thất bại.');
      }
      token = data.token;
      accountUser = data.user.username;
      localStorage.setItem('nso_token', token);
      localStorage.setItem('nso_user', accountUser);
      await tryLoadCharScreen();
    } catch (err) {
      showFormError('auth-error', err.message);
    } finally {
      $('auth-submit').disabled = false;
    }
  };
}

function switchAuthTab(mode) {
  authMode = mode;
  $('tab-login').classList.toggle('active', mode === 'login');
  $('tab-register').classList.toggle('active', mode === 'register');
  $('auth-submit').textContent = mode === 'login' ? 'Đăng nhập' : 'Đăng ký tài khoản mới';
  showFormError('auth-error', '');
}

function showFormError(elId, msg) {
  const el = $(elId);
  if (!msg) {
    el.classList.add('hidden');
    el.textContent = '';
  } else {
    el.textContent = msg;
    el.classList.remove('hidden');
  }
}

async function tryLoadCharScreen() {
  try {
    const res = await fetch('/api/chars', {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!res.ok) return false;
    const data = await res.json();
    if (!data.ok) return false;
    charListCache = data.chars || [];
    $('char-account-label').textContent = `Tài khoản: ${accountUser}`;
    $('auth-screen').classList.add('hidden');
    $('char-screen').classList.remove('hidden');
    renderCharList();
    return true;
  } catch {
    return false;
  }
}

function renderCharList() {
  const listEl = $('char-list');
  listEl.innerHTML = '';
  if (!charListCache.length) {
    listEl.innerHTML = '<p class="subtitle">Chưa có nhân vật nào. Hãy tạo nhân vật bên cạnh!</p>';
    return;
  }
  for (const ch of charListCache) {
    const card = document.createElement('div');
    card.className = 'char-item';

    const cv = document.createElement('canvas');
    cv.width = 76;
    cv.height = 86;
    cv.dataset.charPreview = JSON.stringify({
      head: ch.head,
      body: ch.body,
      leg: ch.leg,
      weapon: ch.weapon
    });

    const info = document.createElement('div');
    info.className = 'char-item-info';
    const cName = CLASS_LABELS[ch.classId] || 'Tập sự';
    const mapMeta = (boot.maps || []).find(m => m.id === ch.mapId);
    info.innerHTML = `
      <h4>${escapeHtml(ch.name)}</h4>
      <p>Lv.${ch.level} • Phái ${cName}</p>
      <p>${mapMeta ? escapeHtml(mapMeta.name) : 'Làng Tone'}</p>
    `;

    const playBtn = document.createElement('button');
    playBtn.className = 'btn btn-primary';
    playBtn.textContent = 'Vào game';
    playBtn.onclick = () => enterGame(ch.id);

    card.appendChild(cv);
    card.appendChild(info);
    card.appendChild(playBtn);
    listEl.appendChild(card);
  }
}

function bindCharCreateUi() {
  $('btn-back-auth').onclick = () => {
    clearTimeout(reconnectTimer);
    const previous = ws;
    ws = null;
    previous?.close();
    localStorage.removeItem('nso_token');
    token = '';
    $('char-screen').classList.add('hidden');
    $('auth-screen').classList.remove('hidden');
  };

  $('gender-pills').addEventListener('click', e => {
    const btn = e.target.closest('[data-gender]');
    if (!btn) return;
    createFormState.gender = Number(btn.dataset.gender);
    createFormState.headId = createFormState.gender === 0 ? MALE_HEADS[0] : FEMALE_HEADS[0];
    for (const b of $('gender-pills').querySelectorAll('.pill')) {
      b.classList.toggle('active', b === btn);
    }
    renderHairAndClassPills();
  });

  $('create-char-form').onsubmit = async e => {
    e.preventDefault();
    showFormError('create-error', '');
    const name = $('new-char-name').value.trim();
    $('btn-create-char').disabled = true;
    try {
      const res = await fetch('/api/chars/create', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`
        },
        body: JSON.stringify({
          name,
          gender: createFormState.gender,
          headId: createFormState.headId,
          classId: createFormState.classId
        })
      });
      const data = await res.json();
      if (!res.ok || !data.ok) {
        throw new Error(data.error || 'Tạo nhân vật thất bại.');
      }
      $('new-char-name').value = '';
      enterGame(data.char.id);
    } catch (err) {
      showFormError('create-error', err.message);
    } finally {
      $('btn-create-char').disabled = false;
    }
  };
}

function renderHairAndClassPills() {
  const heads = createFormState.gender === 0 ? MALE_HEADS : FEMALE_HEADS;
  const hairEl = $('hair-pills');
  hairEl.innerHTML = '';
  heads.forEach((hId, idx) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `pill ${createFormState.headId === hId ? 'active' : ''}`;
    b.textContent = `Tóc ${idx + 1}`;
    b.onclick = () => {
      createFormState.headId = hId;
      renderHairAndClassPills();
    };
    hairEl.appendChild(b);
  });

  const classEl = $('class-pills');
  classEl.innerHTML = '';
  for (let cid = 1; cid <= 6; cid++) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `pill ${createFormState.classId === cid ? 'active' : ''}`;
    b.textContent = CLASS_LABELS[cid];
    b.onclick = () => {
      createFormState.classId = cid;
      $('preview-class-label').textContent = `Phái ${CLASS_LABELS[cid]}`;
      renderHairAndClassPills();
    };
    classEl.appendChild(b);
  }
  $('preview-class-label').textContent = `Phái ${CLASS_LABELS[createFormState.classId]}`;
}

function startCharPreviewLoop() {
  if (previewAnimHandle) cancelAnimationFrame(previewAnimHandle);
  const previewImgs = new Map();
  const loadPreviewImg = url => {
    let entry = previewImgs.get(url);
    if (!entry) {
      const img = new Image();
      entry = { img, ok: false };
      img.onload = () => { entry.ok = true; };
      img.src = url;
      previewImgs.set(url, entry);
    }
    return entry.ok ? entry.img : null;
  };

  const step = now => {
    if (!$('char-screen').classList.contains('hidden') && boot) {
      const wpItem = boot.items[STARTER_WEAPON_ITEM[createFormState.classId]];
      const createLook = {
        head: createFormState.headId,
        body: createFormState.gender === 0 ? 10 : 1,
        leg: createFormState.gender === 0 ? 9 : 0,
        weapon: wpItem && wpItem.part >= 0 ? wpItem.part : 15
      };
      renderCharPreview($('char-create-preview'), boot, loadPreviewImg, createLook, now);

      for (const cv of document.querySelectorAll('canvas[data-char-preview]')) {
        const look = JSON.parse(cv.dataset.charPreview);
        renderCharPreview(cv, boot, loadPreviewImg, look, now);
      }
    }
    previewAnimHandle = requestAnimationFrame(step);
  };
  previewAnimHandle = requestAnimationFrame(step);
}

// ==========================================
// VÀO GAME & WEBSOCKET REAL-TIME
// ==========================================
function enterGame(charId, retry = false) {
  clearTimeout(reconnectTimer);
  if (!retry) reconnectAttempts = 0;
  stopLoops();
  worldState.self = null;
  worldState.map = null;
  if (!renderer) {
    renderer = new GameRenderer(canvas, boot);
  }
  renderer.resize(window.innerWidth, window.innerHeight);

  if (ws) {
    try { ws.close(); } catch {}
    ws = null;
  }

  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${proto}//${location.host}/ws?token=${encodeURIComponent(token)}&charId=${charId}`;
  const connection = new WebSocket(wsUrl);
  ws = connection;
  let rejected = false;
  let packetQueue = Promise.resolve();

  connection.onopen = () => {
    if (ws !== connection) return;
    $('char-screen').classList.add('hidden');
    $('hud').classList.remove('hidden');
    applyTouchVisibility();
    startPingLoop();
    startGameLoop();
  };

  connection.onmessage = ev => {
    packetQueue = packetQueue.then(async () => {
      if (ws !== connection) return;
      const pkt = JSON.parse(ev.data);
      if (pkt.type === 'error') rejected = true;
      if (pkt.type === 'map_enter') reconnectAttempts = 0;
      await handlePacket(pkt, connection);
    }).catch(e => console.warn('Bad WS packet', e));
  };

  connection.onclose = event => {
    packetQueue.finally(() => {
      if (ws !== connection) return;
      stopLoops();
      $('hud').classList.add('hidden');
      $('touch-controls').classList.add('hidden');
      $('death-overlay').classList.add('hidden');
      $('modal-overlay').classList.add('hidden');
      $('char-screen').classList.remove('hidden');
      if (!rejected && event.code !== 1008 && token && reconnectAttempts < 8) {
        const delay = Math.min(15000, 1000 * 2 ** reconnectAttempts++);
        showToast('Mất kết nối. Đang kết nối lại…');
        reconnectTimer = setTimeout(() => enterGame(charId, true), delay);
      } else {
        showToast('Hãy chọn nhân vật để kết nối lại hoặc đăng nhập lại.');
      }
    });
  };
}

function sendWs(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

async function handlePacket(pkt, connection = ws) {
  switch (pkt.type) {
    case 'welcome':
      break;

    case 'pong':
      if (lastPingSentAt > 0) {
        const rtt = Math.round(performance.now() - lastPingSentAt);
        $('hud-ping').textContent = `${rtt}ms`;
      }
      break;

    case 'map_enter': {
      worldState.map = null;
      worldState.self = null;
      worldState.mapId = pkt.mapId;
      worldState.zoneId = pkt.zoneId;
      worldState.zoneCount = pkt.zoneCount || 1;
      worldState.safe = !!pkt.safe;
      lastWaypointAt = performance.now();
      worldState.selectedMobUid = null;

      // Lazy-load map json (< 1 giây nhờ HTTP Cache + SW)
      const mapData = await renderer.loadMap(pkt.mapId);
      if (connection !== ws) return;
      worldState.map = mapData;
      renderer.prefetchNeighbors(mapData);
      lastSyncedSnapshot = '';

      worldState.self = {
        ...pkt.self,
        vx: 0,
        vy: 0,
        onGround: true,
        jumpsLeft: 2
      };
      if (!uiPanels) {
        uiPanels = new UiPanels(boot, sendWs, () => worldState.self, () => ({
          enabled: autoEnabled,
          wakeSupported: 'wakeLock' in navigator,
          wakeActive: !!wakeLockSentinel
        }));
      }

      worldState.players.clear();
      for (const pl of (pkt.players || [])) {
        if (pl.id !== worldState.self.id) {
          worldState.players.set(pl.id, { ...pl, targetX: pl.x, targetY: pl.y });
        }
      }

      worldState.mobs.clear();
      for (const m of (pkt.mobs || [])) {
        worldState.mobs.set(m.uid, { ...m, targetX: m.x, targetY: m.y });
      }

      worldState.drops.clear();
      for (const d of (pkt.drops || [])) {
        worldState.drops.set(d.uid, d);
      }

      updateZoneDropdown(pkt.zoneId, worldState.zoneCount);
      $('hud-map-name').textContent = pkt.mapName || mapData.name;
      updateSelfHud();
      renderSkillHotbar();
      appendChatLog('Hệ thống', `Đã đến ${pkt.mapName} (Khu ${pkt.zoneId}).`);
      break;
    }

    case 'player_join': {
      const pl = pkt.player;
      if (worldState.self && pl.id === worldState.self.id) break;
      worldState.players.set(pl.id, { ...pl, targetX: pl.x, targetY: pl.y });
      break;
    }

    case 'player_leave':
      worldState.players.delete(pkt.playerId);
      break;

    case 'player_move': {
      const p = worldState.players.get(pkt.playerId);
      if (p) {
        p.targetX = pkt.x;
        p.targetY = pkt.y;
        p.dir = pkt.dir;
        p.action = pkt.action;
      }
      break;
    }

    case 'player_appearance': {
      const pl = pkt.player;
      if (worldState.self && pl.id === worldState.self.id) {
        Object.assign(worldState.self, {
          head: pl.head, body: pl.body, leg: pl.leg, weapon: pl.weapon,
          pkMode: pl.pkMode, clanName: pl.clanName
        });
        updateSelfHud();
      } else {
        const other = worldState.players.get(pl.id);
        if (other) Object.assign(other, pl, { targetX: pl.x, targetY: pl.y });
      }
      break;
    }

    case 'player_hp': {
      const target = (worldState.self && worldState.self.id === pkt.playerId)
        ? worldState.self
        : worldState.players.get(pkt.playerId);
      if (target) {
        target.hp = pkt.hp;
        target.maxHp = pkt.maxHp;
        target.level = pkt.level;
        target.isDead = !!pkt.dead;
      }
      if (worldState.self && worldState.self.id === pkt.playerId) updateSelfHud();
      break;
    }

    case 'chat_msg':
      appendChatLog(pkt.playerName, pkt.text, pkt.channel || 'map');
      if (renderer && pkt.playerId && (pkt.channel || 'map') === 'map') {
        renderer.addChatBubble(pkt.playerId, pkt.text);
      }
      break;

    case 'skill_cast': {
      const caster = (worldState.self && worldState.self.id === pkt.playerId)
        ? worldState.self
        : worldState.players.get(pkt.playerId);
      if (caster && renderer) {
        const tMobs = (pkt.targets || [])
          .map(uid => worldState.mobs.get(uid))
          .filter(Boolean);
        renderer.triggerSkillAnim(caster, pkt.skillId, tMobs);
      }
      break;
    }

    case 'damage_popup': {
      const now = performance.now();
      for (const r of (pkt.results || [])) {
        const mob = worldState.mobs.get(r.uid);
        if (!mob) continue;
        mob.hp = r.hp;
        mob.maxHp = r.maxHp;
        mob.dead = r.dead;
        mob.dir = r.dir;
        renderer.hitMobFlash.set(r.uid, now + 180);
        const text = r.crit ? `-${r.damage}!!` : `-${r.damage}`;
        renderer.addPopup(mob.x, mob.y - 16, text, r.crit ? '#fbbf24' : '#ffffff', r.crit ? 1.28 : 1);
      }
      if (worldState.self && pkt.attackerId === worldState.self.id && pkt.expGain > 0) {
        renderer.addPopup(worldState.self.x, worldState.self.y - 46, `+${pkt.expGain} EXP`, '#4ade80', 0.95);
      }
      break;
    }

    case 'mob_move':
      for (const m of (pkt.mobs || [])) {
        const mob = worldState.mobs.get(m.uid);
        if (mob) {
          mob.targetX = m.x;
          mob.targetY = m.y;
          mob.dir = m.dir;
        }
      }
      break;

    case 'mob_respawn': {
      const m = pkt.mob;
      worldState.mobs.set(m.uid, { ...m, targetX: m.x, targetY: m.y });
      break;
    }

    case 'mob_attack': {
      const mob = worldState.mobs.get(pkt.mobUid);
      if (mob) mob.dir = pkt.dir;
      const victim = (worldState.self && worldState.self.id === pkt.targetId)
        ? worldState.self
        : worldState.players.get(pkt.targetId);
      if (victim) {
        victim.hp = pkt.hp;
        victim.maxHp = pkt.maxHp;
        victim.isDead = !!pkt.dead;
        const txt = pkt.damage < 0 ? 'Né!' : `-${pkt.damage}`;
        renderer.addPopup(victim.x, victim.y - 34, txt, pkt.damage < 0 ? '#7dd3fc' : '#ff5252', 1);
        if (worldState.self && victim.id === worldState.self.id) updateSelfHud();
      }
      break;
    }

    case 'drop_spawn':
      worldState.drops.set(pkt.drop.uid, pkt.drop);
      break;

    case 'drop_remove':
      worldState.drops.delete(pkt.uid);
      break;

    case 'self_stats':
      if (worldState.self) {
        Object.assign(worldState.self, pkt);
        delete worldState.self.type;
        updateSelfHud();
        uiPanels?.refreshIfOpen();
      }
      break;

    case 'self_state':
      if (worldState.self && pkt.self) {
        const keepVx = worldState.self.vx;
        const keepVy = worldState.self.vy;
        const keepOnG = worldState.self.onGround;
        const keepJumps = worldState.self.jumpsLeft;
        Object.assign(worldState.self, pkt.self);
        worldState.self.vx = keepVx;
        worldState.self.vy = keepVy;
        worldState.self.onGround = keepOnG;
        worldState.self.jumpsLeft = keepJumps;
        updateSelfHud();
        renderSkillHotbar();
        uiPanels?.refreshIfOpen();
      }
      break;

    case 'npc_dialog':
      uiPanels?.openNpcDialog(pkt);
      break;

    case 'shop_data':
      uiPanels?.openShop(pkt);
      break;

    case 'level_up':
      renderer.triggerLevelUp(pkt.playerId);
      if (worldState.self && pkt.playerId === worldState.self.id) {
        showToast(`Chúc mừng! Bạn đã đạt cấp ${pkt.level}!`);
      }
      break;

    case 'toast':
      showToast(pkt.text);
      break;

    case 'upgrade_result':
      break;

    case 'trade_request':
      uiPanels?.openInvitePrompt(
        'Lời mời Giao dịch',
        `${pkt.fromName} muốn giao dịch trực tiếp với bạn.`,
        'Chấp nhận',
        () => sendWs({ type: 'trade_accept', fromId: pkt.fromId })
      );
      break;

    case 'trade_open':
    case 'trade_state':
      uiPanels?.openTrade(pkt);
      break;

    case 'trade_closed':
      if (uiPanels?.currentModalKind === 'trade') uiPanels.close();
      if (pkt.message) showToast(pkt.message);
      break;

    case 'arena_invite':
      uiPanels?.openInvitePrompt(
        'Thách đấu Lôi đài Solo',
        `${pkt.fromName} thách đấu Lôi đài với bạn (Cược: ${formatNumber(pkt.betXu)} Xu).`,
        'Nhận kèo!',
        () => sendWs({ type: 'arena_accept', fromId: pkt.fromId })
      );
      break;

    case 'arena_start':
      showToast(`⚔ Lôi đài: ${pkt.p1Name} vs ${pkt.p2Name}!`);
      break;

    case 'pvp_hit': {
      const target = (worldState.self && worldState.self.id === pkt.targetId)
        ? worldState.self
        : worldState.players.get(pkt.targetId);
      if (target) {
        target.hp = pkt.hp;
        target.maxHp = pkt.maxHp;
        target.isDead = !!pkt.dead;
        const txt = pkt.crit ? `-${pkt.damage}!!` : `-${pkt.damage}`;
        renderer?.addPopup(target.x, target.y - 34, txt, pkt.crit ? '#fbbf24' : '#ff4d4d', pkt.crit ? 1.25 : 1);
        if (worldState.self && target.id === worldState.self.id) updateSelfHud();
      }
      break;
    }

    case 'arena_result':
      showToast(`🏆 ${pkt.winnerName} chiến thắng Lôi đài (+${formatNumber(pkt.prizeXu)} Xu)!`);
      break;

    case 'events_data':
      uiPanels?.openEvents(pkt);
      break;

    case 'clan_invite':
      uiPanels?.openInvitePrompt(
        'Lời mời Gia tộc',
        `${pkt.fromName} mời bạn gia nhập Gia tộc [${pkt.clanName}].`,
        'Gia nhập',
        () => sendWs({ type: 'clan_accept', clanId: pkt.clanId })
      );
      break;

    case 'clan_data':
      uiPanels?.openClan(pkt);
      break;

    case 'error':
      showToast(pkt.message || 'Có lỗi xảy ra.');
      break;
  }
}

function updateSelfHud() {
  const s = worldState.self;
  if (!s || !boot) return;
  $('hud-name').textContent = s.name;
  $('hud-level').textContent = `Lv.${s.level}`;
  const clanTag = $('hud-clan-tag');
  if (s.clanName) {
    clanTag.textContent = `[${s.clanName}]`;
    clanTag.classList.remove('hidden');
  } else {
    clanTag.classList.add('hidden');
  }
  $('btn-pk-toggle').classList.toggle('pk-active', (s.pkMode || 0) === 3);
  $('btn-pk-toggle').textContent = (s.pkMode || 0) === 3 ? 'PK: Đỏ' : 'PK';

  const hpPct = Math.max(0, Math.min(100, (s.hp / Math.max(1, s.maxHp)) * 100));
  const mpPct = Math.max(0, Math.min(100, (s.mp / Math.max(1, s.maxMp)) * 100));
  $('hud-hp-bar').style.width = `${hpPct}%`;
  $('hud-hp-text').textContent = `${s.hp}/${s.maxHp}`;
  $('hud-mp-bar').style.width = `${mpPct}%`;
  $('hud-mp-text').textContent = `${s.mp}/${s.maxMp}`;

  const startExp = expSumBeforeLevel(s.level);
  const lvlSpan = Math.max(1, getExpForLevel(s.level));
  const expPct = Math.max(0, Math.min(100, ((s.exp - startExp) / lvlSpan) * 100));
  $('hud-exp-bar').style.width = `${expPct}%`;
  $('hud-exp-text').textContent = `${expPct.toFixed(1)}%`;

  $('hud-xu').textContent = formatNumber(s.xu);
  $('hud-yen').textContent = formatNumber(s.yen);
  $('hud-luong').textContent = formatNumber(s.luong);

  const pill = $('hud-quest-pill');
  const tt = boot.tasks?.[s.taskId];
  if (tt && s.taskIndex < (tt.subnames || []).length) {
    const sub = tt.subnames[s.taskIndex];
    const req = tt.counts?.[s.taskIndex] > 0 ? ` (${s.taskCount}/${tt.counts[s.taskIndex]})` : '';
    pill.innerHTML = `<b>NV: ${escapeHtml(tt.name)}</b>${escapeHtml(sub)}${req}`;
    pill.classList.remove('hidden');
  } else {
    pill.classList.add('hidden');
  }

  $('death-overlay').classList.toggle('hidden', !s.isDead);
}

function expSumBeforeLevel(level) {
  const exps = boot?.exps || [];
  let sum = 0;
  for (let i = 0; i < level && i < exps.length; i++) sum += exps[i];
  return sum;
}

function getExpForLevel(level) {
  const exps = boot?.exps || [];
  if (level >= 0 && level < exps.length) return exps[level];
  return 999999999;
}

function updateZoneDropdown(curZone, count) {
  const sel = $('hud-zone-select');
  sel.innerHTML = '';
  for (let i = 0; i < count; i++) {
    const opt = document.createElement('option');
    opt.value = i;
    opt.textContent = `Khu ${i}`;
    opt.selected = i === curZone;
    sel.appendChild(opt);
  }
}

function renderSkillHotbar() {
  const s = worldState.self;
  const bar = $('skill-hotbar');
  if (!s || !boot) return;
  bar.innerHTML = '';
  const slots = s.hotkeySkills || [s.selectedSkillId, 0, -1, -1, -1];
  slots.forEach((skillId, idx) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `slot-btn ${skillId === s.selectedSkillId ? 'active' : ''}`;
    btn.innerHTML = `<span class="slot-key">${idx + 1}</span>`;
    if (skillId >= 0 && boot.skillTemplates[skillId]) {
      const tpl = boot.skillTemplates[skillId];
      btn.title = tpl.name;
      const img = document.createElement('img');
      img.src = `/assets/img/small/${tpl.icon}.png`;
      img.alt = tpl.name;
      btn.appendChild(img);
      btn.onclick = () => castSkill(skillId);
    }
    bar.appendChild(btn);
  });
}

function castSkill(skillId) {
  const s = worldState.self;
  if (!s || s.isDead) return;
  s.selectedSkillId = skillId;
  renderSkillHotbar();

  const targets = [];
  if (worldState.selectedMobUid != null) {
    const sm = worldState.mobs.get(worldState.selectedMobUid);
    if (sm && !sm.dead) targets.push(sm.uid);
  }
  sendWs({ type: 'attack', skillId, targets });
}

function pickupNearestDrop() {
  const s = worldState.self;
  if (!s || s.isDead) return;
  let best = null;
  let bestDist = Infinity;
  for (const d of worldState.drops.values()) {
    const dist = Math.hypot(d.x - s.x, d.y - s.y);
    if (dist <= 150 && dist < bestDist) {
      bestDist = dist;
      best = d;
    }
  }
  if (best) {
    sendWs({ type: 'pickup', uid: best.uid });
  }
}

function tryEnterWaypoint(manualTrigger = false) {
  const s = worldState.self;
  const map = worldState.map;
  if (!s || !map || s.isDead) return;
  const now = performance.now();
  if (now - lastWaypointAt < 1100) return;

  const wp = findWaypointNear(map, s.x, s.y);
  if (!wp) {
    if (manualTrigger) showToast('Hãy đứng gần cổng ánh sáng xanh để chuyển bản đồ.');
    return;
  }
  const atMapEdge = wp.minX <= 30 || wp.maxX >= map.pxw - 30;
  if (manualTrigger || atMapEdge) {
    lastWaypointAt = now;
    sendWs({ type: 'waypoint' });
  }
}

// ==========================================
// VÒNG LẶP VẬT LÝ + RENDER 60FPS + AUTO-PLAY
// ==========================================
const autoInput = { left: false, right: false, up: false, down: false, jumpPressed: false };
const autoSkillCooldowns = new Map();

async function acquireWakeLock() {
  if (!('wakeLock' in navigator) || wakeLockSentinel) return;
  try {
    wakeLockSentinel = await navigator.wakeLock.request('screen');
    wakeLockSentinel.addEventListener('release', () => {
      wakeLockSentinel = null;
    });
  } catch {
    wakeLockSentinel = null;
  }
}

function releaseWakeLock() {
  if (wakeLockSentinel) {
    wakeLockSentinel.release().catch(() => {});
    wakeLockSentinel = null;
  }
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && autoEnabled) {
    acquireWakeLock();
  }
});

function setAutoEnabled(enabled) {
  autoEnabled = !!enabled;
  autoInput.left = false;
  autoInput.right = false;
  autoInput.up = false;
  autoInput.down = false;
  autoInput.jumpPressed = false;
  $('btn-auto-toggle')?.classList.toggle('active', autoEnabled);
  $('touch-auto')?.classList.toggle('active', autoEnabled);
  if (autoEnabled) {
    acquireWakeLock();
    showToast('Đã bật Auto Treo máy (tự đánh, nhặt đồ, ăn HP/MP & giữ sáng màn hình).');
  } else {
    releaseWakeLock();
    showToast('Đã tắt Auto Treo máy.');
  }
}

function stepAutoPlay(now) {
  autoInput.left = false;
  autoInput.right = false;
  autoInput.up = false;
  autoInput.down = false;

  const s = worldState.self;
  if (!autoEnabled || !s || !worldState.map || s.isDead) return;
  if (now < manualMoveHoldUntil) return;

  const cfg = s.autoSettings || { hpThreshold: 50, mpThreshold: 35, pickupAll: true };

  // 1. Tự hồi HP/MP khi dưới ngưỡng
  if (now - lastAutoPotAt >= 1100) {
    const hpPct = (s.hp / Math.max(1, s.maxHp)) * 100;
    const mpPct = (s.mp / Math.max(1, s.maxMp)) * 100;
    if (hpPct < (cfg.hpThreshold ?? 50)) {
      lastAutoPotAt = now;
      quickUseType(16) || quickUseType(18);
    } else if (mpPct < (cfg.mpThreshold ?? 35)) {
      lastAutoPotAt = now;
      quickUseType(17);
    }
  }

  // 2. Tự nhặt vật phẩm/Yên gần nhất
  if (cfg.pickupAll !== false) {
    let bestDrop = null;
    let bestDist = Infinity;
    for (const d of worldState.drops.values()) {
      const dist = Math.hypot(d.x - s.x, d.y - s.y);
      if (dist <= 260 && dist < bestDist) {
        bestDist = dist;
        bestDrop = d;
      }
    }
    if (bestDrop) {
      if (bestDist <= 120) {
        if (now - lastAutoThinkAt >= 260) {
          lastAutoThinkAt = now;
          sendWs({ type: 'pickup', uid: bestDrop.uid });
        }
        return;
      }
      if (bestDrop.x < s.x - 14) autoInput.left = true;
      else if (bestDrop.x > s.x + 14) autoInput.right = true;
      if (bestDrop.y < s.y - 36 && s.onGround) autoInput.jumpPressed = true;
      return;
    }
  }

  // 3. Tìm quái gần nhất đang sống (ưu tiên bỏ qua bù nhìn id=0 trừ khi không có quái khác)
  let targetMob = null;
  let bestMobDist = Infinity;
  for (const m of worldState.mobs.values()) {
    if (m.dead) continue;
    if (m.templateId === 0 && worldState.mobs.size > 1) continue;
    const d = Math.hypot(m.x - s.x, (m.y - s.y) * 1.4);
    if (d < bestMobDist) {
      bestMobDist = d;
      targetMob = m;
    }
  }
  if (!targetMob) return;
  worldState.selectedMobUid = targetMob.uid;

  // Chọn chiêu luân phiên đang hồi chiêu xong & đủ MP
  const hotkeys = (s.hotkeySkills || []).filter(id => id >= 0 && boot?.skillTemplates[id]);
  let chosenSkill = s.selectedSkillId ?? 0;
  if (hotkeys.length > 0) {
    for (let k = 0; k < hotkeys.length; k++) {
      const candId = hotkeys[(autoSkillCursor + k) % hotkeys.length];
      const tpl = boot.skillTemplates[candId];
      const pt = s.skills?.[candId] ?? 0;
      const lvData = tpl?.levels?.find(l => l.point === pt) || tpl?.levels?.[0];
      const readyAt = autoSkillCooldowns.get(candId) || 0;
      if (lvData && s.mp >= lvData.manaUse && now >= readyAt) {
        chosenSkill = candId;
        break;
      }
    }
  }

  const tpl = boot?.skillTemplates[chosenSkill];
  const pt = s.skills?.[chosenSkill] ?? 0;
  const lvData = tpl?.levels?.find(l => l.point === pt) || tpl?.levels?.[0];
  const reachX = Math.max(72, (lvData?.dx || 80) + 55);
  const reachY = Math.max(72, (lvData?.dy || 60) + 60);

  const dx = targetMob.x - s.x;
  const dy = targetMob.y - s.y;
  if (Math.abs(dx) > reachX * 0.78) {
    if (dx < 0) autoInput.left = true;
    else autoInput.right = true;
    if (now - lastAutoPosCheckAt > 900) {
      if (Math.abs(s.x - lastAutoX) < 6 && s.onGround) {
        autoInput.jumpPressed = true;
      }
      lastAutoX = s.x;
      lastAutoPosCheckAt = now;
    }
  }
  if (dy < -42 && s.onGround) {
    autoInput.jumpPressed = true;
  } else if (dy > 48 && s.onGround) {
    autoInput.down = true;
  }

  if (Math.abs(dx) <= reachX && Math.abs(dy) <= reachY) {
    s.dir = dx >= 0 ? 1 : -1;
    const minWait = Math.max(320, (lvData?.cooldown || 500) - 60);
    if (now - lastAutoAttackAt >= 320 && now >= (autoSkillCooldowns.get(chosenSkill) || 0)) {
      lastAutoAttackAt = now;
      autoSkillCooldowns.set(chosenSkill, now + minWait);
      autoSkillCursor = (autoSkillCursor + 1) % Math.max(1, hotkeys.length);
      castSkill(chosenSkill);
    }
  }
}

function startGameLoop() {
  if (gameLoopHandle) cancelAnimationFrame(gameLoopHandle);
  let prevTime = performance.now();

  const frame = now => {
    const dtMs = Math.min(48, Math.max(4, now - prevTime));
    prevTime = now;

    if (worldState.self && worldState.map) {
      stepAutoPlay(now);
      const effectiveInput = {
        left: inputState.left || autoInput.left,
        right: inputState.right || autoInput.right,
        up: inputState.up || autoInput.up,
        down: inputState.down || autoInput.down,
        get jumpPressed() { return inputState.jumpPressed || autoInput.jumpPressed; },
        set jumpPressed(v) { inputState.jumpPressed = v; autoInput.jumpPressed = v; }
      };

      // 1. Vật lý di chuyển mượt 60Hz phía client
      stepPlayerPhysics(worldState.self, effectiveInput, worldState.map, dtMs);
      $('hud-coords').textContent = `${Math.round(worldState.self.x)}, ${Math.round(worldState.self.y)}`;

      // Tự động qua cổng nếu chạm rìa mép bản đồ có waypoint
      tryEnterWaypoint(false);

      // Đồng bộ vị trí định kỳ lên server (mỗi 70ms hoặc ngay khi đổi action/dir)
      const s = worldState.self;
      const rx = Math.round(s.x);
      const ry = Math.round(s.y);
      const snap = `${rx},${ry},${s.dir},${s.action}`;
      if (snap !== lastSyncedSnapshot && now - lastMoveSyncAt >= 70) {
        lastSyncedSnapshot = snap;
        lastMoveSyncAt = now;
        sendWs({ type: 'move', x: rx, y: ry, dir: s.dir, action: s.action });
      }

      // 2. Nội suy mượt chuyển động người chơi khác & quái
      const smoothing = 1 - Math.exp(-dtMs / 45);
      for (const p of worldState.players.values()) {
        p.x += (p.targetX - p.x) * smoothing;
        p.y += (p.targetY - p.y) * smoothing;
      }
      for (const m of worldState.mobs.values()) {
        m.x += (m.targetX - m.x) * smoothing;
        m.y += (m.targetY - m.y) * smoothing;
      }
    }

    if (renderer) {
      renderer.render(worldState, now);
    }
    gameLoopHandle = requestAnimationFrame(frame);
  };
  gameLoopHandle = requestAnimationFrame(frame);
}

function startPingLoop() {
  if (pingTimer) clearInterval(pingTimer);
  pingTimer = setInterval(() => {
    lastPingSentAt = performance.now();
    sendWs({ type: 'ping' });
  }, 8000);
}

function stopLoops() {
  if (autoEnabled) setAutoEnabled(false);
  if (gameLoopHandle) cancelAnimationFrame(gameLoopHandle);
  gameLoopHandle = null;
  if (pingTimer) clearInterval(pingTimer);
  pingTimer = null;
}

function openPlayerContext(pl) {
  selectedTargetPlayer = pl;
  $('ctx-player-name').textContent = `${pl.name} (Lv.${pl.level})`;
  $('player-ctx-menu').classList.remove('hidden');
}

// ==========================================
// ĐIỀU KHIỂN DESKTOP + CẢM ỨNG ĐA ĐIỂM
// ==========================================
function bindHudAndInput() {
  window.addEventListener('blur', () => {
    Object.keys(inputState).forEach(key => { inputState[key] = false; });
  });
  window.addEventListener('keydown', e => {
    if (document.activeElement && ['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) {
      if (e.key === 'Escape') document.activeElement.blur();
      return;
    }
    if (!worldState.self) return;

    switch (e.code) {
      case 'ArrowLeft':
      case 'KeyA':
        inputState.left = true;
        manualMoveHoldUntil = performance.now() + 900;
        break;
      case 'ArrowRight':
      case 'KeyD':
        inputState.right = true;
        manualMoveHoldUntil = performance.now() + 900;
        break;
      case 'ArrowDown':
      case 'KeyS':
        inputState.down = true;
        manualMoveHoldUntil = performance.now() + 900;
        break;
      case 'ArrowUp':
      case 'KeyW':
      case 'Space':
        e.preventDefault();
        manualMoveHoldUntil = performance.now() + 900;
        if (!e.repeat) {
          inputState.up = true;
          inputState.jumpPressed = true;
          tryEnterWaypoint(false);
        }
        break;
      case 'KeyE': {
        const s = worldState.self;
        const nearestNpc = (worldState.map?.npcs || []).find(n => Math.hypot(n.x - s.x, n.y - s.y) <= 75);
        if (nearestNpc) {
          sendWs({ type: 'npc_interact', npcId: nearestNpc.templateId });
        } else {
          tryEnterWaypoint(true);
        }
        break;
      }
      case 'KeyQ':
        quickUseType(16); // Bình HP
        break;
      case 'KeyR':
        quickUseType(17); // Bình MP
        break;
      case 'KeyJ':
      case 'KeyF':
      case 'KeyZ':
        castSkill(worldState.self.selectedSkillId ?? 0);
        break;
      case 'KeyV':
      case 'KeyG':
        pickupNearestDrop();
        break;
      case 'Digit1':
      case 'Digit2':
      case 'Digit3':
      case 'Digit4':
      case 'Digit5': {
        const idx = Number(e.code.replace('Digit', '')) - 1;
        const sk = worldState.self.hotkeySkills?.[idx];
        if (sk != null && sk >= 0) castSkill(sk);
        break;
      }
      case 'Enter':
        e.preventDefault();
        $('chat-input').focus();
        break;
    }
  });

  window.addEventListener('keyup', e => {
    switch (e.code) {
      case 'ArrowLeft':
      case 'KeyA':
        inputState.left = false;
        break;
      case 'ArrowRight':
      case 'KeyD':
        inputState.right = false;
        break;
      case 'ArrowDown':
      case 'KeyS':
        inputState.down = false;
        break;
      case 'ArrowUp':
      case 'KeyW':
      case 'Space':
        inputState.up = false;
        break;
    }
  });

  // Click / chạm lên Canvas (chọn quái đánh / nhặt đồ / xem NPC / mời người chơi / vào cổng)
  canvas.addEventListener('pointerdown', e => {
    if (!worldState.self || !worldState.map || !renderer) return;
    if (e.target !== canvas || !$('modal-overlay').classList.contains('hidden')) return;

    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    const wx = ((e.clientX - rect.left) * scaleX + renderer.camX) / Z;
    const wy = ((e.clientY - rect.top) * scaleY + renderer.camY) / Z;

    // 1. Click gần vật phẩm rơi -> nhặt
    for (const d of worldState.drops.values()) {
      if (Math.hypot(d.x - wx, d.y - 12 - wy) <= 34) {
        sendWs({ type: 'pickup', uid: d.uid });
        return;
      }
    }

    // 2. Click vào người chơi khác -> Giao dịch / Lôi đài / Gia tộc / PK
    for (const pl of worldState.players.values()) {
      if (Math.hypot(pl.x - wx, pl.y - 26 - wy) <= 34) {
        if ((worldState.self.pkMode === 3 && !worldState.safe) || worldState.mapId === 111) {
          sendWs({ type: 'attack_player', targetId: pl.id, skillId: worldState.self.selectedSkillId ?? 0 });
        }
        openPlayerContext(pl);
        return;
      }
    }

    // 3. Click vào quái -> chọn và tung chiêu
    for (const m of worldState.mobs.values()) {
      if (!m.dead && Math.hypot(m.x - wx, m.y - 20 - wy) <= 44) {
        worldState.selectedMobUid = m.uid;
        worldState.self.dir = m.x >= worldState.self.x ? 1 : -1;
        castSkill(worldState.self.selectedSkillId ?? 0);
        return;
      }
    }

    // 4. Click vào cổng -> thử qua map nếu đang đứng gần
    for (const wp of (worldState.map.waypoints || [])) {
      const cx = (wp.minX + wp.maxX) * 0.5;
      const cy = (wp.minY + wp.maxY) * 0.5;
      if (Math.hypot(cx - wx, cy - wy) <= 48) {
        tryEnterWaypoint(true);
        return;
      }
    }

    // 5. Click vào NPC -> mở hộp thoại NPC (Shop, Nhiệm vụ, Rương, Dịch chuyển...)
    for (const n of (worldState.map.npcs || [])) {
      if (Math.hypot(n.x - wx, n.y - 24 - wy) <= 38) {
        sendWs({ type: 'npc_interact', npcId: n.templateId });
        return;
      }
    }
    $('player-ctx-menu').classList.add('hidden');
  });

  // Cảm ứng đa điểm (PointerEvents riêng cho từng nút)
  bindHoldButton($('touch-dpad').querySelector('[data-dir="left"]'), down => { inputState.left = down; manualMoveHoldUntil = performance.now() + 900; });
  bindHoldButton($('touch-dpad').querySelector('[data-dir="right"]'), down => { inputState.right = down; manualMoveHoldUntil = performance.now() + 900; });
  bindHoldButton($('touch-dpad').querySelector('[data-dir="down"]'), down => { inputState.down = down; manualMoveHoldUntil = performance.now() + 900; });
  bindHoldButton($('touch-dpad').querySelector('[data-dir="up"]'), down => {
    inputState.up = down;
    manualMoveHoldUntil = performance.now() + 900;
    if (down) inputState.jumpPressed = true;
  });
  bindHoldButton($('touch-jump'), down => {
    if (down) inputState.jumpPressed = true;
  });
  bindHoldButton($('touch-attack'), down => {
    if (down && worldState.self) castSkill(worldState.self.selectedSkillId ?? 0);
  });
  $('touch-auto').addEventListener('pointerdown', e => {
    e.preventDefault();
    setAutoEnabled(!autoEnabled);
  });
  $('touch-pickup').addEventListener('pointerdown', e => {
    e.preventDefault();
    pickupNearestDrop();
  });
  $('touch-portal').addEventListener('pointerdown', e => {
    e.preventDefault();
    tryEnterWaypoint(true);
  });

  // Chat map & Chat gia tộc
  $('chat-form').onsubmit = e => {
    e.preventDefault();
    const txt = $('chat-input').value.trim();
    if (txt) {
      const ch = $('chat-channel')?.value || 'map';
      sendWs({ type: ch === 'clan' ? 'clan_chat' : 'chat', text: txt });
      $('chat-input').value = '';
    }
    $('chat-input').blur();
  };

  // Đổi khu
  $('hud-zone-select').onchange = e => {
    sendWs({ type: 'change_zone', zoneId: Number(e.target.value) });
  };

  // Hồi sinh
  $('btn-revive-home').onclick = () => sendWs({ type: 'revive', atSpot: false });
  $('btn-revive-spot').onclick = () => sendWs({ type: 'revive', atSpot: true });

  // Đăng xuất về màn hình chọn nhân vật
  $('btn-logout-game').onclick = () => {
    clearTimeout(reconnectTimer);
    stopLoops();
    if (ws) ws.close();
    ws = null;
    worldState.self = null;
    $('hud').classList.add('hidden');
    $('touch-controls').classList.add('hidden');
    $('death-overlay').classList.add('hidden');
    $('player-ctx-menu').classList.add('hidden');
    tryLoadCharScreen();
  };

  $('btn-toggle-touch').onclick = () => {
    touchEnabled = !touchEnabled;
    applyTouchVisibility();
  };

  $('modal-close').onclick = () => uiPanels ? uiPanels.close() : $('modal-overlay').classList.add('hidden');

  // Menu RPG Bước 3 & Bước 4
  $('btn-panel-char').onclick = () => uiPanels?.openCharacter();
  $('btn-panel-bag').onclick = () => uiPanels?.openBag();
  $('btn-panel-skills').onclick = () => uiPanels?.openSkills();
  $('btn-panel-task').onclick = () => uiPanels?.openTask();
  $('hud-quest-pill').onclick = () => uiPanels?.openTask();

  $('btn-panel-upgrade').onclick = () => uiPanels?.openUpgrade();
  $('btn-panel-clan').onclick = () => sendWs({ type: 'clan_info' });
  $('btn-panel-events').onclick = () => sendWs({ type: 'get_events' });
  $('btn-pk-toggle').onclick = () => {
    const cur = worldState.self?.pkMode || 0;
    sendWs({ type: 'set_pk', pkMode: cur === 3 ? 0 : 3 });
  };
  $('btn-auto-toggle').onclick = () => setAutoEnabled(!autoEnabled);
  $('btn-auto-cfg').onclick = () => uiPanels?.openAutoConfig();

  // Context menu tương tác người chơi
  $('ctx-btn-close').onclick = () => $('player-ctx-menu').classList.add('hidden');
  $('ctx-btn-trade').onclick = () => {
    if (selectedTargetPlayer) sendWs({ type: 'trade_invite', targetId: selectedTargetPlayer.id });
    $('player-ctx-menu').classList.add('hidden');
  };
  $('ctx-btn-arena').onclick = () => {
    if (selectedTargetPlayer) sendWs({ type: 'arena_challenge', targetId: selectedTargetPlayer.id, betXu: 0 });
    $('player-ctx-menu').classList.add('hidden');
  };
  $('ctx-btn-clan').onclick = () => {
    if (selectedTargetPlayer) sendWs({ type: 'clan_invite', target: String(selectedTargetPlayer.id) });
    $('player-ctx-menu').classList.add('hidden');
  };
  $('ctx-btn-pk').onclick = () => {
    if (selectedTargetPlayer) sendWs({ type: 'attack_player', targetId: selectedTargetPlayer.id, skillId: worldState.self?.selectedSkillId ?? 0 });
  };
}

function quickUseType(itemType) {
  const s = worldState.self;
  if (!s || s.isDead || !boot) return false;
  const idx = (s.bag || []).findIndex(it => boot.items[it.itemId]?.type === itemType);
  if (idx >= 0) {
    sendWs({ type: 'use_item', bagIdx: idx });
    return true;
  }
  return false;
}

function bindHoldButton(btn, onToggle) {
  const activePointers = new Set();
  btn.addEventListener('pointerdown', e => {
    e.preventDefault();
    btn.setPointerCapture(e.pointerId);
    activePointers.add(e.pointerId);
    btn.classList.add('pressed');
    onToggle(true);
  });
  const release = e => {
    if (!activePointers.has(e.pointerId)) return;
    activePointers.delete(e.pointerId);
    if (activePointers.size === 0) {
      btn.classList.remove('pressed');
      onToggle(false);
    }
  };
  btn.addEventListener('pointerup', release);
  btn.addEventListener('pointercancel', release);
  btn.addEventListener('lostpointercapture', release);
}

function applyTouchVisibility() {
  document.body.classList.toggle('touch-mode', touchEnabled);
  $('touch-controls').classList.toggle('hidden', !touchEnabled);
}

function updateOrientationHint() {
  const isTouch = ('ontouchstart' in window) || navigator.maxTouchPoints > 0;
  const isPortrait = window.innerHeight > window.innerWidth && window.innerWidth < 768;
  $('rotate-hint').classList.toggle('hidden', !(isTouch && isPortrait));
}

function appendChatLog(sender, text, channel = 'map') {
  const logEl = $('chat-log');
  const div = document.createElement('div');
  div.className = `chat-entry ${channel === 'clan' ? 'clan' : channel === 'system' ? 'system' : ''}`;
  const tag = channel === 'clan' ? '[Tộc] ' : channel === 'system' ? '[TB] ' : '';
  div.innerHTML = `${tag}<b>${escapeHtml(sender)}:</b> ${escapeHtml(text)}`;
  logEl.appendChild(div);
  while (logEl.children.length > 40) logEl.removeChild(logEl.firstChild);
  logEl.scrollTop = logEl.scrollHeight;
}

function showToast(text) {
  const box = $('toast-container');
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = text;
  box.appendChild(el);
  setTimeout(() => el.remove(), 2800);
}

function formatNumber(n) {
  return Number(n || 0).toLocaleString('vi-VN');
}

function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

init();
