# True Hordes: limitation discovery (Build 42.21)

Game build checked: 42.21.0, revision 4a0e9546ec (`%USERPROFILE%\Zomboid\version.txt`).
All paths in the brief were verified and exist.

## How this was checked

- `projectzomboid.jar` was decompiled with the Fernflower decompiler bundled in IntelliJ IDEA 2025.3
  (the classes are Java 25, which the JDK 17 `javap` on this PC cannot read). Output is in the session
  scratchpad, not in any game or mod folder. **Line numbers below are lines in that decompiled source**,
  not in any shipped file. I can regenerate it into a folder you choose.
- Vanilla Lua under `media\lua`, the vanilla map data under `media\maps\Muldraugh, KY`, and the Lua of
  Wandering Zombies, Starving Zombies and Louder Gunshots were read directly.
- The off-screen zombie simulation is native code in `PZPopMan64.dll`. **Update (second pass):** with
  capstone and pefile installed, the DLL was disassembled and the parts that matter here were traced
  by hand. Results are in **"Native findings"** below. DLL references are code addresses in the 42.21
  `PZPopMan64.dll` (for example `0x18001c260`). The DLL has no symbols, so function purposes are
  inferred from what the code does. Where that inference is weaker, the text says so.

---

## One-line verdicts

| # | Item | Verdict |
|---|------|---------|
| 1 | The two zombie states | **Possible with limits**: understood on both sides now (see Native findings) |
| 2 | Moving virtual zombies | **Possible with limits**: only indirectly. Sounds wake standing zombies for at most `FollowSoundDistance` tiles; aggro points steer only moving zombies within 300 tiles; `createHordeFromTo` spawns new ones. No per-zombie orders; straight-line movement only |
| 3 | Moving real zombies | **Possible**: full control from Lua inside the loaded area, with known interruptions |
| 4 | Rally groups and leaders | **Possible with limits**: vanilla can be switched off and rebuilt in Lua; it can't be edited in place without Java; nothing exists for virtual zombies |
| 5 | Horde identity across both states | **Possible with limits**: a horde-level record is easy; tracking individual members through the virtual state needs Java |
| 6 | Formation triggers | **Possible with limits**: sounds have an event; sightings and nearby zombies must be polled; animals are ignored by vanilla zombies |
| 7 | Terrain | **Possible**: roads, forest, fields, towns and buildings are known for the whole map, even unloaded; water only partly |
| 8 | Unvisited cells and conserved total | **Possible with limits**: thinning and spawning work from Lua; reading per-cell numbers needs Java; vanilla respawn breaks "never grows" unless disabled |
| 9 | Scale | **Not settled by reading**: needs your measurements (tests T10) |
| 10 | Multiplayer | **Possible with limits**: virtual simulation is server-only; real zombies are run by the nearest client |
| 11 | Lua or Java | A useful mod is possible in **Lua only**; exact membership, virtual census and some fixes need a **ZombieBuddy Java** part |
| 12 | Neighbours | Starving Zombies: **compatible with friction**. Louder Gunshots: **compatible, and it dominates sound triggers**. Wandering Zombies: **conflicts** |

---

## Native findings (`PZPopMan64.dll`, second pass)

These replace the "native, needs test" guesses in the first version of this report.

