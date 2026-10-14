package truehordes.test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Runs every public static no-argument method whose name starts with "test" in each suite.
 * Argument 0 (optional): path to projectzomboid.jar, exposed as system property truehordes.gameJar.
 * Exit code 0 only if at least one test ran and none failed.
 */
public final class TestRunner {
    static final Class<?>[] SUITES = {
        VersionTest.class,
        VisionTest.class,
        MovementTest.class,
        LogStatsTest.class,
        ClockSchedulerTest.class,
        StoreTest.class,
        SaveFileTest.class,
        CoreTest.class,
        VerifyTargetsTest.class,
        LuaArgsTest.class,
        LuaSyntaxTest.class,
        CellReaderTest.class,
        ChunkInfoTest.class,
        ZoneRasterTest.class,
        GridTest.class,
        GridBuilderTest.class,
        WorldTest.class,
        RealMapTest.class,
        RoadFilesTest.class,
        FormulaTest.class,
        RoomCountTest.class,
        SeederTest.class,
        RespawnerTest.class,
        TakeoverTest.class,
    };

    public static void main(String[] args) {
        if (args.length > 0) System.setProperty("truehordes.gameJar", args[0]);
        int passed = 0;
        List<String> failed = new ArrayList<>();
        for (Class<?> suite : SUITES) {
            Method[] methods = suite.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
                try {
                    m.invoke(null);
                    passed++;
                } catch (InvocationTargetException e) {
                    failed.add(suite.getSimpleName() + "." + m.getName() + ": " + e.getCause());
                } catch (ReflectiveOperationException e) {
                    failed.add(suite.getSimpleName() + "." + m.getName() + ": " + e);
                }
            }
        }
        for (String f : failed) System.out.println("FAIL " + f);
        System.out.println(passed + " passed, " + failed.size() + " failed");
        System.exit(failed.isEmpty() && passed > 0 ? 0 : 1);
    }
}
