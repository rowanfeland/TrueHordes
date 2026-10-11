# Sub-project 2: World Knowledge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Java core a whole-map knowledge of walkability and terrain (coarse 8×8-tile grid plus tile detail on request), kept current with player changes, visible on the debug map and through `Hordes_world()`.

**Architecture:** A new package `truehordes.world` holds pure logic (file reader, chunk summaries, zone geometry, grid, builder, tile cache, `World` service) with no game imports, so all of it is tested offline. The game bridge (`GameEnvImpl`, `ZoneSnapshot`, `LiveBits`, two new ZombieBuddy patches) snapshots the map folders and zones on the main thread and feeds live tile changes. `Core` owns one `World`: starts its background build when the core starts, publishes the finished grid in `tick()`, drops it on `stop()`.

**Tech Stack:** Java 25 (`--release 25`, JDK at `%USERPROFILE%\.jdks\jdk-25.0.4.1+1`), ZombieBuddy 2.3.4 patches (`@Patch`, `@Patch.OnExit`, `@Patch.Argument`), Kahlua tables for Lua, Lua 5.1 (Kahlua) for the debug map. Tests: the repo's own runner (`java/test/truehordes/test/TestRunner.java`, `Check`), run by `tools\build.ps1`.

**Spec:** `docs/superpowers/specs/2026-10-10-sp2-world-knowledge-design.md` (parent: `design.md` sections 4.1, 7; decisions D4, D14, D16).

## Global Constraints

- Build 42.21; Java code compiled with `--release 25` against `projectzomboid.jar` and `ZombieBuddy.jar` by `tools\build.ps1`; the build fails if any test fails.
- Patch classes live directly in package `truehordes` (ZombieBuddy does not scan sub-packages). Everything in `truehordes.world` must not import any `zombie.*` or `se.krka.*` class.
- Hooks never throw into the game (wrap like `Hooks.onTick`).
- Never edit under `C:\Program Files (x86)\Steam\...`; only read the game's map files. Do not copy code from other mods.
- Water blocks everywhere (D16). Ground level (z = 0) only.
- Console commands use the `Hordes_` prefix (the user's True Hits mod owns `TH_`).
- Commit after each task on branch `sp2-world-knowledge` with the message ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Leave the final task's changes uncommitted for the user (their git workflow).
- Run the build from the repo root: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`. Expected last lines on success: `N passed, 0 failed` and `Built ...TrueHordes.jar`.

## Review Focus

- A save copy that is half-written (game saving while the build reads it) must fall back to the map copy, not leave the cell blocked. Test: `GridBuilderTest.testDamagedSaveCopyFallsBackToMapCopy` (Task 4).
- Exiting to the menu and loading another world while the first build is still running must never publish the old world's grid into the new one. Test: `WorldTest.testRestartIgnoresOldBuild` (Task 6).
- Tile changes that arrive before the grid is published must not be lost. Test: `WorldTest.testLiveChangeDuringBuildIsAppliedOnPublish` (Task 6).
- Negative or out-of-map coordinates in any query must answer "blocked" without throwing. Tests: `GridTest.testOutsideIsBlocked` (Task 4), `WorldTest.testQueriesOutsideMap` (Task 6).
- A road thinner than a chunk (a 4-tile lane along a chunk's edge) must still mark the chunk as road. Test: `ZoneRasterTest.testThinRoadMarksChunk` (Task 3).

---

## File Structure

New (pure, offline-testable), package `truehordes.world` in `java/src/truehordes/world/`:
- `CollisionBits.java`: tile bit and chunk type constants, `walkable(bits)`.
- `CellReader.java`: reads one `chunkdata_X_Y.bin` into 65,536 tile bytes; never throws.
- `ChunkInfo.java`: packs a chunk's summary (`short`) and border masks (`int`); edge tests.
- `Terrain.java`: terrain kinds, zone-type mapping, priority, placeholder costs.
- `Dir.java`: N, E, S, W.
- `ZoneShape.java`: immutable zone copy (rectangle, polygon, polyline) with the mod's own geometry.
- `ZoneRaster.java`: zones → one terrain code per chunk (road on any tile, others at the chunk centre, priority).
- `WorldSource.java`: snapshot of bounds, map files (first folder wins), save `chunkdata` folder, zones.
- `BuildReport.java`: counts, timing, memory, damaged files.
- `CellSource.java`: loads a cell's tiles (save copy first, map copy fallback).
- `Grid.java`: the coarse arrays and chunk-level queries.
- `GridBuilder.java`: builds a `Grid` from a `WorldSource`.
- `TileCache.java`: LRU of 64 cells' tiles.
- `TerrainSample.java`: debug-map terrain sample.
- `World.java`: the service: background build, publish, live overlay, tile queries, report, debug samples.

New game bridge in `java/src/truehordes/`:
- `ZoneSnapshot.java`: copies the game's zones to `ZoneShape`s (main thread).
- `LiveBits.java`: reflection on `MapCollisionData` (copy `squares`, call the rule methods).

Modified: `HookTargets.java`, `Patches.java`, `Hooks.java`, `Main.java`, `GameEnvImpl.java`, `core/GameEnv.java`, `core/Core.java`, `LuaApi.java`, `mod/.../lua/shared/TrueHordes/Hordes_Core.lua`, `mod/.../lua/client/TrueHordes/Hordes_DebugMap.lua`, `docs/TESTING.md`, `design.md`.

Tests in `java/test/truehordes/test/`: `FakeCells.java` (helper), `CellReaderTest`, `ChunkInfoTest`, `ZoneRasterTest`, `GridTest`, `GridBuilderTest`, `WorldTest`, `RealMapTest`; modified `TestRunner`, `FakeEnv`, `CoreTest`, `VerifyTargetsTest`.

---

### Task 1: Collision file reader

**Files:**
- Create: `java/src/truehordes/world/CollisionBits.java`
- Create: `java/src/truehordes/world/CellReader.java`
- Create: `java/test/truehordes/test/FakeCells.java`
- Create: `java/test/truehordes/test/CellReaderTest.java`
- Modify: `java/test/truehordes/test/TestRunner.java` (add `CellReaderTest.class` to `SUITES`)

**Interfaces:**
- Produces: `CollisionBits.{SOLID=1, WALL_N=2, WALL_W=4, WATER=8, ROOM=16, NO_DATA=0x80, NOT_WALKABLE, CHUNK_EMPTY..CHUNK_VOID, CELL_TILES=256, CHUNK_TILES=8, CHUNKS_PER_CELL=32}`, `static boolean CollisionBits.walkable(int bits)`; `CellReader.read(Path) -> CellReader.Result` (`byte[] tiles` or null, `String error` or null, `missing()`, `clean()`), `CellReader.parse(byte[])`, `CellReader.noData()`. Tile index in a cell = `y * 256 + x` (cell-local).
- Produces (tests): `FakeCells.cell()` → `FakeCells` builder with `chunk(int wx, int wy, int type)`, `tiles(int wx, int wy, byte[] tiles64)`, `byte[] bytes()`, `Path write(Path dir, String name)`.

- [ ] **Step 1: Write the test helper and failing tests**

`java/test/truehordes/test/FakeCells.java`:

```java
package truehordes.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes collision files in the game's chunkdata_X_Y.bin format for tests. */
public final class FakeCells {
    private final int[] types = new int[1024];
    private final byte[][] regular = new byte[1024][];

    private FakeCells() {}

    /** A cell whose 32x32 chunks are all empty (type 0). */
    public static FakeCells cell() { return new FakeCells(); }

    public FakeCells chunk(int wx, int wy, int type) {
        types[wy * 32 + wx] = type;
        return this;
    }

    /** A regular chunk (type 2) with these 64 tile bytes (index ty * 8 + tx). */
    public FakeCells tiles(int wx, int wy, byte[] tiles64) {
        types[wy * 32 + wx] = 2;
        regular[wy * 32 + wx] = tiles64.clone();
        return this;
    }

    public byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(1);
        for (int i = 0; i < 1024; i++) {
            out.write(types[i]);
            if (types[i] == 2) out.write(regular[i], 0, 64);
        }
        return out.toByteArray();
    }

    public Path write(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, bytes());
        return p;
    }

    /** 64 tile bytes all set to bits. */
    public static byte[] fill(int bits) {
        byte[] t = new byte[64];
        java.util.Arrays.fill(t, (byte) bits);
        return t;
    }
}
```

`java/test/truehordes/test/CellReaderTest.java`:

```java
package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import truehordes.world.CellReader;
import truehordes.world.CollisionBits;

public final class CellReaderTest {
    private static int at(byte[] tiles, int x, int y) { return tiles[y * 256 + x] & 0xFF; }

    public static void testUniformChunkTypes() {
        byte[] data = FakeCells.cell()
            .chunk(1, 0, CollisionBits.CHUNK_SOLID)
            .chunk(2, 0, CollisionBits.CHUNK_WATER)
            .chunk(3, 0, CollisionBits.CHUNK_ROOM)
            .chunk(4, 0, CollisionBits.CHUNK_VOID)
            .bytes();
        CellReader.Result r = CellReader.parse(data);
        Check.isTrue(r.clean(), "clean read: " + r.error);
        Check.equal(0, at(r.tiles, 3, 3), "empty chunk tile");
        Check.equal(CollisionBits.SOLID, at(r.tiles, 8 + 7, 7), "solid chunk tile");
        Check.equal(CollisionBits.WATER, at(r.tiles, 16, 0), "water chunk tile");
        Check.equal(CollisionBits.ROOM, at(r.tiles, 24 + 4, 4), "room chunk tile");
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 32, 0), "void chunk is no data");
    }

    public static void testRegularChunkTilesLandInPlace() {
        byte[] t = new byte[64];
        t[0] = (byte) CollisionBits.WALL_N;               // tile (0,0) of the chunk
        t[7 * 8 + 7] = (byte) (CollisionBits.WALL_W | CollisionBits.SOLID); // tile (7,7)
        CellReader.Result r = CellReader.parse(FakeCells.cell().tiles(31, 31, t).bytes());
        Check.isTrue(r.clean(), "clean read: " + r.error);
        Check.equal(CollisionBits.WALL_N, at(r.tiles, 248, 248), "first tile of last chunk");
        Check.equal(CollisionBits.WALL_W | CollisionBits.SOLID, at(r.tiles, 255, 255), "last tile of last chunk");
    }

    public static void testTruncatedFileKeepsWhatWasRead() {
        byte[] full = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).bytes();
        CellReader.Result r = CellReader.parse(Arrays.copyOf(full, 100));
        Check.isTrue(r.tiles != null && r.error != null, "tiles plus an error");
        Check.isTrue(r.error.contains("truncated"), "says truncated: " + r.error);
        Check.equal(CollisionBits.WATER, at(r.tiles, 0, 0), "chunk 0 kept");
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 255, 255), "unread chunk is no data");
    }

    public static void testWrongVersionIsNoData() {
        byte[] data = FakeCells.cell().bytes();
        data[1] = 7;
        CellReader.Result r = CellReader.parse(data);
        Check.isTrue(r.error != null && r.error.contains("version"), "version error: " + r.error);
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 0, 0), "all no data");
    }

    public static void testUnknownChunkTypeStops() {
        byte[] data = FakeCells.cell().chunk(5, 0, 9).bytes();
        CellReader.Result r = CellReader.parse(data);
        Check.isTrue(r.error != null && r.error.contains("chunk type 9"), "type error: " + r.error);
        Check.equal(0, at(r.tiles, 0, 0), "chunks before it kept");
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 40, 0), "chunk with the unknown type is no data");
    }

    public static void testMissingFile() throws Exception {
        Path dir = Files.createTempDirectory("th-cells");
        CellReader.Result r = CellReader.read(dir.resolve("chunkdata_1_1.bin"));
        Check.isTrue(r.missing(), "missing");
        Check.isTrue(CellReader.read(null).missing(), "null path is missing");
    }

    public static void testReadFromDisk() throws Exception {
        Path dir = Files.createTempDirectory("th-cells");
        Path p = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).write(dir, "chunkdata_0_0.bin");
        CellReader.Result r = CellReader.read(p);
        Check.isTrue(r.clean(), "clean");
        Check.equal(CollisionBits.SOLID, at(r.tiles, 0, 0), "solid");
    }

    public static void testWalkable() {
        Check.isTrue(CollisionBits.walkable(0), "empty");
        Check.isTrue(CollisionBits.walkable(CollisionBits.WALL_N | CollisionBits.WALL_W), "walls on edges still walkable");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.SOLID), "solid");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.WATER), "water (D16)");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.ROOM), "room (sealed buildings)");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.NO_DATA), "no data");
    }
}
```

Add `CellReaderTest.class,` to the `SUITES` array in `TestRunner.java` after `LuaSyntaxTest.class,`.

- [ ] **Step 2: Run the build and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `test compile failed` (package `truehordes.world` does not exist).

- [ ] **Step 3: Implement**

`java/src/truehordes/world/CollisionBits.java`:

```java
package truehordes.world;

/** Tile bits and chunk types of the game's chunkdata_X_Y.bin collision files (checked against 42.21's map files). */
public final class CollisionBits {
    public static final int SOLID = 1;
    public static final int WALL_N = 2;
    public static final int WALL_W = 4;
    public static final int WATER = 8;
    public static final int ROOM = 16;
    /** The mod's own marker for "no map here / not read" (never in the game's files). */
    public static final int NO_DATA = 0x80;
    /** A zombie cannot stand on a tile with any of these (water blocks: D16; buildings are sealed in this data). */
    public static final int NOT_WALKABLE = SOLID | WATER | ROOM | NO_DATA;

    public static final int CHUNK_EMPTY = 0;
    public static final int CHUNK_SOLID = 1;
    public static final int CHUNK_REGULAR = 2;
    public static final int CHUNK_WATER = 3;
    public static final int CHUNK_ROOM = 4;
    /** Not in the game's Java reader; found in the vanilla map's edge strips. Means "no map here". */
    public static final int CHUNK_VOID = 5;

    public static final int CELL_TILES = 256;
    public static final int CHUNK_TILES = 8;
    public static final int CHUNKS_PER_CELL = 32;

    private CollisionBits() {}

