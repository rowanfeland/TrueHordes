package truehordes.store;

/** One simulated object. Sub-project 1 only has placeholders; later sub-projects add kinds and fields. */
public final class Entity {
    public static final byte KIND_PLACEHOLDER = 0;

    public final long id;
    public final byte kind;
    public float x;
    public float y;
    public byte z;

    public Entity(long id, byte kind, float x, float y, byte z) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
