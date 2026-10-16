package truehordes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import truehordes.sim.Simulation;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoLightSwitch;

/**
 * Lights as a lure (sub-project 5b spec goal 6), main thread. Only at night and only once the grid is off (so the
 * light is on a generator, or a battery): each lone zombie and each horde rolls once per light whether to go to it.
 * Building lights and placed lights (floodlights, lamps) 25%, streetlights 1% (one that takes a zombie keeps it under
 * the light until morning), a flashlight beam on a real zombie 10%. Real zombies near the player are rolled here;
 * held zombies get the lights the mod has seen in loaded areas (remembered, and saved, while unloaded).
 */
final class LightLure {
    static final int BUILDING = 0, STREET = 1;
    static final long SCAN_MS = 2000;
    /** Night (Claude's call): 21:00 to 06:00. */
    static final int DUSK = 21, DAWN = 6;
    /** A building or placed light keeps a lured zombie this long (in-game hours; Claude's call). */
    static final double BUILDING_HOLD_HOURS = 2;

    record Light(long key, float x, float y, int z, int kind) {}

    boolean on = true;
    double flashChance = 0.10, buildingChance = 0.25, streetChance = 0.01;
    float reach = 60, streetReach = 40;

    /** Every lit light seen in a loaded area, by spot; kept while unloaded. */
    private final Map<Long, Light> remembered = new HashMap<>();
    private final WeakHashMap<IsoZombie, Set<Long>> rolled = new WeakHashMap<>();
    /** Real zombies a streetlight took: kept under it until morning. */
    private final WeakHashMap<IsoZombie, Light> captured = new WeakHashMap<>();
    private long lastScanMs;
    private boolean wasNight;

    void readSettings() {
        on = GameEnvImpl.sandboxBool("TrueHordes.LightLure", true);
        flashChance = GameEnvImpl.sandboxDouble("TrueHordes.FlashlightLureChance", flashChance);
        buildingChance = GameEnvImpl.sandboxDouble("TrueHordes.BuildingLightLureChance", buildingChance);
        streetChance = GameEnvImpl.sandboxDouble("TrueHordes.StreetlightLureChance", streetChance);
        reach = (float) GameEnvImpl.sandboxDouble("TrueHordes.LightReach", reach);
        streetReach = (float) GameEnvImpl.sandboxDouble("TrueHordes.StreetlightReach", streetReach);
    }

    static long key(int x, int y, int z) { return ((long) x << 24) ^ ((long) y << 4) ^ (z & 0xF); }

    void tick(truehordes.core.Core core, long nowMs) {
        if (!on || nowMs - lastScanMs < SCAN_MS) return;
        lastScanMs = nowMs;
        IsoWorld w = IsoWorld.instance;
        if (w == null || w.currentCell == null) return;
        scan(w.currentCell);
        int hour = GameTime.getInstance().getHour();
        boolean night = (hour >= DUSK || hour < DAWN) && !w.isHydroPowerOn();
        Simulation sim = core.soundSink();
        if (!night) {
            if (wasNight) {
                rolled.clear();
                captured.clear();
            }
            wasNight = false;
            if (sim != null) sim.setLures(List.of());
            return;
        }
        wasNight = true;
        double now = GameTime.getInstance().getWorldAgeHours();
        double morning = now + ((DAWN - hour + 24) % 24) - GameTime.getInstance().getMinutes() / 60.0;
        if (sim != null) {
            List<Simulation.Lure> ls = new ArrayList<>(remembered.size());
            for (Light l : remembered.values()) {
                boolean st = l.kind() == STREET;
                ls.add(new Simulation.Lure(l.key(), l.x(), l.y(), st ? streetReach : reach, st ? streetChance : buildingChance,
                    st ? morning : now + BUILDING_HOLD_HOURS));
            }
            sim.setLures(ls);
        }
        realZombies(w.currentCell);
    }

