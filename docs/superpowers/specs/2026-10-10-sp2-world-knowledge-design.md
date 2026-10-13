# Sub-project 2: world knowledge (design)

Parent design: `design.md` (sections 4.1, 6, 7). Decisions it rests on: D4 (no pre-programmed routes), D14 (Java
core, Lua helper), D16 (water blocks everywhere). Approved in conversation on 2026-10-10.

## Goal

The mod's knowledge of the map: for any place, how hard it is to walk there and whether a zombie can get from one
spot to the next. A coarse grid covers the whole map; exact tile detail is available on request. It stays correct
as the player builds and breaks things. **No zombie uses it yet**: the game plays exactly like vanilla, and the only
visible result is a terrain layer on the debug map and a console report.

## Out of scope

Zombies using the data (sub-projects 3, 4, 6); route planning (D4: there is none); upper floors and basements (the
game's collision data is ground level only); the zombie density map (read by sub-project 3 when seeding); water width
(dropped by D16); multiplayer clients (the grid lives wherever the core runs).

## 1. Source data (verified in 42.21)

**Collision files.** One `chunkdata_X_Y.bin` per 256×256-tile cell in every map folder (4,065 for the vanilla map,
22 MB). Format (`zombie.pot.POTChunkData`): a big-endian short version (1), then for each of the cell's 32×32 chunks
(row-major, y outer) one type byte: 0 empty, 1 solid, 2 regular, 3 water, 4 room, 5 void; a regular chunk is
followed by 64 tile bytes (8×8, row-major). Type 5 is not in the game's Java reader but occurs in the vanilla map's
edge strips (165,716 chunks; every one of the 4,065 files parses exactly when 5 carries no tile bytes); it means "no
map here" and is treated as no data (blocked). Tile bits: 1 solid, 2 wall on the north edge, 4 wall on the west edge, 8 water, 16 room.
Uniform chunk types mean every tile has that one property (empty = all clear).

**The game's rules for the bits** (`MapCollisionData.isSolid / isBlockedN / isBlockedW / isWater / isRoom`): solid
covers solid objects and stairs but not water or trees; low (hoppable) fences are not walls; door frames, windows and
window frames are walls whether open or closed; room = inside a building.

**Save copies.** The game rewrites the files for every cell it has loaded into `Saves\<mode>\<save>\chunkdata\` (same
format), so player changes are in them after a save.

**Map folders.** `IsoLot.MapFiles` lists the world's map folders in priority order (index 0 first); each has
`infoFileNames["chunkdata_X_Y.bin"]` → absolute path when that folder has the file. Grid bounds in cells:
`IsoWorld.instance.MetaGrid.minX/minY/maxX/maxY`.

**Zones.** `IsoWorld.instance.MetaGrid` holds every zone after load (from each map's `objects.lua`; modded maps the
same way). Types used here: `Nav` (roads), `TownZone`, `Farm`, `FarmLand`, `Ranch`, `Forest`, `DeepForest`,
`Vegitation`, `TrailerPark`. Zones can be rectangles, polygons or polylines (public `type`, `x/y/w/h`, `points`,
`polylineWidth`, `z`).

**Live changes.** The game feeds its own collision grid from two main-thread methods, which this sub-project hooks:
`MapCollisionData.addChunkToWorld(IsoChunk)` (an area loads around a player) and
`MapCollisionData.squareChanged(IsoGridSquare)` (a tile changes: building, breaking, burning). Both do nothing on a
multiplayer client.

## 2. Data model

**Coarse grid.** One entry per 8×8-tile chunk across the grid bounds (vanilla: about 2,464 × 1,984 ≈ 4.9 million
chunks), held in two flat arrays (about 30 MB):

| Array | Per chunk | Meaning |
|---|---|---|
| info | 2 bytes | walkable tiles out of 64 (7 bits); water, room and no-data majority flags; the covering zone's terrain (4 bits) |
| border masks | 4 bytes | for each of the 8 border tiles on each side, whether a zombie can stand there and leave or enter across that side |

Terrain (`UNKNOWN`, `ROAD`, `TOWN`, `FARM`, `OPEN`, `FOREST`, `DEEP_FOREST`, `BUILDING`, `WATER`) and the four edges
are worked out from these on each query, so a live change only rewrites one chunk's two entries.

- **Terrain** comes from zones first, then from the tiles:
  - more than half its 64 tiles are water → `WATER`;
  - otherwise more than half are room → `BUILDING`;
  - otherwise the zone that covers the chunk, with priority `ROAD` > `TOWN` > `FARM` (`Farm`, `FarmLand`, `Ranch`) >
    `DEEP_FOREST` > `FOREST` > `OPEN` (`Vegitation`, `TrailerPark`, or no zone).
  - A road zone wins as soon as it covers any tile of the chunk, so narrow roads are never lost; other zones need to
    cover the chunk's centre.
- **Walkable tile:** not solid, not water, not room. Room tiles are walkable for real zombies, but buildings are sealed
  boxes in this data (doors and windows are walls), so off-screen zombies treat interiors as blocked.
- **Edge into a neighbour:** true when at least one pair of facing tiles on the shared border is walkable on both
  sides with no wall between them (north edge: the tile's own north-wall bit; west edge: its own west-wall bit; east
  and south use the neighbour's bits). Connectivity inside a chunk is not modelled; tile detail covers that near
  players.
- **Cost:** a function of terrain and openness (`Terrain.cost`), with placeholder values (road 1, town 2, farm/open 3,
  forest 4, deep forest 6, scaled up as openness drops). Tuning comes in sub-project 4. A chunk with no edges is
  blocked.
- **Outside the grid bounds:** blocked, cost infinite, terrain `UNKNOWN`.

**Tile detail.** The 64 Ki tile bytes of one cell, loaded on request from the same file the coarse grid used (save
copy first), with live changes applied on top. A least-recently-used cache keeps 64 cells (about 4 MB).

**Live overlay.** Tile changes received from the hooks are kept per cell in a small map (cell → changed tiles) until
the world closes, so tile detail loaded later still includes them, and the affected chunk's coarse entry is
recomputed.

## 3. Building and updating

**At world start** (after the core starts; single player and dedicated server only):
1. On the main thread, take a snapshot: grid bounds, the list of map folders with their collision file paths, the
   save's `chunkdata` folder, and the ground-level zones of the types above (copied to plain immutable records:
   terrain, shape kind, bounds, points, polyline width; see "Zones off the main thread" below).
2. A background daemon thread (`TrueHordes-World`) builds the coarse grid cell by cell. For each cell: the save copy if
   it exists, otherwise the first map folder (lowest index) that has the file; no file → every chunk `UNKNOWN` and
   blocked. Then terrain from the zone snapshot.
3. When done, the new grid is published in one step (a volatile reference), and the build time, memory and counts
   are logged. Before that, `ready()` is false and every query answers "unknown / blocked".
4. Exiting the world (or a new world starting) cancels a build still running and drops all data.

The finished grid is published on the main thread (the core's tick), so the grid is only ever written by one thread
at a time. The build is measured on the full vanilla map. If it takes more than about 5 seconds, a cache file in the save
folder is added in a follow-up; not before.

**While playing** (main thread): after `addChunkToWorld` the game has just computed the chunk's 64 tile bits in its
own private `squares` array, which the hook copies; after `squareChanged` the hook computes that tile's bits with the
game's own rule methods (`isSolid`, `isBlockedN`, `isBlockedW`, `isWater`, `isRoom`). Both are reached by reflection
on `MapCollisionData.instance`, resolved once at start-up, so the mod never disagrees with the game. The bits go into
the live overlay and the chunk's coarse entry is recomputed. Changes that arrive while the build is still running wait
in the overlay and are applied when the grid is published (on the main thread, in the core's tick).

**Threads.** The grid is written only by the build thread (before publishing) and then only by the main thread.
Queries come from the main thread now (the simulation's own thread in later sub-projects): entries are single array
elements, so a reader sees the old or the new value, never a torn one. The tile cache is synchronized; the live overlay
is a concurrent map whose chunk arrays are replaced, never changed in place.

**Zones off the main thread.** The game's own zone geometry shares a static helper that is not thread-safe, so the
snapshot copies each zone's type and points, and the mod's own geometry (rectangle, polygon by crossing count,
polyline by distance to its segments) does the work on the build thread. Big polygons are filled by scanlines, one per
chunk row.

## 4. Queries (Java, `truehordes.world.World`)

| Query | Answer |
|---|---|
| `ready()` | the coarse grid is built and published |
| `terrainAt(x, y)` | terrain of the chunk containing tile (x, y) |
| `costAt(x, y)` | that chunk's walking cost; `Float.POSITIVE_INFINITY` when blocked or unknown |
| `blockedAt(x, y)` | the chunk has no edges, or is outside the map, or the grid is not ready |
| `canCross(chunkX, chunkY, dir)` | the chunk's edge bit for that direction (N, E, S, W) |
| `walkable(x, y)` | tile level: not solid, not water, not room |
| `canStep(x1, y1, x2, y2)` | tile level, neighbouring tiles (including diagonals, which need both orthogonal steps clear): both walkable and no wall between |

Queries never throw; anything outside the map or not yet loaded answers blocked. Tile queries load the cell's tile
detail on first use (a file read of about 25 KB).

## 5. Debug and console

- **Debug map:** `Hordes_map()` gets a "Terrain" toggle. Colours: road grey, town tan, farm yellow, open pale green,
  forest green, deep forest dark green, water blue, building and blocked black, unknown none. When zoomed in to tile
  scale, walls (north and west bits) are drawn as lines. The map asks Java for a rectangle of terrain at the current
  zoom (`LuaApi.terrainSnapshot(minX, minY, maxX, maxY, step)`), like the population snapshot.
- **Console:** `Hordes_world()` prints the state (building / ready / off), build time, memory, chunk counts per
  terrain, cells read from the save versus the map, files missing or damaged, and tile changes received.

## 6. Error handling

- A damaged or truncated collision file: logged once with its path; its unread chunks are `UNKNOWN` and blocked; the
  build continues.
- A missing hook target or rule method (a game update renamed something): the hook-target self-check reports it,
  live updates switch off, and the grid still builds from the files.
- The build thread catches everything, logs it, and leaves the world "not ready" rather than half-built.
- Hooks are wrapped like sub-project 1's: nothing throws into the game.

## 7. Testing

**Offline (every build).**
- Reader: fake collision files written in the game's format: empty, solid, water, room and regular chunks; a
  truncated file and a wrong version (logged, blocked, no crash).
- Coarse grid: terrain from zones including overlaps and a road covering one tile of a chunk; openness; edges with
  walls on borders, including east and south using the neighbour's bits; save copy wins over map copy; first map
  folder wins; outside bounds is blocked.
- Live updates: a tile change rewrites its chunk's entry and shows in tile detail loaded afterwards; changes during a
  build are applied after it publishes.
- Tile queries: `walkable`, `canStep` across walls and diagonals.
- Real map (reads the vanilla files from the game folder; skipped with a notice if the folder is missing): all 4,065
  files load without errors; build time and memory are printed; spot checks of a known river tile (water), a known
  road (road) and a known bridge (walkable), picked during planning from the map data.

**In game (`docs/TESTING.md`).**
- `Hordes_world()` after loading: `ready`, the build time, counts.
- The terrain layer on `Hordes_map()` matches the world: rivers blue, bridges not blue, roads grey, forest, towns.
- Build a wall, chop a tree, break a window: the wall shows at tile zoom; the chunk updates.
- Save, reload: the change is still there (read from the save copy).
- Optional: dedicated server console shows the build line.

## 8. Done when

All offline tests pass (including the real-map test); the in-game checks above pass in single player; the build time
on the vanilla map is recorded in `design.md`.

## 9. Addendum (2026-10-12): roads and trails from `streets.xml` and the world map

In-game check: the terrain map was right, but some rural roads ended short because the `Nav` zones do not cover
them. Measured on the vanilla map against the Nav zones: `streets.xml` adds about 28,000 road chunks; the world map's
primary, secondary and tertiary roads about 36,500; its trails about 13,000 (almost none in Nav). Approved by the user
(trails as their own terrain: option A).

- **Sources** (each map folder, in the game's `IsoLot.MapFiles` order; every folder's roads are used, not just the
  first): `streets.xml` (`<street width>` with `<point x y>` centre lines, tile coordinates) and `worldmap.xml`
  (`<cell x y>` in the game's old 300-tile cells; `<feature>` with `<geometry type="Polygon|LineString">` points local
  to the cell, then `<properties>`; `highway` = `primary` / `secondary` / `tertiary` / `trail`, optional `width`).
- **Shapes:** a street is a polyline of its width; a world-map Polygon is a polygon; a LineString is a polyline of its
  `width` property, or 4 tiles without one. World-map coordinates become tiles as `cell * 300 + local`.
- **Terrain:** new kind `TRAIL` (code 9, placeholder base cost 1.5, drawn brown). Zone priority becomes ROAD > TOWN >
  TRAIL > FARM > DEEP_FOREST > FOREST > OPEN. Trails, like roads, mark a chunk if they cover any of its tiles. Water
  and buildings still win, and blocking still comes only from the tiles.
- **When:** read on the build thread with a streaming XML reader (the files are up to 6 MB), so loading is not blocked.
  A missing or unreadable file is reported and skipped; a folder with only `worldmap.xml.bin` is counted as "binary
  world map only, skipped".
- **Report:** `Hordes_world()` adds `roads from files: N streets, M world-map roads, T trails` and any files skipped.
- **Tests:** fake `streets.xml` / `worldmap.xml` (coordinate conversion, polygon vs line, default width, trail, unknown
  highway kinds ignored, missing and broken files); the real-map test checks a streets-only road chunk (548, 9244), a
  world-map-only road chunk (92, 9436) and a trail-only chunk (332, 9596), plus the build time with the files.
