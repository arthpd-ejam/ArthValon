#!/usr/bin/env node
/**
 * End-to-end UI test with a real headless browser.
 *
 *  - starts the JVM server (server/build/libs/server-all.jar)
 *  - opens 5 phone-sized browser tabs, joins, plays a full game through the real UI
 *  - records EVERY network request each tab makes and fails if any goes anywhere but the host
 *  - simulates a phone losing Wi-Fi and checks the view is restored
 *  - saves screenshots of each screen to docs/screenshots/
 *
 * Usage:  ./gradlew :server:fatJar && node tools/ui-test.mjs
 */
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { mkdirSync, existsSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Resolve Playwright wherever it is installed (globally or locally).
const require = createRequire(import.meta.url);
const { chromium, devices } = require('playwright');

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const jar = path.join(root, 'server/build/libs/server-all.jar');
const outDir = path.join(root, 'docs/screenshots');
const PORT = Number(process.env.PORT || 18080);
const NAMES = ['Arthur', 'Guinevere', 'Lancelot', 'Gawain', 'Morgause'];

if (!existsSync(jar)) { console.error(`Missing ${jar}. Run: ./gradlew :server:fatJar`); process.exit(2); }
rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const fail = (msg) => { failures.push(msg); console.error('FAIL:', msg); };

// ---------------------------------------------------------------- server
console.log('Starting server…');
const server = spawn('java', ['-jar', jar, String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'] });
let hostUrl = null;
let serverOut = '';
server.stdout.on('data', (d) => {
  serverOut += d.toString();
  const m = serverOut.match(/http:\/\/127\.0\.0\.1:\d+\/\?host=\S+/);
  if (m) hostUrl = m[0];
});
for (let i = 0; i < 100 && !hostUrl; i++) await sleep(100);
if (!hostUrl) { console.error('Server did not start:\n' + serverOut); server.kill(); process.exit(2); }
const origin = `http://127.0.0.1:${PORT}`;
console.log('Server up at', origin);

// ---------------------------------------------------------------- browser
const browser = await chromium.launch();
const phone = devices['Pixel 7'] || devices['Pixel 5'];
const pages = [];
const requests = []; // {name, url}
let shotIndex = 0;
const taken = new Set();

async function shot(page, key) {
  if (taken.has(key)) return;
  taken.add(key);
  const file = path.join(outDir, `${String(++shotIndex).padStart(2, '0')}-${key}.png`);
  await page.screenshot({ path: file, fullPage: false });
  console.log('  📸', path.basename(file));
}

for (let i = 0; i < NAMES.length; i++) {
  const ctx = await browser.newContext({ ...phone, viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: 'dark' });
  const page = await ctx.newPage();
  page.on('request', (r) => requests.push({ name: NAMES[i], url: r.url() }));
  page.on('pageerror', (e) => fail(`${NAMES[i]}: page error ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') fail(`${NAMES[i]}: console error ${m.text()}`); });
  page.name = NAMES[i];
  page.ctx = ctx;
  pages.push(page);
}

const state = (page) => page.evaluate(() => (window.__avalon && window.__avalon.state) || null);
async function waitFor(page, pred, what, timeout = 10000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const s = await state(page);
    if (pred(s)) return s;
    await sleep(50);
  }
  throw new Error(`${page.name}: timeout waiting for ${what}`);
}

// ---------------------------------------------------------------- join
console.log('Joining…');
await pages[0].goto(hostUrl);
await pages[0].waitForSelector('#join-form');
await pages[0].waitForFunction(() => window.__avalon && window.__avalon.connected);
await shot(pages[0], 'join');
for (const page of pages) {
  if (page !== pages[0]) { await page.goto(origin + '/'); await page.waitForSelector('#join-form'); await page.waitForFunction(() => window.__avalon && window.__avalon.connected); }
  await page.fill('#name', page.name);
  await page.click('#join-form button[type=submit]');
  await waitFor(page, (s) => s && s.phase === 'LOBBY', 'lobby');
}
await waitFor(pages[0], (s) => s.players.length === 5, '5 players');
// Duplicate name must be refused with a visible message.
{
  const dupCtx = await browser.newContext({ ...phone, viewport: { width: 390, height: 844 } });
  const dup = await dupCtx.newPage();
  dup.on('request', (r) => requests.push({ name: 'Dup', url: r.url() }));
  await dup.goto(origin + '/');
  await dup.waitForFunction(() => window.__avalon && window.__avalon.connected);
  await dup.fill('#name', 'arthur');
  await dup.click('#join-form button[type=submit]');
  await dup.waitForSelector('.card.evil, .toast:not(.hidden)');
  const txt = await dup.textContent('body');
  if (!/already taken/i.test(txt)) fail('duplicate name was not refused: ' + txt.slice(0, 200));
  await dupCtx.close();
}
await pages[0].waitForSelector('[data-action=start]:not([disabled])');
await shot(pages[0], 'lobby-host');
await shot(pages[1], 'lobby-player');

// Lady of the Lake on at 5 players (host toggle)
await pages[0].click('[data-action=toggleLady]');
await waitFor(pages[0], (s) => s.ladyEnabled === true, 'lady enabled');
await pages[0].click('[data-action=start]');
await Promise.all(pages.map((p) => waitFor(p, (s) => s.phase === 'ROLE_REVEAL', 'role reveal')));

// ---------------------------------------------------------------- role reveal
console.log('Role reveal…');
await shot(pages[1], 'role-hidden');
const roles = {};
for (const page of pages) {
  const s = await state(page);
  roles[page.name] = s.you.role;
  await page.click('[data-action=toggleRole]');
  await page.waitForSelector('.role-card:not(.covered)');
  await shot(page, `role-${s.you.role.toLowerCase()}`);
}
console.log('  roles:', roles);
// knowledge sanity, from the player's own screen only
for (const page of pages) {
  const s = await state(page);
  const r = s.you.role;
  if (r === 'MERLIN' && s.you.knownEvil.length !== 2) fail('Merlin should see 2 evil');
  if (r === 'PERCIVAL' && s.you.merlinOrMorgana.length !== 2) fail('Percival should see 2 names');
  if (r === 'LOYAL_SERVANT' && (s.you.knownEvil.length || s.you.merlinOrMorgana.length || s.you.evilTeammates.length)) fail('Servant should know nothing');
  if ((r === 'MORGANA' || r === 'ASSASSIN') && s.you.evilTeammates.length !== 1) fail(`${r} should see 1 teammate`);
}
for (const page of pages.slice(0, 4)) await page.click('[data-action=confirmRole]');
await waitFor(pages[4], (s) => s.readyIds.length === 4, '4 ready');
await shot(pages[4], 'role-waiting-for-you');
await shot(pages[0], 'role-waiting-host');
await pages[4].click('[data-action=confirmRole]');
await Promise.all(pages.map((p) => waitFor(p, (s) => s.phase === 'TEAM_SELECTION', 'team selection')));

// ---------------------------------------------------------------- reconnect simulation
console.log('Simulating a phone losing Wi-Fi…');
{
  const page = pages[2];
  const before = await state(page);
  await page.ctx.setOffline(true);
  // Chromium keeps an already-open socket alive in offline emulation, so drop it like a phone would.
  await page.evaluate(() => window.__avalon.dropConnection());
  await page.waitForSelector('#overlay', { state: 'visible', timeout: 15000 }).catch(() => fail('no reconnecting overlay shown'));
  await shot(page, 'reconnecting');
  await page.ctx.setOffline(false);
  await page.waitForSelector('#overlay', { state: 'hidden', timeout: 20000 });
  const after = await waitFor(page, (s) => s && s.phase === 'TEAM_SELECTION', 'restored view');
  if (after.you.role !== before.you.role || after.you.id !== before.you.id) fail('view not restored after reconnect');
  // Full reload with the stored token must also restore the seat.
  await page.reload();
  const again = await waitFor(page, (s) => s && s.phase === 'TEAM_SELECTION', 'restored after reload');
  if (again.you.id !== before.you.id) fail('seat not restored after reload');
}

// On any unexpected error, dump what every tab shows so the failure can be understood from the log.
const dumpAndExit = async (err) => {
  console.error('UNEXPECTED:', err && err.message);
  for (const p of pages) {
    try {
      const s = await state(p);
      console.error(`--- ${p.name}: phase=${s && s.phase} pending=${s && s.you.pendingAction} v=${s && s.version}`);
      console.error((await p.textContent('#app')).replace(/\s+/g, ' ').slice(0, 400));
    } catch (e) { console.error(`--- ${p.name}: (no state) ${e.message}`); }
  }
  server.kill();
  process.exit(1);
};
process.on('unhandledRejection', dumpAndExit);
process.on('uncaughtException', dumpAndExit);

// ---------------------------------------------------------------- game loop
console.log('Playing…');
const good = (page) => ['MERLIN', 'PERCIVAL', 'LOYAL_SERVANT'].includes(roles[page.name]);
let guard = 0;
let historyShot = false;
while (guard++ < 400) {
  const states = await Promise.all(pages.map(state));
  const phase = states[0].phase;
  if (phase === 'GAME_OVER') break;
  let acted = false;
  for (let i = 0; i < pages.length; i++) {
    const page = pages[i], s = states[i];
    if (!s || s.phase !== phase) continue;
    const pa = s.you.pendingAction;
    switch (pa) {
      case 'PICK_TEAM': {
        await shot(page, `q${s.quest}-pick-team-leader`);
        const other = pages.find((p, j) => j !== i && states[j] && states[j].phase === phase);
        if (other) await shot(other, `q${s.quest}-pick-team-waiting`);
        const size = s.questSizes[s.quest - 1];
        // Put the leader on the team first, then fill up in seat order.
        const ids = [s.you.id, ...s.players.map((p) => p.id).filter((id) => id !== s.you.id)].slice(0, size);
        for (const id of ids) await page.click(`[data-action=togglePick][data-id="${id}"]`);
        await page.waitForSelector('[data-action=proposeTeam]:not([disabled])');
        await shot(page, `q${s.quest}-pick-team-selected`);
        await page.click('[data-action=proposeTeam]');
        acted = true;
        break;
      }
      case 'VOTE': {
        await shot(page, `q${s.quest}-vote`);
        // Reject the very first proposal once to exercise the rejection track, then approve.
        const reject = s.quest === 1 && s.rejections === 0;
        await page.click(`[data-action=vote][data-vote=${reject ? 'REJECT' : 'APPROVE'}]`);
        await waitFor(page, (x) => x.version > s.version, 'vote registered');
        await shot(page, `q${s.quest}-vote-waiting`);
        acted = true;
        break;
      }
      case 'PLAY_QUEST': {
        await shot(page, `q${s.quest}-quest-${good(page) ? 'good' : 'evil'}`);
        const other = pages.find((p, j) => j !== i && !states[j].proposedTeam.includes(states[j].you.id));
        if (other) await shot(other, `q${s.quest}-quest-waiting`);
        if (good(page) && (await page.$('[data-action=playQuest][data-card=FAIL]'))) fail('good player offered a Fail card');
        // Evil plays Success so that good wins three quests and we reach the assassination.
        await page.click('[data-action=playQuest][data-card=SUCCESS]');
        acted = true;
        break;
      }
      case 'LADY_PICK': {
        await shot(page, 'lady-pick');
        const other = pages.find((p, j) => j !== i);
        await shot(other, 'lady-waiting');
        const btn = await page.$('[data-action=pickLady]:not([disabled])');
        await btn.click();
        await page.click('[data-action=ladyExamine]:not([disabled])');
        acted = true;
        break;
      }
      case 'LADY_CONFIRM': {
        await page.waitForSelector('.big-result');
        await shot(page, 'lady-result');
        // Nobody else may see the result.
        for (let j = 0; j < pages.length; j++) if (j !== i) {
          const txt = await pages[j].textContent('#app');
          if (/is…/.test(txt) && /\b(GOOD|EVIL)\b/.test(txt)) fail(`${pages[j].name} can see the lady result`);
          if (states[j].you.yourLadyResults.length) fail(`${pages[j].name} received a lady result`);
        }
        await page.click('[data-action=ladyConfirm]');
        acted = true;
        break;
      }
      case 'ASSASSINATE': {
        await shot(page, 'assassin');
        const other = pages.find((p, j) => j !== i);
        await shot(other, 'assassin-waiting');
        if (!historyShot) {
          await other.click('[data-action=openHistory]');
          await other.waitForSelector('#sheet:not(.hidden)');
          await shot(other, 'history');
          await other.click('[data-action=closeHistory]');
          historyShot = true;
        }
        const btn = await page.$('[data-action=pickAssassin]:not([disabled])');
        await btn.click();
        page.once('dialog', (d) => d.accept());
        await page.click('[data-action=assassinate]:not([disabled])');
        acted = true;
        break;
      }
      default: break;
    }
    if (acted) {
      // Wait until the server has answered this tab before reading states again, so we never act twice.
      await waitFor(page, (x) => x.version > s.version, 'server to process the action');
      break;
    }
  }
  if (!acted) await sleep(60);
}
await Promise.all(pages.map((p) => waitFor(p, (s) => s.phase === 'GAME_OVER', 'game over')));
await shot(pages[0], 'game-over-host');
await shot(pages[1], 'game-over-player');
const over = await state(pages[1]);
console.log('  winner:', over.gameOver.winner, '-', over.gameOver.reason);
if (over.quests.filter((q) => q.succeeded).length !== 3) fail('expected three successful quests');
if (over.voteHistory.length < 4 || !over.voteHistory.some((v) => !v.approved)) fail('expected a rejected vote in history');
if (over.gameOver.ladyResults.length !== 1) fail('expected exactly one Lady of the Lake examination (after quest 2)');

// Play again keeps the players and deals new roles.
await pages[0].click('[data-action=playAgain]');
await Promise.all(pages.map((p) => waitFor(p, (s) => s.phase === 'ROLE_REVEAL', 'new game')));
const s2 = await state(pages[1]);
if (s2.players.length !== 5 || s2.quests.length) fail('play again did not reset with the same players');

// Role art must have loaded from the host (PNG if present, otherwise the bundled SVG).
await pages[1].click('[data-action=toggleRole]');
await pages[1].waitForSelector('.role-img');
const imgOk = await pages[1].evaluate(() => { const i = document.querySelector('.role-img'); return i.complete && i.naturalWidth > 0; });
if (!imgOk) fail('role image did not load');

// The host can restart a running game: everyone goes back to the lobby with no roles.
await pages[0].click('[data-action=openHistory]');
await pages[0].waitForSelector('[data-action=restart]');
await shot(pages[0], 'host-panel-restart');
pages[0].once('dialog', (d) => d.accept());
await pages[0].click('[data-action=restart]');
await Promise.all(pages.map((p) => waitFor(p, (s) => s.phase === 'LOBBY', 'lobby after restart')));
const s3 = await state(pages[1]);
if (s3.players.length !== 5 || s3.you.role) fail('restart did not return to a clean lobby');

// ---------------------------------------------------------------- offline check
const external = requests.filter((r) => !r.url.startsWith(origin + '/') && r.url !== origin);
if (external.length) fail('requests to other hosts: ' + JSON.stringify(external.slice(0, 10)));
const distinct = [...new Set(requests.map((r) => r.url))];
console.log(`Network: ${requests.length} requests, all to ${origin}; distinct URLs: ${distinct.join(', ')}`);

await browser.close();
server.kill();
if (failures.length) { console.error(`\n${failures.length} failure(s)`); process.exit(1); }
console.log(`\nOK — ${shotIndex} screenshots in ${path.relative(root, outDir)}/`);
