package truehordes.test;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.luaj.kahluafork.compiler.LexState;

/** Compiles every mod Lua file with the game's own Kahlua compiler, so syntax errors are caught before the game. */
public final class LuaSyntaxTest {
    private static void compile(String source, String name) throws Exception {
        Reader reader = new StringReader(source);
        LexState.compile(reader.read(), reader, name, name);
    }

    public static void testAllModLuaFilesCompile() throws Exception {
        List<String> errors = new ArrayList<>();
        int count = 0;
        try (Stream<Path> files = Files.walk(Path.of("mod"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".lua"))::iterator) {
                count++;
                try {
                    compile(Files.readString(p, StandardCharsets.UTF_8), p.toString());
                } catch (Throwable t) {
                    errors.add(p + ": " + t.getMessage());
                }
            }
        }
        Check.isTrue(count >= 2, "found the mod's Lua files (" + count + ")");
        Check.equal(List.of(), errors, "Lua syntax errors");
    }

    public static void testBrokenLuaIsRejected() {
        Throwable caught = null;
        try {
            compile("function broken(\n  return 1\nend", "broken.lua");
        } catch (Throwable t) {
            caught = t;
        }
        Check.isTrue(caught != null, "a syntax error must be reported");
        Check.isTrue(!(caught instanceof LinkageError), "reported by the compiler, not a missing class: " + caught);
    }
}
