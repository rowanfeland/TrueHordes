package truehordes.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import truehordes.population.PlayerNearby;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/**
 * The off-screen simulation (sub-project 4): every held wanderer and horde walks, hears, sees and meets others, each
 * updated on its own schedule by distance to the players (levels of detail). {@link #step} works through the walkers
 * that are due until a time budget is spent, so a population of any size costs a bounded slice per call. The Store is
 * the wanderers' home (the simulation follows it through Store.Listener); hordes live in {@link #hordes()}.
 * Walkers never step into a loaded (real) chunk: the hand-over handles the loaded area (spec 6; sub-project 6 adds
 * walking across it). Not thread-safe: used under the core's lock.
 */
public final class Simulation {
    /** How strongly a follower steers towards the zombie it trails. */
    static final float FOLLOW_PULL = 2.5f;
    /** Walkers this close to a sound mill there instead of walking on (tiles). */
    static final float MILL_RADIUS = 3f;
    /** Moaning reach is re-indexed this often (in-game minutes); hordes move slowly. */
    static final int HORDE_INDEX_MINUTES = 5;
    private static final int CELL = 256;
    private static final PlayerNearby NOBODY = new PlayerNearby() {
        @Override public boolean nearCell(int cellX, int cellY) { return false; }
        @Override public boolean nearChunk(int chunkX, int chunkY) { return false; }
    };

    private final Store store;
    private final SimSettings s;
    private final Random rng;
    private final java.util.function.DoubleSupplier random;
    private final Hordes hordes = new Hordes();
    private final SpatialGrid grid = new SpatialGrid();
    private final Movement movement;
    private final Encounters encounters;
    private final Conservation conservation = new Conservation();
    private final TreeMap<Long, LongStack> buckets = new TreeMap<>();
    private final List<SoundEvent> events = new ArrayList<>();
    private final Map<Long, List<Horde>> moanIndex = new HashMap<>();
    private PlayerNearby players = NOBODY;
    private long currentMinute;
    private double currentHours;
    private long moanIndexMinute = Long.MIN_VALUE / 2; // far past, without overflow in the subtraction
    private long nextEventId = 1;
    private int internal;
    private long queued, updatesTotal, lastStepNanos, maxStepNanos;

    public Simulation(Store store, MapView map, SimSettings s, long seed, double nowHours) {
        this.store = store;
        this.s = s;
        this.rng = new Random(seed);
        this.random = rng::nextDouble;
        this.movement = new Movement(map, s);
        this.encounters = new Encounters(store, hordes, grid, s, rng);
        this.currentHours = nowHours;
        this.currentMinute = minuteOf(nowHours);
        hordes.setOnCreated(this::hordeCreated);
        store.setListener(new Store.Listener() {
            @Override public void added(ZombieRecord r) {
                grid.putWanderer(r);
                if (internal == 0) conservation.added(1);
                firstSchedule(r);
            }

            @Override public void removed(ZombieRecord r) {
                grid.removeWanderer(r);
                if (internal == 0) conservation.removed(1);
            }

            @Override public void moved(ZombieRecord r, float oldX, float oldY) { grid.moveWanderer(r, oldX, oldY); }
        });
        for (ZombieRecord r : store.all()) {
            grid.putWanderer(r);
            firstSchedule(r);
        }
        conservation.reset(held());
    }

    public Hordes hordes() { return hordes; }

    public SpatialGrid grid() { return grid; }

    public Encounters encounters() { return encounters; }

    public Conservation conservation() { return conservation; }

    public Movement movement() { return movement; }

    /** Zombies held off screen: wanderers plus horde members. */
    public long held() { return store.size() + (long) hordes.memberCount(); }

    public void setPlayers(PlayerNearby p) { players = p == null ? NOBODY : p; }

    public PlayerNearby players() { return players; }

    /** Runs a change that moves zombies between wanderers and hordes (not a population change). */
    public void internal(Runnable r) {
        internal++;
        try {
            r.run();
        } finally {
            internal--;
        }
    }

    // ---- sounds ----

