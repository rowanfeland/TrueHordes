package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.BucketRing;
import truehordes.sim.Scheduler;
import truehordes.sim.SimClock;
import truehordes.util.Log;
import truehordes.util.Stats;

public final class ClockSchedulerTest {
    public static void testClockReportsElapsedGameHours() {
        SimClock c = new SimClock();
        Check.near(0, c.advance(10.0), 0, "first call only starts the clock");
        Check.near(0.5, c.advance(10.5), 1e-12, "half an hour");
        Check.near(3.0, c.advance(13.5), 1e-12, "fast-forward is one bigger step");
        Check.near(3.5, c.totalHours(), 1e-12, "total");
    }

    public static void testBackwardsTimeIsIgnored() {
        SimClock c = new SimClock();
        c.reset(20.0);
        Check.near(0, c.advance(19.0), 0, "backwards gives zero");
        Check.near(1.0, c.advance(20.0), 1e-12, "counts from the new reading");
        Check.near(0, c.advance(20.0), 0, "paused gives zero");
        c.clear();
        Check.isTrue(!c.started(), "cleared clock is not started");
    }

    public static void testPausedTickRunsNothing() {
        Scheduler s = new Scheduler(new Stats());
        int[] runs = {0};
        s.register("everyTick", 0, dt -> runs[0]++);
        s.run(0);
        Check.equal(0, runs[0], "no run while paused");
        s.run(0.01);
        Check.equal(1, runs[0], "runs when time moves");
    }

    public static void testCadenceInGameMinutes() {
        Scheduler s = new Scheduler(new Stats());
        List<Double> got = new ArrayList<>();
        s.register("every10min", 10, got::add);
        for (int i = 0; i < 9; i++) s.run(1.0 / 60.0);   // 9 minutes
        Check.equal(0, got.size(), "not yet");
        s.run(1.0 / 60.0);                               // 10th minute
        Check.equal(1, got.size(), "ran once");
        Check.near(10.0 / 60.0, got.get(0), 1e-9, "gets the accumulated time");
    }

    public static void testFailingSystemIsSkippedNotFatal() {
        Log.reset();
        List<String> lines = new ArrayList<>();
        Log.setSink(lines::add);
        Stats stats = new Stats();
        Scheduler s = new Scheduler(stats);
        int[] after = {0};
        s.register("broken", 0, dt -> { throw new IllegalStateException("bad"); });
        s.register("fine", 0, dt -> after[0]++);
        s.run(0.1);
        s.run(0.1);
        Check.equal(2, after[0], "later systems still run");
        Check.equal(1, lines.size(), "error logged once");
        Check.isTrue(stats.format().contains("broken:"), "failing system still timed");
        Log.setSink(null);
        Log.reset();
    }

    public static void testNegativeIntervalRejected() {
        Scheduler s = new Scheduler(new Stats());
        Check.throwsAny(() -> s.register("x", -1, dt -> {}), "negative interval");
    }

    public static void testBucketRingSpreadsWork() {
        BucketRing<String> ring = new BucketRing<>(4);
        ring.schedule("near", 1);
        ring.schedule("far", 4);
        ring.schedule("clamped", 99);
        Check.equal(List.of("near"), ring.next(), "step 1");
        Check.equal(List.of(), ring.next(), "step 2");
        Check.equal(List.of(), ring.next(), "step 3");
        Check.equal(List.of("far", "clamped"), ring.next(), "step 4");
        Check.equal(0, ring.size(), "empty after processing");
        Check.throwsAny(() -> new BucketRing<String>(0), "zero buckets");
    }
}
