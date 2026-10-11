package truehordes;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import truehordes.core.Core;
import truehordes.util.Log;
import zombie.network.GameClient;
import zombie.network.GameServer;

/** ZombieBuddy entry point (javaPkgName = truehordes). Runs once when the jar loads, on clients and servers. */
public class Main {
    public static void main(String[] args) {
        String mode = GameServer.server ? "dedicated server" : GameClient.client ? "MP client" : "client/single player";
        Log.info("True Hordes " + Version.VERSION + " loading (" + mode + ")");
        Core core = new Core(new GameEnvImpl());
        try {
            List<String> missing = HookTargets.missing(Main::declaredMethodNames);
            if (missing.isEmpty()) {
                Log.info("hook targets found: " + HookTargets.ALL.length);
            } else {
                core.disable("game methods missing (game update?): " + missing);
            }
            List<String> liveMissing = HookTargets.missing(HookTargets.LIVE, Main::declaredMethodNames);
            String why = liveMissing.isEmpty() ? LiveBits.init() : "game methods missing: " + liveMissing;
            if (why == null) Log.info("live map updates on");
            else Log.warn("live map updates off (" + why + "); the world map still builds from the map files");
        } catch (Throwable t) {
            core.disable("self-check failed: " + t);
        }
        Hooks.install(core);
    }

    private static Set<String> declaredMethodNames(String className) {
        Set<String> names = new HashSet<>();
        try {
            Class<?> c = Class.forName(className, false, Main.class.getClassLoader());
            for (Method m : c.getDeclaredMethods()) names.add(m.getName());
        } catch (ClassNotFoundException | LinkageError e) {
            Log.warn("hook target class not found: " + className + " (" + e + ")");
        }
        return names;
    }
}
