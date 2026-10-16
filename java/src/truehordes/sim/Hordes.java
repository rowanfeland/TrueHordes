package truehordes.sim;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import truehordes.store.ZombieRecord;

/** Every horde the mod holds, by id. Changed only under the core's lock. */
public final class Hordes {
    private final Map<Long, Horde> byId = new LinkedHashMap<>();
    private long nextId = 1;
    private int members;

    private java.util.function.Consumer<Horde> onCreated = h -> {};

    /** Told about every horde made by {@link #create} (the simulation schedules it). */
    public void setOnCreated(java.util.function.Consumer<Horde> c) { onCreated = c == null ? h -> {} : c; }

    /** A new horde; every member records it as its horde. */
    public Horde create(float x, float y, float heading, List<ZombieRecord> memberList) {
        Horde h = add(new Horde(nextId++, x, y, heading, memberList));
        onCreated.accept(h);
        return h;
    }

    /** A new dormant building horde (see Horde.dormant). */
    public Horde createDormant(float x, float y, List<ZombieRecord> memberList) {
        Horde h = add(new Horde(nextId++, x, y, 0, memberList));
        h.dormant = true;
        onCreated.accept(h);
        return h;
    }

    /** Re-adds a loaded horde with its saved id. */
    public Horde restore(long id, float x, float y, float heading, List<ZombieRecord> memberList) {
        if (byId.containsKey(id)) throw new IllegalStateException("duplicate horde id " + id);
        if (id >= nextId) nextId = id + 1;
        return add(new Horde(id, x, y, heading, memberList));
    }

    private Horde add(Horde h) {
        for (ZombieRecord r : h.members) r.hordeId = h.id;
        byId.put(h.id, h);
        members += h.size();
        return h;
    }

    public Horde get(long id) { return byId.get(id); }

    public void remove(long id) {
        Horde h = byId.remove(id);
        if (h != null) members -= h.size();
    }

    /** Adds a member to a horde (keeps the member count right). */
    public void addMember(Horde h, ZombieRecord r) {
        h.members.add(r);
        r.hordeId = h.id;
        members++;
    }

    /** Removes a member (it keeps hordeId so it can rejoin later). Returns false if it was not a member. */
    public boolean removeMember(Horde h, ZombieRecord r) {
        if (!h.members.remove(r)) return false;
        members--;
        return true;
    }

    /** Removes the member at index {@code i} in O(1) (the last member takes its place; order does not matter). */
    public ZombieRecord removeMemberAt(Horde h, int i) {
        List<ZombieRecord> m = h.members;
        ZombieRecord r = m.get(i);
        int last = m.size() - 1;
        m.set(i, m.get(last));
        m.remove(last);
        members--;
        return r;
    }

    /** Removes the given members in one pass (identity). */
    public void removeMembers(Horde h, java.util.Set<ZombieRecord> gone) {
        int before = h.members.size();
        h.members.removeIf(gone::contains);
        members -= before - h.members.size();
    }

    /** Moves every member of {@code from} into {@code into} and removes {@code from}. */
    public void absorb(Horde into, Horde from) {
        for (ZombieRecord r : from.members) r.hordeId = into.id;
        into.members.addAll(from.members);
        byId.remove(from.id);
    }

    public Collection<Horde> all() { return Collections.unmodifiableCollection(byId.values()); }

    public int size() { return byId.size(); }

    public int memberCount() { return members; }

    public long nextId() { return nextId; }

    /** An id for a horde formed among real zombies (sub-project 6): never used by another horde. */
    public long reserveId() { return nextId++; }

    public void setNextId(long n) { nextId = Math.max(nextId, n); }

    public void clear() {
        byId.clear();
        members = 0;
    }
}
