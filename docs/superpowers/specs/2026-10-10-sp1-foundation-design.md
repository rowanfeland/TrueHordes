# Sub-project 1: foundation and tools (design)

Parent design: `design.md` (sections 2, 4, 7). Decisions it rests on: D12 (ZombieBuddy required), D14 (Java core,
Lua helper). Approved in conversation on 2026-10-10.

## Goal

The base every later sub-project plugs into: a loadable mod with a Java core on ZombieBuddy, a Lua helper, a
lifecycle and update loop driven by game time, a versioned save file, logging and timing, a debug map, and an
offline test bench and build. **No gameplay.** Placeholder entities stand in for zombies so drawing, saving and timing
can be tested now.

## Out of scope

Terrain (sub-project 2), taking over the vanilla population (3), real wandering or hordes (4), stimuli (5),
real zombies (6), consequences (7), multiplayer behaviour beyond loading safely on a server (8).

## 1. Repository and folder layout

The `TrueHordes` folder becomes a local git repository (branch `main`, no remote yet).

```
TrueHordes/
  design.md, TrueHordes - *.md        project design and research (unchanged)
  docs/superpowers/specs|plans/       per-sub-project specs and plans
  mod/                                the Workshop item, copied as-is for testing
    workshop.txt, preview.png
    Contents/mods/TrueHordes/
      common/                          (B42 requires it; may stay nearly empty)
      42/
        mod.info
        poster.png, icon.png
        sandbox-options.txt
        media/java/TrueHordes.jar      build output (not committed)
        media/lua/shared/TrueHordes/   Lua helper (core wrappers, console commands)
        media/lua/client/TrueHordes/   debug map window
        media/lua/shared/Translate/EN/Sandbox.json
  java/
    src/truehordes/                    Java core (package = javaPkgName)
    test/                              offline tests and the test runner
  tools/
    build.ps1                          compile, test, package
    deploy.ps1                         copy mod/ to %USERPROFILE%\Zomboid\Workshop\TrueHordes
  .gitignore                           build output, jar, scratch files
```

`mod.info`: `id=TrueHordes`, `require=\ZombieBuddy`, `incompatible=WanderingZombies,WanderingZombiesWIP`,
`javaJarFile=media/java/TrueHordes.jar`, `javaPkgName=truehordes`, `ZBVersionMin=2.3.0`, `versionMin=42.21`.

## 2. Java core

Package `truehordes`. ZombieBuddy only scans the `javaPkgName` package itself for patch classes, so patch
classes live directly in `truehordes`; everything else can use sub-packages.

| Unit | Purpose |
|---|---|
| `Main` | ZombieBuddy entry. Logs version and mode (single player, dedicated server, MP client). Runs the hook-target self-check; if a required target is missing, the mod switches itself off and says why. |
| `Core` (lifecycle) | States: idle → running → stopping. Starts when a world has loaded, stops on exit to menu or server shutdown. Every entry from the game is wrapped so nothing throws into the game. |
| `SimClock` | Simulation time from in-game time (world age hours), not wall time. Respects pause and game speed. Gives each update the elapsed game time. |
| `Scheduler` | Runs registered systems at a chosen cadence (every tick, every N game minutes) and supports per-object buckets (near objects often, far ones rarely) for sub-project 4. Records time spent per system. |
| `Store` | Entity registry: stable mod IDs, entity records, and a placeholder entity type for testing. |
| `SaveFile` | The mod's own save file in the current save folder (`truehordes/state.bin` via `ZomboidFileSystem.getFileNameInCurrentSave`). Versioned header, write to a temp file then rename, keep one backup, upgrade hook for older versions, and a clear log message if the file is unreadable (the mod starts empty rather than crashing). |
| `Config` | Reads the mod's sandbox options from `SandboxOptions` (`getOptionByName`). |
| `Log` | `[TrueHordes]` lines in `console.txt`, levels, switchable debug categories, and "log each kind of error once". |
| `Stats` | Counters and per-system timings for `TH_stats()`. |
| `LuaApi` | Exposed to Lua as one table, `TrueHordesJ`, through ZombieBuddy's Lua-class exposure (works whatever the load order). Values cross as Kahlua types and are checked defensively. |

**Ticking and saving.** The update is driven from the game's main loop and the save from the game's save path.
Whether that is a ZombieBuddy patch on a game method or a Lua event calling into Java is settled in the plan, after
checking the exact targets (`OnSave` fires from `GameWindow.java:1112`/`:1181`; `OnPostSave` from `:888`). Everything
runs on the main thread, except a dedicated server's shutdown hook, which saves and stops on its own thread;
the core's entry points are therefore synchronized.

## 3. Sandbox options

