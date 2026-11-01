/* Coup — player client. Plain JS, no dependencies, no network requests except to the host. */
(function () {
  'use strict';

  // ------------------------------------------------------------ storage (separate from Avalon's keys)
  const store = {
    get(k) { try { return localStorage.getItem('coup.' + k); } catch (e) { return null; } },
    set(k, v) { try { if (v == null) localStorage.removeItem('coup.' + k); else localStorage.setItem('coup.' + k, v); } catch (e) { /* private mode */ } },
  };

  (function readHostKey() {
    try {
      const u = new URL(location.href);
      const k = u.searchParams.get('host');
      if (k) {
        store.set('hostKey', k);
        u.searchParams.delete('host');
        history.replaceState(null, '', u.pathname + (u.search || '') + u.hash);
      }
    } catch (e) { /* ignore */ }
  })();

  const ui = {
    state: null, server: null, me: null,
    connected: false, joining: false, error: null,
    lastVersion: -1, lastEventShown: null,
    historyOpen: false,
    chosenAction: null,   // ActionType picked on your turn
    chosenTarget: null,   // target id ('' = yourself for Convert)
    keep: new Set(),      // exchange picks
    hideCards: store.get('hideCards') === '1',
  };

  const $ = (id) => document.getElementById(id);
  const app = $('app');
  const statusEl = $('status');
  const bannerEl = $('banner');
  const toastEl = $('toast');
  const overlayEl = $('overlay');
  const overlayText = $('overlay-text');
  const sheetEl = $('sheet');
  const sheetBody = $('sheet-body');

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  // ------------------------------------------------------------ websocket
  let ws = null;
  let retryMs = 500;
  let retryTimer = null;
  let lastMessageAt = 0;

  function wsUrl() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return proto + '//' + location.host + '/coup/ws';
  }

  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    clearTimeout(retryTimer);
    showOverlay(ui.me ? 'Reconnecting…' : 'Connecting…');
    try { ws = new WebSocket(wsUrl()); } catch (e) { scheduleReconnect(); return; }
    ws.onopen = () => {
      retryMs = 500;
      ui.connected = true;
      lastMessageAt = Date.now();
      const token = store.get('token');
      if (token) send({ type: 'resume', token });
      else { hideOverlay(); render(); }
    };
    ws.onmessage = (ev) => {
      lastMessageAt = Date.now();
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      onMessage(msg);
    };
    ws.onclose = () => { ui.connected = false; ws = null; scheduleReconnect(); render(); };
    ws.onerror = () => { /* onclose follows */ };
  }

  function scheduleReconnect() {
    clearTimeout(retryTimer);
    showOverlay(ui.me ? 'Connection lost. Reconnecting…' : 'Connecting to the host…');
    retryTimer = setTimeout(() => { retryMs = Math.min(retryMs * 1.7, 5000); connect(); }, retryMs);
  }

  function send(obj) {
    if (!ws || ws.readyState !== WebSocket.OPEN) { toast('Not connected yet'); return false; }
    ws.send(JSON.stringify(obj));
    return true;
  }
  function act(action, extra) { return send(Object.assign({ type: 'action', action }, extra || {})); }

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      if (!ws || ws.readyState !== WebSocket.OPEN) { retryMs = 300; connect(); } else send({ type: 'ping' });
    }
  });
  window.addEventListener('online', () => { retryMs = 300; connect(); });
  window.addEventListener('pageshow', () => { if (!ws) connect(); });
  setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      if (Date.now() - lastMessageAt > 45000) { try { ws.close(); } catch (e) { /* ignore */ } } else send({ type: 'ping' });
    }
  }, 20000);

  function onMessage(msg) {
    switch (msg.type) {
      case 'joined':
        ui.me = msg.playerId; ui.joining = false; ui.lastVersion = -1;
        store.set('token', msg.token);
        hideOverlay();
        break;
      case 'coupState':
        if (msg.state.version < ui.lastVersion) return;
        onState(msg.state, msg.server);
        hideOverlay();
        break;
      case 'error':
        ui.joining = false;
        if (msg.fatal) {
          store.set('token', null);
          ui.me = null; ui.state = null; ui.lastVersion = -1;
          hideOverlay(); ui.error = msg.message; render();
        } else toast(msg.message);
        break;
      case 'kicked':
        store.set('token', null);
        ui.me = null; ui.state = null; ui.lastVersion = -1; ui.error = msg.message;
        render();
        break;
      default: break;
    }
  }

  function onState(state, server) {
    const prev = ui.state;
    ui.lastVersion = state.version;
    ui.state = state;
    ui.server = server || null;
    ui.me = state.you.id;
    if (!prev || prev.phase !== state.phase || prev.turnId !== state.turnId) {
      ui.chosenAction = null; ui.chosenTarget = null; ui.keep = new Set();
    }
    const newEvent = state.lastEvent && state.lastEvent !== ui.lastEventShown;
    ui.lastEventShown = state.lastEvent;
    if (prev && prev.phase !== state.phase && modalOnYes) closeModal();
    render();
    if (newEvent && prev && state.phase !== 'LOBBY') banner(state.lastEvent);
    maybeAnnounceWinner(state);
  }

  // ------------------------------------------------------------ data helpers
  const CARDS = {
    DUKE: { name: 'Duke', glyph: '👑', ability: 'Tax: take 3 coins. Blocks Foreign Aid.' },
    ASSASSIN: { name: 'Assassin', glyph: '🗡️', ability: 'Pay 3 to assassinate a player.' },
    CAPTAIN: { name: 'Captain', glyph: '⚓', ability: 'Steal 2 coins. Blocks stealing.' },
    AMBASSADOR: { name: 'Ambassador', glyph: '🕊️', ability: 'Exchange cards with the deck. Blocks stealing.' },
    CONTESSA: { name: 'Contessa', glyph: '🌹', ability: 'Blocks assassination.' },
  };
  const ACTIONS = {
    INCOME: { name: 'Income', cls: 'GOLD', desc: '+1 coin. Can’t be stopped.' },
    FOREIGN_AID: { name: 'Foreign Aid', cls: 'GOLD', desc: '+2 coins. A Duke can block.' },
    TAX: { name: 'Tax', cls: 'DUKE', desc: 'Duke · +3 coins.' },
    STEAL: { name: 'Steal', cls: 'CAPTAIN', desc: 'Captain · take 2 coins from a player.' },
    ASSASSINATE: { name: 'Assassinate', cls: 'ASSASSIN', desc: 'Assassin · pay 3, a player loses a card.' },
    EXCHANGE: { name: 'Exchange', cls: 'AMBASSADOR', desc: 'Ambassador · swap cards with the deck.' },
    COUP: { name: 'Coup', cls: 'COUP', desc: 'Pay 7, a player loses a card. Can’t be stopped.' },
    CONVERT: { name: 'Convert', cls: 'GOLD', desc: 'Pay 1 to switch sides, 2 to switch someone else.' },
    EMBEZZLE: { name: 'Embezzle', cls: 'GOLD', desc: 'Take the Treasury Reserve. Claim: no Duke.' },
  };

  function players() { return ui.state ? ui.state.players : []; }
  function byId(id) { return players().find((p) => p.id === id); }
  function nameOf(id) { const p = byId(id); return p ? p.name : '?'; }
  function isMe(id) { return ui.me === id; }
  function cardName(c) { return (CARDS[c] || { name: c }).name; }
  function initials(name) { return (name || '?').trim().slice(0, 2).toUpperCase(); }
  function avatar(p) {
    const extra = (isMe(p.id) ? ' me' : '') + (ui.state && ui.state.turnId === p.id ? ' leader' : '');
    return `<span class="avatar${extra}" aria-hidden="true">${esc(initials(p.name))}</span>`;
  }
  function chip(c, lost) { return `<span class="chip ${esc(c)}${lost ? ' lost' : ''}">${(CARDS[c] || {}).glyph || ''} ${esc(cardName(c))}</span>`; }
  function factionTag(f) { return f ? `<span class="faction ${esc(f)}">${f === 'LOYALIST' ? 'Loyalist' : 'Reformist'}</span>` : ''; }

  function cardHtml(c, opts) {
    opts = opts || {};
    const info = CARDS[c] || { name: c, glyph: '?', ability: '' };
    const cls = `ccard ${esc(c)}${opts.lost ? ' lost' : ''}${opts.selected ? ' selected' : ''}`;
    // Card art lives in img/<character>.jpg (bundled); the emoji shows only if a picture fails to load.
    const art = `<img class="cimg" src="img/${esc(String(c).toLowerCase())}.jpg" alt="" onerror="this.outerHTML='<span class=&quot;glyph&quot;>${info.glyph}</span>'">`;
    const inner = `${art}<span class="cname">${esc(info.name)}</span>${opts.noAbility ? '' : `<span class="ability">${esc(info.ability)}</span>`}`;
    if (opts.action) return `<button class="${cls}" data-action="${opts.action}" data-index="${opts.index}">${inner}</button>`;
    return `<div class="${cls}">${inner}</div>`;
  }

  function myHandHtml(s, opts) {
    const m = s.you;
    if (!m.cards.length) return '';
    if (ui.hideCards && !(opts && opts.force)) {
      return `<div class="card flat center"><div class="hand small">${m.cards.map((c) => c.revealed ? cardHtml(c.character, { lost: true, noAbility: true }) : '<div class="ccard back"><span class="glyph">🂠</span><span class="cname">Hidden</span></div>').join('')}</div>
        <button class="subtle-link" data-action="toggleHide">Show my cards</button></div>`;
    }
    return `<div class="card"><h3>Your cards</h3><div class="hand">${m.cards.map((c) => cardHtml(c.character, { lost: c.revealed })).join('')}</div>
      <p class="center" style="margin:6px 0 0"><button class="subtle-link" data-action="toggleHide">Hide my cards</button></p></div>`;
  }

  function claimText(p) {
    const actor = esc(nameOf(p.actorId));
    const target = p.targetId ? `<strong>${esc(nameOf(p.targetId))}</strong>` : '';
    switch (p.action) {
      case 'FOREIGN_AID': return `${actor} takes Foreign Aid (+2)`;
      case 'TAX': return `${actor} claims ${chip('DUKE')} to take Tax (+3)`;
      case 'STEAL': return `${actor} claims ${chip('CAPTAIN')} to steal from ${target}`;
      case 'ASSASSINATE': return `${actor} claims ${chip('ASSASSIN')} to assassinate ${target}`;
      case 'EXCHANGE': return `${actor} claims ${chip('AMBASSADOR')} to exchange cards`;
      case 'EMBEZZLE': return `${actor} claims to have <strong>no Duke</strong> and embezzles the Treasury Reserve (${ui.state.treasuryReserve})`;
      case 'COUP': return `${actor} launches a Coup against ${target}`;
      case 'CONVERT': return `${actor} converts ${target || 'themself'}`;
      default: return `${actor}: ${esc(p.action)}`;
    }
  }

  // ------------------------------------------------------------ render
  function render() {
    const s = ui.state;
    renderStatus(s);
    let html;
    if (!s || !ui.me) html = viewJoin();
    else if (s.phase === 'LOBBY') html = viewLobby(s);
    else if (s.phase === 'GAME_OVER') html = viewGameOver(s);
    else html = viewGame(s);
    app.innerHTML = html;
    app.setAttribute('data-phase', s ? s.phase : 'JOIN');
    if (ui.historyOpen) renderHistory();
  }

  function renderStatus(s) {
    if (!s || s.phase === 'LOBBY' || s.phase === 'GAME_OVER' || !ui.me) { statusEl.classList.add('hidden'); return; }
    statusEl.classList.remove('hidden');
    const m = s.you;
    statusEl.innerHTML = `
      <div class="status-grid">
        <div class="cell"><span class="label">Turn</span><span class="value">${esc(nameOf(s.turnId))}${isMe(s.turnId) ? ' (you)' : ''}</span></div>
        <div class="cell"><span class="label">You</span><span class="value"><span class="coins">${m.coins}</span></span></div>
        ${s.mode === 'REFORMATION' ? `<div class="cell"><span class="label">Reserve</span><span class="value"><span class="coins">${s.treasuryReserve}</span></span></div>` : ''}
        <div class="cell"><span class="label">Deck</span><span class="value">${s.deckSize}</span></div>
      </div>
      <div class="status-tools">
        <button class="icon-btn" data-action="showCards">🃏 My cards</button>
        <button class="icon-btn" data-action="openHistory">📜 Table &amp; log</button>
      </div>`;
  }

  function viewJoin() {
    const saved = store.get('name') || '';
    const hostKey = store.get('hostKey');
    return `
      <div class="hero">
        <div class="eyebrow">Offline party game</div>
        <h1>Coup</h1>
        <p>${hostKey ? 'You are the host. Enter your name to take your seat.' : 'Enter your name to join the table.'}</p>
      </div>
      ${ui.error ? `<div class="card evil">${esc(ui.error)}</div>` : ''}
      <form class="card" id="join-form" autocomplete="off">
        <label for="name" class="muted">Your name</label>
        <input class="input" id="name" name="name" maxlength="20" placeholder="e.g. Ana" value="${esc(saved)}" autocapitalize="words" autocomplete="off" enterkeyhint="go">
        <div style="height:12px"></div>
        <button class="btn big" type="submit" ${ui.connected ? '' : 'disabled'}>${ui.joining ? 'Joining…' : 'Join the table'}</button>
      </form>
      <p class="muted center" style="font-size:14px">No internet needed. Stay connected to the host's Wi-Fi hotspot.</p>`;
  }

  function viewLobby(s) {
    const m = s.you;
    const n = s.players.length;
    const base = s.mode === 'BASE';
    const min = 2, max = base ? 6 : 10;
    const canStart = n >= min && n <= max;
    const list = s.players.map((p) => `
      <li>
        ${avatar(p)}
        <span class="name">${esc(p.name)}${isMe(p.id) ? ' <span class="muted">(you)</span>' : ''}</span>
        ${p.isHost ? '<span class="tag gold">Host</span>' : ''}
        <span class="conn ${p.connected ? '' : 'off'}"></span>
        ${m.isHost && !p.isHost ? `<button class="btn small danger" data-action="kick" data-id="${esc(p.id)}">Remove</button>` : ''}
      </li>`).join('');
    const joinUrl = ui.server && ui.server.joinUrl;
    const why = n < min ? `Need ${min - n} more.` : n > max ? (base ? 'Classic Coup is for 2–6. Pick Reformation for 7–10.' : 'Max 10 players.') : 'Ready to start.';
    return `
      <div class="hero">
        <div class="eyebrow">Coup · Lobby</div>
        <h1>${n} player${n === 1 ? '' : 's'} at the table</h1>
        <p>${why}</p>
      </div>
      ${m.isHost && joinUrl ? `<div class="card flat center"><h3>Friends open this address</h3><div class="linkbox">${esc(joinUrl)}</div><p class="muted" style="margin:8px 0 0;font-size:14px">They must be on your hotspot Wi-Fi.</p></div>` : ''}
      <div class="card"><ul class="list">${list}</ul></div>
      ${m.isHost ? `
        <div class="card"><h3>Version</h3>
          <div class="mode-pick">
            <button class="${base ? 'on' : ''}" data-action="setMode" data-mode="BASE"><span class="t">Classic</span><span class="d">2–6 players · 15 cards</span></button>
            <button class="${base ? '' : 'on'}" data-action="setMode" data-mode="REFORMATION"><span class="t">Reformation</span><span class="d">2–10 players · Loyalists vs Reformists, Treasury Reserve</span></button>
          </div>
        </div>
        <div class="actions"><button class="btn big" data-action="start" ${canStart ? '' : 'disabled'}>Start game</button></div>`
        : `<div class="card flat center"><span class="muted">Version:</span> <strong>${base ? 'Classic' : 'Reformation'}</strong></div><p class="center muted pulse">Waiting for the host to start…</p>`}
      ${rulesCard(s.mode)}`;
  }

  function rulesCard(mode) {
    return `<details class="card"><summary style="font-weight:700;cursor:pointer">How to play</summary>
      <ul class="tips" style="margin-top:10px">
        <li>Everyone has 2 secret cards (influence) and 2 coins. Lose both cards and you are out. Last player standing wins.</li>
        <li>On your turn pick one action. You may claim any character, even one you don't have: that's the bluff.</li>
        <li>Anyone can <strong>Challenge</strong> a claim. If the claim was a lie, the liar loses a card; if it was true, the challenger loses a card.</li>
        <li>Duke blocks Foreign Aid. Contessa blocks assassination. Captain or Ambassador blocks stealing. Blocks can be challenged too.</li>
        <li>Nothing happens until <strong>everyone</strong> has tapped Pass, Challenge or Block.</li>
        <li>With 10 or more coins you must Coup.</li>
        ${mode === 'REFORMATION' ? '<li><strong>Reformation:</strong> you can’t Coup, Assassinate, Steal from, or block the Foreign Aid of your own side, until everyone is on one side. Convert costs go to the Treasury Reserve; Embezzle takes it (if you claim no Duke).</li>' : ''}
      </ul></details>`;
  }

  function tableHtml(s, opts) {
    opts = opts || {};
    return `<ul class="seat-list">${s.players.map((p) => {
      const over = s.phase === 'GAME_OVER';
      const hidden = over ? p.finalHidden.map((c) => chip(c)).join(' ') : `<span class="backs">${'<i></i>'.repeat(p.influence)}</span>`;
      const lost = p.revealed.map((c) => chip(c, true)).join(' ');
      return `<li class="${s.turnId === p.id ? 'turn' : ''} ${p.alive ? '' : 'dead'}">
        ${avatar(p)}
        <div class="who"><span class="n">${esc(p.name)}${isMe(p.id) ? ' <span class="muted">(you)</span>' : ''}${p.alive ? '' : ' <span class="muted">· out</span>'}${s.winnerId === p.id ? ' 🏆' : ''}</span>
          <span class="sub">${factionTag(p.faction)} ${hidden} ${lost}</span></div>
        <span class="coins">${p.coins}</span>
        <span class="conn ${p.connected ? '' : 'off'}"></span>
        ${opts.host && s.you.isHost && !p.connected && !isMe(p.id) ? rejoinBtn(p) : ''}
      </li>`;
    }).join('')}</ul>`;
  }

  function rejoinBtn(p) {
    const rejoinable = (ui.server && ui.server.rejoinable) || [];
    return rejoinable.includes(p.id) ? '<span class="tag ok">can rejoin</span>'
      : `<button class="btn small secondary" data-action="allowRejoin" data-id="${esc(p.id)}">Let rejoin</button>`;
  }

  function recentLog(s, k) {
    const items = s.log.slice(-k);
    if (!items.length) return '';
    return `<div class="card flat"><h3>Latest</h3><ul class="log">${items.map((e) => `<li>${esc(e)}</li>`).join('')}</ul></div>`;
  }

  function hostSkip(s) {
    if (!s.you.isHost) return '';
    return `<div style="margin-top:10px"><button class="btn ghost" data-action="forceContinue">Skip players who aren't answering (host)</button></div>`;
  }

  function waitingList(p) {
    if (!p || !p.waitingIds.length) return '';
    return `<div class="waiting">${p.waitingIds.map((id) => `<span class="tag off">${esc(nameOf(id))}…</span>`).join('')}${p.passedIds.map((id) => `<span class="tag ok">${esc(nameOf(id))} ✓</span>`).join('')}</div>`;
  }

  // ---- the game table, whatever the phase
  function viewGame(s) {
    const m = s.you;
    let main = '';
    switch (m.pending) {
      case 'TAKE_ACTION': main = viewTakeAction(s); break;
      case 'RESPOND': main = viewRespond(s); break;
      case 'RESPOND_BLOCK': main = viewRespondBlock(s); break;
      case 'LOSE_INFLUENCE': main = viewLoseInfluence(s); break;
      case 'EXCHANGE': main = viewExchange(s); break;
      case 'ELIMINATED': main = viewWatching(s, true); break;
      default: main = viewWatching(s, false); break;
    }
    const showHand = m.pending !== 'LOSE_INFLUENCE' && m.pending !== 'EXCHANGE';
    return `${main}
      ${showHand ? myHandHtml(s) : ''}
      <div class="card"><h3>Table</h3>${tableHtml(s)}</div>
      ${recentLog(s, 4)}
      <p class="center"><button class="subtle-link" data-action="openHistory">Full log, players &amp; host tools</button></p>`;
  }

  function viewTakeAction(s) {
    const m = s.you;
    const a = ui.chosenAction ? m.actions.find((x) => x.type === ui.chosenAction) : null;
    const buttons = m.actions.map((o) => {
      const info = ACTIONS[o.type];
      const desc = o.type === 'EMBEZZLE' ? `Take the Treasury Reserve (${s.treasuryReserve}). Claim: no Duke.` : info.desc;
      return `<button class="abtn ${info.cls} ${ui.chosenAction === o.type ? 'selected' : ''} ${o.type === 'COUP' && m.mustCoup ? 'wide' : ''}" data-action="chooseAction" data-type="${o.type}" ${o.allowed ? '' : 'disabled'}>
        <span class="t">${esc(info.name)}</span><span class="d">${esc(o.allowed ? desc : (o.reason || desc))}</span></button>`;
    }).join('');
    let picker = '';
    let confirm = '';
    if (a) {
      const needsTarget = a.type === 'COUP' || a.type === 'ASSASSINATE' || a.type === 'STEAL';
      if (needsTarget || a.type === 'CONVERT') {
        const chips = a.targets.map((id) => {
          const p = byId(id);
          return `<li><button data-action="chooseTarget" data-id="${esc(id)}" class="${ui.chosenTarget === id ? 'selected' : ''}">${avatar(p)}<span class="n">${esc(p.name)}</span><span class="coins" style="font-size:15px">${p.coins}</span></button></li>`;
        }).join('');
        const self = a.type === 'CONVERT'
          ? `<li><button data-action="chooseTarget" data-id="" class="${ui.chosenTarget === '' ? 'selected' : ''}">${avatar(byId(m.id))}<span class="n">Myself (1 coin)</span></button></li>` : '';
        picker = `<div class="card"><h3>${a.type === 'CONVERT' ? 'Who switches sides? (others cost 2)' : 'Choose a player'}</h3><ul class="pick">${self}${chips}</ul></div>`;
        const ready = ui.chosenTarget !== null && (needsTarget ? ui.chosenTarget !== '' : true);
        const label = ready ? `${ACTIONS[a.type].name} ${ui.chosenTarget ? esc(nameOf(ui.chosenTarget)) : (a.type === 'CONVERT' ? 'myself' : '')}` : 'Choose a player';
        confirm = `<div class="actions"><button class="btn big" data-action="declare" ${ready ? '' : 'disabled'}>${label}</button></div>`;
      } else {
        confirm = `<div class="actions"><button class="btn big" data-action="declare">${esc(ACTIONS[a.type].name)}</button></div>`;
      }
    }
    return `
      <div class="hero"><div class="eyebrow">Your turn</div><h1>${m.mustCoup ? 'You must launch a Coup' : 'Choose an action'}</h1>
        <p>You have <span class="coins">${m.coins}</span>${m.faction ? ' · ' + factionTag(m.faction) : ''}</p></div>
      <div class="action-grid">${buttons}</div>
      ${picker}${confirm}
      ${!a ? '' : '<p class="center muted" style="font-size:14px">You can bluff: claim any character, even one you don’t have.</p>'}`;
  }

  function viewRespond(s) {
    const m = s.you;
    const p = s.pending;
    const blocks = m.canBlockWith.map((c) => `<button class="btn big secondary" data-action="block" data-card="${c}">Block with ${(CARDS[c] || {}).glyph || ''} ${esc(cardName(c))}</button>`).join('');
    const challenge = m.canChallenge ? `<button class="btn big evil" data-action="challenge">Challenge${p.action === 'EMBEZZLE' ? ': they DO have the Duke' : ''}</button>` : '';
    const youTarget = p.targetId === m.id;
    return `
      <div class="hero"><div class="eyebrow">${youTarget ? 'You are the target' : 'Respond'}</div>
        <div class="claim-box"><div class="big">${claimText(p)}</div></div>
        ${p.challengeSettled ? '<p>The challenge failed. You may still block.</p>' : '<p>Nothing happens until everyone has answered.</p>'}
        ${waitingList(p)}</div>
      <div class="actions">
        ${challenge}${blocks}
        <button class="btn big good" data-action="pass">${m.canBlockWith.length && !m.canChallenge ? 'Don’t block' : 'Allow'}</button>
      </div>`;
  }

  function viewRespondBlock(s) {
    const p = s.pending;
    return `
      <div class="hero"><div class="eyebrow">Block</div>
        <div class="claim-box"><div class="big">${esc(nameOf(p.blockerId))} claims ${chip(p.blockCharacter)} to block ${esc(nameOf(p.actorId))}'s ${esc(ACTIONS[p.action].name)}</div></div>
        <p>Challenge the block, or let it stand.</p>${waitingList(p)}</div>
      <div class="actions">
        <button class="btn big evil" data-action="challenge">Challenge the block</button>
        <button class="btn big good" data-action="pass">Let it stand</button>
      </div>`;
  }

  function viewLoseInfluence(s) {
    const m = s.you;
    const cards = m.cards.map((c, i) => c.revealed ? cardHtml(c.character, { lost: true }) : cardHtml(c.character, { action: 'loseCard', index: i })).join('');
    return `
      <div class="hero"><div class="eyebrow">You lose a card</div><h1>Pick a card to reveal</h1>
        <p>You ${esc(s.pending && s.pending.lossReason ? s.pending.lossReason : 'lost influence')}. The card you tap is turned face up for good.</p></div>
      <div class="card"><div class="hand">${cards}</div></div>`;
  }

  function viewExchange(s) {
    const m = s.you;
    const cards = m.exchangeOptions.map((c, i) => cardHtml(c, { action: 'toggleKeep', index: i, selected: ui.keep.has(i) })).join('');
    const done = ui.keep.size === m.keepCount;
    return `
      <div class="hero"><div class="eyebrow">Exchange</div><h1>Keep ${m.keepCount} card${m.keepCount === 1 ? '' : 's'}</h1>
        <p>Your cards plus two from the deck. The rest go back, shuffled. Nobody else sees these.</p></div>
      <div class="card"><div class="hand wrap">${cards}</div></div>
      <div class="actions"><button class="btn big" data-action="exchange" ${done ? '' : 'disabled'}>Keep these (${ui.keep.size}/${m.keepCount})</button></div>`;
  }

  function viewWatching(s, out) {
    const p = s.pending;
    let head;
    if (s.phase === 'ACTION') {
      head = `<h1>${esc(nameOf(s.turnId))} is choosing</h1>`;
    } else if (s.phase === 'RESPONSE' && p) {
      head = `<div class="claim-box"><div class="big">${claimText(p)}</div></div>${waitingList(p)}`;
    } else if (s.phase === 'BLOCK_RESPONSE' && p) {
      head = `<div class="claim-box"><div class="big">${esc(nameOf(p.blockerId))} claims ${chip(p.blockCharacter)} to block</div></div>${waitingList(p)}`;
    } else if (s.phase === 'LOSE_INFLUENCE' && p) {
      head = `<h1>${esc(nameOf(p.losingPlayerId))} loses a card</h1><p>They ${esc(p.lossReason || '')} and choose which card to reveal.</p>`;
    } else if (s.phase === 'EXCHANGE' && p) {
      head = `<h1>${esc(nameOf(p.actorId))} is exchanging</h1><p>Picking cards from the Court deck in secret.</p>`;
    } else head = '<h1>Waiting…</h1>';
    const yourPart = s.phase === 'RESPONSE' || s.phase === 'BLOCK_RESPONSE'
      ? (out ? '' : '<p class="center muted">You already answered. Waiting for the others…</p>') : '';
    return `<div class="hero"><div class="eyebrow">${out ? 'You are out · watching' : 'Coup'}</div>${head}</div>
      ${yourPart}
      ${s.phase === 'ACTION' ? '' : '<p class="center muted pulse">Waiting…</p>'}
      ${hostSkip(s)}`;
  }

  function viewGameOver(s) {
    const m = s.you;
    const w = byId(s.winnerId);
    return `
      <div class="hero"><div class="eyebrow">Game over</div><div class="big-result good">${w ? esc(w.name) : '?'} wins</div>
        <p>${isMe(s.winnerId) ? 'You are the last one standing. 🏆' : 'Last player standing.'}</p></div>
      <div class="card"><h3>Everyone's cards</h3>${tableHtml(s)}</div>
      <div class="card"><h3>Log</h3><ul class="log">${s.log.slice(-15).map((e) => `<li>${esc(e)}</li>`).join('')}</ul></div>
      ${m.isHost ? `<div class="actions"><button class="btn big" data-action="playAgain">Play again (same players)</button><button class="btn ghost" data-action="restart">Back to the lobby</button></div>` : '<p class="center muted">The host can start a new game.</p>'}`;
  }

  function renderHistory() {
    const s = ui.state;
    if (!s) { closeHistory(); return; }
    const host = s.you.isHost && s.phase !== 'LOBBY';
    sheetBody.innerHTML = `
      <h3>Players</h3>${tableHtml(s, { host: true })}
      ${s.you.isHost ? '<p class="muted" style="font-size:14px;margin-top:8px">If a friend lost their seat (new browser, cleared data), tap Let rejoin and they join again with the same name.</p>' : ''}
      ${host && s.phase !== 'GAME_OVER' ? '<button class="btn ghost" data-action="forceContinue" style="margin-top:8px">Skip players who aren’t answering</button>' : ''}
      ${host ? '<button class="btn danger" data-action="restart" style="margin-top:8px">Restart game (back to lobby)</button>' : ''}
      <div style="height:14px"></div>
      <h3>Log</h3>${s.log.length ? `<ul class="log">${s.log.slice().reverse().map((e) => `<li>${esc(e)}</li>`).join('')}</ul>` : '<p class="muted">Nothing yet.</p>'}
      <div style="height:14px"></div>${rulesCard(s.mode)}`;
    sheetEl.classList.remove('hidden');
  }
  function closeHistory() { ui.historyOpen = false; sheetEl.classList.add('hidden'); }

  // ------------------------------------------------------------ feedback
  let toastTimer = null;
  function toast(text) {
    toastEl.textContent = text;
    toastEl.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.add('hidden'), 3500);
  }
  let bannerTimer = null;
  function banner(text) {
    bannerEl.style.top = (statusEl.classList.contains('hidden') ? 12 : statusEl.offsetHeight + 8) + 'px';
    bannerEl.textContent = text;
    bannerEl.classList.remove('hidden');
    clearTimeout(bannerTimer);
    bannerTimer = setTimeout(() => bannerEl.classList.add('hidden'), 6000);
  }

  const modalEl = $('modal');
  const modalBody = $('modal-body');
  let modalOnYes = null;
  function showModal(html, opts) {
    opts = opts || {};
    modalOnYes = opts.onYes || null;
    modalBody.innerHTML = html;
    modalEl.querySelector('.modal-card').className = 'modal-card ' + (opts.cls || '');
    modalEl.classList.remove('hidden');
  }
  function closeModal() { modalOnYes = null; modalEl.classList.add('hidden'); modalBody.innerHTML = ''; }
  function confirmDialog(text, onYes) {
    showModal(`<p style="font-size:19px;font-weight:700;margin:6px 0 16px">${esc(text)}</p>
      <div class="row"><button class="btn ghost" data-action="closeModal">Cancel</button><button class="btn" data-action="confirmYes">Yes</button></div>`, { onYes });
  }
  function showCardsModal() {
    const s = ui.state;
    if (!s || !s.you.cards.length) return;
    showModal(`<h3>Your cards</h3><div class="hand">${s.you.cards.map((c) => cardHtml(c.character, { lost: c.revealed })).join('')}</div>
      <button class="btn secondary" data-action="closeModal" style="margin-top:14px">Hide</button>`);
  }
  let winnerShownFor = null;
  function maybeAnnounceWinner(s) {
    if (s.phase !== 'GAME_OVER') {
      // A new game (or the lobby) makes the old winner announcement stale: don't leave it covering the screen.
      if (winnerShownFor !== null && !modalEl.classList.contains('hidden') && !modalOnYes) closeModal();
      winnerShownFor = null;
      return;
    }
    if (winnerShownFor === s.version || winnerShownFor !== null) return;
    winnerShownFor = s.version;
    const w = byId(s.winnerId);
    showModal(`<div class="announce"><div class="eyebrow">Game over</div><div class="big-result good">${w ? esc(w.name) : '?'}</div><div class="counts">wins the game${isMe(s.winnerId) ? ' — that’s you! 🏆' : ''}</div></div>
      <button class="btn" data-action="closeModal" style="margin-top:10px">See everyone's cards</button>`, { cls: 'good' });
  }

  function showOverlay(text) { overlayText.textContent = text; overlayEl.classList.remove('hidden'); }
  function hideOverlay() { overlayEl.classList.add('hidden'); }

  // ------------------------------------------------------------ events
  document.addEventListener('submit', (ev) => {
    if (ev.target && ev.target.id === 'join-form') {
      ev.preventDefault();
      const name = (ev.target.elements.name.value || '').trim();
      if (!name) { toast('Please enter a name'); return; }
      store.set('name', name);
      ui.error = null; ui.joining = true;
      const msg = { type: 'join', name };
      const hostKey = store.get('hostKey');
      if (hostKey) msg.hostKey = hostKey;
      if (send(msg)) render();
    }
  });

  document.addEventListener('click', (ev) => {
    const el = ev.target.closest('[data-action]');
    if (!el) return;
    const s = ui.state;
    const action = el.getAttribute('data-action');
    const id = el.getAttribute('data-id');
    const index = parseInt(el.getAttribute('data-index'), 10);
    switch (action) {
      case 'start': act('start'); break;
      case 'setMode': act('setOptions', { mode: el.getAttribute('data-mode') }); break;
      case 'kick': confirmDialog('Remove ' + nameOf(id) + ' from the table?', () => act('kick', { playerId: id })); break;
      case 'chooseAction': {
        const t = el.getAttribute('data-type');
        ui.chosenAction = ui.chosenAction === t ? null : t;
        ui.chosenTarget = null;
        render();
        break;
      }
      case 'chooseTarget': ui.chosenTarget = id; render(); break;
      case 'declare': {
        const a = ui.chosenAction;
        if (!a) break;
        const extra = { actionType: a };
        if (ui.chosenTarget) extra.targetId = ui.chosenTarget;
        if (a === 'COUP' || a === 'ASSASSINATE') {
          const t = ui.chosenTarget;
          confirmDialog((a === 'COUP' ? 'Launch a Coup against ' : 'Assassinate ') + nameOf(t) + '?', () => act('declare', extra));
        } else act('declare', extra);
        break;
      }
      case 'pass': act('pass'); break;
      case 'challenge': act('challenge'); break;
      case 'block': act('block', { card: el.getAttribute('data-card') }); break;
      case 'loseCard': {
        const c = s.you.cards[index];
        confirmDialog('Reveal your ' + cardName(c.character) + '? You lose it for good.', () => act('loseInfluence', { index }));
        break;
      }
      case 'toggleKeep': {
        if (ui.keep.has(index)) ui.keep.delete(index);
        else if (ui.keep.size < s.you.keepCount) ui.keep.add(index);
        render();
        break;
      }
      case 'exchange': act('exchange', { keep: Array.from(ui.keep) }); break;
      case 'forceContinue': confirmDialog('Skip whoever is not answering? Missing answers count as Allow; a stuck card choice is random.', () => act('forceContinue')); break;
      case 'playAgain': act('playAgain'); break;
      case 'restart': confirmDialog('Abandon this game and send everyone back to the lobby?', () => { closeHistory(); act('restart'); }); break;
      case 'allowRejoin': act('allowRejoin', { playerId: id }); break;
      case 'toggleHide': ui.hideCards = !ui.hideCards; store.set('hideCards', ui.hideCards ? '1' : '0'); render(); break;
      case 'showCards': showCardsModal(); break;
      case 'openHistory': ui.historyOpen = true; renderHistory(); break;
      case 'closeHistory': closeHistory(); break;
      case 'closeModal': closeModal(); break;
      case 'confirmYes': { const fn = modalOnYes; closeModal(); if (fn) fn(); break; }
      default: break;
    }
  });
  sheetEl.addEventListener('click', (ev) => { if (ev.target === sheetEl) closeHistory(); });
  modalEl.addEventListener('click', (ev) => { if (ev.target === modalEl) closeModal(); });
  bannerEl.addEventListener('click', () => bannerEl.classList.add('hidden'));

  // Tiny hook for automated UI tests (only what this player already sees).
  window.__coup = {
    get state() { return ui.state; },
    get connected() { return ui.connected; },
    dropConnection() { if (ws) { try { ws.close(); } catch (e) { /* ignore */ } } },
  };

  render();
  connect();
})();