    public static boolean walkable(int bits) {
        return (bits & NOT_WALKABLE) == 0;
    }
}
```

`java/src/truehordes/world/CellReader.java`:

```java
package truehordes.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads one cell's collision file into 256x256 tile bytes (index = y * 256 + x, cell-local). Format (42.21): big-endian
 * short version 1, then for each of the 32x32 chunks (y outer) one type byte; only a regular chunk (2) is followed by
 * its 64 tile bytes. Never throws: problems come back in {@link Result#error}, unread parts are NO_DATA.
 */
public final class CellReader {
    public static final int VERSION = 1;
    private static final int TILES = CollisionBits.CELL_TILES * CollisionBits.CELL_TILES;

    /** Tiles of one cell (null when the file is missing) and an error (null when the whole file read cleanly). */
    public static final class Result {
        public final byte[] tiles;
        public final String error;

        Result(byte[] tiles, String error) {
            this.tiles = tiles;
            this.error = error;
        }

        public boolean missing() { return tiles == null; }

        public boolean clean() { return tiles != null && error == null; }
    }

    private CellReader() {}

    public static byte[] noData() {
        byte[] t = new byte[TILES];
        Arrays.fill(t, (byte) CollisionBits.NO_DATA);
        return t;
    }

    public static Result read(Path file) {
        if (file == null) return new Result(null, "no file");
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new Result(null, "missing");
        } catch (IOException | RuntimeException e) {
            return new Result(null, "unreadable: " + e);
        }
        return parse(data);
    }

    public static Result parse(byte[] data) {
        byte[] tiles = noData();
        if (data.length < 2) return new Result(tiles, "truncated header");
        int version = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (version != VERSION) return new Result(tiles, "unknown version " + version);
        int pos = 2;
        for (int c = 0; c < 1024; c++) {
            if (pos >= data.length) return new Result(tiles, "truncated at chunk " + c);
            int type = data[pos++] & 0xFF;
            int ox = (c % 32) * 8;
            int oy = (c / 32) * 8;
            if (type == CollisionBits.CHUNK_REGULAR) {
                if (pos + 64 > data.length) return new Result(tiles, "truncated in chunk " + c);
                for (int ty = 0; ty < 8; ty++) System.arraycopy(data, pos + ty * 8, tiles, (oy + ty) * 256 + ox, 8);
                pos += 64;
            } else {
                int bits = uniformBits(type);
                if (bits < 0) return new Result(tiles, "unknown chunk type " + type + " at chunk " + c);
                for (int ty = 0; ty < 8; ty++) {
                    int row = (oy + ty) * 256 + ox;
                    Arrays.fill(tiles, row, row + 8, (byte) bits);
                }
            }
        }
        if (pos != data.length) return new Result(tiles, (data.length - pos) + " bytes after the last chunk");
        return new Result(tiles, null);
    }

    private static int uniformBits(int type) {
        switch (type) {
            case CollisionBits.CHUNK_EMPTY: return 0;
            case CollisionBits.CHUNK_SOLID: return CollisionBits.SOLID;
            case CollisionBits.CHUNK_WATER: return CollisionBits.WATER;
            case CollisionBits.CHUNK_ROOM: return CollisionBits.ROOM;
            case CollisionBits.CHUNK_VOID: return CollisionBits.NO_DATA;
            default: return -1;
        }
    }
}
```

- [ ] **Step 4: Run the build and confirm the tests pass**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `50 passed, 0 failed` (42 existing + 8 new), then `Built ...`.

- [ ] **Step 5: Commit**

```bash
git add java/src/truehordes/world java/test/truehordes/test/FakeCells.java java/test/truehordes/test/CellReaderTest.java java/test/truehordes/test/TestRunner.java
git commit -m "SP2: collision file reader (chunk types 0-5, damaged files never throw)"
```

---

### Task 2: Chunk summaries, terrain and directions

**Files:**
- Create: `java/src/truehordes/world/ChunkInfo.java`
- Create: `java/src/truehordes/world/Terrain.java`
- Create: `java/src/truehordes/world/Dir.java`
- Create: `java/test/truehordes/test/ChunkInfoTest.java`
- Modify: `java/test/truehordes/test/TestRunner.java` (add `ChunkInfoTest.class`)

**Interfaces:**
- Consumes: `CollisionBits` (Task 1).
- Produces:
  - `ChunkInfo.summarise(byte[] tiles, int offset, int stride, int zoneCode) -> short`; `ChunkInfo.masks(byte[] tiles, int offset, int stride) -> int`; `ChunkInfo.open(short)`, `ChunkInfo.zone(short)`, `ChunkInfo.withZone(short, int)`; flags `ChunkInfo.WATER`, `ROOM`, `NO_DATA`; `ChunkInfo.UNLOADED` (short); `ChunkInfo.crossNorth(int a, int north)`, `ChunkInfo.crossWest(int a, int west)`.
  - `enum Terrain {UNKNOWN(0), ROAD(1), TOWN(2), FARM(3), OPEN(4), FOREST(5), DEEP_FOREST(6), BUILDING(7), WATER(8)}` with `code`, `zonePriority`, `baseCost`, `ofCode(int)`, `fromZoneType(String)`, `classify(short info)`, `cost(short info)`.
  - `enum Dir {N, E, S, W}` with `dx`, `dy`.

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/ChunkInfoTest.java`:

```java
package truehordes.test;

import truehordes.world.ChunkInfo;
import truehordes.world.CollisionBits;
import truehordes.world.Terrain;

public final class ChunkInfoTest {
    private static short summary(byte[] t, Terrain zone) { return ChunkInfo.summarise(t, 0, 8, zone.code); }

    public static void testOpenCountAndZone() {
        byte[] t = FakeCells.fill(0);
        t[0] = (byte) CollisionBits.SOLID;
        t[1] = (byte) CollisionBits.WATER;
        t[2] = (byte) CollisionBits.WALL_N; // a wall on the edge still leaves the tile walkable
        short s = summary(t, Terrain.FOREST);
        Check.equal(62, ChunkInfo.open(s), "open tiles");
        Check.equal(Terrain.FOREST.code, ChunkInfo.zone(s), "zone kept");
        Check.equal(Terrain.FOREST, Terrain.classify(s), "classified by zone");
    }

    public static void testMajorities() {
        byte[] water = FakeCells.fill(CollisionBits.WATER);
        Check.equal(Terrain.WATER, Terrain.classify(summary(water, Terrain.ROAD)), "water beats a road zone");
        byte[] room = FakeCells.fill(CollisionBits.ROOM);
        Check.equal(Terrain.BUILDING, Terrain.classify(summary(room, Terrain.TOWN)), "room majority is a building");
        byte[] none = FakeCells.fill(CollisionBits.NO_DATA);
        Check.equal(Terrain.UNKNOWN, Terrain.classify(summary(none, Terrain.ROAD)), "no data is unknown");
        byte[] half = FakeCells.fill(0);
        for (int i = 0; i < 32; i++) half[i] = (byte) CollisionBits.WATER;
        Check.equal(Terrain.OPEN, Terrain.classify(summary(half, Terrain.UNKNOWN)), "exactly half water is not water; no zone is open");
    }

    public static void testWithZoneKeepsTileFlags() {
        short s = summary(FakeCells.fill(CollisionBits.WATER), Terrain.UNKNOWN);
        short z = ChunkInfo.withZone(s, Terrain.ROAD.code);
        Check.equal(Terrain.ROAD.code, ChunkInfo.zone(z), "zone set");
        Check.equal(Terrain.WATER, Terrain.classify(z), "still water");
        Check.equal(Terrain.UNKNOWN, Terrain.classify(ChunkInfo.UNLOADED), "unloaded is unknown");
    }

    public static void testMasks() {
        byte[] t = FakeCells.fill(0);
        t[0 * 8 + 3] = (byte) CollisionBits.WALL_N;   // top row, column 3: cannot leave north
        t[7 * 8 + 5] = (byte) CollisionBits.SOLID;    // bottom row, column 5: cannot stand
        t[2 * 8 + 0] = (byte) CollisionBits.WALL_W;   // left column, row 2: cannot leave west
        t[6 * 8 + 7] = (byte) CollisionBits.WATER;    // right column, row 6: cannot stand
        int m = ChunkInfo.masks(t, 0, 8);
        Check.equal(0xFF & ~(1 << 3), m & 0xFF, "north entry");
        Check.equal(0xFF & ~(1 << 5), (m >>> 8) & 0xFF, "south exit");
        Check.equal(0xFF & ~(1 << 2), (m >>> 16) & 0xFF, "west entry");
        Check.equal(0xFF & ~(1 << 6), (m >>> 24) & 0xFF, "east exit");
    }

    public static void testMasksFromCellLayout() {
        byte[] cell = new byte[256 * 256];
        cell[(8 + 0) * 256 + 16 + 4] = (byte) CollisionBits.WALL_N; // chunk (2,1), top row column 4
        int m = ChunkInfo.masks(cell, 8 * 256 + 16, 256);
        Check.equal(0xFF & ~(1 << 4), m & 0xFF, "offset and stride respected");
    }

    public static void testCrossingNeedsBothSides() {
        int open = ChunkInfo.masks(FakeCells.fill(0), 0, 8);
        int blocked = ChunkInfo.masks(FakeCells.fill(CollisionBits.SOLID), 0, 8);
        byte[] walled = FakeCells.fill(0);
        for (int i = 0; i < 8; i++) walled[i] = (byte) CollisionBits.WALL_N;
        int wallN = ChunkInfo.masks(walled, 0, 8);
        Check.isTrue(ChunkInfo.crossNorth(open, open), "open to open");
        Check.isTrue(!ChunkInfo.crossNorth(open, blocked), "into solid");
        Check.isTrue(!ChunkInfo.crossNorth(wallN, open), "wall along the north edge");
        byte[] oneGap = FakeCells.fill(0);
        for (int i = 1; i < 8; i++) oneGap[i * 8] = (byte) CollisionBits.WALL_W;
        Check.isTrue(ChunkInfo.crossWest(ChunkInfo.masks(oneGap, 0, 8), open), "one gap is enough");
    }

    public static void testZoneTypesAndCosts() {
        Check.equal(Terrain.ROAD, Terrain.fromZoneType("Nav"), "Nav");
        Check.equal(Terrain.TOWN, Terrain.fromZoneType("TownZone"), "TownZone");
        Check.equal(Terrain.FARM, Terrain.fromZoneType("FarmLand"), "FarmLand");
        Check.equal(Terrain.FARM, Terrain.fromZoneType("Ranch"), "Ranch");
        Check.equal(Terrain.DEEP_FOREST, Terrain.fromZoneType("DeepForest"), "DeepForest");
        Check.equal(Terrain.OPEN, Terrain.fromZoneType("Vegitation"), "Vegitation (the game's spelling)");
        Check.equal(null, Terrain.fromZoneType("ParkingStall"), "ignored type");
        Check.isTrue(Terrain.ROAD.zonePriority > Terrain.TOWN.zonePriority, "road beats town");
        Check.isTrue(Terrain.FARM.zonePriority > Terrain.DEEP_FOREST.zonePriority, "farm beats deep forest");
        Check.isTrue(Terrain.DEEP_FOREST.zonePriority > Terrain.FOREST.zonePriority, "deep forest beats forest");
        Check.isTrue(Terrain.FOREST.zonePriority > Terrain.OPEN.zonePriority, "forest beats open");
        short road = ChunkInfo.summarise(FakeCells.fill(0), 0, 8, Terrain.ROAD.code);
        Check.near(1.0, Terrain.cost(road), 1e-6, "open road costs its base");
        byte[] half = FakeCells.fill(0);
        for (int i = 0; i < 32; i++) half[i] = (byte) CollisionBits.SOLID;
        Check.near(2.0, Terrain.cost(ChunkInfo.summarise(half, 0, 8, Terrain.ROAD.code)), 1e-6, "half open doubles");
        Check.isTrue(Float.isInfinite(Terrain.cost(ChunkInfo.UNLOADED)), "unknown is infinite");
    }
}
```

Add `ChunkInfoTest.class,` to `SUITES`.

- [ ] **Step 2: Run the build and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `test compile failed` (`ChunkInfo`, `Terrain` missing).

- [ ] **Step 3: Implement**

`java/src/truehordes/world/Dir.java`:

```java
package truehordes.world;

/** The four neighbours of a chunk. */
public enum Dir {
    N(0, -1), E(1, 0), S(0, 1), W(-1, 0);

    public final int dx;
    public final int dy;

    Dir(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }
}
```

`java/src/truehordes/world/ChunkInfo.java`:

```java
package truehordes.world;

/**
 * One chunk's summary packed into a short, and its border masks packed into an int.
 * Summary: bits 0-6 walkable tiles (0-64); bit 7 water majority; bit 8 room majority; bit 9 no-data majority;
 * bits 10-13 the covering zone's Terrain code (0 none). Majority means more than 32 of 64 tiles.
 * Masks: byte 0 north entry (top-row tile walkable with no north wall), byte 1 south exit (bottom-row tile walkable),
 * byte 2 west entry (left-column tile walkable with no west wall), byte 3 east exit (right-column tile walkable);
 * bit i is column i (north, south) or row i (west, east). Walls sit on a tile's own north and west edges.
 */
public final class ChunkInfo {
    public static final int OPEN_MASK = 0x7F;
    public static final int WATER = 1 << 7;
    public static final int ROOM = 1 << 8;
    public static final int NO_DATA = 1 << 9;
    public static final int ZONE_SHIFT = 10;
    /** A chunk nothing has been read for. */
    public static final short UNLOADED = (short) NO_DATA;

    private ChunkInfo() {}

    /** tiles[offset + ty * stride + tx] for the chunk's 8x8 tiles. */
    public static short summarise(byte[] tiles, int offset, int stride, int zoneCode) {
        int open = 0, water = 0, room = 0, none = 0;
        for (int ty = 0; ty < 8; ty++) {
            int row = offset + ty * stride;
            for (int tx = 0; tx < 8; tx++) {
                int b = tiles[row + tx] & 0xFF;
                if ((b & CollisionBits.NO_DATA) != 0) { none++; continue; }
                if ((b & CollisionBits.WATER) != 0) water++;
                if ((b & CollisionBits.ROOM) != 0) room++;
                if (CollisionBits.walkable(b)) open++;
            }
        }
        int info = open;
        if (water > 32) info |= WATER;
        if (room > 32) info |= ROOM;
        if (none > 32) info |= NO_DATA;
        return (short) (info | ((zoneCode & 0xF) << ZONE_SHIFT));
    }

    public static int masks(byte[] tiles, int offset, int stride) {
        int n = 0, s = 0, w = 0, e = 0;
        for (int i = 0; i < 8; i++) {
            int top = tiles[offset + i] & 0xFF;
            if (CollisionBits.walkable(top) && (top & CollisionBits.WALL_N) == 0) n |= 1 << i;
            int bottom = tiles[offset + 7 * stride + i] & 0xFF;
            if (CollisionBits.walkable(bottom)) s |= 1 << i;
            int left = tiles[offset + i * stride] & 0xFF;
            if (CollisionBits.walkable(left) && (left & CollisionBits.WALL_W) == 0) w |= 1 << i;
            int right = tiles[offset + i * stride + 7] & 0xFF;
            if (CollisionBits.walkable(right)) e |= 1 << i;
        }
        return n | (s << 8) | (w << 16) | (e << 24);
    }

    public static int open(short info) { return info & OPEN_MASK; }

    public static int zone(short info) { return ((info & 0xFFFF) >>> ZONE_SHIFT) & 0xF; }

    public static short withZone(short info, int zoneCode) {
        return (short) ((info & ~(0xF << ZONE_SHIFT)) | ((zoneCode & 0xF) << ZONE_SHIFT));
    }

    /** From chunk a into its north neighbour: a's top row (no north wall) meets the neighbour's bottom row. */
    public static boolean crossNorth(int a, int north) {
        return (a & 0xFF & (north >>> 8)) != 0;
    }

    /** From chunk a into its west neighbour: a's left column (no west wall) meets the neighbour's right column. */
    public static boolean crossWest(int a, int west) {
        return ((a >>> 16) & 0xFF & (west >>> 24)) != 0;
    }
}
```

