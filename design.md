# True Hordes: design

Project Zomboid Build 42.21 mod. Single player first, then dedicated multiplayer.
Status: **approved by the user (2026-10-10). Sub-project 1 built and merged; its in-game acceptance and the user's
tests (section 9) are pending. Sub-project 2 starts when the user says so.** Progress per sub-project: section 7.
Each sub-project (section 7) gets its own design → plan → build cycle (Superpowers workflow, per the user's
global CLAUDE.md).

Source documents in this folder:
- `TrueHordes - Limitations Report.md`: what vanilla allows, with Java and native evidence and tests T1–T12.
- `TrueHordes - Research 2 - Off-screen Simulation.md`: existing horde mods, map collision data, absorbing native
  zombies, thunder, lights, generators, off-screen damage, tests T13–T14.
- `TrueHordes - Decisions.md`: dated decision log (this document supersedes it as the single reference).
- `docs/superpowers/specs/` and `docs/superpowers/plans/`: each sub-project's spec and implementation plan
  (sub-project 1: `2026-10-10-sp1-foundation-design.md`, `2026-10-10-sp1-foundation.md`).
- `docs/TESTING.md`: in-game acceptance checklists.

---

## 1. Vision

The Walking Dead feel. Every zombie on the map wanders, alone or in hordes, along roads and through forests,
fields and towns. Nowhere is safe. Hordes are not placed or scripted: they **happen**.
- Zombies wander until something changes their course: a sound, a horde they can see, another zombie they bump into.
- Zombies that bump into each other form a group. Individuals that see a horde of about 10 or more go to join it.
  Hordes that meet merge into bigger hordes. Hordes can split.
- A loud enough sound turns a horde, wholly or partly depending on how far away it is. Individuals converging on
  the same sound meet on the way and form new hordes.
- Over in-game weeks, a town can empty into a roaming horde. There are still plenty of stragglers.

### The scenario we are aiming for

> You're in your house in the woods. A thunderstorm is over you; lightning strikes and thunder roars nearby. You lie
> down to sleep, and suddenly hear the moans: one, then five, then a hundred, all wandering past your house. They
> haven't noticed you yet; they're too busy following the sound. A few bang on windows and doors, break some, then
> give up. You didn't make too much noise. The herd passes, and you live to fight another day.

Nowhere is safe, and none of it is scripted: it happens naturally from wandering, stimuli and hordes forming.

### Success looks like
- A player in a quiet house sees a horde come down the road with nothing scripted bringing it there.
- A gunshot in town draws in the town's stragglers and part of a passing horde, and a new horde leaves afterwards.
- A town left alone for weeks has changed: emptier in some places, a horde parked in others.
- Performance stays playable at the measured real-zombie budget.

---

## 2. Constraints and workflow

