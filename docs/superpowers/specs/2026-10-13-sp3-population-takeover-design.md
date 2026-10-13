# Sub-project 3: population takeover (design)

Parent design: `design.md` (sections 4.2, 4.5, 7; decisions D1, D10, D17; question Q5). Approved in conversation on
2026-10-13.

## Goal

The mod owns every zombie. The game's native off-screen population stops creating zombies; every zombie that leaves
the loaded area joins the mod's population; a new game starts with the whole map's population created up front by the
mod, distributed exactly like vanilla; growth and respawn follow the vanilla sandbox settings but are done by the
mod. Real zombies near players are part of the population too. **No wandering yet**: held zombies stay where they
are until sub-project 4, and the arrival step here is a basic one until sub-project 6.

## Decisions made in conversation

- **New games are the target.** Existing saves get option A: the native population stops creating zombies, and the
  native zombies it already holds join the mod as areas are explored (they arrive real, then are taken when they
  leave). No bulk absorption.
- **Start = vanilla.** Same numbers and distribution as vanilla, from the same settings. Hordes are not placed: they
  form over weeks once wandering exists.
- **The vanilla population settings feed the mod** instead of the native spawner (Population multiplier, start and
  peak multipliers, peak day, respawn hours, unseen hours, respawn multiplier). Raising Population raises the mod's
  population.
- **Respawn**: in long-unseen areas early on, shifting over months to newcomers arriving at the map's edges. New
  sandbox option `TrueHordes.EdgeArrivalMonths` (default 12): the share of newcomers placed at the edges is
  `min(1, months since the game started / EdgeArrivalMonths)`.
