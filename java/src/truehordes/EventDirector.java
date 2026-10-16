package truehordes;

import fmod.fmod.FMODSoundEmitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import truehordes.events.EventKind;
import truehordes.events.Gunfire;
import truehordes.events.Placement;
import truehordes.events.Schedule;
import truehordes.sim.MapView;
import truehordes.sim.SoundEvent;
import truehordes.util.Log;
import zombie.GameTime;
import zombie.WorldSoundManager;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.DrainableComboItem;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * Meta events (sub-project 5b spec goals 1-3), main thread only. Replaces vanilla's gunshot schedule and random
 * dog/scream ambients with the mod's schedule: far events 300-1000 tiles away, near ones 50-100, each a real world
 * sound (so 5a's capture, falloff and rolls apply to every zombie, real or held) plus positional audio for the player.
 * Owls and wolves stay ambience. Also fires A-Life's silent off-screen fights as gunfire and keeps a near-event car
 * alarm sounding until its battery is flat. Its schedule, alarms and the lights it remembers are saved beside the
 * mod's save.
 */
final class EventDirector {
    /** A world sound due at a real-time moment (bursts and fights). */
    private record Shot(long atMs, float x, float y, int radius, int kind) {}

    /** A car alarm the mod set off: kept sounding (vanilla stops alarms after a while) until the battery is flat. */
    static final class Alarm {
        int sqlId;
        float x, y, charge;
        double lastHours;
        transient BaseVehicle vehicle;
    }

    private final List<Shot> shots = new ArrayList<>();
    private final List<FMODSoundEmitter> emitters = new ArrayList<>();
    final List<Alarm> alarms = new ArrayList<>();
    private double farNext = Double.NaN, nearGunNext = Double.NaN, carNext = Double.NaN;
    private boolean nearGunFires = true, carFires = true;
    private boolean loaded;
    private long lastAlarmMs;
    private final DoubleSupplier u = () -> ThreadLocalRandom.current().nextDouble();
    final LightLure lights = new LightLure();

    /** Settings read when the world starts. */
    boolean metaOn = true, nearOn = true;
    double farFreq = 1, nearGunFreq = 1, carFreq = 1, batteryHours = 6;

    void readSettings() {
        metaOn = GameEnvImpl.sandboxBool("TrueHordes.MetaEvents", true);
        nearOn = GameEnvImpl.sandboxBool("TrueHordes.NearEvents", true);
        farFreq = Math.max(0.01, GameEnvImpl.sandboxDouble("TrueHordes.FarEventFrequency", 1));
        nearGunFreq = Math.max(0.01, GameEnvImpl.sandboxDouble("TrueHordes.NearGunshotFrequency", 1));
        carFreq = Math.max(0.01, GameEnvImpl.sandboxDouble("TrueHordes.CarAlarmFrequency", 1));
        batteryHours = Math.max(0.25, GameEnvImpl.sandboxDouble("TrueHordes.CarAlarmBatteryHours", 6));
        lights.readSettings();
    }

    static double today() { return GameTime.getInstance().getWorldAgeDaysSinceBegin(); }

    /** Louder Gunshots' multiplier when that mod is active, else NaN. */
    static double louderMultiplier() {
        return GameEnvImpl.sandboxDouble("LouderGunshots.Multiplier", Double.NaN);
    }

    /** Every frame while the mod runs the population. */
    void tick(truehordes.core.Core core) {
        if (!loaded) {
            loaded = true;
            readSettings();
            load(core.stateFile());
        }
        long now = System.currentTimeMillis();
        fireDueShots(now);
        tickEmitters();
        double day = today();
        IsoPlayer p = anyPlayer();
        MapView map = core.mapView();
        if (p != null && map != null) {
            if (metaOn) farEvents(day, p, map);
            if (nearOn) nearEvents(day, p, map);
        }
        if (now - lastAlarmMs >= 1000) {
            lastAlarmMs = now;
            keepAlarms(core);
        }
        lights.tick(core, now);
    }

    // ---- schedules ----

    private void farEvents(double day, IsoPlayer p, MapView map) {
        if (Double.isNaN(farNext)) farNext = Schedule.next(Schedule.Rule.FAR, day, farFreq, u).atDays();
        if (day < farNext) return;
        farNext = Schedule.next(Schedule.Rule.FAR, day, farFreq, u).atDays();
        EventKind k = EventKind.pick(EventKind.FAR, u.getAsDouble());
        float[] spot = Placement.pick(map, k.where(), p.getX(), p.getY(), players(), Placement.FAR_MIN, Placement.FAR_MAX, u);
        if (spot == null) return;
        fire(k, spot[0], spot[1], k.viewKind());
        Log.debug("core", "far meta event: " + k.name() + " at " + (int) spot[0] + "," + (int) spot[1]);
    }

    private void nearEvents(double day, IsoPlayer p, MapView map) {
        if (Double.isNaN(nearGunNext)) planNearGun(day);
        if (day >= nearGunNext) {
            boolean fires = nearGunFires;
            planNearGun(day);
            if (fires) {
                List<EventKind> guns = new ArrayList<>();
                for (EventKind k : EventKind.FAR) if (k.gun() && k.where() == Placement.Where.TOWN) guns.add(k);
                EventKind k = EventKind.pick(guns, u.getAsDouble());
                float[] spot = Placement.pick(map, Placement.Where.ANYWHERE, p.getX(), p.getY(), players(),
                    Placement.NEAR_MIN, Placement.NEAR_MAX, u);
                if (spot != null) {
                    fire(k, spot[0], spot[1], SoundEvent.META);
                    Log.debug("core", "near gunshot at " + (int) spot[0] + "," + (int) spot[1]);
                }
            }
        }
        if (Double.isNaN(carNext)) planCar(day);
        if (day >= carNext) {
            boolean fires = carFires;
            planCar(day);
            if (fires) carAlarm(p);
        }
    }

    private void planNearGun(double day) {
        Schedule.Next n = Schedule.next(Schedule.Rule.NEAR_GUN, day, nearGunFreq, u);
        nearGunNext = n.atDays();
        nearGunFires = n.fires();
    }

    private void planCar(double day) {
        Schedule.Next n = Schedule.next(Schedule.Rule.CAR_ALARM, day, carFreq, u);
        carNext = n.atDays();
        carFires = n.fires();
    }

    // ---- firing ----

    /** One event: its shots as world sounds (a burst a few tenths apart) and its audio for the player. */
    void fire(EventKind k, float x, float y, int viewKind) {
        int radius = k.gun() ? Gunfire.radius(louderMultiplier()) : k.radius();
        int n = k.shots(u.getAsDouble());
        double[] t = Gunfire.times(n, 0, u);
        long now = System.currentTimeMillis();
        for (double s : t) shots.add(new Shot(now + (long) (s * 1000), x, y, radius, viewKind));
        play(k.sound(u.getAsDouble()), x, y, radius);
    }

    /** An A-Life off-screen fight at (x, y): a scattered burst of rifle shots over about a minute. */
    void firefight(float x, float y, int casualties) {
        int radius = Gunfire.rifleRadius(louderMultiplier());
        int n = Gunfire.fightShots(casualties);
        double[] t = Gunfire.times(n, Gunfire.FIGHT_SECONDS, u);
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) shots.add(new Shot(now + (long) (t[i] * 1000), x, y, radius, SoundEvent.ALIFE));
        // The player hears a rifle burst every few shots when the fight is in reach of the meta sounds (1,000 tiles).
        for (int i = 0; i < n; i += 6) {
            long at = now + (long) (t[i] * 1000);
            shots.add(new Shot(at, x, y, -radius, SoundEvent.ALIFE));
        }
        Log.debug("core", "A-Life fight: " + n + " shots at " + (int) x + "," + (int) y);
    }

    private void fireDueShots(long now) {
        if (shots.isEmpty()) return;
        Iterator<Shot> it = shots.iterator();
        while (it.hasNext()) {
            Shot s = it.next();
            if (s.atMs() > now) continue;
            it.remove();
            if (s.radius() < 0) {
                play(u.getAsDouble() < 0.5 ? "MetaAssaultRifle1" : "MetaPistol1", s.x(), s.y(), -s.radius());
                continue;
            }
            int before = SoundCapture.during;
            SoundCapture.during = s.kind();
            try {
                WorldSoundManager.instance.addSound(null, (int) Math.floor(s.x()), (int) Math.floor(s.y()), 0, s.radius(),
                    s.radius());
            } finally {
                SoundCapture.during = before;
            }
        }
    }

    // ---- audio ----

    /** Positional audio at the event's real spot, at the same falloff zombies hear (within the sounds' 1,000 tiles). */
    void play(String sound, float x, float y, float radius) {
        IsoPlayer p = anyPlayer();
        if (p == null || p.hasTrait(zombie.scripting.objects.CharacterTrait.DEAF)) return;
        float d = (float) Math.hypot(x - p.getX(), y - p.getY());
        if (d > 1000) return;
        playAt(sound, x, y, Gunfire.volume(radius, d));
    }

    void playAt(String sound, float x, float y, float volume) {
        FMODSoundEmitter e = new FMODSoundEmitter();
        e.x = x;
        e.y = y;
        e.z = 0;
        long id = e.playAmbientSound(sound);
        if (id == 0) return;
        e.setVolume(id, volume);
        e.tick();
        emitters.add(e);
    }

    private void tickEmitters() {
        for (int i = emitters.size() - 1; i >= 0; i--) {
            FMODSoundEmitter e = emitters.get(i);
            e.tick();
            if (e.isEmpty()) emitters.remove(i);
        }
    }

    /**
     * Vanilla's random ambient call: dogs and screams come from the mod's schedule now; owls and wolves at night stay
     * as vanilla made them (audio only, 1,000 tiles away, not in cities before day 29).
     */
    void wildlife() {
        IsoPlayer p = anyPlayer();
        if (p == null || p.getSquare() == null) return;
        int hour = GameTime.getInstance().getHour();
        if (hour > 7 && hour < 21) return;
        boolean cityEarly = !p.getSquare().isRuralExtraFussy() && GameTime.instance.getWorldAgeDaysSinceBegin() < 29;
        if (cityEarly) return;
        int pick = ThreadLocalRandom.current().nextInt(5);
        if (pick != 2 && pick != 3) return;
        double a = u.getAsDouble() * 2 * Math.PI;
        playAt(pick == 2 ? "MetaOwl" : "MetaWolfHowl", p.getX() + (float) Math.cos(a) * 1000,
            p.getY() + (float) Math.sin(a) * 1000, 0.2f + 0.3f * (float) u.getAsDouble());
    }

    // ---- car alarms ----

    /** A parked car 50-100 tiles away with an alarm and a charged battery: its alarm goes off. None: skipped. */
    private void carAlarm(IsoPlayer p) {
        if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
        List<BaseVehicle> cars = new ArrayList<>();
        for (BaseVehicle v : IsoWorld.instance.currentCell.getVehicles()) {
            double d = Math.hypot(v.getX() - p.getX(), v.getY() - p.getY());
            if (d < Placement.NEAR_MIN || d > Placement.NEAR_MAX || v.getDriver() != null) continue;
            if (v.getScript() == null || !v.getScript().getSounds().alarmEnable || v.getBatteryCharge() <= 0) continue;
            if (v.isAlarmActive() || owned(v.getSqlId()) != null) continue;
            cars.add(v);
        }
        if (cars.isEmpty()) {
            Log.debug("core", "car alarm event skipped: no car with an alarm 50-100 tiles away");
            return;
        }
        BaseVehicle v = cars.get(ThreadLocalRandom.current().nextInt(cars.size()));
        v.getVehicleAlarmObject().trigger();
        Alarm a = new Alarm();
        a.sqlId = v.getSqlId();
        a.vehicle = v;
        a.x = v.getX();
        a.y = v.getY();
        a.charge = v.getBatteryCharge();
        a.lastHours = GameTime.getInstance().getWorldAgeHours();
        alarms.add(a);
        Log.debug("core", "car alarm set off at " + (int) a.x + "," + (int) a.y);
    }

    private Alarm owned(int sqlId) {
        for (Alarm a : alarms) if (a.sqlId == sqlId) return a;
        return null;
    }

    /**
     * Once a second: each alarm the mod set off keeps sounding (its start time is held at now, so vanilla never times
     * it out) and drains its battery, also while the car is unloaded (the game keeps a sounding car updating off
     * screen). It stops when the battery is flat or someone sits in the driver's seat. A car the game no longer holds
     * (after a reload) keeps a repeating alarm sound in the off-screen simulation and gets its battery drained by the
     * elapsed time when it is loaded again.
     */
    private void keepAlarms(truehordes.core.Core core) {
        if (alarms.isEmpty()) return;
        double now = GameTime.getInstance().getWorldAgeHours();
        Iterator<Alarm> it = alarms.iterator();
        while (it.hasNext()) {
            Alarm a = it.next();
            double dt = Math.max(0, now - a.lastHours);
            a.lastHours = now;
            a.charge = (float) Math.max(0, a.charge - dt / batteryHours);
            if (a.vehicle == null) a.vehicle = findLoaded(a.sqlId, a.x, a.y);
            BaseVehicle v = a.vehicle;
            if (v == null) {
                if (a.charge <= 0) {
                    it.remove();
                    continue;
                }
                truehordes.sim.Simulation sim = core.soundSink();
                if (sim != null) sim.offerSound(a.x, a.y, 150, true, 0xA1A2L ^ a.sqlId, SoundEvent.CAR_ALARM);
                continue;
            }
            a.x = v.getX();
            a.y = v.getY();
            VehiclePart battery = v.getBattery();
            if (v.getDriver() != null || battery == null || !(battery.getInventoryItem() instanceof DrainableComboItem item)) {
                stopAlarm(v);
                it.remove();
                continue;
            }
            float charge = Math.min(item.getCurrentUsesFloat(), a.charge);
            item.setCurrentUsesFloat(charge);
            a.charge = charge;
            if (charge <= 0) {
                stopAlarm(v);
                it.remove();
                continue;
            }
            v.getVehicleAlarmObject().setStartTime(now);
        }
    }

    private static void stopAlarm(BaseVehicle v) {
        if (v.getVehicleAlarmObject().isSoundOn()) v.getVehicleAlarmObject().onAlarmStop();
        v.getVehicleAlarmObject().setStartTime(0);
    }

    private static BaseVehicle findLoaded(int sqlId, float x, float y) {
        if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return null;
        for (BaseVehicle v : IsoWorld.instance.currentCell.getVehicles()) {
            if (v.getSqlId() == sqlId && Math.hypot(v.getX() - x, v.getY() - y) < 50) return v;
        }
        return null;
    }

    // ---- players ----

    static IsoPlayer anyPlayer() {
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p != null && p.isAlive()) return p;
        }
        return null;
    }

    static float[] players() {
        List<Float> xy = new ArrayList<>();
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p != null && p.isAlive()) {
                xy.add(p.getX());
                xy.add(p.getY());
            }
        }
        float[] out = new float[xy.size()];
        for (int i = 0; i < out.length; i++) out[i] = xy.get(i);
        return out;
    }

    // ---- save ----

    static Path file(Path stateFile) { return stateFile.resolveSibling("events.properties"); }

    void save(Path stateFile) {
        if (!loaded || stateFile == null) return;
        try {
            Properties pr = new Properties();
            pr.setProperty("farNext", Double.toString(farNext));
            pr.setProperty("nearGunNext", Double.toString(nearGunNext));
            pr.setProperty("nearGunFires", Boolean.toString(nearGunFires));
            pr.setProperty("carNext", Double.toString(carNext));
            pr.setProperty("carFires", Boolean.toString(carFires));
            StringBuilder sb = new StringBuilder();
            for (Alarm a : alarms) {
                sb.append(a.sqlId).append(',').append(a.x).append(',').append(a.y).append(',').append(a.charge).append(',')
                    .append(a.lastHours).append(';');
            }
            pr.setProperty("alarms", sb.toString());
            pr.setProperty("lights", lights.saveString());
            truehordes.core.Core c = Hooks.core();
            if (c != null) pr.setProperty("edgePool", Integer.toString(c.edgePool()));
            java.io.StringWriter w = new java.io.StringWriter();
            pr.store(w, "True Hordes events (sub-project 5b)");
            Files.writeString(file(stateFile), w.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.errorOnce("events:save", "could not save the meta-event state", t);
        }
    }

    private void load(Path stateFile) {
        if (stateFile == null) return;
        try {
            Path f = file(stateFile);
            if (!Files.exists(f)) return;
            Properties pr = new Properties();
            pr.load(new java.io.StringReader(Files.readString(f, StandardCharsets.UTF_8)));
            farNext = Double.parseDouble(pr.getProperty("farNext", "NaN"));
            nearGunNext = Double.parseDouble(pr.getProperty("nearGunNext", "NaN"));
            nearGunFires = Boolean.parseBoolean(pr.getProperty("nearGunFires", "true"));
            carNext = Double.parseDouble(pr.getProperty("carNext", "NaN"));
            carFires = Boolean.parseBoolean(pr.getProperty("carFires", "true"));
            for (String s : pr.getProperty("alarms", "").split(";")) {
                String[] f2 = s.split(",");
                if (f2.length < 5) continue;
                Alarm a = new Alarm();
                a.sqlId = Integer.parseInt(f2[0]);
                a.x = Float.parseFloat(f2[1]);
                a.y = Float.parseFloat(f2[2]);
                a.charge = Float.parseFloat(f2[3]);
                a.lastHours = Double.parseDouble(f2[4]);
                alarms.add(a);
            }
            lights.loadString(pr.getProperty("lights", ""));
            truehordes.core.Core c = Hooks.core();
            if (c != null) c.setEdgePool(Integer.parseInt(pr.getProperty("edgePool", "0")));
        } catch (Throwable t) {
            Log.errorOnce("events:load", "could not read the meta-event state; starting fresh", t);
        }
    }

    /** World unloading: forget everything (the next world loads its own). */
    void stop() {
        shots.clear();
        for (FMODSoundEmitter e : emitters) e.stopAll();
        emitters.clear();
        alarms.clear();
        farNext = nearGunNext = carNext = Double.NaN;
        nearGunFires = carFires = true;
        loaded = false;
        lights.clear();
    }
}
