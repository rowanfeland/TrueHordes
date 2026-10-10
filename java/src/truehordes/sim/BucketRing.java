package truehordes.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Spreads objects over N buckets and hands out one bucket per step. Scheduling an object 1 step ahead updates it
 * every step; scheduling it N steps ahead updates it once per full cycle. Near objects often, far ones rarely.
 */
public final class BucketRing<T> {
    private final List<List<T>> buckets = new ArrayList<>();
    private int current;

    public BucketRing(int count) {
        if (count < 1) throw new IllegalArgumentException("bucket count must be at least 1");
        for (int i = 0; i < count; i++) buckets.add(new ArrayList<>());
    }

    public int count() { return buckets.size(); }

    /** Schedules obj for the step `stepsAhead` from now (1 = next step), clamped to 1..count. */
    public void schedule(T obj, int stepsAhead) {
        int ahead = Math.max(1, Math.min(stepsAhead, buckets.size()));
        buckets.get((current + ahead) % buckets.size()).add(obj);
    }

    /** Advances one step and returns (and empties) that step's bucket. */
    public List<T> next() {
        current = (current + 1) % buckets.size();
        List<T> due = new ArrayList<>(buckets.get(current));
        buckets.get(current).clear();
        return due;
    }

    public int size() {
        int n = 0;
        for (List<T> b : buckets) n += b.size();
        return n;
    }

    public void clear() {
        for (List<T> b : buckets) b.clear();
        current = 0;
    }
}
