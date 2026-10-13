# Sub-project 2 addendum: roads and trails from map files - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (native, per the user). Steps use `- [ ]`.

**Goal:** Add the roads and trails that `Nav` zones miss, read from each map folder's `streets.xml` and `worldmap.xml`, as `ROAD` and a new `TRAIL` terrain.

**Architecture:** A pure parser `truehordes.world.RoadFiles` turns both files into `ZoneShape`s on the build thread; `GridBuilder` adds them to the zone list before rasterising. `WorldSource` gains the map folder list (snapshotted by `GameEnvImpl`). No game imports in `truehordes.world`.

**Tech Stack:** Java 25, `javax.xml.stream` (StAX, in the JDK), the repo's test runner, Kahlua Lua for one colour.

**Spec:** `docs/superpowers/specs/2026-10-10-sp2-world-knowledge-design.md` section 9 (addendum).

**Plan style (user's token rule):** exact interfaces, file lists and test cases are given here; code is written once, test first, during execution.

## Global Constraints

- Same as the sub-project 2 plan: `--release 25`, no `zombie.*` imports in `truehordes.world`, hooks never throw, build with `powershell -ExecutionPolicy Bypass -File tools\build.ps1`.
- Do not stage the user's uncommitted `design.md`, `docs/TESTING.md`, `Hordes_DebugMap.lua` edits unless this plan changes them; doc edits at the end are left uncommitted with theirs.
- XML parsing must not resolve external entities (set `XMLInputFactory.SUPPORT_DTD` false, `IS_SUPPORTING_EXTERNAL_ENTITIES` false).

## Review Focus

- A modded map folder without `streets.xml`/`worldmap.xml` must build normally (missing files skipped, counted). Test: `RoadFilesTest.testMissingFilesAreSkipped`.
- A truncated or malformed XML file must keep the shapes read before the error and never stop the build. Test: `RoadFilesTest.testBrokenXmlKeepsEarlierShapes`.
- World-map coordinates are cell-local in 300-tile cells; a wrong conversion silently misplaces every road. Test: `RoadFilesTest.testWorldMapCellOffset` and the real-map spot checks.
- A trail inside a town must stay town (priority), a trail through forest must be trail. Test: `ZoneRasterTest.testTrailPriorityAndAnyTile`.
- Folders listing both copies of the same road must not break anything (duplicates are harmless: same terrain). No test needed.

---

### Task 1: TRAIL terrain and any-tile rule for trails

**Files:** modify `java/src/truehordes/world/Terrain.java`, `java/src/truehordes/world/ZoneRaster.java`; tests in `ChunkInfoTest`, `ZoneRasterTest`.

**Interfaces:** `Terrain.TRAIL` (code 9, zonePriority between TOWN and FARM, baseCost 1.5f); `public final boolean anyTile` on `Terrain` (true for ROAD and TRAIL); priorities become ROAD 7, TOWN 6, TRAIL 5, FARM 4, DEEP_FOREST 3, FOREST 2, OPEN 1. `ZoneRaster` uses `z.terrain.anyTile` instead of `== ROAD`.

- [ ] Tests first: `ChunkInfoTest.testTrailTerrain` (classify a chunk with zone TRAIL → TRAIL; cost 1.5 when fully open; TRAIL priority below TOWN and above FARM); `ZoneRasterTest.testTrailPriorityAndAnyTile` (a 2-tile-wide trail at x 14..15 marks chunk 1 (any tile); the same trail inside a TOWN rect stays TOWN; inside a FOREST rect becomes TRAIL). Run: compile failure (TRAIL missing).
- [ ] Implement; run: all pass.
- [ ] Commit `SP2 roads: TRAIL terrain, trails and roads mark chunks on any tile`.

### Task 2: `RoadFiles` parser

**Files:** create `java/src/truehordes/world/RoadFiles.java`, `java/test/truehordes/test/RoadFilesTest.java`; add the suite to `TestRunner`.

**Interfaces:**
- `public static final class RoadFiles.Result { public final List<ZoneShape> shapes; public int streets, worldRoads, trails; public final List<String> problems; }`
- `static Result read(List<Path> mapFolders)`: for each folder, `streets.xml` then `worldmap.xml`; a missing `worldmap.xml` with `worldmap.xml.bin` present adds problem `"<folder>: binary world map only, skipped"`; a missing file otherwise is silent; a parse error adds `"<file>: <error>"` and keeps shapes already read.
- `static void readStreets(InputStream, Result)`, `static void readWorldMap(InputStream, Result)` (package-private, used by tests).
- Streets: polyline, points rounded to int tiles, width `max(1, round(width))` (missing width → 4). World map: `highway` primary/secondary/tertiary → ROAD, trail → TRAIL, other values ignored; Polygon with ≥ 3 points → polygon; LineString with ≥ 2 points → polyline of `width` property (rounded, ≥ 1) or 4; coordinates `cell * 300 + local`, rounded.

- [ ] Tests first (`RoadFilesTest`), using in-memory XML strings and temp folders:
  - `testStreetBecomesWidePolyline` (street width 6 from (100,200) to (140,200): contains (120,202), not (120,204); `streets == 1`);
  - `testWorldMapCellOffset` (cell x=2 y=3, Polygon local (10,10),(20,10),(20,20),(10,20) tertiary → contains tile (615,915), not (605,905); `worldRoads == 1`);
  - `testTrailAndLineStringWidth` (LineString trail with no width → polyline width 4 is TRAIL; LineString secondary with width 8 → ROAD covering 3.5 tiles off the line; `trails == 1`);
  - `testOtherHighwaysAndFeaturesIgnored` (`highway="footway"`, `water="river"`, `building="yes"` → no shapes);
  - `testBrokenXmlKeepsEarlierShapes` (two streets then truncated text → 2 shapes, 1 problem naming the error);
  - `testMissingFilesAreSkipped` (empty folder → 0 shapes, 0 problems; folder with only `worldmap.xml.bin` → 1 problem containing "binary world map only");
  - `testReadsBothFilesFromFolders` (temp folder with both files → counts from both).
- Run: compile failure. Implement with StAX (DTD and external entities off). Run: pass.
- [ ] Commit `SP2 roads: streets.xml and worldmap.xml parser`.

### Task 3: Build integration and report

**Files:** modify `WorldSource.java` (add `public final List<Path> mapFolders`; new 8-argument constructor; the existing 7-argument constructor delegates with an empty list), `GridBuilder.java` (read `RoadFiles` before rasterising, add shapes to the zone list, fill the report), `BuildReport.java` (fields `streets`, `worldRoads`, `trails`, `List<String> roadProblems`), `World.java` (report text: ` | roads from files: N streets, M world-map roads, T trails` plus ` (skipped: ...)` when there are problems); tests in `GridBuilderTest`, `WorldTest`.

- [ ] Tests first: `GridBuilderTest.testRoadsFromMapFolder` (empty cell file plus a folder with a `streets.xml` crossing chunk (2,2) → `terrain(2,2) == ROAD`, report `streets == 1`); `GridBuilderTest.testTrailFromWorldMap` (trail polygon → TRAIL chunk); `WorldTest.testReportListsRoadFiles` (report contains `roads from files: 1 streets`). Run: compile failure.
- [ ] Implement; run: pass. Commit `SP2 roads: build reads map folders' road files; report`.

### Task 4: Game bridge, debug colour, real-map checks

**Files:** modify `GameEnvImpl.java` (pass `Paths.get(mf.mapDirectoryAbsolutePath)` for each `IsoLot.MapFiles` entry, in order), `Hordes_DebugMap.lua` (colour `[9] = { 0.50, 0.35, 0.20 }` trail), `RealMapTest.java` (pass the map folder; check (548, 9244) ROAD, (92, 9436) ROAD, (332, 9596) TRAIL; print the road counts and the build time).

- [ ] Real-map test first (fails: those chunks are OPEN/UNKNOWN without the files' roads... run and confirm failure on the new checks only), then wire `RealMapTest` folders (the test itself supplies the folder, so the failure proves the build reads it once `GridBuilder` does: already done in Task 3, so this run is the integration check; record the result). Bridge and Lua: compile and `LuaSyntaxTest`.
- [ ] Commit `SP2 roads: game map folders, trail colour, real-map checks`.
- [ ] Docs (left uncommitted with the user's): `design.md` sub-project 2 row (roads from files, new build time), `docs/TESTING.md` SP2 row 3 (trails brown; rural roads continue). Deploy with `tools\deploy.ps1`. Final self-review of the range.
