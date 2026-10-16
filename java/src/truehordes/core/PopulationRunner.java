package truehordes.core;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import truehordes.population.CellBook;
import truehordes.population.EdgeChunks;
import truehordes.population.Formula;
import truehordes.population.MapSource;
import truehordes.population.PopulationSettings;
import truehordes.population.Respawner;
import truehordes.population.Seeder;
import truehordes.population.Takeover;
import truehordes.save.LoadResult;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;
import truehordes.util.Log;
import truehordes.world.World;

/**
 * The population side of the core (sub-project 3): seeds a new game on a background thread once the world map is
 * ready, then runs growth and respawn every in-game hour; an existing save is taken over gradually (no seeding).
 * Called only from the core's synchronized methods.
 */
final class PopulationRunner {
    enum Mode { NONE, SEEDING, SEEDED, GRADUAL }

    private record SeedResult(Store store, CellBook book, int outdoor, int rooms, int skipped, long millis) {}

    private final GameEnv env;
    private final Store store;
    private final World world;
    private final Takeover takeover;
    private final Respawner respawner = new Respawner();
    private final Random rnd = new Random();
    private Mode mode = Mode.NONE;
    private CellBook book = new CellBook();
    private PopulationSettings settings;
    private MapSource map;
    private long[] edge;
    private double lastHourly = Double.NaN;
    private volatile Object seedToken;
    private volatile SeedResult seedResult;

    PopulationRunner(GameEnv env, Store store, World world, Takeover takeover) {
        this.env = env;
        this.store = store;
        this.world = world;
        this.takeover = takeover;
    }

    Mode mode() { return mode; }

    boolean seededWorld() { return mode == Mode.SEEDED || mode == Mode.SEEDING; }

    boolean buildingZombiesSeeded() { return settings != null && settings.buildingZombiesSeeded; }

    void start(LoadResult r) {
        settings = env.populationSettings();
        if (r.seeded) {
            mode = Mode.SEEDED;
            try {
                book = CellBook.load(r.population);
            } catch (IllegalArgumentException e) {
                Log.warn("population bookkeeping unreadable (" + e.getMessage() + "); it is rebuilt");
                book = new CellBook();
            }
        } else if (!r.records.isEmpty() || !env.newWorld()) {
            mode = Mode.GRADUAL;
            Log.info("existing save: taking over the population gradually (no seeding)");
        } else {
            mode = Mode.SEEDING;
            Log.info("new game: the population will be seeded once the world map is ready");
        }
        env.spawnLoadedChunks();
    }

    void tick(double hours) {
        if (mode == Mode.SEEDING) {
            if (seedToken == null && world.ready()) launchSeeding(hours);
            SeedResult res = seedResult;
            if (res != null) merge(res, hours);
            return;
        }
        if (mode == Mode.SEEDED && world.ready() && (Double.isNaN(lastHourly) || hours - lastHourly >= 1.0)) hourly(hours);
    }

    private void launchSeeding(double hours) {
        settings = env.populationSettings();
        map = env.mapSource();
        PopulationSettings s = settings;
        MapSource m = map;
        Object token = new Object();
        seedToken = token;
        Thread t = new Thread(() -> {
            try {
                long t0 = System.nanoTime();
                Store seeded = new Store();
                Seeder seeder = new Seeder();
                Random r = new Random();
                int outdoor = seeder.seedOutdoor(m, world::walkable, s, hours / 24.0, r, seeded);
                int skipped = seeder.lastSkipped();
                int rooms = s.buildingZombiesSeeded ? seeder.seedRooms(m, s, r, seeded) : 0;
                CellBook b = new CellBook();
                b.init(m, s, r::nextInt, hours);
                if (seedToken == token) {
                    seedResult = new SeedResult(seeded, b, outdoor, rooms, skipped, (System.nanoTime() - t0) / 1_000_000L);
                }
            } catch (Throwable e) {
                Log.error("seeding the population failed", e);
            }
        }, "TrueHordes-Seed");
        t.setDaemon(true);
        t.start();
    }

