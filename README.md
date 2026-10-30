# Avalon Offline

A private, fully offline version of *The Resistance: Avalon* for a group of friends.

* One Android phone is the **host**. It turns on its Wi-Fi hotspot and runs the host app.
* Everyone else connects to that hotspot and plays in their phone's **browser** (Android or iPhone). Nothing to install.
* **No internet is used at any point.** Every file is bundled; the player page makes no requests to anything but the host phone.
* The host phone is also a player.
* The server is the referee: it keeps all secrets and only tells each phone what that player is allowed to know.

Supports 5–10 players with Merlin, Percival, Morgana, the Assassin, Loyal Servants, Minions and the Lady of the Lake.

---

## 1. Getting the app (Windows 10, no programming tools needed)

The app is built automatically by GitHub every time the code changes. You download the finished APK from there.

1. Open this repository on GitHub and click the **Actions** tab at the top.
2. Click the newest run in the list (the top one with a green tick ✅).
3. Scroll down to the **Artifacts** section and click **avalon-offline-debug-apk**. A zip file downloads.
4. Right-click the zip → **Extract All…**. Inside is `app-debug.apk`.
5. Get the APK onto the host phone. Easiest ways:
   * plug the phone into the PC with a USB cable and copy the file into the phone's **Download** folder, or
   * email it to yourself / put it in Google Drive and open it on the phone.
