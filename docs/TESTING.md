# True Hordes: in-game tests

## Sub-project 1 acceptance (single player, debug)

Setup: run `tools\deploy.ps1`, start the game with ZombieBuddy active, enable **True Hordes** (and ZombieBuddy) for a
new or existing save. ZombieBuddy may ask to approve the True Hordes jar the first time: approve it.

| # | Do | Expect |
|---|---|---|
| 1 | Load the save. Open `console.txt` | `[TrueHordes] True Hordes 0.1.0 loading`, `hook targets found: 5`, then `started with 0 entities` |
| 2 | Lua console: `Hordes_status()` | `state RUNNING`, `mode single player`, `entities 0` |
| 3 | `Hordes_seedTest(1000)` then `Hordes_map()` | Map window; about 1,000 red dots within 300 tiles of the green player square; wheel zooms (density squares when zoomed out), drag pans, right-click follows |
| 4 | `Hordes_stats()` | `entities=1000` and a `tick:` timing line |
| 5 | Save and quit to the main menu, reload the save | `console.txt`: `stopped`, then `started with 1000 entities (OK: loaded)`; the dots are back on `Hordes_map()` |
| 6 | Look in the save folder | `truehordes\state.bin` and, after a second save, `state.bin.bak` |
| 7 | `Hordes_clearTest()`, save, reload | `started with 0 entities` |
| 7b | `Hordes_seedTest(200)`, walk around for a few minutes, then quit by killing the game (Task Manager), reload | `started with ... entities` close to 200 and **no** "mod save is from world hour" warning (hot saves kept the mod file in step); `Hordes_debug("core", true)` beforehand shows `saved ... entities` lines while playing |
| 8 | Sandbox: set True Hordes > Enabled off on a new world | `switched off in sandbox options`; `Hordes_status()` shows `state OFF` |
| 9 | Without ZombieBuddy (or jar not approved): `Hordes_status()` | `True Hordes Java core is NOT loaded`, no Lua errors |
| 10 (optional) | Dedicated server with ZombieBuddy and the jar | Server `console.txt` shows `loading (dedicated server)` and `started`; no errors |

## Sub-project 2 acceptance: world knowledge (single player, debug)

Setup: `tools\deploy.ps1` (Claude runs it), then load a save with True Hordes and ZombieBuddy.

| # | Do | Expect |
|---|---|---|
| 1 | Load the save; open `console.txt` | `live map updates on`, then a few seconds after `started`: `world map READY \| built in ... s \| ... MB \| chunks: ...` |
| 2 | Lua console: `Hordes_world()` | `READY`, the build time, chunk counts per terrain, `cells: ... from save, ... from map, ... missing, 0 damaged`, and `roads from files: ... streets, ... world-map roads, ... trails` |
| 3 | `Hordes_map()`, zoom out | Coloured terrain: roads grey, trails brown, towns tan, farms yellow, forest green (darker deep forest), water blue, buildings black; it matches the in-game world map, and rural roads no longer stop short |
| 4 | Find a river with a road bridge (for example around tile 12316, 6718, north-east of Muldraugh) | River blue, the bridge grey across it (not blue) |
| 5 | Zoom in until walls appear (4 px per tile or more) | White lines on wall edges, dark squares on solid tiles, around the buildings near you |
| 6 | Build a wall (or break a window, or chop a tree) next to you, then look at the map zoomed in | The wall appears (or the window gap / tree spot changes) within a second; `Hordes_world()` shows more `tile changes` |
| 7 | Save and quit, reload, `Hordes_world()` and the map | The wall is still there; `from save` is above 0 |
| 8 (optional) | Dedicated server | Server `console.txt` shows the `world map READY` line |