    /** A sound at (x, y) heard for {@code durationHours}; wakes idle zombies in range and gets walkers moving. */
    public SoundEvent addSound(float x, float y, float loudnessDb, double durationHours) {
        SoundEvent e = new SoundEvent(nextEventId++, x, y, loudnessDb, currentHours, currentHours + durationHours,
            false, 0);
        events.add(e);
        float range = Hearing.range(loudnessDb);
        grid.near(x, y, range, r -> {
            r.idle = false;
            r.lingerUntilHours = 0;
            schedule(r, currentMinute);
        }, h -> scheduleHorde(h, currentMinute));
        return e;
    }

    public List<SoundEvent> sounds() { return Collections.unmodifiableList(events); }

    private void expireSounds(double now) {
        for (int i = events.size() - 1; i >= 0; i--) {
            SoundEvent e = events.get(i);
            if (e.endHours() <= now) {
                events.remove(i);
                internal(() -> encounters.soundEnded(e));
            }
        }
    }

    // ---- scheduling ----

    static long minuteOf(double hours) { return (long) Math.floor(hours * 60.0); }

    private void firstSchedule(ZombieRecord r) {
        if (r.idle) return;
        // Spread first updates over one interval so a big population does not all fall due at once.
        int iv = LevelOfDetail.intervalMinutes(players, r.chunkX(), r.chunkY());
        schedule(r, currentMinute + 1 + rng.nextInt(iv));
    }

    private void schedule(ZombieRecord r, long minute) {
        if (r.dueMinute == minute) return;
        r.dueMinute = minute;
        push(minute, r.id);
    }

    private void scheduleHorde(Horde h, long minute) {
        if (h.dueMinute == minute) return;
        h.dueMinute = minute;
        push(minute, -h.id);
    }

    /** A horde the encounters just formed (they put it in the grid): schedule it. */
    private void hordeCreated(Horde h) { scheduleHorde(h, currentMinute + 1); }

    /** Re-adds a saved horde (load): grid and schedule. Not a population change. */
    public Horde restoreHorde(long id, float x, float y, float heading, float pauseMinutes, List<ZombieRecord> members) {
        Horde h = hordes.restore(id, x, y, heading, members);
        h.pauseMinutes = pauseMinutes;
        grid.putHorde(h);
        conservation.added(h.size());
        scheduleHorde(h, currentMinute + 1 + rng.nextInt(LevelOfDetail.FAR_MINUTES));
        return h;
    }

    private void push(long minute, long key) {
        buckets.computeIfAbsent(minute, k -> new LongStack()).push(key);
        queued++;
    }

    private int interval(float x, float y) {
        return LevelOfDetail.intervalMinutes(players, chunk(x), chunk(y));
    }

    // ---- the step ----

    /** Updates due walkers until {@code budgetNanos} is spent. Returns the number of walkers updated. */
    public int step(double nowHours, long budgetNanos) {
        long t0 = System.nanoTime();
        currentHours = nowHours;
        currentMinute = minuteOf(nowHours);
        expireSounds(nowHours);
        if (currentMinute - moanIndexMinute >= HORDE_INDEX_MINUTES) rebuildMoanIndex();
        int updated = 0, pops = 0;
        while (true) {
            Map.Entry<Long, LongStack> e = buckets.firstEntry();
            if (e == null || e.getKey() > currentMinute) break;
            LongStack b = e.getValue();
            if (b.isEmpty()) {
                buckets.pollFirstEntry();
                continue;
            }
            long key = b.pop();
            queued--;
            long minute = e.getKey();
            if (key > 0) {
                ZombieRecord r = store.get(key);
                if (r != null && r.dueMinute == minute) {
                    updateWanderer(r, nowHours);
                    updated++;
                }
            } else {
                Horde h = hordes.get(-key);
                if (h != null && h.dueMinute == minute) {
                    updateHorde(h, nowHours);
                    updated++;
                }
            }
            if ((++pops & 31) == 0 && System.nanoTime() - t0 > budgetNanos) break;
        }
        long took = System.nanoTime() - t0;
        lastStepNanos = took;
        maxStepNanos = Math.max(maxStepNanos, took);
        updatesTotal += updated;
        return updated;
    }

