# Sub-project 4: Simulation core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every zombie the mod holds off screen wanders; hordes form, follow, merge, shed members and dissolve by their own sight, hearing and wandering, on a background thread, for any population size.

**Architecture:** Individuals plus horde units (spec section 3). Wanderers stay `ZombieRecord`s in the `Store` with new walk fields; a `Horde` holds its members' records whole and moves as one point. A pure `truehordes.sim` package (offline-tested) does movement, sight, moaning/sound, encounters and levels of detail over a `MapView` and a `SpatialGrid`; a `SimThread` runs it in short slices under the core's lock. Bridge code adds the rally-group patch, the horde hand-over layout, Lua commands and the map drawing.

**Tech Stack:** Java 25 (game's Zulu 25 JRE, compiled with the JDK 25 at `%USERPROFILE%\.jdks\jdk-25.0.4.1+1`), ZombieBuddy patches, Kahlua Lua; the repo's own test runner (`java/test/truehordes/test/TestRunner.java`, tests are `public static void testX()` methods using `Check`).

**Spec:** `docs/superpowers/specs/2026-10-14-sp4-simulation-core-design.md` (sections 1–12; this plan maps each section to tasks in the coverage table at the end).

## Global Constraints

- Work only in `Desktop\Zomboid Mods Personal\TrueHordes`; never edit under Program Files; never copy other mods' code.
- Build/test: `powershell -File tools\build.ps1` (compiles, runs all tests, fails on any failure). Claude runs `tools\deploy.ps1` after mod code changes.
- No director: nothing may choose a destination for a walker except its own heading roll, sight, moaning or a `SoundEvent` (D5, D19).
- Horde forms at `HordeFormSize` (10) zombies **moving together**; dissolves at fewer than `HordeMinSize` (5), i.e. at 4 or fewer.
- Hordes merge **only by touching** (spread circles overlap); sight between hordes does nothing.
- Moaning is **always the lowest-priority sound**; any other `SoundEvent` heard makes a walker ignore and forget moaning; members never hear their own horde; a horde ignores moaning quieter (at its position) than its own.
- Terrain preference, best first: 1 roads (incl. highways), 2 trails, 3 farmland, 4 open ground and forest (equal). Water never entered (D16). Walkers never leave the map.
- Stragglers: 1 in `StragglerOneIn` (75), never join or follow.
- Break-aways only in tight spots; no splitting at forks.
- Vanilla rally groups off while the mod owns the population; sandbox values untouched.
- Members of a horde keep their full `ZombieRecord` (outfit, persistentId, stateFlags, modData) at all times.
- The simulation never touches game objects; the main thread does only hand-over, snapshots, the rally patch.
- Console commands use the `Hordes_` prefix.
- Population size is whatever the sandbox makes; must work up to 1M+ held zombies.

## Review Focus

1. **Lock hold time with 1M zombies:** a sim slice holding the core lock must not stall the main thread's hand-over; expect slices ≤ 2 ms. Test: `SimulationTest.testSliceRespectsBudget` (Task 7).
2. **Hordes reaching the loaded area between hand-overs:** a horde whose centre enters a loaded chunk must be laid out and spawned, never left invisible inside the loaded area. Test: `HandOverTest.testHordeEnteringLoadedChunkIsLaidOut` (Task 10).
3. **Old saves:** a format-2 save (sub-project 3) must load with every zombie as a wanderer and stragglers rolled, no loss. Test: `SaveFileTest.testV2UpgradesToWanderers` (Task 11).
4. **Map holes:** unknown/void chunks inside the map and the map's ragged edge must never trap a walker (no infinite turning in place). Test: `MovementTest.testBoxedInWalkerPausesInsteadOfSpinning` (Task 5).
5. **Death while real:** a horde member killed while real must leave the horde and count as a death in conservation, never be resurrected by the rejoin rule. Test: `HandOverTest.testKilledMemberNeverRejoins` (Task 10).

---

## File structure

New, pure (`java/src/truehordes/sim/`, offline-tested):
- `SimSettings.java` — all tunables (sandbox + constants) in one immutable record with a builder.
- `Vision.java` — vanilla vision radius and cone chance.
- `MapView.java` — interface the sim reads the map through; `WorldMapView.java` adapts `World`.
- `Movement.java` — heading fan scoring, avoidance, edges, speed, pauses.
- `Horde.java`, `Hordes.java` — horde record and the horde registry (ids, lookup).
- `SpatialGrid.java` — per-chunk buckets of walkers (wanderer ids and horde ids).
- `SoundEvent.java`, `Hearing.java` — sound events, falloff, masking, priority (moaning lowest).
- `Encounters.java` — joining, following chains, merging, break-aways, dissolving, convergence.
- `LevelOfDetail.java` — update interval by distance to players.
- `Simulation.java` — owns all of the above; `step(nowHours, budgetNanos)`.
- `Conservation.java` — the balance check.
- `SimThread.java` — background thread (in `truehordes.core`, it uses the core lock).
- `HandOver.java` — horde member layout on load, rejoin on unload (`truehordes.sim`, pure; uses a floor check).

Modified: `store/ZombieRecord.java` (walk fields), `store/Store.java` (snapshot includes kinds), `save/SaveFile.java`, `save/LoadResult.java` (format 3), `core/Core.java`, `core/PopulationRunner.java`, `core/GameEnv.java`, `GameEnvImpl.java`, `PopulationBridge.java`, `Patches.java`, `HookTargets.java`, `Hooks.java`, `LuaApi.java`, Lua `Hordes_Core.lua`, `Hordes_DebugMap.lua`, `media/sandbox-options.txt`, translations, `docs/TESTING.md`, `design.md`.

New tests (`java/test/truehordes/test/`): `VisionTest`, `MovementTest`, `SpatialGridTest`, `HearingTest`, `EncountersTest`, `SimulationTest`, `HandOverTest`, `SimSpeedTest`; additions to `SaveFileTest`, `CoreTest`, `VerifyTargetsTest`, `StoreTest`. Register every new class in `TestRunner`.

## Rulings made while planning (from reading the game, 42.21)

- **Sight** (`IsoZombie.updateVisionRadius` / `getVisionRadiusAdjusted`, spotting at ~line 1896): radius = `clamp((20 − max(dark·5, rain·2.5 + fog·7)) × sightMul, 10, 20)` with `sightMul` 1.75 Eagle, 1 Normal, 0.35 Poor (Random settings pick per zombie as the game does: setting 4 → 1..3, 5 → 2..3). Cone: `cos` of the angle between the zombie's heading and the target: `< −0.4` → 0; `< −0.2` → 1/8; `< 0` → 1/4; `< 0.2` → 1/2; `≤ 0.4` → 2; `≤ 0.6` → 8; `≤ 0.8` → 16; else 32. Off screen the per-check spot chance is `weight / 32`.
- **Speed:** the game's zombie speed is animation-driven (no tile constant). Ruling: off-screen base speeds in tiles per real second: shambler 0.45, fast shambler 0.9, sprinter 2.2 (Random: per-zombie mix as the game's setting), times a walking duty cycle 0.5 (zombies idle about half the time), converted with the game's real seconds per in-game minute (`GameEnv.realSecondsPerGameMinute()`). Constants in `SimSettings`, verified in game (TESTING row 4). Cost if wrong: hordes too fast/slow; one constant.
- **Rally groups:** patch `zombie.ai.ZombieGroupManager.update(IsoZombie)` with `@Patch.OnEnter(skipOn = true)` returning `Hooks.rallyOff()`; groups are only created inside `update`, so no group ever forms.
- **Terrain mapping:** `ROAD` rank 1, `TRAIL` 2, `FARM` 3, `OPEN`/`FOREST`/`DEEP_FOREST`/`TOWN`/`UNKNOWN` 4; `BUILDING`/`WATER` not enterable. `TOWN` (yards and lots between town streets) ranks with open ground so in town walkers prefer streets.
- **Defaults:** `FollowChance` 0.01 per in-game hour of seeing a moving zombie; `OffRoadChance` 0.10 per heading roll while on road; `BreakAwayChance` 0.005 per member per tight-spot chunk. Tuned in testing / sub-project 9.
- **Moaning loudness:** `L(n) = 40 + 10·log10(n)` dB at the horde, heard where `L − 20·log10(max(d,1)) ≥ 30` (d in tiles). 10 zombies → 50 dB → heard to 10 tiles (+spread); 1,000 → 70 dB → 100 tiles; 10,000 → 80 dB → ~316 tiles. Same `Hearing` used by test sounds. Sub-project 5 retunes the physics.