- Build 42.21. B42 layout: `42\` and `common\`, `mod.info`, `sandbox-options.txt`, JSON translations.
- Work in `Desktop\Zomboid Mods Personal\TrueHordes`; copy to `%USERPROFILE%\Zomboid\Workshop\TrueHordes` for testing.
- Never edit under `C:\Program Files (x86)\Steam\...`. Do not copy code from other mods (reading their docs for
  ideas is fine). If something cannot be done, say so.
- **ZombieBuddy (Workshop 3619862853) is a required dependency.** Java code lives in the mod's own jar, built with
  JDK 25 (`%USERPROFILE%\.jdks\jdk-25.0.4.1+1`) against `projectzomboid.jar` and `ZombieBuddy.jar`, following
  ViewpointTrueBallistics' layout (patch classes directly in the `javaPkgName` package, wrapped hooks that never
  throw into the game, a Lua API, unit tests and a patch-target check). On a dedicated server, ZombieBuddy and the jar
  must be installed server-side too.
- Single player first; dedicated server after the single-player mod works.
- Declared incompatible with Wandering Zombies (`WanderingZombies`, `WanderingZombiesWIP`).
- **Git:** the TrueHordes folder is a git repository published to GitHub (`origin`, branch `main`). Each piece of work
  goes on its own branch (one per sub-project, e.g. `sp1-foundation`). When work is done, Claude says so; the user
  reviews and merges in GitHub Desktop. Claude does not merge or push on its own, and does not hand over merge steps.
- **Build and test:** `tools\build.ps1` (compile with JDK 25, run the offline tests, package the jar; fails if any
  test fails); `tools\deploy.ps1` copies `mod\` into the Workshop folder. In-game checks: `docs/TESTING.md`.
- **Execution style:** foundations that offline tests can catch are built inline ("native"); actual gameplay systems
  use subagent-driven development (a fresh implementer and reviewer per task), as the user prefers.

---

## 3. Decisions

| # | Decision | Date |
|---|---|---|
| D1 | **Full replace.** The mod owns every off-screen zombie, individuals as well as hordes. The game only handles real zombies near players. Vanilla off-screen zombies never wander (they stand until a sound wakes them, then walk a straight line), so they can't deliver the vision. | 2026-10-10 |
| D2 | **Everything wanders**, individuals and hordes, on and off roads. | 2026-10-10 |
| D3 | **Hordes form naturally**: bumping into each other; individuals going to join a visible horde of about 10+; hordes merging on contact; splitting. Exact rules are left open until the user adds them before sub-project 4 (Q1). | 2026-10-10 |
| D4 | **No pre-programmed routes.** Each wanderer or horde picks its next heading from its surroundings. Roads are cheaper to walk, so they get used more, but are never assigned. | 2026-10-10 |
| D5 | **A simulation manager, not a storyteller.** It does bookkeeping: scheduling, encounters, joining, merging, splitting, real/off-screen conversion, saving. It does not send hordes at players. | 2026-10-10 |
| D6 | **Real zombies near players: as many as the machine can handle**, measured (test T10) and then capped by a setting. | 2026-10-10 |
| D7 | **Zombies respond to sound off-screen.** Example: a 1,000-strong horde on the highway by Muldraugh is reached by a gunshot. It turns and heads for the source; once near there, the simulation manager has it decide where to wander next. | 2026-10-10 |
| D8 | **Hordes react by loudness, which fades with distance.** Far out only part of a horde breaks off (example given: about half in the outer 700–850 tiles of an 850-tile sound); the closer, the larger the share; inside a threshold, the whole horde goes. The rest stays a horde and keeps wandering. Numbers are tuning values. | 2026-10-10 |
| D9 | **Individuals (not in a horde) always respond to a sound they can hear**, out to the edge of its reach. Converging individuals meet and form hordes. | 2026-10-10 |
| D10 | **The mod owns individuals too.** They wander with the mod's logic until they see a horde and join it, or meet enough others to form one. | 2026-10-10 |
| D11 | **The mod has its own sound model with realistic distance falloff, across the whole map.** Loudness depends on the source and fades with distance like real life, instead of a flat radius where everything inside reacts equally. Examples: a gunshot is about town-wide; a helicopter is map-wide; storms are map-wide. It covers everything that moves zombies. See section 5. | 2026-10-10 |
| D12 | **ZombieBuddy Java is required**, not an optional companion. | 2026-10-10 |
| D13 | Sandbox `FollowSoundDistance` = 1000 (affects only vanilla off-screen behaviour; mostly superseded by D1 and D11). | 2026-10-09 |
| D14 | **Java core, Lua as helper.** The simulation lives in Java (ZombieBuddy): world knowledge, population store, update loop, saving, population takeover. Lua is the helper for game-facing glue: sandbox options, events, steering real zombies, debug UI, console commands, talking to Java through ZombieBuddy-exposed functions. Reason: performance at tens of thousands of zombies and whole-map terrain, and only Java can reach the save's collision data and the native population. The offline test bench is plain Java tests. | 2026-10-10 |
| D15 | **Idea, not final: structure damage scales with crowd size.** A lone zombie is slow to break in; a crowd is fast; a huge horde crushes through. Example numbers (not final): 1 zombie ≈ 5 in-game hours for a window, 24 for a door; 10 together ≈ 1 hour for a window, 5 for a door; 1,000 ≈ 10 minutes, zombies crushing themselves against doors and windows. Applies to real zombies near players (sub-project 6) and to off-screen wear (sub-project 7). | 2026-10-10 |

---

## 4. Architecture

Six layers. **Java is the core (D14)**: every layer below except the parts that must touch Lua-only game hooks.
Lua is the helper: sandbox options, events, steering real zombies, debug UI and console commands.

```
          sounds, thunder, lights, players                 map files + save
                      │                                         │
              ┌───────▼────────┐                       ┌────────▼────────┐
              │ Stimuli and    │                       │ World knowledge │  terrain cost, water width,
              │ sound model    │                       │                 │  walls, roads, zones
              └───────┬────────┘                       └────────┬────────┘
                      │  "what each zombie/horde perceives"     │ "how hard is it to walk here"
              ┌───────▼─────────────────────────────────────────▼───────┐
              │ Simulation manager (population store + update loop)     │
              │ individuals and hordes, wandering, encounters,          │
              │ join / merge / split, saving                            │
              └───────┬─────────────────────────────────────────┬───────┘
                      │ near players                            │ while away
              ┌───────▼────────┐                       ┌────────▼────────┐
              │ Materialisation│  real zombies,        │ Consequences    │  damage ledger, generators,
              │ bridge         │  their wandering      │                 │  corpses, applied on load
              └───────┬────────┘                       └─────────────────┘
                      │
              ┌───────▼────────┐
              │ Population     │  vanilla off-screen population switched off,
              │ takeover       │  initial population seeded from the map
              └────────────────┘
