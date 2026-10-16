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

    /** Sub-project 4: vanilla rally groups are switched off through ZombieGroupManager.update (required). */
    public static void testRallyTargetExists() throws Exception {
        List<String> all = new java.util.ArrayList<>();
        for (String[] t : HookTargets.ALL) all.add(t[0] + "." + t[1]);
        Check.isTrue(all.contains("zombie.ai.ZombieGroupManager.update"), "rally target is required");
        Check.equal(14, HookTargets.ALL.length, "hook count (sub-project 5a added addSound, getSoundAttract, getBiggestSoundZomb)");
        try (ZipFile jar = new ZipFile(System.getProperty("truehordes.gameJar"))) {
            Check.isTrue(ClassFileMethods.methodNames(jar, "zombie.ai.ZombieGroupManager").contains("update"),
                "ZombieGroupManager.update exists in the game");
        }
    }

    public static void testLiveTargetsAndRulesExistInGameJar() throws Exception {
        String jarPath = System.getProperty("truehordes.gameJar");
        Check.isTrue(jarPath != null, "build passes the game jar path to the test runner");
        try (ZipFile jar = new ZipFile(jarPath)) {
            List<String> missing = HookTargets.missing(HookTargets.LIVE, cls -> {
                try {
                    return ClassFileMethods.methodNames(jar, cls);
                } catch (Exception e) {
                    return Set.of();
                }
            });
            Check.equal(List.of(), missing, "missing live hook targets");
            Set<String> mcd = ClassFileMethods.methodNames(jar, HookTargets.MCD);
            for (String rule : HookTargets.RULES) Check.isTrue(mcd.contains(rule), "rule method " + rule);
        }
    }

    /** Sub-project 3: the takeover's hook targets are required, and the private members it reflects on must exist. */
    public static void testTakeoverTargetsAndReflectedMembersExist() throws Exception {
        List<String> all = new java.util.ArrayList<>();
        for (String[] t : HookTargets.ALL) all.add(t[0] + "." + t[1]);
        for (String need : List.of("zombie.popman.ZombiePopulationManager.onConfigReloaded",
                "zombie.popman.ZombiePopulationManager.removeChunkFromWorld",
                "zombie.popman.ZombiePopulationManager.virtualizeZombie",
                "zombie.popman.ZombiePopulationManager.addChunkToWorld",
                "zombie.VirtualZombieManager.roomSpotted")) {
            Check.isTrue(all.contains(need), "required hook target " + need);
        }
        ClassLoader loader = VerifyTargetsTest.class.getClassLoader();
        Class<?> popman = Class.forName(HookTargets.POPMAN, false, loader);
        Class<?> dirs = Class.forName("zombie.iso.IsoDirections", false, loader);
        Class<?> flags = Class.forName("zombie.popman.ZombieStateFlags", false, loader);
        popman.getDeclaredMethod(HookTargets.ADD_STANDING, float.class, float.class, float.class, dirs, int.class, flags, int.class);
        popman.getDeclaredMethod(HookTargets.ADD_MOVING, float.class, float.class, float.class, dirs, int.class, flags,
            int.class, int.class, int.class);
        popman.getDeclaredMethod(HookTargets.CONFIG_FLOAT, String.class, float.class);
        for (String f : HookTargets.REFLECTED_FIELDS) popman.getDeclaredField(f);
        Class<?> rzm = Class.forName("zombie.popman.RealZombieMap", false, loader);
        rzm.getDeclaredMethod("isLive", int.class);
    }
}