`java/src/truehordes/world/Terrain.java`:

```java
package truehordes.world;

/** What a chunk is, for walking. Codes are stored in ChunkInfo (4 bits). Costs are placeholders until sub-project 4. */
public enum Terrain {
    UNKNOWN(0, 0, Float.POSITIVE_INFINITY),
    ROAD(1, 6, 1f),
    TOWN(2, 5, 2f),
    FARM(3, 4, 3f),
    OPEN(4, 1, 3f),
    FOREST(5, 2, 4f),
    DEEP_FOREST(6, 3, 6f),
    BUILDING(7, 0, Float.POSITIVE_INFINITY),
    WATER(8, 0, Float.POSITIVE_INFINITY);

    public final int code;
    /** Which zone wins where zones overlap (higher wins); 0 for kinds that never come from a zone. */
    public final int zonePriority;
    public final float baseCost;

    private static final Terrain[] BY_CODE = new Terrain[16];

    static {
        for (Terrain t : values()) BY_CODE[t.code] = t;
    }

    Terrain(int code, int zonePriority, float baseCost) {
        this.code = code;
        this.zonePriority = zonePriority;
        this.baseCost = baseCost;
    }

    public static Terrain ofCode(int code) {
        Terrain t = code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
        return t == null ? UNKNOWN : t;
    }

    /** The game's zone type names (objects.lua); null for types that do not affect walking. */
    public static Terrain fromZoneType(String type) {
        if (type == null) return null;
        switch (type) {
            case "Nav": return ROAD;
            case "TownZone": return TOWN;
            case "Farm":
            case "FarmLand":
            case "Ranch": return FARM;
            case "Forest": return FOREST;
            case "DeepForest": return DEEP_FOREST;
            case "Vegitation":
            case "TrailerPark": return OPEN;
            default: return null;
        }
    }

    /** No data, then water, then building, then the zone (no zone = open). */
    public static Terrain classify(short info) {
        if ((info & ChunkInfo.NO_DATA) != 0) return UNKNOWN;
        if ((info & ChunkInfo.WATER) != 0) return WATER;
        if ((info & ChunkInfo.ROOM) != 0) return BUILDING;
        int zone = ChunkInfo.zone(info);
        return zone == 0 ? OPEN : ofCode(zone);
    }

    /** Base cost, rising to 3x as walkable tiles drop to none. Blocked chunks are handled by Grid.cost. */
    public static float cost(short info) {
        Terrain t = classify(info);
        if (Float.isInfinite(t.baseCost)) return Float.POSITIVE_INFINITY;
        return t.baseCost * (1f + (64 - ChunkInfo.open(info)) / 32f);
    }
}
```

- [ ] **Step 4: Run the build and confirm the tests pass**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `57 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java/src/truehordes/world java/test/truehordes/test/ChunkInfoTest.java java/test/truehordes/test/TestRunner.java
git commit -m "SP2: chunk summaries, border masks, terrain kinds and costs"
```

---

### Task 3: Zone shapes and rasterising zones to chunks

**Files:**
- Create: `java/src/truehordes/world/ZoneShape.java`
- Create: `java/src/truehordes/world/ZoneRaster.java`
- Create: `java/test/truehordes/test/ZoneRasterTest.java`
- Modify: `java/test/truehordes/test/TestRunner.java` (add `ZoneRasterTest.class`)

**Interfaces:**
- Consumes: `Terrain` (Task 2).
- Produces: `ZoneShape.rect(Terrain, int x, int y, int w, int h)`, `ZoneShape.polygon(Terrain, int[] points)`, `ZoneShape.polyline(Terrain, int[] points, int width)`; fields `terrain`, `kind`, `x`, `y`, `w`, `h`; `boolean contains(int tx, int ty)` (tile centre). `ZoneRaster.rasterise(List<ZoneShape>, int originChunkX, int originChunkY, int widthChunks, int heightChunks) -> byte[]` (terrain code per chunk, index `(cy - originY) * width + (cx - originX)`).

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/ZoneRasterTest.java`:

```java
package truehordes.test;

import java.util.List;
import truehordes.world.Terrain;
import truehordes.world.ZoneRaster;
import truehordes.world.ZoneShape;

public final class ZoneRasterTest {
    /** A 4x4-chunk area at the origin. */
    private static int code(byte[] codes, int cx, int cy) { return codes[cy * 4 + cx]; }

    public static void testRectNeedsChunkCentre() {
        // Covers tiles 0..11 in x: chunk 0 fully, chunk 1 up to tile 11 (its centre tile 12 is outside).
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.FOREST, 0, 0, 12, 8)), 0, 0, 4, 4);
        Check.equal(Terrain.FOREST.code, code(c, 0, 0), "chunk 0");
        Check.equal(0, code(c, 1, 0), "chunk 1 centre not covered");
    }

    public static void testThinRoadMarksChunk() {
        // A 4-tile-wide road along the right edge of chunk column 1 (tiles 12..15), all rows.
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.ROAD, 12, 0, 4, 32)), 0, 0, 4, 4);
        for (int cy = 0; cy < 4; cy++) {
            Check.equal(Terrain.ROAD.code, code(c, 1, cy), "road chunk row " + cy);
            Check.equal(0, code(c, 0, cy), "left of road row " + cy);
            Check.equal(0, code(c, 2, cy), "right of road row " + cy);
        }
    }

    public static void testPriorityWhereZonesOverlap() {
        List<ZoneShape> zones = List.of(
            ZoneShape.rect(Terrain.FOREST, 0, 0, 32, 32),
            ZoneShape.rect(Terrain.TOWN, 0, 0, 16, 16),
            ZoneShape.rect(Terrain.OPEN, 0, 0, 32, 32),
            ZoneShape.rect(Terrain.ROAD, 0, 0, 1, 1));
        byte[] c = ZoneRaster.rasterise(zones, 0, 0, 4, 4);
        Check.equal(Terrain.ROAD.code, code(c, 0, 0), "road beats town");
        Check.equal(Terrain.TOWN.code, code(c, 1, 1), "town beats forest");
        Check.equal(Terrain.FOREST.code, code(c, 3, 3), "forest beats open");
    }

    public static void testPolygonByCentre() {
        // Right triangle with corners (0,0), (32,0), (0,32): chunk (0,0) and (2,0) centres are inside, (3,3) is not.
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.polygon(Terrain.DEEP_FOREST, new int[] {0, 0, 32, 0, 0, 32})), 0, 0, 4, 4);
        Check.equal(Terrain.DEEP_FOREST.code, code(c, 0, 0), "corner chunk");
        Check.equal(Terrain.DEEP_FOREST.code, code(c, 2, 0), "along the top");
        Check.equal(0, code(c, 3, 3), "outside the hypotenuse");
        Check.equal(0, code(c, 2, 2), "centre (20.5,20.5) is outside");
    }

    public static void testRoadPolygonAnyTile() {
        // A thin road polygon covering only tiles x 14..15 in rows 0..7 (inside chunk (1,0), not its centre).
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.polygon(Terrain.ROAD, new int[] {14, 0, 16, 0, 16, 8, 14, 8})), 0, 0, 4, 4);
        Check.equal(Terrain.ROAD.code, code(c, 1, 0), "any tile counts for roads");
        Check.equal(0, code(c, 2, 0), "neighbour untouched");
    }

    public static void testPolylineByDistance() {
        ZoneShape line = ZoneShape.polyline(Terrain.ROAD, new int[] {0, 4, 32, 4}, 2);
        Check.isTrue(line.contains(10, 4), "on the line");
        Check.isTrue(!line.contains(10, 7), "3.5 tiles off");
        byte[] c = ZoneRaster.rasterise(List.of(line), 0, 0, 4, 4);
        for (int cx = 0; cx < 4; cx++) Check.equal(Terrain.ROAD.code, code(c, cx, 0), "row 0 chunk " + cx);
        Check.equal(0, code(c, 0, 1), "row 1 untouched");
    }

    public static void testOffsetOriginAndClipping() {
        // Origin at chunk (10,20); a zone partly outside the area must not throw and must land in place.
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.FARM, 72, 152, 64, 64)), 10, 20, 4, 4);
        Check.equal(Terrain.FARM.code, c[(20 - 20) * 4 + (10 - 10)], "first chunk (80..87, 160..167)");
        Check.equal(Terrain.FARM.code, c[3 * 4 + 3], "last chunk");
    }

    public static void testContainsRect() {
        ZoneShape r = ZoneShape.rect(Terrain.TOWN, 5, 5, 2, 2);
        Check.isTrue(r.contains(5, 5) && r.contains(6, 6), "inside");
        Check.isTrue(!r.contains(7, 5) && !r.contains(4, 5), "outside");
    }
}
```

Add `ZoneRasterTest.class,` to `SUITES`.

- [ ] **Step 2: Run the build and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `test compile failed` (`ZoneShape`, `ZoneRaster` missing).

- [ ] **Step 3: Implement**

`java/src/truehordes/world/ZoneShape.java`:

```java
package truehordes.world;

import java.util.Arrays;

/**
 * An immutable copy of one map zone, with the mod's own geometry (the game's Zone shares a static helper that is not
 * thread-safe, so the build thread never touches game zones). Tile (tx, ty) is inside when its centre is.
 */
public final class ZoneShape {
    public enum Kind { RECT, POLYGON, POLYLINE }

    public final Terrain terrain;
    public final Kind kind;
    /** Bounds in tiles: [x, x + w) by [y, y + h). */
    public final int x, y, w, h;
    private final int[] points;
    private final double halfWidth;

    private ZoneShape(Terrain terrain, Kind kind, int x, int y, int w, int h, int[] points, double halfWidth) {
        this.terrain = terrain;
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.points = points;
        this.halfWidth = halfWidth;
    }

    public static ZoneShape rect(Terrain terrain, int x, int y, int w, int h) {
        return new ZoneShape(terrain, Kind.RECT, x, y, w, h, null, 0);
    }

    /** Corners as x0, y0, x1, y1, ... (closed implicitly). */
    public static ZoneShape polygon(Terrain terrain, int[] points) {
        int[] b = bounds(points, 0);
        return new ZoneShape(terrain, Kind.POLYGON, b[0], b[1], b[2], b[3], points.clone(), 0);
    }

    /** A line through x0, y0, x1, y1, ... covering everything within width / 2 of it. */
    public static ZoneShape polyline(Terrain terrain, int[] points, int width) {
        double half = width / 2.0;
        int[] b = bounds(points, (int) Math.ceil(half));
        return new ZoneShape(terrain, Kind.POLYLINE, b[0], b[1], b[2], b[3], points.clone(), half);
    }

    private static int[] bounds(int[] pts, int pad) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < pts.length; i += 2) {
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        if (minX > maxX) return new int[] {0, 0, 0, 0};
        return new int[] {minX - pad, minY - pad, maxX - minX + 2 * pad + 1, maxY - minY + 2 * pad + 1};
    }

    public boolean contains(int tx, int ty) {
        if (tx < x || tx >= x + w || ty < y || ty >= y + h) return false;
        double px = tx + 0.5, py = ty + 0.5;
        switch (kind) {
            case RECT: return true;
            case POLYGON: return insidePolygon(px, py);
            default: return nearPolyline(px, py);
        }
    }

    /** Polygon only: sorted x positions where the horizontal line at py crosses the outline. */
    double[] crossings(double py) {
        int n = points.length / 2;
        double[] xs = new double[n];
        int count = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = points[2 * i], yi = points[2 * i + 1], xj = points[2 * j], yj = points[2 * j + 1];
            if ((yi > py) != (yj > py)) xs[count++] = xi + (py - yi) * (xj - xi) / (yj - yi);
        }
        double[] out = Arrays.copyOf(xs, count);
        Arrays.sort(out);
        return out;
    }

    private boolean insidePolygon(double px, double py) {
        int right = 0;
        for (double cx : crossings(py)) if (cx > px) right++;
        return (right & 1) == 1;
    }

    private boolean nearPolyline(double px, double py) {
        double limit = halfWidth * halfWidth;
        for (int i = 0; i + 3 < points.length; i += 2) {
            if (distSq(px, py, points[i], points[i + 1], points[i + 2], points[i + 3]) <= limit) return true;
        }
        return points.length == 2 && distSq(px, py, points[0], points[1], points[0], points[1]) <= limit;
    }

    private static double distSq(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
        double ex = ax + t * dx - px, ey = ay + t * dy - py;
        return ex * ex + ey * ey;
    }
}
```

`java/src/truehordes/world/ZoneRaster.java`:

```java
package truehordes.world;

import java.util.List;

/**
 * Zones to one Terrain code per chunk. A road marks a chunk if it covers any of its tiles (narrow roads are never lost);
 * every other zone needs the chunk's centre tile (cx * 8 + 4, cy * 8 + 4). Where zones overlap, the higher
 * Terrain.zonePriority wins. Polygons are filled by scanlines, so big forests cost one pass per chunk row.
 */
public final class ZoneRaster {
    private ZoneRaster() {}

    public static byte[] rasterise(List<ZoneShape> zones, int originX, int originY, int width, int height) {
        byte[] codes = new byte[width * height];
        for (ZoneShape z : zones) {
            int cx0 = Math.max(Math.floorDiv(z.x, 8), originX);
            int cx1 = Math.min(Math.floorDiv(z.x + z.w - 1, 8), originX + width - 1);
            int cy0 = Math.max(Math.floorDiv(z.y, 8), originY);
            int cy1 = Math.min(Math.floorDiv(z.y + z.h - 1, 8), originY + height - 1);
            if (cx0 > cx1 || cy0 > cy1) continue;
            boolean road = z.terrain == Terrain.ROAD;
            switch (z.kind) {
                case RECT:
                    for (int cy = cy0; cy <= cy1; cy++) {
                        for (int cx = cx0; cx <= cx1; cx++) {
                            if (road || z.contains(cx * 8 + 4, cy * 8 + 4)) mark(codes, originX, originY, width, cx, cy, z.terrain);
                        }
                    }
                    break;
                case POLYGON:
                    if (road) polygonAnyTile(z, codes, originX, originY, width, height);
                    else polygonCentres(z, codes, originX, originY, width, cy0, cy1);
                    break;
                default:
                    for (int cy = cy0; cy <= cy1; cy++) {
                        for (int cx = cx0; cx <= cx1; cx++) {
                            if (road ? anyTile(z, cx, cy) : z.contains(cx * 8 + 4, cy * 8 + 4)) {
                                mark(codes, originX, originY, width, cx, cy, z.terrain);
                            }
                        }
                    }
            }
        }
        return codes;
    }

