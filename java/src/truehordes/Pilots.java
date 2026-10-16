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
import truehordes.real.RealZone;
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
import zombie.network.GameServer;

/**
 * Steers real zombies by the simulation's rules (sub-project 6 spec goals 1-7): every real zombie has a {@link Pilot};
 * when vanilla is not busy with it (no target, not attacking, thumping, eating, lying or sitting), it is sent with
 * {@code pathToLocation} to a waypoint 12-20 tiles on, picked by its own {@link Movement} (travel, milling at home,
 * following a sound or a light, walking behind its horde's leader in a column). Real zombies far from every player and
 * out of every player's sight go back to the simulation, still walking. Main thread only.
 */
final class Pilots {
    final RealZone zone = new RealZone();
    private final WeakHashMap<IsoZombie, Pilot> pilots = new WeakHashMap<>();
    private Map<Long, Crowd.Group> groups = new HashMap<>();
    private final Random rnd = new Random();
    private Movement movement;
    private SimSettings movementSettings;
    private truehordes.sim.MapView movementMap;
    float radius = 50;
    int ordersPerTick = 25;
    private int cursor;
    private long lastZoneMs, lastCrowdMs;
    private double lastCrowdHours = Double.NaN;
    private int orders, sentBack;

    /** Tiles ahead a waypoint is put (about 12-20 with the walker's speed rounding). */
    static final float LOOK = 16f;
    /** A zombie this close to its lure mills there. */
    static final float MILL = 4f;
    static final double INVESTIGATE_HOURS = 6;
    /** Zombies looked at per tick (each comes round again every few ticks). */
    static final int PER_TICK = 200;
    static final long BUDGET_NANOS = 2_000_000L;
    /** How long a far real zombie must be out of every player's sight before it goes back to the simulation. */
    static final long UNSEEN_MS = 5000;

    void readSettings() {
        radius = (float) Math.max(0, GameEnvImpl.sandboxDouble("TrueHordes.RealRadius", 50));
        ordersPerTick = (int) Math.max(1, GameEnvImpl.sandboxDouble("TrueHordes.PilotOrdersPerTick", 25));
    }

    int count() { return pilots.size(); }

    String stats() {
        return "pilots " + pilots.size() + ", real hordes " + groups.size() + ", orders " + orders + ", sent back "
            + sentBack + ", real radius " + (int) radius;
    }

    static double nowHours() { return GameTime.getInstance().getWorldAgeHours(); }

    /** Positions of every player this game runs zombies for: all connected players on a server, local ones otherwise. */
    static List<IsoPlayer> players() {
        List<IsoPlayer> out = new ArrayList<>();
        if (GameServer.server) {
            for (IsoPlayer p : GameServer.getPlayers()) if (p != null && !p.isDead()) out.add(p);
        } else {
            for (int i = 0; i < IsoPlayer.numPlayers; i++) {
                IsoPlayer p = IsoPlayer.players[i];
                if (p != null && !p.isDead()) out.add(p);
            }
        }
        return out;
    }

    private void publishZone() {
        List<IsoPlayer> ps = players();
        float[] xy = new float[ps.size() * 2];
        for (int i = 0; i < ps.size(); i++) {
            xy[i * 2] = ps.get(i).getX();
            xy[i * 2 + 1] = ps.get(i).getY();
        }
        zone.set(xy, radius);
    }

    /** A local player can see the tile (x, y, z) right now (always false on a dedicated server: sub-project 7). */
    static boolean seen(float x, float y, float z) {
        if (GameServer.server) return false;
        IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare((double) x, (double) y, (double) z);
        if (sq == null) return false;
        for (int i = 0; i < IsoPlayer.numPlayers; i++) if (IsoPlayer.players[i] != null && sq.isCouldSee(i)) return true;
        return false;
    }

