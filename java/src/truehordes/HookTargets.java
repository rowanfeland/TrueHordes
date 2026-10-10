package truehordes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** The game methods the mod hooks. Shared by the patches, the start-up self-check and the offline test. */
public final class HookTargets {
    public static final String POPMAN = "zombie.popman.ZombiePopulationManager";
    public static final String MCD = "zombie.MapCollisionData";
    public static final String TICK = "updateMain";
    public static final String SAVE = "save";
    public static final String STOP = "stop";

    public static final String[][] ALL = {
        {POPMAN, TICK},
        {MCD, SAVE},
        {POPMAN, STOP},
    };

    private HookTargets() {}

    /** Returns "class.method" for every target the lookup does not report. */
    public static List<String> missing(Function<String, Set<String>> methodsOfClass) {
        List<String> out = new ArrayList<>();
        for (String[] t : ALL) {
            Set<String> names = methodsOfClass.apply(t[0]);
            if (names == null || !names.contains(t[1])) out.add(t[0] + "." + t[1]);
        }
        return out;
    }
}
