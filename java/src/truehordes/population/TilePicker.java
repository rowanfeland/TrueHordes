package truehordes.population;

/** Whether a zombie can stand on an outdoor tile (sub-project 2's world map: not solid, water or room). */
@FunctionalInterface
public interface TilePicker {
    boolean outdoorWalkable(int x, int y);
}