Page "True Hordes": `TrueHordes.Enabled` (boolean, default true), `TrueHordes.DebugLogging` (boolean, default
false). JSON translations in `Sandbox.json`.

## 4. Lua helper

- `media/lua/shared/TrueHordes/TH_Core.lua`: checks that `TrueHordesJ` exists and warns once if it doesn't
  (ZombieBuddy missing or jar not loaded). Defines the console commands as thin wrappers:
  - `TH_status()`: Java loaded, version, mode, enabled, entity count, last save time;
  - `TH_stats()`: entity count and milliseconds per system per tick;
  - `TH_debug(category, on)`: switch a debug log category;
  - `TH_save()`: force a save of the mod's state now;
  - `TH_seedTest(n)`: add `n` placeholder entities scattered around the player (test only);
  - `TH_clearTest()`: remove placeholder entities;
  - `TH_map()`: open or close the debug map.
- `media/lua/client/TrueHordes/TH_DebugMap.lua`: a movable window that draws the mod's entities on a map (pan,
  zoom, player position). When zoomed out it switches to a density view per area so thousands of dots don't slow
  the UI. It fetches a snapshot from Java no more than a few times a second.

## 5. Offline tests and build

- Plain Java tests with a small runner (no external libraries to download): save/load round trip, unreadable-file
  fallback, version upgrade hook, `SimClock` (pause, speed), `Scheduler` cadence and buckets, `Store` ID stability.
- `VerifyTargets`: checks every method the mod hooks exists in the installed `projectzomboid.jar`, so a game
  update is caught before testing in game.
- `tools/build.ps1`: compiles with JDK 25 (`%USERPROFILE%\.jdks\jdk-25.0.4.1+1`) against `projectzomboid.jar` and
  `ZombieBuddy.jar`, runs the tests (build fails if they fail), packages `TrueHordes.jar` into `mod/`.
- `tools/deploy.ps1`: copies `mod/` to `%USERPROFILE%\Zomboid\Workshop\TrueHordes` (only when run, never
  automatically).

## 6. Save consistency (user requirement)

The mod will own every zombie's position, so its save must never disagree with the game's save, in single player or
on a server.

- **Saved with the world, not on its own schedule.** The mod writes its state in the same save cycle as the game,
  so the game's save and the mod's save describe the same moment. B42 has two kinds of world save (found in the
  final review of sub-project 1):
  - **full saves** (quit, exit to menu, sleep, Lua `saveGame()`, the server's "save world every N minutes"):
    hooked at `MapCollisionData.save`, always written;
  - **hot saves** between full saves (single player: after each batch of chunk saves, `ChunkSaveWorker`
    gathers game time on the main thread via `GameTime.saveToBufferMap`; dedicated server: game time queued every
    600 s via `ServerChunkLoader.saveLater`): written at most every 20 real seconds so the main thread does not hitch.
  Sub-project 3 must still reconcile vanilla chunk saves (zombies inside saved chunks) with the mod's file on load.
- **Only the machine that owns the simulation saves.** Single player: the player's game. Dedicated server: the
  server, into the server's save folder. MP clients never write simulation state.
- **Atomic and recoverable.** Write to a temporary file, then rename over the old one; keep the previous file as a
  backup. A half-written file can never replace a good one.
- **Matched to the world save.** Each mod save records the game time it was taken at. On load, the mod compares it
  with the world's game time and logs a warning if they differ (for example after a crash between the two writes).
  The mod then uses the newest valid file instead of failing.
- **Complete records.** Every entity is saved with its position and everything needed to restore it exactly; a
  round-trip test checks that loading a save and saving it again gives identical data.
- **Conservation check on load and save.** Entity counts are logged; any unexplained difference is reported.
  Sub-project 4 extends this to zombie conservation.

## 7. Error handling

- Required hook target missing: the mod disables itself, logs why, and `TH_status()` shows it.
- Java exception during an update: logged once per kind, that system is skipped for the tick, the game continues.
- Save file unreadable: logged, the bad file is kept aside, the mod starts empty.
- Lua side without Java: one warning; the console commands print that Java isn't loaded.
- On an MP client, the Java core stays idle (the simulation will be server-side; sub-project 8).

## 8. Done when

1. `tools/build.ps1` passes its tests and produces the jar; `tools/deploy.ps1` copies the mod.
2. In single player debug, with ZombieBuddy, `TH_status()` reports Java loaded, version and mode.
3. `TH_seedTest(1000)` shows on `TH_map()`, and `TH_stats()` shows per-system timings.
4. Save, quit to menu, reload: the placeholder entities are still there; `TH_clearTest()` removes them.
5. Optional at this stage: a dedicated server loads the mod without errors (idle; nothing simulated yet).
