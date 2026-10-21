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


## Sub-project 5a acceptance: sound core (single player, debug)

| # | Do | Expect |
|---|---|---|
| 1 | Load the save, `Hordes_status()`; check the console for `[TrueHordes]` errors | no "real-zombie hearing failed" or "sound capture failed" errors (they would mean a patch fell back to vanilla) |
| 2 | `Hordes_map()`, fire one shot | a shaded orange circle (gunfire) appears at you, darkest in the middle, fading to the edge; the list (right) shows `gunfire r<radius>` with wanderers/hordes counts; it fades ~30 s after |
| 3 | Fire with Louder Gunshots at 5, then set it to 1 (or disable) and fire again | the circle's radius (list `r`) is 5x bigger with the mod; off-screen dots respond from that far |
| 4 | Drive a car, start a generator or set off an alarm | blue (vehicle), teal (appliance) or yellow (alarm) circles; an alarm stays one entry while it rings |
| 5 | Let an A-Life NPC fire a gun near you (A-Life enabled) | a gunfire circle at the NPC; zombies head there |
| 6 | Click a row in the sound list | the map jumps to that sound |
| 7 | `Hordes_sound()` (radius 1,000) with real zombies around you 200-900 tiles out, and held ones beyond | real zombies near the edge ignore it about half the time; close ones all come; held dots respond the same way; lines run from responding hordes to the spot |
| 8 | Near a big held horde (find one with `Hordes_hordes()`), fire repeatedly from 600-800 tiles | each shot splits part of it off (new purple circles heading to you, list `split` counts); after many shots the rest follows (hive mind) |
| 9 | Stand just outside a big held horde's reach, then walk towards it (or let it come) | distant zombie groans from its direction, more and louder as it gets closer or bigger; silence when none is near |
| 10 | Sandbox: `SoundEdgeChance` 0 and `SoundCertainShare` 0.5 in a new game | fewer zombies at the edges of sounds respond, everyone inside half the radius does |


## Sub-project 5b acceptance: events (single player, debug)

Turn on `Hordes_debug("core", true)` first: every event the mod fires is logged in `console.txt` (`far meta event`,
`near gunshot`, `car alarm set off`, `A-Life fight`). Fast-forward to make the schedules come round.

