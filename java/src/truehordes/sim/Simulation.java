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
    /** Longest stretch of in-game time one update simulates (see elapsedMinutes). */
    static final double MAX_STEP_MINUTES = 60;
    /** Zombies keep heading to a sound's spot for this long after it ends, then give up (in-game hours). */
    static final double INVESTIGATE_HOURS = 6;
    /** A follower walks this far behind the zombie it follows (tiles). */
    static final float TRAIL_GAP = 1.5f;
    /** A follower further than this from its leader has lost it (tiles). */
    static final float TRAIL_LOST = 48f;
    /** Walkers this close to a sound mill there instead of walking on (tiles). */
    static final float MILL_RADIUS = 3f;
    /** Moaning reach is re-indexed this often (in-game minutes); hordes move slowly. */
    static final int HORDE_INDEX_MINUTES = 5;
    /** Shortest time a straggler stands around between short shuffles, in in-game hours (up to 3 more at random). */
    static final double STRAGGLER_LINGER_MIN_HOURS = 1.0;
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
    private final HandOver handOver;
    /** A horde reached the loaded area: the main thread should run arrivals so it is laid out and made real. */
    private boolean layoutDue;
    private final TreeMap<Long, LongStack> buckets = new TreeMap<>();
    private final List<SoundEvent> events = new ArrayList<>();
    private final Map<Long, List<Horde>> moanIndex = new HashMap<>();
    /** Idle building zombies not yet grouped into a building horde (grouped at the next step). */
    private final List<ZombieRecord> pendingIdle = new ArrayList<>();
    /** The dormant building horde of each chunk (a chunk is about one house). */
    private final Map<Long, Horde> dormantByChunk = new HashMap<>();
    private PlayerNearby players = NOBODY;
    private long currentMinute;
    private double currentHours;
    private long moanIndexMinute = Long.MIN_VALUE / 2; // far past, without overflow in the subtraction
    private long nextEventId = 1;
    private int internal;
    private long queued, updatesTotal, lastStepNanos, maxStepNanos, maxUpdateNanos;
    private String maxUpdateWhat = "-";

    public Simulation(Store store, MapView map, SimSettings s, long seed, double nowHours) {
        this.store = store;
        this.s = s;
        this.rng = new Random(seed);
        this.random = rng::nextDouble;
        this.movement = new Movement(map, s);
        this.encounters = new Encounters(store, hordes, grid, s, rng);
        this.handOver = new HandOver(this, store);
        this.currentHours = nowHours;
        this.currentMinute = minuteOf(nowHours);
        hordes.setOnCreated(this::hordeCreated);
        store.setListener(new Store.Listener() {
            @Override public void added(ZombieRecord r) {
                grid.putWanderer(r);
                if (internal == 0) conservation.added(1);
                if (r.idle && r.building) pendingIdle.add(r);
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
            if (r.idle && r.building) pendingIdle.add(r);
            firstSchedule(r);
        }
        conservation.reset(held());
    }

    public Hordes hordes() { return hordes; }

    public SpatialGrid grid() { return grid; }

    public Encounters encounters() { return encounters; }

    public Conservation conservation() { return conservation; }

    public Movement movement() { return movement; }

    public SimSettings settings() { return s; }

    public HandOver handOver() { return handOver; }

    /** True once after a horde touched the loaded area (the main thread then runs arrivals for loaded chunks). */
    public boolean takeLayoutDue() {
        boolean d = layoutDue;
        layoutDue = false;
        return d;
    }

    /** Horde members per 256-tile cell (by horde centre), keyed like CellBook: (cellX << 32) | cellY. */
    public Map<Long, Integer> hordeMembersPerCell() {
        Map<Long, Integer> out = new HashMap<>();
        for (Horde h : hordes.all()) out.merge(cellKey(h.x, h.y), h.size(), Integer::sum);
        return out;
    }

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
        List<Horde> asleep = new ArrayList<>();
        grid.nearHordes(x, y, range, h -> { if (h.dormant) asleep.add(h); });
        for (Horde h : asleep) wake(h, x, y, e.endHours() + INVESTIGATE_HOURS, 0);
        return e;
    }

    // ---- building hordes ----

    /** Groups idle building zombies added since the last step into their chunk's dormant building horde. */
    private void groupBuildings() {
        Map<Long, List<ZombieRecord>> byChunk = new HashMap<>();
        for (ZombieRecord r : pendingIdle) {
            if (store.get(r.id) != r || !r.idle) continue;
            if (!r.strRolled) {
                r.strRolled = true;
                r.straggler = rng.nextInt(s.buildingStragglerOneIn()) == 0; // stays inside when the group leaves
            }
            if (r.straggler) continue;
            byChunk.computeIfAbsent(SpatialGrid.key(r.chunkX(), r.chunkY()), k -> new ArrayList<>()).add(r);
        }
        pendingIdle.clear();
        for (Map.Entry<Long, List<ZombieRecord>> e : byChunk.entrySet()) {
            List<ZombieRecord> list = e.getValue();
            Horde h = dormantByChunk.get(e.getKey());
            if (h != null && (hordes.get(h.id) != h || !h.dormant)) h = null;
            if (h == null && list.size() < 2) continue; // a lone zombie stays an idle zombie
            Horde target = h;
            internal(() -> {
                for (ZombieRecord r : list) store.remove(r.id);
                if (target == null) {
                    double sx = 0, sy = 0;
                    for (ZombieRecord r : list) { sx += r.x(); sy += r.y(); }
                    Horde g = hordes.createDormant((float) (sx / list.size()), (float) (sy / list.size()), list);
                    grid.putHorde(g);
                    dormantByChunk.put(e.getKey(), g);
                } else {
                    float old = target.spread();
                    for (ZombieRecord r : list) hordes.addMember(target, r);
                    grid.moveHorde(target, target.x, target.y, old);
                }
            });
        }
    }

    /**
     * Sets a building horde loose towards (tx, ty): 10 or more come out as a horde, fewer as wanderers heading there
     * (they may gather into a horde at the spot). {@code followHorde} is the horde whose moaning woke it, or 0.
     */
    private void wake(Horde h, float tx, float ty, double investigateUntilHours, long followHorde) {
        h.dormant = false;
        if (h.size() >= s.hordeFormSize()) {
            h.heading = (float) Math.atan2(ty - h.y, tx - h.x);
            h.targetX = tx;
            h.targetY = ty;
            h.investigateUntilHours = investigateUntilHours;
            h.followHordeId = followHorde;
            h.pauseMinutes = 0;
            scheduleHorde(h, currentMinute);
            return;
        }
        List<ZombieRecord> members = new ArrayList<>(h.members);
        internal(() -> {
            grid.removeHorde(h);
            hordes.remove(h.id);
            for (ZombieRecord r : members) {
                r.idle = false;
                r.heading = (float) Math.atan2(ty - r.y(), tx - r.x());
                r.targetX = (int) Math.floor(tx);
                r.targetY = (int) Math.floor(ty);
                r.investigateUntilHours = investigateUntilHours;
                store.restore(r);
                schedule(r, currentMinute);
            }
        });
    }

    /** A dormant building horde listens: a louder horde's moaning, noticed gradually, sets it loose. */
    private void listen(Horde h, double dt) {
        Hearing.Pull p = pull(h.x, h.y, h.id, 0f);
        if (p != null && p.horde() != null
                && random.getAsDouble() < 1 - Math.pow(1 - encounters.ramped(s.moanResponsePerHour(), currentHours), dt / 60.0)) {
            wake(h, p.x(), p.y(), currentHours + INVESTIGATE_HOURS, p.horde().id);
            return;
        }
        if (p != null && p.event() != null) {
            wake(h, p.x(), p.y(), p.event().endHours() + INVESTIGATE_HOURS, 0);
            return;
        }
        scheduleHorde(h, currentMinute + LevelOfDetail.FAR_MINUTES);
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
        return restoreHorde(id, x, y, heading, pauseMinutes, members, false);
    }

    public Horde restoreHorde(long id, float x, float y, float heading, float pauseMinutes, List<ZombieRecord> members,
                              boolean dormant) {
        Horde h = hordes.restore(id, x, y, heading, members);
        h.dormant = dormant;
        if (dormant) dormantByChunk.put(SpatialGrid.key(chunk(x), chunk(y)), h);
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
        if (!pendingIdle.isEmpty()) groupBuildings();
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
                    long u0 = System.nanoTime();
                    updateWanderer(r, nowHours);
                    noteUpdate(System.nanoTime() - u0, 0);
                    updated++;
                }
            } else {
                Horde h = hordes.get(-key);
                if (h != null && h.dueMinute == minute) {
                    long u0 = System.nanoTime();
                    int size = h.size();
                    updateHorde(h, nowHours);
                    noteUpdate(System.nanoTime() - u0, size);
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

    /**
     * In-game minutes this update simulates: the time since the walker's last update, but at most MAX_STEP_MINUTES.
     * When the game runs faster than the simulation can follow (fast-forward, sleeping), walkers move a little slower
     * in game time instead of jumping hours at once, which would break follow chains, joining and gathering.
     */
    private double elapsedMinutes(double simHours, double now, int interval) {
        if (Double.isNaN(simHours)) return interval;
        return Math.max(0, Math.min(MAX_STEP_MINUTES, (now - simHours) * 60));
    }

    /** How far behind the oldest due walker is, in in-game minutes (0 when keeping up). */
    public long lagMinutes() {
        Map.Entry<Long, LongStack> e = buckets.firstEntry();
        return e == null ? 0 : Math.max(0, currentMinute - e.getKey());
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
            if (r.followId < 0 && hordes.get(-r.followId) == null) r.followId = 0; // the horde it heard is gone
            if (p != null && p.event() == null && p.horde() != null && r.followId != -p.horde().id) {
                // Moaning is noticed gradually: a chance per hour to turn towards it; until then it is ignored.
                if (r.straggler || random.getAsDouble() >= 1 - Math.pow(1 - encounters.ramped(s.moanResponsePerHour(), currentHours), dt / 60.0)) {
                    p = null;
                } else {
                    r.followId = -p.horde().id;
                }
            }
            // Stragglers hear real sounds like everyone, but never go towards a horde's moaning.
            if (p != null && (p.event() != null || !r.straggler)) {
                px = p.x();
                py = p.y();
                pw = p.weight();
                if (p.event() != null) {
                    encounters.responded(p.event(), r);
                    // It keeps heading for the spot after the sound ends (spec 4.2: converging on a sound).
                    r.targetX = (int) Math.floor(p.event().x());
                    r.targetY = (int) Math.floor(p.event().y());
                    r.investigateUntilHours = p.event().endHours() + INVESTIGATE_HOURS;
                    if (Hearing.dist(r.x(), r.y(), px, py) <= MILL_RADIUS) {
                        r.pauseMinutes = 2 + 3 * rng.nextFloat();
                        walk = false;
                    }
                }
            } else if (r.hasTarget() && r.investigateUntilHours > now) {
                // Still going to where it heard the sound: when 10 or more gather there they become a horde.
                px = r.targetX + 0.5f;
                py = r.targetY + 0.5f;
                pw = Hearing.EVENT_PULL;
                if (Hearing.dist(r.x(), r.y(), px, py) <= MILL_RADIUS) {
                    r.pauseMinutes = 2 + 3 * rng.nextFloat();
                    walk = false;
                    final float gx = px, gy = py;
                    internal(() -> encounters.gatherAt(gx, gy, r.targetX, r.targetY));
                    if (store.get(r.id) != r) return; // it became part of a horde
                }
            } else if (r.followId > 0) {
                ZombieRecord t = store.get(r.followId);
                if (t == null || Hearing.dist(r.x(), r.y(), t.x(), t.y()) > TRAIL_LOST) {
                    r.followId = 0; // lost sight of it
                } else if (trail(r, t, dt)) {
                    walk = false;
                } else {
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
                    if (r.investigateUntilHours <= now) {
                        r.targetX = ZombieRecord.NO_TARGET; // it walked somewhere else: the old target is stale
                        r.targetY = ZombieRecord.NO_TARGET;
                    }
                }
                // No random pauses (in-game test 2026-10-15: they left zombies standing on roads); only boxed in.
                r.pauseMinutes = st.boxedIn() ? 10f : 0f;
                // A straggler only shuffles a little, then lingers where it is for a few hours (spec 4.6).
                if (r.straggler && (p == null || p.event() == null)) {
                    r.lingerUntilHours = now + STRAGGLER_LINGER_MIN_HOURS + 3 * rng.nextDouble();
                }
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
        if (h.dormant) {
            listen(h, dt);
            return;
        }
        int iv = interval(h.x, h.y);
        // Touching the loaded area: the main thread lays out the members whose place is inside it (the rest walk on).
        if (iv < LevelOfDetail.FAR_MINUTES && HandOver.touchesLoaded(h, players)) layoutDue = true;
        boolean walk = true;
        if (h.pauseMinutes > 0) {
            h.pauseMinutes = (float) Math.max(0, h.pauseMinutes - dt);
            walk = h.pauseMinutes <= 0;
        }
        int tight = 0;
        if (walk) {
            Hearing.Pull p = pull(h.x, h.y, h.id, Hearing.maskingDb(h));
            if (h.followHordeId != 0 && hordes.get(h.followHordeId) == null) h.followHordeId = 0;
            if (p != null && p.event() == null && p.horde() != null && h.followHordeId != p.horde().id) {
                // Like single zombies, a horde notices a louder horde's moaning gradually.
                if (random.getAsDouble() >= 1 - Math.pow(1 - encounters.ramped(s.moanResponsePerHour(), currentHours), dt / 60.0)) p = null;
            }
            h.followHordeId = p != null && p.horde() != null ? p.horde().id : 0;
            float px = 0, py = 0, pw = 0;
            if (p != null && p.event() != null) {
                // Like a wanderer, it keeps heading for the spot after the sound ends.
                h.targetX = p.event().x();
                h.targetY = p.event().y();
                h.investigateUntilHours = p.event().endHours() + INVESTIGATE_HOURS;
            }
            if (p != null) {
                px = p.x();
                py = p.y();
                pw = p.weight();
            } else if (!Float.isNaN(h.targetX) && h.investigateUntilHours > now) {
                px = h.targetX;
                py = h.targetY;
                pw = Hearing.EVENT_PULL;
            }
            if (pw > 0 && (p == null || p.event() != null)
                    && Hearing.dist(h.x, h.y, px, py) <= MILL_RADIUS + h.spread()) {
                h.pauseMinutes = 2 + 3 * rng.nextFloat();
                h.targetX = Float.NaN; // arrived
                walk = false;
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
                h.pauseMinutes = st.boxedIn() ? 10f : 0f;
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

    /** Remembers the slowest single update (hordeSize 0 = a wanderer) for the stats line. */
    private void noteUpdate(long nanos, int hordeSize) {
        if (nanos > maxUpdateNanos) {
            maxUpdateNanos = nanos;
            maxUpdateWhat = hordeSize == 0 ? "wanderer" : "horde of " + hordeSize;
        }
    }

    /**
     * A follower walks straight to its place just behind the zombie it follows (as far as its speed allows this
     * update) and takes its heading, so a follow chain stays together and can become a horde. False when that place
     * cannot be entered (water, buildings, the loaded area): the follower then just steers towards it.
     */
    private boolean trail(ZombieRecord r, ZombieRecord t, double dt) {
        float gx = (float) (t.x() - Math.cos(t.heading) * TRAIL_GAP), gy = (float) (t.y() - Math.sin(t.heading) * TRAIL_GAP);
        int gcx = chunk(gx), gcy = chunk(gy);
        if (movement.map().rank(gcx, gcy) == 0 || players.nearChunk(gcx, gcy)) return false;
        float d = Hearing.dist(r.x(), r.y(), gx, gy);
        float reach = movement.speedTilesPerGameMinute(1, s.speedSetting(), r.id, (r.stateFlags & 2) != 0) * (float) dt;
        if (d > reach) {
            float f = reach / d;
            gx = r.x() + (gx - r.x()) * f;
            gy = r.y() + (gy - r.y()) * f;
            if (movement.map().rank(chunk(gx), chunk(gy)) == 0 || players.nearChunk(chunk(gx), chunk(gy))) return false;
        }
        store.moved(r, gx, gy);
        r.heading = t.heading;
        r.pauseMinutes = 0;
        r.targetX = ZombieRecord.NO_TARGET;
        r.targetY = ZombieRecord.NO_TARGET;
        return true;
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
            if (h.dormant) continue; // building hordes wait silently
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
            "sim: behind %d in-game min, wanderers %d, hordes %d (biggest %d, %d members), updates %d, queue %d, last step %.2f ms (max %.2f, slowest update %.2f ms: %s), "
                + "formed %d, joined %d, merged %d, left behind %d, broke up %d, sounds %d | conservation %s",
            lagMinutes(), store.size(), hordes.size(), biggest, hordes.memberCount(), updatesTotal, queued, lastStepNanos / 1e6,
            maxStepNanos / 1e6, maxUpdateNanos / 1e6, maxUpdateWhat, encounters.formed(), encounters.joined(), encounters.merged(), encounters.brokeAway(),
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
