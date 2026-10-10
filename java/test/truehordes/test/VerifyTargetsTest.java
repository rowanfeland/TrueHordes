package truehordes.test;

import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import truehordes.HookTargets;

/** Every game method the mod hooks must exist in the installed projectzomboid.jar (catches game updates). */
public final class VerifyTargetsTest {
    public static void testAllHookTargetsExistInGameJar() throws Exception {
        String jarPath = System.getProperty("truehordes.gameJar");
        Check.isTrue(jarPath != null, "build passes the game jar path to the test runner");
        try (ZipFile jar = new ZipFile(jarPath)) {
            List<String> missing = HookTargets.missing(cls -> {
                try {
                    return ClassFileMethods.methodNames(jar, cls);
                } catch (Exception e) {
                    return Set.of();
                }
            });
            Check.equal(List.of(), missing, "missing hook targets");
        }
    }

    /** Review Important #1: the single-player hot-save and the server's game-time save are hooked too. */
    public static void testHotSaveTargetsAreHooked() {
        List<String> all = new java.util.ArrayList<>();
        for (String[] t : HookTargets.ALL) all.add(t[0] + "." + t[1]);
        Check.isTrue(all.contains("zombie.GameTime.saveToBufferMap"), "single-player hot-save hooked: " + all);
        Check.isTrue(all.contains("zombie.network.ServerChunkLoader.saveLater"), "server game-time save hooked: " + all);
    }

    public static void testMissingIsReported() {
        List<String> missing = HookTargets.missing(cls -> Set.of("somethingElse"));
        Check.equal(HookTargets.ALL.length, missing.size(), "every target reported");
        Check.isTrue(missing.contains(HookTargets.POPMAN + "." + HookTargets.TICK), "names class.method");
    }
}
