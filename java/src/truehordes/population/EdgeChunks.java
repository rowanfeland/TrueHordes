package truehordes.population;

/** Walkable chunks beside the map's outer edge, where newcomers arrive later in a game. Keys: chunkX << 32 | chunkY. */
public interface EdgeChunks {
    int count();

    long chunk(int i);
}