    private void merge(SeedResult res, double hours) {
        seedResult = null;
        for (ZombieRecord r : res.store.all()) {
            store.add(r.x(), r.y(), r.z, r.dir, r.outfitId, r.stateFlags, r.persistentId, r.targetX, r.targetY,
                r.modData, r.building);
        }
        book = res.book;
        mode = Mode.SEEDED;
        lastHourly = hours;
        Log.info(String.format(Locale.ROOT, "seeded the population: %d outdoors, %d in buildings (%d skipped: no walkable"
            + " tile) in %.1f s", res.outdoor, res.rooms, res.skipped, res.millis / 1000.0));
        env.spawnLoadedChunks();
    }

    /** Zombies held in hordes per cell (sub-project 4), so respawn sees them; set by the core. */
    private java.util.function.Supplier<Map<Long, Integer>> inHordes = java.util.Map::of;

    /** Map-edge respawns arrive as hordes (sub-project 6); null = one by one. */
    void setEdgeHordes(truehordes.population.Respawner.EdgeHordes h) { respawner.setEdgeHordes(h); }

    int edgePool() { return respawner.edgePool(); }

    void setEdgePool(int n) { respawner.setEdgePool(n); }

    void setHordeCounter(java.util.function.Supplier<Map<Long, Integer>> c) { inHordes = c == null ? java.util.Map::of : c; }

    private void hourly(double hours) {
        double previous = Double.isNaN(lastHourly) ? hours : lastHourly;
        lastHourly = hours;
        settings = env.populationSettings();
        if (map == null) map = env.mapSource();
        if (!book.initialised()) book.init(map, settings, rnd::nextInt, hours);
        for (long k : env.loadedChunks()) book.seen((int) (k >> 32), (int) k, hours);
        Map<Long, Integer> real = env.realZombiesPerCell();
        Map<Long, Integer> hordes = inHordes.get();
        int added = respawner.update(hours, previous, book,
            (cx, cy) -> store.countInCell(cx, cy) + hordes.getOrDefault(((long) cx << 32) | (cy & 0xFFFFFFFFL), 0)
                + real.getOrDefault(((long) cx << 32) | (cy & 0xFFFFFFFFL), 0),
            map, settings, rnd, edgeChunks(), world::walkable, store, env.playerNearby());
        if (added > 0) Log.debug("population", "growth and respawn added " + added);
    }

    private EdgeChunks edgeChunks() {
        if (edge == null) edge = world.edgeChunks();
        long[] e = edge;
        return new EdgeChunks() {
            @Override public int count() { return e.length; }

            @Override public long chunk(int i) { return e[i]; }
        };
    }

    byte[] blob() { return book.initialised() ? book.save() : new byte[0]; }

    String status(double hours) {
        StringBuilder sb = new StringBuilder(" | population ").append(mode.name().toLowerCase(Locale.ROOT));
        int real = 0;
        for (int v : env.realZombiesPerCell().values()) real += v;
        sb.append(" | held ").append(store.size()).append(" | real ").append(real);
        if (book.initialised() && settings != null) {
            long target = 0;
            for (int cy = 0; cy < book.heightInCells(); cy++) {
                for (int cx = 0; cx < book.widthInCells(); cx++) {
                    target += Formula.curve(book.baseTarget(book.cellX0() + cx, book.cellY0() + cy), hours / 24.0, settings);
                }
            }
            sb.append(" | target ").append(target);
        } else {
            sb.append(" | target -");
        }
        sb.append(" | hand-over: ").append(takeover.counts());
        return sb.toString();
    }

    void stop() {
        seedToken = null;
        seedResult = null;
        mode = Mode.NONE;
        book = new CellBook();
        settings = null;
        map = null;
        edge = null;
        lastHourly = Double.NaN;
    }
}
