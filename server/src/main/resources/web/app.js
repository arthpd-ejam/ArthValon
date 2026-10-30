/* Avalon Offline — player client. Plain JS, no dependencies, no network requests except to the host. */
(function () {
  'use strict';

  // ------------------------------------------------------------ storage
  const store = {
    get(k) { try { return localStorage.getItem('avalon.' + k); } catch (e) { return null; } },
    set(k, v) { try { if (v == null) localStorage.removeItem('avalon.' + k); else localStorage.setItem('avalon.' + k, v); } catch (e) { /* private mode etc. */ } },
  };

  // The host phone opens ...?host=KEY once; remember the key so a reload still works.
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

  // ------------------------------------------------------------ state
  const ui = {
    state: null,         // last PlayerView
    server: null,        // ServerInfo
    me: null,            // player id
    connected: false,
    joining: false,
    roleShown: false,
    selected: new Set(), // team picks
    pickedLady: null,
    pickedAssassin: null,
    lastVersion: -1,
    lastEventShown: null,
    historyOpen: false,
    error: null,
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
  let heartbeat = null;
  let lastMessageAt = 0;

  function wsUrl() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return proto + '//' + location.host + '/ws';
  }

  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    clearTimeout(retryTimer);
    showOverlay(ui.me ? 'Reconnecting…' : 'Connecting…');
    try {
      ws = new WebSocket(wsUrl());
    } catch (e) {
      scheduleReconnect();
      return;
    }
    ws.onopen = () => {
      retryMs = 500;
      ui.connected = true;
      lastMessageAt = Date.now();
      const token = store.get('token');
      if (token) {
        send({ type: 'resume', token });
      } else {
        hideOverlay();
        render();
      }
    };
    ws.onmessage = (ev) => {
      lastMessageAt = Date.now();
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      onMessage(msg);
    };
    ws.onclose = () => {
      ui.connected = false;
      ws = null;
      scheduleReconnect();
      render();
    };
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

  function act(action, extra) {
    return send(Object.assign({ type: 'action', action }, extra || {}));
  }

  // Phones suspend timers and sockets when the screen is off; recover eagerly.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      if (!ws || ws.readyState !== WebSocket.OPEN) { retryMs = 300; connect(); }
      else send({ type: 'ping' });
    }
  });
  window.addEventListener('online', () => { retryMs = 300; connect(); });
  window.addEventListener('pageshow', () => { if (!ws) connect(); });

  heartbeat = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      if (Date.now() - lastMessageAt > 45000) {
        try { ws.close(); } catch (e) { /* ignore */ }
      } else {
        send({ type: 'ping' });
      }
    }
  }, 20000);

  // ------------------------------------------------------------ messages
  function onMessage(msg) {
    switch (msg.type) {
      case 'joined':
        ui.me = msg.playerId;
        ui.joining = false;
        ui.lastVersion = -1;
        store.set('token', msg.token);
        hideOverlay();
        break;
      case 'state':
        if (msg.state.version < ui.lastVersion) return; // stale
        onState(msg.state, msg.server);
        hideOverlay();
        break;
      case 'error':
        ui.joining = false;
        if (msg.fatal) {
          // Our seat is gone (server restarted, or kicked): back to the join screen.
          store.set('token', null);
          ui.me = null; ui.state = null; ui.lastVersion = -1;
          hideOverlay();
          ui.error = msg.message;
          render();
        } else {
          toast(msg.message);
        }
        break;
      case 'kicked':
        store.set('token', null);
        ui.me = null; ui.state = null; ui.lastVersion = -1;
        ui.error = msg.message;
        render();
        break;
      case 'pong':
        break;
      default:
        break;
    }
  }

  function onState(state, server) {
    const prev = ui.state;
    ui.lastVersion = state.version;
    ui.state = state;
    ui.server = server || null;
    ui.me = state.you.id;
    if (!prev || prev.phase !== state.phase || prev.quest !== state.quest) {
      ui.selected = new Set();
      ui.pickedLady = null;
      ui.pickedAssassin = null;
      if (state.phase === 'ROLE_REVEAL' && (!prev || prev.phase !== 'ROLE_REVEAL')) ui.roleShown = false;
    }
    if (state.phase === 'LOBBY') ui.roleShown = false;
    if (state.lastEvent && state.lastEvent !== ui.lastEventShown) {
      ui.lastEventShown = state.lastEvent;
      if (prev) banner(state.lastEvent);
    }
    render();
  }

  // ------------------------------------------------------------ helpers
  function me() { return ui.state ? ui.state.you : null; }
  function players() { return ui.state ? ui.state.players : []; }
  function byId(id) { return players().find((p) => p.id === id); }
  function nameOf(id) { const p = byId(id); return p ? p.name : '?'; }
  function initials(name) { return (name || '?').trim().slice(0, 2).toUpperCase(); }
  function isMe(id) { return ui.me === id; }

  function avatar(p, cls) {
    const extra = (cls || '') + (isMe(p.id) ? ' me' : '') + (ui.state && ui.state.leaderId === p.id ? ' leader' : '');
    return `<span class="avatar ${extra}" aria-hidden="true">${esc(initials(p.name))}</span>`;
  }

  function nameTags(ids) {
    return `<div class="names">${ids.map((id) => `<span class="tag">${esc(nameOf(id))}</span>`).join('')}</div>`;
  }

  const ROLE_INFO = {
    MERLIN: { name: 'Merlin', loyalty: 'GOOD', text: 'You know who is evil. Guide the good team without being obvious — if the Assassin identifies you at the end, evil wins.' },
    PERCIVAL: { name: 'Percival', loyalty: 'GOOD', text: 'You see two names: one is Merlin, the other is Morgana pretending to be Merlin. Work out which is which and protect Merlin.' },
    LOYAL_SERVANT: { name: 'Loyal Servant of Arthur', loyalty: 'GOOD', text: 'You know nothing but your loyalty. Watch the votes and quests closely and help good succeed on three quests.' },
    MORGANA: { name: 'Morgana', loyalty: 'EVIL', text: 'You appear as Merlin to Percival. Sow confusion, fail quests and help the Assassin find the real Merlin.' },
    ASSASSIN: { name: 'Assassin', loyalty: 'EVIL', text: 'If good wins three quests, you get one shot: name Merlin and evil wins anyway.' },
    MINION: { name: 'Minion of Mordred', loyalty: 'EVIL', text: 'You know your fellow evil players. Get onto quests and fail them, or make the votes fail five times in a row.' },
  };

  // ------------------------------------------------------------ render
  function render() {
    const s = ui.state;
    renderStatus(s);
    let html;
    if (!s || !ui.me) html = viewJoin();
    else {
      switch (s.phase) {
        case 'LOBBY': html = viewLobby(s); break;
        case 'ROLE_REVEAL': html = viewRoleReveal(s); break;
        case 'TEAM_SELECTION': html = viewTeamSelection(s); break;
        case 'TEAM_VOTE': html = viewVote(s); break;
        case 'QUEST': html = viewQuest(s); break;
        case 'LADY_PICK': html = viewLadyPick(s); break;
        case 'LADY_RESULT': html = viewLadyResult(s); break;
        case 'ASSASSINATION': html = viewAssassination(s); break;
        case 'GAME_OVER': html = viewGameOver(s); break;
        default: html = `<div class="card">Unknown phase ${esc(s.phase)}</div>`;
      }
    }
    app.innerHTML = html;
    app.setAttribute('data-phase', s ? s.phase : 'JOIN');
    if (ui.historyOpen) renderHistory();
    const input = app.querySelector('input[autofocus]');
    if (input && document.activeElement !== input && !('ontouchstart' in window)) input.focus();
  }

  function renderStatus(s) {
    if (!s || s.phase === 'LOBBY' || s.phase === 'GAME_OVER' || !ui.me) { statusEl.classList.add('hidden'); return; }
    statusEl.classList.remove('hidden');
    const track = s.questSizes.map((size, i) => {
      const q = s.quests[i];
      let cls = q ? (q.succeeded ? 'success' : 'fail') : (i + 1 === s.quest ? 'current' : '');
      const star = s.failsRequired[i] === 2 ? '<span class="star" title="Needs two fails">★</span>' : '';
      return `<span class="q ${cls}" title="Quest ${i + 1}: team of ${size}">${size}${star}</span>`;
    }).join('');
    const rejects = [0, 1, 2, 3, 4].map((i) => `<span class="dot ${i < s.rejections ? 'on' : ''} ${i === 4 ? 'last' : ''}"></span>`).join('');
    const lady = s.ladyEnabled && s.ladyHolderId ? `<div><span class="label">Lady</span> <span class="value">${esc(nameOf(s.ladyHolderId))}</span></div>` : '';
    statusEl.innerHTML = `
      <div class="status-row">
        <div><span class="label">Quest</span> <span class="value">${s.quest} of 5</span></div>
        <div class="track">${track}</div>
      </div>
      <div class="status-row">
        <div style="min-width:0"><span class="label">Leader</span> <span class="value">${esc(nameOf(s.leaderId))}</span></div>
        ${lady}
        <div class="rejects" title="Rejected proposals this quest: ${s.rejections} of 5"><span class="label">Rejects</span> ${rejects}</div>
      </div>`;
  }

  // ---- join
  function viewJoin() {
    const saved = store.get('name') || '';
    const hostKey = store.get('hostKey');
    return `
      <div class="hero">
        <div class="eyebrow">Offline party game</div>
        <h1>Avalon</h1>
        <p>${hostKey ? 'You are the host. Enter your name to take your seat.' : 'Enter your name to join the table.'}</p>
      </div>
      ${ui.error ? `<div class="card evil">${esc(ui.error)}</div>` : ''}
      <form class="card" id="join-form" autocomplete="off">
        <label for="name" class="muted">Your name</label>
        <input class="input" id="name" name="name" maxlength="20" placeholder="e.g. Arthur" value="${esc(saved)}" autofocus autocapitalize="words" autocomplete="off" enterkeyhint="go">
        <div style="height:12px"></div>
        <button class="btn big" type="submit" ${ui.connected ? '' : 'disabled'}>${ui.joining ? 'Joining…' : 'Join the table'}</button>
      </form>
      <p class="muted center" style="font-size:14px">No internet needed. Stay connected to the host's Wi-Fi hotspot.</p>`;
  }

  // ---- lobby
  function viewLobby(s) {
    const m = s.you;
    const n = s.players.length;
    const canStart = n >= 5 && n <= 10;
    const list = s.players.map((p) => `
      <li>
        ${avatar(p)}
        <span class="name">${esc(p.name)}${isMe(p.id) ? ' <span class="muted">(you)</span>' : ''}</span>
        ${p.isHost ? '<span class="tag gold">Host</span>' : ''}
        <span class="conn ${p.connected ? '' : 'off'}" title="${p.connected ? 'connected' : 'disconnected'}"></span>
        ${m.isHost && !p.isHost ? `<button class="btn small danger" data-action="kick" data-id="${esc(p.id)}" aria-label="Remove ${esc(p.name)}">Remove</button>` : ''}
      </li>`).join('');
    const joinUrl = ui.server && ui.server.joinUrl;
    const ladyDefault = n >= 7;
    const ladyOn = s.ladyEnabled;
    return `
      <div class="hero">
        <div class="eyebrow">Lobby</div>
        <h1>${n} player${n === 1 ? '' : 's'} at the table</h1>
        <p>${canStart ? 'Ready to start.' : n < 5 ? `Need ${5 - n} more (5–10 players).` : 'Too many players (max 10).'}</p>
      </div>
      ${m.isHost && joinUrl ? `<div class="card flat center"><h3>Friends open this address</h3><div class="linkbox">${esc(joinUrl)}</div><p class="muted" style="margin:8px 0 0;font-size:14px">They must be on your hotspot Wi-Fi.</p></div>` : ''}
      <div class="card"><ul class="list">${list}</ul></div>
      ${m.isHost ? `
        <div class="card">
          <div class="toggle" data-action="toggleLady" role="switch" aria-checked="${ladyOn}">
            <div><div style="font-weight:700">Lady of the Lake</div><div class="muted" style="font-size:14px">${ladyDefault ? 'On by default at 7+ players' : 'Optional at 5–6 players'}</div></div>
            <span class="switch ${ladyOn ? 'on' : ''}"></span>
          </div>
        </div>
        <div class="actions"><button class="btn big" data-action="start" ${canStart ? '' : 'disabled'}>Start game</button></div>`
        : `<p class="center muted pulse">Waiting for the host to start…</p>`}`;
  }

  // ---- role reveal
  function viewRoleReveal(s) {
    const m = s.you;
    const info = ROLE_INFO[m.role] || { name: m.role, loyalty: m.loyalty, text: '' };
    const good = m.loyalty === 'GOOD';
    const ready = s.readyIds.length;
    const n = s.players.length;
    const done = m.pendingAction !== 'CONFIRM_ROLE';
    let knowledge = '';
    if (m.role === 'MERLIN') knowledge = `<div class="know"><h3>These players are evil</h3>${nameTags(m.knownEvil)}</div>`;
    else if (m.role === 'PERCIVAL') knowledge = `<div class="know"><h3>Merlin and Morgana (in some order)</h3>${nameTags(m.merlinOrMorgana)}</div>`;
    else if (!good) knowledge = `<div class="know"><h3>Your fellow evil players</h3>${m.evilTeammates.length ? nameTags(m.evilTeammates) : '<span class="muted">none</span>'}</div>`;
    else knowledge = `<div class="know"><h3>What you know</h3><span class="muted">Only your own loyalty.</span></div>`;

    const card = ui.roleShown
      ? `<div class="card role-card ${good ? 'good' : 'evil'}" data-action="toggleRole">
           <div class="loyalty">${good ? 'Good' : 'Evil'}</div>
           <div class="role-name">${esc(info.name)}</div>
           <p class="muted" style="font-size:16px">${esc(info.text)}</p>
           ${knowledge}
           <p class="muted" style="margin:14px 0 0;font-size:14px">Tap the card to hide it</p>
         </div>`
      : `<div class="card role-card covered" data-action="toggleRole" role="button" tabindex="0">
           <div class="role-name" style="font-size:22px">Your secret role</div>
           <p class="muted">Make sure nobody is looking, then tap to reveal.</p>
         </div>`;
    return `
      <div class="hero"><div class="eyebrow">Role reveal</div><h1>Hello, ${esc(m.name)}</h1></div>
      ${card}
      <p class="center progress">${ready} of ${n} players ready</p>
      <div class="actions">
        ${done ? `<button class="btn secondary" disabled>Waiting for the others…</button>` : `<button class="btn big" data-action="confirmRole">I know my role</button>`}
        ${m.isHost && done && ready < n ? `<button class="btn ghost" data-action="forceContinue">Skip waiting (host)</button>` : ''}
      </div>`;
  }

  // ---- team selection
  function viewTeamSelection(s) {
    const m = s.you;
    const size = s.questSizes[s.quest - 1];
    const leader = nameOf(s.leaderId);
    const twoFails = s.failsRequired[s.quest - 1] === 2;
    if (m.pendingAction !== 'PICK_TEAM') {
      return `
        <div class="hero"><div class="eyebrow">Quest ${s.quest}</div><h1>${esc(leader)} is choosing</h1>
        <p>A team of ${size}${twoFails ? ' — this quest needs two fails to fail' : ''}.</p></div>
        ${lastVoteCard(s)}
        <p class="center muted pulse">Waiting for the leader…</p>
        ${historyButton()}`;
    }
    const picks = s.players.map((p) => {
      const sel = ui.selected.has(p.id);
      const disabled = !sel && ui.selected.size >= size;
      return `<li><button data-action="togglePick" data-id="${esc(p.id)}" class="${sel ? 'selected' : ''}" ${disabled ? 'disabled' : ''} aria-pressed="${sel}">${avatar(p)}<span class="n">${esc(p.name)}${isMe(p.id) ? ' (you)' : ''}</span></button></li>`;
    }).join('');
    return `
      <div class="hero"><div class="eyebrow">Quest ${s.quest} · You lead</div><h1>Pick ${size} for the quest</h1>
      <p>${twoFails ? 'This quest needs two fails to fail.' : 'Choose wisely — everyone will vote on your team.'}</p></div>
      ${lastVoteCard(s)}
      <ul class="pick">${picks}</ul>
      <div class="actions"><button class="btn big" data-action="proposeTeam" ${ui.selected.size === size ? '' : 'disabled'}>Propose team (${ui.selected.size}/${size})</button></div>
      ${historyButton()}`;
  }

  function lastVoteCard(s) {
    const last = s.voteHistory[s.voteHistory.length - 1];
    if (!last || last.quest !== s.quest || last.approved) return '';
    return `<div class="card flat"><h3>Last proposal (rejected ${s.rejections}/5)</h3>${voteGrid(last.votes, last.teamIds, last.leaderId)}</div>`;
  }

  function voteGrid(votes, teamIds, leaderId) {
    return `<div class="muted" style="font-size:14px;margin-bottom:6px">Team: ${teamIds.map(nameOf).map(esc).join(', ')} · Leader: ${esc(nameOf(leaderId))}</div>
      <div class="votes">${players().map((p) => {
        const v = votes[p.id];
        return `<div class="v ${v || 'pending'}"><span class="name">${esc(p.name)}</span><span>${v === 'APPROVE' ? '👍' : v === 'REJECT' ? '👎' : '…'}</span></div>`;
      }).join('')}</div>`;
  }

  // ---- vote
  function viewVote(s) {
    const m = s.you;
    const team = s.proposedTeam;
    const voted = s.votedIds.length;
    const n = s.players.length;
    const teamList = `<div class="card"><h3>Proposed team · Quest ${s.quest}</h3><ul class="list">${team.map((id) => `<li>${avatar(byId(id))}<span class="name">${esc(nameOf(id))}${isMe(id) ? ' (you)' : ''}</span>${id === s.leaderId ? '<span class="tag gold">Leader</span>' : ''}</li>`).join('')}</ul></div>`;
    const progress = `<div class="card flat"><h3>${voted} of ${n} voted</h3><div class="votes">${s.players.map((p) => `<div class="v ${s.votedIds.includes(p.id) ? '' : 'pending'}"><span class="name">${esc(p.name)}</span><span>${s.votedIds.includes(p.id) ? '✓' : '…'}</span></div>`).join('')}</div></div>`;
    if (m.pendingAction === 'VOTE') {
      return `
        <div class="hero"><div class="eyebrow">Vote · Rejection ${s.rejections} of 5</div><h1>${esc(nameOf(s.leaderId))} proposes</h1><p>Approve or reject this team. Votes are revealed together.</p></div>
        ${teamList}
        <div class="actions"><div class="row">
          <button class="btn big good" data-action="vote" data-vote="APPROVE">Approve</button>
          <button class="btn big evil" data-action="vote" data-vote="REJECT">Reject</button>
        </div></div>
        ${historyButton()}`;
    }
    return `
      <div class="hero"><div class="eyebrow">Vote · Quest ${s.quest}</div><h1>You voted ${m.yourVote === 'APPROVE' ? '👍 Approve' : '👎 Reject'}</h1><p class="pulse">Waiting for everyone…</p></div>
      ${teamList}${progress}${historyButton()}`;
  }

  // ---- quest
  function viewQuest(s) {
    const m = s.you;
    const team = s.proposedTeam;
    const onTeam = team.includes(m.id);
    const played = s.playedIds.length;
    const twoFails = s.failsRequired[s.quest - 1] === 2;
    const teamList = `<div class="card"><h3>On the quest</h3><ul class="list">${team.map((id) => `<li>${avatar(byId(id))}<span class="name">${esc(nameOf(id))}${isMe(id) ? ' (you)' : ''}</span><span class="tag ${s.playedIds.includes(id) ? 'ok' : 'off'}">${s.playedIds.includes(id) ? 'Played' : 'Deciding'}</span></li>`).join('')}</ul></div>`;
    if (m.pendingAction === 'PLAY_QUEST') {
      const canFail = m.allowedQuestCards.includes('FAIL');
      return `
        <div class="hero"><div class="eyebrow">Quest ${s.quest}${twoFails ? ' · needs two fails' : ''}</div><h1>Play your card</h1><p>Only the number of each card is revealed, never who played what.</p></div>
        ${teamList}
        <div class="actions">${canFail
          ? `<div class="row"><button class="btn big good" data-action="playQuest" data-card="SUCCESS">Success</button><button class="btn big evil" data-action="playQuest" data-card="FAIL">Fail</button></div>`
          : `<button class="btn big good" data-action="playQuest" data-card="SUCCESS">Play Success</button><p class="center muted" style="margin-top:8px;font-size:14px">As a good player you can only play Success.</p>`}
        </div>${historyButton()}`;
    }
    return `
      <div class="hero"><div class="eyebrow">Quest ${s.quest}</div><h1>${onTeam ? 'Card played' : 'The team is on the quest'}</h1><p class="pulse">${played} of ${team.length} cards played…</p></div>
      ${onTeam && m.yourQuestCard ? `<div class="card flat center">You played <strong>${m.yourQuestCard === 'FAIL' ? 'Fail' : 'Success'}</strong>.</div>` : ''}
      ${teamList}${historyButton()}`;
  }

  // ---- lady of the lake
  function ladyHeld(s) {
    const held = new Set();
    s.ladyHistory.forEach((h) => { held.add(h.holderId); held.add(h.targetId); });
    if (s.ladyHolderId) held.add(s.ladyHolderId);
    return held;
  }

  function viewLadyPick(s) {
    const m = s.you;
    const holder = nameOf(s.ladyHolderId);
    if (m.pendingAction !== 'LADY_PICK') {
      return `<div class="hero"><div class="eyebrow">Lady of the Lake</div><h1>${esc(holder)} examines a player</h1><p>They will privately learn whether that player is good or evil.</p></div>
        <p class="center muted pulse">Waiting for ${esc(holder)}…</p>${historyButton()}`;
    }
    const held = ladyHeld(s);
    const picks = s.players.filter((p) => p.id !== m.id).map((p) => {
      const eligible = !held.has(p.id);
      const sel = ui.pickedLady === p.id;
      return `<li><button data-action="pickLady" data-id="${esc(p.id)}" class="${sel ? 'selected' : ''}" ${eligible ? '' : 'disabled'}>${avatar(p)}<span class="n">${esc(p.name)}</span>${eligible ? '' : '<span class="tag off">held it</span>'}</button></li>`;
    }).join('');
    return `
      <div class="hero"><div class="eyebrow">Lady of the Lake · after quest ${s.quest - 1}</div><h1>Examine a player</h1><p>You will learn only whether they are good or evil. Players who already held the Lady cannot be chosen; the Lady then passes to them.</p></div>
      <ul class="pick">${picks}</ul>
      <div class="actions"><button class="btn big" data-action="ladyExamine" ${ui.pickedLady ? '' : 'disabled'}>Examine ${ui.pickedLady ? esc(nameOf(ui.pickedLady)) : ''}</button></div>
      ${historyButton()}`;
  }

  function viewLadyResult(s) {
    const m = s.you;
    const last = s.ladyHistory[s.ladyHistory.length - 1];
    if (m.pendingAction !== 'LADY_CONFIRM') {
      return `<div class="hero"><div class="eyebrow">Lady of the Lake</div><h1>${esc(nameOf(last.holderId))} examined ${esc(nameOf(last.targetId))}</h1><p>Only ${esc(nameOf(last.holderId))} sees the result.</p></div>
        <p class="center muted pulse">Waiting for ${esc(nameOf(last.holderId))}…</p>
        ${m.isHost ? `<div class="actions"><button class="btn ghost" data-action="forceContinue">Skip waiting (host)</button></div>` : ''}${historyButton()}`;
    }
    const mine = m.yourLadyResults.find((r) => r.targetId === last.targetId);
    const good = mine && mine.loyalty === 'GOOD';
    return `
      <div class="hero"><div class="eyebrow">Lady of the Lake · private</div><h1>${esc(nameOf(last.targetId))} is…</h1></div>
      <div class="card role-card ${good ? 'good' : 'evil'}"><div class="big-result ${good ? 'good' : 'evil'}">${good ? 'GOOD' : 'EVIL'}</div><p class="muted">Only you can see this. You may lie about it.</p></div>
      <div class="actions"><button class="btn big" data-action="ladyConfirm">Got it — pass the Lady</button></div>`;
  }

  // ---- assassination
  function viewAssassination(s) {
    const m = s.you;
    if (m.pendingAction !== 'ASSASSINATE') {
      return `<div class="hero"><div class="eyebrow">Three quests succeeded</div><h1>The Assassin strikes</h1><p>Evil gets one chance: if they name Merlin, evil wins. Good players: keep a straight face.</p></div>
        <p class="center muted pulse">Waiting for the Assassin…</p>${historyButton()}`;
    }
    const picks = s.players.filter((p) => p.id !== m.id).map((p) => {
      const sel = ui.pickedAssassin === p.id;
      const teammate = m.evilTeammates.includes(p.id);
      return `<li><button data-action="pickAssassin" data-id="${esc(p.id)}" class="${sel ? 'selected' : ''}" ${teammate ? 'disabled' : ''}>${avatar(p)}<span class="n">${esc(p.name)}</span>${teammate ? '<span class="tag evil">evil</span>' : ''}</button></li>`;
    }).join('');
    return `
      <div class="hero"><div class="eyebrow">You are the Assassin</div><h1>Who is Merlin?</h1><p>Discuss with your team, then choose. This ends the game.</p></div>
      <ul class="pick">${picks}</ul>
      <div class="actions"><button class="btn big evil" data-action="assassinate" ${ui.pickedAssassin ? '' : 'disabled'}>Assassinate ${ui.pickedAssassin ? esc(nameOf(ui.pickedAssassin)) : ''}</button></div>`;
  }

  // ---- game over
  function viewGameOver(s) {
    const m = s.you;
    const g = s.gameOver;
    const good = g.winner === 'GOOD';
    const roles = s.players.map((p) => {
      const r = g.roles[p.id];
      const info = ROLE_INFO[r] || { name: r, loyalty: '' };
      const isGood = info.loyalty === 'GOOD';
      return `<tr><td>${esc(p.name)}${isMe(p.id) ? ' (you)' : ''}</td><td class="${isGood ? 'good-text' : 'evil-text'}">${esc(info.name)}</td><td>${g.assassinTargetId === p.id ? '🎯' : ''}</td></tr>`;
    }).join('');
    return `
      <div class="hero"><div class="eyebrow">Game over</div><div class="big-result ${good ? 'good' : 'evil'}">${good ? 'GOOD WINS' : 'EVIL WINS'}</div><p>${esc(g.reason)}</p></div>
      <div class="card"><h3>Everyone's roles</h3><table><tbody>${roles}</tbody></table>${g.assassinTargetId ? '<p class="muted" style="margin:8px 0 0;font-size:14px">🎯 = the Assassin\'s target</p>' : ''}</div>
      <div class="card"><h3>Quests</h3>${questHistoryHtml(s)}</div>
      ${g.ladyResults.length ? `<div class="card"><h3>Lady of the Lake</h3>${g.ladyResults.map((r) => { const h = s.ladyHistory.find((x) => x.afterQuest === r.afterQuest); return `<div class="hist-item">${esc(nameOf(h ? h.holderId : ''))} examined <strong>${esc(nameOf(r.targetId))}</strong>: <span class="${r.loyalty === 'GOOD' ? 'good-text' : 'evil-text'}">${r.loyalty}</span> <span class="muted">(after quest ${r.afterQuest})</span></div>`; }).join('')}</div>` : ''}
      <div class="card"><h3>Votes</h3>${voteHistoryHtml(s)}</div>
      ${m.isHost ? `<div class="actions"><button class="btn big" data-action="playAgain">Play again (same players)</button></div>` : '<p class="center muted">The host can start a new game with the same players.</p>'}`;
  }

  // ---- history
  function historyButton() {
    if (!ui.state) return '';
    return `<p class="center"><button class="subtle-link" data-action="openHistory">Players, votes &amp; quest history</button></p>`;
  }

  // Everyone's connection status; the host can open a disconnected seat to be rejoined by name.
  function playersHtml(s) {
    const rejoinable = (ui.server && ui.server.rejoinable) || [];
    return `<ul class="list">${s.players.map((p) => `
      <li>${avatar(p)}<span class="name">${esc(p.name)}${isMe(p.id) ? ' <span class="muted">(you)</span>' : ''}</span>
        ${p.id === s.leaderId ? '<span class="tag gold">Leader</span>' : ''}
        ${s.ladyHolderId === p.id ? '<span class="tag">Lady</span>' : ''}
        <span class="conn ${p.connected ? '' : 'off'}" title="${p.connected ? 'connected' : 'disconnected'}"></span>
        ${s.you.isHost && !p.connected && !isMe(p.id) ? (rejoinable.includes(p.id)
          ? '<span class="tag ok">can rejoin</span>'
          : `<button class="btn small secondary" data-action="allowRejoin" data-id="${esc(p.id)}">Let ${esc(p.name)} rejoin</button>`) : ''}
      </li>`).join('')}</ul>
      ${s.you.isHost ? '<p class="muted" style="font-size:14px;margin-top:8px">If a friend lost their seat (new browser, cleared data), let them rejoin: they join again with the same name.</p>' : ''}`;
  }

  function questHistoryHtml(s) {
    if (!s.quests.length) return '<p class="muted">No quests finished yet.</p>';
    return s.quests.map((q) => `
      <div class="hist-item">
        <div class="h"><span>Quest ${q.quest}: <span class="${q.succeeded ? 'good-text' : 'evil-text'}">${q.succeeded ? 'Success' : 'Failed'}</span></span><span class="muted">${q.failsRequired === 2 ? 'needed 2 fails' : ''}</span></div>
        <div class="cards-result">${'<div class="qcard success">SUCCESS</div>'.repeat(q.successes)}${'<div class="qcard fail">FAIL</div>'.repeat(q.fails)}</div>
        <div class="muted" style="font-size:14px">Team: ${q.teamIds.map(nameOf).map(esc).join(', ')} · Leader: ${esc(nameOf(q.leaderId))}</div>
      </div>`).join('');
  }

  function voteHistoryHtml(s) {
    if (!s.voteHistory.length) return '<p class="muted">No votes yet.</p>';
    return s.voteHistory.slice().reverse().map((v) => `
      <div class="hist-item">
        <div class="h"><span>Quest ${v.quest} · proposal ${v.attempt}</span><span class="${v.approved ? 'good-text' : 'evil-text'}">${v.approved ? 'Approved' : 'Rejected'}</span></div>
        ${voteGrid(v.votes, v.teamIds, v.leaderId)}
      </div>`).join('');
  }

  function renderHistory() {
    const s = ui.state;
    if (!s) { closeHistory(); return; }
    sheetBody.innerHTML = `<h3>Players</h3>${playersHtml(s)}<div style="height:14px"></div><h3>Quests</h3>${questHistoryHtml(s)}<div style="height:14px"></div><h3>Votes</h3>${voteHistoryHtml(s)}`;
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
    // Sit just below the status bar so it never covers the action buttons at the bottom.
    bannerEl.style.top = (statusEl.classList.contains('hidden') ? 12 : statusEl.offsetHeight + 8) + 'px';
    bannerEl.textContent = text;
    bannerEl.classList.remove('hidden');
    clearTimeout(bannerTimer);
    bannerTimer = setTimeout(() => bannerEl.classList.add('hidden'), 7000);
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
      ui.error = null;
      ui.joining = true;
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
    switch (action) {
      case 'start': act('start'); break;
      case 'kick': if (confirm('Remove ' + nameOf(id) + ' from the table?')) act('kick', { playerId: id }); break;
      case 'toggleLady': act('setOptions', { ladyOfTheLake: !s.ladyEnabled }); break;
      case 'toggleRole': ui.roleShown = !ui.roleShown; render(); break;
      case 'confirmRole': ui.roleShown = false; act('confirmRole'); break;
      case 'forceContinue': act('forceContinue'); break;
      case 'togglePick': {
        const size = s.questSizes[s.quest - 1];
        if (ui.selected.has(id)) ui.selected.delete(id);
        else if (ui.selected.size < size) ui.selected.add(id);
        render();
        break;
      }
      case 'proposeTeam': act('proposeTeam', { team: Array.from(ui.selected) }); break;
      case 'vote': act('vote', { vote: el.getAttribute('data-vote') }); break;
      case 'playQuest': act('playQuest', { card: el.getAttribute('data-card') }); break;
      case 'pickLady': ui.pickedLady = ui.pickedLady === id ? null : id; render(); break;
      case 'ladyExamine': if (ui.pickedLady) act('ladyExamine', { playerId: ui.pickedLady }); break;
      case 'ladyConfirm': act('ladyConfirm'); break;
      case 'pickAssassin': ui.pickedAssassin = ui.pickedAssassin === id ? null : id; render(); break;
      case 'assassinate': if (ui.pickedAssassin && confirm('Assassinate ' + nameOf(ui.pickedAssassin) + '? This ends the game.')) act('assassinate', { playerId: ui.pickedAssassin }); break;
      case 'playAgain': act('playAgain'); break;
      case 'openHistory': ui.historyOpen = true; renderHistory(); break;
      case 'allowRejoin': act('allowRejoin', { playerId: id }); break;
      case 'closeHistory': closeHistory(); break;
      default: break;
    }
  });
  sheetEl.addEventListener('click', (ev) => { if (ev.target === sheetEl) closeHistory(); });
  bannerEl.addEventListener('click', () => bannerEl.classList.add('hidden'));

  // Expose a tiny hook for automated UI tests (no game data beyond what the player already sees).
  window.__avalon = {
    get state() { return ui.state; },
    get connected() { return ui.connected; },
    // Lets the UI test simulate a dropped Wi-Fi connection.
    dropConnection() { if (ws) { try { ws.close(); } catch (e) { /* ignore */ } } },
  };

  render();
  connect();
})();