    /** Lit lights in the loaded area: remembered if lit; forgotten if their spot is loaded and they are off. */
    private void scan(IsoCell cell) {
        Map<Long, Light> lit = new HashMap<>();
        for (IsoLightSource s : cell.getLamppostPositions()) {
            if (s == null || !s.active || s.switches.isEmpty()) continue;
            IsoLightSwitch sw = s.switches.get(0);
            lit.put(key(s.x, s.y, s.z), new Light(key(s.x, s.y, s.z), s.x + 0.5f, s.y + 0.5f, s.z,
                sw.streetLight ? STREET : BUILDING));
        }
        Set<IsoRoom> rooms = new HashSet<>();
        for (IsoChunk c : cell.getChunkMap(0).getChunks()) {
            if (c == null) continue;
            for (IsoRoomLight rl : c.roomLights) {
                if (rl == null || !rl.active || rl.room == null || !rooms.add(rl.room)) continue;
                int x = rl.x + rl.width / 2, y = rl.y + rl.height / 2;
                lit.put(key(x, y, rl.z), new Light(key(x, y, rl.z), x + 0.5f, y + 0.5f, rl.z, BUILDING));
            }
        }
        remembered.values().removeIf(l -> !lit.containsKey(l.key()) && cell.getGridSquare(l.x(), l.y(), l.z()) != null);
        remembered.putAll(lit);
    }

    /** Real zombies in reach of a light roll once for it; a flashlight beam rolls the zombies it touches. */
    private void realZombies(IsoCell cell) {
        List<Light> ls = new ArrayList<>(remembered.values());
        float r2 = reach * reach, s2 = streetReach * streetReach;
        for (IsoZombie z : cell.getZombieList()) {
            if (z == null || z.isDead() || z.getTarget() != null) continue;
            Light held = captured.get(z);
            if (held != null) {
                if (Math.hypot(z.getX() - held.x(), z.getY() - held.y()) > 6) z.pathToSound((int) held.x(),
                    (int) held.y(), held.z());
                continue;
            }
            for (Light l : ls) {
                float dx = z.getX() - l.x(), dy = z.getY() - l.y(), d2 = dx * dx + dy * dy;
                boolean st = l.kind() == STREET;
                if (d2 > (st ? s2 : r2) || !roll(z, l.key(), st ? streetChance : buildingChance)) continue;
                z.pathToSound((int) l.x(), (int) l.y(), l.z());
                if (st) captured.put(z, l);
                break;
            }
        }
        flashlights(cell);
    }

    private void flashlights(IsoCell cell) {
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p == null || !p.isAlive()) continue;
            InventoryItem item = p.getActiveLightItem();
            if (item == null || !item.isTorchCone()) continue;
            float dist = item.getLightDistance(), dot = item.getTorchDot();
            zombie.iso.Vector2 look = p.getLookVector(new zombie.iso.Vector2());
            long id = -1 - i;
            for (IsoZombie z : cell.getZombieList()) {
                if (z == null || z.isDead() || z.getTarget() != null) continue;
                float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 1 || d > dist || (dx * look.x + dy * look.y) / d < dot) continue;
                if (roll(z, id, flashChance)) z.pathToSound((int) p.getX(), (int) p.getY(), (int) p.getZ());
            }
        }
    }

    /** One remembered roll per zombie and light. */
    private boolean roll(IsoZombie z, long light, double chance) {
        Set<Long> seen = rolled.computeIfAbsent(z, k -> new HashSet<>());
        if (!seen.add(light)) return false;
        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    String saveString() {
        StringBuilder sb = new StringBuilder();
        for (Light l : remembered.values()) {
            sb.append((int) l.x()).append(',').append((int) l.y()).append(',').append(l.z()).append(',').append(l.kind())
                .append(';');
        }
        return sb.toString();
    }

    void loadString(String s) {
        for (String e : s.split(";")) {
            String[] f = e.split(",");
            if (f.length < 4) continue;
            int x = Integer.parseInt(f[0]), y = Integer.parseInt(f[1]), z = Integer.parseInt(f[2]);
            remembered.put(key(x, y, z), new Light(key(x, y, z), x + 0.5f, y + 0.5f, z, Integer.parseInt(f[3])));
        }
    }

    void clear() {
        remembered.clear();
        rolled.clear();
        captured.clear();
        wasNight = false;
    }

    /** For the viewer: lit lights the mod knows (x, y, kind), or none by day. */
    List<Light> known() { return new ArrayList<>(remembered.values()); }
}
