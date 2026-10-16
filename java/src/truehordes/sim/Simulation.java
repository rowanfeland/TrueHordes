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
    /** World sounds captured on the game's main thread, handled at the start of the next step (lock-free). */
    private final java.util.concurrent.ConcurrentLinkedQueue<Offer> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final SoundModel soundModel;
    /** Sound spots (target tile key) last checked for gathering, and the minute (cleared with the sounds). */
    private final Map<Long, Long> gatherChecked = new HashMap<>();
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
        this.soundModel = new SoundModel(s);
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

    /** A world sound offered from any thread: x, y, radius in tiles (as the game hears it), re-sent every tick or not. */
    record Offer(float x, float y, float radius, boolean repeating, long sourceKey, int kind) {}

    /** Minutes an ongoing (re-sent) sound stays active after the game last re-sent it. */
    static final double REPEAT_HOLD_MINUTES = 2;
    /** A discrete sound (a gunshot) is over in a moment; kept this long for the viewer and for gathering. */
    static final double DISCRETE_MINUTES = 1;
    /** Walkers heading for a spot this close to a new sound do not roll again (their time is refreshed). */
    static final float SAME_SPOT = 20f;

    /**
     * A world sound captured on any thread (the game's main thread, without the core lock): handled at the start of
     * the next step. {@code sourceKey} identifies the game's source so that re-sends of a repeating sound fold into
     * one ongoing sound (0 = none).
     */
    public void offerSound(float x, float y, float radius, boolean repeating, long sourceKey, int kind) {
        if (radius > 0) inbox.add(new Offer(x, y, radius, repeating, sourceKey, kind));
    }

    /** A sound at (x, y) heard for {@code durationHours} (the debug sound): rolled at once for everyone in reach. */
    public SoundEvent addSound(float x, float y, float loudnessDb, double durationHours) {
        SoundEvent e = new SoundEvent(nextEventId++, x, y, loudnessDb, currentHours, currentHours + durationHours,
            false, 0, SoundEvent.DEBUG, false, 0);
        events.add(e);
        broadcast(e);
        return e;
    }

    /** Handles queued sounds while the slice lasts (at least one per step); the rest wait for the next step. */
    private void drainSounds(long t0, long budgetNanos) {
        Offer o;
        boolean first = true;
        while ((first || System.nanoTime() - t0 < budgetNanos) && (o = inbox.poll()) != null) {
            first = false;
            if (o.repeating()) {
                SoundEvent same = ongoing(o);
                if (same != null) {
                    same.extendTo(currentHours + REPEAT_HOLD_MINUTES / 60);
                    continue;
                }
            }
            double minutes = o.repeating() ? REPEAT_HOLD_MINUTES : DISCRETE_MINUTES;
            SoundEvent e = new SoundEvent(nextEventId++, o.x(), o.y(), SoundModel.dbFromRadius(o.radius()), currentHours,
                currentHours + minutes / 60, false, 0, o.kind(), o.repeating(), o.sourceKey());
            events.add(e);
            broadcast(e);
        }
        // Ongoing sounds: walkers that walked into reach since roll once (checked once per in-game minute).
        for (int i = 0; i < events.size(); i++) {
            SoundEvent e = events.get(i);
            if (e.repeating && e.lastBroadcastMinute != currentMinute) broadcast(e);
        }
    }

    private SoundEvent ongoing(Offer o) {
        for (int i = 0; i < events.size(); i++) {
            SoundEvent e = events.get(i);
            if (e.repeating && e.sourceKey == o.sourceKey() && Hearing.dist(e.x(), e.y(), o.x(), o.y()) <= 2) return e;
        }
        return null;
    }

    /**
     * Rolls a sound for every held wanderer and horde in its reach that has not rolled for it yet (sub-project 5a spec
     * section 4): at once, not at their next update, so far zombies on slow updates hear a single gunshot too. A big
     * circle walks only the occupied chunks.
     */
    private void broadcast(SoundEvent e) {
        e.lastBroadcastMinute = currentMinute;
        float range = e.radius() * SoundModel.maxReachFactor(s.hearingSetting()), ex = e.x(), ey = e.y();
        List<ZombieRecord> ws = new ArrayList<>();
        grid.wanderersIn(ex - range, ey - range, ex + range, ey + range, r -> {
            if (r.rolledSoundId != e.id() && Hearing.dist(r.x(), r.y(), ex, ey) <= range) ws.add(r);
        });
        List<Horde> hs = new ArrayList<>();
        grid.nearHordes(ex, ey, range, h -> { if (h.rolledSoundId != e.id()) hs.add(h); });
        for (ZombieRecord r : ws) {
            r.rolledSoundId = e.id();
            if (store.get(r.id) == r) wandererHears(r, e);
        }
        for (Horde h : hs) {
            h.rolledSoundId = e.id();
            if (hordes.get(h.id) == h) hordeHears(h, e);
        }
    }

    private void wandererHears(ZombieRecord r, SoundEvent e) {
        float level = SoundModel.levelAt(e.loudnessDb(), Hearing.dist(r.x(), r.y(), e.x(), e.y()))
            + SoundModel.hearingBoostDb(SoundModel.perZombieHearing(s.hearingSetting(), r.id));
        if (level < SoundModel.THRESHOLD_DB) return;
        if (r.hasTarget() && r.investigateUntilHours > currentHours) {
            float tx = r.targetX + 0.5f, ty = r.targetY + 0.5f;
            if (Hearing.dist(tx, ty, e.x(), e.y()) <= SAME_SPOT) {
                r.investigateUntilHours = Math.max(r.investigateUntilHours, e.endHours() + INVESTIGATE_HOURS);
                return;
            }
            float current = SoundModel.levelAt(r.targetDb, Hearing.dist(r.x(), r.y(), tx, ty));
            if (level < current + SoundModel.SWITCH_DB) return;
        }
        if (random.getAsDouble() >= soundModel.loneChance(level)) return;
        sendTo(r, e, e.endHours() + INVESTIGATE_HOURS);
        e.wanderers++;
    }

    private void sendTo(ZombieRecord r, SoundEvent e, double until) {
        r.idle = false;
        r.lingerUntilHours = 0;
        r.pauseMinutes = 0;
        r.targetX = (int) Math.floor(e.x());
        r.targetY = (int) Math.floor(e.y());
        r.targetDb = e.loudnessDb();
        r.investigateUntilHours = until;
        r.heading = (float) Math.atan2(e.y() - r.y(), e.x() - r.x());
        encounters.responded(e, r);
        schedule(r, currentMinute);
    }

    /**
     * A horde in reach of a sound: the share that hears it (its own moaning drowns the rest) goes for it; half or more
     * and the whole horde goes (hive mind). A dormant building horde is woken the same way.
     */
    private void hordeHears(Horde h, SoundEvent e) {
        float d = Math.max(1f, Hearing.dist(h.x, h.y, e.x(), e.y()) - h.spread());
        // A horde hears with the setting's usual hearing (Random: Normal).
        int hs = s.hearingSetting() >= 1 && s.hearingSetting() <= 3 ? s.hearingSetting() : 2;
        float level = SoundModel.levelAt(e.loudnessDb(), d) + SoundModel.hearingBoostDb(hs);
        if (level < SoundModel.THRESHOLD_DB) return;
        if (!h.dormant && !Float.isNaN(h.targetX) && h.investigateUntilHours > currentHours) {
            if (Hearing.dist(h.targetX, h.targetY, e.x(), e.y()) <= SAME_SPOT) {
                h.investigateUntilHours = Math.max(h.investigateUntilHours, e.endHours() + INVESTIGATE_HOURS);
                return;
            }
            float current = SoundModel.levelAt(h.targetDb, Math.max(1f, Hearing.dist(h.x, h.y, h.targetX, h.targetY)
                - h.spread()));
            if (level < current + SoundModel.SWITCH_DB) return;
        }
        int n = h.size();
        int k = (int) Math.floor(soundModel.hordeShare(level, n) * n + random.getAsDouble());
        if (k <= 0) return;
        double until = e.endHours() + INVESTIGATE_HOURS;
        e.hordes++;
        if (k >= n) {
            if (h.dormant) {
                wake(h, e.x(), e.y(), until, 0);
            } else {
                h.targetX = e.x();
                h.targetY = e.y();
                h.investigateUntilHours = until;
                h.followHordeId = 0;
                h.pauseMinutes = 0;
                h.heading = (float) Math.atan2(e.y() - h.y, e.x() - h.x);
                scheduleHorde(h, currentMinute);
            }
            h.targetDb = e.loudnessDb();
            return;
        }
        breakOff(h, k, e, until);
    }

    /**
     * {@code k} members of {@code h} heard the sound and leave from its edge facing it: 10 or more as a new horde,
     * fewer as wanderers, all heading for the sound. The rest carry on.
     */
    private void breakOff(Horde h, int k, SoundEvent e, double until) {
        float old = h.spread();
        float ang = (float) Math.atan2(e.y() - h.y, e.x() - h.x);
        // Clear of the horde it left (touching would merge them straight back).
        float away = old + Horde.spreadOf(k) + Encounters.BUMP_RADIUS + 1;
        float ex = h.x + (float) Math.cos(ang) * away, ey = h.y + (float) Math.sin(ang) * away;
        if (movement.map().rank(chunk(ex), chunk(ey)) == 0) {
            ex = h.x;
            ey = h.y;
        }
        List<ZombieRecord> out = new ArrayList<>(k);
        for (int i = 0; i < k; i++) out.add(h.members.get(h.members.size() - 1 - i));
        java.util.Set<ZombieRecord> gone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        gone.addAll(out);
        final float fx = ex, fy = ey;
        internal(() -> {
            hordes.removeMembers(h, gone);
            grid.moveHorde(h, h.x, h.y, old);
            if (k >= s.hordeFormSize()) {
                Horde nh = hordes.create(fx, fy, ang, out);
                nh.targetX = e.x();
                nh.targetY = e.y();
                nh.targetDb = e.loudnessDb();
                nh.investigateUntilHours = until;
                nh.rolledSoundId = e.id();
                grid.putHorde(nh);
                scheduleHorde(nh, currentMinute);
            } else {
                for (ZombieRecord r : out) {
                    r.hordeId = 0;
                    r.followId = 0;
                    r.rolledSoundId = e.id();
                    store.restore(r);
                    store.moved(r, fx + (float) (random.getAsDouble() - 0.5) * 3, fy + (float) (random.getAsDouble() - 0.5) * 3);
                    sendTo(r, e, until);
                }
            }
        });
        e.splitOff += k;
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
        drainSounds(t0, budgetNanos);
        if (gatherChecked.size() > 4096) gatherChecked.clear();
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
            // Heading for a sound it heard: moaning (always the lowest priority) does not turn it.
            Hearing.Pull p = r.hasTarget() && r.investigateUntilHours > now ? null : pull(r.x(), r.y(), 0, 0f);
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
                    // Once per spot per in-game minute: a firefight draws thousands, and each arrival scanning all the
                    // others at the spot every update grew with the square of the crowd.
                    long spot = SpatialGrid.key(r.targetX, r.targetY);
                    Long last = gatherChecked.get(spot);
                    if (last == null || last != currentMinute) {
                        gatherChecked.put(spot, currentMinute);
                        internal(() -> encounters.gatherAt(gx, gy, r.targetX, r.targetY));
                    }
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
            Hearing.Pull p = !Float.isNaN(h.targetX) && h.investigateUntilHours > now ? null
                : pull(h.x, h.y, h.id, Hearing.maskingDb(h));
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
        if (near.isEmpty()) return null;
        // Sounds are rolled once when they happen (broadcast) and followed as targets; only moaning pulls here.
        return Hearing.choose(x, y, ownHordeId, ownDb, java.util.Collections.emptyList(), near);
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