    private static void polygonCentres(ZoneShape z, byte[] codes, int ox, int oy, int width, int cy0, int cy1) {
        for (int cy = cy0; cy <= cy1; cy++) {
            double[] xs = z.crossings(cy * 8 + 4.5);
            for (int i = 0; i + 1 < xs.length; i += 2) {
                // chunk centres cx * 8 + 4.5 inside [xs[i], xs[i + 1])
                int from = Math.max((int) Math.ceil((xs[i] - 4.5) / 8), ox);
                int to = Math.min((int) Math.ceil((xs[i + 1] - 4.5) / 8) - 1, ox + width - 1);
                for (int cx = from; cx <= to; cx++) mark(codes, ox, oy, width, cx, cy, z.terrain);
            }
        }
    }

    private static void polygonAnyTile(ZoneShape z, byte[] codes, int ox, int oy, int width, int height) {
        int ty0 = Math.max(z.y, oy * 8), ty1 = Math.min(z.y + z.h - 1, (oy + height) * 8 - 1);
        for (int ty = ty0; ty <= ty1; ty++) {
            double[] xs = z.crossings(ty + 0.5);
            for (int i = 0; i + 1 < xs.length; i += 2) {
                // tile centres tx + 0.5 inside [xs[i], xs[i + 1])
                int from = (int) Math.ceil(xs[i] - 0.5);
                int to = (int) Math.ceil(xs[i + 1] - 0.5) - 1;
                if (from > to) continue;
                int cxFrom = Math.max(Math.floorDiv(from, 8), ox);
                int cxTo = Math.min(Math.floorDiv(to, 8), ox + width - 1);
                for (int cx = cxFrom; cx <= cxTo; cx++) mark(codes, ox, oy, width, cx, Math.floorDiv(ty, 8), z.terrain);
            }
        }
    }

    private static boolean anyTile(ZoneShape z, int cx, int cy) {
        for (int ty = 0; ty < 8; ty++) {
            for (int tx = 0; tx < 8; tx++) if (z.contains(cx * 8 + tx, cy * 8 + ty)) return true;
        }
        return false;
    }

    private static void mark(byte[] codes, int ox, int oy, int width, int cx, int cy, Terrain t) {
        int i = (cy - oy) * width + (cx - ox);
        if (t.zonePriority > Terrain.ofCode(codes[i]).zonePriority) codes[i] = (byte) t.code;
    }
}
```

- [ ] **Step 4: Run the build and confirm the tests pass**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `65 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java/src/truehordes/world java/test/truehordes/test/ZoneRasterTest.java java/test/truehordes/test/TestRunner.java
git commit -m "SP2: zone shapes and zone-to-chunk rasterising (roads on any tile, priorities)"
```

---

### Task 4: Grid, world source and the grid builder

**Files:**
- Create: `java/src/truehordes/world/Grid.java`
- Create: `java/src/truehordes/world/WorldSource.java`
- Create: `java/src/truehordes/world/BuildReport.java`
- Create: `java/src/truehordes/world/CellSource.java`
- Create: `java/src/truehordes/world/GridBuilder.java`
- Create: `java/test/truehordes/test/GridTest.java`
- Create: `java/test/truehordes/test/GridBuilderTest.java`
- Modify: `java/test/truehordes/test/TestRunner.java` (add both)

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `Grid(int originX, int originY, int width, int height)` (chunk units); `int index(cx, cy)` (-1 outside); `short info(cx, cy)`; `int masks(cx, cy)`; `void set(cx, cy, short info, int masks)`; `Terrain terrain(cx, cy)`; `boolean canCross(cx, cy, Dir)`; `boolean blocked(cx, cy)`; `float cost(cx, cy)`; `long memoryBytes()`; `int[] terrainCounts()` (index = Terrain code).
  - `WorldSource(int minCellX, int minCellY, int maxCellX, int maxCellY, Map<Long, Path> mapFiles, Path saveChunkDir, List<ZoneShape> zones)` (inclusive cell bounds; `saveChunkDir` may be null); `static long key(int a, int b)`; `Path mapFile(cx, cy)`; `Path saveFile(cx, cy)`; `static Map<Long, Path> resolveMapFiles(List<Map<String, String>> foldersInPriority)`.
  - `BuildReport` public fields `cellsFromSave`, `cellsFromMap`, `cellsMissing`, `filesDamaged`, `List<String> damaged`, `long millis`, `long memoryBytes`, `int[] terrainCounts`; `void damaged(Path, String)`.
  - `CellSource.load(WorldSource, int cx, int cy, BuildReport) -> byte[]` (null when no file).
  - `GridBuilder.build(WorldSource, BooleanSupplier cancelled, BuildReport) -> Grid` (null if cancelled).

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/GridTest.java`:

```java
package truehordes.test;

import truehordes.world.ChunkInfo;
import truehordes.world.CollisionBits;
import truehordes.world.Dir;
import truehordes.world.Grid;
import truehordes.world.Terrain;

public final class GridTest {
    private static final int OPEN = ChunkInfo.masks(FakeCells.fill(0), 0, 8);

    private static Grid openGrid() {
        Grid g = new Grid(100, 200, 3, 3);
        for (int cy = 200; cy < 203; cy++) {
            for (int cx = 100; cx < 103; cx++) {
                g.set(cx, cy, ChunkInfo.summarise(FakeCells.fill(0), 0, 8, Terrain.ROAD.code), OPEN);
            }
        }
        return g;
    }

    public static void testOutsideIsBlocked() {
        Grid g = openGrid();
        Check.equal(-1, g.index(99, 200), "left of grid");
        Check.equal(-1, g.index(-5, -5), "negative");
        Check.equal(Terrain.UNKNOWN, g.terrain(103, 201), "right of grid");
        Check.isTrue(g.blocked(-1, -1), "blocked outside");
        Check.isTrue(Float.isInfinite(g.cost(500, 500)), "infinite outside");
        Check.isTrue(!g.canCross(102, 201, Dir.E), "cannot leave the map");
    }

    public static void testEdgesBothWays() {
        Grid g = openGrid();
        byte[] wallW = FakeCells.fill(0);
        for (int i = 0; i < 8; i++) wallW[i * 8] = (byte) CollisionBits.WALL_W;
        g.set(101, 201, ChunkInfo.summarise(wallW, 0, 8, Terrain.ROAD.code), ChunkInfo.masks(wallW, 0, 8));
        Check.isTrue(!g.canCross(101, 201, Dir.W), "wall on the west edge");
        Check.isTrue(!g.canCross(100, 201, Dir.E), "same wall seen from the west");
        Check.isTrue(g.canCross(101, 201, Dir.E), "east side open");
        Check.isTrue(g.canCross(101, 201, Dir.N) && g.canCross(101, 201, Dir.S), "north and south open");
        Check.isTrue(!g.blocked(101, 201), "not blocked");
        Check.near(1.0, g.cost(101, 201), 1e-6, "road cost");
    }

    public static void testSolidChunkIsBlocked() {
        Grid g = openGrid();
        byte[] solid = FakeCells.fill(CollisionBits.SOLID);
        g.set(101, 201, ChunkInfo.summarise(solid, 0, 8, Terrain.ROAD.code), ChunkInfo.masks(solid, 0, 8));
        Check.isTrue(g.blocked(101, 201), "blocked");
        Check.isTrue(Float.isInfinite(g.cost(101, 201)), "infinite");
        Check.isTrue(!g.canCross(101, 200, Dir.S), "neighbour cannot enter");
    }

    public static void testNewGridIsUnknown() {
        Grid g = new Grid(0, 0, 2, 2);
        Check.equal(Terrain.UNKNOWN, g.terrain(1, 1), "unloaded");
        Check.isTrue(g.blocked(1, 1), "blocked");
        Check.equal(4 * 2L + 4 * 4L, g.memoryBytes(), "2 + 4 bytes per chunk");
        Check.equal(4, g.terrainCounts()[Terrain.UNKNOWN.code], "counts");
    }
}
```

`java/test/truehordes/test/GridBuilderTest.java`:

```java
package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import truehordes.util.Log;
import truehordes.world.BuildReport;
import truehordes.world.CollisionBits;
import truehordes.world.Grid;
import truehordes.world.GridBuilder;
import truehordes.world.Terrain;
import truehordes.world.WorldSource;
import truehordes.world.ZoneShape;

public final class GridBuilderTest {
    private static Map<Long, Path> files(Object... cellAndPath) {
        Map<Long, Path> m = new HashMap<>();
        for (int i = 0; i < cellAndPath.length; i += 3) {
            m.put(WorldSource.key((Integer) cellAndPath[i], (Integer) cellAndPath[i + 1]), (Path) cellAndPath[i + 2]);
        }
        return m;
    }

    public static void testBuildsFromMapFilesAndZones() throws Exception {
        Path dir = Files.createTempDirectory("th-map");
        Path a = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).write(dir, "chunkdata_10_20.bin");
        WorldSource src = new WorldSource(10, 20, 11, 20, files(10, 20, a), null,
            List.of(ZoneShape.rect(Terrain.FOREST, 10 * 256, 20 * 256, 512, 256)));
        BuildReport r = new BuildReport();
        Grid g = GridBuilder.build(src, () -> false, r);
        Check.equal(Terrain.WATER, g.terrain(320, 640), "water chunk at the cell's first chunk");
        Check.equal(Terrain.FOREST, g.terrain(321, 640), "forest zone on empty ground");
        Check.equal(Terrain.UNKNOWN, g.terrain(352, 640), "cell 11 has no file");
        Check.equal(1, r.cellsFromMap, "one map file");
        Check.equal(1, r.cellsMissing, "one missing");
        Check.equal(64L * 32 * 6, g.memoryBytes(), "2 cells of 32x32 chunks, 6 bytes each");
        Check.isTrue(r.terrainCounts[Terrain.FOREST.code] > 0, "counts filled");
    }

    public static void testSaveCopyWinsOverMapCopy() throws Exception {
        Path map = FakeCells.cell().write(Files.createTempDirectory("th-map"), "chunkdata_0_0.bin");
        Path saveDir = Files.createTempDirectory("th-save");
        FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).write(saveDir, "chunkdata_0_0.bin");
        BuildReport r = new BuildReport();
        Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, map), saveDir, List.of()), () -> false, r);
        Check.isTrue(g.blocked(0, 0), "the save's wall is used");
        Check.equal(1, r.cellsFromSave, "from save");
        Check.equal(0, r.cellsFromMap, "not from map");
    }

    public static void testDamagedSaveCopyFallsBackToMapCopy() throws Exception {
        Log.setSink(line -> {});
        try {
            Path map = FakeCells.cell().write(Files.createTempDirectory("th-map"), "chunkdata_0_0.bin");
            Path saveDir = Files.createTempDirectory("th-save");
            byte[] full = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).bytes();
            Files.write(saveDir.resolve("chunkdata_0_0.bin"), java.util.Arrays.copyOf(full, 50));
            BuildReport r = new BuildReport();
            Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, map), saveDir, List.of()), () -> false, r);
            Check.isTrue(!g.blocked(0, 0), "map copy used, not the half-written save");
            Check.equal(1, r.filesDamaged, "damage counted");
            Check.equal(1, r.cellsFromMap, "from map");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testDamagedMapCopyKeepsWhatWasRead() throws Exception {
        Log.setSink(line -> {});
        try {
            Path dir = Files.createTempDirectory("th-map");
            byte[] full = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).bytes();
            Path p = dir.resolve("chunkdata_0_0.bin");
            Files.write(p, java.util.Arrays.copyOf(full, 10));
            BuildReport r = new BuildReport();
            Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, p), null, List.of()), () -> false, r);
            Check.equal(Terrain.WATER, g.terrain(0, 0), "chunk read before the damage");
            Check.equal(Terrain.UNKNOWN, g.terrain(31, 31), "unread chunk unknown");
            Check.equal(1, r.filesDamaged, "damage counted");
            Check.isTrue(r.damaged.get(0).contains("chunkdata_0_0.bin"), "path named: " + r.damaged);
        } finally {
            Log.setSink(null);
        }
    }

    public static void testFirstMapFolderWins() {
        Map<String, String> modMap = new HashMap<>();
        modMap.put("chunkdata_1_2.bin", "C:/mod/chunkdata_1_2.bin");
        modMap.put("1_2.lotheader", "C:/mod/1_2.lotheader");
        Map<String, String> vanilla = new HashMap<>();
        vanilla.put("chunkdata_1_2.bin", "C:/vanilla/chunkdata_1_2.bin");
        vanilla.put("chunkdata_3_4.bin", "C:/vanilla/chunkdata_3_4.bin");
        Map<Long, Path> files = WorldSource.resolveMapFiles(List.of(modMap, vanilla));
        Check.equal(2, files.size(), "two cells, lot headers ignored");
        Check.isTrue(files.get(WorldSource.key(1, 2)).toString().contains("mod"), "first folder wins");
        Check.isTrue(files.get(WorldSource.key(3, 4)).toString().contains("vanilla"), "fills the rest");
    }

    public static void testCancelReturnsNull() throws Exception {
        WorldSource src = new WorldSource(0, 0, 3, 3, new HashMap<>(), null, List.of());
        Check.equal(null, GridBuilder.build(src, () -> true, new BuildReport()), "cancelled");
    }
}
```

Add `GridTest.class, GridBuilderTest.class,` to `SUITES`.

- [ ] **Step 2: Run the build and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `test compile failed`.

- [ ] **Step 3: Implement**

`java/src/truehordes/world/Grid.java`:

```java
package truehordes.world;

import java.util.Arrays;

/**
 * The coarse map: one ChunkInfo summary and one border-mask int per 8x8-tile chunk. Coordinates are chunk coordinates
 * (tile / 8). Written by one thread at a time (the builder before publishing, then the main thread); entries are single
 * array elements, so readers see an old or a new value, never a torn one.
 */
public final class Grid {
    public final int originX, originY, width, height;
    private final short[] info;
    private final int[] masks;

    public Grid(int originX, int originY, int width, int height) {
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.info = new short[width * height];
        this.masks = new int[width * height];
        Arrays.fill(info, ChunkInfo.UNLOADED);
    }

    public int index(int cx, int cy) {
        int ix = cx - originX, iy = cy - originY;
        if (ix < 0 || iy < 0 || ix >= width || iy >= height) return -1;
        return iy * width + ix;
    }

    public short info(int cx, int cy) {
        int i = index(cx, cy);
        return i < 0 ? ChunkInfo.UNLOADED : info[i];
    }

    public int masks(int cx, int cy) {
        int i = index(cx, cy);
        return i < 0 ? 0 : masks[i];
    }

    public void set(int cx, int cy, short chunkInfo, int chunkMasks) {
        int i = index(cx, cy);
        if (i < 0) return;
        info[i] = chunkInfo;
        masks[i] = chunkMasks;
    }

    public Terrain terrain(int cx, int cy) { return Terrain.classify(info(cx, cy)); }

    public boolean canCross(int cx, int cy, Dir d) {
        int a = masks(cx, cy), b = masks(cx + d.dx, cy + d.dy);
        switch (d) {
            case N: return ChunkInfo.crossNorth(a, b);
            case S: return ChunkInfo.crossNorth(b, a);
            case W: return ChunkInfo.crossWest(a, b);
            default: return ChunkInfo.crossWest(b, a);
        }
    }

    public boolean blocked(int cx, int cy) {
        for (Dir d : Dir.values()) if (canCross(cx, cy, d)) return false;
        return true;
    }

    public float cost(int cx, int cy) {
        return blocked(cx, cy) ? Float.POSITIVE_INFINITY : Terrain.cost(info(cx, cy));
    }

    public long memoryBytes() { return info.length * 2L + masks.length * 4L; }

    /** Number of chunks of each terrain, indexed by Terrain.code. */
    public int[] terrainCounts() {
        int[] counts = new int[16];
        for (short s : info) counts[Terrain.classify(s).code]++;
        return counts;
    }
}
```