- **Mod data survives off-screen**, for any mod (the user's True Hits keeps zombie state in `ModData`). A dedicated
  True Hits hook for state kept elsewhere can be a quick patch near the end of the project.
- Method: **mirror the game's own zombie records** (option 1), seed the whole map at the start of a new game.

## Facts (verified in the decompiled 42.21 game)

- **Native configuration** comes only from `ZombiePopulationManager.onConfigReloaded()` (called from `init`):
  `n_configFloat("PopulationMultiplier", ...)`, `PopulationStartMultiplier`, `PopulationPeakMultiplier`,
  `n_configInt("PopulationPeakDay")`, `RespawnHours`, `RespawnUnseenHours`, `RespawnMultiplier`, `RedistributeHours`,
  `FollowSoundDistance`, `MinZombiesPerChunk`, `MaxZombiesPerChunk`, `UniformZombiesPerChunk` (0.2). The native side
  fills a 256×256 cell to its target the first time anything touches it and respawns only if `RespawnHours > 0`.
- **Out**: `ZombiePopulationManager.removeChunkFromWorld(IsoChunk)` hands every live, non-reanimated-player zombie of
  an unloading chunk to native with `n_addZombie(x, y, z, dir, persistentOutfitID, stateFlags, pathTargetX/Y or
  MIN_VALUE, persistentId)` (dead ones `die()`). `ZombiePopulationManager.virtualizeZombie(IsoZombie)` does the same
  for one zombie; it is called from `IsoMovingObject` when a zombie walks onto a square that is not loaded.
- **In**: `addChunkToWorld(IsoChunk)` calls `n_loadChunk(wx, wy, true)`; the native side hands that chunk's held
  zombies back through `updateMain`, which calls the private `addZombieStanding(x, y, z, dir, descriptorID, state,
  persistentId)` or `addZombieMoving(..., pathTargetX, pathTargetY, persistentId)`. These:
  - drop the record if a real zombie with that `persistentId` is already alive (`isStaleRecord`);
  - need a loaded square with a solid floor, **otherwise hand the zombie to native** (`n_addZombie`);
  - delete it if it is too close to a player's spawn point (`PlayerSpawns.allowZombie`);
  - sit a third of standing ones against a wall, restore crawler and fake-dead states, and create the zombie with
    `VirtualZombieManager.createRealZombieAlways(descriptorID, dir, false, persistentId)`.
- **Outfits**: `descriptorID` is the zombie's persistent outfit ID; 0 means "dress by the area's zombie type"
  (`dressInRandomOutfit`, zone `ZombiesType` definitions). **IDs**: `createRealZombieAlways` registers the zombie and
  gets its `persistentId` from native (`registerZombie`); 0 asks for a new one.
- **Distribution**: `LotHeader.getZombieIntensityForChunk(info, wx, wy)` (0–255 per 8×8 chunk) already includes
  B42's **zombie Voronoi noise**: at map load `IsoMetaGrid` multiplies each chunk's intensity by every
  `ZombieVoronoi` mask (world seed; sandbox `ZombieVoronoiNoise`, default on) and stores the result. The mod reads
  that stored value.
- **Story and scripted zombies** (vehicle stories, survivor-in-horde rooms, trigger hordes, Lua spawns) are created
  real directly; they reach the mod through the same out-hooks when they leave.
- ZombieBuddy `@Patch.OnEnter(skipOn = true)` can skip the original method; `@Patch.Argument(n)` passes arguments.
- `KahluaTableImpl.save(ByteBuffer)` / `load(ByteBuffer, int)` serialise a Lua table (used for `ModData`).

## 1. Zombie records and the store

- Record fields (the game's own off-screen fields, plus mod data): position (x, y, z), direction, persistent outfit
  ID (0 = not dressed yet), state flags (`ZombieStateFlags` int: crawler, fake dead, can walk, can crawl under
  vehicle, initialised, reanimated for grapple), `persistentId` (0 = none yet), path target (or none), and an
  optional mod-data blob (bytes from `KahluaTableImpl.save`, kept only when the zombie had mod data).
- The SP1 `Store` and `Entity` become these records. Indexes: by chunk (records in a chunk, for arrival and
  respawn counts), by `persistentId` (duplicates).
- Save format version 2 holds the records; version 1 (SP1 placeholder entities) still loads (placeholders are
  discarded with a log line). This closes the SP1 deferred item "no test for upgrading save versions".

## 2. Handing over

- **Native off**: a patch on `onConfigReloaded` (exit) re-sends `PopulationMultiplier`, `PopulationStartMultiplier`,
  `PopulationPeakMultiplier`, `RespawnMultiplier` and `RespawnHours` as 0 (the private native setters are called by
  reflection). The game's own sandbox values are not changed. Whether "multiplier 0" fully stops cell population and
  respawn is checked by the first plan task (DLL read plus an in-game check with the game's population debug view);
  if it does not, the task picks the native lever that does and records it.
- **Out**: `removeChunkFromWorld` (enter): the mod takes every live zombie of the chunk (same filters as the game:
  not a reanimated player, not dead) into its store and removes it from the world, then lets the game run, which
  finds none. `virtualizeZombie` (enter, skip): the mod takes the zombie instead of the game. Both record the
  zombie's mod data.
- **In**: `addChunkToWorld` (exit): for each held record in that chunk, the mod checks the square is loaded with a
  solid floor and calls the game's private `addZombieStanding` / `addZombieMoving` by reflection (moving if it has a
  path target). Records that fail the floor check stay held. A record deleted by the spawn-point rule is counted
  ("refused near a player's spawn point"), as vanilla deletes it. After creation, the mod data blob is loaded back
  into the new zombie's `ModData`, matched by `persistentId`.
- **No duplicates**: before spawning a chunk's records, any held record whose `persistentId` belongs to a real zombie
  already alive (`isStaleRecord`'s registry, or a real zombie in the chunk loaded from the chunk save) is dropped and
  counted. Every hand-over is counted: out, in, dropped as duplicate, refused near spawn, kept (no floor).
- Single player and dedicated server; never on a multiplayer client.

## 3. Seeding, growth and respawn

- **When**: on the core's first start in a world with no mod save **and** a freshly created world (the exact signal
  is chosen in the plan from the game code; a mod added to an older save does not seed).
- **Per-chunk target**: vanilla's formula, read out of `PZPopMan64.dll` by the first plan task (from the chunk
  intensity, the population and start/peak multipliers, min/max per chunk and the uniform per-chunk value), checked
  against the game's own per-cell targets in the debug population view.
- **Seeding** places each chunk's target as individual standing zombies at random outdoor walkable tiles of that
  chunk (sub-project 2's tile queries, which exclude rooms; vanilla also drops held zombies inside rooms of newly
  created chunks). Zombies inside buildings come, as in vanilla, from the game's own building spawns when a chunk is
  first created; they are real and join the population like any other. Outfit 0, no `persistentId` yet.
- **Growth**: the target follows the start → peak curve to the peak day as the DLL computes it; the mod adds the
  difference over time.
- **Respawn**: every `RespawnHours` (if above 0), cells (256×256) unseen by any player for `RespawnUnseenHours` that
  are below target by the DLL's threshold get up to `target × RespawnMultiplier` newcomers. Each newcomer is placed in
  that cell (unseen placement) or, with the edge share from `EdgeArrivalMonths`, at a walkable chunk next to the
  map's outer edge (playable chunks bordering no-data areas, from sub-project 2's grid).
- **Counting** includes real zombies (the game's live zombie list, per cell) so loaded towns are not refilled.

## 4. Saving, debug, testing

- Saving: as sub-project 1 (full and hot saves, synchronized core), format version 2.
- Debug: `Hordes_map()` draws the held records (density at low zoom); `Hordes_status()` adds held, real, target, and
  the hand-over counts; `Hordes_seedTest` / `Hordes_clearTest` are retired.
- Offline tests: records and the 1 → 2 upgrade; chunk and ID indexes; duplicate dropping; seeding counts against the
  formula (with a fake intensity map including Voronoi-zero chunks); growth curve; respawn timing, unseen test and
  edge share; mod-data blob round trip; hook targets and reflected methods exist in the game jar.
- In game (`docs/TESTING.md`): new game → `console.txt` seeded total; the game's population debug view shows native
  targets at 0; walk into a town → zombies appear (outfits by area); walk away → dots on the map; save, reload →
  same totals, no duplicates; kill some → total drops; True Hits states survive (when True Hits uses ModData);
  optional dedicated server.

## Out of scope

Wandering (4), sound (5), the full arrival bridge with budget, columns and walking in across the edge (6),
consequences (7), multiplayer clients (8), a True Hits hook beyond ModData.