    private double elapsedMinutes(double simHours, double now, int interval) {
        if (Double.isNaN(simHours)) return interval;
        return Math.max(0, Math.min(24 * 60, (now - simHours) * 60));
    }

    private void updateWanderer(ZombieRecord r, double now) {
        double dt = elapsedMinutes(r.simHours, now, LevelOfDetail.FAR_MINUTES);
        r.simHours = now;
        r.dueMinute = Long.MIN_VALUE;
        if (players.nearChunk(r.chunkX(), r.chunkY())) {
            schedule(r, currentMinute + LevelOfDetail.EDGE_MINUTES);
            return;
        }
        if (r.idle) return; // woken only by a sound
        if (r.lingerUntilHours > now) {
            long until = minuteOf(r.lingerUntilHours) + 1;
            schedule(r, Math.min(until, currentMinute + interval(r.x(), r.y())));
            return;
        }
        boolean walk = true;
        if (r.pauseMinutes > 0) {
            r.pauseMinutes = (float) Math.max(0, r.pauseMinutes - dt);
            walk = r.pauseMinutes <= 0;
        }
        if (walk) {
            float px = 0, py = 0, pw = 0;
            Hearing.Pull p = pull(r.x(), r.y(), 0, 0f);
            if (p != null && !r.straggler) {
                px = p.x();
                py = p.y();
                pw = p.weight();
                if (p.event() != null) {
                    encounters.responded(p.event(), r);
                    if (Hearing.dist(r.x(), r.y(), px, py) <= MILL_RADIUS) {
                        r.pauseMinutes = 2 + 3 * rng.nextFloat();
                        walk = false;
                    }
                }
            } else if (r.followId > 0) {
                ZombieRecord t = store.get(r.followId);
                if (t != null) {
                    px = t.x();
                    py = t.y();
                    pw = FOLLOW_PULL;
                }
            }
            if (walk) {
                boolean crawler = (r.stateFlags & 2) != 0;
                Movement.Step st = movement.advance(r.x(), r.y(), r.heading, (float) dt, 1, r.id, crawler, px, py, pw,
                    random);
                if (players.nearChunk(chunk(st.x()), chunk(st.y()))) {
                    r.heading = turnedBack(st.heading());
                } else {
                    store.moved(r, st.x(), st.y());
                    r.heading = st.heading();
                }
                r.pauseMinutes = st.boxedIn() ? 10f : Movement.rollPause((float) dt, random);
            }
        }
        internal(() -> encounters.wanderer(r, now, dt / 60.0));
        if (store.get(r.id) == r && r.dueMinute == Long.MIN_VALUE && !r.idle) {
            schedule(r, currentMinute + interval(r.x(), r.y()));
        }
    }

