package truehordes.sim;

/**
 * A sound zombies can hear off screen, active from {@code startHours} to {@code endHours} (world hours). Sub-project 4
 * only makes the debug test sound; sub-project 5 adds gunshots, meta events, helicopters, thunder and the rest on this
 * same type. Horde moaning is not an event: hordes are heard directly (see Hearing), always at the lowest priority.
 */
public record SoundEvent(long id, float x, float y, float loudnessDb, double startHours, double endHours,
                         boolean moaning, long sourceHordeId) {

    public boolean activeAt(double hours) { return hours >= startHours && hours < endHours; }
}