---

### Task 1: Settings, sandbox options and walk fields

**Files:**
- Create: `java/src/truehordes/sim/SimSettings.java`
- Modify: `java/src/truehordes/store/ZombieRecord.java`, `java/src/truehordes/core/GameEnv.java`, `java/src/truehordes/GameEnvImpl.java`, `java/test/truehordes/test/FakeEnv.java`, `mod/.../media/sandbox-options.txt`, `mod/.../lua/shared/Translate/EN/Sandbox_EN.txt` (the existing translation file)
- Test: `java/test/truehordes/test/StoreTest.java` (add), `java/test/truehordes/test/CoreTest.java` (add)

**Interfaces:**
- Produces: `record SimSettings(int stragglerOneIn, int hordeFormSize, int hordeMinSize, double followChancePerHour, double offRoadChance, double breakAwayChance, int sightSetting, int speedSetting, double realSecondsPerGameMinute)` with `static SimSettings defaults()` (75, 10, 5, 0.01, 0.10, 0.005, 2, 3, 2.5) and `Builder`; constants `DUTY = 0.5`, `SHAMBLER = 0.45`, `FAST = 0.9`, `SPRINTER = 2.2` (tiles/real s).
- Produces: `GameEnv.simSettings()` (from sandbox `TrueHordes.*`, `lore.sight`, `lore.speed`, `GameTime` real seconds per minute).
- Produces on `ZombieRecord` (mutable, sim-owned, guarded by the core lock): `float heading` (radians), `float pauseMinutes`, `boolean straggler`, `boolean strRolled`, `long followId` (0 none; >0 record id, <0 −hordeId), `long hordeId` (last horde, 0 none), `double lingerUntilHours`, `boolean idle` (building/sitting zombies: true until a sound moves them; initialised from `building` or the sitting state flag).

