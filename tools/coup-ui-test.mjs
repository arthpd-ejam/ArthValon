#!/usr/bin/env node
/**
 * End-to-end Coup UI test with a real headless browser.
 *
 *  - starts the JVM server (server/build/libs/server-all.jar)
 *  - opens phone-sized tabs on /coup/, joins, plays full games (Classic, then Reformation) by tapping the real UI
 *  - records every network request and fails if any goes anywhere but the host
 *  - drops one phone's connection mid-game and checks it comes back to the same seat
 *  - saves screenshots of each screen to docs/screenshots/coup/
 *
 * Usage:  ./gradlew :server:fatJar && node tools/coup-ui-test.mjs
 */
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { mkdirSync, existsSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const jar = process.env.JAR || path.join(root, 'server/build/libs/server-all.jar');
const outDir = process.env.OUT || path.join(root, 'docs/screenshots/coup');
const PORT = Number(process.env.PORT || 18081);
const NAMES = ['Arth', 'Bea', 'Carlo', 'Dina', 'Enzo', 'Faye'];

if (!existsSync(jar)) { console.error(`Missing ${jar}. Run: ./gradlew :server:fatJar`); process.exit(2); }
rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const fail = (msg) => { failures.push(msg); console.error('FAIL:', msg); };

console.log('Starting server…');
const server = spawn('java', ['-jar', jar, String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'] });
let hostUrl = null;
let out = '';
server.stdout.on('data', (d) => {
  out += d.toString();
  const m = out.match(/http:\/\/127\.0\.0\.1:\d+\/coup\/\?host=\S+/);
  if (m) hostUrl = m[0];
});
for (let i = 0; i < 150 && !hostUrl; i++) await sleep(100);
if (!hostUrl) { console.error('Server did not start:\n' + out); server.kill(); process.exit(2); }
const origin = `http://127.0.0.1:${PORT}`;

const browser = await chromium.launch();
const requests = [];
let shotIndex = 0;
const taken = new Set();
async function shot(page, key) {
  if (taken.has(key)) return;
  taken.add(key);
  const file = path.join(outDir, `${String(++shotIndex).padStart(2, '0')}-${key}.png`);
  await page.screenshot({ path: file, fullPage: true });
  console.log('  📸', path.basename(file));
}

const pages = [];
for (let i = 0; i < NAMES.length; i++) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, colorScheme: 'dark' });
  const page = await ctx.newPage();
  page.on('request', (r) => requests.push({ name: NAMES[i], url: r.url() }));
  page.on('pageerror', (e) => fail(`${NAMES[i]}: page error ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') fail(`${NAMES[i]}: console error ${m.text()}`); });
  page.name = NAMES[i];
  pages.push(page);
}
const state = (page) => page.evaluate(() => (window.__coup && window.__coup.state) || null);
async function waitFor(page, pred, what, timeout = 10000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const s = await state(page);
    if (s && pred(s)) return s;
    await sleep(40);
  }
  throw new Error(`${page.name}: timed out waiting for ${what}`);
}
async function tap(page, sel) {
  await page.locator(sel).first().click({ timeout: 4000 });
}
async function confirmIfAsked(page) {
  const yes = page.locator('[data-action=confirmYes]');
  if (await yes.count()) { await yes.first().click({ timeout: 3000 }); }
}

let rnd = 12345;
const rand = (n) => { rnd = (rnd * 1103515245 + 12345) & 0x7fffffff; return rnd % n; };

async function playGame(players, label) {
  let steps = 0;
  let dropped = false;
  while (true) {
    if (++steps > 3000) throw new Error(`${label}: game did not finish`);
    const states = await Promise.all(players.map(state));
    if (states.every((s) => s && s.phase === 'GAME_OVER')) return states[0];
    let acted = false;
    for (let i = 0; i < players.length; i++) {
      const page = players[i];
      const s = states[i];
      if (!s) continue;
      const m = s.you;
      try {
        switch (m.pending) {
          case 'TAKE_ACTION': {
            await shot(page, `${label}-your-turn`);
            const allowed = m.actions.filter((a) => a.allowed);
            // Prefer interesting actions so every screen gets exercised.
            const pick = allowed[rand(allowed.length)];
            await tap(page, `[data-action=chooseAction][data-type=${pick.type}]`);
            if (['COUP', 'ASSASSINATE', 'STEAL'].includes(pick.type) || (pick.type === 'CONVERT')) {
              const ids = pick.targets;
              const target = pick.type === 'CONVERT' && (!ids.length || rand(2)) ? '' : ids[rand(ids.length)];
              await tap(page, `[data-action=chooseTarget][data-id="${target}"]`);
              await shot(page, `${label}-pick-target`);
            }
            await tap(page, '[data-action=declare]');
            await confirmIfAsked(page);
            acted = true;
            break;
          }
          case 'RESPOND': {
            await shot(page, `${label}-respond-${s.pending.action.toLowerCase()}`);
            const r = rand(10);
            if (r < 2 && m.canChallenge) await tap(page, '[data-action=challenge]');
            else if (r < 4 && m.canBlockWith.length) await tap(page, '[data-action=block]');
            else await tap(page, '[data-action=pass]');
            acted = true;
            break;
          }
          case 'RESPOND_BLOCK': {
            await shot(page, `${label}-respond-block`);
            if (rand(3) === 0) await tap(page, '[data-action=challenge]'); else await tap(page, '[data-action=pass]');
            acted = true;
            break;
          }
          case 'LOSE_INFLUENCE': {
            await shot(page, `${label}-lose-card`);
            await tap(page, '[data-action=loseCard]');
            await confirmIfAsked(page);
            acted = true;
            break;
          }
          case 'EXCHANGE': {
            await shot(page, `${label}-exchange`);
            for (let k = 0; k < m.keepCount; k++) await tap(page, `[data-action=toggleKeep][data-index="${k + 1}"]`);
            await shot(page, `${label}-exchange-picked`);
            await tap(page, '[data-action=exchange]');
            acted = true;
            break;
          }
          case 'ELIMINATED': await shot(page, `${label}-eliminated`); break;
          default:
            if (s.phase === 'RESPONSE') await shot(page, `${label}-watching-response`);
            if (s.phase === 'ACTION') await shot(page, `${label}-watching-turn`);
            if (s.phase === 'LOSE_INFLUENCE') await shot(page, `${label}-watching-loss`);
            break;
        }
      } catch (e) {
        // State moved on between reading it and tapping (another phone acted first): just retry.
        if (!/Timeout|timed out|not attached|detached/i.test(String(e))) throw e;
      }
      if (acted) break; // re-read everyone's state after each tap
    }
    if (!dropped && steps > 20) {
      dropped = true;
      const p = players[players.length - 1];
      const before = await state(p);
      await p.evaluate(() => window.__coup.dropConnection());
      await sleep(300);
      const after = await waitFor(p, (s) => s.version >= before.version, 'reconnect', 10000).catch((e) => { fail(e.message); return null; });
      if (after && after.you.id !== before.you.id) fail('reconnect landed on a different seat');
    }
    await sleep(acted ? 60 : 120);
  }
}

try {
  // ---------------- Classic, 4 players
  const classic = pages.slice(0, 4);
  await classic[0].goto(hostUrl);
  await shot(classic[0], 'join');
  for (let i = 1; i < classic.length; i++) await classic[i].goto(origin + '/coup/');
  for (const p of classic) {
    await p.locator('#name').fill(p.name);
    await p.locator('#join-form button[type=submit]').click();
  }
  await waitFor(classic[0], (s) => s.players.length === 4, 'all joined');
  await shot(classic[0], 'lobby-host');
  await shot(classic[1], 'lobby-player');
  await tap(classic[0], '[data-action=start]');
  await waitFor(classic[0], (s) => s.phase === 'ACTION', 'start');
  const end1 = await playGame(classic, 'classic');
  await sleep(300);
  for (const p of classic) { const s = await state(p); if (!s.winnerId) fail(`${p.name}: no winner`); }
  await shot(classic[0], 'classic-game-over-modal');
  await classic[0].locator('[data-action=closeModal]').first().click().catch(() => {});
  await shot(classic[0], 'classic-game-over');
  console.log('Classic winner:', end1.players.find((p) => p.id === end1.winnerId).name);

  // ---------------- Reformation, 6 players
  await tap(classic[0], '[data-action=restart]');
  await confirmIfAsked(classic[0]);
  await waitFor(classic[0], (s) => s.phase === 'LOBBY', 'back to lobby');
  for (let i = 4; i < 6; i++) {
    await pages[i].goto(origin + '/coup/');
    await pages[i].locator('#name').fill(pages[i].name);
    await pages[i].locator('#join-form button[type=submit]').click();
  }
  await waitFor(pages[0], (s) => s.players.length === 6, 'six joined');
  await tap(pages[0], '[data-action=setMode][data-mode=REFORMATION]');
  await waitFor(pages[0], (s) => s.mode === 'REFORMATION', 'mode');
  await shot(pages[0], 'reformation-lobby');
  await tap(pages[0], '[data-action=start]');
  await waitFor(pages[0], (s) => s.phase === 'ACTION', 'start reformation');
  taken.clear();
  const end2 = await playGame(pages, 'reform');
  console.log('Reformation winner:', end2.players.find((p) => p.id === end2.winnerId).name);
  await pages[1].locator('[data-action=closeModal]').first().click().catch(() => {});
  await shot(pages[1], 'reform-game-over');
  await pages[0].locator('[data-action=openHistory]').first().click().catch(() => {});
  await shot(pages[0], 'reform-table-sheet');
} catch (e) {
  fail(String(e && e.stack || e));
}

const outside = requests.filter((r) => !r.url.startsWith(origin) && !r.url.startsWith('ws://127.0.0.1:' + PORT) && !r.url.startsWith('data:'));
if (outside.length) fail('requests left the host: ' + outside.map((r) => r.url).join(', '));
console.log(`${requests.length} requests, all to the host: ${!outside.length}`);

await browser.close();
server.kill();
if (failures.length) { console.error(`\n${failures.length} failure(s)`); process.exit(1); }
console.log('\nCoup UI test passed.');