6. On the phone, open the **Files** app, find `app-debug.apk` and tap it.
   * Android will say *"For your security, your phone is not allowed to install unknown apps from this source"*. Tap **Settings**, switch on **Allow from this source**, go back and tap **Install**.
   * If Play Protect warns you, choose **Install anyway**. (It's a private app that was never uploaded to the Play Store.)
7. You now have **Avalon Offline** in your app list.

You only need to do this once per phone, and only on the host phone. Repeat it when you want a newer version.

## 2. Hosting a game

1. On the host phone, **turn on the Wi-Fi hotspot**: *Settings → Network & internet → Hotspot & tethering → Wi-Fi hotspot*. (It works without a SIM card or mobile data: the hotspot is just a private Wi-Fi network.)
2. Open **Avalon Offline**. It starts the game server and shows a **QR code and an address** such as `http://192.168.43.1:8080/`.
   * If it says *No hotspot found*, the hotspot isn't on yet. Turn it on and the screen updates by itself.
   * Allow the notification when asked: the notification keeps the server running while the screen is off.
3. Friends **join the hotspot Wi-Fi** on their phones (the hotspot name and password are in the same settings screen), then **scan the QR code** with their camera, or type the address into their browser. They enter a name and they're in.
4. On the host phone tap **Open my player screen**. That's your own seat at the table. You can also tap **Open in Chrome instead** if you prefer.
5. When 5–10 people have joined, the host taps **Start game**. The Lady of the Lake is on automatically with 7 or more players; with 5–6 the host can switch it on in the lobby.
6. Play! Phones that fall asleep or lose Wi-Fi simply reconnect and show the same screen again. If a friend's browser has really lost its seat (for example they cleared their browser data), the host opens the player list, taps **Let … rejoin**, and the friend joins again with the same name.
7. At the end everyone sees all roles and the full vote history. The host can tap **Play again** to start a new game with the same people.
8. Need to abandon a game half-way (someone left, wrong settings)? The host opens **Players, votes & quest history** at the bottom of any screen and taps **Restart game (back to lobby)**. Everyone returns to the lobby, players can be added or removed, and new roles are dealt when the host starts again.

Tips
* Keep the host phone plugged in or charged; it's doing the work.
* Phones occasionally show *"No internet"* on the hotspot. That's expected and fine. If a phone asks whether to *stay connected*, say yes.
* iPhones: open the address in Safari. If the camera doesn't offer to open the link, type it in by hand.

## 2b. Using the real Avalon card pictures

The app ships with its own drawn role cards. If you'd rather see the card art you know from the physical game, add pictures of your own cards and they are used automatically:

1. Photograph or scan each role card (or use images you already have) and save them as PNG files with exactly these names:
   `merlin.png`, `percival.png`, `loyal_servant.png`, `morgana.png`, `assassin.png`, `minion.png`.
   Portrait orientation, roughly 600×800 pixels is plenty; keep each file under ~300 KB so phones load them quickly.
2. Put them in the folder `server/src/main/resources/web/img/` (next to the `.svg` files) and commit/push, or upload them through GitHub's web interface (**Add file → Upload files** inside that folder).
3. GitHub Actions builds a new APK with the pictures bundled. Install it on the host phone as in section 1.

If a `.png` is missing the drawn `.svg` card is shown instead. (The official artwork is copyrighted by its publisher, which is why it is not included in this repository; using photos of cards you own for a private game among friends is up to you.)

## 3. How the game works (rules implemented)

| Players | Good | Evil | Quest team sizes |
|---|---|---|---|
| 5 | 3 | 2 | 2, 3, 2, 3, 3 |
| 6 | 4 | 2 | 2, 3, 4, 3, 4 |
| 7 | 4 | 3 | 2, 3, 3, 4★, 4 |
| 8 | 5 | 3 | 3, 4, 4, 5★, 5 |
| 9 | 6 | 3 | 3, 4, 4, 5★, 5 |
| 10 | 6 | 4 | 3, 4, 4, 5★, 5 |

★ Quest 4 needs **two** Fail cards to fail at 7+ players.

* Good: Merlin, Percival, Loyal Servants. Evil: Morgana, Assassin, Minions of Mordred.
* Merlin sees who is evil. Percival sees Merlin and Morgana without knowing which is which. Evil players see each other. Loyal Servants see only their own role.
* Leadership rotates in seat (join) order; the first leader is random.
* The leader proposes a team; everyone votes; votes are revealed together with names. Five rejections in a row = evil wins.
* The team secretly plays Success/Fail (good players can only play Success). Only the counts are shown, shuffled.
* Three successful quests → the Assassin picks a player; if it's Merlin, evil wins, otherwise good wins. Three failed quests → evil wins.
* Lady of the Lake: starts with the player just before the first leader. After quests 2, 3 and 4 the holder examines someone who has never held it and privately learns only whether they are good or evil. The token passes to the examined player.

## 4. Running the server on a PC (for testing)

The exact same server runs on a computer, so you can try the game with several browser tabs, or host from a laptop.

**Install once**

1. Install a Java 17 (or newer) JDK, e.g. [Adoptium Temurin 17](https://adoptium.net/) – download the Windows `.msi`, run it, and tick *"Set JAVA_HOME"* during setup.
2. Download this repository: green **Code** button → **Download ZIP** → extract it somewhere (for example `C:\avalon`).

**Run**

Open *Command Prompt* in that folder (type `cmd` in the folder's address bar in Explorer) and run:

```bash
gradlew.bat :server:run
```

The first run downloads the build tools (needs internet once). When it prints `Avalon Offline server is running`, it lists the addresses to open. Open the *"YOUR screen"* link in your own browser (that makes you the host) and give the other address to the players on the same Wi-Fi. Press `Ctrl+C` to stop.

To build a single runnable file instead:

```bash
gradlew.bat :server:fatJar
```

```bash
java -jar server\build\libs\server-all.jar 8080
```

Windows Firewall will ask whether to allow Java to accept connections: allow it on **private** networks, otherwise phones can't reach the PC.

## 5. For developers

```
engine/   pure Kotlin game engine (rules, validation, per-player views) + unit tests
server/   Ktor HTTP + WebSocket server, bundled web UI (server/src/main/resources/web) + simulation and leak tests
app/      Android host app (foreground service, hotspot IP detection, QR code, WebView player screen)
tools/    headless-browser UI test and screenshot generator
docs/     screenshots of every screen (docs/screenshots)
```

Run all tests:

```bash
./gradlew :engine:test :server:test
```

What the tests cover:

* **Engine unit tests** – setup for every player count, role knowledge, team sizes, the two-fail rule, five rejections, assassination, Lady of the Lake (no repeated targets, loyalty-only results) and rejected illegal actions.
* **Simulated games** – the real server with 5–10 bot clients over WebSockets, random choices, random disconnects and reconnects, many games per player count.
* **Leak test** – every message each bot received is audited: no other player's role, no per-player quest cards, no Lady result for anyone but the examiner, no incomplete votes, no roles before the end.
* **Offline test** – the bundled web files are scanned for external URLs, and the headless-browser test records every request and fails if any leaves the host.

Headless-browser UI test and screenshots (needs Node.js and Playwright's Chromium):

```bash
./gradlew :server:fatJar
```

```bash
node tools/ui-test.mjs
```

It plays a full 5-player game through the real UI, simulates a phone losing Wi-Fi, checks the duplicate-name and Lady-of-the-Lake privacy rules from the browser's point of view, and writes screenshots to `docs/screenshots/`.

The Android module is only included when an Android SDK is available (`ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`), so the engine and server can be built and tested on a machine without it. GitHub Actions (`.github/workflows/android.yml`) runs the tests and builds the APK on every push.

### Security model

The server holds the full state and is the only referee. A client receives exactly one thing: its own `PlayerView`, computed by `Game.viewFor(playerId)`. Every action is validated for phase, actor and legality (`IllegalActionException`), and rejected actions change nothing. Reconnects use a random token stored in the browser; the host phone uses a separate host key that is only shown in the host app.
