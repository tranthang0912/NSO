const EQUIP_SLOT_NAMES = [
  'Nón', 'Vũ khí', 'Áo', 'Dây chuyền',
  'Găng tay', 'Nhẫn', 'Quần', 'Ngọc bội',
  'Giày', 'Phù', 'Thú nuôi', 'Mặt nạ',
  'Áo choàng', 'Bao tay', 'Mắt thần', 'Bí kíp'
];

const CLASS_NAMES = {
  0: 'Tập sự',
  1: 'Ninja Kiếm',
  2: 'Ninja Phi tiêu',
  3: 'Ninja Kunai',
  4: 'Ninja Cung',
  5: 'Ninja Đao',
  6: 'Ninja Quạt'
};

// Hằng số cường hóa chuẩn NSO (GameData.java)
const UP_CRYSTAL = [1, 4, 16, 64, 256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304];
const UP_CLOTHE = [4, 9, 33, 132, 177, 256, 656, 2880, 3968, 6016, 13440, 54144, 71680, 108544, 225280, 1032192];
const UP_ADORN = [6, 14, 50, 256, 320, 512, 1024, 5120, 6016, 9088, 19904, 86016, 108544, 166912, 360448, 1589248];
const UP_WEAPON = [18, 42, 132, 627, 864, 1360, 2816, 13824, 17792, 26880, 54016, 267264, 315392, 489472, 1032192, 4587520];
const COIN_UP_CRYSTAL = [10, 40, 160, 640, 2560, 10240, 40960, 163840, 655360, 1310720, 3932160, 11796480];
const COIN_UP_CLOTHE = [120, 270, 990, 3960, 5310, 7680, 19680, 86400, 119040, 180480, 403200, 1624320, 2150400, 3256320, 6758400, 10137600];
const COIN_UP_ADORN = [180, 420, 1500, 7680, 9600, 15360, 30720, 153600, 180480, 272640, 597120, 2580480, 3256320, 5007360, 10813440, 16220160];
const COIN_UP_WEAPON = [540, 1260, 3960, 18810, 25920, 40800, 84480, 414720, 533760, 806400, 1620480, 8017920, 9461760, 14684160, 22026240, 33039360];
const GOLD_UP = [1, 2, 3, 4, 5, 10, 15, 20, 50, 100, 150, 200, 300, 400, 500, 600];
const MAX_PERCENT = [80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5];

export class UiPanels {
  constructor(boot, sendWs, getSelf, getAutoInfo) {
    this.boot = boot;
    this.sendWs = sendWs;
    this.getSelf = getSelf;
    this.getAutoInfo = getAutoInfo || (() => ({ enabled: false, wakeSupported: false, wakeActive: false }));
    this.currentModalKind = null;
    this.lastShopData = null;
    this.lastTradeData = null;
    this.lastEventsData = null;
    this.lastClanData = null;
    this.selectedBagIdx = 0;

    // State Cường hóa
    this.upTab = 'upgrade'; // 'upgrade' | 'combine'
    this.upTarget = { type: 'equipped', idx: 1 };
    this.upStoneCounts = new Map(); // bagIdx -> count chosen
    this.upUseIns = false;
    this.upUseLuong = false;

    // State Giao dịch
    this.tradeSelectedBag = new Set();

    this.overlay = document.getElementById('modal-overlay');
    this.titleEl = document.getElementById('modal-title');
    this.bodyEl = document.getElementById('modal-body');
  }

  close() {
    this.currentModalKind = null;
    this.overlay.classList.add('hidden');
  }

  refreshIfOpen() {
    if (this.overlay.classList.contains('hidden')) return;
    if (this.currentModalKind === 'char') this.openCharacter();
    else if (this.currentModalKind === 'bag') this.openBag();
    else if (this.currentModalKind === 'box') this.openBox();
    else if (this.currentModalKind === 'skills') this.openSkills();
    else if (this.currentModalKind === 'task') this.openTask();
    else if (this.currentModalKind === 'upgrade') this.openUpgrade();
    else if (this.currentModalKind === 'shop' && this.lastShopData) this.openShop(this.lastShopData);
  }

  // 1. Nhân vật + Cộng điểm Tiềm năng + Trang bị đang mặc
  openCharacter() {
    const s = this.getSelf();
    if (!s) return;
    this.currentModalKind = 'char';
    const pot = s.potential || [0, 0, 0, 0];
    const potNames = ['Sức mạnh', 'Thân pháp', 'Thể lực', 'Chakra'];

    const eqHtml = EQUIP_SLOT_NAMES.map((slotName, idx) => {
      const eq = s.equipped?.[idx];
      if (!eq) {
        return `<div class="eq-slot empty" title="${slotName}"><span>${slotName}</span></div>`;
      }
      const tpl = this.boot.items[eq.itemId];
      const up = eq.upgrade > 0 ? `<em class="up-tag">+${eq.upgrade}</em>` : '';
      return `
        <div class="eq-slot filled" data-unequip="${idx}" title="${esc(tpl?.name || '')} (Click để tháo)">
          <img src="/assets/img/small/${tpl?.icon || 0}.png" alt="" />
          ${up}
          <small>${slotName}</small>
        </div>
      `;
    }).join('');

    const potHtml = potNames.map((label, idx) => `
      <div class="stat-row">
        <span>${label}: <b>${pot[idx]}</b></span>
        ${s.point > 0 ? `
          <div class="stat-btns">
            <button type="button" class="mini-btn" data-pot-idx="${idx}" data-pot-amt="1">+1</button>
            ${s.point >= 10 ? `<button type="button" class="mini-btn" data-pot-idx="${idx}" data-pot-amt="10">+10</button>` : ''}
          </div>
        ` : ''}
      </div>
    `).join('');

    this.titleEl.textContent = `Nhân vật • ${s.name}`;
    this.bodyEl.innerHTML = `
      <div class="two-col">
        <div>
          <p class="sec-title">Chỉ số chiến đấu (${CLASS_NAMES[s.classId] || 'Tập sự'})</p>
          <div class="stat-card">
            <div>Cấp độ: <b>${s.level}</b> • HP: <b>${s.hp}/${s.maxHp}</b> • MP: <b>${s.mp}/${s.maxMp}</b></div>
            <div>Tấn công: <b class="hl">${s.damage2} ~ ${s.damage}</b> • Chí mạng: <b>${(s.fatal / 10).toFixed(1)}%</b></div>
            <div>Né tránh: <b>${s.miss}</b> • Chính xác: <b>${s.exactly}</b></div>
            <div>Kháng: Hỏa <b>${s.resFire}</b> • Băng <b>${s.resIce}</b> • Phong <b>${s.resWind}</b></div>
            ${s.clanName ? `<div>Gia tộc: <b class="hl">[${esc(s.clanName)}]</b></div>` : ''}
          </div>

          <p class="sec-title" style="margin-top:10px">Tiềm năng (Dư: <b class="hl">${s.point}</b>)</p>
          <div class="stat-card">${potHtml}</div>
        </div>

        <div>
          <p class="sec-title">Trang bị đang mặc (Click để tháo)</p>
          <div class="eq-grid">${eqHtml}</div>
        </div>
      </div>
    `;
    this.overlay.classList.remove('hidden');

    this.bodyEl.querySelectorAll('[data-pot-idx]').forEach(btn => {
      btn.onclick = () => this.sendWs({
        type: 'up_potential',
        index: Number(btn.dataset.potIdx),
        points: Number(btn.dataset.potAmt)
      });
    });
    this.bodyEl.querySelectorAll('[data-unequip]').forEach(el => {
      el.onclick = () => this.sendWs({ type: 'unequip_item', slot: Number(el.dataset.unequip) });
    });
  }