`java/src/truehordes/world/WorldSource.java`:

```java
package truehordes.world;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything the build needs, captured on the main thread at world start. Immutable. */
public final class WorldSource {
    private static final Pattern CHUNKDATA = Pattern.compile("chunkdata_(-?\\d+)_(-?\\d+)\\.bin");

    /** Cell bounds, inclusive. */
    public final int minCellX, minCellY, maxCellX, maxCellY;
    public final Map<Long, Path> mapFiles;
    /** The save's chunkdata folder; null when there is none (tests, no save). */
    public final Path saveChunkDir;
    public final List<ZoneShape> zones;

    public WorldSource(int minCellX, int minCellY, int maxCellX, int maxCellY, Map<Long, Path> mapFiles,
                       Path saveChunkDir, List<ZoneShape> zones) {
        this.minCellX = minCellX;
        this.minCellY = minCellY;
        this.maxCellX = maxCellX;
        this.maxCellY = maxCellY;
        this.mapFiles = Map.copyOf(mapFiles);
        this.saveChunkDir = saveChunkDir;
        this.zones = List.copyOf(zones);
    }

    public static long key(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    public Path mapFile(int cx, int cy) { return mapFiles.get(key(cx, cy)); }

    public Path saveFile(int cx, int cy) {
        return saveChunkDir == null ? null : saveChunkDir.resolve("chunkdata_" + cx + "_" + cy + ".bin");
    }

    /**
     * Each map folder's file names (name to absolute path), highest priority first (the game's IsoLot.MapFiles order).
     * The first folder that has a cell's collision file wins.
     */
    public static Map<Long, Path> resolveMapFiles(List<Map<String, String>> foldersInPriority) {
        Map<Long, Path> out = new HashMap<>();
        for (Map<String, String> folder : foldersInPriority) {
            for (Map.Entry<String, String> e : folder.entrySet()) {
                Matcher m = CHUNKDATA.matcher(e.getKey());
                if (!m.matches() || e.getValue() == null) continue;
                out.putIfAbsent(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), Paths.get(e.getValue()));
            }
        }
        return out;
    }
}
```

`java/src/truehordes/world/BuildReport.java`:

```java
package truehordes.world;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import truehordes.util.Log;

/** What a build read and how long it took. Filled by one thread, read after publishing. */
public final class BuildReport {
    public int cellsFromSave;
    public int cellsFromMap;
    public int cellsMissing;
    public int filesDamaged;
    /** The first few damaged files with their errors. */
    public final List<String> damaged = new ArrayList<>();
    public long millis;
    public long memoryBytes;
    public int[] terrainCounts = new int[16];

    public void damaged(Path file, String error) {
        filesDamaged++;
        if (damaged.size() < 5) damaged.add(file + ": " + error);
        Log.warn("collision file " + file + ": " + error);
    }
}
```

`java/src/truehordes/world/CellSource.java`:

```java
package truehordes.world;

import java.nio.file.Path;

/** Loads one cell's tiles: the save's copy when it reads cleanly, otherwise the map's copy. */
public final class CellSource {
    private CellSource() {}

    /** Null when neither file exists. */
    public static byte[] load(WorldSource src, int cx, int cy, BuildReport report) {
        Path save = src.saveFile(cx, cy);
        CellReader.Result fromSave = CellReader.read(save);
        if (fromSave.clean()) {
            report.cellsFromSave++;
            return fromSave.tiles;
        }
        if (!fromSave.missing()) report.damaged(save, fromSave.error + " (using the map's copy)");
        Path map = src.mapFile(cx, cy);
        CellReader.Result fromMap = CellReader.read(map);
        if (!fromMap.missing()) {
            if (!fromMap.clean()) report.damaged(map, fromMap.error);
            report.cellsFromMap++;
            return fromMap.tiles;
        }
        report.cellsMissing++;
        return null;
    }
}
```

`java/src/truehordes/world/GridBuilder.java`:

```java
package truehordes.world;

import java.util.function.BooleanSupplier;

/** Builds the coarse grid for a whole map from its collision files and zones. Runs on the build thread. */
public final class GridBuilder {
    private GridBuilder() {}

    /** Null if cancelled. */
    public static Grid build(WorldSource src, BooleanSupplier cancelled, BuildReport report) {
        long t0 = System.nanoTime();
        int originX = src.minCellX * 32, originY = src.minCellY * 32;
        int width = (src.maxCellX - src.minCellX + 1) * 32, height = (src.maxCellY - src.minCellY + 1) * 32;
        Grid g = new Grid(originX, originY, width, height);
        byte[] zones = ZoneRaster.rasterise(src.zones, originX, originY, width, height);
        for (int cy = 0; cy < height; cy++) {
            for (int cx = 0; cx < width; cx++) {
                int z = zones[cy * width + cx];
                if (z != 0) g.set(originX + cx, originY + cy, ChunkInfo.withZone(ChunkInfo.UNLOADED, z), 0);
            }
        }
        for (int cellY = src.minCellY; cellY <= src.maxCellY; cellY++) {
            for (int cellX = src.minCellX; cellX <= src.maxCellX; cellX++) {
                if (cancelled.getAsBoolean()) return null;
                byte[] tiles = CellSource.load(src, cellX, cellY, report);
                if (tiles == null) continue;
                for (int wy = 0; wy < 32; wy++) {
                    for (int wx = 0; wx < 32; wx++) {
                        int cx = cellX * 32 + wx, cy = cellY * 32 + wy;
                        int offset = wy * 8 * 256 + wx * 8;
                        int zone = zones[(cy - originY) * width + (cx - originX)];
                        g.set(cx, cy, ChunkInfo.summarise(tiles, offset, 256, zone), ChunkInfo.masks(tiles, offset, 256));
                    }
                }
            }
        }
        report.terrainCounts = g.terrainCounts();
        report.memoryBytes = g.memoryBytes();
        report.millis = (System.nanoTime() - t0) / 1_000_000L;
        return g;
    }
}
```

- [ ] **Step 4: Run the build and confirm the tests pass**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `75 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java/src/truehordes/world java/test/truehordes/test/GridTest.java java/test/truehordes/test/GridBuilderTest.java java/test/truehordes/test/TestRunner.java
git commit -m "SP2: coarse grid, world source and builder (save copy first, damaged files fall back)"
```

---

### Task 5: The World service (build thread, publish, live overlay, tile queries, report)

**Files:**
- Create: `java/src/truehordes/world/TileCache.java`
- Create: `java/src/truehordes/world/TerrainSample.java`
- Create: `java/src/truehordes/world/World.java`
- Create: `java/test/truehordes/test/WorldTest.java`
- Modify: `java/test/truehordes/test/TestRunner.java` (add `WorldTest.class`)

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `World` with `enum State {OFF, BUILDING, READY, FAILED}`; `void start(WorldSource)`, `void tick()`, `void stop()`, `State state()`, `boolean ready()`, `boolean awaitBuilt(long millis)` (tests: true once a finished build is waiting to be published or already published); queries `Terrain terrainAt(int x, int y)`, `float costAt(x, y)`, `boolean blockedAt(x, y)`, `boolean canCross(int chunkX, int chunkY, Dir)`, `int tileBits(x, y)`, `boolean walkable(x, y)`, `boolean canStep(x1, y1, x2, y2)`; live `void liveChunk(int chunkX, int chunkY, byte[] bits64)`, `void liveTile(int x, int y, int bits)`; `String report()`; `long memoryBytes()`; debug `TerrainSample terrainSample(int minX, int minY, int maxX, int maxY, int step)`, `int[] tileSample(int minX, int minY, int maxX, int maxY, int max)`.
- `TerrainSample` fields: `x0`, `y0`, `step`, `cols`, `rows`, `int[] codes` (row-major).

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/WorldTest.java`:

```java
package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import truehordes.util.Log;
import truehordes.world.CollisionBits;
import truehordes.world.Dir;
import truehordes.world.Terrain;
import truehordes.world.TerrainSample;
import truehordes.world.World;
import truehordes.world.WorldSource;
import truehordes.world.ZoneShape;

public final class WorldTest {
    /** Cell (0,0): chunk (0,0) water, chunk (1,0) a wall on the west edge of tile (8,3), the rest empty ground. */
    private static WorldSource source() throws Exception {
        Path dir = Files.createTempDirectory("th-world");
        byte[] wall = FakeCells.fill(0);
        wall[3 * 8] = (byte) CollisionBits.WALL_W;
        Path p = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).tiles(1, 0, wall).write(dir, "chunkdata_0_0.bin");
        Map<Long, Path> files = new HashMap<>();
        files.put(WorldSource.key(0, 0), p);
        return new WorldSource(0, 0, 0, 0, files, null, List.of(ZoneShape.rect(Terrain.ROAD, 16, 0, 8, 256)));
    }

    private static World built() throws Exception {
        World w = new World();
        w.start(source());
        Check.isTrue(w.awaitBuilt(10_000), "build finished");
        w.tick();
        return w;
    }

    public static void testNotReadyUntilPublished() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = new World();
            Check.equal(World.State.OFF, w.state(), "off before start");
            Check.isTrue(w.blockedAt(20, 4), "blocked while off");
            w.start(source());
            Check.isTrue(w.awaitBuilt(10_000), "build finished");
            Check.isTrue(!w.ready(), "not published before tick");
            w.tick();
            Check.isTrue(w.ready(), "published");
            Check.equal(World.State.READY, w.state(), "ready");
            w.stop();
            Check.equal(World.State.OFF, w.state(), "off after stop");
            Check.isTrue(!w.ready(), "dropped");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testCoarseQueries() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.equal(Terrain.WATER, w.terrainAt(3, 3), "water chunk");
            Check.isTrue(w.blockedAt(3, 3), "water blocks (D16)");
            Check.equal(Terrain.ROAD, w.terrainAt(17, 100), "road zone");
            Check.near(1.0, w.costAt(17, 100), 1e-6, "road cost");
            Check.equal(Terrain.OPEN, w.terrainAt(100, 100), "no zone");
            Check.isTrue(w.canCross(1, 0, Dir.E), "chunk (1,0) to the east");
            Check.isTrue(!w.canCross(1, 0, Dir.W), "chunk (1,0) to the water");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testTileQueries() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.isTrue(!w.walkable(2, 2), "water tile");
            Check.isTrue(w.walkable(9, 3), "ground");
            Check.isTrue(!w.canStep(8, 3, 7, 3), "into water");
            Check.isTrue(w.canStep(9, 4, 9, 5), "open step south");
            Check.isTrue(!w.canStep(9, 4, 9, 4), "not a step");
            Check.isTrue(!w.canStep(9, 4, 11, 4), "too far");
            Check.isTrue(w.canStep(10, 10, 11, 11), "open diagonal");
            Check.equal(CollisionBits.WALL_W, w.tileBits(8, 3), "wall tile bits");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testWallBlocksStepAndDiagonal() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            w.liveTile(12, 12, CollisionBits.WALL_W); // wall between (11,12) and (12,12)
            Check.isTrue(!w.canStep(11, 12, 12, 12), "east into the wall");
            Check.isTrue(!w.canStep(12, 12, 11, 12), "west through the wall");
            Check.isTrue(!w.canStep(11, 11, 12, 12), "diagonal past the wall");
            Check.isTrue(w.canStep(11, 11, 12, 11), "the row above is open");
            w.liveTile(20, 20, CollisionBits.WALL_N); // wall between (20,19) and (20,20)
            Check.isTrue(!w.canStep(20, 19, 20, 20), "south into the wall");
            Check.isTrue(!w.canStep(20, 20, 20, 19), "north through the wall");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testLiveChunkRewritesEntry() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.isTrue(!w.blockedAt(40, 40), "open before");
            w.liveChunk(5, 5, FakeCells.fill(CollisionBits.SOLID));
            Check.isTrue(w.blockedAt(40, 40), "blocked after the chunk changed");
            Check.isTrue(!w.walkable(41, 41), "tile detail follows");
            Check.equal(Terrain.OPEN, w.terrainAt(40, 40), "zone kept (open: no zone)");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testLiveChangeDuringBuildIsAppliedOnPublish() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = new World();
            w.start(source());
            w.liveChunk(6, 6, FakeCells.fill(CollisionBits.SOLID));
            w.liveTile(60, 60, CollisionBits.SOLID);
            Check.isTrue(w.awaitBuilt(10_000), "build finished");
            w.tick();
            Check.isTrue(w.blockedAt(50, 50), "chunk change kept");
            Check.isTrue(!w.walkable(60, 60), "tile change kept");
            Check.isTrue(w.walkable(61, 60), "neighbour tile from the file");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testRestartIgnoresOldBuild() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = new World();
            w.start(source());
            Path dir = Files.createTempDirectory("th-world2");
            Path p = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).write(dir, "chunkdata_0_0.bin");
            Map<Long, Path> files = new HashMap<>();
            files.put(WorldSource.key(0, 0), p);
            w.stop();
            w.start(new WorldSource(0, 0, 0, 0, files, null, List.of()));
            Check.isTrue(w.awaitBuilt(10_000), "second build finished");
            Thread.sleep(200); // give a late first build every chance to publish wrongly
            w.tick();
            Check.isTrue(w.blockedAt(3, 3), "the second world's solid chunk, not the first world's water");
            Check.equal(Terrain.OPEN, w.terrainAt(3, 3), "solid ground without a zone classifies as open");
            Check.equal(Terrain.OPEN, w.terrainAt(17, 100), "no road: the first world's zones are gone");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testQueriesOutsideMap() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.isTrue(w.blockedAt(-10, -10), "negative");
            Check.isTrue(w.blockedAt(5000, 5000), "beyond");
            Check.equal(Terrain.UNKNOWN, w.terrainAt(-1, 3), "unknown");
            Check.isTrue(!w.walkable(-1, -1), "negative tile");
            Check.isTrue(!w.walkable(300, 300), "tile outside");
            Check.isTrue(!w.canStep(-1, 0, 0, 0), "step from outside");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testReportAndSamples() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            w.liveTile(9, 9, CollisionBits.SOLID);
            String r = w.report();
            Check.isTrue(r.contains("READY"), r);
            Check.isTrue(r.contains("1 from map"), r);
            Check.isTrue(r.contains("1 tile changes"), r);
            Check.isTrue(r.contains("water"), "terrain counts: " + r);
            TerrainSample s = w.terrainSample(0, 0, 32, 16, 8);
            Check.equal(4, s.cols, "cols");
            Check.equal(2, s.rows, "rows");
            Check.equal(Terrain.WATER.code, s.codes[0], "first sample water");
            Check.equal(Terrain.ROAD.code, s.codes[2], "third sample road");
            int[] tiles = w.tileSample(0, 0, 16, 16, 1000);
            boolean sawWall = false;
            for (int i = 0; i < tiles.length; i += 3) {
                if (tiles[i] == 8 && tiles[i + 1] == 3 && tiles[i + 2] == CollisionBits.WALL_W) sawWall = true;
            }
            Check.isTrue(sawWall, "wall tile in the sample");
            Check.isTrue(w.tileSample(0, 0, 16, 16, 3).length <= 9, "capped");
            Check.equal(0, w.tileSample(0, 0, 1000, 1000, 100000).length, "too big an area returns nothing");
        } finally {
            Log.setSink(null);
        }
    }
}
```

The first world's chunk (0,0) is WATER and its chunk column 2 is ROAD; seeing OPEN at both proves only the second build was published.

Add `WorldTest.class,` to `SUITES`.

- [ ] **Step 2: Run the build and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `test compile failed` (`World`, `TerrainSample` missing).

- [ ] **Step 3: Implement**

`java/src/truehordes/world/TileCache.java`:

```java
package truehordes.world;

