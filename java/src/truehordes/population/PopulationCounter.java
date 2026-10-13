package truehordes.population;

/** Zombies in a 256x256 cell: held by the mod plus real ones there now. */
@FunctionalInterface
public interface PopulationCounter {
    int count(int cellX, int cellY);
}