  // 2. Hành trang
  openBag() {
    const s = this.getSelf();
    if (!s) return;
    this.currentModalKind = 'bag';
    const bag = s.bag || [];
    if (this.selectedBagIdx >= bag.length) this.selectedBagIdx = Math.max(0, bag.length - 1);

    const gridHtml = bag.map((it, idx) => {
      const tpl = this.boot.items[it.itemId];
      const up = it.upgrade > 0 ? `<em class="up-tag">+${it.upgrade}</em>` : '';
      const qty = it.quantity > 1 ? `<b class="qty-tag">${it.quantity}</b>` : '';
      return `
        <div class="inv-slot ${idx === this.selectedBagIdx ? 'selected' : ''}" data-bag-idx="${idx}">
          <img src="/assets/img/small/${tpl?.icon || 0}.png" alt="" />
          ${up}${qty}
        </div>
      `;
    }).join('');

    const selItem = bag[this.selectedBagIdx];
    const selDetailHtml = selItem ? this.renderItemDetail(selItem, true) : '<p class="muted">Chọn vật phẩm để xem chi tiết.</p>';

    this.titleEl.textContent = `Hành trang (${bag.length}/${s.bagCapacity})`;
    this.bodyEl.innerHTML = `
      <div class="bag-toolbar">
        <span>🟡 Xu: <b>${fmt(s.xu)}</b> • 🪙 Yên: <b>${fmt(s.yen)}</b> • 💎 Lượng: <b>${fmt(s.luong)}</b></span>
        <button type="button" class="mini-btn" id="btn-sort-bag">Sắp xếp</button>
      </div>
      <div class="inv-grid">${gridHtml || '<p class="muted" style="grid-column:1/-1">Hành trang trống.</p>'}</div>
      <div class="item-inspector">${selDetailHtml}</div>
    `;
    this.overlay.classList.remove('hidden');

    const sortBtn = document.getElementById('btn-sort-bag');
    if (sortBtn) sortBtn.onclick = () => this.sendWs({ type: 'sort_bag' });

    this.bodyEl.querySelectorAll('[data-bag-idx]').forEach(el => {
      el.onclick = () => {
        this.selectedBagIdx = Number(el.dataset.bagIdx);
        this.openBag();
      };
    });

    const useBtn = document.getElementById('btn-use-item');
    if (useBtn) useBtn.onclick = () => this.sendWs({ type: 'use_item', bagIdx: this.selectedBagIdx });

    const upBtn = document.getElementById('btn-up-from-bag');
    if (upBtn) {
      upBtn.onclick = () => {
        this.upTab = 'upgrade';
        this.upTarget = { type: 'bag', idx: this.selectedBagIdx };
        this.upStoneCounts.clear();
        this.openUpgrade();
      };
    }

    const sellBtn = document.getElementById('btn-sell-item');
    if (sellBtn) sellBtn.onclick = () => this.sendWs({ type: 'sell_item', bagIdx: this.selectedBagIdx, quantity: 1 });

    const dropBtn = document.getElementById('btn-drop-item');
    if (dropBtn) dropBtn.onclick = () => this.sendWs({ type: 'drop_bag_item', bagIdx: this.selectedBagIdx });
  }

  // 3. Rương đồ (NPC Kamakura)
  openBox() {
    const s = this.getSelf();
    if (!s) return;
    this.currentModalKind = 'box';

    const bagHtml = (s.bag || []).map((it, idx) => {
      const tpl = this.boot.items[it.itemId];
      return `
        <div class="inv-slot" data-to-box="${idx}" title="Cất ${esc(tpl?.name || '')}">
          <img src="/assets/img/small/${tpl?.icon || 0}.png" alt="" />
          ${it.quantity > 1 ? `<b class="qty-tag">${it.quantity}</b>` : ''}
        </div>
      `;
    }).join('');

    const boxHtml = (s.box || []).map((it, idx) => {
      const tpl = this.boot.items[it.itemId];
      return `
        <div class="inv-slot" data-to-bag="${idx}" title="Lấy ${esc(tpl?.name || '')}">
          <img src="/assets/img/small/${tpl?.icon || 0}.png" alt="" />
          ${it.quantity > 1 ? `<b class="qty-tag">${it.quantity}</b>` : ''}
        </div>
      `;
    }).join('');

    this.titleEl.textContent = `Rương đồ cá nhân (${(s.box || []).length}/${s.boxCapacity})`;
    this.bodyEl.innerHTML = `
      <div class="bag-toolbar">
        <span>Xu trong rương: <b class="hl">${fmt(s.xuInBox)}</b> (Hành trang: ${fmt(s.xu)})</span>
        <div class="stat-btns">
          <button type="button" class="mini-btn" id="btn-dep-xu">Cất 10k Xu</button>
          <button type="button" class="mini-btn" id="btn-wdr-xu">Rút 10k Xu</button>
        </div>
      </div>
      <div class="two-col">
        <div>
          <p class="sec-title">Hành trang (Click để cất)</p>
          <div class="inv-grid small-grid">${bagHtml}</div>
        </div>
        <div>
          <p class="sec-title">Rương đồ (Click để lấy)</p>
          <div class="inv-grid small-grid">${boxHtml}</div>
        </div>
      </div>
    `;
    this.overlay.classList.remove('hidden');

    document.getElementById('btn-dep-xu').onclick = () => this.sendWs({ type: 'xu_box', delta: 10000 });
    document.getElementById('btn-wdr-xu').onclick = () => this.sendWs({ type: 'xu_box', delta: -10000 });
    this.bodyEl.querySelectorAll('[data-to-box]').forEach(el => {
      el.onclick = () => this.sendWs({ type: 'bag_to_box', bagIdx: Number(el.dataset.toBox) });
    });
    this.bodyEl.querySelectorAll('[data-to-bag]').forEach(el => {
      el.onclick = () => this.sendWs({ type: 'box_to_bag', boxIdx: Number(el.dataset.toBag) });
    });
  }