import java.util.LinkedHashMap;
import java.util.Map;

/** The most recently used cells' tiles (same file choice as the build). Thread-safe. */
final class TileCache {
    static final int MAX_CELLS = 64;

    private final WorldSource src;
    private final LinkedHashMap<Long, byte[]> cells = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) { return size() > MAX_CELLS; }
    };

    TileCache(WorldSource src) { this.src = src; }

    synchronized byte[] cell(int cx, int cy) {
        long k = WorldSource.key(cx, cy);
        byte[] tiles = cells.get(k);
        if (tiles == null) {
            boolean inside = cx >= src.minCellX && cx <= src.maxCellX && cy >= src.minCellY && cy <= src.maxCellY;
            tiles = inside ? CellSource.load(src, cx, cy, new BuildReport()) : null;
            if (tiles == null) tiles = CellReader.noData();
            cells.put(k, tiles);
        }
        return tiles;
    }

    synchronized int size() { return cells.size(); }
}
```

`java/src/truehordes/world/TerrainSample.java`:

```java
package truehordes.world;

/** Terrain codes sampled every step tiles, row-major, starting at tile (x0, y0). For the debug map. */
public final class TerrainSample {
    public final int x0, y0, step, cols, rows;
    public final int[] codes;

    TerrainSample(int x0, int y0, int step, int cols, int rows, int[] codes) {
        this.x0 = x0;
        this.y0 = y0;
        this.step = step;
        this.cols = cols;
        this.rows = rows;
        this.codes = codes;
    }
}
```

`java/src/truehordes/world/World.java`:

```java
package truehordes.world;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import truehordes.util.Log;

/**
 * The mod's knowledge of the map for one loaded world. start() builds the coarse grid on a daemon thread; tick() (main
 * thread) publishes it; live changes from the game go into an overlay of whole chunks (arrays replaced, never changed
 * in place) and into the published grid. Queries never throw; anything unknown or outside the map answers blocked.
 */
public final class World {
    public enum State { OFF, BUILDING, READY, FAILED }

    /** Largest area tileSample() will scan, in tiles per side. */
    static final int MAX_TILE_SAMPLE_SIDE = 256;
    private static final int MAX_SAMPLE_CELLS = 40_000;

    private volatile State state = State.OFF;
    private volatile Object buildToken;
    private volatile Grid grid;
    private volatile Grid built;
    private volatile BuildReport report;
    private volatile BuildReport pendingReport;
    private volatile TileCache tiles;
    private final Map<Long, byte[]> live = new ConcurrentHashMap<>();
    private volatile int tileChanges;

    public State state() { return state; }

    public boolean ready() { return grid != null; }

    public synchronized void start(WorldSource src) {
        stop();
        Object token = new Object();
        buildToken = token;
        tiles = new TileCache(src);
        state = State.BUILDING;
        Thread t = new Thread(() -> runBuild(src, token), "TrueHordes-World");
        t.setDaemon(true);
        t.start();
    }

    private void runBuild(WorldSource src, Object token) {
        try {
            BuildReport r = new BuildReport();
            Grid g = GridBuilder.build(src, () -> buildToken != token, r);
            synchronized (this) {
                if (g == null || buildToken != token) return;
                pendingReport = r;
                built = g;
            }
        } catch (Throwable t) {
            synchronized (this) {
                if (buildToken != token) return;
                state = State.FAILED;
            }
            Log.error("world map build failed", t);
        }
    }

    /** Main thread: publishes a finished build and applies live changes received meanwhile. */
    public synchronized void tick() {
        Grid g = built;
        if (g == null) return;
        built = null;
        for (Map.Entry<Long, byte[]> e : live.entrySet()) {
            recompute(g, (int) (e.getKey() >> 32), (int) (long) e.getKey(), e.getValue());
        }
        report = pendingReport;
        grid = g;
        state = State.READY;
        Log.info(report());
    }

    public synchronized void stop() {
        buildToken = null;
        grid = null;
        built = null;
        report = null;
        pendingReport = null;
        tiles = null;
        live.clear();
        tileChanges = 0;
        state = State.OFF;
    }

