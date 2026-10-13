package truehordes.population;

import java.util.Random;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/**
 * Seeds a new game's population across the whole map with vanilla's formulas (spec section 5): outdoors per 256x256
 * cell (chunk targets, the start-to-peak curve, proportional spread), and optionally every building's rooms.
 */
public final class Seeder {
    /** Tries to find a walkable tile in a chunk before a zombie is skipped. */
    static final int TILE_TRIES = 20;

    private int skipped;

    /** Zombies that found no walkable tile in their chunk during the last outdoor seeding. */
    public int lastSkipped() { return skipped; }

    /** Returns the number of records added. */
    public int seedOutdoor(MapSource map, TilePicker tiles, PopulationSettings s, double day, Random rnd, Store store) {
        skipped = 0;
        int added = 0;
        int cellX0 = Math.floorDiv(map.minChunkX(), 32), cellX1 = Math.floorDiv(map.maxChunkX(), 32);
        int cellY0 = Math.floorDiv(map.minChunkY(), 32), cellY1 = Math.floorDiv(map.maxChunkY(), 32);
        int[] targets = new int[1024];
        for (int cellY = cellY0; cellY <= cellY1; cellY++) {
            for (int cellX = cellX0; cellX <= cellX1; cellX++) {
                int total = 0;
                for (int i = 0; i < 1024; i++) {
                    targets[i] = Formula.chunkTarget(map.intensity(cellX * 32 + i % 32, cellY * 32 + i / 32), s,
                        () -> rnd.nextInt(100));
                    total += targets[i];
                }
                if (total == 0) continue;
                int[] counts = Formula.distribute(targets, Formula.curve(total, day, s), rnd::nextInt);
                for (int i = 0; i < 1024; i++) {
                    int cx = cellX * 32 + i % 32, cy = cellY * 32 + i / 32;
                    for (int n = 0; n < counts[i]; n++) {
                        if (placeInChunk(cx, cy, tiles, rnd, store)) added++;
                        else skipped++;
                    }
                }
            }
        }
        return added;
    }

    /** A standing, undressed zombie at a random walkable tile of the chunk; false if none was found. */
    static boolean placeInChunk(int cx, int cy, TilePicker tiles, Random rnd, Store store) {
        for (int t = 0; t < TILE_TRIES; t++) {
            int x = cx * 8 + rnd.nextInt(8), y = cy * 8 + rnd.nextInt(8);
            if (tiles.outdoorWalkable(x, y)) {
                store.add(x + 0.5f, y + 0.5f, 0f, (byte) rnd.nextInt(8), 0, 0, 0, ZombieRecord.NO_TARGET,
                    ZombieRecord.NO_TARGET, null, false);
                return true;
            }
        }
        return false;
    }

    /** Every room's zombies (vanilla's room formula), on the room's floor tiles and level. Returns records added. */
    public int seedRooms(MapSource map, PopulationSettings s, Random rnd, Store store) {
        int added = 0;
        for (MapSource.RoomInfo room : map.rooms()) {
            int n = RoomCount.count(s.zombiesSetting, room.chunkIntensity(), room.area(), room.buildingRoomCount(),
                room.emptyOutside(), rnd::nextInt);
            int[] r = room.rects();
            int area = 0;
            for (int i = 0; i + 3 < r.length; i += 4) area += r[i + 2] * r[i + 3];
            if (area <= 0) continue;
            for (int k = 0; k < n; k++) {
                int pick = rnd.nextInt(area);
                for (int i = 0; i + 3 < r.length; i += 4) {
                    int a = r[i + 2] * r[i + 3];
                    if (pick < a) {
                        int x = r[i] + pick % r[i + 2], y = r[i + 1] + pick / r[i + 2];
                        store.add(x + 0.5f, y + 0.5f, room.level(), (byte) rnd.nextInt(8), 0, 0, 0,
                            ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, true);
                        added++;
                        break;
                    }
                    pick -= a;
                }
            }
        }
        return added;
    }
}
