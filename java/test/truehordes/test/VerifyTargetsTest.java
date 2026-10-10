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

    public static void testMissingIsReported() {
        List<String> missing = HookTargets.missing(cls -> Set.of("somethingElse"));
        Check.equal(HookTargets.ALL.length, missing.size(), "every target reported");
        Check.isTrue(missing.contains(HookTargets.POPMAN + "." + HookTargets.TICK), "names class.method");
    }
}
