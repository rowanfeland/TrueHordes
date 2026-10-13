package truehordes.population;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import truehordes.store.Store;

/**
 * Growth and respawn (spec section 5), run every in-game hour in a mod-seeded world.
 * Growth: a cell no player has ever loaded is topped up to vanilla's start-to-peak curve (vanilla fills an untouched
 * cell at the curve value of the day it is first touched). Respawn: a visited cell with no player near, at least 10
 * below the curve, whose RespawnHours have passed, gets one group in an eligible chunk (unseen for RespawnUnseenHours,
 * below its own value, not refilled for RespawnHours) or, with the edge share from EdgeArrivalMonths, at the map's edge.
 */
public final class Respawner {
    private static final double HOURS_PER_MONTH = 24.0 * 30.0;

    private final Map<Long, Integer> respawnedLast = new HashMap<>();
    private int edgeLast;

    /** Zombies respawned into (or on behalf of) this cell during the last update. */
    public int lastRespawned(int cellX, int cellY) { return respawnedLast.getOrDefault(CellBook.key(cellX, cellY), 0); }

    /** Zombies placed at the map's edge during the last update. */
    public int lastEdgeArrivals() { return edgeLast; }

    /** Returns the zombies added. */
    public int update(double hours, CellBook book, PopulationCounter counter, MapSource map, PopulationSettings s,
                      Random rnd, EdgeChunks edge, TilePicker tiles, Store store, PlayerNearby players) {
        respawnedLast.clear();
        edgeLast = 0;
        if (!book.initialised()) return 0;
        double day = hours / 24.0;
        double edgeShare = s.edgeArrivalMonths <= 0f ? 1.0
            : Math.min(1.0, Math.max(0.0, (hours - book.startHours()) / HOURS_PER_MONTH / s.edgeArrivalMonths));
        int added = 0;
        for (int cy = 0; cy < book.heightInCells(); cy++) {
            for (int cx = 0; cx < book.widthInCells(); cx++) {
                int cellX = book.cellX0() + cx, cellY = book.cellY0() + cy;
                int t = book.baseTarget(cellX, cellY);
                if (t <= 0 || players.nearCell(cellX, cellY)) continue;
                int target = Formula.curve(t, day, s);
                int have = counter.count(cellX, cellY);
                if (!book.everLoaded(cellX, cellY)) {
                    if (target > have) added += grow(cellX, cellY, target - have, map, s, rnd, tiles, store);
                } else {
                    added += respawn(cellX, cellY, hours, target, target - have, book, map, s, rnd, edge, edgeShare,
                        tiles, store, players);
                }
            }
        }
        return added;
    }

    private int grow(int cellX, int cellY, int n, MapSource map, PopulationSettings s, Random rnd, TilePicker tiles,
                     Store store) {
        float[] cumulative = new float[1024];
        float sum = 0f;
        for (int i = 0; i < 1024; i++) {
            sum += Math.max(0f, Formula.chunkValue(map.intensity(cellX * 32 + i % 32, cellY * 32 + i / 32), s));
            cumulative[i] = sum;
        }
        if (sum <= 0f) return 0;
        int added = 0;
        for (int k = 0; k < n; k++) {
            float pick = rnd.nextFloat() * sum;
            int lo = 0, hi = 1023;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulative[mid] > pick) hi = mid; else lo = mid + 1;
            }
            if (Seeder.placeInChunk(cellX * 32 + lo % 32, cellY * 32 + lo / 32, tiles, rnd, store)) added++;
        }
        return added;
    }

    private int respawn(int cellX, int cellY, double hours, int target, int deficit, CellBook book, MapSource map,
                        PopulationSettings s, Random rnd, EdgeChunks edge, double edgeShare, TilePicker tiles,
                        Store store, PlayerNearby players) {
        if (s.respawnHours <= 0f) return 0;
        if (deficit < 10) {
            book.setLastRespawn(cellX, cellY, hours);
            return 0;
        }
        if (book.lastRespawn(cellX, cellY) + s.respawnHours > hours) return 0;
        int group = Formula.respawnGroup(target, deficit, s);
        long chunk;
        boolean atEdge = edge.count() > 0 && rnd.nextDouble() < edgeShare;
        if (atEdge) {
            chunk = edge.chunk(rnd.nextInt(edge.count()));
        } else {
            List<Long> eligible = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                int x = cellX * 32 + i % 32, y = cellY * 32 + i / 32;
                if (players.nearChunk(x, y)) continue;
                if (hours - book.lastSeen(x, y) < s.respawnUnseenHours) continue;
                if (hours - book.lastRefill(x, y) < s.respawnHours) continue;
                if (store.inChunk(x, y).size() >= Formula.chunkValue(map.intensity(x, y), s)) continue;
                eligible.add(CellBook.key(x, y));
            }
            if (eligible.isEmpty()) {
                book.setLastRespawn(cellX, cellY, hours);
                return 0;
            }
            chunk = eligible.get(rnd.nextInt(eligible.size()));
        }
        int x = (int) (chunk >> 32), y = (int) chunk;
        int added = 0;
        for (int k = 0; k < group; k++) if (Seeder.placeInChunk(x, y, tiles, rnd, store)) added++;
        book.refilled(x, y, hours);
        book.setLastRespawn(cellX, cellY, hours);
        respawnedLast.merge(CellBook.key(cellX, cellY), added, Integer::sum);
        if (atEdge) edgeLast += added;
        return added;
    }
}
