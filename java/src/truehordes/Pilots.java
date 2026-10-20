package truehordes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import se.krka.kahlua.vm.KahluaTable;
import truehordes.core.Core;
import truehordes.real.Crowd;
import truehordes.real.Pilot;
import truehordes.sim.Encounters;
import truehordes.sim.Hearing;
import truehordes.sim.Movement;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.sim.SoundModel;
import truehordes.store.ZombieRecord;
import truehordes.util.Log;
import zombie.GameTime;
import zombie.ai.State;
import zombie.ai.states.PathFindState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.ZombieIdleState;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Steers real zombies by the simulation's rules (sub-project 6 spec goals 1-7): every real zombie has a {@link Pilot};
 * when vanilla is not busy with it (no target, not attacking, thumping, eating, lying or sitting), it is sent with
 * {@code pathToLocation} to a waypoint 12-20 tiles on, picked by its own {@link Movement} (travel, milling at home,
 * following a sound or a light, walking behind its horde's leader in a column). Every zombie in the loaded area is
 * real (user, 2026-10-16: hiding unseen ones made invisible zombies; not worth it). Main thread only.
 */
final class Pilots {
    private final WeakHashMap<IsoZombie, Pilot> pilots = new WeakHashMap<>();
    private Map<Long, Crowd.Group> groups = new HashMap<>();
    private final Random rnd = new Random();
    private Movement movement;
    private SimSettings movementSettings;
    private truehordes.sim.MapView movementMap;
    int ordersPerTick = 8;
    private int cursor;
    private long lastCrowdMs;
    private double lastCrowdHours = Double.NaN;
    private int orders;

    /** Tiles ahead a waypoint is put (about 12-20 with the walker's speed rounding). */
    static final float LOOK = 24f;
    /** A zombie this close to its lure mills there. */
    static final float MILL = 4f;
    static final double INVESTIGATE_HOURS = 6;
    /** Zombies looked at per tick (each comes round again every few ticks). */
    static final int PER_TICK = 200;
    static final long BUDGET_NANOS = 2_000_000L;

    void readSettings() {
        ordersPerTick = (int) Math.max(1, GameEnvImpl.sandboxDouble("TrueHordes.PilotOrdersPerTick", 8));
        chaseHours = Math.max(0, GameEnvImpl.sandboxDouble("TrueHordes.ChaseHours", 1));
    }

    /** Chase memory (user, 2026-10-20): in-game hours a zombie that lost a player keeps after them (0 off). */
    double chaseHours = 1;
    private int chases;

    int count() { return pilots.size(); }

    /** The real hordes as of the last crowd pass (for the debug map). */
    java.util.Collection<Crowd.Group> realGroups() { return groups.values(); }

    /** The horde a real zombie is in (0 none), for the debug map. */
    long hordeIdOf(IsoZombie z) {
        Pilot p = pilots.get(z);
        return p == null ? 0 : p.hordeId;
    }

    String stats() {
        return "pilots " + pilots.size() + ", real hordes " + groups.size() + ", orders " + orders + ", walked in "
            + PopulationBridge.walkedIn + ", walked off " + walkedOff + ", chases " + chases;
    }

    static double nowHours() { return GameTime.getInstance().getWorldAgeHours(); }

    /** Multiplayer (sub-project 7): the id clients and server agree on is the zombie's online id. */
    static boolean mp() { return GameServer.server || GameClient.client; }

    static long idOf(IsoZombie z) {
        if (mp()) return z.getOnlineID();
        return z.persistentId != 0 ? z.persistentId : System.identityHashCode(z);
    }

    /**
     * Single player: forming and steering. Dedicated server (sub-project 7): forming only (the server is the brain:
     * hordes, follows, lures; it tells each client in tags); the clients that own the zombies steer them.
     */
    void tick(Core c, long nowMs) {
        if (!c.ownsPopulation()) return;
        core = c;
        SimSettings s = c.simSettings();
        truehordes.sim.MapView map = c.mapView();
        if (s == null || map == null) return;
        double now = nowHours();
        List<IsoZombie> list = IsoWorld.instance.currentCell.getZombieList();
        if (nowMs - lastCrowdMs >= 1000) {
            lastCrowdMs = nowMs;
            crowd(c, list, s, now);
        }
        if (GameServer.server) return;
        steer(list, nowMs, now, s, map, false);
    }

    // ---- multiplayer client (sub-project 7) ----

    private truehordes.world.World clientWorld;
    private SimSettings clientSettings;
    private truehordes.sim.MapView clientMap;
    private final java.util.ArrayDeque<double[]> out = new java.util.ArrayDeque<>();
    /** Outgoing message kinds: a lure the zombie heard, a zombie walked off the loaded edge. */
    static final int OUT_LURE = 1, OUT_OFF = 2, OUT_CHASE = 3, OUT_CHASE_FAR = 4;

    /** The client's own copy of the map (built in the background on first use; the pilots' Movement needs it). */
    truehordes.world.World clientWorld() { return clientWorld; }

    /** MP client, every tick (from Lua): steer the zombies this client owns by the tags the server sends. */
    void clientTick(long nowMs) {
        if (!GameClient.client) return;
        try {
            if (clientWorld == null) {
                GameEnvImpl env = new GameEnvImpl();
                clientSettings = env.simSettings();
                truehordes.world.WorldSource src = env.worldSource();
                if (src == null) return;
                clientWorld = new truehordes.world.World();
                clientWorld.start(src);
                readSettings();
                Log.info("MP client: building the map copy the real-zombie steering uses");
            }
            clientWorld.tick();
            Hooks.EVENTS.tickAudio();
            if (!clientWorld.ready()) return;
            if (clientMap == null) clientMap = new truehordes.sim.WorldMapView(clientWorld);
            double now = nowHours();
            List<IsoZombie> list = IsoWorld.instance.currentCell.getZombieList();
            if (nowMs - lastCrowdMs >= 1000) {
                lastCrowdMs = nowMs;
                List<Pilot> ps = new ArrayList<>(list.size());
                byId.clear();
                for (IsoZombie z : list) {
                    if (z == null || z.isDead() || z.isReanimatedPlayer()) continue;
                    Pilot p = pilot(z);
                    p.id = idOf(z);
                    p.x = z.getX();
                    p.y = z.getY();
                    ps.add(p);
                    byId.put(p.id, p);
                }
                groups = Crowd.groups(ps);
                chaseRules(now);
            }
            steer(list, nowMs, now, clientSettings, clientMap, true);
        } catch (Throwable t) {
            Log.errorOnce("pilots:client:" + t.getClass().getName(), "MP client steering failed", t);
        }
    }

    /** The next message for the server (MP client): {kind, onlineId, x, y, until-or-heading}, or null. */
    double[] takeOut() { return out.poll(); }

    static final int TAG_STRIDE = 9;

    /**
     * MP client: the server's word on the zombies near this player (rows of TAG_STRIDE: online id, horde, follow,
     * dormant, straggler, lure x, lure y, lure until, heading). Zombies near the player it does not list are loners.
     */
    void applyTags(double[] rows, float px, float py, float radius) {
        java.util.Set<Long> listed = new java.util.HashSet<>();
        for (int i = 0; i + TAG_STRIDE - 1 < rows.length; i += TAG_STRIDE) {
            IsoZombie z = GameClient.IDToZombieMap.get((short) rows[i]);
            if (z == null) continue;
            Pilot p = pilot(z);
            p.id = idOf(z);
            listed.add(p.id);
            p.hordeId = (long) rows[i + 1];
            p.followId = (long) rows[i + 2];
            p.dormant = rows[i + 3] != 0;
            p.straggler = rows[i + 4] != 0;
            if (!Double.isNaN(rows[i + 5]) && rows[i + 7] > p.lureUntilHours + 0.01 && !p.chasing(nowHours())) {
                p.lure((float) rows[i + 5], (float) rows[i + 6], rows[i + 7], p.lureDb);
            }
            if (Float.isNaN(p.wpX)) p.heading = (float) rows[i + 8];
        }
        for (java.util.Map.Entry<IsoZombie, Pilot> e : pilots.entrySet()) {
            Pilot p = e.getValue();
            if (listed.contains(p.id) || Math.hypot(p.x - px, p.y - py) > radius) continue;
            p.hordeId = 0;
            p.followId = 0;
        }
    }

    /** Server: tags of the zombies within {@code radius} of (px, py) that are not plain loners. */
    double[] tagsFor(float px, float py, float radius) {
        List<double[]> rows = new ArrayList<>();
        double now = nowHours();
        for (java.util.Map.Entry<IsoZombie, Pilot> e : pilots.entrySet()) {
            IsoZombie z = e.getKey();
            Pilot p = e.getValue();
            if (z == null || Math.hypot(z.getX() - px, z.getY() - py) > radius) continue;
            boolean lured = p.lured(now);
            if (p.hordeId == 0 && p.followId == 0 && !p.dormant && !p.straggler && !lured) continue;
            rows.add(new double[] {z.getOnlineID(), p.hordeId, p.followId, p.dormant ? 1 : 0, p.straggler ? 1 : 0,
                lured ? p.lureX : Double.NaN, lured ? p.lureY : Double.NaN, lured ? p.lureUntilHours : 0, p.heading});
        }
        double[] flat = new double[rows.size() * TAG_STRIDE];
        for (int i = 0; i < rows.size(); i++) System.arraycopy(rows.get(i), 0, flat, i * TAG_STRIDE, TAG_STRIDE);
        return flat;
    }

    /** Server: a client's zombie heard a sound (vanilla sent it there); it and its horde head there. */
    void clientLure(int onlineId, float x, float y) {
        IsoZombie z = ServerMap.instance.zombieMap.get((short) onlineId);
        if (z != null) heard(z, x, y, SoundModel.THRESHOLD_DB + 20);
    }

    /** Server: a client's zombie reached the loaded edge heading out; the mod takes it just past the edge. */
    void clientChase(int onlineId, float x, float y, double untilHours, boolean far) {
        IsoZombie z = ServerMap.instance.zombieMap.get((short) onlineId);
        if (z == null) return;
        Pilot p = pilot(z);
        if (far) {
            p.chaseFarX = x;
            p.chaseFarY = y;
            return;
        }
        if (!p.chasing(nowHours())) chases++;
        p.chase(x, y, 0, 0, untilHours, SoundModel.THRESHOLD_DB + 20);
    }

    /** Server: a client's zombie reached the loaded edge heading out; the mod takes it just past the edge. */
    void clientOff(Core c, int onlineId, float x, float y, float heading) {
        IsoZombie z = ServerMap.instance.zombieMap.get((short) onlineId);
        if (z == null) return;
        Pilot p = pilot(z);
        p.heading = heading;
        if (PopulationBridge.walkOff(c, z, x, y)) {
            pilots.remove(z);
            walkedOff++;
        }
    }

    // ---- steering (single player, and MP clients for the zombies they own) ----

    private boolean steeringClient;

    private void steer(List<IsoZombie> list, long nowMs, double now, SimSettings s, truehordes.sim.MapView map,
                       boolean client) {
        if (movement == null || movementSettings != s || movementMap != map) {
            movement = new Movement(map, s);
            movementSettings = s;
            movementMap = map;
        }
        steeringClient = client;
        int n = list.size();
        if (n == 0) return;
        long t0 = System.nanoTime();
        int budget = ordersPerTick;
        for (int k = 0; k < Math.min(n, PER_TICK); k++) {
            if (cursor >= n) cursor = 0;
            IsoZombie z = list.get(cursor++);
            if (z == null || z.isDead() || z.isReanimatedPlayer()) continue;
            if (client && z.isRemoteZombie()) continue; // another player's machine runs it
            Pilot p = pilot(z);
            p.x = z.getX();
            p.y = z.getY();
            soundPath(z, p, now, client);
            sight(z, p, now, client);
            if (busy(z)) {
                p.wpX = Float.NaN;
                continue;
            }
            if (budget > 0 && due(p, z, nowMs, now, s)) {
                order(p, z, nowMs, now, s);
                budget--;
            }
            if ((k & 15) == 15 && System.nanoTime() - t0 > BUDGET_NANOS) break;
        }
    }

    /**
     * Vanilla sent the zombie to a sound (MP clients run vanilla hearing): that spot is its lure for the usual six
     * hours, as off screen; an MP client tells the server, which sends the whole horde.
     */
    private void soundPath(IsoZombie z, Pilot p, double now, boolean client) {
        State st = z.getCurrentState();
        if (st != PathFindState.instance() && st != WalkTowardState.instance()) return;
        if (!z.getPathFindBehavior2().isGoalSound()) return;
        float tx = z.getPathTargetX() + 0.5f, ty = z.getPathTargetY() + 0.5f;
        if (p.lured(now) && Crowd_dist(p.lureX, p.lureY, tx, ty) < 4f) return;
        p.lure(tx, ty, now + INVESTIGATE_HOURS, SoundModel.THRESHOLD_DB + 20);
        p.wpX = tx;
        p.wpY = ty;
        p.nextOrderMs = System.currentTimeMillis() + 8000;
        if (client) out.add(new double[] {OUT_LURE, z.getOnlineID(), tx, ty, p.lureUntilHours});
    }

    /**
     * Chase memory (user, 2026-10-20): while vanilla has a zombie after a player, note where it last saw them and the
     * way they were going; when vanilla gives up, it goes to that spot and on along that heading for chaseHours.
     */
    private void sight(IsoZombie z, Pilot p, double now, boolean client) {
        if (z.getTarget() instanceof IsoPlayer pl) {
            float px = pl.getX(), py = pl.getY();
            if (Float.isNaN(p.seenX)) {
                p.seenDx = 0;
                p.seenDy = 0;
            } else {
                float dx = px - p.seenX, dy = py - p.seenY;
                if (dx * dx + dy * dy > 0.25f) {
                    p.seenDx = dx;
                    p.seenDy = dy;
                }
            }
            p.seenX = px;
            p.seenY = py;
            return;
        }
        if (Float.isNaN(p.seenX) || z.getTarget() != null) return;
        float sx = p.seenX, sy = p.seenY;
        p.seenX = Float.NaN;
        if (chaseHours <= 0) return;
        p.chase(sx, sy, p.seenDx, p.seenDy, now + chaseHours, SoundModel.THRESHOLD_DB + 20);
        p.wpX = Float.NaN;
        chases++;
        if (client) {
            out.add(new double[] {OUT_CHASE, z.getOnlineID(), sx, sy, p.chaseUntilHours});
            if (!Float.isNaN(p.chaseFarX)) {
                out.add(new double[] {OUT_CHASE_FAR, z.getOnlineID(), p.chaseFarX, p.chaseFarY, p.chaseUntilHours});
            }
        }
    }

    /** Chase memory: a real horde more than half of which is chasing a player follows them. */
    private void chaseRules(double now) {
        for (Crowd.Group g : groups.values()) Crowd.chaseRule(g, now);
    }

    Pilot pilot(IsoZombie z) {
        Pilot p = pilots.get(z);
        if (p == null) {
            p = new Pilot(idOf(z));
            p.heading = z.getForwardDirection().getDirection();
            p.crawler = z.isCrawling();
            readModData(z, p);
            pilots.put(z, p);
        }
        return p;
    }

    /** A held record just made real as {@code z}: its pilot carries the record's state. */
    void adopt(IsoZombie z, ZombieRecord r) {
        Pilot p = new Pilot(idOf(z));
        p.x = r.x();
        p.y = r.y();
        p.heading = r.heading;
        p.hordeId = r.hordeId;
        p.homeX = r.homeX;
        p.homeY = r.homeY;
        p.straggler = r.straggler;
        p.dormant = r.idle;
        p.crawler = (r.stateFlags & 2) != 0;
        if (r.hasTarget() && r.investigateUntilHours > nowHours()) {
            p.lure(r.targetX + 0.5f, r.targetY + 0.5f, r.investigateUntilHours, r.targetDb);
            p.dormant = false;
        }
        pilots.put(z, p);
        writeModData(z, p);
    }

    /** The record a real zombie leaves behind when it goes back to the simulation carries the pilot's state. */
    static void carry(Pilot p, ZombieRecord r, double now) {
        r.heading = p.heading;
        r.homeX = p.homeX;
        r.homeY = p.homeY;
        r.straggler = p.straggler;
        r.strRolled = true;
        r.idle = p.dormant;
        if (p.lured(now)) {
            // A chase taken off screen keeps going along the player's heading.
            boolean far = p.chasing(now) && !Float.isNaN(p.chaseFarX);
            r.targetX = (int) Math.floor(far ? p.chaseFarX : p.lureX);
            r.targetY = (int) Math.floor(far ? p.chaseFarY : p.lureY);
            r.investigateUntilHours = p.lureUntilHours;
            r.targetDb = p.lureDb;
        }
    }

    /** The horde a real zombie belongs to (0 none), for taking it into the simulation. */
    long hordeOf(IsoZombie z) {
        Pilot p = pilots.get(z);
        return p == null ? 0 : p.hordeId;
    }

    Pilot existing(IsoZombie z) { return pilots.get(z); }

    // ---- vanilla in charge ----

    /** Vanilla is busy with it: chasing, fighting, thumping, eating, falling, lying or sitting. */
    static boolean busy(IsoZombie z) {
        if (z.getTarget() != null || z.isUseless() || z.isFakeDead()) return true;
        State st = z.getCurrentState();
        return st != ZombieIdleState.instance() && st != PathFindState.instance() && st != WalkTowardState.instance();
    }

    // ---- hearing and lights ----

    /** A real zombie heard a sound (the 5a roll said yes): it and its horde head there (the horde rolls once). */
    void heard(IsoZombie z, float sx, float sy, float levelDb) {
        Pilot p = pilot(z);
        double now = nowHours();
        if (p.lured(now) && Crowd_dist(p.lureX, p.lureY, sx, sy) > 20f
                && levelDb < SoundModel.levelAt(p.lureDb, Crowd_dist(p.x, p.y, p.lureX, p.lureY)) + SoundModel.SWITCH_DB) {
            return;
        }
        Crowd.Group g = p.hordeId == 0 ? null : groups.get(p.hordeId);
        if (g == null) {
            p.lure(sx, sy, now + INVESTIGATE_HOURS, levelDb);
            return;
        }
        for (Pilot m : g.members) m.lure(sx, sy, now + INVESTIGATE_HOURS, levelDb);
    }

    /** A light (sub-project 5b) draws a real zombie: it goes there and stays near it until {@code untilHours}. */
    void lure(IsoZombie z, float x, float y, int zl, double untilHours) {
        Pilot p = pilot(z);
        p.lure(x, y, untilHours, Simulation.LURE_DB);
        z.pathToSound((int) x, (int) y, zl);
        p.wpX = x;
        p.wpY = y;
    }

    private static float Crowd_dist(float ax, float ay, float bx, float by) { return (float) Math.hypot(ax - bx, ay - by); }

    // ---- orders ----

    private boolean due(Pilot p, IsoZombie z, long nowMs, double now, SimSettings s) {
        if (p.dormant || now < p.waitUntilHours) return false;
        if (Float.isNaN(p.wpX) || nowMs >= p.nextOrderMs) return true;
        if (Crowd_dist(p.x, p.y, p.wpX, p.wpY) < 4f) return true;
        // A follower whose slot moved on (its leader took a new waypoint).
        float[] want = p.chasing(now) ? null : followTarget(p, now);
        if (want != null && Crowd_dist(want[0], want[1], p.wpX, p.wpY) > 6f) return true;
        if (nowMs - p.checkMs >= 6000) {
            boolean stuck = Crowd_dist(p.x, p.y, p.checkX, p.checkY) < 1f;
            p.checkMs = nowMs;
            p.checkX = p.x;
            p.checkY = p.y;
            if (stuck) {
                // Blocked or the path failed: turn aside (90 to 180 degrees) and try again.
                p.heading += (float) ((rnd.nextBoolean() ? 1 : -1) * (Math.PI / 2 + rnd.nextDouble() * Math.PI / 2));
                return true;
            }
        }
        return false;
    }

    /** Where a horde member or a follower wants to be: its slot behind its leader, or just behind the one it follows. */
    private float[] followTarget(Pilot p, double now) {
        if (p.hordeId != 0) {
            Crowd.Group g = groups.get(p.hordeId);
            if (g == null || g.members.isEmpty() || g.leader(now) == p) return null;
            Pilot l = g.leader(now);
            float bx = Float.isNaN(l.wpX) ? l.x : l.wpX, by = Float.isNaN(l.wpX) ? l.y : l.wpY;
            return Pilot.slot(p.slot, g.members.size(), bx, by, l.heading);
        }
        return null;
    }

    /** A zombie this close to the loaded edge, heading out, is taken off screen (tiles). */
    static final float EDGE = 4f;
    private int walkedOff;
    private Core core;

    private void order(Pilot p, IsoZombie z, long nowMs, double now, SimSettings s) {
        Core c = core;
        float tx, ty;
        if (!Float.isNaN(p.lureX) && p.lureUntilHours <= now) {
            // Done investigating: it settles where the sound took it.
            p.homeX = p.x;
            p.homeY = p.y;
            p.lureX = Float.NaN;
        }
        boolean chasing = p.chasing(now);
        float[] slot = chasing ? null : followTarget(p, now);
        Crowd.Group g = p.hordeId == 0 ? null : groups.get(p.hordeId);
        int members = g == null ? 1 : g.members.size();
        boolean leads = g == null || chasing || g.leader(now) == p;
        if (chasing && Crowd_dist(p.x, p.y, p.lureX, p.lureY) <= MILL + (g == null ? 0 : g.spread()) && p.chaseOn()) {
            // Chase memory: at the spot it last saw the player, on along their heading.
            if (steeringClient) out.add(new double[] {OUT_CHASE, z.getOnlineID(), p.lureX, p.lureY, p.chaseUntilHours});
        }
        if (slot != null) {
            tx = slot[0];
            ty = slot[1];
            p.heading = g.leader(now).heading;
        } else if (p.lured(now) && leads) {
            float d = Crowd_dist(p.x, p.y, p.lureX, p.lureY);
            if (d <= MILL + (g == null ? 0 : g.spread())) {
                // Arrived: mill about the spot a few minutes at a time.
                p.waitUntilHours = now + (2 + 3 * rnd.nextDouble()) / 60.0;
                tx = p.lureX + (float) ((rnd.nextDouble() - 0.5) * 8);
                ty = p.lureY + (float) ((rnd.nextDouble() - 0.5) * 8);
            } else if (d <= LOOK) {
                tx = p.lureX;
                ty = p.lureY;
                p.heading = (float) Math.atan2(ty - p.y, tx - p.x);
            } else {
                Movement.Step st = movement.advance(p.x, p.y, p.heading, minutes(members, p, s), members, p.id, p.crawler,
                    p.lureX, p.lureY, Hearing.EVENT_PULL, rnd::nextDouble);
                tx = st.x();
                ty = st.y();
                p.heading = st.boxedIn() ? p.heading + (float) Math.PI : st.heading();
            }
        } else if (p.followId != 0 && byId(p.followId) != null) {
            Pilot t = byId(p.followId);
            tx = (float) (t.x - Math.cos(t.heading) * 1.5);
            ty = (float) (t.y - Math.sin(t.heading) * 1.5);
            p.heading = t.heading;
        } else if (p.straggler) {
            // A straggler shuffles a little, then lingers for a few hours.
            tx = p.x + (float) ((rnd.nextDouble() - 0.5) * 6);
            ty = p.y + (float) ((rnd.nextDouble() - 0.5) * 6);
            p.waitUntilHours = now + 1 + 3 * rnd.nextDouble();
        } else {
            if (Float.isNaN(p.homeX)) {
                p.homeX = p.x;
                p.homeY = p.y;
            }
            float mins = minutes(members, p, s);
            Movement.Step st = members > 1 || Simulation.traveller(p.id, s.travellerShare())
                ? movement.advance(p.x, p.y, p.heading, mins, members, p.id, p.crawler, 0, 0, 0, rnd::nextDouble)
                : movement.advanceHome(p.x, p.y, p.heading, mins, p.id, p.crawler, p.homeX, p.homeY,
                    Simulation.HOME_RADIUS, rnd::nextDouble);
            tx = st.x();
            ty = st.y();
            p.heading = st.boxedIn() ? p.heading + (float) Math.PI : st.heading();
        }
        // The loaded edge (sub-project 6, user's test: real zombies never left): the game cannot path into unloaded
        // ground, so the waypoint stops at the last loaded tile on the way; at the edge the mod takes the zombie.
        if (!PopulationBridge.loadedAt(tx, ty)) {
            float dx = tx - p.x, dy = ty - p.y, len = (float) Math.hypot(dx, dy);
            float ex = p.x, ey = p.y, ox = tx, oy = ty;
            for (float t = 2; t < len; t += 2) {
                float qx = p.x + dx / len * t, qy = p.y + dy / len * t;
                if (!PopulationBridge.loadedAt(qx, qy)) {
                    ox = p.x + dx / len * (t + 2);
                    oy = p.y + dy / len * (t + 2);
                    break;
                }
                ex = qx;
                ey = qy;
            }
            if (Crowd_dist(p.x, p.y, ex, ey) < EDGE && z.getZ() < 1f && z.getCurrentBuilding() == null) {
                p.heading = (float) Math.atan2(dy, dx);
                if (steeringClient) {
                    // MP: the server takes it (it owns the population); stop steering it meanwhile.
                    out.add(new double[] {OUT_OFF, z.getOnlineID(), ox, oy, p.heading});
                    p.waitUntilHours = now + 0.25;
                    return;
                }
                if (PopulationBridge.walkOff(c, z, ox, oy)) {
                    pilots.remove(z);
                    walkedOff++;
                    return;
                }
            }
            tx = ex;
            ty = ey;
        }
        z.pathToLocation((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(z.getZ()));
        p.wpX = tx;
        p.wpY = ty;
        // Performance pass: each order is a path search on the game's pathfinding thread, shared with chasing zombies.
        p.nextOrderMs = nowMs + 8000 + rnd.nextInt(7000);
        orders++;
        writeModData(z, p);
    }

    /** In-game minutes of walking that cover about LOOK tiles. */
    private float minutes(int members, Pilot p, SimSettings s) {
        float v = movement.speedTilesPerGameMinute(members, s.speedSetting(), p.id, p.crawler);
        return v <= 0 ? 5f : LOOK / v;
    }

    private final Map<Long, Pilot> byId = new HashMap<>();

    private Pilot byId(long id) { return byId.get(id); }

    // ---- hordes among real zombies ----

    private void crowd(Core c, List<IsoZombie> list, SimSettings s, double now) {
        double dt = Double.isNaN(lastCrowdHours) ? 0 : Math.max(0, Math.min(1, now - lastCrowdHours));
        lastCrowdHours = now;
        List<Pilot> ps = new ArrayList<>(list.size());
        byId.clear();
        for (IsoZombie z : list) {
            if (z == null || z.isDead() || z.isReanimatedPlayer()) continue;
            Pilot p = pilot(z);
            p.id = idOf(z);
            p.x = z.getX();
            p.y = z.getY();
            ps.add(p);
            byId.put(p.id, p);
        }
        double ramp = Encounters.bumpRamp(now / 24, s.peakDay());
        Crowd.Rules rules = new Crowd.Rules(s.joinChancePerHour() * ramp, s.bumpFollowChance() * ramp,
            s.followChancePerHour() * ramp, s.crowdDriftPerHour() * ramp, s.hordeFormSize(), s.hordeMinSize());
        try {
            // One lock for all the "is part of it still held" questions (performance pass).
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Pilot p : ps) if (p.hordeId != 0) ids.add(p.hordeId);
            java.util.Set<Long> held = new java.util.HashSet<>();
            if (!ids.isEmpty()) {
                synchronized (c) {
                    Simulation sim = c.simulation();
                    if (sim != null) for (long id : ids) if (sim.hordes().get(id) != null) held.add(id);
                }
            }
            groups = Crowd.update(ps, dt, now, rules, held::contains, h -> newHorde(c, h), rnd::nextDouble);
            chaseRules(now);
            // Held zombies hear real hordes' moaning too.
            float[] packed = new float[groups.size() * 4];
            int i = 0;
            for (Crowd.Group g : groups.values()) {
                packed[i++] = g.id;
                packed[i++] = g.cx;
                packed[i++] = g.cy;
                packed[i++] = g.members.size();
            }
            Simulation sim = c.simulation();
            if (sim != null) sim.setRealHordes(packed);
        } catch (Throwable t) {
            Log.errorOnce("pilots:crowd:" + t.getClass().getName(), "real horde forming failed", t);
        }
    }

    private static boolean heldPart(Core c, long id) {
        synchronized (c) {
            Simulation sim = c.simulation();
            return sim != null && sim.hordes().get(id) != null;
        }
    }

    private static long newHorde(Core c, float heading) {
        synchronized (c) {
            Simulation sim = c.simulation();
            if (sim == null) return 0;
            long id = sim.hordes().reserveId();
            sim.handOver().rememberReal(id, heading);
            return id;
        }
    }

    // ---- the horde tag kept on the zombie (survives saves) ----

    static final String KEY_HORDE = "TrueHordes_h", KEY_HEADING = "TrueHordes_hd", KEY_LX = "TrueHordes_lx",
        KEY_LY = "TrueHordes_ly", KEY_LU = "TrueHordes_lu", KEY_DORMANT = "TrueHordes_d";

    private static void writeModData(IsoZombie z, Pilot p) {
        try {
            KahluaTable t = z.getModData();
            if (t == null) return;
            t.rawset(KEY_HORDE, p.hordeId == 0 ? null : (double) p.hordeId);
            t.rawset(KEY_HEADING, (double) p.heading);
            t.rawset(KEY_DORMANT, p.dormant ? Boolean.TRUE : null);
            boolean lure = !Float.isNaN(p.lureX);
            t.rawset(KEY_LX, lure ? (double) p.lureX : null);
            t.rawset(KEY_LY, lure ? (double) p.lureY : null);
            t.rawset(KEY_LU, lure ? p.lureUntilHours : null);
        } catch (Throwable t) {
            Log.errorOnce("pilots:moddata", "could not keep a zombie's horde tag", t);
        }
    }

    private static void readModData(IsoZombie z, Pilot p) {
        try {
            if (!z.hasModData()) return;
            KahluaTable t = z.getModData();
            if (t.rawget(KEY_HORDE) instanceof Double h) p.hordeId = (long) (double) h;
            if (t.rawget(KEY_HEADING) instanceof Double h) p.heading = (float) (double) h;
            if (Boolean.TRUE.equals(t.rawget(KEY_DORMANT))) p.dormant = true;
            if (t.rawget(KEY_LX) instanceof Double x && t.rawget(KEY_LY) instanceof Double y
                    && t.rawget(KEY_LU) instanceof Double u) {
                p.lureX = (float) (double) x;
                p.lureY = (float) (double) y;
                p.lureUntilHours = u;
            }
        } catch (Throwable t) {
            Log.errorOnce("pilots:moddata:read", "could not read a zombie's horde tag", t);
        }
    }

    void clear() {
        pilots.clear();
        groups = new HashMap<>();
        byId.clear();
        lastCrowdHours = Double.NaN;
        cursor = 0;
    }
}
