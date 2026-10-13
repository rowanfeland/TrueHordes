package truehordes.population;

/** What the game bridge reads from a real zombie leaving the loaded area (the fields the game itself keeps). */
public record ZombieSnapshot(float x, float y, float z, byte dir, int outfitId, int stateFlags, int persistentId,
                             int targetX, int targetY, byte[] modData) {}