**How Java talks to the DLL.** Every call from Java (sound, aggro target, horde spawn, chunk load) is queued
as a record and handed to the popman thread once per update (`0x18001d5e0` merges the queues, and
`0x1800218e0` is the thread's main update). Sounds arrive as `{x, y, radius, volume}` only (`0x1800291d0`).

**Sounds and virtual zombies**
1. **Volume is ignored.** Only x, y and radius are used by the native side (`0x18001c260`, `0x18001d2c0`).
2. **A sound stays "active" for 1 second after it was last sent.** While active it is applied immediately,
   then at most once every 5 seconds (`0x180022180-0x18002223b`: `+1000 ms` expiry, `+5000 ms` re-apply).
   Identical sounds re-sent (alarms, sirens) refresh it, so they keep pulling. A single gunshot is
   applied once.
3. **Who reacts to a new sound (`0x18001c260`):** every 8×8 chunk whose centre is inside the radius,
   **except chunks within 10 tiles of the source**. In those chunks, only **standing** virtual zombies on
   ground level, not inside a building or on an obstacle (map-collision flags `0x19`), and without state
   flag `0x08`. Zombies that are already moving are not touched by this step.
4. **They move as units of up to 26.** Connected zombies from the same chunk (flood-filled through walkable
   tiles) are packed into one moving unit with a leader. The followers share the leader's position while
   virtual (`0x180016550`). They are spread back over the leader's chunk area only when the unit stops or
   becomes real (`0x1800163b0`). This is a native reason arrivals come in clumps.
5. **Scatter.** Each unit aims at the sound **± (Manhattan distance from its chunk to the sound) × 0.25** on
   each axis (`0x18001c5a7-0x18001c5e3`; random range `0x180013cc0`, factor 0.25 at `0x1800fa2f0`). A unit
   400 tiles away aims up to ±100 tiles off.
6. **`FollowSoundDistance` is a tile budget.** The setting is copied to the settings object at `+0x184`
   (default 100, set in `0x180019cf0`). The tile counter is reset to 0 whenever a unit gets a sound target
   (`0x18001c652`, `0x1800223c1`). Each time the unit crosses into a new tile the counter goes up; when it reaches
   `FollowSoundDistance` the unit **stops and becomes standing** (`0x1800173ae-0x180017434`). **This only
   applies when the target came from a sound.** Units without a sound (spawned by `createHordeFromTo`, or real
   zombies that walked out of the loaded area with a path target) walk until they arrive or are blocked.
7. **Distraction of moving units.** Every update, each moving unit looks for the **nearest active sound whose
   radius covers it** (`0x18001d2c0`). If that is a different sound from its current one, it re-targets to it
   with the same ±25% scatter and a reset counter (`0x1800222c0-0x1800223c6`). So a horde walking somewhere
   is pulled away by any nearer sound it is inside.
8. **No path search in a normal game.** Each step moves the unit 0.06 tiles × the game-time multiplier in a
   **straight line** toward its target (`0x180015cc0`, `0x180015c90`, constant `0x1800fa564`). A path search
   exists but is switched on only in Last Stand (`Core.bLastStand` → flag `0x180131918`,
   `0x180025110`; set from `MapCollisionData.java:105`). If the straight line is blocked (`0x180016570`), the
   unit counts as "arrived" and **becomes standing where it is**. Virtual zombies therefore cut straight
   across country, ignore roads, and pile up against walls, fences and rivers.
9. **Hand-off to real.** A moving unit becomes real only when it is in a loaded chunk **and not on the edge
   row** of the loaded area (`0x180017058-0x180017113`; edge test `0x18000e3b0` = native `isOnEdge`).
   On the edge it keeps walking inward. **Correction:** the Java edge wipe (`ZombiePopulationManager.java:542`)
   therefore rarely hits walking arrivals; my first report overstated it.

**Aggro targets (`setAggroTarget`)**
- Stored per id. Sending the same id again replaces it; `-1,-1` removes it (`0x1800203a0`).
- Each update, every **moving** unit within **300 tiles (Manhattan distance)** of an aggro point is pointed at the
  nearest one, with no scatter (`0x18001d240`, limit `0x12c`; applied at `0x1800223e0-0x18002244e`).
  **Standing zombies are not affected**, and the tile counter is not reset.
- **While any aggro point exists, sound re-targeting of moving units is skipped entirely** (`0x180022259`).
- A unit that is "arrived" or blocked but still within 300 tiles of an aggro point stays moving instead of
  standing (`0x18001740c`).
- So it is not map-wide. It is a 300-tile magnet for zombies that are already walking.

**Cells, population and unvisited areas**
- The DLL allocates an object for every 256×256 cell of the map at start (`0x18001bc50`). A cell is
  **loaded the first time anything touches it** (`0x18001e8f0`): a sound whose radius covers it, a moving
  unit inside it, a chunk loading, or a horde spawn. Loading reads `zpop_X_Y.bin` if it exists
  (`0x180008e60`).
- **The first time a cell is touched it is filled with individual standing virtual zombies up to its
  target** (`0x180008b30`: per-chunk target from the zombie density map, the population multipliers and
  min/max per chunk; then `0x18000bed0` adds zombies; flag `+0x45` marks the cell as populated). Before that
  the cell holds no individual zombies, only the potential to spawn its target.
- Consequence: **a big sound populates every untouched cell in its radius and then pulls the new zombies.**
  Your 5× shot did exactly that across Muldraugh.
- **Respawn** (`0x180020e70`, every update over the list of loaded cells): only if `RespawnHours > 0`
  (`0x18001dfa0`), and only when a cell is at least 10 zombies below its target.
- **Loading a chunk** moves that chunk's standing virtual zombies out to Java as real zombies
  (`0x18001be00`); it does not create new ones.

---

## 1. The two zombie states

**Verdict: possible with limits.**

**Where it runs.** The off-screen ("virtual") simulation is native. `ZombiePopulationManager` declares
about 25 `native` methods (`ZombiePopulationManager.java:100-154`) and loads `PZPopMan64.dll` (`:191`).
The DLL's exported symbols match those methods one to one. It is initialised with the bounds of the whole
map grid (`:284-291`), so it covers the entire map, not a radius around players. It runs on a separate thread
(`updateThread`, `:810`). Every Java entry point returns early when `GameClient.client` is true
(for example `:157`, `:285`, `:348`, `:509`), so **in multiplayer only the server runs it**. In single
player your own game runs it.

**How far "real" extends.** Real zombies exist only in loaded chunks. In single player the loaded window is
`IsoChunkMap.chunkGridWidth` chunks square. At 1920×1080 or above it is 19×19 chunks = **152×152 tiles,
about 76 tiles from the player in each direction** (`IsoChunkMap.java:118-129`). Smaller screens get less.
On a server each player contributes their own `onlineChunkGridWidth` square (`LoadedAreas.java:35-52`), up to
64 areas of at most 128 chunks each (`:66-74`).

**Real → virtual.** This happens when a chunk unloads (`ZombiePopulationManager.removeChunkFromWorld`, `:358-417`):
- A zombie that is **outdoors, on the ground floor and in a walk/pathfind state** is stored as a *moving*
  virtual zombie with its path target (`:384`, `:391-392`).
- **Any other zombie (indoors, upstairs, idle, eating, thumping) is stored as *standing*: its target is
  discarded** (`:384-386`).
- In multiplayer the server also **deletes** real zombies beyond a per-client cap
  (`ZombieCountOptimiser.java:18-53`, sandbox `ZombiesCountBeforeDelete`, default 300). Deleted zombies are
  released from the population for good (`IsoZombie.removeFromWorld`, `IsoZombie.java:4004`, `:4024`).

**Virtual → real.** Every frame the DLL returns a list of zombies to create (`n_getAddZombieCount` /
`n_getAddZombieData`, `ZombiePopulationManager.java:514-555`). Moving ones are created and immediately given
`pathToLocation` to their stored target (`:791-794`). So **yes, a virtual zombie that walks into the loaded
area becomes real** and keeps walking. The exceptions:
- If it is handed over on the outermost tile row of a loaded area, its target is wiped and it spawns standing
  (`:542-545`, `LoadedAreas.isOnEdge`). **Corrected after the native pass:** the DLL itself refuses to hand a
  walking unit over on that edge row and keeps it walking inward (see Native findings, point 9). So this
  wipe rarely affects walking arrivals. It can still hit followers that are spread onto edge tiles when
  their unit is handed over.
- Zombies landing in a room of a brand-new chunk are silently skipped (`:535-540`).
- Zombies landing near a player spawn point are removed (`:612-616`, `:760-764`).
- If the square has no floor, the zombie is sent back to the DLL (`:669`, `:803`).

**Your open question ("did the arrivals turn from virtual into real?").** By the code, almost certainly yes:
a 5× rifle shot (about 850 tiles) reaches far beyond the 76-tile real window, so nearly every responder was
virtual and was converted on arrival. Test T1 confirms it from the console log.

**The DLL also tracks real zombies.** Every 5 seconds Java sends it the ID, state and position of every real
zombie (`:501-506`, `:579-583`). B42.21 gives each zombie a `persistentId` that the DLL assigns and that
travels through both states (`IsoZombie.java:224`, `VirtualZombieManager.java:226`,
`ZombiePopulationManager.java:386`).

## 2. Moving virtual zombies

**Verdict: possible with limits. Existing virtual zombies cannot be given a destination individually from
Lua or Java; only indirect tools exist.**

**Sound.** `WorldSoundManager.addSound` forwards every sound to the DLL (`WorldSoundManager.java:145`), but the
DLL only receives a sound if **radius ≥ 50, the source is not a zombie, and the "stress zombies" flag is set**
(`ZombiePopulationManager.java:450-466`). The radius is multiplied by the Hearing sandbox setting (Pinpoint ×3,
Normal ×1, Poor ×0.45; `WorldSoundManager.java:468-474`). The DLL gets only x, y, radius and volume.
- *How a virtual zombie picks between several sounds, and how far it follows one:* **settled by the
  native pass** (Native findings, points 1–7):
  - A standing zombie takes the first sound that covers it; volume plays no part.
  - A moving one switches to the nearest active sound that covers it.
  - A sound-driven unit walks at most `FollowSoundDistance` tiles (default 100, range 10–1000,
    `SandboxOptions.java:1715`) and then stands.
  - From 850 tiles away, a single shot moves a virtual zombie only about 100 tiles toward it. Only zombies
    within about 100 tiles of the loaded area actually arrive.
- *Why some stall or wander:* see "Parked items" at the end.

**The other calls:**

| Call (Lua global) | What it actually does | Source |
|---|---|---|
| `createHordeFromTo(sx, sy, tx, ty, n)` | Asks the DLL to create **n new** virtual zombies at sx,sy that walk to tx,ty. Adds population; does not move existing zombies. No client check: on an MP client the DLL isn't initialised, so call it only on the server. | `LuaManager.java:5234`, `ZombiePopulationManager.java:438` |
| `createHordeInAreaTo(x, y, w, h, tx, ty, n)` | Same, spawning inside a rectangle. | `LuaManager.java:5242` |
| `spawnHorde(x, y, x2, y2, z, n)` | Creates **real** zombies on loaded squares in a rectangle; nothing virtual. | `LuaManager.java:5250` |
| `addVirtualZombie(x, y)` | **Empty stub in 42.21: does nothing.** | `LuaManager.java:7989` |
| `setAggroTarget(id, x, y)` | Sends a target to the DLL. Vanilla Last Stand calls it **every tick with the player index (0–3) as `id`** and clears it with `-1,-1` (`Challenge1.lua:53`, `:191`). So it is **one attraction point per id, not a per-zombie order**. **Native pass:** it steers only virtual zombies that are **already moving**, within **300 tiles** (Manhattan) of the point. It ignores standing ones, and while any aggro point exists it switches off sound re-targeting for moving units everywhere. | `LuaManager.java:8008`, `ZombiePopulationManager.java:434` |

**Count or locate virtual zombies:**
- **Lua: no.** `ZombiePopulationManager` is not exposed to Lua, and no global returns counts or positions.
- **Java: possible with limits.** The DLL "draws" the debug population map by calling back into Java:
  `ZombiePopulationRenderer.renderZombie(x, y, …)` for each virtual zombie and
  `renderCellInfo(cellX, cellY, effectivePopulation, targetPopulation, lastRepopTime)` per 256×256 cell
  (`ZombiePopulationRenderer.java:177`, `:197`). A ZombieBuddy patch on those callbacks could collect positions
  and per-cell counts. This is what the **Debug menu → Zombie Population** window shows
  (`ZombiePopulationWindow.lua`), and that window is the main tool for the tests below. Whether it can run on a
  headless dedicated server is untested and doubtful (it renders into UI buffers).

## 3. Moving real zombies

**Verdict: possible (inside the loaded area).**

- `pathToLocation(x, y, z)` (integers) and `pathToSound(x, y, z)` always take effect
  (`IsoGameCharacter.java:5949-5962`). **`pathToLocationF` and `pathToCharacter` are overridden on zombies and
  are silently ignored while the zombie is already walking and its `allowRepathDelay` > 0**
  (`IsoZombie.java:998-1010`). Vanilla sets that delay to 120 after a sound and 400 after a rally move
  (`IsoZombie.java:1491`, `ZombieGroupManager.java:139`). The field is public Java, but Lua can't set fields.
  Use the integer versions.
- `setTarget(obj)` sets the chase target (`IsoZombie.java:4890`). `spotted(other, forced)` runs the vanilla
  sighting logic (`:2633`). Vanilla only ever spots players (`IsoPlayer.java:1159-1167`).
- **Reach of one order:** the pathfinder (native, `PZPathFind64.dll` in B42, `PathfindNative.java:40`) plans
  inside loaded chunks. If the path "succeeds" and the target chunk is not loaded, the zombie **keeps walking**
  (`PathFindState.java:51-57`). When it leaves the loaded area it becomes a *moving* virtual zombie carrying
  that target (see item 1). So one order can in principle send a zombie across the map, handing over to the DLL
  on the way. Whether the pathfinder accepts a target 300+ tiles away is native: **test T8**.
  Native pass: once virtual, such a zombie walks a **straight line** to that target with no tile limit
  (no sound attached). It stops for good at the first blocking obstacle, and any active sound that covers it
  re-targets it (Native findings, points 6–8).
- **What interrupts it:**
  - seeing a player (`setTarget`);
  - a new sound: `RespondToSound` re-paths when the sound lies in a different direction from the current goal
    (`IsoZombie.java:1486-1496`);
  - vanilla rally groups, but only once the zombie is idle (`ZombieGroupManager.java:110`);
  - thumping doors and windows;
  - the "walking on the spot" detector, which fails the path (`PathFindBehavior2.java:796`);
  - other mods (Starving Zombies, Wandering Zombies);
  - in multiplayer, a change of owning client (item 10).

## 4. Rally groups and leaders

**Verdict: possible with limits. Real zombies only.**

How vanilla does it (`ZombieGroupManager.java`, `ZombieGroup.java`), all on the machine that runs the zombie:
- **Who joins:** idle real zombies with no target, not in a building, not in a `Forest`/`DeepForest` zone, not
  sitting or eating, and only after about 2000 ticks without seeing a player or hearing a sound
  (`ZombieGroupManager.java:60-88`, `IsoZombie.java:3201-3203`). Checked once every 30 ticks (`:36-40`).
- **Forming:** a zombie joins the nearest group whose leader is within `RallyTravelDistance` (default 20) and
  which is below `RallyGroupSize` (default 20) ± `RallyGroupSizeVariance`. Otherwise it starts its own group
  (`:98-108`, `:170-194`). Members drift out of the group beyond `RallyTravelDistance` (`ZombieGroup.java:66-85`).
- **Leader:** simply **the first member in the list** (`ZombieGroup.java:54-56`).
- **Positions:** a member farther than `RallyGroupRadius` (default 3) from the leader paths to **a random tile
  within ±radius of the leader** (`ZombieGroupManager.java:146-163`). That is why they bunch.
- **Leader movement:** every 5 in-game minutes an idle leader walks up to 10 tiles away from other leaders
  within `RallyGroupSeparation` (default 15) (`:110-145`). There is no travel goal; groups only spread apart.

What a mod can do:
- **Lua cannot edit this logic.** `ZombieGroupManager`, `ZombieGroup` and `IsoZombie.group` are not visible to Lua.
- **Lua can switch it off and rebuild it:** `getCore():setZombieGroupSound(false)` disables grouping on that
  machine (`Core.java:4002`, checked at `ZombieGroupManager.java:65`), or set `RallyGroupSize` to 0 or 1 in sandbox
  options. A Lua replacement can choose its own leader, make members trail behind the leader along its heading,
  merge groups and keep the group moving.
- **Java could patch it in place** (leader choice, formation offset).
- **Virtual zombies:** nothing comparable exists on the Java side.

## 5. Horde identity across both states

**Verdict: possible with limits.**

- **A horde as a record** (ID, centre, heading, destination, head-count, mode) is easy: server-side global
  ModData (`ModData.getOrCreate`) is saved with the world and works in SP and MP.
- **Individual membership through the virtual state is not possible from Lua:**
  - A virtual record keeps only position, direction, outfit, state flags (crawler, fake-dead and similar),
    path target and `persistentId` (`ZombiePopulationManager.java:386`, `:392`). **Per-zombie `getModData()` is
    lost** the moment a zombie turns virtual (test T6). Group membership and chase target are lost too.
  - Lua has **no unique ID that survives**. `getID()` is a runtime object number, and zombie objects are reused
    from a pool (`VirtualZombieManager.java:157-185`). `getOnlineID()` is MP-only and reassigned.
    `getPersistentOutfitID()` survives but repeats: about 500 variants per outfit (`PersistentOutfits.java:152-199`).
  - **Java can read `persistentId`** (a public field, `IsoZombie.java:224`), which does survive (test T7).
- **What is lost when members unload:** see the list above. Also, a member that was not walking outdoors on the
  ground floor at unload time loses its destination (item 1).
- **Position and heading while virtual:** without Java, the horde record has to *estimate* where its members
  are, because nothing reports virtual positions to Lua.

## 6. Formation triggers

**Verdict: possible with limits.**

| Trigger | How to observe it | Machine |
|---|---|---|
| World sounds and radius | `Events.OnWorldSound(x, y, z, radius, volume, source)` fires for every sound created, zombie sounds included (`WorldSoundManager.java:544`). | Every machine that creates the sound. In MP, client sounds are re-added on the server (`WorldSoundPacket.java:139`), so the **server sees all player sounds**. |
| Zombie sees a player | **No event.** Poll `zombie:getTarget()` in `OnZombieUpdate` or a budgeted tick. | The client that owns the zombie. The server also runs spotting for its zombies (`IsoPlayer.java:1159-1163`). |
| Zombie sees or is near other moving zombies | **No event and no vanilla concept.** Zombies only spot players. The mod must poll `getCell():getZombieList()` positions and states itself (cost grows quickly with count; budget it). | Owner client, or the server in MP. |
| B42 animals | **Vanilla zombies ignore animals**: no zombie state references `IsoAnimal`, and animal noises are FMOD audio, never world sounds (no `addSound` calls in animal code). A mod can poll `getCell():getAnimals()` (`IsoCell.java:4517`, loaded area only) and create its own world sound to attract zombies. Wild animals far away are not real objects. | Wherever chunks are loaded. |

## 7. Terrain

**Verdict: possible.**

- **Map zones are loaded for the whole map at start-up**, loaded chunks or not, and are readable from Lua on
  server and client: `getWorld():getMetaGrid():getZonesAt(x, y, 0)` / `getZoneAt` (`IsoMetaGrid.java:187-206`),
  `zone:getType()`. Vanilla Muldraugh, KY `objects.lua` contains 5,801 **`Nav`** zones (road strips such as 8×234
  tiles), 2,482 `Forest`, 1,105 `DeepForest`, 9,310 `Vegitation`, 1,540 `FarmLand`, 2,714 `TownZone`, plus Farm,
  Ranch and TrailerPark. **Named streets from `streets.xml` are also registered as `Nav` zones at world start**
  (`IsoWorld.java:438`, `WorldMapStreet.registerNavZones`).
- **Buildings:** `getBuildingAt(x, y)` / `getRoomAt` work for the whole map (`IsoMetaGrid.java:249-357`).
- **Zombie density map:** `getChunkData(cx, cy):getZombieIntensity()` for the whole map (`IsoMetaChunk.java:56`).
- **Water is the gap.** It is not a zone layer (only 49 small `WaterZone` and 840 `WaterFlow` point entries).
  Rivers and highway classes (primary, secondary, tertiary, trail) are in `worldmap.xml`, but Lua can reach
  that only through a client map UI. **On the server, water and road class need Java** (or a road/water table
  pre-built from the map files and shipped with the mod, which works only for maps you prepare).
- **Is it enough to prefer roads but leave them?** For the *horde record* and for *real* zombies, yes: the mod
  picks waypoints along `Nav` zones and can divert off-road toward a pull. **Virtual zombies don't follow roads
  at all**: the DLL moves them in a straight line and stops them at the first obstacle (Native findings,
  point 8). To make an off-screen horde follow a road, the mod would have to feed it short hops between road
  waypoints. Each hop needs a target the DLL accepts, and the only per-unit way to give one is a sound or an
  aggro point (both affect everything nearby), or keeping the horde on paper and not as virtual zombies at all.

## 8. Unvisited cells and the conserved-total design

**Verdict: possible with limits.**

- **Individual zombies or just a number? Settled by the native pass:** a cell nothing has touched yet has
  no individual zombies. The first time it is touched (a sound covering it, a unit walking in, a chunk
  loading, a horde spawn), it is filled straight away with individual standing virtual zombies up to its
  target, and from then on it is saved to `zpop_X_Y.bin` (Native findings, "Cells"). T5 is now a
  confirmation only.
- Conserved-total consequence: a far area's population can be removed **before** it exists as individuals
  only by keeping its cell from being touched. Any big sound will populate it. Once populated, removing it
  needs `zpopClearZombies`, and the per-cell totals are only readable through Java.
- **Read the number per cell:** Lua no; Java yes, via the renderer callback (item 2).
- **Change it / force population:**
  - `zpopSpawnNow(cellX, cellY)`, `zpopSpawnTimeToZero(...)` and `zpopClearZombies(...)` are global Lua functions
    that work on a whole 256×256 cell (`LuaManager.java:7965-7981`). In MP a client needs the `PopmanManage`
    permission; the server can call them directly (`ZombiePopulationManager.java:831-868`). Despite the "debug"
    name, nothing restricts them to debug mode.
  - `setMinMaxZombiesPerChunk(min, max)` only stores the values. They reach the DLL in `onConfigReloaded`, which
    runs at world start or on a server options reload (`:313-327`, `LuaManager.java:4907`). **Effectively
    start-up only**, unless Java calls `onConfigReloaded`.
  - `registerSpawnOrigin` only takes effect before the DLL starts (`:300-309`).
- **Remove zombies as a cell loads:** yes. Catch them in `OnZombieCreate` (fires before position and ID are set,
  `VirtualZombieManager.java:206`), act on the first `OnZombieUpdate`, then call `zombie:removeFromWorld()`. That
  releases them from the population permanently (`IsoZombie.java:4024`).

**The conserved-total design, step by step:**

| Step | Possible? |
|---|---|
| Absorb a far area's population into a horde record | **With limits.** Counting needs Java. Removing is whole-cell only (`zpopClearZombies`). **Vanilla respawn refills cells** every `RespawnHours` (default 72) and redistributes within cells every `RedistributeHours` (default 12; `SandboxOptions.java:1711-1714`). For "the total never grows" these must be 0, or the mod has to keep removing the refill. |
| Thin the area when a player arrives | **Yes** (remove real zombies as they are created). |
| Place the same number at the horde when a player gets near | **With limits.** `createHordeInAreaTo` adds virtual zombies that walk to a point, but Last Stand notes the game "will remove any virtual zombies that are too close the the player" (`Challenge1.lua:135`), and spawns near player spawn points are removed (`ZombiePopulationManager.java:612`). Real zombies can be placed out of sight with `addZombiesInOutfit` / `spawnHorde`. Whether `createHordeInAreaTo` adds exactly n, and whether they count toward the cell's effective population, is **test T4**. |
| Total never grows | **Only if** respawn is disabled. Server culling (`ZombiesCountBeforeDelete`) can also *shrink* it in MP. |

## 9. Scale

**Verdict: can't be settled by reading. Test T10 gives the numbers.**

What the code does say:
- Issuing a path order from Lua is cheap. The cost is the pathfinding request it queues on the native
  pathfinder thread (`PathfindNative.java:220-228`). A new order cancels the previous request for that zombie.
- The game has **no single-player cap** on real zombies. In MP the server deletes outdoor, target-less zombies
  beyond `ZombiesCountBeforeDelete` per client area (default 300, which the game's own help "strongly
  recommends"; `ZombieCountOptimiser.java`). A thousand real zombies in one spot in MP will be culled.
- Each real zombie is a full animated character. On a 4 GB GPU with Project Viewpoint, expect rendering, not
  Lua, to be the first limit. T10 separates the two.
- Wandering Zombies budgets with "at most 20 zombies **and** at most 2 ms per tick", round-robin
  (its sandbox options `UpdateZedLimit` and `UpdateMsLimit`). That is a sensible starting point, but measure.

## 10. Multiplayer

**Verdict: possible with limits.**

- **Virtual zombies:** simulated **only on the server** (item 1). With no players near a horde, its members are
  virtual on the server and move only as the DLL moves them.
- **Real zombies:** the server owns the list. **The nearest relevant client is the "owner" and runs the AI**
  (`NetworkZombieManager.updateAuth`, `NetworkZombieManager.java:56-149`; re-checked every 2 s, with hysteresis
  ×1.618). Sound response runs only on the owner (`IsoZombie.java:1406`: skipped on the server).
  `OnZombieUpdate` fires on the server and on clients, so mods filter with `isRemoteZombie()`, as Starving and
  Wandering Zombies do.
- **Consequences for shared horde state:**
  - The horde record must live on the server (global ModData).
  - Steering real members must be done by **each owning client**, ordered by the server through
    `sendServerCommand`. Path orders issued on the server for client-owned zombies will likely be overwritten by
    the owner's updates (to confirm in an MP test).
  - One horde can be split across several owners when players are in different places.
  - With no players near, only the server's virtual simulation and the horde record exist.

## 11. Lua or Java

| Need | Lua only | Needs ZombieBuddy Java |
|---|---|---|
| Observe sounds, steer real zombies, replace rally groups, terrain from zones, horde records, thin cells, spawn hordes, aggro points | ✔ | |
| Stable identity of a zombie through the virtual state (`persistentId`) | | ✔ |
| Count or locate virtual zombies; per-cell population numbers | | ✔ (renderer callbacks; feasibility on a dedicated server unproven) |
| Turn a specific real zombie virtual on demand (`virtualizeZombie` exists but isn't exposed) | | ✔ |
| Fix vanilla causes of lost zombies (edge target wipe at `ZombiePopulationManager.java:542`, sound scatter at `IsoZombie.java:1483`) | | ✔ (both are Java, so patchable) |
| Edit vanilla rally logic in place | | ✔ |
| Water and road class on a dedicated server | | ✔ (or pre-built data) |
| Push per-chunk min/max population after start | | ✔ (`onConfigReloaded`) |
| Change how the DLL moves virtual zombies | ✘ (native; out of reach of both) | ✘ |

ViewpointTrueBallistics is a good template. Layout: `42/src/<pkg>` with patch classes directly in the package
named by `javaPkgName` (ZombieBuddy does not scan sub-packages), `Main.java`, a `Hooks` class that wraps
everything and never throws into the game, a `LuaApi` of `VTB_*` globals, and `test/` with unit tests, a
`VerifyTargets` check of every patch against the real classes, and an offline patch harness. Build with
`javac --release 21 -cp "projectzomboid.jar;ZombieBuddy.jar"` (needs JDK 25 to read the game classes; this PC has
JDK 17/21/23, plus IntelliJ's runtime). The jar goes to `42/media/java/`, and `mod.info` gets
`require=\ZombieBuddy`, `javaJarFile`, `javaPkgName`, `ZBVersionMin`.

**Cost of choosing Java:** every player *and the dedicated server* must have ZombieBuddy installed as a
Java agent (`-agentlib:zbNative` on Windows). That is a real barrier for server hosts.

## 12. Neighbours

- **Louder Gunshots:** compatible. It multiplies every firearm's `SoundRadius` on client and server
  (`LouderGunshots.lua`), so most shots clear the radius-50 threshold and reach virtual zombies hundreds of tiles
  away. For True Hordes, gunshots will dominate sound-based formation. Combined with the real-zombie scatter
  (distance ÷ 2.5, item "Parked"), far listeners land wide of the shooter. Any formation rule keyed to sound
  radius has to assume Louder Gunshots may be at 5–20×.
- **Starving Zombies (3396867685):** compatible with friction. On the owning client it re-paths **idle** real
  zombies toward corpses or a smelled player every 250 ms with `pathToSound` (`RYUKU_StarvingZombies.lua:189-196`).
  Walking horde members are left alone, but a horde that stops will be pulled apart by corpses, and eating zombies
  (`setEatBodyTarget`) stay behind. To make skeleton corpses it briefly creates and removes a real zombie
  (`RYUKU_StarvingZombies_Server.lua:57-111`), which fires `OnZombieCreate`; a horde mod must not count that one.
  Its player smell is a ready-made extra trigger.
- **Wandering Zombies (2983905789), if enabled with True Hordes: they conflict.** From its code it collects
  zombies in `OnZombieUpdate`, then on `OnTick` (max 20 zombies, 2 ms) issues `pathToSound`/`pathToLocation` to
  every untargeted, unalerted real zombie on its own schedule. It runs its own leader/follower hordes from the
  vanilla rally sandbox values, and with TryStopVirtual it pulls any target more than 95 tiles from the nearest
  player back toward the player (`RYUKU_WanderingZombies.lua:250-264`). Both mods would give competing orders to
  the same real zombies (last order wins, so zombies jitter and both kinds of horde break up), and TryStopVirtual
  directly cancels any True Hordes order that leads out of the loaded area. Neither touches virtual zombies, so
  there is no conflict there. It should be declared `incompatible` in mod.info.

---

## Parked items: causes only, no design

**Getting lost on the way to a sound**
1. **Real zombies aim at a random point near the sound**, offset by up to ±(Manhattan distance ÷ 2.5) in x and y
   (`IsoZombie.java:1483-1485`). A zombie 300 tiles away aims up to ±120 tiles off. Blocked line of sight adds
   ±2–10 more (`:1506-1528`).
2. **Virtual zombies walk only `FollowSoundDistance` (default 100) tiles toward a sound, then stand**
   (native, confirmed; Native findings, point 6). From 850 tiles away they get about 100 tiles closer and stop.
3. **Virtual zombies aim off target too**: ±(Manhattan distance × 0.25) (point 5).
4. **Virtual zombies walk in straight lines with no path search** and stand still at the first wall, fence or
   river in the way (point 8). This is probably the biggest native cause of "lost".
5. **Unloading while not walking outdoors on the ground floor wipes the target** (`:384-386`).
6. Handing over on the loaded edge wipes the target (`ZombiePopulationManager.java:542-545`), but the DLL
   avoids handing walking units over on the edge, so this is a minor cause (corrected).

**Distracted by other noises**
- A zombie switches to a new sound if it is **more than twice as attractive** as the current one, or **any**
  audible sound once about 2 seconds (60 ticks) have passed since the current sound stopped
  (`IsoZombie.java:1440-1453`). Sounds last only 16 ticks (`WorldSoundManager.java:529`), so a single gunshot
  stops "holding" its listeners almost at once. A walking zombie re-paths only if the new sound is in a different
  direction (`:1486-1496`).
- Attraction is volume × falloff with distance, and drops further between rooms or between inside and outside
  (`WorldSoundManager.java:295-323`).
- **Virtual zombies:** a moving unit switches to the **nearest** active sound whose radius covers it, regardless
  of volume (Native findings, point 7). A standing one wakes for the first sound that covers it
  (point 3).

**Stuck in buildings and losing interest**
- When its path ends, a zombie goes idle with no goal. Vanilla rally groups, which would move it again, **never
  form inside buildings or in forest zones** (`ZombieGroupManager.java:74`, `:81-83`).
- A zombie within 4 tiles of a repeating sound it can see stops walking (`IsoZombie.java:1408-1416`).
- The "walking on the spot" detector fails the path when a zombie makes no progress, for example against
  furniture (`PathFindBehavior2.java:796`).
- Indoor zombies that unload are stored as standing, so they lose their goal (`ZombiePopulationManager.java:384`).
- Doors and windows become thump targets, which stops the walk.

---

## Tests I need you to run

Single player, debug, **God Mode only (Invisible off)**. Each command is one console line under 256 characters.
Run them in a throwaway save; T3 and T4 can pull many zombies to you.

**Setup (once).** Turn on population logging: create
`%USERPROFILE%\Zomboid\messaging\Trigger_Zombie.xml` containing exactly:

```xml
<zombieTriggerXmlFile><setDebugLoggingEnabled>true</setDebugLoggingEnabled><bDebugLoggingEnabled>true</bDebugLoggingEnabled></zombieTriggerXmlFile>
```

The game watches that file in debug mode (`ZombiePopulationManager.java:194`). After that, `console.txt` gets
`ZPOP:` lines. Open the population map with:

```
ZombiePopulationWindow.OnOpenPanel()
```

On that map, right-click a cell for Clear Zombies, Set Time To Zero, Spawn Now, World Sound (100), Teleport Here
and the Display options. Cell coordinates are world ÷ 256.

**T1: are arrivals converted from virtual, and do they lose their target at the edge? (items 1, 2, parked)**
Stand on open ground at the edge of town and **don't move** (so no new chunks load). Fire one rifle shot. Let the
zombies arrive (fast-forward is fine). Then search `console.txt` for `ZPOP:`.
- `virtual -> real` lines = moving virtual zombies became real with their target. That confirms conversion.
- `unloaded -> real` lines while you stood still = arrivals that were created **standing**: units that reached
  their (scattered) target or were blocked inside your loaded area. Per the native pass, the edge wipe should
  be rare.
- Expected from the native pass: only zombies that started within about 100 tiles of your loaded area
  arrive (the `FollowSoundDistance` budget), and they arrive in clumps.
- If ZombieBuddy's Watches work in your setup, also run these first; each call is then logged with its
  arguments (path target `-2147483648` = none):

```
ZombieBuddy.Watches.Add("zombie.popman.ZombiePopulationManager","addZombieMoving")
```
```
ZombieBuddy.Watches.Add("zombie.popman.ZombiePopulationManager","addZombieStanding")
```

**T2: how far a virtual zombie follows a sound (item 2).** Put a sound 400 tiles east with radius 300, then
watch the moving dots in the population window for 1–2 in-game hours:

```
local p=getPlayer();getWorldSoundManager():addSound(p,math.floor(p:getX())+400,math.floor(p:getY()),0,300,100)
```

- Expected (native pass): dots far from the sound stop after about 100 tiles, move in straight lines, travel
  in clumps, and stop at obstacles. Dots within 10 tiles of the sound don't react.
- Repeat in a new save with Follow Sound Distance = 1000: they should get about 10× further.
- This is now a confirmation test. Run it if you want to see it with your own eyes, or skip it.

**T3: what `setAggroTarget` pulls (item 2).**

```
local p=getPlayer();setAggroTarget(0,math.floor(p:getX()),math.floor(p:getY()))
```

Expected (native pass): **nothing happens** on its own, because aggro only steers zombies that are already
moving. To see it work, first get dots moving with the T2 sound 250 tiles away, then set the aggro point on
yourself. Moving dots within 300 tiles should turn toward you, and dots farther away should keep going.
Then clear it:

```
setAggroTarget(0,-1,-1)
```

**T4: `createHordeFromTo` (items 2, 8).** Note the target cell's Effective Population first, then:

```
local p=getPlayer();local x,y=math.floor(p:getX()),math.floor(p:getY());createHordeFromTo(x+300,y,x,y,50)
```

- Do 50 moving dots appear 300 tiles east?
- Do they arrive?
- Does Effective Population rise by 50?
- Are they still there after save, quit and reload?

**T5: unvisited cells (item 8).** Pan the map to a cell you have never been near (for example Louisville).
- Expected (native pass): no dots and Effective 0 (or the cell is not shown at all) until something touches
  it. Then right-click it → World Sound (100): the cell should fill with dots at once.
- If dots are already there, either the cell was touched earlier (by a big gunshot, for example) or the
  window itself loads cells. Tell me which.

Then right-click → Spawn Now and note what changes. Then right-click → Clear Zombies and check whether the numbers
come back over time (respawn).

**T6: does per-zombie ModData survive becoming virtual? (item 5).** Tag every real zombie around you:

```
local l=getCell():getZombieList();for i=0,l:size()-1 do l:get(i):getModData().th=1 end;print("tagged",l:size())
```

Teleport 300+ tiles away (map → Teleport Here), wait one in-game hour, come back, then run:

```
local n,l=0,getCell():getZombieList();for i=0,l:size()-1 do if l:get(i):getModData().th then n=n+1 end end;print("tagged",n,"of",l:size())
```

0 = lost (expected).

**T7 (optional, needs ZombieBuddy started in experimental mode, `-agentlib:zbNative=experimental --`): does
`persistentId` survive?**

```
local z=getCell():getZombieList():get(0);print(zbget(z,"persistentId"),z:getX(),z:getY())
```

Leave and return as in T6, then run this, replacing `ID` with the number you saw:

```
local l=getCell():getZombieList();for i=0,l:size()-1 do local z=l:get(i);if zbget(z,"persistentId")==ID then print("found",z:getX(),z:getY()) end end
```

**T8: one long path order and the hand-off (item 3).** Send one zombie 400 tiles east:

```
local z=getCell():getZombieList():get(0);z:pathToLocation(math.floor(z:getX())+400,math.floor(z:getY()),0);print(z:getX(),z:getY(),z:getCurrentStateName())
```

- It walks off and a moving dot continues east on the map = the order survives into the virtual state.
- It stands still or turns idle = the pathfinder refuses far targets, so orders must be chained in shorter hops.

**T9: what interrupts an order (item 3).** Repeat T8 with `+60` instead of `+400`, then shout (Q) or fire from
the opposite side. Note whether the zombie turns.

**T10: scale (item 9).** On open ground, read the frame rate:

```
print(getAverageFPS())
```

Then spawn 200 real zombies 20–40 tiles away and read it again:

```
local p=getPlayer();local x,y=p:getX(),p:getY();spawnHorde(x+20,y+20,x+40,y+40,0,200)
```

Then time one round of path orders to every real zombie:

```
local t=getTimestampMs();local l=getCell():getZombieList();for i=0,l:size()-1 do local z=l:get(i);z:pathToLocation(math.floor(z:getX())+30,math.floor(z:getY()),0) end;print(l:size(),getTimestampMs()-t,"ms")
```

Repeat at 500 and 1,000 zombies, with Viewpoint on and off. Send me the FPS and ms figures, and whether the
zombies visibly start moving late (pathfinder queue).

**T11: switching off vanilla rally groups (item 4).**

```
getCore():setZombieGroupSound(false)
```

Idle zombies should stop clumping around leaders. Turn it back on with `getCore():setZombieGroupSound(true)`.

**T12 (later, multiplayer): ownership.** On a client of a dedicated server, with two players apart:

```
local r,l=0,getCell():getZombieList();for i=0,l:size()-1 do if l:get(i):isRemoteZombie() then r=r+1 end end;print("remote",r,"of",l:size())
```

---

## What is realistic to build, and what is not

**Realistic**
- A **server-side horde record** (global ModData) with an ID, an estimated position, heading, destination along
  `Nav` roads, a head-count and a mode (wandering, drawn to a sound, chasing), working in SP and MP.
- **Formation from real causes**: sounds (`OnWorldSound`), zombies that have a player target, clusters of
  zombies, and optionally animals (via a mod-made sound).
- **Real-zombie hordes near players** that the mod fully controls: its own leader, members trailing behind in a
  column along the road, groups merging, groups that keep walking instead of idling, off-road pulls through
  fields and forest, all with vanilla rally switched off. Plenty of stragglers left outside any horde.
- **Moving far hordes "on paper"** and materialising them as virtual or real zombies when a player approaches,
  thinning the places they left (subject to the respawn and counting limits in item 8).
- **Nudging virtual zombies** with mod-made world sounds or aggro points. Coarse: it affects every zombie in
  range, not just horde members. Per the native pass:
  - a sound wakes only standing zombies (more than 10 tiles away) and moves them at most `FollowSoundDistance`
    tiles;
  - an aggro point steers only moving zombies within 300 tiles.
- With a Java part: exact membership by `persistentId`, a census of virtual zombies (at least in SP/host), and
  removal of the vanilla edge and scatter causes of lost zombies.

**Not realistic**
- Giving an *existing* virtual zombie its own destination or horde, or reading virtual positions, from Lua.
- Changing how virtual zombies move (road preference, pace, sound choice, straight lines): it is inside
  `PZPopMan64.dll`. The native path search can only be switched on through the Last Stand flag, which also
  sets every chunk's natural population target to 0 (`0x18000c0c0` returns 0 when it is set), so it is not
  a usable switch.
- A conserved total with vanilla respawn left on, or in MP with server culling left at its default.
- Thousands of **real** zombies moving together on screen. SP will hit frame rate (T10 will say where); MP culls
  above about 300 per client area.
- Steering client-owned real zombies purely from the server in MP; the owning client has to do it.
- Keeping per-zombie data (ModData, groups, targets) on zombies through the virtual state.