| # | Do | Expect |
|---|---|---|
| 1 | New game, check `console.txt` | no `[TrueHordes]` errors; the game's own far gunshots never happen (only the mod's) |
| 2 | Fast-forward 1-3 in-game days, `Hordes_map()` | a far event 300-1000 tiles away: `meta event` (gunshots, several circles a few tenths of a second apart), `scream` or `dog`; dots near it respond |
| 3 | Listen when one fires (iso and Viewpoint) | the sound comes from its direction; closer ones are louder |
| 4 | Louder Gunshots at 5 vs off | far gunshot circles are r1000 with it, r600 without |
| 5 | At night after day 29 (outside a city) | owls and wolf howls still play now and then (ambience, no circle on the map) |
| 6 | Month 1: wait for `near gunshot` | gunshots 50-100 tiles away, loud; real zombies around you turn to it |
| 7 | Month 1, parked cars around: wait for `car alarm set off` | a real car 50-100 tiles away blares (yellow `car alarm` circle); it keeps going past vanilla's normal time; walk off so it unloads, come back later: still sounding and the battery lower; sit in the driver seat: it stops |
| 8 | A-Life enabled, let a while pass | console: `Project A-Life compatibility on`; `A-Life fight` entries; red `A-Life fight` circles off screen, zombies drawn there |
| 9 | Vanilla weather, heavy rain: fire a shot vs on a dry day | fewer zombies respond in the rain (the circle's counts) |
| 10 | True Weathers - Gameplay with regional weather on, `Hordes_map()` | blue shading where it rains; `storm thunder` circles over storms away from you; dots outside a storm head into it, dots inside ignore it |
| 11 | After the power shuts off, at night run a generator with lights on in a house | yellow light dots on the map; some zombies (about 1 in 4) and some hordes turn up at the house; with a generator on a streetlight, rarely one stands under it until morning |
| 12 | At night after the shutoff, shine a flashlight at zombies that have not seen you | about 1 in 10 of those the beam touches comes to you |
| 13 | During the day | no light luring (no new zombies drawn by lights) |
| 14 | Save and quit during a sounding car alarm and with lights lit, reload | the alarm keeps going (battery drained for the time passed); the light dots are still on the map |

## Sub-project 6 acceptance: materialisation bridge (deferred to the sub-project 7 server test, user)

| # | Steps | Expected |
|---|---|---|
| 1 | Stand still in a town for an in-game hour | real zombies keep wandering (yards, roads); some walk off out of sight; only building zombies and stragglers stand still |
| 2 | Fire a gun | real zombies that hear it go to the spot, mill, then wander on; some leave the loaded area |
| 3 | `Hordes_roadHorde(300)` near Muldraugh | outlines far up the highway; the horde walks in, becomes real without popping, walks past and out, one horde on the debug map |
| 4 | `Hordes_ringHordes(6, 100, 120)`, then a gunshot | all six converge on you |
| 5 | Debug map (`Hordes_map`) in town | real zombies green (bright in a real horde), real hordes ringed cyan; no invisible zombies or cars |
| 6 | Save and reload with a real horde on screen | still one horde (debug map, `Hordes_stats` real hordes) |
| 7 | 20+ zombies at a door | it breaks in well under an hour; a lone zombie takes about a day |

## Chase memory, far sight, moan (branch `chase-memory`)

| # | Do | Expect |
|---|----|--------|
| 1 | Get seen by a few zombies, break line of sight round a corner and keep running | they go to where they lost you, then on the way you were heading for about an in-game hour; `Hordes_stats` chases goes up |
| 2 | Same with a real horde where under half see you | only those chase; with over half, the whole horde follows |
| 3 | `Hordes_roadHorde(300)`, watch `Hordes_stats` | "walked in" climbs as the horde arrives |
| 4 | Viewpoint 3D on, a horde beyond the loaded area | dark figures where the horde is, on the ground, moving; console "Viewpoint found" |
| 5 | Viewpoint off (2D) | figures on the screen edge in the horde's direction |
| 6 | A 50+ horde just outside the loaded area | moaning from its direction; console "horde moan heard" once |

## Sub-project 8 acceptance: consequences while away

| # | Do | Expect |
|---|----|--------|
| 1 | Lure a horde to a house off screen (e.g. `Hordes_sound` far away), wait some hours, `Hordes_stats` | sieges > 0 |
| 2 | Walk back to that house | doors/windows broken or damaged by crowd size and hours, blood and glass |
| 3 | A far gunfire event off screen, then visit the spot | corpses there (stats corpses count drops when loaded) |
| 4 | Run a generator with lights at night, walk away | zombies lured until about when the fuel would run out, not after |
| 5 | One zombie at a window | takes far longer than before (about 17 in-game hours) |


## S8 extras (2026-10-21)
| Check | How | Pass |
|---|---|---|
| Shots per kill option | Sandbox `OffscreenShotsPerKill` 0 → far gunfire leaves no corpses; 1 → many | |
| Trample | Plant crops, leave (>1 cell away), `Hordes_roadHorde(300, x, y)` from beyond the crops so it walks over them while you are away, come back | crops destroyed, low fences down |
| Door swing | `DoorSwingChance` 100, open a house door, lure a zombie in from outside | door shuts behind it ~1-3 s later (slam); the zombie claws at it and alone never breaks it; 3 together do in time (`DoorBreakMinCrowd`) |
| Rain wash | Make blood outside, wait in rain (or set weather) a day | blood mostly gone; indoor blood stays |
| Rain wash map wide | With True Weathers, leave a bloody spot in a town where it rains, come back a day later | washed there; a dry town's blood stays |
| Edge crossing | Stand still an in-game hour, `Hordes_stats` twice | "walked in" and "walked off" both climb |
| Far view (Viewpoint) | Viewpoint "Far world up to" at max, look out over open ground, `Hordes_roadHorde(300)` | figures with heads near the edge, bars further, dots far; the horde a moving patch of dots out to ~4 km; no FPS drop |

## Retest after the 2026-10-21 test fixes

| What | How | Expect |
|---|---|---|
| Hordes_stats / Hordes_status | Run them in the console | Text, no "expected 0 arguments" error |
| No errors | Play 20 min with hordes, then search console.txt for `[TrueHordes] ERROR` | None (errors now carry `<-` stack frames: send them if any) |
| Silhouettes occluded | Viewpoint 3D, stand behind a house / inside with walls / behind a car / in woods | Figures behind them are not drawn; through a window or open ground they are |
| Leaving a real horde | `Hordes_roadHorde(300)`, let it reach you, run well away | It stays where it was left (no jump towards you, does not follow the loaded edge); debug map shows it purple again, not scattered red dots |
| Coming back | Walk back to it | It comes in when its centre is in the real area |
| Moan | Stand ~100-250 tiles from a held horde, then near a real one | Moaning both times, louder closer; console line `horde moan: ... sound started` (if "FAILED to start", report it). Now the game's groan files (b412e54): "started" but silent means the clip is out of range or the Zombie volume slider is down |
| A-Life ambient gunfire | New game with Project A-Life (opener plays gunfire ~95 tiles away) | Debug map shows an "A-Life fight" sound there; `Hordes_debug("core", true)` logs `A-Life sound ...` |

## Retest after test round 2 (2026-10-21)

| What | How | Expect |
|---|---|---|
| Moan chorus | Stand 100-250 tiles from a held horde of 50+, turn around; then near a big one | A chorus of 3-10 chase gurgles from the horde's direction, quieter than before, fuller (not louder) for bigger hordes; console `horde moan: ... N voices ... started` |
| Moan range | `Hordes_moanTest(10)`, call again to stop; try 5, 12, 20 | One chase gurgle from the east; note the farthest distance you still hear it (the chorus stands 12 tiles out) |
| Moan options | Sandbox "Hear hordes moaning" off; volume 0-100 | Off: silence; volume scales it |
| Far sight | New game | Outlines off by default (existing saves keep their setting) |
| Loaded horde | Let a horde walk into the loaded area past loaded zombies | Whole horde keeps walking; zombies it brushes join; after reaching a sound it sweeps round the area (never standing) for about an in-game hour, then walks on |
| Why standing | `Hordes_stats()` while watching a horde | `horde members N: dormant, held by vanilla, waiting, stuck` counts; report them if many stand |