  // 4. Kỹ năng + Gán phím tắt 1..5
  openSkills() {
    const s = this.getSelf();
    if (!s) return;
    this.currentModalKind = 'skills';

    const entries = Object.entries(s.skills || {});
    const rows = entries.map(([tidStr, pt]) => {
      const tid = Number(tidStr);
      const tpl = this.boot.skillTemplates[tid];
      if (!tpl) return '';
      const curLv = (tpl.levels || []).find(l => l.point === pt) || tpl.levels?.[0];
      const nextLv = (tpl.levels || []).find(l => l.point === pt + 1);
      const isEntrySkill = (s.classId >= 1 && tid === [0, 1, 10, 19, 28, 37, 46][s.classId]);
      const reqLv = nextLv ? (isEntrySkill ? Math.max(1, nextLv.level - 9) : nextLv.level) : 999;
      const canUp = s.spoint > 0 && pt < tpl.maxPoint && reqLv <= s.level;

      const hkBtns = tpl.type !== 0 ? [0, 1, 2, 3, 4].map(slot => {
        const active = s.hotkeySkills?.[slot] === tid;
        return `<button type="button" class="mini-btn ${active ? 'active-hk' : ''}" data-hk-slot="${slot}" data-hk-skill="${tid}">Phím ${slot + 1}</button>`;
      }).join('') : '<span class="muted">Nội tại bị động</span>';

      return `
        <div class="skill-row">
          <img src="/assets/img/small/${tpl.icon}.png" alt="" />
          <div class="skill-info">
            <div class="skill-title">
              <b>${esc(tpl.name)}</b>
              <span class="lv-badge">Cấp ${pt}/${tpl.maxPoint}</span>
              ${canUp ? `<button type="button" class="mini-btn up-skill-btn" data-up-skill="${tid}">+1</button>` : ''}
            </div>
            <p class="muted">${esc(tpl.description || '')} ${curLv ? `(MP: ${curLv.manaUse}, Đánh ${curLv.maxFight} mục tiêu)` : ''}</p>
            <div class="stat-btns">${hkBtns}</div>
          </div>
        </div>
      `;
    }).join('');

    this.titleEl.textContent = `Kỹ năng (Điểm kỹ năng dư: ${s.spoint})`;
    this.bodyEl.innerHTML = `<div class="skill-list">${rows}</div>`;
    this.overlay.classList.remove('hidden');

    this.bodyEl.querySelectorAll('[data-up-skill]').forEach(btn => {
      btn.onclick = () => this.sendWs({ type: 'up_skill', templateId: Number(btn.dataset.upSkill), points: 1 });
    });
    this.bodyEl.querySelectorAll('[data-hk-slot]').forEach(btn => {
      btn.onclick = () => this.sendWs({
        type: 'set_hotkey',
        slot: Number(btn.dataset.hkSlot),
        skillId: Number(btn.dataset.hkSkill)
      });
    });
  }

  // 5. Shop NPC
  openShop(shopData) {
    this.lastShopData = shopData;
    this.currentModalKind = 'shop';
    const s = this.getSelf();

    const itemsHtml = (shopData.items || []).map(it => {
      const priceTag = it.gold > 0 ? `💎 ${fmt(it.gold)} Lượng`
        : it.yen > 0 ? `🪙 ${fmt(it.yen)} Yên`
        : `🟡 ${fmt(it.coin)} Xu`;
      const opts = (it.options || []).slice(0, 3).map(o => this.formatOption(o[0], o[1])).join(' • ');
      return `
        <div class="shop-item">
          <img src="/assets/img/small/${it.icon}.png" alt="" />
          <div class="shop-item-info">
            <div><b>${esc(it.name)}</b> <small class="muted">(Lv.${it.level})</small></div>
            ${opts ? `<div class="opt-line">${esc(opts)}</div>` : ''}
            <div class="price-line">${priceTag}</div>
          </div>
          <div class="shop-buy-btns">
            <button type="button" class="mini-btn" data-buy-idx="${it.index}" data-qty="1">Mua 1</button>
            ${it.isUpToUp ? `<button type="button" class="mini-btn" data-buy-idx="${it.index}" data-qty="10">x10</button>` : ''}
          </div>
        </div>
      `;
    }).join('');

    this.titleEl.textContent = shopData.storeName || 'Cửa hàng';
    this.bodyEl.innerHTML = `
      <div class="bag-toolbar">
        <span>🟡 Xu: <b>${fmt(s?.xu)}</b> • 🪙 Yên: <b>${fmt(s?.yen)}</b> • 💎 Lượng: <b>${fmt(s?.luong)}</b></span>
      </div>
      <div class="shop-list">${itemsHtml || '<p class="muted">Cửa hàng hiện chưa có vật phẩm.</p>'}</div>
    `;
    this.overlay.classList.remove('hidden');

    this.bodyEl.querySelectorAll('[data-buy-idx]').forEach(btn => {
      btn.onclick = () => this.sendWs({
        type: 'buy_item',
        storeId: shopData.storeId,
        index: Number(btn.dataset.buyIdx),
        quantity: Number(btn.dataset.qty)
      });
    });
  }

