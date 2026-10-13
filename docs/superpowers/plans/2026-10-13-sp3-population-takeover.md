# Sub-project 3: Population Takeover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (native, per the user: no subagents until the foundations are done). Steps use `- [ ]`.

**Goal:** The mod owns every zombie: native off-screen spawning off, every zombie leaving the loaded area held by the mod, a new game seeded across the whole map (outdoor and, by option, buildings) with vanilla's formulas, vanilla growth and respawn done by the mod, zombies coming back real when their area loads.

**Architecture:** Pure logic in new packages `truehordes.population` (formulas, room count, seeding, cell bookkeeping and respawn, takeover counting) with no game imports, tested offline. `truehordes.store` holds `ZombieRecord`s with chunk and persistent-ID indexes; `truehordes.save.SaveFile` moves to version 2. The game bridge (`truehordes` package) adds five ZombieBuddy patches, a reflection spawner, a map-data snapshot for seeding and a mod-data codec. `Core` runs takeover, seeding (once the world map is ready), growth and respawn.

**Tech Stack:** Java 25, ZombieBuddy 2.3.4 (`@Patch.OnEnter(skipOn = true)`, `@Patch.Argument`), Kahlua (`KahluaTableImpl.save/load`), the repo's test runner.

**Spec:** `docs/superpowers/specs/2026-10-13-sp3-population-takeover-design.md` (section 5 has every vanilla formula with its DLL/Java source).

