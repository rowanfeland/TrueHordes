package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import truehordes.Version;

public final class VersionTest {
    public static void testVersionMatchesModInfo() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("mod/Contents/mods/TrueHordes/42/mod.info"));
        Check.isTrue(lines.contains("modversion=" + Version.VERSION), "mod.info modversion matches Version.VERSION " + Version.VERSION);
    }
}