  // 6. Hội thoại NPC
  openNpcDialog(pkt) {
    this.currentModalKind = 'npc';
    const actionsHtml = (pkt.actions || []).map((a, idx) =>
      `<button type="button" class="npc-act-btn" data-act-idx="${idx}">${esc(a.label)}</button>`
    ).join('');

    this.titleEl.textContent = pkt.npcName || 'NPC';
    this.bodyEl.innerHTML = `
      <p class="npc-greeting">${esc(pkt.greeting || '')}</p>
      <div class="npc-actions-list">${actionsHtml || '<p class="muted">Không có thao tác nào khả dụng.</p>'}</div>
    `;
    this.overlay.classList.remove('hidden');

    this.bodyEl.querySelectorAll('[data-act-idx]').forEach(btn => {
      const a = pkt.actions[Number(btn.dataset.actIdx)];
      btn.onclick = () => {
        if (a.action === 'open_shop') this.sendWs({ type: 'open_shop', storeId: a.param });
        else if (a.action === 'open_box') this.openBox();
        else if (a.action === 'save_location') { this.sendWs({ type: 'save_location' }); this.close(); }
        else if (a.action === 'teleport') { this.sendWs({ type: 'teleport_map', mapId: a.param }); this.close(); }
        else if (a.action === 'exchange') this.sendWs({ type: 'exchange_luong', option: a.param });
        else if (a.action === 'join_class') { this.sendWs({ type: 'join_class', classId: a.param }); this.close(); }
        else if (a.action === 'task_status') this.openTask();
        else if (a.action === 'open_upgrade') this.openUpgrade();
        else if (a.action === 'open_events') this.sendWs({ type: 'get_events' });
        else if (a.action === 'open_clan') this.sendWs({ type: 'clan_info' });
        else if (a.action === 'enter_cave') { this.sendWs({ type: 'enter_cave', tier: a.param }); this.close(); }
      };
    });
  }

  // 7. Nhiệm vụ chính tuyến
  openTask() {
    const s = this.getSelf();
    if (!s) return;
    this.currentModalKind = 'task';
    const tt = this.boot.tasks?.[s.taskId];
    if (!tt) {
      this.titleEl.textContent = 'Nhiệm vụ chính tuyến';
      this.bodyEl.innerHTML = '<p>Bạn đã hoàn thành toàn bộ chuỗi nhiệm vụ chính tuyến hiện có!</p>';
      this.overlay.classList.remove('hidden');
      return;
    }

    const stepsHtml = (tt.subnames || []).map((sub, i) => {
      if (!sub) return '';
      let cls = '';
      let mark = '○';
      if (i < s.taskIndex) { cls = 'done'; mark = '✓'; }
      else if (i === s.taskIndex) { cls = 'active'; mark = '➤'; }
      const req = tt.counts?.[i] > 0 ? ` (${i === s.taskIndex ? s.taskCount : (i < s.taskIndex ? tt.counts[i] : 0)}/${tt.counts[i]})` : '';
      return `<li class="task-step ${cls}"><b>${mark}</b> ${esc(sub)}${req}</li>`;
    }).join('');

    this.titleEl.textContent = `Nhiệm vụ #${s.taskId + 1}: ${tt.name}`;
    this.bodyEl.innerHTML = `
      ${tt.detail ? `<p class="npc-greeting">${esc(tt.detail)}</p>` : ''}
      ${tt.levelRequire > s.level ? `<p class="form-error">Yêu cầu đạt cấp ${tt.levelRequire} để tiếp tục.</p>` : ''}
      <ul class="task-steps">${stepsHtml}</ul>
    `;
    this.overlay.classList.remove('hidden');
  }