**Plan style (user's token rule):** exact interfaces, formulas, file lists and test cases here; code written once, test first, during execution.

## Global Constraints

- Build 42.21, `--release 25`, `tools\build.ps1` must pass; no `zombie.*`/`se.krka.*` imports outside the `truehordes` bridge package and `truehordes.LuaApi`.
- Hooks never throw into the game; a hook that fails logs once and leaves the game's own behaviour running.
- Single player and dedicated server only; every hook and core path returns immediately on a multiplayer client.
- Water blocks (D16); records are placed only on walkable tiles from sub-project 2's world map.
- Conservation: every zombie leaving, arriving, dropped or refused is counted; nothing is created or deleted except by seeding, growth, respawn, death and the vanilla spawn-point rule.
- Commit per task on `sp3-population-takeover`, message ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; the final docs task is left uncommitted (user's git workflow). Do not stage the user's uncommitted `design.md` D17 edit unless a task changes `design.md` (then it is left uncommitted with it).

## Review Focus

- Exit to menu or quit **while an area unload is in progress**, or a save running between "taken from the world" and "written to the mod file": the zombie must exist exactly once after reload. Test: `TakeoverTest.testTakenZombieSurvivesSaveBeforeChunkSave` (Task 5) and in-game check 6.
- A held record whose tile is now blocked (a wall built on it, water) must not be lost or spawned into the wall: it stays held. Test: `TakeoverTest.testBlockedTileKeepsRecordHeld` (Task 5).
- A world where the world map is not ready yet when chunks load at game start must still get its zombies once seeding finishes. Test: `CoreTest.testSeedingSpawnsLoadedChunksAfterwards` (Task 7).
- A mod added to an existing save must not seed and must not skip room spawns. Test: `CoreTest.testExistingSaveIsTakenOverGradually` (Task 7).
- Mod data from another mod (True Hits) must survive save and reload byte-for-byte. Test: `SaveFileTest.testModDataRoundTrip` (Task 2).

---

### Task 1: Vanilla formulas

**Files:** create `java/src/truehordes/population/PopulationSettings.java`, `java/src/truehordes/population/Formula.java`, `java/test/truehordes/test/FormulaTest.java`; register the suite.

**Interfaces:**
- `PopulationSettings` (immutable): `multiplier, startMultiplier, peakMultiplier` (float), `peakDay` (int), `respawnHours, respawnUnseenHours, respawnMultiplier` (float), `minPerChunk, maxPerChunk, uniformPerChunk` (float), `uniformDistribution` (boolean), `zombiesSetting` (int, sandbox Zombies 1–5), `buildingZombiesSeeded` (boolean), `edgeArrivalMonths` (float). Constructor with all fields; `static PopulationSettings vanillaDefaults()` (0.65, 1.0, 1.5, 28, 72, 16, 0.1, MinZombiesPerChunk/Max as the game's defaults read in Task 6 — use 0.06 and 12.0 here and confirm in Task 6, 0.2, false, 4, true, 12).
- `Formula.chunkTarget(int intensity, PopulationSettings s, IntSupplier rand100) -> int` (spec 5, per-chunk target; `rand100` returns 0..99).
- `Formula.curve(int t, double day, PopulationSettings s) -> int`.
- `Formula.distribute(int[] targets, int n, IntUnaryOperator randBelow) -> int[]` (cell fill: proportional `trunc(t/T × n)`, at least 1 when `n ≥ T`, remainder to random chunks with a target, up to 900 tries each; `randBelow(k)` returns 0..k-1).
- `Formula.respawnGroup(int curveTarget, int deficit, int periods, PopulationSettings s) -> int` (0 when `deficit < 10` or `respawnHours <= 0`; else `clamp(trunc(curveTarget × respawnMultiplier / 100) × periods / 36, 10, deficit)`; record in the ledger that the native budget is per elapsed period).

- [ ] Tests first: intensity 0 → 0; intensity 255, multiplier 1, min 0, max 12 → 12; a value below 1 (e.g. 0.3) with `rand100` 10 → 1 and with 50 → 0; a value of 1.4 → 1 when `rand100 < 50`, 0 otherwise; uniform mode ignores intensity; `curve(1000, 0)` = 1000 × start; at half the peak day the midpoint; at and after the peak day `1000 × peak`; `distribute` keeps the total exactly `n` (with targets having room), gives each targeted chunk at least 1 when `n ≥ T`, never puts zombies in target-0 chunks; `respawnGroup` cases (deficit 9 → 0, respawn off → 0, small amount → 10, capped by deficit). Run: compile failure.
- [ ] Implement; run: pass. Commit `SP3: vanilla population formulas`.

### Task 2: Zombie records, store indexes, save format 2

**Files:** create `java/src/truehordes/store/ZombieRecord.java`; modify `Store.java`, `Snapshot` users, `save/SaveFile.java`, `save/LoadResult.java`; delete `store/Entity.java`; update `StoreTest`, `SaveFileTest`, `CoreTest` (placeholder uses become records).

**Interfaces:**
- `ZombieRecord` (mutable position, the rest final unless noted): `long id`, `float x, y, z`, `byte dir`, `int outfitId` (0 = dress by area), `int stateFlags`, `int persistentId` (0 = none yet; settable), `int targetX, targetY` (`Integer.MIN_VALUE` = none), `byte[] modData` (null = none), `boolean building` (seeded inside a building). Constants `NO_TARGET = Integer.MIN_VALUE`.
- `Store`: `ZombieRecord add(float x, float y, float z, byte dir, int outfitId, int stateFlags, int persistentId, int targetX, int targetY, byte[] modData, boolean building)` (assigns id); `restore(ZombieRecord)`; `get(long)`; `remove(long)`; `List<ZombieRecord> inChunk(int chunkX, int chunkY)` (copy); `ZombieRecord byPersistentId(int)`; `void moved(ZombieRecord r, float x, float y)` (keeps the chunk index right); `int size()`; `int countInCell(int cellX, int cellY)`; `Collection<ZombieRecord> all()`; `clear()`; existing `snapshot(...)` keeps its contract. `seedTest`/`removeKind` removed.
- `SaveFile`: `VERSION = 2`; record layout: id (long), x, y, z (float), dir (byte), outfitId, stateFlags, persistentId, targetX, targetY (int), building (byte), modData length (int, -1 = none) and bytes. Header gains `seeded` (boolean) and the population bookkeeping blob from Task 4 (length + bytes; empty until Task 7 wires it). Version 1 files load with status OK, zero records and message "version 1 placeholders discarded". `LoadResult` gains `List<ZombieRecord> records`, `boolean seeded`, `byte[] population`.

- [ ] Tests first: `StoreTest` (chunk index after add/move/remove, negative coordinates, persistent-ID index and its update when `persistentId` is set, `countInCell`); `SaveFileTest.testRecordsRoundTrip` (every field), `testModDataRoundTrip` (arbitrary bytes, null), `testVersion1Upgrades` (write a version-1 file with the old layout in the test, read → OK, 0 records, message), keep the existing CRC/backup/locked-file tests passing with records. Run: compile failure.
- [ ] Implement; adapt `Core`'s use of the store (seedTest/clearTest go in Task 7; until then keep `Core` compiling by removing them now and updating `LuaApi`/`Hordes_Core.lua` to drop `Hordes_seedTest`/`Hordes_clearTest`). Run: pass. Commit `SP3: zombie records, store indexes, save format 2`.

### Task 3: Room count port and seeding

**Files:** create `population/RoomCount.java`, `population/MapSource.java`, `population/Seeder.java`, tests `RoomCountTest`, `SeederTest`.

**Interfaces:**
- `RoomCount.count(int zombiesSetting, float lootZombieIntensity, int roomArea, int buildingRoomCount, boolean emptyOutside, IntUnaryOperator randBelow) -> int`: a line-by-line port of `VirtualZombieManager.getZombieCountForRoom` (read the whole method in the decompiled game; keep its constants and its use of `Rand`, mapped to `randBelow`). Returns 0 when `emptyOutside`.
- `MapSource` (interface, implemented by the game bridge in Task 6 and by fakes in tests): `int minChunkX(), minChunkY(), maxChunkX(), maxChunkY()`; `int intensity(int chunkX, int chunkY)` (-1 = no data); `List<RoomInfo> rooms()` where `RoomInfo` is a record `(int level, int area, int buildingRoomCount, float lootZombieIntensity, boolean emptyOutside, int[] rects /* x, y, w, h, ... */)`.
- `TilePicker` (interface): `boolean outdoorWalkable(int x, int y)` (sub-project 2 `World.walkable`).
- `Seeder.seedOutdoor(MapSource, TilePicker, PopulationSettings, double day, Random, Store) -> int` (per 256-cell: chunk targets → curve → distribute → each zombie at a random outdoor walkable tile of its chunk, up to 20 tries, else skipped and counted; standing, outfit 0); `Seeder.seedRooms(MapSource, PopulationSettings, Random, Store) -> int` (per room: `RoomCount.count`, each at a random tile of the room's rects at the room's level, `building = true`); `Seeder.lastSkipped()`.

- [ ] Tests first: `RoomCountTest` with cases computed by hand from the ported source (default Zombies, a dense chunk, a big room, a building over 100 rooms, empty-outside → 0) using a fixed `randBelow`; `SeederTest` with a fake 2×1-cell map (one Voronoi-zero chunk, one blocked chunk) — totals match `curve` per cell, no record in intensity-0 chunks, none on non-walkable tiles, rooms placed inside their rects at their level, deterministic with a seeded `Random`. Run: compile failure.
- [ ] Implement; run: pass. Commit `SP3: room count port and whole-map seeding`.

### Task 4: Cell bookkeeping, growth and respawn

**Files:** create `population/CellBook.java`, `population/Respawner.java`, test `CellBookTest`, `RespawnerTest`.

**Interfaces:**
- `CellBook`: per 256-cell `int baseTarget` (sum of chunk targets T at seeding), `double lastRespawnHours`, `boolean everLoaded`; per chunk `double lastSeenHours`, `double lastRefillHours`, `int chunkTarget`. `void init(MapSource, PopulationSettings, IntSupplier rand100)`; `void seen(int chunkX, int chunkY, double hours)` (marks cell `everLoaded`); `byte[] save()` / `static CellBook load(byte[])`.
- `PopulationCounter` (interface): `int count(int cellX, int cellY)` (held + real in that cell).
- `Respawner.update(double hours, CellBook, PopulationCounter, PopulationSettings, Random, EdgeChunks, TilePicker, Store, Predicate<chunkKey> nearPlayer) -> int added`:
  - growth: never-loaded cells below `curve(T, day)` get the difference (placed like seeding);
  - respawn (spec 5): visited cells with no player near, deficit ≥ 10, `RespawnHours` elapsed → one group of `Formula.respawnGroup` zombies; placement: with probability `edgeShare = min(1, monthsSinceStart / edgeArrivalMonths)` at a random `EdgeChunks` chunk, else at a random eligible chunk of that cell (unseen for `RespawnUnseenHours`, below its target, not refilled for `RespawnHours`); the group stands together (wandering in sub-project 4 moves it).
- `EdgeChunks` (interface): `int count()`, `long chunk(int i)` — walkable chunks within 2 chunks of a no-data chunk (built from sub-project 2's grid in Task 6).

- [ ] Tests first: growth tops up a never-loaded cell to the curve and leaves a loaded one alone; respawn off → nothing; deficit 9 → nothing; timers (`RespawnHours`, `RespawnUnseenHours`) respected; edge share 0 at start, 1 after `edgeArrivalMonths`, group placed in edge chunks then; save/load round trip. Run: compile failure.
- [ ] Implement; run: pass. Commit `SP3: cell bookkeeping, growth and respawn`.

### Task 5: Takeover logic (hand-over counting, arrivals, duplicates)

**Files:** create `population/Takeover.java`, `population/ZombieSnapshot.java`, test `TakeoverTest`.

**Interfaces:**
- `ZombieSnapshot` record: the `ZombieRecord` fields without `id`/`building` (what the bridge reads from a real zombie).
- `Takeover(Store)`: `void taken(ZombieSnapshot)` (adds a record, counts `out`); `List<ZombieRecord> arrivals(int chunkX, int chunkY, IntPredicate liveIds, BiPredicate<Integer,Integer> floorAt)` (drops records whose `persistentId` is live, counts `duplicates`; returns records with a floor tile and removes them from the store, counts `in`; leaves the rest held, counts `keptNoFloor`); `void refused(ZombieRecord)` (spawn-point rule; counts `refused`); `void failed(ZombieRecord)` (spawn failed for another reason: re-adds it, counts `retried`); `String counts()`.
- Ordering for crash safety: a taken zombie is in the store before the game's chunk save; the store is written by the existing full/hot saves. On load, duplicates are resolved by `arrivals` (live ID wins).

- [ ] Tests first: out/in round trip keeps every field; live ID → dropped and counted; no floor → kept; refused → gone and counted; failed → back in the store; `testTakenZombieSurvivesSaveBeforeChunkSave` (take → save store → reload store → arrival with the same ID live → exactly one); `testBlockedTileKeepsRecordHeld`. Run: compile failure.
- [ ] Implement; run: pass. Commit `SP3: takeover logic`.

### Task 6: Game bridge

**Files:** create `truehordes/GameMapSource.java` (MapSource over `IsoMetaGrid`: `LotHeader.getZombieIntensityForChunk(metaCell.info, wx, wy)`, rooms from `BuildingDef.rooms` with `RoomDef.level`, `getArea()`, `isEmptyOutside()`, `rects`, building room count, `IsoMetaChunk.getLootZombieIntensity()`), `truehordes/Spawner.java` (reflection: `ZombiePopulationManager.addZombieStanding/addZombieMoving`, `onConfigReloaded` re-send through private `n_configFloat`), `truehordes/ModDataCodec.java` (`byte[] save(IsoZombie)` via `KahluaTableImpl.save(ByteBuffer)` when `hasModData()`, `void load(IsoZombie, byte[])`), `truehordes/Snapshots.java` (IsoZombie → ZombieSnapshot with `ZombieStateFlags.intFromZombie`, `getPersistentOutfitID`, path target, mod data); modify `Patches.java` (five patches), `Hooks.java`, `HookTargets.java` (new required targets: `ZombiePopulationManager.onConfigReloaded`, `removeChunkFromWorld`, `virtualizeZombie`, `addChunkToWorld`, `VirtualZombieManager.roomSpotted`; reflected private methods `addZombieStanding`, `addZombieMoving`, `n_configFloat`), `GameEnvImpl.java` (settings from `SandboxOptions.instance.zombieConfig`, `zombies`, `distribution`, the game's min/max per chunk fields, the two new options; `newWorld()` = no mod state file and no files in `<save>/zpop`), `core/GameEnv.java`, `mod/.../sandbox-options.txt` and `Sandbox.json` (`TrueHordes.BuildingZombiesSeeded` default true, `TrueHordes.EdgeArrivalMonths` default 12, range 0–120), `VerifyTargetsTest`.

**Patches (all wrapped, all skip on MP clients):**
- `onConfigReloaded` exit → `Hooks.onConfigReloaded()` → re-send PopulationMultiplier, PopulationStartMultiplier, PopulationPeakMultiplier, RespawnMultiplier, RespawnHours as 0.
- `removeChunkFromWorld(IsoChunk)` enter → `Hooks.onChunkUnloading(chunk)`: every live, non-reanimated-player `IsoZombie` on the chunk's squares (all levels) → `Takeover.taken(snapshot)`, then `removeFromWorld()` + `removeFromSquare()`.
- `virtualizeZombie(IsoZombie)` enter, `skipOn = true` → `Hooks.onZombieWalkedOff(z)` returns true (skip) after taking it; returns false (game runs) if the core is not running.
- `addChunkToWorld(IsoChunk)` exit → `Hooks.onChunkLoaded(chunk)`: `Takeover.arrivals(...)` with live IDs from the game's registry (`ZombiePopulationManager` `realZombies.isLive` via reflection, plus zombies already on the chunk) and a floor check (`square != null && TreatAsSolidFloor()`), then `Spawner.spawn(record)`; after creation find the zombie by `persistentId` and load its mod data; a record the spawn-point rule deleted → `refused`.
- `VirtualZombieManager.roomSpotted(IsoRoom)` enter, `skipOn = true` → skip only when the core is running a mod-seeded world with `buildingZombiesSeeded` on.

- [ ] Tests first: `VerifyTargetsTest` covers every new target and reflected method in the game jar (method names; field `realZombies`); `GameEnv` fakes compile. Run: failure on the missing targets list. Implement; run: pass.
- [ ] Commit `SP3: game bridge - native off, hand-over hooks, spawner, map source, options`.

### Task 7: Core wiring, Lua, status

**Files:** modify `core/Core.java`, `LuaApi.java`, `Hordes_Core.lua`, `Hordes_DebugMap.lua` (draws records; unchanged snapshot contract), `CoreTest`.

**Behaviour:**
- `start()`: load the mod file; if `seeded` flag or records exist → normal; else if `env.newWorld()` → `pendingSeed = true`; else gradual takeover (log "existing save: taking over gradually").
- `tick()`: when `pendingSeed` and the world map is READY → `Seeder.seedOutdoor` (+ `seedRooms` if the option) and `CellBook.init`, `seeded = true`, then `env.spawnLoadedChunks()` (the bridge runs `onChunkLoaded` for every currently loaded chunk); every in-game hour: `CellBook.seen` for chunks near players, `Respawner.update`.
- `save()`: records, `seeded`, `CellBook.save()`.
- `status()`: held, real (game's zombie count), target total, hand-over counts, seeded/gradual, next respawn check.

- [ ] Tests first (`CoreTest` with fakes): `testNewWorldSeedsOnceWorldMapReady`, `testSeedingSpawnsLoadedChunksAfterwards`, `testExistingSaveIsTakenOverGradually` (no seed; room skip off), `testSeededFlagSurvivesReload` (no re-seed), `testStatusShowsCounts`. Run: compile failure.
- [ ] Implement; run: pass. Commit `SP3: core wiring, status and debug map`.

### Task 8: Checklist, design status, deploy, review (docs left uncommitted)

- [ ] `docs/TESTING.md`: sub-project 3 rows (spec section 4 in-game list, plus "the game's population debug view shows no native population", "a room shows its zombies before you open it, and no second set appears", "a True Hits zombie keeps its state off-screen" when True Hits uses ModData).
- [ ] `design.md`: sub-project 3 row and the facts learned (formulas, hooks, room spawn path).
- [ ] `tools\deploy.ps1`; whole-branch self-review (no subagents); rulings and deferred minors reported.