- [ ] **Step 1: Write failing tests**
  - `StoreTest.testWalkFieldsDefault`: a new record has `followId == 0`, `hordeId == 0`, `!strRolled`, `idle == building`.
  - `CoreTest.testSimSettingsReachTheCore`: `FakeEnv` with `stragglerOneIn = 40` → `core.simSettings().stragglerOneIn() == 40` after start.
  - `SimSettings` builder rejects `hordeMinSize > hordeFormSize` and `stragglerOneIn < 1` (`Check.throwsAny`).
- [ ] **Step 2: Run `powershell -File tools\build.ps1`** — Expected: compile FAIL (`SimSettings` missing).
- [ ] **Step 3: Implement** `SimSettings`, the fields (constructor leaves defaults; sitting flag = existing `stateFlags` sitting bit), `GameEnv.simSettings()` in `GameEnvImpl` and `FakeEnv`, `Core.simSettings()`. Sandbox options (page `TrueHordes`): `StragglerOneIn` integer 1–100000 default 75; `HordeFormSize` 2–1000 default 10; `HordeMinSize` 2–1000 default 5; `FollowChance` double 0–1 default 0.01; `OffRoadChance` 0–1 default 0.10; `BreakAwayChance` 0–1 default 0.005; with EN translations and tooltips in plain words.
- [ ] **Step 4: Run build** — Expected: all tests pass.
- [ ] **Step 5: Commit** `SP4: sim settings, sandbox options, walk fields`.

### Task 2: Vision (vanilla sight)

**Files:** Create `java/src/truehordes/sim/Vision.java`; Test `java/test/truehordes/test/VisionTest.java` (register in `TestRunner`).