  // 8. Cường hóa & Luyện đá (Bước 4)
  openUpgrade() {
    const s = this.getSelf();
    if (!s) return;
    this.currentModalKind = 'upgrade';

    // Chuẩn hóa map đá đang chọn theo số lượng còn trong túi
    for (const [bIdx, cnt] of Array.from(this.upStoneCounts.entries())) {
      const it = s.bag?.[bIdx];
      if (!it || it.itemId < 0 || it.itemId > 11) {
        this.upStoneCounts.delete(bIdx);
      } else if (cnt > it.quantity) {
        this.upStoneCounts.set(bIdx, it.quantity);
      }
    }

    this.titleEl.textContent = 'Thợ rèn Kenshinto • Cường hóa & Luyện đá';
    const tabsHtml = `
      <div class="sub-tabs">
        <button type="button" class="mini-btn ${this.upTab === 'upgrade' ? 'active-hk' : ''}" id="tab-up-gear">🔨 Đập đồ</button>
        <button type="button" class="mini-btn ${this.upTab === 'combine' ? 'active-hk' : ''}" id="tab-up-stone">💎 Luyện đá</button>
      </div>
    `;

    if (this.upTab === 'combine') {
      const counts = new Array(11).fill(0);
      for (const it of (s.bag || [])) {
        if (it.itemId >= 0 && it.itemId <= 10) counts[it.itemId] += it.quantity;
      }
      const rows = counts.map((qty, tier) => {
        if (qty <= 0) return '';
        const cost = COIN_UP_CRYSTAL[tier];
        const can = qty >= 4;
        return `
          <div class="shop-item">
            <img src="/assets/img/small/${this.boot.items[tier]?.icon || 188}.png" alt="" />
            <div class="shop-item-info">
              <div><b>${esc(this.boot.items[tier]?.name || `Đá cấp ${tier + 1}`)}</b> (Có: <b>${qty}</b>)</div>
              <div class="muted">4 viên + ${fmt(cost)} Yên/Xu → 1 ${esc(this.boot.items[tier + 1]?.name || '')}</div>
            </div>
            <div class="stat-btns">
              <button type="button" class="mini-btn" ${can ? '' : 'disabled'} data-comb-tier="${tier}" data-comb-xu="0">Luyện (Yên)</button>
              <button type="button" class="mini-btn" ${can ? '' : 'disabled'} data-comb-tier="${tier}" data-comb-xu="1">Luyện (Xu)</button>
            </div>
          </div>
        `;
      }).join('');

      this.bodyEl.innerHTML = `
        ${tabsHtml}
        <p class="muted" style="margin-bottom:8px">Ghép 4 viên Đá cùng cấp để tạo 1 viên Đá cấp cao hơn.</p>
        <div class="shop-list">${rows || '<p class="muted">Trong hành trang chưa có Đá nâng cấp (cấp 1..11).</p>'}</div>
      `;
      this.overlay.classList.remove('hidden');
      this.bindUpgradeTabSwitch();
      this.bodyEl.querySelectorAll('[data-comb-tier]').forEach(btn => {
        btn.onclick = () => this.sendWs({
          type: 'combine_stones',
          stoneItemId: Number(btn.dataset.combTier),
          useXu: btn.dataset.combXu === '1'
        });
      });
      return;
    }

    // Tab Đập đồ
    const candidates = [];
    (s.equipped || []).forEach((it, idx) => {
      if (!it) return;
      const tpl = this.boot.items[it.itemId];
      if (tpl && tpl.type <= 9 && tpl.fashion <= -1) {
        candidates.push({ type: 'equipped', idx, it, tpl, label: '[Mặc] ' + tpl.name });
      }
    });
    (s.bag || []).forEach((it, idx) => {
      const tpl = this.boot.items[it.itemId];
      if (tpl && tpl.type <= 9 && tpl.fashion <= -1) {
        candidates.push({ type: 'bag', idx, it, tpl, label: '[Túi] ' + tpl.name });
      }
    });

    if (!candidates.some(c => c.type === this.upTarget.type && c.idx === this.upTarget.idx) && candidates.length > 0) {
      this.upTarget = { type: candidates[0].type, idx: candidates[0].idx };
    }
    const selCand = candidates.find(c => c.type === this.upTarget.type && c.idx === this.upTarget.idx);

    const targetsHtml = candidates.map(c => {
      const active = c.type === this.upTarget.type && c.idx === this.upTarget.idx;
      return `
        <div class="inv-slot ${active ? 'selected' : ''}" data-up-t="${c.type}" data-up-i="${c.idx}" title="${esc(c.label)}">
          <img src="/assets/img/small/${c.tpl.icon}.png" alt="" />
          ${c.it.upgrade > 0 ? `<em class="up-tag">+${c.it.upgrade}</em>` : ''}
        </div>
      `;
    }).join('');

    const stoneIndices = [];
    let crystalPower = 0;
    const stoneBagHtml = (s.bag || []).map((it, bIdx) => {
      if (it.itemId < 0 || it.itemId > 11) return '';
      const tpl = this.boot.items[it.itemId];
      const chosen = this.upStoneCounts.get(bIdx) || 0;
      for (let k = 0; k < chosen; k++) {
        stoneIndices.push(bIdx);
        crystalPower += UP_CRYSTAL[it.itemId];
      }
      return `
        <div class="inv-slot ${chosen > 0 ? 'selected' : ''}" data-stone-idx="${bIdx}" title="Click thêm ${esc(tpl?.name || '')}">
          <img src="/assets/img/small/${tpl?.icon || 188}.png" alt="" />
          ${chosen > 0 ? `<em class="up-tag">x${chosen}</em>` : ''}
          ${it.quantity > 1 ? `<b class="qty-tag">${it.quantity}</b>` : ''}
        </div>
      `;
    }).join('');

    let previewHtml = '<p class="muted">Chọn 1 trang bị bên trên để xem tỉ lệ cường hóa.</p>';
    if (selCand) {
      const curUp = selCand.it.upgrade || 0;
      const canIncrease = curUp < selCand.tpl.maxUpgrade;
      const t = selCand.tpl.type;
      let rawPct = 0;
      let cost = 0;
      if (canIncrease) {
        if (t === 1) {
          rawPct = Math.floor(crystalPower * 100 / UP_WEAPON[curUp]);
          cost = COIN_UP_WEAPON[curUp];
        } else if (t === 3 || t === 5 || t === 7 || t === 9) {
          rawPct = Math.floor(crystalPower * 100 / UP_ADORN[curUp]);
          cost = COIN_UP_ADORN[curUp];
        } else {
          rawPct = Math.floor(crystalPower * 100 / UP_CLOTHE[curUp]);
          cost = COIN_UP_CLOTHE[curUp];
        }
        rawPct = Math.min(MAX_PERCENT[curUp] - (curUp >= 14 ? 1 : 0), rawPct);
        if (this.upUseLuong) rawPct = Math.min(100, Math.floor(rawPct * 1.5));
      }
      const hasBh = (s.bag || []).some(it =>
        (it.itemId === 242 && curUp < 8) || (it.itemId === 284 && curUp < 12)
        || (it.itemId === 285 && curUp < 14) || (it.itemId === 475 && curUp < 16)
      );
      previewHtml = `
        <div class="up-chance-box">
          <div><b>${esc(selCand.tpl.name)}</b> (+${curUp}, tối đa +${selCand.tpl.maxUpgrade}) ${canIncrease ? `→ +${curUp + 1}` : '• Đã đạt giới hạn'}</div>
          <div>Tỉ lệ thành công: <b class="hl">${stoneIndices.length ? Math.max(0, rawPct) : 0}%</b> • Số đá đã chọn: <b>${stoneIndices.length}/18</b></div>
          <div>Phí cường hóa: <b class="hl">${fmt(cost)} Yên/Xu</b> ${this.upUseLuong ? ` + <b>${GOLD_UP[curUp] || 1} Lượng</b>` : ''}</div>
          <div class="inline-form">
            <label class="chk-line"><input type="checkbox" id="chk-up-ins" ${this.upUseIns ? 'checked' : ''} ${hasBh ? '' : 'disabled'} /> Dùng Bảo hiểm ${hasBh ? '(Có sẵn)' : '(Chưa có)'}</label>
            <label class="chk-line"><input type="checkbox" id="chk-up-luong" ${this.upUseLuong ? 'checked' : ''} /> Cẩn thận (x1.5 tỉ lệ bằng Lượng)</label>
          </div>
          <div class="stat-btns" style="margin-top:6px">
            <button type="button" class="btn btn-primary" id="btn-do-upgrade" ${stoneIndices.length && canIncrease ? '' : 'disabled'}>🔨 Nâng cấp ngay</button>
            <button type="button" class="btn btn-ghost" id="btn-clear-stones">Bỏ chọn đá</button>
          </div>
        </div>
      `;
    }

    this.bodyEl.innerHTML = `
      ${tabsHtml}
      <p class="sec-title">1. Chọn trang bị cần cường hóa</p>
      <div class="inv-grid">${targetsHtml || '<p class="muted" style="grid-column:1/-1">Không có trang bị phù hợp.</p>'}</div>
      <p class="sec-title" style="margin-top:10px">2. Click chọn Đá nâng cấp từ Hành trang (Có thể bấm nhiều lần nếu xếp chồng)</p>
      <div class="inv-grid">${stoneBagHtml || '<p class="muted" style="grid-column:1/-1">Chưa có Đá nâng cấp trong túi.</p>'}</div>
      ${previewHtml}
    `;
    this.overlay.classList.remove('hidden');
    this.bindUpgradeTabSwitch();

    this.bodyEl.querySelectorAll('[data-up-t]').forEach(el => {
      el.onclick = () => {
        this.upTarget = { type: el.dataset.upT, idx: Number(el.dataset.upI) };
        this.openUpgrade();
      };
    });
    this.bodyEl.querySelectorAll('[data-stone-idx]').forEach(el => {
      el.onclick = () => {
        const bIdx = Number(el.dataset.stoneIdx);
        const it = s.bag[bIdx];
        const totalChosen = Array.from(this.upStoneCounts.values()).reduce((a, b) => a + b, 0);
        if (!it || totalChosen >= 18) return;
        const cur = this.upStoneCounts.get(bIdx) || 0;
        if (cur < it.quantity) {
          this.upStoneCounts.set(bIdx, cur + 1);
        } else {
          this.upStoneCounts.delete(bIdx);
        }
        this.openUpgrade();
      };
    });

    const insChk = document.getElementById('chk-up-ins');
    if (insChk) insChk.onchange = () => { this.upUseIns = insChk.checked; this.openUpgrade(); };
    const lgChk = document.getElementById('chk-up-luong');
    if (lgChk) lgChk.onchange = () => { this.upUseLuong = lgChk.checked; this.openUpgrade(); };
    const clrBtn = document.getElementById('btn-clear-stones');
    if (clrBtn) clrBtn.onclick = () => { this.upStoneCounts.clear(); this.openUpgrade(); };

    const doBtn = document.getElementById('btn-do-upgrade');
    if (doBtn) {
      doBtn.onclick = () => {
        this.sendWs({
          type: 'upgrade_item',
          targetType: this.upTarget.type,
          targetIdx: this.upTarget.idx,
          stoneIndices,
          useInsurance: this.upUseIns,
          useLuong: this.upUseLuong
        });
        this.upStoneCounts.clear();
      };
    }
  }