    void tick(Core c, long nowMs) {
        if (!c.ownsPopulation()) return;
        if (nowMs - lastZoneMs >= 250) {
            lastZoneMs = nowMs;
            publishZone();
        }
        SimSettings s = c.simSettings();
        truehordes.sim.MapView map = c.mapView();
        if (s == null || map == null) return;
        if (movement == null || movementSettings != s || movementMap != map) {
            movement = new Movement(map, s);
            movementSettings = s;
            movementMap = map;
        }
        double now = nowHours();
        List<IsoZombie> list = IsoWorld.instance.currentCell.getZombieList();
        if (nowMs - lastCrowdMs >= 1000) {
            lastCrowdMs = nowMs;
            crowd(c, list, s, now);
        }
        int n = list.size();
        if (n == 0) return;
        long t0 = System.nanoTime();
        int budget = ordersPerTick;
        List<IsoZombie> back = new ArrayList<>();
        for (int k = 0; k < Math.min(n, PER_TICK); k++) {
            if (cursor >= n) cursor = 0;
            IsoZombie z = list.get(cursor++);
            if (z == null || z.isDead() || z.isReanimatedPlayer()) continue;
            Pilot p = pilot(z);
            p.x = z.getX();
            p.y = z.getY();
            if (busy(z)) {
                p.wpX = Float.NaN;
                continue;
            }
            if (zone.on() && !zone.near(p.x, p.y, RealZone.DEMAT_MARGIN) && !seen(p.x, p.y, z.getZ())) {
                // Out of sight a few seconds (a player glancing round does not make zombies blink in and out).
                if (p.unseenSinceMs == 0) p.unseenSinceMs = nowMs;
                if (nowMs - p.unseenSinceMs >= UNSEEN_MS) {
                    back.add(z);
                    continue;
                }
            } else {
                p.unseenSinceMs = 0;
            }
            if (budget > 0 && due(p, z, nowMs, now, s)) {
                order(p, z, nowMs, now, s);
                budget--;
            }
            if ((k & 15) == 15 && System.nanoTime() - t0 > BUDGET_NANOS) break;
        }
        for (IsoZombie z : back) {
            Pilot p = pilots.get(z);
            if (p != null && PopulationBridge.sendBack(c, z, p)) {
                pilots.remove(z);
                sentBack++;
            }
        }
    }

    Pilot pilot(IsoZombie z) {
        Pilot p = pilots.get(z);
        if (p == null) {
            p = new Pilot(z.persistentId != 0 ? z.persistentId : System.identityHashCode(z));
            p.heading = z.getForwardDirection().getDirection();
            p.crawler = z.isCrawling();
            readModData(z, p);
            pilots.put(z, p);
        }
        return p;
    }

    /** A held record just made real as {@code z}: its pilot carries the record's state. */
    void adopt(IsoZombie z, ZombieRecord r) {
        Pilot p = new Pilot(z.persistentId != 0 ? z.persistentId : System.identityHashCode(z));
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
            r.targetX = (int) Math.floor(p.lureX);
            r.targetY = (int) Math.floor(p.lureY);
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
        if (Crowd_dist(p.x, p.y, p.wpX, p.wpY) < 2.5f) return true;
        // A follower whose slot moved on (its leader took a new waypoint).
        float[] want = followTarget(p);
        if (want != null && Crowd_dist(want[0], want[1], p.wpX, p.wpY) > 3f) return true;
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
    private float[] followTarget(Pilot p) {
        if (p.hordeId != 0) {
            Crowd.Group g = groups.get(p.hordeId);
            if (g == null || g.members.isEmpty() || g.leader() == p) return null;
            Pilot l = g.leader();
            float bx = Float.isNaN(l.wpX) ? l.x : l.wpX, by = Float.isNaN(l.wpX) ? l.y : l.wpY;
            return Pilot.slot(p.slot, g.members.size(), bx, by, l.heading);
        }
        return null;
    }

    private void order(Pilot p, IsoZombie z, long nowMs, double now, SimSettings s) {
        float tx, ty;
        if (!Float.isNaN(p.lureX) && p.lureUntilHours <= now) {
            // Done investigating: it settles where the sound took it.
            p.homeX = p.x;
            p.homeY = p.y;
            p.lureX = Float.NaN;
        }
        float[] slot = followTarget(p);
        Crowd.Group g = p.hordeId == 0 ? null : groups.get(p.hordeId);
        int members = g == null ? 1 : g.members.size();
        boolean leads = g == null || g.leader() == p;
        if (slot != null) {
            tx = slot[0];
            ty = slot[1];
            p.heading = g.leader().heading;
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
        z.pathToLocation((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(z.getZ()));
        p.wpX = tx;
        p.wpY = ty;
        p.nextOrderMs = nowMs + 4000 + rnd.nextInt(4000);
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
            p.x = z.getX();
            p.y = z.getY();
            ps.add(p);
            byId.put(p.id, p);
        }
        double ramp = Encounters.bumpRamp(now / 24, s.peakDay());
        Crowd.Rules rules = new Crowd.Rules(s.joinChancePerHour() * ramp, s.bumpFollowChance() * ramp,
            s.followChancePerHour() * ramp, s.crowdDriftPerHour() * ramp, s.hordeFormSize(), s.hordeMinSize());
        try {
            groups = Crowd.update(ps, dt, now, rules, id -> heldPart(c, id), h -> newHorde(c, h), rnd::nextDouble);
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