    /** Tests: waits until a build is waiting to be published (or already is). */
    public boolean awaitBuilt(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (built != null || grid != null) return true;
            if (state == State.FAILED) return false;
            Thread.sleep(5);
        }
        return false;
    }

    // ---- coarse queries (tile coordinates) ----

    public Terrain terrainAt(int x, int y) {
        Grid g = grid;
        return g == null ? Terrain.UNKNOWN : g.terrain(Math.floorDiv(x, 8), Math.floorDiv(y, 8));
    }

    public float costAt(int x, int y) {
        Grid g = grid;
        return g == null ? Float.POSITIVE_INFINITY : g.cost(Math.floorDiv(x, 8), Math.floorDiv(y, 8));
    }

    public boolean blockedAt(int x, int y) {
        Grid g = grid;
        return g == null || g.blocked(Math.floorDiv(x, 8), Math.floorDiv(y, 8));
    }

    public boolean canCross(int chunkX, int chunkY, Dir d) {
        Grid g = grid;
        return g != null && g.canCross(chunkX, chunkY, d);
    }

    // ---- tile queries ----

    public int tileBits(int x, int y) {
        if (grid == null) return CollisionBits.NO_DATA;
        int cx = Math.floorDiv(x, 8), cy = Math.floorDiv(y, 8);
        byte[] chunk = live.get(WorldSource.key(cx, cy));
        if (chunk != null) return chunk[Math.floorMod(y, 8) * 8 + Math.floorMod(x, 8)] & 0xFF;
        TileCache tc = tiles;
        if (tc == null) return CollisionBits.NO_DATA;
        byte[] cell = tc.cell(Math.floorDiv(x, 256), Math.floorDiv(y, 256));
        return cell[Math.floorMod(y, 256) * 256 + Math.floorMod(x, 256)] & 0xFF;
    }

    public boolean walkable(int x, int y) { return CollisionBits.walkable(tileBits(x, y)); }

    /** Neighbouring tiles only (diagonals need both orthogonal routes clear). */
    public boolean canStep(int x1, int y1, int x2, int y2) {
        int dx = x2 - x1, dy = y2 - y1;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || (dx == 0 && dy == 0)) return false;
        if (dx == 0 || dy == 0) return step(x1, y1, dx, dy);
        return step(x1, y1, dx, 0) && step(x1 + dx, y1, 0, dy) && step(x1, y1, 0, dy) && step(x1, y1 + dy, dx, 0);
    }

    private boolean step(int x, int y, int dx, int dy) {
        int from = tileBits(x, y), to = tileBits(x + dx, y + dy);
        if (!CollisionBits.walkable(from) || !CollisionBits.walkable(to)) return false;
        if (dx == 1) return (to & CollisionBits.WALL_W) == 0;
        if (dx == -1) return (from & CollisionBits.WALL_W) == 0;
        if (dy == -1) return (from & CollisionBits.WALL_N) == 0;
        return (to & CollisionBits.WALL_N) == 0;
    }

    // ---- live changes (main thread) ----

    /** An area loaded around a player: the game's own 64 tile bits for that chunk (index ty * 8 + tx). */
    public void liveChunk(int chunkX, int chunkY, byte[] bits64) {
        if (state == State.OFF || bits64 == null || bits64.length != 64) return;
        byte[] copy = bits64.clone();
        live.put(WorldSource.key(chunkX, chunkY), copy);
        Grid g = grid;
        if (g != null) recompute(g, chunkX, chunkY, copy);
    }

    /** One tile changed (building, breaking, burning). */
    public void liveTile(int x, int y, int bits) {
        if (state == State.OFF) return;
        int cx = Math.floorDiv(x, 8), cy = Math.floorDiv(y, 8);
        long k = WorldSource.key(cx, cy);
        byte[] old = live.get(k);
        byte[] chunk = old != null ? old.clone() : baseChunk(cx, cy);
        chunk[Math.floorMod(y, 8) * 8 + Math.floorMod(x, 8)] = (byte) bits;
        live.put(k, chunk);
        tileChanges++;
        Grid g = grid;
        if (g != null) recompute(g, cx, cy, chunk);
    }

    private byte[] baseChunk(int cx, int cy) {
        byte[] out = new byte[64];
        TileCache tc = tiles;
        if (tc == null) {
            java.util.Arrays.fill(out, (byte) CollisionBits.NO_DATA);
            return out;
        }
        byte[] cell = tc.cell(Math.floorDiv(cx, 32), Math.floorDiv(cy, 32));
        int ox = Math.floorMod(cx, 32) * 8, oy = Math.floorMod(cy, 32) * 8;
        for (int ty = 0; ty < 8; ty++) System.arraycopy(cell, (oy + ty) * 256 + ox, out, ty * 8, 8);
        return out;
    }

    private static void recompute(Grid g, int cx, int cy, byte[] chunk) {
        if (g.index(cx, cy) < 0) return;
        int zone = ChunkInfo.zone(g.info(cx, cy));
        g.set(cx, cy, ChunkInfo.summarise(chunk, 0, 8, zone), ChunkInfo.masks(chunk, 0, 8));
    }

    // ---- reporting and debug ----

    public long memoryBytes() {
        Grid g = grid;
        TileCache tc = tiles;
        return (g == null ? 0 : g.memoryBytes()) + live.size() * 80L + (tc == null ? 0 : tc.size() * 65_536L);
    }

    public String report() {
        StringBuilder sb = new StringBuilder("world map ").append(state);
        BuildReport r = report;
        if (r != null) {
            sb.append(String.format(Locale.ROOT, " | built in %.1f s | %.1f MB", r.millis / 1000.0, memoryBytes() / 1048576.0));
            sb.append(" | chunks:");
            for (Terrain t : Terrain.values()) {
                int n = r.terrainCounts[t.code];
                if (n > 0) sb.append(' ').append(t.name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(' ').append(n).append(',');
            }
            sb.setLength(sb.length() - 1);
            sb.append(" | cells: ").append(r.cellsFromSave).append(" from save, ").append(r.cellsFromMap).append(" from map, ")
                .append(r.cellsMissing).append(" missing, ").append(r.filesDamaged).append(" damaged");
            if (!r.damaged.isEmpty()) sb.append(" (").append(String.join("; ", r.damaged)).append(')');
        }
        sb.append(" | live: ").append(live.size()).append(" chunks, ").append(tileChanges).append(" tile changes");
        return sb.toString();
    }

    /** Terrain codes every step tiles (step rounded up to a multiple of 8) over the rectangle; null if not ready. */
    public TerrainSample terrainSample(int minX, int minY, int maxX, int maxY, int step) {
        Grid g = grid;
        if (g == null) return null;
        int s = Math.max(8, (step + 7) / 8 * 8);
        int x0 = Math.floorDiv(minX, s) * s, y0 = Math.floorDiv(minY, s) * s;
        int cols = Math.max(0, (maxX - x0 + s - 1) / s), rows = Math.max(0, (maxY - y0 + s - 1) / s);
        if ((long) cols * rows > MAX_SAMPLE_CELLS) return null;
        int[] codes = new int[cols * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                codes[r * cols + c] = g.terrain(Math.floorDiv(x0 + c * s, 8), Math.floorDiv(y0 + r * s, 8)).code;
            }
        }
        return new TerrainSample(x0, y0, s, cols, rows, codes);
    }

    /** (x, y, bits) for each tile in [minX, maxX) x [minY, maxY) with any bit set, at most max tiles; empty if too big. */
    public int[] tileSample(int minX, int minY, int maxX, int maxY, int max) {
        if (grid == null || maxX - minX > MAX_TILE_SAMPLE_SIDE || maxY - minY > MAX_TILE_SAMPLE_SIDE || max <= 0) return new int[0];
        int[] out = new int[Math.min(max, (maxX - minX) * Math.max(0, maxY - minY)) * 3];
        int n = 0;
        for (int y = minY; y < maxY && n < out.length; y++) {
            for (int x = minX; x < maxX && n < out.length; x++) {
                int b = tileBits(x, y);
                if (b == 0) continue;
                out[n++] = x;
                out[n++] = y;
                out[n++] = b;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
```

- [ ] **Step 4: Run the build and confirm the tests pass**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `84 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java/src/truehordes/world java/test/truehordes/test/WorldTest.java java/test/truehordes/test/TestRunner.java
git commit -m "SP2: World service - background build, publish on tick, live overlay, tile queries, report"
```

---

### Task 6: Real-map test (vanilla files from the game folder)

**Files:**
- Create: `java/test/truehordes/test/RealMapTest.java`
- Modify: `java/test/truehordes/test/TestRunner.java` (add `RealMapTest.class`)

**Interfaces:**
- Consumes: `World`, `WorldSource`, `ZoneShape`, `Terrain` (Tasks 2–5); system property `truehordes.gameJar` (set by `TestRunner` from `build.ps1`).

Spot checks (found by scanning the 42.21 files while planning; Nav rectangle `x=12309 y=6697 w=14 h=97` in `objects.lua` crosses a river):
- tile (12316, 6718): on the bridge: walkable, chunk terrain `ROAD`;
- tile (12300, 6718): river beside the bridge: not walkable;
- tile (12330, 6722): inside an all-water chunk (origin 12328, 6720): terrain `WATER`;
- step (12316, 6716) → (12316, 6717): along the bridge: allowed.

- [ ] **Step 1: Write the test**

`java/test/truehordes/test/RealMapTest.java`:

```java
package truehordes.test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import truehordes.util.Log;
import truehordes.world.Terrain;
import truehordes.world.World;
import truehordes.world.WorldSource;
import truehordes.world.ZoneShape;

/** Builds the grid from the installed vanilla map (Muldraugh, KY): every file reads, timing, and known spots. */
public final class RealMapTest {
    private static final Pattern FILE = Pattern.compile("chunkdata_(\\d+)_(\\d+)\\.bin");
    private static final Pattern NAV = Pattern.compile(
        "type = \"Nav\", x = (\\d+), y = (\\d+), z = 0, width = (\\d+), height = (\\d+)");

    private static Path mapDir() {
        String jar = System.getProperty("truehordes.gameJar");
        return jar == null ? null : Paths.get(jar).getParent().resolve("media").resolve("maps").resolve("Muldraugh, KY");
    }

    public static void testVanillaMap() throws Exception {
        Path dir = mapDir();
        if (dir == null || !Files.isDirectory(dir)) {
            System.out.println("RealMapTest: vanilla map folder not found, skipped");
            return;
        }
        Map<Long, Path> files = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "chunkdata_*.bin")) {
            for (Path p : ds) {
                Matcher m = FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int cx = Integer.parseInt(m.group(1)), cy = Integer.parseInt(m.group(2));
                files.put(WorldSource.key(cx, cy), p);
                minX = Math.min(minX, cx);
                maxX = Math.max(maxX, cx);
                minY = Math.min(minY, cy);
                maxY = Math.max(maxY, cy);
            }
        }
        List<ZoneShape> roads = navRects(dir.resolve("objects.lua"));
        List<String> log = new ArrayList<>();
        Log.setSink(log::add);
        World w = new World();
        try {
            w.start(new WorldSource(minX, minY, maxX, maxY, files, null, roads));
            Check.isTrue(w.awaitBuilt(120_000), "built within 2 minutes");
            w.tick();
            String report = w.report();
            System.out.println("RealMapTest: " + files.size() + " files, " + roads.size() + " road zones; " + report);
            Check.isTrue(report.contains(" 0 damaged"), "no damaged files: " + report);
            Check.isTrue(report.contains(files.size() + " from map"), "every file read: " + report);
            Check.isTrue(w.walkable(12316, 6718), "bridge tile walkable");
            Check.equal(Terrain.ROAD, w.terrainAt(12316, 6718), "bridge chunk is road");
            Check.isTrue(!w.walkable(12300, 6718), "river beside the bridge");
            Check.equal(Terrain.WATER, w.terrainAt(12330, 6722), "all-water chunk");
            Check.isTrue(w.blockedAt(12330, 6722), "water blocks (D16)");
            Check.isTrue(w.canStep(12316, 6716, 12316, 6717), "walk along the bridge");
            Check.isTrue(!w.blockedAt(12316, 6718), "bridge chunk connects");
        } finally {
            w.stop();
            Log.setSink(null);
        }
    }

    private static List<ZoneShape> navRects(Path objects) throws IOException {
        List<ZoneShape> out = new ArrayList<>();
        if (!Files.exists(objects)) return out;
        Matcher m = NAV.matcher(Files.readString(objects));
        while (m.find()) {
            out.add(ZoneShape.rect(Terrain.ROAD, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        }
        return out;
    }
}
```

Add `RealMapTest.class,` to `SUITES`.

- [ ] **Step 2: Run the build**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: a line `RealMapTest: 4065 files, 5104 road zones; world map READY | built in X s | Y MB | ...` and `85 passed, 0 failed`. Record X and Y for Task 9. If X is above 5 seconds, note it for the user (the spec's cache follow-up); do not add a cache in this plan.

If `Files.readString` fails on the file's encoding, replace it with `new String(Files.readAllBytes(objects), java.nio.charset.StandardCharsets.ISO_8859_1)` (the regex only needs ASCII).

- [ ] **Step 3: Commit**

```bash
git add java/test/truehordes/test/RealMapTest.java java/test/truehordes/test/TestRunner.java
git commit -m "SP2: real-map test on the vanilla files (bridge, river, timing)"
```

---

### Task 7: Game bridge - snapshot, core wiring, live hooks

**Files:**
- Create: `java/src/truehordes/ZoneSnapshot.java`
- Create: `java/src/truehordes/LiveBits.java`
- Modify: `java/src/truehordes/core/GameEnv.java`
- Modify: `java/src/truehordes/core/Core.java`
- Modify: `java/src/truehordes/GameEnvImpl.java`
- Modify: `java/src/truehordes/HookTargets.java`
- Modify: `java/src/truehordes/Patches.java`
- Modify: `java/src/truehordes/Hooks.java`
- Modify: `java/src/truehordes/Main.java`
- Modify: `java/test/truehordes/test/FakeEnv.java`
- Modify: `java/test/truehordes/test/CoreTest.java`
- Modify: `java/test/truehordes/test/VerifyTargetsTest.java`

**Interfaces:**
- Consumes: `World`, `WorldSource`, `ZoneShape`, `Terrain` (Tasks 2–5).
- Produces: `GameEnv.worldSource() -> WorldSource` (null if unavailable); `Core.world() -> World`; `HookTargets.LIVE` (`{MCD, "addChunkToWorld"}`, `{MCD, "squareChanged"}`), `HookTargets.RULES` (`isSolid`, `isBlockedN`, `isBlockedW`, `isWater`, `isRoom`), `HookTargets.missing(String[][] targets, Function<String, Set<String>>)`; `Hooks.onChunkLoaded(IsoChunk)`, `Hooks.onSquareChanged(IsoGridSquare)`; `LiveBits.init() -> String` (null when ready), `LiveBits.available()`, `LiveBits.chunkBits() -> byte[]`, `LiveBits.squareBits(IsoGridSquare) -> int`.

- [ ] **Step 1: Write the failing tests**

In `java/test/truehordes/test/FakeEnv.java` add a field and method (with the import `truehordes.world.WorldSource`):

```java
    public WorldSource worldSource;

    @Override public WorldSource worldSource() { return worldSource; }
```

Append to `java/test/truehordes/test/CoreTest.java` (keep its existing helpers; this test only needs `FakeEnv`, `Core`, `Files`):

```java
    public static void testCoreStartsAndStopsTheWorldMap() throws Exception {
        truehordes.util.Log.setSink(line -> {});
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("th-core-world");
            FakeEnv env = new FakeEnv(dir.resolve("state.bin"));
            env.worldSource = new truehordes.world.WorldSource(0, 0, 0, 0, new java.util.HashMap<>(), null, java.util.List.of());
            truehordes.core.Core core = new truehordes.core.Core(env);
            core.tick();
            Check.isTrue(core.world().state() != truehordes.world.World.State.OFF, "build started by the first tick");
            Check.isTrue(core.world().awaitBuilt(10_000), "built");
            core.tick();
            Check.equal(truehordes.world.World.State.READY, core.world().state(), "published by a later tick");
            core.stop();
            Check.equal(truehordes.world.World.State.OFF, core.world().state(), "dropped on stop");
        } finally {
            truehordes.util.Log.setSink(null);
        }
    }

    public static void testNoWorldSourceStillRuns() throws Exception {
        truehordes.util.Log.setSink(line -> {});
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("th-core-world");
            FakeEnv env = new FakeEnv(dir.resolve("state.bin"));
            truehordes.core.Core core = new truehordes.core.Core(env);
            core.tick();
            Check.equal(truehordes.core.Core.State.RUNNING, core.state(), "core runs without a map");
            Check.equal(truehordes.world.World.State.OFF, core.world().state(), "no map");
        } finally {
            truehordes.util.Log.setSink(null);
        }
    }
```

Append to `java/test/truehordes/test/VerifyTargetsTest.java`:

```java
    public static void testLiveTargetsAndRulesExistInGameJar() throws Exception {
        String jarPath = System.getProperty("truehordes.gameJar");
        Check.isTrue(jarPath != null, "build passes the game jar path to the test runner");
        try (ZipFile jar = new ZipFile(jarPath)) {
            List<String> missing = HookTargets.missing(HookTargets.LIVE, cls -> {
                try {
                    return ClassFileMethods.methodNames(jar, cls);
                } catch (Exception e) {
                    return Set.of();
                }
            });
            Check.equal(List.of(), missing, "missing live hook targets");
            Set<String> mcd = ClassFileMethods.methodNames(jar, HookTargets.MCD);
            for (String rule : HookTargets.RULES) Check.isTrue(mcd.contains(rule), "rule method " + rule);
        }
    }
```

- [ ] **Step 2: Run the build and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `compile failed` or `test compile failed` (`worldSource()`, `world()`, `LIVE` missing).

- [ ] **Step 3: Implement**

`java/src/truehordes/core/GameEnv.java`: add the import `truehordes.world.WorldSource;` and:

```java
    /** Snapshot of the loaded world's map files, save folder and zones (main thread); null if unavailable. */
    WorldSource worldSource();
```

`java/src/truehordes/core/Core.java`:
- add `import truehordes.world.World;` and `import truehordes.world.WorldSource;`
- add the field `private final World world = new World();` and the accessor `public World world() { return world; }`
- in `tick()`, after the `if (state == State.IDLE && !start()) return;` line, add `world.tick();`
- at the end of `start()`, just before `return true;`, add:

```java
        try {
            WorldSource src = env.worldSource();
            if (src != null) world.start(src);
            else Log.warn("map data unavailable; terrain knowledge is off");
        } catch (Throwable t) {
            Log.error("could not read the map data; terrain knowledge is off", t);
        }
```

- in `stop()`, after `store.clear();` add `world.stop();`

Keep the word "world" out of these warnings: `CoreTest` checks for the save-mismatch warning by looking for any WARN line containing "world".

`java/src/truehordes/ZoneSnapshot.java`:

```java
package truehordes;

import java.util.ArrayList;
import java.util.List;
import truehordes.world.Terrain;
import truehordes.world.ZoneShape;
import zombie.iso.zones.Zone;

/** Copies the game's ground-level zones that matter for walking into plain ZoneShapes. Main thread only. */
final class ZoneSnapshot {
    private ZoneSnapshot() {}

    static List<ZoneShape> copy(List<Zone> zones) {
        List<ZoneShape> out = new ArrayList<>();
        for (Zone z : zones) {
            if (z == null || z.z != 0) continue;
            Terrain t = Terrain.fromZoneType(z.type);
            if (t == null) continue;
            if (z.isRectangle()) out.add(ZoneShape.rect(t, z.x, z.y, z.w, z.h));
            else if (z.isPolygon() && z.points.size() >= 6) out.add(ZoneShape.polygon(t, z.points.toArray()));
            else if (z.isPolyline() && z.points.size() >= 2 && z.polylineWidth > 0) {
                out.add(ZoneShape.polyline(t, z.points.toArray(), z.polylineWidth));
            }
        }
        return out;
    }
}
```

`java/src/truehordes/GameEnvImpl.java`: add imports `java.util.ArrayList`, `java.util.HashMap`, `java.util.List`, `java.util.Map`, `truehordes.world.WorldSource`, `zombie.iso.IsoLot`, `zombie.iso.IsoMetaGrid`, `zombie.iso.MapFiles`, and:

```java
    @Override
    public WorldSource worldSource() {
        IsoMetaGrid grid = IsoWorld.instance == null ? null : IsoWorld.instance.getMetaGrid();
        if (grid == null || grid.maxX < grid.minX) return null;
        List<Map<String, String>> folders = new ArrayList<>();
        for (MapFiles mf : IsoLot.MapFiles) folders.add(new HashMap<>(mf.infoFileNames));
        Path saveDir = Paths.get(ZomboidFileSystem.instance.getFileNameInCurrentSave("chunkdata"));
        return new WorldSource(grid.minX, grid.minY, grid.maxX, grid.maxY, WorldSource.resolveMapFiles(folders),
            saveDir, ZoneSnapshot.copy(grid.zones));
    }
```

`java/src/truehordes/LiveBits.java`:

```java
package truehordes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import zombie.MapCollisionData;
import zombie.iso.IsoGridSquare;
import truehordes.world.CollisionBits;

/**
 * Reads the game's own collision bits so the mod never disagrees with it: after addChunkToWorld the chunk's 64 bits
 * are in MapCollisionData's private squares array; for one changed square the game's private rule methods are called.
 */
final class LiveBits {
    private static Field squares;
    private static Method solid, blockedN, blockedW, water, room;
    private static volatile boolean available;

    private LiveBits() {}

    /** Resolves the reflection targets once. Returns null when ready, otherwise why live updates are off. */
    static String init() {
        try {
            squares = MapCollisionData.class.getDeclaredField("squares");
            squares.setAccessible(true);
            solid = rule(HookTargets.RULES[0]);
            blockedN = rule(HookTargets.RULES[1]);
            blockedW = rule(HookTargets.RULES[2]);
            water = rule(HookTargets.RULES[3]);
            room = rule(HookTargets.RULES[4]);
            available = true;
            return null;
        } catch (Throwable t) {
            available = false;
            return t.toString();
        }
    }

    private static Method rule(String name) throws NoSuchMethodException {
        Method m = MapCollisionData.class.getDeclaredMethod(name, IsoGridSquare.class);
        m.setAccessible(true);
        return m;
    }

    static boolean available() { return available; }

    static byte[] chunkBits() throws IllegalAccessException {
        return ((byte[]) squares.get(MapCollisionData.instance)).clone();
    }

    static int squareBits(IsoGridSquare sq) throws ReflectiveOperationException {
        MapCollisionData m = MapCollisionData.instance;
        int bits = 0;
        if ((Boolean) solid.invoke(m, sq)) bits |= CollisionBits.SOLID;
        if ((Boolean) blockedN.invoke(m, sq)) bits |= CollisionBits.WALL_N;
        if ((Boolean) blockedW.invoke(m, sq)) bits |= CollisionBits.WALL_W;
        if ((Boolean) water.invoke(m, sq)) bits |= CollisionBits.WATER;
        if ((Boolean) room.invoke(m, sq)) bits |= CollisionBits.ROOM;
        return bits;
    }
}
```

`java/src/truehordes/HookTargets.java`: add after `HOT_SAVE_SERVER`:

```java
    /** An area loaded around a player (MapCollisionData has just computed its 64 tile bits). */
    public static final String CHUNK_LOADED = "addChunkToWorld";
    /** One tile changed (building, breaking, burning). */
    public static final String SQUARE_CHANGED = "squareChanged";

    /** Live map updates. Optional: if missing, the world map still builds from the files. */
    public static final String[][] LIVE = {
        {MCD, CHUNK_LOADED},
        {MCD, SQUARE_CHANGED},
    };

    /** MapCollisionData's private per-tile rule methods, called by LiveBits. */
    public static final String[] RULES = {"isSolid", "isBlockedN", "isBlockedW", "isWater", "isRoom"};
```

and replace the body of `missing` with a delegate plus a general version:

```java
    /** Returns "class.method" for every required target the lookup does not report. */
    public static List<String> missing(Function<String, Set<String>> methodsOfClass) {
        return missing(ALL, methodsOfClass);
    }

    public static List<String> missing(String[][] targets, Function<String, Set<String>> methodsOfClass) {
        List<String> out = new ArrayList<>();
        for (String[] t : targets) {
            Set<String> names = methodsOfClass.apply(t[0]);
            if (names == null || !names.contains(t[1])) out.add(t[0] + "." + t[1]);
        }
        return out;
    }
```

`java/src/truehordes/Patches.java`: add imports `zombie.iso.IsoChunk`, `zombie.iso.IsoGridSquare`, and the two classes:

```java
    /** An area loaded around a player; the game has just computed its collision bits. */
    @Patch(className = HookTargets.MCD, methodName = HookTargets.CHUNK_LOADED)
    public static class ChunkLoaded {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoChunk chunk) { Hooks.onChunkLoaded(chunk); }
    }

    /** One tile changed (building, breaking, burning). */
    @Patch(className = HookTargets.MCD, methodName = HookTargets.SQUARE_CHANGED)
    public static class SquareChanged {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGridSquare square) { Hooks.onSquareChanged(square); }
    }