  bindUpgradeTabSwitch() {
    const t1 = document.getElementById('tab-up-gear');
    const t2 = document.getElementById('tab-up-stone');
    if (t1) t1.onclick = () => { this.upTab = 'upgrade'; this.openUpgrade(); };
    if (t2) t2.onclick = () => { this.upTab = 'combine'; this.openUpgrade(); };
  }

  // 9. Giao dịch trực tiếp 2 người (Bước 4)
  openTrade(tradeData) {
    this.lastTradeData = tradeData;
    this.currentModalKind = 'trade';
    const s = this.getSelf();
    if (!s) return;

    if (tradeData.type === 'trade_open') {
      this.tradeSelectedBag = new Set(tradeData.myIndices || []);
    } else {
      this.tradeSelectedBag = new Set(tradeData.myIndices || []);
    }

    const bagGrid = (s.bag || []).map((it, idx) => {
      if (it.locked) return '';
      const tpl = this.boot.items[it.itemId];
      const chosen = this.tradeSelectedBag.has(idx);
      return `
        <div class="inv-slot ${chosen ? 'selected' : ''}" data-tr-idx="${idx}" title="${esc(tpl?.name || '')}">
          <img src="/assets/img/small/${tpl?.icon || 0}.png" alt="" />
          ${it.quantity > 1 ? `<b class="qty-tag">${it.quantity}</b>` : ''}
        </div>
      `;
    }).join('');

    const partnerItemsHtml = (tradeData.partnerItems || []).map(it => {
      const tpl = this.boot.items[it.itemId];
      return `
        <div class="inv-slot" title="${esc(tpl?.name || '')}">
          <img src="/assets/img/small/${tpl?.icon || 0}.png" alt="" />
          ${it.upgrade > 0 ? `<em class="up-tag">+${it.upgrade}</em>` : ''}
          ${it.quantity > 1 ? `<b class="qty-tag">${it.quantity}</b>` : ''}
        </div>
      `;
    }).join('');

    this.titleEl.textContent = `Giao dịch trực tiếp với ${tradeData.partnerName}`;
    this.bodyEl.innerHTML = `
      <div class="two-col">
        <div>
          <p class="sec-title">Của bạn ${tradeData.myLocked ? '🔒 Đã khóa' : ''} ${tradeData.myConfirmed ? '✅ Đã xác nhận' : ''}</p>
          <div class="inline-form">
            <span>Xu gửi:</span>
            <input id="trade-xu-input" class="inline-input" type="number" min="0" max="${s.xu}" value="${tradeData.myXu || 0}" ${tradeData.myLocked ? 'disabled' : ''} />
            <button type="button" class="mini-btn" id="btn-trade-upd" ${tradeData.myLocked ? 'disabled' : ''}>Cập nhật Xu</button>
          </div>
          <p class="muted" style="margin-bottom:4px">Chọn vật phẩm không khóa để gửi:</p>
          <div class="inv-grid small-grid">${bagGrid || '<p class="muted" style="grid-column:1/-1">Không có đồ không khóa.</p>'}</div>
        </div>
        <div>
          <p class="sec-title">${esc(tradeData.partnerName)} ${tradeData.partnerLocked ? '🔒 Đã khóa' : '⏳ Đang chọn'} ${tradeData.partnerConfirmed ? '✅' : ''}</p>
          <div class="stat-card" style="margin-bottom:8px">
            <div>Xu đối phương đưa: <b class="hl">${fmt(tradeData.partnerXu || 0)}</b></div>
          </div>
          <div class="inv-grid small-grid">${partnerItemsHtml || '<p class="muted" style="grid-column:1/-1">Chưa đặt vật phẩm.</p>'}</div>
        </div>
      </div>
      <div class="insp-actions" style="margin-top:12px">
        <button type="button" class="btn btn-primary" id="btn-trade-lock" ${tradeData.myLocked ? 'disabled' : ''}>🔒 Khóa giao dịch</button>
        <button type="button" class="btn btn-accent" id="btn-trade-confirm" ${tradeData.myLocked && tradeData.partnerLocked && !tradeData.myConfirmed ? '' : 'disabled'}>✅ Xác nhận trao đổi</button>
        <button type="button" class="btn btn-ghost" id="btn-trade-cancel">✕ Hủy</button>
      </div>
    `;
    this.overlay.classList.remove('hidden');

    const syncTrade = () => {
      const xuVal = Math.max(0, Number(document.getElementById('trade-xu-input')?.value || 0));
      this.sendWs({ type: 'trade_update', xu: xuVal, bagIndices: Array.from(this.tradeSelectedBag) });
    };

    if (!tradeData.myLocked) {
      this.bodyEl.querySelectorAll('[data-tr-idx]').forEach(el => {
        el.onclick = () => {
          const idx = Number(el.dataset.trIdx);
          if (this.tradeSelectedBag.has(idx)) this.tradeSelectedBag.delete(idx);
          else this.tradeSelectedBag.add(idx);
          syncTrade();
        };
      });
    }
    const updBtn = document.getElementById('btn-trade-upd');
    if (updBtn) updBtn.onclick = syncTrade;
    document.getElementById('btn-trade-lock').onclick = () => this.sendWs({ type: 'trade_lock' });
    document.getElementById('btn-trade-confirm').onclick = () => this.sendWs({ type: 'trade_confirm' });
    document.getElementById('btn-trade-cancel').onclick = () => { this.sendWs({ type: 'trade_cancel' }); this.close(); };
  }

