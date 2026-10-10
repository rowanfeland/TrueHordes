# True Hordes: in-game tests

## Sub-project 1 acceptance (single player, debug)

Setup: run `tools\deploy.ps1`, start the game with ZombieBuddy active, enable **True Hordes** (and ZombieBuddy) for a
new or existing save. ZombieBuddy may ask to approve the True Hordes jar the first time: approve it.

| # | Do | Expect |
|---|---|---|
| 1 | Load the save. Open `console.txt` | `[TrueHordes] True Hordes 0.1.0 loading`, `hook targets found: 3`, then `started with 0 entities` |
| 2 | Lua console: `TH_status()` | `state RUNNING`, `mode single player`, `entities 0` |
| 3 | `TH_seedTest(1000)` then `TH_map()` | Map window; about 1,000 red dots within 300 tiles of the green player square; wheel zooms (density squares when zoomed out), drag pans, right-click follows |
| 4 | `TH_stats()` | `entities=1000` and a `tick:` timing line |
| 5 | Save and quit to the main menu, reload the save | `console.txt`: `stopped`, then `started with 1000 entities (OK: loaded)`; the dots are back on `TH_map()` |
| 6 | Look in the save folder | `truehordes\state.bin` and, after a second save, `state.bin.bak` |
| 7 | `TH_clearTest()`, save, reload | `started with 0 entities` |
| 8 | Sandbox: set True Hordes > Enabled off on a new world | `switched off in sandbox options`; `TH_status()` shows `state OFF` |
| 9 | Without ZombieBuddy (or jar not approved): `TH_status()` | `True Hordes Java core is NOT loaded`, no Lua errors |
| 10 (optional) | Dedicated server with ZombieBuddy and the jar | Server `console.txt` shows `loading (dedicated server)` and `started`; no errors |