```

`java/src/truehordes/Hooks.java`: add imports `zombie.iso.IsoChunk`, `zombie.iso.IsoGridSquare`, `truehordes.world.World`, and:

```java
    public static void onChunkLoaded(IsoChunk chunk) {
        Core c = core;
        if (c == null || chunk == null || !LiveBits.available()) return;
        try {
            World w = c.world();
            if (w.state() == World.State.OFF) return;
            w.liveChunk(chunk.wx, chunk.wy, LiveBits.chunkBits());
        } catch (Throwable t) {
            Log.errorOnce("hook:chunk:" + t.getClass().getName(), "live map update (area loaded) failed", t);
        }
    }

    public static void onSquareChanged(IsoGridSquare square) {
        Core c = core;
        if (c == null || square == null || square.z != 0 || !LiveBits.available()) return;
        try {
            World w = c.world();
            if (w.state() == World.State.OFF) return;
            w.liveTile(square.x, square.y, LiveBits.squareBits(square));
        } catch (Throwable t) {
            Log.errorOnce("hook:square:" + t.getClass().getName(), "live map update (tile changed) failed", t);
        }
    }
```

`java/src/truehordes/Main.java`: inside the `try` after the existing `if/else` on `missing`, add:

```java
            List<String> liveMissing = HookTargets.missing(HookTargets.LIVE, Main::declaredMethodNames);
            String why = liveMissing.isEmpty() ? LiveBits.init() : "game methods missing: " + liveMissing;
            if (why == null) Log.info("live map updates on");
            else Log.warn("live map updates off (" + why + "); the world map still builds from the map files");
```

- [ ] **Step 4: Run the build and confirm the tests pass**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `88 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java/src/truehordes java/test/truehordes/test
git commit -m "SP2: game bridge - map/zone snapshot, core owns the world map, live hooks"
```

---

### Task 8: Lua - `Hordes_world()` and the terrain layer on the debug map

**Files:**
- Modify: `java/src/truehordes/LuaApi.java`
- Modify: `mod/Contents/mods/TrueHordes/42/media/lua/shared/TrueHordes/Hordes_Core.lua`
- Modify: `mod/Contents/mods/TrueHordes/42/media/lua/client/TrueHordes/Hordes_DebugMap.lua`

**Interfaces:**
- Consumes: `Core.world()`, `World.report()`, `World.terrainSample`, `World.tileSample` (Tasks 5, 7).
- Produces: Lua globals `Hordes_world()`, `TrueHordes.terrain(minX, minY, maxX, maxY, step)` → table `{x0, y0, step, cols, rows, [1..n] = code}` or nil; `TrueHordes.tiles(minX, minY, maxX, maxY, max)` → table `{n, [1..3n] = x, y, bits}` or nil. Java globals `Hordes_j_world`, `Hordes_j_terrain`, `Hordes_j_tiles`.

- [ ] **Step 1: Java API**

Add to `LuaApi.java` (imports `truehordes.world.TerrainSample`, `truehordes.world.World`):

```java
    @LuaMethod(name = "Hordes_j_world", global = true)
    public static String world() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.world().report();
    }

    /** Debug-map terrain codes for a rectangle, or nil when the world map is not ready. */
    @LuaMethod(name = "Hordes_j_terrain", global = true)
    public static KahluaTable terrain(Object minX, Object minY, Object maxX, Object maxY, Object step) {
        Core c = Hooks.core();
        if (c == null) return null;
        try {
            TerrainSample s = c.world().terrainSample((int) LuaArgs.toFloat(minX, 0), (int) LuaArgs.toFloat(minY, 0),
                (int) LuaArgs.toFloat(maxX, 0), (int) LuaArgs.toFloat(maxY, 0), LuaArgs.toInt(step, 8));
            if (s == null) return null;
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("x0", (double) s.x0);
            t.rawset("y0", (double) s.y0);
            t.rawset("step", (double) s.step);
            t.rawset("cols", (double) s.cols);
            t.rawset("rows", (double) s.rows);
            for (int i = 0; i < s.codes.length; i++) t.rawset((double) (i + 1), (double) s.codes[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:terrain:" + t.getClass().getName(), "terrain snapshot failed", t);
            return null;
        }
    }

    /** Debug-map tiles with any collision bit set: n, then x, y, bits triples; nil when not ready. */
    @LuaMethod(name = "Hordes_j_tiles", global = true)
    public static KahluaTable tiles(Object minX, Object minY, Object maxX, Object maxY, Object max) {
        Core c = Hooks.core();
        if (c == null || !c.world().ready()) return null;
        try {
            int[] s = c.world().tileSample((int) LuaArgs.toFloat(minX, 0), (int) LuaArgs.toFloat(minY, 0),
                (int) LuaArgs.toFloat(maxX, 0), (int) LuaArgs.toFloat(maxY, 0), LuaArgs.toInt(max, 20000));
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("n", (double) (s.length / 3));
            for (int i = 0; i < s.length; i++) t.rawset((double) (i + 1), (double) s[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:tiles:" + t.getClass().getName(), "tile snapshot failed", t);
            return null;
        }
    }
```

- [ ] **Step 2: Lua wrappers**

In `Hordes_Core.lua`, after `function Hordes_clearTest() ... end` add:

```lua
function Hordes_world() return call("world") end
```

and after the existing `TrueHordes.snapshot` function add:

```lua
--- Debug-map terrain codes for a rectangle (see LuaApi.terrain), or nil when the world map is not ready.
function TrueHordes.terrain(minX, minY, maxX, maxY, step)
    local j = J()
    if not j then return nil end
    return j.terrain(minX, minY, maxX, maxY, step)
end

--- Debug-map collision tiles for a small rectangle (see LuaApi.tiles), or nil.
function TrueHordes.tiles(minX, minY, maxX, maxY, max)
    local j = J()
    if not j then return nil end
    return j.tiles(minX, minY, maxX, maxY, max)
end
```

- [ ] **Step 3: Terrain layer on the debug map**

In `Hordes_DebugMap.lua`:

1. Below `local MAX_POINTS = 4000` add:

```lua
local TERRAIN_MS = 1000
local TILE_ZOOM = 4 -- px per tile at which walls are drawn
-- Terrain codes (Java truehordes.world.Terrain) to colours.
local TERRAIN_COLOURS = {
    [1] = { 0.45, 0.45, 0.45 }, -- road
    [2] = { 0.55, 0.45, 0.30 }, -- town
    [3] = { 0.60, 0.55, 0.20 }, -- farm
    [4] = { 0.30, 0.42, 0.25 }, -- open
    [5] = { 0.15, 0.40, 0.15 }, -- forest
    [6] = { 0.05, 0.25, 0.08 }, -- deep forest
    [7] = { 0.00, 0.00, 0.00 }, -- building
    [8] = { 0.15, 0.30, 0.70 }, -- water
}
```

2. In `Hordes_MapPanel:new`, before `return o`, add:

```lua
    o.showTerrain = true
    o.terrain = nil
    o.tiles = nil
    o.lastTerrain = 0
```

3. Add these functions after `Hordes_MapPanel:fetch`:

```lua
function Hordes_MapPanel:createChildren()
    ISPanel.createChildren(self)
    self.terrainButton = ISButton:new(self.width - 90, 2, 86, 18, "Terrain: on", self, Hordes_MapPanel.onToggleTerrain)
    self.terrainButton:initialise()
    self:addChild(self.terrainButton)
end

function Hordes_MapPanel:onToggleTerrain()
    self.showTerrain = not self.showTerrain
    self.terrainButton:setTitle(self.showTerrain and "Terrain: on" or "Terrain: off")
end

function Hordes_MapPanel:fetchTerrain()
    local now = getTimestampMs()
    if self.terrain and now - self.lastTerrain < TERRAIN_MS then return end
    self.lastTerrain = now
    local halfW = self.width / 2 / self.zoom
    local halfH = self.height / 2 / self.zoom
    local step = math.max(8, math.ceil(8 / self.zoom))
    self.terrain = TrueHordes.terrain(self.centerX - halfW, self.centerY - halfH,
        self.centerX + halfW, self.centerY + halfH, step)
    if self.zoom >= TILE_ZOOM then
        self.tiles = TrueHordes.tiles(math.floor(self.centerX - halfW), math.floor(self.centerY - halfH),
            math.ceil(self.centerX + halfW), math.ceil(self.centerY + halfH), 20000)
    else
        self.tiles = nil
    end
end

function Hordes_MapPanel:drawTerrain()
    local t = self.terrain
    if not t then return end
    local size = t.step * self.zoom
    for r = 0, t.rows - 1 do
        for c = 0, t.cols - 1 do
            local colour = TERRAIN_COLOURS[t[r * t.cols + c + 1]]
            if colour then
                local sx, sy = self:worldToScreen(t.x0 + c * t.step, t.y0 + r * t.step)
                self:drawRect(sx, sy, size + 1, size + 1, 0.85, colour[1], colour[2], colour[3])
            end
        end
    end
    local tiles = self.tiles
    if not tiles then return end
    local z = self.zoom
    for i = 1, tiles.n * 3, 3 do
        local sx, sy = self:worldToScreen(tiles[i], tiles[i + 1])
        local bits = tiles[i + 2]
        if bits % 2 == 1 then self:drawRect(sx, sy, z, z, 0.9, 0.1, 0.1, 0.1) end -- solid
        if math.floor(bits / 2) % 2 == 1 then self:drawRect(sx, sy, z, 2, 1, 0.95, 0.95, 0.95) end -- wall north
        if math.floor(bits / 4) % 2 == 1 then self:drawRect(sx, sy, 2, z, 1, 0.95, 0.95, 0.95) end -- wall west
    end
end
```

4. In `Hordes_MapPanel:prerender`, after `self:fetch()` add:

```lua
    if self.showTerrain then
        self:fetchTerrain()
        self:drawTerrain()
    end
```

5. In `onMouseWheel`, `onMouseMove` (inside the `if`), and `onRightMouseDown`, next to each `self.snapshot = nil` add `self.terrain = nil`.

6. Add `require "ISUI/ISButton"` under the existing `require` lines.

- [ ] **Step 4: Run the build**

Run: `powershell -ExecutionPolicy Bypass -File tools\build.ps1`
Expected: `88 passed, 0 failed` (`LuaSyntaxTest` compiles both Lua files with the game's compiler).

- [ ] **Step 5: Commit**

```bash
git add java/src/truehordes/LuaApi.java mod/Contents/mods/TrueHordes/42/media/lua
git commit -m "SP2: Hordes_world() and the debug map's terrain and wall layer"
```

---

### Task 9: In-game checklist, design status, deploy (left uncommitted for the user)

**Files:**
- Modify: `docs/TESTING.md`
- Modify: `design.md`

- [ ] **Step 1: Add the in-game checklist**

Append to `docs/TESTING.md`:

```markdown

## Sub-project 2 acceptance: world knowledge (single player, debug)

Setup: `tools\deploy.ps1` (Claude runs it), then load a save with True Hordes and ZombieBuddy.

| # | Do | Expect |
|---|---|---|
| 1 | Load the save; open `console.txt` | `live map updates on`, then a few seconds after `started`: `world map READY | built in ... s | ... MB | chunks: ...` |
| 2 | Lua console: `Hordes_world()` | `READY`, the build time, chunk counts per terrain, `cells: ... from save, ... from map, 0 missing, 0 damaged` |
| 3 | `Hordes_map()`, zoom out | Coloured terrain: roads grey, towns tan, farms yellow, forest green (darker deep forest), water blue, buildings black; it matches the in-game world map |
| 4 | Find a river with a road bridge (for example Muldraugh's south-east, around 12316, 6718) | River blue, the bridge grey across it (not blue) |
| 5 | Zoom in until walls appear (4 px per tile or more) | White lines on wall edges, dark squares on solid tiles, around the buildings near you |
| 6 | Build a wall (or break a window, or chop a tree) next to you, then look at the map zoomed in | The wall appears (or the window gap / tree spot changes) within a second; `Hordes_world()` shows more `tile changes` |
| 7 | Save and quit, reload, `Hordes_world()` and the map | The wall is still there; `from save` is above 0 |
| 8 (optional) | Dedicated server | Server `console.txt` shows the `world map READY` line |
```

- [ ] **Step 2: Record status in `design.md`**

In the Progress table, replace row 2's text with:

```
| 2 | **Built on branch `sp2-world-knowledge` (2026-10-10); in-game acceptance pending** | Coarse 8×8-tile grid of the whole map (about 30 MB) built on a background thread from the collision files (save copy first) and zones; tile detail on request; live updates from `MapCollisionData.addChunkToWorld` / `squareChanged`; `Hordes_world()`; terrain and wall layer on the debug map. Vanilla map build: X s, Y MB (from RealMapTest). Acceptance: `docs/TESTING.md` sub-project 2 rows 1–7 |
```

with X and Y from Task 6. In section 6 ("Facts"), add one line under the collision data facts: `- Collision chunk type 5 ("no map here") exists in the vanilla files' edge strips; the game's Java reader does not know it (sub-project 2).` If section 6 has no collision-data list, add the line at the end of section 6.

- [ ] **Step 3: Build and deploy**

Run: `powershell -ExecutionPolicy Bypass -File tools\deploy.ps1`
Expected: `88 passed, 0 failed`, `Built ...`, `Deployed to ...\Zomboid\Workshop\TrueHordes`.

- [ ] **Step 4: Leave uncommitted**

Do not commit `docs/TESTING.md` and `design.md`: tell the user they are uncommitted on `sp2-world-knowledge` for them to review and commit.