  // 10. Sự kiện: Săn Boss Thế Giới & Hang động (Bước 4)
  openEvents(ev) {
    this.lastEventsData = ev;
    this.currentModalKind = 'events';
    const s = this.getSelf();

    const bossRows = (ev.bosses || []).map(b => `
      <div class="shop-item">
        <div class="shop-item-info">
          <div><b>♛ ${esc(b.name)}</b> <small class="lv-badge">Lv.${b.level}</small></div>
          <div class="muted">${esc(b.mapName)} • ${b.alive ? `<span class="hl">Đang sống (${fmt(b.hp)}/${fmt(b.maxHp)} HP)</span>` : `Tái xuất sau ${b.respawnInSec}s`}</div>
        </div>
        <button type="button" class="mini-btn" data-warp-boss="${b.index}">Tới săn</button>
      </div>
    `).join('');

    const caveRows = (ev.caves || []).map(c => `
      <div class="shop-item">
        <div class="shop-item-info">
          <div><b>🕳 ${esc(c.name)}</b> <small class="muted">(Lv.${c.minLevel}+)</small></div>
          <div class="opt-line">${esc(c.reward)}</div>
        </div>
        <button type="button" class="mini-btn" data-enter-cave="${c.tier}">Vào Hang</button>
      </div>
    `).join('');

    const inCaveMap = [91, 94, 105, 114].includes(s?.mapId);
    this.titleEl.textContent = 'Săn Boss Thế Giới & Hang Động Tổ Đội';
    this.bodyEl.innerHTML = `
      <p class="sec-title">Boss Thế Giới</p>
      <div class="shop-list" style="margin-bottom:12px">${bossRows}</div>
      <div class="bag-toolbar">
        <span class="sec-title" style="margin:0">Hang Động (Dọn quái Đợt 1 → Hạ Boss Đợt 2)</span>
        ${inCaveMap ? `<button type="button" class="mini-btn danger-mini" id="btn-leave-cave">Rời Hang động</button>` : ''}
      </div>
      <div class="shop-list">${caveRows}</div>
    `;
    this.overlay.classList.remove('hidden');

    this.bodyEl.querySelectorAll('[data-warp-boss]').forEach(btn => {
      btn.onclick = () => { this.sendWs({ type: 'warp_boss', bossIndex: Number(btn.dataset.warpBoss) }); this.close(); };
    });
    this.bodyEl.querySelectorAll('[data-enter-cave]').forEach(btn => {
      btn.onclick = () => { this.sendWs({ type: 'enter_cave', tier: Number(btn.dataset.enterCave) }); this.close(); };
    });
    const lc = document.getElementById('btn-leave-cave');
    if (lc) lc.onclick = () => { this.sendWs({ type: 'leave_cave' }); this.close(); };
  }

  // 11. Gia tộc (Bước 4)
  openClan(data) {
    this.lastClanData = data;
    this.currentModalKind = 'clan';
    const s = this.getSelf();
    if (!s) return;

    if (!data.clan) {
      const listHtml = (data.clans || []).map(c => `
        <div class="member-row">
          <span><b>[${esc(c.name)}]</b> Lv.${c.level} • Tộc trưởng: ${esc(c.leaderName)}</span>
          <span class="muted">${c.memberCount} thành viên</span>
        </div>
      `).join('');

      this.titleEl.textContent = 'Gia Tộc';
      this.bodyEl.innerHTML = `
        <div class="stat-card" style="margin-bottom:10px">
          <div>Bạn chưa gia nhập Gia tộc nào. Chi phí thành lập: <b class="hl">2.000 Xu</b>.</div>
          <div class="inline-form">
            <input id="clan-create-name" class="inline-input" placeholder="Nhập tên Gia tộc (2-24 ký tự)" maxlength="24" />
            <button type="button" class="btn btn-primary" id="btn-create-clan">Thành lập</button>
          </div>
        </div>
        <p class="sec-title">Danh sách Gia tộc trên máy chủ</p>
        <div class="shop-list">${listHtml || '<p class="muted">Chưa có Gia tộc nào.</p>'}</div>
      `;
      this.overlay.classList.remove('hidden');
      document.getElementById('btn-create-clan').onclick = () => {
        const name = document.getElementById('clan-create-name').value.trim();
        if (name) this.sendWs({ type: 'create_clan', name });
      };
      return;
    }

    const c = data.clan;
    const isLeader = s.name === c.leaderName;
    const memRows = (c.members || []).map(m => `
      <div class="member-row">
        <span>
          <i class="${m.online ? 'dot-on' : 'dot-off'}"></i>
          <b>${esc(m.name)}</b> (Lv.${m.level} • ${CLASS_NAMES[m.classId] || 'Tập sự'})
          ${m.memberType === 4 ? ' <em class="lv-badge">Tộc trưởng</em>' : ''}
        </span>
        <div class="stat-btns">
          <span class="muted">Cống hiến: ${fmt(m.pointClan)}</span>
          ${isLeader && m.playerId !== s.id ? `<button type="button" class="mini-btn danger-mini" data-kick-id="${m.playerId}">Mời ra</button>` : ''}
        </div>
      </div>
    `).join('');

    this.titleEl.textContent = `Gia tộc [${c.name}] • Lv.${c.level}`;
    this.bodyEl.innerHTML = `
      <div class="stat-card" style="margin-bottom:10px">
        <div>Tộc trưởng: <b class="hl">${esc(c.leaderName)}</b> • Quỹ Xu: <b class="hl">${fmt(c.coin)}</b> • EXP: <b>${fmt(c.exp)}</b></div>
        <div>Thông báo: <i>${esc(c.alert || '')}</i></div>
        ${isLeader ? `
          <div class="inline-form">
            <input id="clan-alert-inp" class="inline-input" placeholder="Sửa thông báo gia tộc..." value="${esc(c.alert || '')}" maxlength="120" />
            <button type="button" class="mini-btn" id="btn-clan-alert">Lưu TB</button>
          </div>
        ` : ''}
        <div class="inline-form">
          <input id="clan-donate-inp" class="inline-input" type="number" min="100" value="1000" />
          <button type="button" class="mini-btn" id="btn-clan-donate">Góp Xu</button>
          <input id="clan-inv-inp" class="inline-input" placeholder="Tên người chơi mời vào tộc..." />
          <button type="button" class="mini-btn" id="btn-clan-inv">Mời</button>
        </div>
      </div>
      <p class="sec-title">Thành viên (${(c.members || []).length})</p>
      <div class="shop-list" style="margin-bottom:10px">${memRows}</div>
      <button type="button" class="btn btn-ghost" id="btn-clan-leave">Rời Gia tộc</button>
    `;
    this.overlay.classList.remove('hidden');

    if (isLeader) {
      document.getElementById('btn-clan-alert').onclick = () => {
        this.sendWs({ type: 'clan_alert', alert: document.getElementById('clan-alert-inp').value });
      };
    }
    document.getElementById('btn-clan-donate').onclick = () => {
      this.sendWs({ type: 'clan_donate', amount: Number(document.getElementById('clan-donate-inp').value || 0) });
    };
    document.getElementById('btn-clan-inv').onclick = () => {
      const target = document.getElementById('clan-inv-inp').value.trim();
      if (target) this.sendWs({ type: 'clan_invite', target });
    };
    document.getElementById('btn-clan-leave').onclick = () => this.sendWs({ type: 'clan_leave' });
    this.bodyEl.querySelectorAll('[data-kick-id]').forEach(btn => {
      btn.onclick = () => this.sendWs({ type: 'clan_kick', playerId: Number(btn.dataset.kickId) });
    });
  }