    private void updateHorde(Horde h, double now) {
        double dt = elapsedMinutes(h.simHours, now, LevelOfDetail.FAR_MINUTES);
        h.simHours = now;
        h.dueMinute = Long.MIN_VALUE;
        if (players.nearChunk(chunk(h.x), chunk(h.y))) {
            scheduleHorde(h, currentMinute + LevelOfDetail.EDGE_MINUTES);
            return;
        }
        boolean walk = true;
        if (h.pauseMinutes > 0) {
            h.pauseMinutes = (float) Math.max(0, h.pauseMinutes - dt);
            walk = h.pauseMinutes <= 0;
        }
        int tight = 0;
        if (walk) {
            Hearing.Pull p = pull(h.x, h.y, h.id, Hearing.maskingDb(h));
            h.followHordeId = p != null && p.horde() != null ? p.horde().id : 0;
            float px = 0, py = 0, pw = 0;
            if (p != null) {
                px = p.x();
                py = p.y();
                pw = p.weight();
                if (p.event() != null && Hearing.dist(h.x, h.y, px, py) <= MILL_RADIUS + h.spread()) {
                    h.pauseMinutes = 2 + 3 * rng.nextFloat();
                    walk = false;
                }
            }
            if (walk) {
                float ox = h.x, oy = h.y, oldSpread = h.spread();
                Movement.Step st = movement.advance(h.x, h.y, h.heading, (float) dt, h.size(), h.id, h.allCrawlers(),
                    px, py, pw, random);
                if (players.nearChunk(chunk(st.x()), chunk(st.y()))) {
                    h.heading = turnedBack(st.heading());
                } else {
                    h.x = st.x();
                    h.y = st.y();
                    h.heading = st.heading();
                    tight = st.tightChunks();
                }
                grid.moveHorde(h, ox, oy, oldSpread);
                h.pauseMinutes = st.boxedIn() ? 10f : Movement.rollPause((float) dt, random);
            }
        }
        final int tightSpots = tight;
        Horde[] survivor = new Horde[1];
        internal(() -> survivor[0] = encounters.horde(h, now, tightSpots));
        Horde sv = survivor[0];
        if (sv != null && hordes.get(sv.id) == sv && (sv.dueMinute == Long.MIN_VALUE || sv.dueMinute < currentMinute)) {
            scheduleHorde(sv, currentMinute + interval(sv.x, sv.y));
        }
    }

    private static float turnedBack(float heading) { return Movement.normalise(heading + (float) Math.PI); }

    // ---- hearing ----

    private Hearing.Pull pull(float x, float y, long ownHordeId, float ownDb) {
        List<Horde> near = moanIndex.getOrDefault(cellKey(x, y), Collections.emptyList());
        if (events.isEmpty() && near.isEmpty()) return null;
        return Hearing.choose(x, y, ownHordeId, ownDb, events, near);
    }

    /** Which hordes can be heard from each 256-tile cell (a horde's moaning reaches a few cells at most). */
    private void rebuildMoanIndex() {
        moanIndex.clear();
        for (Horde h : hordes.all()) {
            float r = Hearing.moanRange(h);
            int x0 = Math.floorDiv((int) (h.x - r), CELL), x1 = Math.floorDiv((int) (h.x + r), CELL);
            int y0 = Math.floorDiv((int) (h.y - r), CELL), y1 = Math.floorDiv((int) (h.y + r), CELL);
            for (int cx = x0; cx <= x1; cx++) {
                for (int cy = y0; cy <= y1; cy++) {
                    moanIndex.computeIfAbsent(SpatialGrid.key(cx, cy), k -> new ArrayList<>()).add(h);
                }
            }
        }
        moanIndexMinute = currentMinute;
    }

    private static long cellKey(float x, float y) {
        return SpatialGrid.key(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(y), CELL));
    }

    static int chunk(float tile) { return Math.floorDiv((int) Math.floor(tile), 8); }

    // ---- status ----

    public long queued() { return queued; }

    public long updatesTotal() { return updatesTotal; }

    /** Checks the balance; "" when it holds. */
    public String checkConservation(String where) { return conservation.check(held(), where); }

    public String stats() {
        int biggest = 0;
        for (Horde h : hordes.all()) biggest = Math.max(biggest, h.size());
        return String.format(Locale.ROOT,
            "sim: wanderers %d, hordes %d (biggest %d, %d members), updates %d, queue %d, last step %.2f ms (max %.2f), "
                + "formed %d, joined %d, merged %d, left behind %d, broke up %d, sounds %d | conservation %s",
            store.size(), hordes.size(), biggest, hordes.memberCount(), updatesTotal, queued, lastStepNanos / 1e6,
            maxStepNanos / 1e6, encounters.formed(), encounters.joined(), encounters.merged(), encounters.brokeAway(),
            encounters.dissolved(), events.size(), conservation.summary());
    }

    /** Growable stack of walker keys for one in-game minute. */
    static final class LongStack {
        private long[] a = new long[8];
        private int n;

        void push(long v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        long pop() { return a[--n]; }

        boolean isEmpty() { return n == 0; }
    }
}