```

### 4.1 World knowledge
- Source: the map-wide collision grid in `chunkdata_X_Y.bin` (map folders) with the save's updated copies in
  `Saves\...\chunkdata\` (solid, wall north, wall west, water, room bits per tile), plus map zones (`Nav` roads and
  streets, Forest, DeepForest, FarmLand, TownZone…), building definitions and the zombie density map.
- Output: a coarse cost grid (for example 8×8-tile cells) for wandering and local avoidance, with tile-level detail
  available near players. Water classified by width (narrow = crossable at a cost, wide = blocking).
- Java reads the files (Lua can't read the save copies). Works on modded maps because they ship the same files.

### 4.2 Population store and simulation manager
- **Levels of detail by distance to the nearest player:**
  - far: stragglers as counts per area that drift between neighbouring areas; hordes as single objects
    (position, heading, size, composition);
  - mid-range: individuals as coordinates with outfit and state;
  - near: real game zombies (section 4.4).
- **Scheduling:** far objects are updated rarely in large steps, near ones often in small steps, so cost stays flat
  as the population grows. Hordes cost the same off-screen whatever their size.
- **Wandering:** each wanderer or horde picks its next heading from its current heading (momentum), terrain cost
  ahead, perceived stimuli, nearby hordes, and randomness. Local avoidance steers around walls, buildings and wide
  water. No long routes are planned in advance.
- **Encounters:** a spatial index finds who is near whom; joining, merging and splitting follow the rules from Q1.
- **Composition:** hordes keep what they're made of (outfits, crawlers, special zombies) so they give back the
  right zombies when they become real. Whether individual identities are kept (`persistentId`) is decided in
  sub-project 4.
- **Saving:** the mod's own save data, with conservation checks (no zombies appear or vanish except by death,
  rules or settings).

### 4.3 Stimuli and sound model
See section 5.

### 4.4 Materialisation bridge
- Off-screen ↔ real conversion near players, within the measured budget (D6). The criterion is distance, and
  possibly visibility (the engine's own "can the player see this" test), decided in sub-project 6.
- Real zombies get the mod's wandering through path orders (`pathToLocation`, integer version), within a per-tick
  budget; real hordes walk as a column behind their leader. Vanilla rally groups are switched off.
- Leaving range turns a real zombie back into the mod's data, keeping its outfit, state and heading.

### 4.5 Population takeover
- The vanilla off-screen population (`PZPopMan64.dll`) is switched off or emptied, and the mod seeds its own
  population from the map's density, starting conditions and sandbox settings. Respawn behaviour is the mod's.
- Candidate methods (decided by a spike in sub-project 3): zero the native targets and spawn our own; absorb native
  zombies chunk by chunk through `n_loadChunk` (Research 2, section 4); or a mix. Every real zombie the game creates
  is caught and attributed to the mod's population (no duplicates).

### 4.6 Consequences while away
- A ledger of pending effects per chunk or structure, applied when the area loads: broken windows and doors, player
  structures worn down by their real HP, corpses and blood.
- Generators: predicted run-out time from fuel and consumption recorded at unload; a lit base can hold a horde until
  then.

### 4.7 Multiplayer (after single player)
- The simulation manager runs on the server. Real zombies near players are steered by their owning client, ordered by
  the server. ZombieBuddy and the jar are installed on the server. The vanilla zombie cull is reviewed (the user will
  test it at 0).

---

## 5. Sound model (D7–D11)

The mod replaces "everything inside a radius reacts the same" with **loudness that fades with distance**.

- **Every source has a loudness** at the source: gunshots by weapon, vehicles, alarms, generators, helicopters,
  thunder, explosions, shouting, and so on. Sources are captured from the game:
  - world sounds through the game's sound event (`OnWorldSound` on every machine, and on the server for all player
    sounds) or a Java hook on `WorldSoundManager.addSound`;
  - the helicopter (it already adds a 500-radius world sound from its position, `Helicopter.java:208`);
  - thunder, which makes no world sound (`OnThunderEvent` in single player; a Java hook on the server).
- **Falloff like real life.** Perceived loudness drops with distance (for example by a fixed amount per doubling of
  distance), with extra loss through buildings and terrain if wanted. A zombie hears a sound if its perceived loudness
  is above a hearing threshold. **Reach** = the distance where it falls below that threshold. So a gunshot reaches
  about a town, a helicopter or a storm the whole map.
- **Who reacts and how much:**
  - individuals react whenever they can hear it (D9);
  - hordes send a share of themselves that grows with the perceived loudness: part of the horde near the edge of
    reach, all of it above a threshold (D8);
  - a horde or wanderer already following a sound switches only for a clearly louder one (rule to tune).
- **After arriving**, the simulation manager has them settle, mill about, or wander on (D7).
- **Mapping from the game's radius:** the game expresses sounds as a radius (Louder Gunshots multiplies it, the
  Hearing setting scales it). The model converts radius into source loudness so modded and modified sounds work
  without a list. The conversion and per-source overrides are tuning.
- **Real zombies near players:** whether they keep vanilla hearing or use the same model is open (Q4).

---

## 6. Facts the design relies on

From the limitations report and Research 2 (42.21):
- Vanilla off-screen simulation is native (`PZPopMan64.dll`), server or single-player host only. Its zombies only move
  toward a sound or target, in straight lines, stopping at obstacles; path search is Last Stand only.
- Real zombies exist within about 76 tiles of a player at 1080p (19×19 chunks), less on smaller screens; on a server
  per player.
- Lua has no stable zombie ID; Java can read `IsoZombie.persistentId`. Per-zombie ModData is lost when a zombie goes
  virtual.
- `pathToLocation` (integers) always takes effect; `pathToLocationF` is ignored during the repath delay. A path whose
  target is unloaded keeps walking.
- `OnWorldSound` fires for every sound; the server sees all player sounds. Sounds under radius 50 never reached the
  native simulation (irrelevant once the mod owns sound).
- `Nav` zones (including streets) cover the whole map; the collision grid covers the whole map at tile level and
  includes player changes in the save's copy.
- Thunder events carry map coordinates; zombies ignore light natively; generators deduct off-screen fuel on reload.
- In MP, the nearest relevant client runs a real zombie's AI; the server culls outdoor, target-less zombies beyond
  `ZombiesCountBeforeDelete` (default 300) and deleted zombies are gone for good.
- Existing mods (Hordes B41/B42, Living Hordes) use paper hordes and are single-player only.

Learned while building sub-project 1 (verified against the decompiled 42.21 game and the final review):
- **Hooks that work in single player and on a dedicated server** (all names unique, checked against the installed
  jar by the offline tests and at start-up):
  - every frame / server frame: `ZombiePopulationManager.updateMain` (exit);
  - full saves (quit, exit to menu, sleep, Lua `saveGame()`, the server's "save world every N minutes"):
    `MapCollisionData.save` (exit), the same moment the vanilla population saves;
  - **B42 also hot-saves the world between full saves**: single player after each batch of chunk saves
    (`ChunkSaveWorker` gathers game time on the main thread via `GameTime.saveToBufferMap`); dedicated server every
    600 s (`ServerChunkLoader.saveLater`). The mod hooks both and writes at most every 20 real seconds;
  - world unloading: `ZombiePopulationManager.stop` (enter). The save always runs before the stop.
- **Threads:** everything runs on the main thread except a dedicated server's shutdown hook (Ctrl+C, panel stop),
  which saves and stops on its own thread. The core's entry points are synchronized for that reason.
- **Save folder:** `ZomboidFileSystem.getFileNameInCurrentSave` is correct at both start and save, including on a
  server and on the game's crash-recovery save; the mod's file is `<save>\truehordes\state.bin` (+ `.bak`).
- **Sub-project 3 must reconcile vanilla chunk saves** (zombies saved inside chunks continuously) with the mod's
  own file on load; hot saves narrow the gap but do not remove it.
- **Lua bridge:** ZombieBuddy exposes a Java class as a Lua table (`TrueHordesJ`, `@Exposer.LuaClass`); Lua calls its
  static methods with a dot. Kahlua tables take `Double` keys and `Boolean` values.
- **Lua can be syntax-checked offline** with the game's own compiler (`org.luaj.kahluafork.compiler.LexState` in
  `projectzomboid.jar`); the build does this for every mod Lua file.

---

## 7. Sub-projects

| # | Sub-project | Delivers | Depends on | Key risks / spikes |
|---|---|---|---|---|
| 0 | **Tests (user)** | Results of T1, T4, T6–T11, T13, T14 (T2, T3, T5 optional) | — | — |
| 1 | **Foundation and tools** | Mod skeleton (Lua + Java jar on ZombieBuddy), sandbox options scaffold, save data, logging, console commands, debug map/overlay of the mod's population, and an offline test bench that runs simulation logic outside the game | 0 | Build pipeline with JDK 25 |
| 2 | **World knowledge** | Collision grid reader (map + save copies), zones and roads, water width, coarse cost grid and queries | 1 | Memory and load time for the whole map; keeping up with player changes |
| 3 | **Population takeover** | Vanilla off-screen population switched off, mod population seeded, every real zombie caught and attributed | 1, (2) | **Spike first:** which takeover method is clean and safe (native internals); reconciling vanilla chunk saves with the mod's file (section 6) |
| 4 | **Simulation core** | Levels of detail, scheduling, wandering, local avoidance, encounters, join/merge/split, saving, conservation checks | 2, 3 | Q1 rules; tuning wandering so it looks natural |
| 5 | **Stimuli and sound model** | Loudness falloff model, source capture (world sounds, helicopter, thunder), horde share rule, individual response; lights and generators as stimuli | 4 | Q2–Q4; MP thunder needs Java |
| 6 | **Materialisation bridge** | Off-screen ↔ real conversion within budget, real wandering and columns, vanilla rally off, state kept both ways, crowd-scaled break-in speed for real zombies (D15) | 4 | Real-zombie budget (T10); pathfinder load; edge cases at the loaded boundary |
| 7 | **Consequences while away** | Damage ledger applied on load (crowd-scaled wear, D15), generator run-out, corpses | 4, 6 | Knowing player structures off-screen |
| 8 | **Multiplayer** | Server-side manager, owner-client steering, server ZombieBuddy install, cull settings, dedicated-server tests | 1–7 | Ownership changes; bandwidth |
| 9 | **Tuning and compatibility** | Sandbox options, performance scaling, Wandering Zombies incompatibility, Starving Zombies and Louder Gunshots behaviour, presets | all | — |

Each sub-project's design starts by re-reading this document and the relevant research sections.

### Progress

| # | Status | Notes |
|---|---|---|
| 0 | **Pending (user)** | In-game tests from section 9 |
| 1 | **Built and merged to `main` (2026-10-10); in-game acceptance passed in single player (rows 1, 3–7b; row 2 blocked by a `TH_` name clash with the user's True Hits mod, so the commands were renamed to `Hordes_`; rows 8–10 skipped by the user)** | 42 offline tests. Java core (lifecycle, game-time clock, scheduler with update buckets, entity store, crash-safe versioned save file matched to full and hot saves), ZombieBuddy hooks, Lua console commands (`Hordes_status`, `Hordes_stats`, `Hordes_debug`, `Hordes_save`, `Hordes_seedTest`, `Hordes_clearTest`, `Hordes_map`), debug map, build and deploy scripts. Acceptance: `docs/TESTING.md` rows 1–9 (and 7b). Deferred minor issues from its final review are listed below |
| 2 | Not started; begins when the user says so | |
| 3–9 | Not started | |

Deferred minor issues from sub-project 1's final review (fix when the code they touch is next changed):
- the scheduler's pending time is not reset between worlds (matters once systems run on an interval);
- a save file with duplicate entity IDs makes start-up retry every frame instead of falling back;
- the number of entities saved is not compared with the number loaded back;
- no test for upgrading older save formats yet (only format version 1 exists);
- if the game's `MapCollisionData.save` throws, the mod's full save is skipped (`@Patch.OnExit` default);
- the debug map draws outside its panel and stops dragging when the mouse leaves it;
- `Hordes_seedTest` has no upper limit;
- `Hordes_status` shows the previous world's load result after exiting a world.

---

## 8. Open questions

| # | Question | Needed by |
|---|---|---|
| Q1 | Joining and merging rules: bump distance; how far an individual can see a horde and the minimum size (about 10+); merge rules; what makes a horde split. **Left open on purpose:** the user adds these before sub-project 4 starts; do not invent them. | Sub-project 4 |
| Q2 | Stimuli other than sound, and their weights: thunder or lightning, base lights after the power goes off, players seen, other hordes | Sub-project 5 |
| Q3 | Sound numbers: loudness per source, falloff rate, hearing threshold, horde share curve, "switch to a louder sound" rule | Sub-project 5 (tuning) |
| Q4 | Do real zombies near players keep vanilla hearing, or use the mod's sound model? | Sub-project 5/6 |
| Q5 | Population start: how many zombies, where, and whether any arrive from the map edges over time | Sub-project 3 |
| Q6 | Is "real only if the player could see it" wanted near players, or distance only? | Sub-project 6 |
| Q7 | Crowd-scaled structure damage (D15): final numbers, the curve between 1, 10 and 1,000 zombies, and how it works for real zombies (vanilla thumping changed in Java) versus off-screen wear | Sub-projects 6, 7 |

---

## 9. Tests to run before execution

From `TrueHordes - Limitations Report.md` and `TrueHordes - Research 2 - Off-screen Simulation.md`:

| Test | Informs |
|---|---|
| T1 virtual → real conversion and arrival pattern | Sub-projects 3, 6 |
| T4 `createHordeFromTo` | Sub-project 3 |
| T6 ModData lost when virtual | Sub-projects 4, 6 |
| T7 `persistentId` survives (ZombieBuddy experimental mode) | Sub-projects 3, 4 |
| T8 one long path order and hand-off | Sub-project 6 |
| T9 what interrupts an order | Sub-project 6 |
| T10 scale: FPS and steering cost at 200 / 500 / 1,000 real zombies, Viewpoint on and off | D6, sub-project 6 |
| T11 vanilla rally groups off | Sub-project 6 |
| T13 can Lua read the collision files | Sub-project 2 |
| T14 thunder events | Sub-project 5 |
| T2, T3, T5 | Optional now (vanilla off-screen behaviour, superseded by D1) |
| T12 multiplayer ownership | Sub-project 8 |