  // 12. Cài đặt Auto + Wake Lock (Bước 4)
  openAutoConfig() {
    const s = this.getSelf();
    if (!s) return;
    this.currentModalKind = 'auto_cfg';
    const as = s.autoSettings || { hpThreshold: 50, mpThreshold: 35, pickupAll: true };
    const info = this.getAutoInfo();

    this.titleEl.textContent = 'Cấu hình Auto Treo Máy';
    this.bodyEl.innerHTML = `
      <div class="stat-card">
        <div>Trạng thái Auto: <b class="hl">${info.enabled ? 'ĐANG CHẠY' : 'TẮT'}</b></div>
        <div>Giữ sáng màn hình (Wake Lock): <b>${info.wakeSupported ? (info.wakeActive ? '🌙 Đang giữ sáng màn hình' : 'Sẵn sàng khi bật Auto') : 'Trình duyệt không hỗ trợ Wake Lock'}</b></div>
        <div class="inline-form">
          <span>Tự uống HP khi dưới (%):</span>
          <input id="auto-hp-inp" class="inline-input" type="number" min="0" max="95" value="${as.hpThreshold ?? 50}" />
        </div>
        <div class="inline-form">
          <span>Tự uống MP khi dưới (%):</span>
          <input id="auto-mp-inp" class="inline-input" type="number" min="0" max="95" value="${as.mpThreshold ?? 35}" />
        </div>
        <label class="chk-line"><input type="checkbox" id="auto-pick-chk" ${as.pickupAll !== false ? 'checked' : ''} /> Tự động nhặt Yên & vật phẩm</label>
        <div class="insp-actions" style="margin-top:8px">
          <button type="button" class="btn btn-primary" id="btn-save-auto">Lưu cấu hình</button>
        </div>
      </div>
    `;
    this.overlay.classList.remove('hidden');

    document.getElementById('btn-save-auto').onclick = () => {
      this.sendWs({
        type: 'save_auto_settings',
        hpThreshold: Number(document.getElementById('auto-hp-inp').value || 50),
        mpThreshold: Number(document.getElementById('auto-mp-inp').value || 35),
        pickupAll: !!document.getElementById('auto-pick-chk').checked
      });
      this.close();
    };
  }

  // Hộp thoại xác nhận lời mời (Giao dịch / Lôi đài / Gia tộc)
  openInvitePrompt(title, message, acceptLabel, onAccept) {
    this.currentModalKind = 'prompt';
    this.titleEl.textContent = title;
    this.bodyEl.innerHTML = `
      <p class="npc-greeting">${esc(message)}</p>
      <div class="insp-actions">
        <button type="button" class="btn btn-primary" id="btn-prompt-ok">${esc(acceptLabel)}</button>
        <button type="button" class="btn btn-ghost" id="btn-prompt-no">Từ chối</button>
      </div>
    `;
    this.overlay.classList.remove('hidden');
    document.getElementById('btn-prompt-ok').onclick = () => { this.close(); onAccept(); };
    document.getElementById('btn-prompt-no').onclick = () => this.close();
  }

  renderItemDetail(it, withActions) {
    const tpl = this.boot.items[it.itemId];
    const opts = (it.options || []).map(o => `<li>${esc(this.formatOption(o[0], o[1]))}</li>`).join('');
    const isEquip = tpl && tpl.type >= 0 && tpl.type <= 15;
    const canUpgrade = tpl && tpl.type <= 9 && tpl.fashion <= -1;
    return `
      <div class="insp-head">
        <img src="/assets/img/small/${tpl?.icon || 0}.png" alt="" />
        <div>
          <b>${esc(tpl?.name || `#${it.itemId}`)} ${it.upgrade > 0 ? `+${it.upgrade}` : ''}</b>
          <div class="muted">Cấp yêu cầu: ${tpl?.level || 1} • ${it.locked ? 'Đã khóa' : 'Không khóa'}</div>
          ${tpl?.description ? `<div class="muted">${esc(tpl.description)}</div>` : ''}
        </div>
      </div>
      ${opts ? `<ul class="opt-detail-list">${opts}</ul>` : ''}
      ${withActions ? `
        <div class="insp-actions">
          <button type="button" class="btn btn-primary" id="btn-use-item">${isEquip ? 'Trang bị' : 'Sử dụng'}</button>
          ${canUpgrade ? '<button type="button" class="btn btn-accent" id="btn-up-from-bag">Cường hóa</button>' : ''}
          <button type="button" class="btn btn-accent" id="btn-sell-item">Bán 1 (${fmt(tpl?.resaleYen)} Yên)</button>
          <button type="button" class="btn btn-ghost" id="btn-drop-item">Vứt bỏ</button>
        </div>
      ` : ''}
    `;
  }

  formatOption(id, param) {
    const optTpl = this.boot.itemOptions?.[id];
    if (!optTpl) return `Thuộc tính #${id}: ${param}`;
    return optTpl.name.replace('#', String(param));
  }
}

function fmt(n) {
  return Number(n || 0).toLocaleString('vi-VN');
}

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
