# True Hordes: in-game tests

## Sub-project 1 acceptance (single player, debug)

Passed 2026-10-10. Kept for reference: `Hordes_seedTest` / `Hordes_clearTest` were retired in sub-project 3 (the real population replaced the test dots), and the hook count is now 10.

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

## Sub-project 3 acceptance: population takeover (single player, debug)

Setup: `tools\deploy.ps1` (Claude runs it). Start a **new game** with True Hordes and ZombieBuddy (sandbox: True Hordes >
Building zombies from the start = on, the default). Open the game's debug population view if you use it (`zpop` debug
map) for check 2.

| # | Do | Expect |
|---|---|---|
| 1 | Start the new game; open `console.txt` | `hook targets found: 10`, `native off-screen spawning switched off`, `new game: the population will be seeded...`, then a few seconds after `world map READY`: `seeded the population: N outdoors, M in buildings (...) in X s` |
| 2 | Lua console: `Hordes_status()` | `population seeded`, `held` (most of the map), `real` (around you), `target`, `hand-over: out ..., in ...` |
| 3 | `Hordes_map()`, zoom out | Dots (density squares when zoomed out) spread unevenly over towns and countryside, empty Voronoi gaps, no dots in water |
| 4 | Walk into a town you have not visited | Zombies appear as the area loads, dressed for the area (army base, hospital...), some sitting against walls, crawlers present; `in` count rises |
| 5 | Enter a building and open rooms | Zombies already inside before you open rooms; **no second set** appears when you look into a room |
| 6 | Walk far away from that town | Its zombies become dots on the map; `out` count rises; `real` drops |
| 7 | Save and quit, reload, `Hordes_status()` | Same `held + real` (give or take kills), `population seeded`, no `duplicates dropped` growth beyond a handful |
| 8 | Kill some zombies, check `Hordes_status()` | `real` drops and nothing respawns at once nearby |
| 9 | (True Hits, when it stores state in ModData) Damage a zombie, walk away and come back | Its state is kept |
| 10 | Load an **existing** save (made before this sub-project) | `existing save: taking over the population gradually`; zombies still appear as vanilla; `population gradual` |
| 11 (optional) | Time: sleep or wait a few in-game days, `Hordes_status()` | `held` grows in areas you have not visited (start to peak curve); `target` matches |
| 12 (optional) | Dedicated server | Server `console.txt` shows the seeding line; zombies appear for players |

## Sub-project 4 acceptance: off-screen wandering and hordes (single player, debug)

Setup: Claude ran `tools\deploy.ps1`. Start a **new game** (True Hordes + ZombieBuddy). New sandbox options on the True
Hordes page: stragglers 1 in 75, horde forms at 10, smallest horde 5, follow chance, off-road chance, chance to be left
behind, chance to take a trail (30%), chance to notice a horde's moaning (1% per hour), chance to join a horde in sight
(5% per hour). Leave them at their defaults. Tuned offline on the real map: in Louisville about half the zombies are in
hordes after 10 in-game days, most after a month, and two city-sized hordes roam after 2 to 3 months.

| # | Do | Expect |
|---|---|---|
| 1 | Start; open `console.txt` | `hook targets found: 11`, `vanilla rally groups switched off ...`, the seeding line, `simulation started: N wanderers, 0 hordes` |
| 2 | `Hordes_map()`, then wait or sleep 1 to 3 in-game days | red dots drift, mostly along roads; none in water; none leave the map; yellow dots are stragglers |
| 3 | Near a populated area, fire a sound 200 tiles east of you (outside the loaded area; held zombies do not walk into it until sub-project 6): `Hordes_sound(getPlayer():getX()-500, getPlayer():getY())`, then wait 30 in-game minutes and look there on the map | dots converge on that point; when the sound ends (30 in-game minutes), a purple circle with a count (a horde) appears where 10 or more gathered |
| 4 | Watch that horde on the map for a while | moves at a believable pace (not faster than a walking zombie), pauses briefly, prefers roads |
| 5 | After about a week, `Hordes_hordes()` | several hordes; some say `following #N` (a smaller horde trailing a bigger one's moaning) |
| 6 | Walk towards a horde | its front rows appear as real zombies at the loaded edge, more as you get closer; no stacked blob on one tile; `in` rises in `Hordes_status()` |
| 7 | First person, map closed, 5 minutes | no regular hitches (a hitch every ~20 s means the save encode, finding I7); `Hordes_stats()` second line: `last step` about 2 ms or less |
| 8 | Save and quit, reload; `Hordes_hordes()` | the same hordes (ids, sizes); hordes near you appear right away |
| 9 | `Hordes_debug("core", true)`, wait an in-game hour | no `conservation off` warning in `console.txt` |
| 10 | Load a sub-project 3 save | everyone a wanderer, stragglers rolled (yellow dots appear), no zombies lost |
| 13 | `Hordes_hordes()` on a new game or your current save | first line counts "building hordes waiting (N zombies)": tens of thousands of building zombies grouped per house; dim purple dots/circles on the map |
| 14 | Fire `Hordes_sound(...)` 200 tiles away near houses, wait 2 to 6 in-game hours | building hordes there wake: 10+ come out as a horde walking to the spot, smaller groups as wanderers that gather there; about 1 in 10 building zombies stay inside |
| 15 | `Hordes_stats()` while fast-forwarding | `behind N in-game min` stays small at normal speed; at high speed it grows but hordes still form (zombies just move slower in game time) |
| 12 | Louisville at high game speed: watch `Hordes_map()` over Louisville for 1 to 3 in-game months, `Hordes_hordes()` | hordes form over the first weeks; by 2 to 3 months one or two hordes of 10,000+ roam the city and sometimes leave it |
| 11 (optional) | Wait a few in-game weeks | `held` (status) grows only with the sandbox's start-to-peak curve; a town that hordes walked out of stays emptier |

