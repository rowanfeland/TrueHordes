package truehordes.world;

/** The four neighbours of a chunk. */
public enum Dir {
    N(0, -1), E(1, 0), S(0, 1), W(-1, 0);

    public final int dx;
    public final int dy;

    Dir(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }
}