**Interfaces:**
- Produces: `static float radius(int sight, float dark01, float rain01, float fog01)`; `static float coneWeight(float cosAngle)` (0…32, bands above); `static boolean sees(float fromX, float fromY, float heading, float toX, float toY, float toRadius, float radius, DoubleSupplier rng)` (distance to the target circle's nearest point ≤ radius, then `rng < coneWeight/32`); `static int perZombieSight(int setting, IntUnaryOperator rand)`.

- [ ] **Step 1: Failing tests:** Normal clear day → 20; Normal full dark → 15; Poor clear → 10 (clamped); Eagle full fog → `clamp((20−7)·1.75)=20`; cone weights at cos 1, 0.7, 0.5, 0.3, 0.1, −0.1, −0.3, −0.5 → 32, 16, 8, 2, 0.5, 0.25, 0.125, 0; `sees` false at 21 tiles straight ahead; true at 5 tiles ahead with rng 0.99; false directly behind with rng 0; a horde circle of spread 6 centred 24 tiles ahead is seen (edge at 18).
- [ ] **Step 2: Run build** — Expected: FAIL (no `Vision`).
- [ ] **Step 3: Implement** `Vision`.
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: vanilla sight rule`.

### Task 3: MapView and movement

**Files:** Create `sim/MapView.java`, `sim/WorldMapView.java`, `sim/Movement.java`; Test `MovementTest.java`.

**Interfaces:**
- Produces: `interface MapView { int rank(int chunkX, int chunkY); /* 1..4, or 0 = cannot enter */ boolean inMap(int chunkX, int chunkY); boolean tight(int chunkX, int chunkY); /* building chunk next to a passable gap */ boolean nearEdge(int chunkX, int chunkY, int margin); }` — `WorldMapView(World)` maps terrain per the ruling; `tight` = passable chunk with ≥ 2 of its 4 neighbours `BUILDING`; `inMap` false for void/outside the grid.
- Produces: `final class Movement { Movement(MapView map, SimSettings s); float chooseHeading(float x, float y, float heading, float pullX, float pullY, float pullWeight, DoubleSupplier rng); /* NaN = boxed in */ float speedTilesPerGameMinute(int members, int speedSetting); Step advance(float x, float y, float heading, float minutes, int members, float pullX, float pullY, float pullWeight, DoubleSupplier rng) }` with `record Step(float x, float y, float heading, int tightChunks, boolean boxedIn)`; fan = 0, ±45°, ±90° (and 180° at weight ×0.05); look-ahead 3 chunks; score = momentum (cos to current, weight 2) + terrain (rank 1→4 = 4,3,2,1) + pull (cos to pull × pullWeight) ; candidates whose next chunk is rank 0, outside the map or within 2 chunks of the edge heading outward are dropped; while on a road, a non-road candidate is kept only if `rng < offRoadChance`; pick by weighted random over positive scores; crowding: `speed × (1 − 0.5·log10(members)/4)` clamped ≥ 0.5×, members ≤ 10 = full speed; pause: when `pauseMinutes` is 0, chance 1/120 per in-game minute to start a pause of 2–10 minutes.

- [ ] **Step 1: Failing tests** (fake `MapView` from a char grid):
  - `testPrefersRoadOverForest` (10,000 rolls at a T junction: road headings ≥ 80%).
  - `testRankOrder` road > trail > farm > open = forest by pick frequency.
  - `testNeverEntersWater` (water ahead on all but one side → always that side).
  - `testTurnsBackAtMapEdgeDeadEnd` (road leading off the map: walker ends heading inward within 3 steps; never `inMap == false`).
  - `testOffRoadChanceRespected` (with 0 never leaves the road; with 1 leaves at the same rate as terrain alone).
  - `testCrowdingSlowsBigHordes` (10 → 1.0×, 10,000 → 0.5×).
  - `testBoxedInWalkerPausesInsteadOfSpinning` (Review Focus 4: enclosed by water/void → `boxedIn`, position unchanged, no exception, heading kept).
  - `testTightChunksCounted` (path through an alley between building chunks reports `tightChunks > 0`; open field 0).
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement** `MapView`, `WorldMapView`, `Movement`.
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: movement over the world map`.

### Task 4: Horde records and the spatial grid

**Files:** Create `sim/Horde.java`, `sim/Hordes.java`, `sim/SpatialGrid.java`; Test `SpatialGridTest.java` (incl. horde tests).

**Interfaces:**
- Produces: `final class Horde { final long id; float x, y, heading, pauseMinutes; final List<ZombieRecord> members; long followHordeId; float spread() /* 2 + sqrt(members)·0.5 tiles */; int size(); float loudnessDb() /* 40 + 10·log10(n) */ }`.
- Produces: `final class Hordes { Horde create(float x, float y, float heading, List<ZombieRecord> members); Horde get(long id); void remove(long id); Collection<Horde> all(); int memberCount(); long nextId(); void setNextId(long) }` — creating sets each member's `hordeId`.
- Produces: `final class SpatialGrid { void putWanderer(ZombieRecord r); void moveWanderer(ZombieRecord r, float oldX, float oldY); void removeWanderer(ZombieRecord r); void putHorde(Horde h); void moveHorde(Horde h, float oldX, float oldY); void removeHorde(Horde h); void near(float x, float y, float radius, Consumer<ZombieRecord> wanderers, Consumer<Horde> hordes) }` — buckets per 8×8 chunk; hordes are registered in every chunk their spread circle covers.

- [ ] **Step 1: Failing tests:** spread grows with size (10 → ~3.6, 10,000 → 52); loudness 10 → 50 dB, 1,000 → 70 dB; grid `near` returns a wanderer 15 tiles away for radius 20 and not one 25 away; a horde of spread 30 is found from a point 25 tiles outside its centre's chunk; `moveWanderer` across chunks keeps one entry; removing a horde leaves no bucket entries; creating a horde sets every member's `hordeId`.
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: horde records and spatial grid`.

### Task 5: Hearing, sound events and moaning

**Files:** Create `sim/SoundEvent.java`, `sim/Hearing.java`; Test `HearingTest.java`.

**Interfaces:**
- Produces: `record SoundEvent(long id, float x, float y, float loudnessDb, double startHours, double endHours, boolean moaning, long sourceHordeId)`.
- Produces: `final class Hearing { static float levelAt(float sourceDb, float distance); static boolean audible(float sourceDb, float distance) /* levelAt ≥ 30 */; static float range(float sourceDb); static Pull choose(float x, float y, long ownHordeId, float ownDb, List<SoundEvent> events, List<Horde> nearbyHordes) }` with `record Pull(float x, float y, float weight, SoundEvent event /* null = moaning */)`; rules: any audible non-moaning event wins over every moaning source (loudest level at the listener wins among events); otherwise the loudest audible horde moaning whose level at the listener exceeds `ownDb` (a horde's own level at itself; 0 for wanderers) and whose id ≠ `ownHordeId`; else `null`.

- [ ] **Step 1: Failing tests:** `range(50) ≈ 10`, `range(70) ≈ 100`; wanderer 50 tiles from a 1,000-horde gets a pull towards it; a member never hears its own horde; **a 10,000-horde 80 tiles away does not pull back a walker that hears a test sound** (lowest priority); small horde (20) 60 tiles from a big horde (2,000) is pulled; the big horde is not pulled by the small one (masking); no events and no hordes → `null`.
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: hearing, sound events, moaning priority and masking`.

### Task 6: Encounters

**Files:** Create `sim/Encounters.java`; Test `EncountersTest.java`.

**Interfaces:**
- Consumes: `Vision`, `Hordes`, `SpatialGrid`, `Hearing`, `SimSettings`, `Store`.
- Produces: `final class Encounters { Encounters(Store store, Hordes hordes, SpatialGrid grid, SimSettings s, Random rng); void wanderer(ZombieRecord r, double nowHours, double dtHours); void horde(Horde h, double nowHours, int tightChunks); void soundEnded(SoundEvent e); int formed(), joined(), merged(), brokeAway(), dissolved() }`.
  - `wanderer`: rolls straggler once (`strRolled`); stragglers and `idle` records do nothing here; looks for hordes in vision radius → join (`sees`) → record removed from `Store`, added to `members`, grid updated; else for moving wanderers seen → with `followChancePerHour × dt` set `followId`; if the follow chain this record belongs to (walk `followId` links, cycle-safe, max depth 1000) has ≥ `hordeFormSize` moving non-straggler members within 16 tiles of the head → form a horde at the head with the head's heading.
  - `horde`: merge with any horde whose spread circle overlaps (bigger keeps id; heading = size-weighted circular mean; spread recomputed); break-aways: for each `tightChunks`, each member leaves with `breakAwayChance` → back to `Store` at the horde centre ± spread, `lingerUntilHours = now + 1..6`, `hordeId` kept; dissolve if `size < hordeMinSize`.
  - `soundEnded`: walkers that responded to `e` (tracked by event id) and are within 8 tiles of the source and number ≥ `hordeFormSize` (non-stragglers) become one horde.

- [ ] **Step 1: Failing tests:**
  - `testStandingCrowdNeverForms` (250 idle wanderers in one chunk, 30 days → 0 hordes).
  - `testFollowChainFormsAtTen` (9 → no horde; 10th joins → horde of 10).
  - `testWandererJoinsSeenHorde` and `testWandererBehindHordeDoesNotSee` (cone).
  - `testStragglerNeverJoinsOrFollows` and `testStragglerRateAboutOneIn75` (100,000 rolls within 1/60..1/95).
  - `testHordesMergeOnlyByTouch` (spreads 1 tile apart → no merge even though in sight; overlapping → merge; bigger id kept; heading of 2,000 vs 50 changes < 5°).
  - `testBreakAwayOnlyInTightSpots` (open road 0 over 1,000 steps; alley > 0).
  - `testDissolveAtFour` (5 stays; 4 dissolves into 4 wanderers in the Store).
  - `testSoundConvergenceFormsHorde` (12 responders at the source when the sound ends → one horde of 12; 8 → none).
  - Conservation inside each test: `store.size() + hordes.memberCount()` constant.
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: joining, following, merging, break-aways, dissolving`.

### Task 7: Levels of detail and the Simulation

**Files:** Create `sim/LevelOfDetail.java`, `sim/Simulation.java`, `sim/Conservation.java`; Test `SimulationTest.java`.

**Interfaces:**
- Consumes: Tasks 2–6; `population.PlayerNearby` (existing `nearCell`, `nearChunk`).
- Produces: `LevelOfDetail.intervalMinutes(boolean nearChunk, boolean nearCell)` → 0.25 (near the loaded edge: neighbouring chunk loaded), 1 (within the near-cell ring), 20 (far) in-game minutes.
- Produces: `final class Simulation { Simulation(Store store, MapView map, SimSettings s, long seed); Hordes hordes(); void setPlayers(PlayerNearby p); void addSound(SoundEvent e); int step(double nowHours, long budgetNanos) /* returns walkers updated */; Conservation conservation(); String stats() }` — keeps a due-time priority queue of walkers (wanderers and hordes) by next update time; `step` pops due walkers until the budget is spent: moves them (`Movement`), updates the grid, runs `Encounters`; idle wanderers are only scheduled when a sound reaches them; lingering wanderers do not move until `lingerUntilHours`; wanderers with `followId` steer towards their target; follower hordes (pulled by moaning) steer towards the pull; walkers inside loaded chunks are skipped (sub-project 6 owns real zombies); `stats()` = updated/s, queue length, last step nanos.
- Produces: `final class Conservation { void addedToPopulation(int n); void died(int n); void becameReal(int n); void becameHeld(int n); String check(int held, int real) /* "" when balanced */ }`.

- [ ] **Step 1: Failing tests:**
  - `testWanderersSpreadOverDays` (1,000 outdoor wanderers in one cell, 7 in-game days → bounding box at least 3× larger; none in water; none outside the map).
  - `testFarWalkersUpdateLessOften` (counts per in-game hour: far ≈ 3, near-edge ≈ 240).
  - `testSliceRespectsBudget` (Review Focus 1: 1M wanderers, `step` with 2 ms budget returns within 4 ms).
  - `testFollowerHordeTrailsUntilLeaderPauses` (20-horde pulled by a 2,000-horde 60 tiles ahead on a road: gap does not close while the leader walks; closes and merges within the leader's pause).
  - `testNoDirector` (no sounds, no pulls: the mean heading over 1,000 walkers after 30 days has no preferred direction beyond terrain: |mean resultant| < 0.1 on a uniform open map).
  - `testConservationOverThirtyDays` (`check` returns "").
  - `testRealChunksSkipped` (walker inside a loaded chunk is not moved).
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: simulation, levels of detail, conservation`.

### Task 8: Speed test (1M zombies)

**Files:** Create `java/test/truehordes/test/SimSpeedTest.java`.

- [ ] **Step 1: Write test** `testOneMillionHeldZombiesStayWithinBudget`: build a 300×300-cell fake open/road map, 1M wanderers (90% outdoor, 10% idle), 50 hordes of 1,000; simulate 24 in-game hours at 2 ms slices with a 10 ms sleep model; assert total CPU per in-game hour (at 1-hour days: 150 real s per in-game hour) < 15 s, and no slice > 4 ms. Print the numbers.
- [ ] **Step 2: Run build** — Expected: PASS (if FAIL, profile `Simulation.step` and fix before continuing; ruling recorded in the ledger).
- [ ] **Step 3: Commit** `SP4: 1M zombie speed test`.

### Task 9: Background thread and core integration

**Files:** Create `java/src/truehordes/core/SimThread.java`; Modify `core/Core.java`, `core/PopulationRunner.java`; Test `CoreTest.java` (add).

**Interfaces:**
- Produces: `final class SimThread { SimThread(Object lock, Simulation sim, DoubleSupplier nowHours, long sliceNanos /* 2 ms */, long sleepMillis /* 10 */); void start(); void stop() /* joins */; boolean running(); }` — daemon thread named `TrueHordes-sim`; loop: `synchronized (lock) { sim.step(now, slice) }`, then sleep; exceptions logged once and the thread keeps going.
- Modify `Core`: create `Simulation` when the population is ready (seeded or gradual), start `SimThread` on `start()`, stop it in `stop()`; `Core.simulation()`; status adds `hordes N (biggest M)`, `wanderers W`; `stats()` adds `Simulation.stats()` and the conservation line.
- Modify `PopulationRunner`: newly added records (seeding merge, growth, respawn, arrivals of real zombies) are registered with the `Simulation` (grid + schedule) and counted in `Conservation.addedToPopulation`.

- [ ] **Step 1: Failing tests:** `CoreTest.testSimThreadStartsAndStops` (start → `running()`; stop → thread gone within 1 s); `CoreTest.testSimThreadSurvivesAnException` (a `MapView` that throws once → thread still running, error logged once); `CoreTest.testStatusShowsHordes`.
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: simulation thread in the core`.

### Task 10: Hand-over with the loaded area

**Files:** Create `java/src/truehordes/sim/HandOver.java`; Modify `population/Takeover.java`, `PopulationBridge.java`; Test `HandOverTest.java`.

**Interfaces:**
- Produces: `final class HandOver { HandOver(Hordes hordes, SpatialGrid grid, Store store); List<ZombieRecord> layOut(Horde h, Takeover.FloorCheck floors) /* column behind the centre along the heading: rows of ceil(sqrt(n)/2) wide, 1.2 tiles apart, each member placed on the nearest floor tile within 4 tiles, else at the centre; members moved into the Store; horde removed */; void hordesTouching(long[] loadedChunks, Consumer<Horde> out); boolean rejoin(ZombieRecord taken) /* if taken.hordeId's horde exists and is within 2× its spread + 32 tiles and the record is not dead: add back as member, true */ }`.
- Modify `Takeover.taken`: after recording a real zombie as held, call `rejoin`; else it is a wanderer.
- Modify `PopulationBridge.spawnLoadedChunks` / chunk-loaded path: before arrivals, lay out every horde whose spread circle touches a loaded chunk, then run the normal sub-project 3 arrivals for those chunks.
- Deaths: the existing sub-project 3 death path removes records; it now also calls `Conservation.died`.

- [ ] **Step 1: Failing tests:**
  - `testLayoutIsColumnBehindHeading` (heading east, 100 members → all x ≤ centre x + 1, spread mostly along x).
  - `testLayoutKeepsEveryRecordWhole` (outfit, persistentId, modData unchanged; 100 in, 100 out).
  - `testHordeEnteringLoadedChunkIsLaidOut` (Review Focus 2).
  - `testRealMemberRejoinsNearbyHorde` and `testFarHordeMeansWanderer`.
  - `testKilledMemberNeverRejoins` (Review Focus 5: death removes it; conservation counts one death; no rejoin).
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: hordes hand over at the loaded edge`.

### Task 11: Save format 3 and saving off the main thread

**Files:** Modify `save/SaveFile.java`, `save/LoadResult.java`, `core/Core.java`; Create `core/SaveWriter.java`; Test `SaveFileTest.java` (add), `CoreTest.java` (add).

**Interfaces:**
- Produces: `SaveFile.VERSION = 3`; `static byte[] encode(Store store, Hordes hordes, double worldAgeHours, boolean seeded, byte[] population)` (under the lock, in memory) and `static void writeBytes(Path file, byte[] data)` (atomic: temp + move, backup kept as today); records add heading, pauseMinutes, straggler, strRolled, followId, hordeId, lingerUntilHours, idle; hordes section: id, x, y, heading, pause, followHordeId, member count, member records; `LoadResult.hordes` (list of decoded hordes with member records).
- Produces: `final class SaveWriter { void submit(Path file, byte[] data); void flush(long timeoutMillis) /* waits for pending writes */; void stop() }` — one daemon thread `TrueHordes-save`; newer submissions replace an unwritten older one.
- Modify `Core.save()` / `hotSave()`: encode under the lock, submit to `SaveWriter`; the game's full save on quit (`Core.save` called from the SAVE hook followed by STOP) calls `flush(10_000)` in `stop()`.

- [ ] **Step 1: Failing tests:** `testV3RoundTripKeepsHordesAndWalkFields`; `testV2UpgradesToWanderers` (Review Focus 3: a v2 file from the sub-project 3 writer loads with every record a wanderer, `strRolled == false`, no hordes); `testV1StillUpgrades` (existing behaviour); `CoreTest.testHotSaveDoesNotWriteOnCallerThread` (writer thread name recorded by a test hook); `CoreTest.testStopFlushesPendingSave`.
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: save format 3, writes off the main thread`.

### Task 12: Vanilla rally groups off

**Files:** Modify `HookTargets.java`, `Patches.java`, `Hooks.java`; Test `VerifyTargetsTest.java` (add).

**Interfaces:**
- Produces: `HookTargets.RALLY = {"zombie.ai.ZombieGroupManager", "update"}` added to `ALL` (required); `Patches.RallyOff` with `@Patch.OnEnter(skipOn = true) static boolean enter() { return Hooks.rallyOff(); }`; `Hooks.rallyOff()` = `core != null && core.takesOverPopulation()`; start-up log `vanilla rally groups switched off (the mod forms hordes)`.

- [ ] **Step 1: Failing test:** `VerifyTargetsTest.testRallyTargetExists` (the target is in `ALL` and found in the game jar by the existing verifier; hook count 11).
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: vanilla rally groups off`.

### Task 13: Lua commands and the debug map

**Files:** Modify `LuaApi.java`, `lua/shared/TrueHordes/Hordes_Core.lua`, `lua/client/TrueHordes/Hordes_DebugMap.lua`, `store/Store.java` (snapshot); Test `LuaSyntaxTest` (existing, covers Lua), `StoreTest.java` (add).

**Interfaces:**
- Produces: `Hordes_sound(x, y, loudness)` → `Hordes_j_sound` → `Simulation.addSound(new SoundEvent(..., loudness default 90 dB, duration 0.5 in-game hours, moaning=false))`, returns `"sound at x,y (L dB) heard to ~R tiles"`; `Hordes_hordes()` → biggest 20 hordes: `id size x,y heading moving|paused followers-of`; `Hordes_stats()` gains sim and conservation lines (Task 9).
- Produces: snapshot adds per-point kind (0 wanderer, 1 straggler) and a horde list (x, y, spread, size, heading) — `Hordes_j_snapshot` returns `hordes` subtable; the map draws hordes as circles (radius = spread, scaled), size label, heading arrow; stragglers in a separate colour; wanderers unchanged.

- [ ] **Step 1: Failing tests:** `StoreTest.testSnapshotMarksStragglers`; `LuaSyntaxTest` covers the edited Lua files (existing).
- [ ] **Step 2: Run build** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run build** — Expected: PASS.
- [ ] **Step 5: Commit** `SP4: Hordes_sound, Hordes_hordes, hordes on the debug map`.

### Task 14: Docs, deploy, self-review

**Files:** Modify `docs/TESTING.md`, `design.md` (sub-project 4 row, facts learned).

- [ ] **Step 1:** Add `## Sub-project 4 acceptance` to `docs/TESTING.md`:

| # | Do | Expect |
|---|---|---|
| 1 | New game; `console.txt` | `hook targets found: 11`, `vanilla rally groups switched off`, seeding line, `simulation started` |
| 2 | `Hordes_map()`, watch an unvisited town over 2–3 in-game days (sleep/wait) | dots drift, mostly along roads; none in water; none leave the map |
| 3 | `Hordes_sound(x, y)` ~60 tiles from you in a populated area, wait 30 in-game minutes | dots converge; a horde circle with a count appears when the sound ends |
| 4 | Follow that horde on the map | moves at a believable pace, pauses briefly, prefers roads |
| 5 | `Hordes_hordes()` after a week | several hordes; smaller ones trailing bigger ones |
| 6 | Walk towards a horde | its zombies appear as a column; `in` rises; no invisible horde inside the loaded area |
| 7 | First person, map closed, 5 minutes | no regular hitches; `Hordes_stats()` sim time per tick small; save writes not on the main thread |
| 8 | Save, quit, reload; `Hordes_hordes()` | same hordes (ids, sizes) |
| 9 | `Hordes_stats()` | conservation `ok` |
| 10 | Load a sub-project 3 save | everyone a wanderer; stragglers shown in their colour |

- [ ] **Step 2:** `design.md`: sub-project 4 row → built on branch, facts learned (sight rule, rally target, speed ruling).
- [ ] **Step 3:** Run `powershell -File tools\deploy.ps1` — Expected: all tests pass, deployed.
- [ ] **Step 4:** Self-review against the spec (no subagents per the user's rule); fix Critical/Important with a failing test first.
- [ ] **Step 5:** Commit code; leave `design.md` and `docs/TESTING.md` uncommitted for the user.

---

## Spec coverage

| Spec section | Tasks |
|---|---|
| 1 Goal / scope (any population, no director, out of scope) | Global Constraints; Task 7 `testNoDirector`; Task 8 (1M) |
| 2 Rules (Q1, Q8) | Tasks 5, 6, 3 (pauses, crowding), 12 (rally) |
| 3 Architecture | Tasks 1, 4, 7, 9 |
| 4.1 Movement | Task 3 |
| 4.2 Formation and following | Task 6 |
| 4.3 Sight | Task 2 |
| 4.4 Moaning | Task 5 |
| 4.5 Encounters | Task 6 |
| 4.6 Stragglers | Tasks 1, 6, 13 |
| 4.7 Test sound | Tasks 5, 6 (convergence), 13 |
| 5 LOD, threads, save, conservation, stats | Tasks 7, 8, 9, 11 |
| 6 Hand-over | Task 10 |
| 7 Rally off | Task 12 |
| 8 Settings | Task 1 |
| 9 Save format 3 | Task 11 |
| 10 Debug tools | Task 13 |
| 11 Testing | every task; Task 14 |
| 12 Risks | Review Focus; rulings above; Task 8 |
