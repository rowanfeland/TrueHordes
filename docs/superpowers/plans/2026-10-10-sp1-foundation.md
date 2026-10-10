# True Hordes sub-project 1 (foundation and tools) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A loadable Project Zomboid B42.21 mod with a Java core on ZombieBuddy (lifecycle, game-time clock, scheduler, entity store, crash-safe save file matched to the world save, logging, timings), a Lua helper with console commands and a debug map, and an offline build and test pipeline. No gameplay: placeholder entities stand in for zombies.

**Architecture:** Pure-Java units (`truehordes.util`, `.sim`, `.store`, `.save`, `.core`) hold all logic and never import game classes, so they run in offline tests. A thin game bridge in package `truehordes` (ZombieBuddy patch classes, `GameEnvImpl`, `Hooks`, `LuaApi`, `Main`) connects them to the game through three hooks that exist in both single player and on a dedicated server: tick after `ZombiePopulationManager.updateMain`, save after `MapCollisionData.save`, stop before `ZombiePopulationManager.stop`. Lua calls Java through one exposed table, `TrueHordesJ`.

**Tech Stack:** Java 25 (JDK at `%USERPROFILE%\.jdks\jdk-25.0.4.1+1`), ZombieBuddy 2.3.4 (`@Patch`, `@Exposer.LuaClass`), Kahlua (inside `projectzomboid.jar`), Lua 5.1 (Kahlua) for the helper and UI, PowerShell build scripts, git.

**Spec:** `docs/superpowers/specs/2026-10-10-sp1-foundation-design.md` (parent: `design.md`).

## Global Constraints

- Build 42.21; B42 mod layout: `42\` and `common\`, `mod.info`, `sandbox-options.txt`, JSON translations.
- `mod.info`: `id=TrueHordes`, `require=\ZombieBuddy`, `incompatible=\WanderingZombies,\WanderingZombiesWIP`, `javaJarFile=media/java/TrueHordes.jar`, `javaPkgName=truehordes`, `ZBVersionMin=2.3.0`, `versionMin=42.21`.
- ZombieBuddy patch classes live directly in package `truehordes` (it does not scan sub-packages).
- Pure units (`truehordes.util/.sim/.store/.save/.core`) must not import `zombie.*`, `se.krka.*` or `me.zed_0xff.*`.
- Every entry from the game is wrapped: nothing may throw into the game.
- Only the machine that owns the simulation saves (single player, or the dedicated server); MP clients never write.
- Save file: `<current save>\truehordes\state.bin`, written temp-then-rename with a `.bak` copy of the previous file.
- Log lines start with `[TrueHordes] ` and go to `System.out` (ends up in `console.txt`).
- Never edit files under `C:\Program Files (x86)\Steam\...`. Do not copy code from other mods.
- Work in `Desktop\Zomboid Mods Personal\TrueHordes`; `tools\deploy.ps1` copies `mod\` to `%USERPROFILE%\Zomboid\Workshop\TrueHordes` only when run.
- Commit after every task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Switching saves in one session** (quit to menu, load another save): no entities or clock state may leak from the previous save. Pinned by `CoreTest.testStopThenStartOtherSaveDoesNotLeak` (Task 6).
2. **Crash between the world save and the mod save**: the mod must start with the newest valid file and warn about the game-time mismatch, not fail. Pinned by `CoreTest.testMismatchedSaveTimeWarnsButLoads` (Task 6).
3. **Corrupted, truncated or newer-version save file**: set aside, recover from `.bak`, else start empty, never crash. Pinned by `SaveFileTest.testTruncatedFileRecoversFromBackup`, `testNewerVersionIsSetAside` (Task 5).
4. **Time going backwards or jumping** (reload, sleeping at high speed, pause): no negative steps, no runs while paused. Pinned by `ClockSchedulerTest.testBackwardsTimeIsIgnored`, `testPausedTickRunsNothing` (Task 3).
5. **Running on a multiplayer client or with the mod disabled in sandbox**: stays idle and never writes a file. Pinned by `CoreTest.testClientNeverRunsOrWrites`, `testSandboxDisabledStaysOff` (Task 6).

---

## File map

| File | Responsibility |
|---|---|
| `.gitattributes` | Keep source files LF |
| `tools/build.ps1` | Compile with JDK 25, run tests, package jar |
| `tools/deploy.ps1` | Copy `mod\` into the Workshop folder |
| `tools/make_placeholder_png.py` | Generate placeholder images |
| `mod/workshop.txt`, `mod/preview.png` | Workshop item metadata |
| `mod/Contents/mods/TrueHordes/common/readme.txt` | Keeps B42's required `common` folder |
| `mod/Contents/mods/TrueHordes/42/mod.info`, `poster.png`, `icon.png` | Mod descriptor and images |
| `.../42/media/sandbox-options.txt`, `.../lua/shared/Translate/EN/Sandbox.json` | Sandbox page |
| `.../42/media/lua/shared/TrueHordes/TH_Core.lua` | Java bridge check and console commands |
| `.../42/media/lua/client/TrueHordes/TH_DebugMap.lua` | Debug map window |
| `java/src/truehordes/Version.java` | Version constant |
| `java/src/truehordes/util/Log.java`, `Stats.java` | Logging, timings |
| `java/src/truehordes/sim/SimClock.java`, `Scheduler.java`, `BucketRing.java` | Game-time clock, cadence, buckets |
| `java/src/truehordes/store/Entity.java`, `Store.java`, `Snapshot.java` | Entity registry and map snapshots |
| `java/src/truehordes/save/SaveFile.java`, `LoadResult.java` | Versioned, crash-safe save file |
| `java/src/truehordes/core/GameEnv.java`, `Core.java` | Lifecycle (pure, testable) |
| `java/src/truehordes/HookTargets.java` | Names of the game methods the mod hooks |
| `java/src/truehordes/GameEnvImpl.java` | `GameEnv` backed by the game |
| `java/src/truehordes/Hooks.java`, `Patches.java`, `Main.java` | ZombieBuddy entry and patches |
| `java/src/truehordes/LuaApi.java` | `TrueHordesJ` table for Lua |
| `java/test/truehordes/test/*.java` | Runner, checks, fakes, tests |

---

### Task 1: Repository scaffold, build pipeline and test runner

**Files:**
- Create: `.gitattributes`, `tools/build.ps1`, `tools/make_placeholder_png.py`
- Create: `mod/workshop.txt`, `mod/preview.png`, `mod/Contents/mods/TrueHordes/common/readme.txt`
- Create: `mod/Contents/mods/TrueHordes/42/mod.info`, `poster.png`, `icon.png`
- Create: `mod/Contents/mods/TrueHordes/42/media/sandbox-options.txt`
- Create: `mod/Contents/mods/TrueHordes/42/media/lua/shared/Translate/EN/Sandbox.json`
- Create: `java/src/truehordes/Version.java`
- Test: `java/test/truehordes/test/Check.java`, `TestRunner.java`, `VersionTest.java`

**Interfaces:**
- Produces: `truehordes.Version.VERSION` (String "0.1.0"); `truehordes.test.Check` (`equal`, `near`, `isTrue`, `throwsAny`); `TestRunner.SUITES` (array of test classes; every later task adds its class); `tools/build.ps1` (exit code 0 only if compile and all tests pass). Tests run with the repo root as working directory.

- [ ] **Step 1: Create static mod files**

`.gitattributes`:
```
* text=auto
*.java text eol=lf
*.lua text eol=lf
*.md text eol=lf
*.txt text eol=lf
*.json text eol=lf
*.info text eol=lf
*.ps1 text eol=crlf
*.png binary
*.bin binary
```

`mod/workshop.txt`:
```
version=1
title=True Hordes
description=Walking Dead style hordes that form for real reasons and roam the whole map.
description=
description=Work in progress. Requires ZombieBuddy.
tags=Build 42
visibility=unlisted
```

`mod/Contents/mods/TrueHordes/common/readme.txt`:
```
B42 expects this folder to exist next to the 42 folder. Leave it in place.
```

`mod/Contents/mods/TrueHordes/42/mod.info`:
```
name=True Hordes
id=TrueHordes
description=Walking Dead style hordes that form for real reasons and roam the whole map. Work in progress; requires ZombieBuddy.
poster=poster.png
icon=icon.png
modversion=0.1.0
versionMin=42.21
require=\ZombieBuddy
incompatible=\WanderingZombies,\WanderingZombiesWIP
javaJarFile=media/java/TrueHordes.jar
javaPkgName=truehordes
ZBVersionMin=2.3.0
```

`mod/Contents/mods/TrueHordes/42/media/sandbox-options.txt`:
```
VERSION = 1,

option TrueHordes.Enabled
{
	type = boolean,
	default = true,
	page = TrueHordes,
	translation = TrueHordes_Enabled,
}

option TrueHordes.DebugLogging
{
	type = boolean,
	default = false,
	page = TrueHordes,
	translation = TrueHordes_DebugLogging,
}
```

`mod/Contents/mods/TrueHordes/42/media/lua/shared/Translate/EN/Sandbox.json`:
```json
{
    "Sandbox_TrueHordes": "True Hordes",
    "Sandbox_TrueHordes_Enabled": "Enabled",
    "Sandbox_TrueHordes_Enabled_tooltip": "Turns the True Hordes simulation on or off for this world.",
    "Sandbox_TrueHordes_DebugLogging": "Debug logging",
    "Sandbox_TrueHordes_DebugLogging_tooltip": "Writes extra [TrueHordes] lines to console.txt. Leave off unless testing."
}
```

- [ ] **Step 2: Create the placeholder image generator and images**

`tools/make_placeholder_png.py`:
```python
"""Writes a solid-colour placeholder PNG (no third-party libraries).

Usage: python tools/make_placeholder_png.py <out.png> <size>
"""
import struct
import sys
import zlib


def png(path, size, rgb=(110, 20, 20)):
    row = b"\x00" + bytes(rgb) * size
    raw = row * size

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", header))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


if __name__ == "__main__":
    png(sys.argv[1], int(sys.argv[2]))
```

Run:
```bash
python tools/make_placeholder_png.py mod/preview.png 256
python tools/make_placeholder_png.py mod/Contents/mods/TrueHordes/42/poster.png 256
python tools/make_placeholder_png.py mod/Contents/mods/TrueHordes/42/icon.png 64
```
Expected: three PNG files exist (`file mod/preview.png` reports "PNG image data, 256 x 256").

- [ ] **Step 3: Create the build script**

`tools/build.ps1`:
```powershell
# Compiles the Java core with JDK 25, runs the offline tests, and packages TrueHordes.jar into mod\.
# Usage (from anywhere): powershell -ExecutionPolicy Bypass -File tools\build.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $env:USERPROFILE '.jdks\jdk-25.0.4.1+1\bin'
$game = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid'
$gameJar = Join-Path $game 'projectzomboid.jar'
$cp = "$gameJar;$(Join-Path $game 'ZombieBuddy.jar')"
$out = Join-Path $root 'build'
$jarOut = Join-Path $root 'mod\Contents\mods\TrueHordes\42\media\java\TrueHordes.jar'

Push-Location $root
try {
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    New-Item -ItemType Directory -Force (Join-Path $out 'classes'), (Join-Path $out 'test') | Out-Null

    $src = @(Get-ChildItem (Join-Path $root 'java\src') -Recurse -Filter *.java | ForEach-Object FullName)
    & (Join-Path $jdk 'javac.exe') --release 25 -encoding UTF-8 -Xlint:-options -cp $cp -d (Join-Path $out 'classes') @src
    if ($LASTEXITCODE -ne 0) { throw 'compile failed' }

    $tests = @(Get-ChildItem (Join-Path $root 'java\test') -Recurse -Filter *.java | ForEach-Object FullName)
    & (Join-Path $jdk 'javac.exe') --release 25 -encoding UTF-8 -Xlint:-options -cp "$(Join-Path $out 'classes');$cp" -d (Join-Path $out 'test') @tests
    if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }

    & (Join-Path $jdk 'java.exe') -cp "$(Join-Path $out 'test');$(Join-Path $out 'classes')" truehordes.test.TestRunner $gameJar
    if ($LASTEXITCODE -ne 0) { throw 'tests failed' }

    New-Item -ItemType Directory -Force (Split-Path $jarOut) | Out-Null
    & (Join-Path $jdk 'jar.exe') --create --file $jarOut -C (Join-Path $out 'classes') .
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
    Write-Host "Built $jarOut"
} finally {
    Pop-Location
}
```

- [ ] **Step 4: Write the test helpers and the failing test**

`java/test/truehordes/test/Check.java`:
```java
package truehordes.test;

import java.util.Objects;

/** Minimal assertions for the offline tests (no external test library). */
public final class Check {
    private Check() {}

    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void near(double expected, double actual, double tolerance, String what) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(what + ": expected " + expected + " +/- " + tolerance + " but was " + actual);
        }
    }

    public static void isTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void throwsAny(Runnable action, String what) {
        try {
            action.run();
        } catch (Throwable expected) {
            return;
        }
        throw new AssertionError(what + ": expected an exception");
    }
}
```

`java/test/truehordes/test/TestRunner.java`:
```java
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
```

`java/test/truehordes/test/VersionTest.java`:
```java
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
```

- [ ] **Step 5: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL. Either "compile failed" (no sources yet in `java\src`) or "cannot find symbol: class Version".

- [ ] **Step 6: Implement**

`java/src/truehordes/Version.java`:
```java
package truehordes;

/** Mod version; must match modversion in mod.info (checked by VersionTest). */
public final class Version {
    public static final String VERSION = "0.1.0";

    private Version() {}
}
```

- [ ] **Step 7: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `1 passed, 0 failed` and `Built ...\TrueHordes.jar`.

- [ ] **Step 8: Commit**

```bash
git add .gitattributes tools mod java
git commit -m "SP1: mod scaffold, build script and test runner

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Logging and timings

**Files:**
- Create: `java/src/truehordes/util/Log.java`, `java/src/truehordes/util/Stats.java`
- Test: `java/test/truehordes/test/LogStatsTest.java`; Modify: `TestRunner.java` (add `LogStatsTest.class` to `SUITES`)

**Interfaces:**
- Produces: `Log.info(String)`, `Log.warn(String)`, `Log.error(String, Throwable)`, `Log.errorOnce(String key, String msg, Throwable) -> boolean`, `Log.debug(String category, String msg)`, `Log.setDebug(String category, boolean)`, `Log.isDebug(String) -> boolean`, `Log.debugCategories() -> String`, `Log.setSink(Consumer<String>)`, `Log.reset()`, `Log.TAG`. `Stats.time(String, long nanos)`, `Stats.count(String, long)`, `Stats.averageMs(String) -> double`, `Stats.counter(String) -> long`, `Stats.format() -> String`, `Stats.clear()`.

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/LogStatsTest.java`:
```java
package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.util.Log;
import truehordes.util.Stats;

public final class LogStatsTest {
    private static List<String> capture() {
        List<String> lines = new ArrayList<>();
        Log.reset();
        Log.setSink(lines::add);
        return lines;
    }

    public static void testLinesCarryTag() {
        List<String> lines = capture();
        Log.info("hello");
        Log.warn("careful");
        Check.equal("[TrueHordes] hello", lines.get(0), "info line");
        Check.equal("[TrueHordes] WARN careful", lines.get(1), "warn line");
        Log.setSink(null);
    }

    public static void testErrorOnceLogsEachKeyOnce() {
        List<String> lines = capture();
        Check.isTrue(Log.errorOnce("k", "boom", new RuntimeException("x")), "first logged");
        Check.isTrue(!Log.errorOnce("k", "boom", new RuntimeException("x")), "second suppressed");
        Check.equal(1, lines.size(), "one line");
        Check.isTrue(lines.get(0).contains("ERROR boom: java.lang.RuntimeException: x"), "error text");
        Log.setSink(null);
    }

    public static void testDebugCategories() {
        List<String> lines = capture();
        Log.debug("save", "hidden");
        Log.setDebug("save", true);
        Log.debug("save", "shown");
        Log.debug("other", "hidden");
        Log.setDebug("all", true);
        Log.debug("other", "shown too");
        Check.equal(2, lines.size(), "two debug lines");
        Check.equal("[TrueHordes] [save] shown", lines.get(0), "category prefix");
        Check.equal("all,save", Log.debugCategories(), "categories listed sorted");
        Log.setSink(null);
        Log.reset();
    }

    public static void testStatsAveragesAndFormats() {
        Stats s = new Stats();
        s.time("tick", 2_000_000);
        s.time("tick", 4_000_000);
        Check.near(2.2, s.averageMs("tick"), 1e-9, "moving average (alpha 0.1)");
        s.count("entities", 7);
        Check.equal(7L, s.counter("entities"), "counter");
        String text = s.format();
        Check.isTrue(text.contains("entities=7"), "counter in text");
        Check.isTrue(text.contains("tick: avg 2.200 ms, last 4.000 ms, max 4.000 ms, 2 calls"), "timing line: " + text);
        s.clear();
        Check.equal("no stats yet", s.format(), "cleared");
    }
}
```
Modify `TestRunner.SUITES` to:
```java
    static final Class<?>[] SUITES = {
        VersionTest.class,
        LogStatsTest.class,
    };
```

- [ ] **Step 2: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL, "test compile failed" (package truehordes.util does not exist).

- [ ] **Step 3: Implement**

`java/src/truehordes/util/Log.java`:
```java
package truehordes.util;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "[TrueHordes] " lines on System.out (console.txt), switchable debug categories, each error kind once. */
public final class Log {
    public static final String TAG = "[TrueHordes] ";
    private static final Consumer<String> DEFAULT_SINK = System.out::println;
    private static final Set<String> debugOn = ConcurrentHashMap.newKeySet();
    private static final Set<String> errorsSeen = ConcurrentHashMap.newKeySet();
    private static volatile Consumer<String> sink = DEFAULT_SINK;

    private Log() {}

    /** Redirects output (tests); null restores System.out. */
    public static void setSink(Consumer<String> newSink) {
        sink = newSink == null ? DEFAULT_SINK : newSink;
    }

    public static void info(String msg) { sink.accept(TAG + msg); }

    public static void warn(String msg) { sink.accept(TAG + "WARN " + msg); }

    public static void error(String msg, Throwable t) {
        sink.accept(TAG + "ERROR " + msg + (t == null ? "" : ": " + t));
    }

    /** Logs only the first occurrence of each key. Returns true if this call logged. */
    public static boolean errorOnce(String key, String msg, Throwable t) {
        if (!errorsSeen.add(key)) return false;
        error(msg, t);
        return true;
    }

    public static void setDebug(String category, boolean on) {
        if (on) debugOn.add(category); else debugOn.remove(category);
    }

    public static boolean isDebug(String category) {
        return debugOn.contains(category) || debugOn.contains("all");
    }

    public static void debug(String category, String msg) {
        if (isDebug(category)) sink.accept(TAG + "[" + category + "] " + msg);
    }

    public static String debugCategories() {
        return String.join(",", new TreeSet<>(debugOn));
    }

    /** Forgets debug categories and logged error keys (tests and world reloads). */
    public static void reset() {
        debugOn.clear();
        errorsSeen.clear();
    }
}
```

`java/src/truehordes/util/Stats.java`:
```java
package truehordes.util;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Per-system timings (moving average, last, max, calls) and counters for TH_stats(). Main thread only. */
public final class Stats {
    private static final double ALPHA = 0.1;
    private final Map<String, double[]> timings = new TreeMap<>(); // avgMs, lastMs, maxMs, calls
    private final Map<String, Long> counters = new TreeMap<>();

    public void time(String name, long nanos) {
        double ms = nanos / 1_000_000.0;
        double[] t = timings.computeIfAbsent(name, k -> new double[4]);
        t[0] = t[3] == 0 ? ms : t[0] + ALPHA * (ms - t[0]);
        t[1] = ms;
        t[2] = Math.max(t[2], ms);
        t[3]++;
    }

    public void count(String name, long value) { counters.put(name, value); }

    public double averageMs(String name) {
        double[] t = timings.get(name);
        return t == null ? 0 : t[0];
    }

    public long counter(String name) { return counters.getOrDefault(name, 0L); }

    public void clear() {
        timings.clear();
        counters.clear();
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        timings.forEach((k, t) -> sb.append(String.format(Locale.ROOT,
            "%s: avg %.3f ms, last %.3f ms, max %.3f ms, %d calls%n", k, t[0], t[1], t[2], (long) t[3])));
        return sb.length() == 0 ? "no stats yet" : sb.toString().trim();
    }
}
```

- [ ] **Step 4: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `5 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java
git commit -m "SP1: logging and per-system timings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Game-time clock, scheduler and update buckets

**Files:**
- Create: `java/src/truehordes/sim/SimClock.java`, `Scheduler.java`, `BucketRing.java`
- Test: `java/test/truehordes/test/ClockSchedulerTest.java`; Modify: `TestRunner.java` (add `ClockSchedulerTest.class`)

**Interfaces:**
- Consumes: `Log.errorOnce`, `Stats.time` (Task 2).
- Produces: `SimClock.reset(double worldAgeHours)`, `SimClock.advance(double worldAgeHours) -> double elapsedHours`, `SimClock.clear()`, `SimClock.started() -> boolean`, `SimClock.totalHours() -> double`. `Scheduler(Stats)`, `Scheduler.Task` (`void run(double elapsedHours)`), `Scheduler.register(String name, double everyGameMinutes, Task)`, `Scheduler.run(double elapsedHours)`, `Scheduler.size()`. `BucketRing<T>(int count)`, `schedule(T, int stepsAhead)`, `next() -> List<T>`, `size()`, `count()`, `clear()`.

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/ClockSchedulerTest.java`:
```java
package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.BucketRing;
import truehordes.sim.Scheduler;
import truehordes.sim.SimClock;
import truehordes.util.Log;
import truehordes.util.Stats;

public final class ClockSchedulerTest {
    public static void testClockReportsElapsedGameHours() {
        SimClock c = new SimClock();
        Check.near(0, c.advance(10.0), 0, "first call only starts the clock");
        Check.near(0.5, c.advance(10.5), 1e-12, "half an hour");
        Check.near(3.0, c.advance(13.5), 1e-12, "fast-forward is one bigger step");
        Check.near(3.5, c.totalHours(), 1e-12, "total");
    }

    public static void testBackwardsTimeIsIgnored() {
        SimClock c = new SimClock();
        c.reset(20.0);
        Check.near(0, c.advance(19.0), 0, "backwards gives zero");
        Check.near(1.0, c.advance(20.0), 1e-12, "counts from the new reading");
        Check.near(0, c.advance(20.0), 0, "paused gives zero");
        c.clear();
        Check.isTrue(!c.started(), "cleared clock is not started");
    }

    public static void testPausedTickRunsNothing() {
        Scheduler s = new Scheduler(new Stats());
        int[] runs = {0};
        s.register("everyTick", 0, dt -> runs[0]++);
        s.run(0);
        Check.equal(0, runs[0], "no run while paused");
        s.run(0.01);
        Check.equal(1, runs[0], "runs when time moves");
    }

    public static void testCadenceInGameMinutes() {
        Scheduler s = new Scheduler(new Stats());
        List<Double> got = new ArrayList<>();
        s.register("every10min", 10, got::add);
        for (int i = 0; i < 9; i++) s.run(1.0 / 60.0);   // 9 minutes
        Check.equal(0, got.size(), "not yet");
        s.run(1.0 / 60.0);                               // 10th minute
        Check.equal(1, got.size(), "ran once");
        Check.near(10.0 / 60.0, got.get(0), 1e-9, "gets the accumulated time");
    }

    public static void testFailingSystemIsSkippedNotFatal() {
        Log.reset();
        List<String> lines = new ArrayList<>();
        Log.setSink(lines::add);
        Stats stats = new Stats();
        Scheduler s = new Scheduler(stats);
        int[] after = {0};
        s.register("broken", 0, dt -> { throw new IllegalStateException("bad"); });
        s.register("fine", 0, dt -> after[0]++);
        s.run(0.1);
        s.run(0.1);
        Check.equal(2, after[0], "later systems still run");
        Check.equal(1, lines.size(), "error logged once");
        Check.isTrue(stats.format().contains("broken:"), "failing system still timed");
        Log.setSink(null);
        Log.reset();
    }

    public static void testNegativeIntervalRejected() {
        Scheduler s = new Scheduler(new Stats());
        Check.throwsAny(() -> s.register("x", -1, dt -> {}), "negative interval");
    }

    public static void testBucketRingSpreadsWork() {
        BucketRing<String> ring = new BucketRing<>(4);
        ring.schedule("near", 1);
        ring.schedule("far", 4);
        ring.schedule("clamped", 99);
        Check.equal(List.of("near"), ring.next(), "step 1");
        Check.equal(List.of(), ring.next(), "step 2");
        Check.equal(List.of(), ring.next(), "step 3");
        Check.equal(List.of("far", "clamped"), ring.next(), "step 4");
        Check.equal(0, ring.size(), "empty after processing");
        Check.throwsAny(() -> new BucketRing<String>(0), "zero buckets");
    }
}
```
Modify `TestRunner.SUITES` to add `ClockSchedulerTest.class,` after `LogStatsTest.class,`.

- [ ] **Step 2: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL, "test compile failed" (package truehordes.sim does not exist).

- [ ] **Step 3: Implement**

`java/src/truehordes/sim/SimClock.java`:
```java
package truehordes.sim;

/** Simulation time from in-game world age (hours). Paused = no change; higher game speed = bigger steps. */
public final class SimClock {
    private double lastHours = Double.NaN;
    private double totalHours;

    /** Starts counting from this world age. */
    public void reset(double worldAgeHours) {
        lastHours = worldAgeHours;
        totalHours = 0;
    }

    /** Forgets everything (world unloaded). */
    public void clear() {
        lastHours = Double.NaN;
        totalHours = 0;
    }

    /** Elapsed in-game hours since the previous call; 0 when paused, on the first call, or if time went back. */
    public double advance(double worldAgeHours) {
        if (Double.isNaN(lastHours)) {
            reset(worldAgeHours);
            return 0;
        }
        double d = worldAgeHours - lastHours;
        lastHours = worldAgeHours;
        if (!(d > 0)) return 0;
        totalHours += d;
        return d;
    }

    public boolean started() { return !Double.isNaN(lastHours); }

    public double totalHours() { return totalHours; }
}
```

`java/src/truehordes/sim/Scheduler.java`:
```java
package truehordes.sim;

import java.util.ArrayList;
import java.util.List;
import truehordes.util.Log;
import truehordes.util.Stats;

/** Runs systems at a cadence in in-game minutes (0 = every tick in which time moved) and times them. Main thread only. */
public final class Scheduler {
    public interface Task {
        void run(double elapsedHours);
    }

    private static final class Entry {
        final String name;
        final double intervalHours;
        final Task task;
        double pending;

        Entry(String name, double intervalHours, Task task) {
            this.name = name;
            this.intervalHours = intervalHours;
            this.task = task;
        }
    }

    /** Tolerance for accumulated floating-point time (about 4 microseconds of game time). */
    private static final double EPSILON_HOURS = 1e-9;

    private final List<Entry> entries = new ArrayList<>();
    private final Stats stats;

    public Scheduler(Stats stats) {
        this.stats = stats;
    }

    public void register(String name, double everyGameMinutes, Task task) {
        if (everyGameMinutes < 0) throw new IllegalArgumentException("negative interval for " + name);
        entries.add(new Entry(name, everyGameMinutes / 60.0, task));
    }

    /** Advances all systems by the elapsed in-game hours. A failing system is logged once and skipped for this run. */
    public void run(double elapsedHours) {
        for (Entry e : entries) {
            e.pending += elapsedHours;
            boolean due = e.intervalHours > 0 ? e.pending >= e.intervalHours - EPSILON_HOURS : elapsedHours > 0;
            if (!due) continue;
            double dt = e.pending;
            e.pending = 0;
            long t0 = System.nanoTime();
            try {
                e.task.run(dt);
            } catch (Throwable t) {
                Log.errorOnce("system:" + e.name + ":" + t.getClass().getName(),
                    "system " + e.name + " failed and was skipped this run", t);
            }
            stats.time(e.name, System.nanoTime() - t0);
        }
    }

    public int size() { return entries.size(); }
}
```

`java/src/truehordes/sim/BucketRing.java`:
```java
package truehordes.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Spreads objects over N buckets and hands out one bucket per step. Scheduling an object 1 step ahead updates it
 * every step; scheduling it N steps ahead updates it once per full cycle. Near objects often, far ones rarely.
 */
public final class BucketRing<T> {
    private final List<List<T>> buckets = new ArrayList<>();
    private int current;

    public BucketRing(int count) {
        if (count < 1) throw new IllegalArgumentException("bucket count must be at least 1");
        for (int i = 0; i < count; i++) buckets.add(new ArrayList<>());
    }

    public int count() { return buckets.size(); }

    /** Schedules obj for the step `stepsAhead` from now (1 = next step), clamped to 1..count. */
    public void schedule(T obj, int stepsAhead) {
        int ahead = Math.max(1, Math.min(stepsAhead, buckets.size()));
        buckets.get((current + ahead) % buckets.size()).add(obj);
    }

    /** Advances one step and returns (and empties) that step's bucket. */
    public List<T> next() {
        current = (current + 1) % buckets.size();
        List<T> due = new ArrayList<>(buckets.get(current));
        buckets.get(current).clear();
        return due;
    }

    public int size() {
        int n = 0;
        for (List<T> b : buckets) n += b.size();
        return n;
    }

    public void clear() {
        for (List<T> b : buckets) b.clear();
        current = 0;
    }
}
```

- [ ] **Step 4: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `12 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java
git commit -m "SP1: game-time clock, scheduler and update buckets

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Entity store and map snapshots

**Files:**
- Create: `java/src/truehordes/store/Entity.java`, `Store.java`, `Snapshot.java`
- Test: `java/test/truehordes/test/StoreTest.java`; Modify: `TestRunner.java` (add `StoreTest.class`)

**Interfaces:**
- Produces: `Entity(long id, byte kind, float x, float y, byte z)` with public `id`, `kind`, mutable `x`, `y`, `z`; `Entity.KIND_PLACEHOLDER = 0`. `Store.create(byte kind, float x, float y, byte z) -> Entity`, `Store.restore(Entity)`, `Store.get(long)`, `Store.remove(long) -> boolean`, `Store.removeKind(byte) -> int`, `Store.size()`, `Store.nextId()`, `Store.setNextId(long)`, `Store.all() -> Collection<Entity>`, `Store.clear()`, `Store.snapshot(float minX, float minY, float maxX, float maxY, int maxPoints) -> Snapshot`. `Snapshot` public final fields: `boolean density`, `int cellSize`, `float[] data` (points: x,y pairs; density: cellOriginX, cellOriginY, count triples), `int items` (pairs or triples in `data`), `int total`, `int maxCount`.

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/StoreTest.java`:
```java
package truehordes.test;

import truehordes.store.Entity;
import truehordes.store.Snapshot;
import truehordes.store.Store;

public final class StoreTest {
    public static void testIdsAreStableAndNeverReused() {
        Store s = new Store();
        Entity a = s.create(Entity.KIND_PLACEHOLDER, 1, 2, (byte) 0);
        Entity b = s.create(Entity.KIND_PLACEHOLDER, 3, 4, (byte) 0);
        Check.equal(1L, a.id, "first id");
        Check.equal(2L, b.id, "second id");
        Check.isTrue(s.remove(a.id), "removed");
        Entity c = s.create(Entity.KIND_PLACEHOLDER, 5, 6, (byte) 0);
        Check.equal(3L, c.id, "ids are not reused");
        Check.equal(2, s.size(), "size");
    }

    public static void testRestoreKeepsIdsAndAdvancesCounter() {
        Store s = new Store();
        s.restore(new Entity(40, Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0));
        Check.equal(41L, s.nextId(), "next id after restored max");
        Check.throwsAny(() -> s.restore(new Entity(40, Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0)), "duplicate id rejected");
        s.setNextId(10);
        Check.equal(41L, s.nextId(), "never moves backwards");
    }

    public static void testRemoveKindAndClear() {
        Store s = new Store();
        s.create(Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0);
        s.create((byte) 5, 0, 0, (byte) 0);
        Check.equal(1, s.removeKind(Entity.KIND_PLACEHOLDER), "removed placeholders");
        Check.equal(1, s.size(), "other kind kept");
        s.clear();
        Check.equal(0, s.size(), "cleared");
        Check.equal(1L, s.nextId(), "counter reset on clear");
    }

    public static void testSnapshotPointsInsideRectangleOnly() {
        Store s = new Store();
        s.create(Entity.KIND_PLACEHOLDER, 10, 10, (byte) 0);
        s.create(Entity.KIND_PLACEHOLDER, 50, 50, (byte) 0);
        s.create(Entity.KIND_PLACEHOLDER, 500, 500, (byte) 0);
        Snapshot snap = s.snapshot(0, 0, 100, 100, 10);
        Check.isTrue(!snap.density, "points mode");
        Check.equal(2, snap.items, "two points");
        Check.equal(2, snap.total, "total in view");
        Check.near(10, snap.data[0], 0, "x of first");
        Check.near(50, snap.data[3], 0, "y of second");
    }

    public static void testSnapshotSwitchesToDensity() {
        Store s = new Store();
        for (int i = 0; i < 100; i++) s.create(Entity.KIND_PLACEHOLDER, 3 + (i % 2), 3, (byte) 0);
        for (int i = 0; i < 20; i++) s.create(Entity.KIND_PLACEHOLDER, 200, 200, (byte) 0);
        Snapshot snap = s.snapshot(0, 0, 256, 256, 50);
        Check.isTrue(snap.density, "density mode when too many points");
        Check.equal(120, snap.total, "all counted");
        Check.isTrue(snap.cellSize >= 8, "cell size at least 8");
        Check.isTrue(snap.items * 3 == snap.data.length, "triples");
        Check.equal(100, snap.maxCount, "densest cell");
        int sum = 0;
        for (int i = 2; i < snap.data.length; i += 3) sum += (int) snap.data[i];
        Check.equal(120, sum, "counts add up");
        Check.isTrue(snap.items <= 50, "no more cells than maxPoints");
    }
}
```
Modify `TestRunner.SUITES` to add `StoreTest.class,`.

- [ ] **Step 2: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL, "test compile failed" (package truehordes.store does not exist).

- [ ] **Step 3: Implement**

`java/src/truehordes/store/Entity.java`:
```java
package truehordes.store;

/** One simulated object. Sub-project 1 only has placeholders; later sub-projects add kinds and fields. */
public final class Entity {
    public static final byte KIND_PLACEHOLDER = 0;

    public final long id;
    public final byte kind;
    public float x;
    public float y;
    public byte z;

    public Entity(long id, byte kind, float x, float y, byte z) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
```

`java/src/truehordes/store/Snapshot.java`:
```java
package truehordes.store;

/**
 * What the debug map draws. Points mode: data holds x,y pairs. Density mode: data holds
 * cellOriginX, cellOriginY, count triples for square cells of cellSize tiles.
 */
public final class Snapshot {
    public final boolean density;
    public final int cellSize;
    public final float[] data;
    public final int items;
    public final int total;
    public final int maxCount;

    public Snapshot(boolean density, int cellSize, float[] data, int items, int total, int maxCount) {
        this.density = density;
        this.cellSize = cellSize;
        this.data = data;
        this.items = items;
        this.total = total;
        this.maxCount = maxCount;
    }
}
```

`java/src/truehordes/store/Store.java`:
```java
package truehordes.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of simulated entities with stable IDs. Main thread only. */
public final class Store {
    private final Map<Long, Entity> byId = new LinkedHashMap<>();
    private long nextId = 1;

    public Entity create(byte kind, float x, float y, byte z) {
        Entity e = new Entity(nextId++, kind, x, y, z);
        byId.put(e.id, e);
        return e;
    }

    /** Re-adds a loaded entity with its saved ID. */
    public void restore(Entity e) {
        if (byId.putIfAbsent(e.id, e) != null) throw new IllegalStateException("duplicate entity id " + e.id);
        if (e.id >= nextId) nextId = e.id + 1;
    }

    public Entity get(long id) { return byId.get(id); }

    public boolean remove(long id) { return byId.remove(id) != null; }

    public int removeKind(byte kind) {
        int before = byId.size();
        byId.values().removeIf(e -> e.kind == kind);
        return before - byId.size();
    }

    public int size() { return byId.size(); }

    public long nextId() { return nextId; }

    /** Raises the ID counter (from a save file); never lowers it. */
    public void setNextId(long n) { nextId = Math.max(nextId, n); }

    public Collection<Entity> all() { return Collections.unmodifiableCollection(byId.values()); }

    public void clear() {
        byId.clear();
        nextId = 1;
    }

    /** Entities inside [minX,maxX) x [minY,maxY): as points if there are at most maxPoints, else counts per cell. */
    public Snapshot snapshot(float minX, float minY, float maxX, float maxY, int maxPoints) {
        int limit = Math.max(1, maxPoints);
        List<Entity> in = new ArrayList<>();
        for (Entity e : byId.values()) {
            if (e.x >= minX && e.x < maxX && e.y >= minY && e.y < maxY) in.add(e);
        }
        if (in.size() <= limit) {
            float[] d = new float[in.size() * 2];
            for (int i = 0; i < in.size(); i++) {
                d[i * 2] = in.get(i).x;
                d[i * 2 + 1] = in.get(i).y;
            }
            return new Snapshot(false, 0, d, in.size(), in.size(), 1);
        }
        int cell = 8;
        double w = Math.max(1, maxX - minX);
        double h = Math.max(1, maxY - minY);
        while ((Math.ceil(w / cell) + 1) * (Math.ceil(h / cell) + 1) > limit) cell *= 2;
        Map<Long, int[]> counts = new HashMap<>();
        for (Entity e : in) {
            int cx = (int) Math.floor(e.x / cell);
            int cy = (int) Math.floor(e.y / cell);
            long key = ((long) cx << 32) ^ (cy & 0xffffffffL);
            counts.computeIfAbsent(key, k -> new int[] {cx, cy, 0})[2]++;
        }
        float[] d = new float[counts.size() * 3];
        int i = 0;
        int max = 0;
        for (int[] c : counts.values()) {
            d[i++] = (float) c[0] * cell;
            d[i++] = (float) c[1] * cell;
            d[i++] = c[2];
            max = Math.max(max, c[2]);
        }
        return new Snapshot(true, cell, d, counts.size(), in.size(), max);
    }
}
```

- [ ] **Step 4: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `17 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java
git commit -m "SP1: entity store and debug-map snapshots

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Crash-safe, versioned save file

**Files:**
- Create: `java/src/truehordes/save/SaveFile.java`, `LoadResult.java`
- Test: `java/test/truehordes/test/SaveFileTest.java`; Modify: `TestRunner.java` (add `SaveFileTest.class`)

**Interfaces:**
- Consumes: `Store`, `Entity` (Task 4); `Log` (Task 2).
- Produces: `SaveFile.MAGIC`, `SaveFile.VERSION = 1`, `SaveFile.write(Path file, Store store, double worldAgeHours) throws IOException`, `SaveFile.read(Path file) -> LoadResult`, `SaveFile.backupOf(Path) -> Path`. `LoadResult` fields: `Status status` (`OK`, `MISSING`, `RECOVERED`, `FAILED`), `double worldAgeHours` (NaN unless OK/RECOVERED), `long nextId`, `List<Entity> entities`, `String message`.

File format v1 (big-endian, `DataOutputStream`): `int MAGIC` ("THRD" = 0x54485244), `int version`, `double worldAgeHours`, `long savedAtMillis`, `long nextId`, `int count`, then `count` x (`long id`, `byte kind`, `float x`, `float y`, `byte z`), then `long crc32` of all preceding bytes.

- [ ] **Step 1: Write the failing tests**

`java/test/truehordes/test/SaveFileTest.java`:
```java
package truehordes.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import truehordes.save.LoadResult;
import truehordes.save.SaveFile;
import truehordes.store.Entity;
import truehordes.store.Store;
import truehordes.util.Log;

public final class SaveFileTest {
    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("th-save-test");
    }

    private static Store sample() {
        Store s = new Store();
        s.create(Entity.KIND_PLACEHOLDER, 10.5f, 20.25f, (byte) 0);
        s.create((byte) 3, -5f, 7f, (byte) 1);
        s.remove(1);
        s.create(Entity.KIND_PLACEHOLDER, 1f, 2f, (byte) 0);
        return s;
    }

    public static void testRoundTripIsExact() throws IOException {
        Path f = tempDir().resolve("truehordes/state.bin");
        Store s = sample();
        SaveFile.write(f, s, 123.5);
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.OK, r.status, "status");
        Check.near(123.5, r.worldAgeHours, 0, "world age stored");
        Check.equal(s.nextId(), r.nextId, "next id stored");
        Check.equal(2, r.entities.size(), "entity count");
        Entity e = r.entities.get(0);
        Check.equal(2L, e.id, "id kept");
        Check.equal((byte) 3, e.kind, "kind kept");
        Check.near(-5f, e.x, 0, "x exact");
        Check.equal((byte) 1, e.z, "z kept");
        // Save the loaded data again: bytes must be identical apart from the savedAtMillis field (offset 16..23).
        Store again = new Store();
        for (Entity x : r.entities) again.restore(x);
        again.setNextId(r.nextId);
        Path g = f.resolveSibling("again.bin");
        SaveFile.write(g, again, 123.5);
        byte[] a = Files.readAllBytes(f);
        byte[] b = Files.readAllBytes(g);
        Check.equal(a.length, b.length, "same length");
        Check.isTrue(Arrays.equals(Arrays.copyOfRange(a, 0, 16), Arrays.copyOfRange(b, 0, 16)), "same header start");
        Check.isTrue(Arrays.equals(Arrays.copyOfRange(a, 24, a.length - 8), Arrays.copyOfRange(b, 24, b.length - 8)), "same body");
    }

    public static void testMissingFileIsEmptyNotError() throws IOException {
        LoadResult r = SaveFile.read(tempDir().resolve("state.bin"));
        Check.equal(LoadResult.Status.MISSING, r.status, "missing");
        Check.equal(0, r.entities.size(), "empty");
    }

    public static void testSecondWriteKeepsBackupOfFirst() throws IOException {
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        SaveFile.write(f, s, 1.0);
        s.create(Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0);
        SaveFile.write(f, s, 2.0);
        Check.isTrue(Files.exists(SaveFile.backupOf(f)), "backup exists");
        Check.near(1.0, SaveFile.read(SaveFile.backupOf(f)).worldAgeHours, 0, "backup is the previous save");
        Check.near(2.0, SaveFile.read(f).worldAgeHours, 0, "main is the newest save");
        Check.isTrue(!Files.exists(f.resolveSibling("state.bin.tmp")), "no temp file left");
    }

    public static void testTruncatedFileRecoversFromBackup() throws IOException {
        Log.setSink(line -> {});
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        SaveFile.write(f, s, 1.0);
        SaveFile.write(f, s, 2.0);
        byte[] bytes = Files.readAllBytes(f);
        Files.write(f, Arrays.copyOf(bytes, bytes.length / 2));
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.RECOVERED, r.status, "recovered from backup");
        Check.near(1.0, r.worldAgeHours, 0, "backup data");
        Check.isTrue(!Files.exists(f), "bad file moved aside");
        try (var listing = Files.list(f.getParent())) {
            Check.isTrue(listing.anyMatch(p -> p.getFileName().toString().startsWith("state.bin.bad-")), "bad copy kept");
        }
        Log.setSink(null);
    }

    public static void testCorruptWithoutBackupStartsEmpty() throws IOException {
        Log.setSink(line -> {});
        Path f = tempDir().resolve("state.bin");
        Files.write(f, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.FAILED, r.status, "failed");
        Check.equal(0, r.entities.size(), "empty");
        Check.isTrue(r.message.length() > 0, "explains why");
        Log.setSink(null);
    }

    public static void testNewerVersionIsSetAside() throws IOException {
        Log.setSink(line -> {});
        Path f = tempDir().resolve("state.bin");
        SaveFile.write(f, sample(), 1.0);
        byte[] bytes = Files.readAllBytes(f);
        bytes[7] = 99; // version int low byte -> 99
        // Re-stamp the CRC so only the version is "wrong".
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        java.nio.ByteBuffer.wrap(bytes, bytes.length - 8, 8).putLong(crc.getValue());
        Files.write(f, bytes);
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.FAILED, r.status, "newer version not loaded");
        Check.isTrue(r.message.contains("newer"), "says it is newer: " + r.message);
        Check.isTrue(!Files.exists(f), "kept aside, not overwritten later");
        Log.setSink(null);
    }
}
```
Modify `TestRunner.SUITES` to add `SaveFileTest.class,`.

- [ ] **Step 2: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL, "test compile failed" (package truehordes.save does not exist).

- [ ] **Step 3: Implement**

`java/src/truehordes/save/LoadResult.java`:
```java
package truehordes.save;

import java.util.List;
import truehordes.store.Entity;

/** Outcome of reading the mod's save file. */
public final class LoadResult {
    public enum Status { OK, MISSING, RECOVERED, FAILED }

    public final Status status;
    public final double worldAgeHours;
    public final long nextId;
    public final List<Entity> entities;
    public final String message;

    LoadResult(Status status, double worldAgeHours, long nextId, List<Entity> entities, String message) {
        this.status = status;
        this.worldAgeHours = worldAgeHours;
        this.nextId = nextId;
        this.entities = entities;
        this.message = message;
    }

    static LoadResult empty(Status status, String message) {
        return new LoadResult(status, Double.NaN, 1, List.of(), message);
    }
}
```

`java/src/truehordes/save/SaveFile.java`:
```java
package truehordes.save;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import truehordes.store.Entity;
import truehordes.store.Store;
import truehordes.util.Log;

/**
 * The mod's own save file, written in the same save cycle as the world.
 * Writes go to "<file>.tmp" (flushed to disk), the previous file is copied to "<file>.bak", then the temp file
 * replaces the main one atomically, so a half-written file never replaces a good one.
 */
public final class SaveFile {
    public static final int MAGIC = 0x54485244; // "THRD"
    public static final int VERSION = 1;
    private static final int HEADER_BYTES = 4 + 4 + 8 + 8 + 8 + 4;
    private static final int ENTITY_BYTES = 8 + 1 + 4 + 4 + 1;

    private SaveFile() {}

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    public static void write(Path file, Store store, double worldAgeHours) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES + store.size() * ENTITY_BYTES + 8);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeDouble(worldAgeHours);
        out.writeLong(System.currentTimeMillis());
        out.writeLong(store.nextId());
        out.writeInt(store.size());
        for (Entity e : store.all()) {
            out.writeLong(e.id);
            out.writeByte(e.kind);
            out.writeFloat(e.x);
            out.writeFloat(e.y);
            out.writeByte(e.z);
        }
        out.flush();
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue());
        out.flush();

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes.toByteArray());
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        if (Files.exists(file)) Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static LoadResult read(Path file) {
        Path bak = backupOf(file);
        if (!Files.exists(file) && !Files.exists(bak)) return LoadResult.empty(LoadResult.Status.MISSING, "no save file yet");
        String problem;
        if (Files.exists(file)) {
            try {
                return decode(file, LoadResult.Status.OK);
            } catch (IOException | RuntimeException e) {
                problem = file.getFileName() + " unreadable (" + e.getMessage() + ")";
                setAside(file);
            }
        } else {
            problem = file.getFileName() + " missing";
        }
        if (Files.exists(bak)) {
            try {
                LoadResult r = decode(bak, LoadResult.Status.RECOVERED);
                Log.warn(problem + "; loaded the backup instead");
                return new LoadResult(r.status, r.worldAgeHours, r.nextId, r.entities, problem + "; loaded backup");
            } catch (IOException | RuntimeException e) {
                problem += "; backup unreadable too (" + e.getMessage() + ")";
            }
        }
        Log.warn(problem + "; starting empty");
        return LoadResult.empty(LoadResult.Status.FAILED, problem);
    }

    private static LoadResult decode(Path file, LoadResult.Status okStatus) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length < HEADER_BYTES + 8) throw new IOException("file too short");
        CRC32 crc = new CRC32();
        crc.update(all, 0, all.length - 8);
        long stored = ByteBuffer.wrap(all, all.length - 8, 8).getLong();
        if (stored != crc.getValue()) throw new IOException("checksum mismatch");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(all, 0, all.length - 8));
        if (in.readInt() != MAGIC) throw new IOException("not a True Hordes save");
        int version = in.readInt();
        if (version > VERSION) throw new IOException("made by a newer version of True Hordes (" + version + ")");
        switch (version) {
            case 1:
                return decodeV1(in, okStatus);
            default:
                throw new IOException("unsupported save version " + version);
        }
    }

    private static LoadResult decodeV1(DataInputStream in, LoadResult.Status okStatus) throws IOException {
        double worldAge = in.readDouble();
        in.readLong(); // savedAtMillis: informational
        long nextId = in.readLong();
        int count = in.readInt();
        if (count < 0) throw new IOException("negative entity count");
        List<Entity> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entities.add(new Entity(in.readLong(), in.readByte(), in.readFloat(), in.readFloat(), in.readByte()));
        }
        if (in.available() != 0) throw new IOException("unexpected trailing data");
        return new LoadResult(okStatus, worldAge, nextId, entities, okStatus == LoadResult.Status.OK ? "loaded" : "loaded backup");
    }

    private static void setAside(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
            Log.warn("kept the unreadable save as " + bad.getFileName());
        } catch (IOException e) {
            Log.error("could not move the unreadable save aside", e);
        }
    }
}
```

- [ ] **Step 4: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `23 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java
git commit -m "SP1: crash-safe versioned save file with backup recovery

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Core lifecycle

**Files:**
- Create: `java/src/truehordes/core/GameEnv.java`, `Core.java`
- Test: `java/test/truehordes/test/FakeEnv.java`, `CoreTest.java`; Modify: `TestRunner.java` (add `CoreTest.class`)

**Interfaces:**
- Consumes: `SimClock`, `Scheduler` (Task 3); `Store`, `Entity` (Task 4); `SaveFile`, `LoadResult` (Task 5); `Log`, `Stats` (Task 2).
- Produces: `GameEnv` (`boolean isClient()`, `boolean isServer()`, `boolean isWorldReady()`, `double worldAgeHours()`, `Path stateFile()`, `boolean enabled()`, `boolean debugLogging()`). `Core(GameEnv)`, `Core.State` (`IDLE`, `RUNNING`, `OFF`, `DISABLED`), `Core.tick()`, `Core.save() -> boolean`, `Core.stop()`, `Core.disable(String reason)`, `Core.state()`, `Core.store()`, `Core.stats()`, `Core.scheduler()`, `Core.status() -> String`, `Core.seedTest(int count, float centerX, float centerY, float radius, Random) -> int`, `Core.clearTest() -> int`, `Core.MISMATCH_HOURS = 0.25`.

- [ ] **Step 1: Write the fake environment and the failing tests**

`java/test/truehordes/test/FakeEnv.java`:
```java
package truehordes.test;

import java.nio.file.Path;
import truehordes.core.GameEnv;

/** Test stand-in for the game. */
public final class FakeEnv implements GameEnv {
    public boolean client;
    public boolean server;
    public boolean worldReady = true;
    public double worldAgeHours = 100.0;
    public Path stateFile;
    public boolean enabled = true;
    public boolean debugLogging;

    public FakeEnv(Path stateFile) { this.stateFile = stateFile; }

    @Override public boolean isClient() { return client; }
    @Override public boolean isServer() { return server; }
    @Override public boolean isWorldReady() { return worldReady; }
    @Override public double worldAgeHours() { return worldAgeHours; }
    @Override public Path stateFile() { return stateFile; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean debugLogging() { return debugLogging; }
}
```

`java/test/truehordes/test/CoreTest.java`:
```java
package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import truehordes.core.Core;
import truehordes.save.SaveFile;
import truehordes.util.Log;

public final class CoreTest {
    private static Path file() throws Exception {
        return Files.createTempDirectory("th-core-test").resolve("truehordes/state.bin");
    }

    public static void testStartsOnFirstTickAndRunsSystems() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        List<Double> runs = new ArrayList<>();
        core.scheduler().register("probe", 0, runs::add);
        Check.equal(Core.State.IDLE, core.state(), "idle before first tick");
        core.tick();
        Check.equal(Core.State.RUNNING, core.state(), "running after first tick");
        env.worldAgeHours += 0.5;
        core.tick();
        Check.equal(1, runs.size(), "system ran once time moved");
        Check.near(0.5, runs.get(0), 1e-12, "elapsed game time");
    }

    public static void testWaitsForWorld() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.worldReady = false;
        Core core = new Core(env);
        core.tick();
        Check.equal(Core.State.IDLE, core.state(), "no world yet");
    }

    public static void testSaveThenRestartRestoresEntities() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        Check.equal(500, core.seedTest(500, 1000, 2000, 300, new Random(1)), "seeded");
        Check.isTrue(core.save(), "saved");
        core.stop();
        Check.equal(0, core.store().size(), "store cleared on stop");
        core.tick();
        Check.equal(500, core.store().size(), "restored after restart");
        Check.equal(500, core.clearTest(), "cleared placeholders");
    }

    public static void testStopThenStartOtherSaveDoesNotLeak() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        core.seedTest(10, 0, 0, 50, new Random(2));
        core.save();
        core.stop();
        env.stateFile = file();          // a different save
        env.worldAgeHours = 5.0;
        core.tick();
        Check.equal(0, core.store().size(), "nothing from the other save");
        env.worldAgeHours = 5.25;
        List<Double> runs = new ArrayList<>();
        core.scheduler().register("probe", 0, runs::add);
        core.tick();
        Check.near(0.25, runs.get(0), 1e-12, "clock restarted from the new world's time");
    }

    public static void testMismatchedSaveTimeWarnsButLoads() throws Exception {
        List<String> lines = new ArrayList<>();
        Log.setSink(lines::add);
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        core.seedTest(3, 0, 0, 10, new Random(3));
        core.save();               // mod save at hour 100
        core.stop();
        env.worldAgeHours = 104.0; // world save from 4 hours later (e.g. crash between the two writes)
        core.tick();
        Check.equal(3, core.store().size(), "still loads the newest valid file");
        Check.isTrue(lines.stream().anyMatch(l -> l.contains("WARN") && l.contains("world")), "warned about mismatch");
        Log.setSink(null);
    }

    public static void testClientNeverRunsOrWrites() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.client = true;
        Core core = new Core(env);
        core.tick();
        Check.equal(Core.State.IDLE, core.state(), "client stays idle");
        Check.isTrue(!core.save(), "client does not save");
        Check.isTrue(!Files.exists(env.stateFile), "no file written");
    }

    public static void testSandboxDisabledStaysOff() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.enabled = false;
        Core core = new Core(env);
        core.tick();
        Check.equal(Core.State.OFF, core.state(), "off by sandbox");
        Check.isTrue(!core.save(), "no save when off");
        Check.isTrue(!Files.exists(env.stateFile), "no file written");
        core.stop();
        env.enabled = true;
        core.tick();
        Check.equal(Core.State.RUNNING, core.state(), "next world can run");
    }

    public static void testDisabledIsPermanent() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.disable("missing game method");
        core.tick();
        core.stop();
        core.tick();
        Check.equal(Core.State.DISABLED, core.state(), "stays disabled");
        Check.isTrue(core.status().contains("missing game method"), "status explains");
    }

    public static void testSeedOnlyWhileRunning() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        Check.equal(0, core.seedTest(5, 0, 0, 10, new Random(4)), "not running yet");
        core.tick();
        Check.equal(5, core.seedTest(5, 0, 0, 10, new Random(4)), "running");
        Check.isTrue(Files.notExists(SaveFile.backupOf(env.stateFile)), "seeding does not save");
    }
}
```
Modify `TestRunner.SUITES` to add `CoreTest.class,`.

- [ ] **Step 2: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL, "test compile failed" (package truehordes.core does not exist).

- [ ] **Step 3: Implement**

`java/src/truehordes/core/GameEnv.java`:
```java
package truehordes.core;

import java.nio.file.Path;

/** What the core needs from the game. The game-backed implementation is truehordes.GameEnvImpl. */
public interface GameEnv {
    boolean isClient();

    boolean isServer();

    /** True once a world is loaded and has a current cell. */
    boolean isWorldReady();

    double worldAgeHours();

    /** The mod's save file inside the current save folder. */
    Path stateFile();

    /** Sandbox TrueHordes.Enabled. */
    boolean enabled();

    /** Sandbox TrueHordes.DebugLogging. */
    boolean debugLogging();
}
```

`java/src/truehordes/core/Core.java`:
```java
package truehordes.core;

import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import truehordes.save.LoadResult;
import truehordes.save.SaveFile;
import truehordes.sim.Scheduler;
import truehordes.sim.SimClock;
import truehordes.store.Entity;
import truehordes.store.Store;
import truehordes.util.Log;
import truehordes.util.Stats;

/**
 * Lifecycle of the simulation for one loaded world. Starts on the first tick after a world is ready, saves when the
 * game saves, clears everything on stop. Only the machine that owns the simulation runs it (never an MP client).
 */
public final class Core {
    public enum State { IDLE, RUNNING, OFF, DISABLED }

    /** A mod save more than this many in-game hours away from the world's time is reported. */
    public static final double MISMATCH_HOURS = 0.25;

    private final GameEnv env;
    private final Store store = new Store();
    private final Stats stats = new Stats();
    private final SimClock clock = new SimClock();
    private final Scheduler scheduler = new Scheduler(stats);
    private State state = State.IDLE;
    private String disabledReason = "";
    private String lastLoad = "";
    private double lastSaveHours = Double.NaN;

    public Core(GameEnv env) {
        this.env = env;
    }

    public State state() { return state; }

    public Store store() { return store; }

    public Stats stats() { return stats; }

    public Scheduler scheduler() { return scheduler; }

    /** Permanently switches the mod off for this game session. */
    public void disable(String reason) {
        state = State.DISABLED;
        disabledReason = reason;
        Log.warn("disabled: " + reason);
    }

    public void tick() {
        if (state == State.DISABLED || state == State.OFF || env.isClient() || !env.isWorldReady()) return;
        if (state == State.IDLE && !start()) return;
        long t0 = System.nanoTime();
        scheduler.run(clock.advance(env.worldAgeHours()));
        stats.count("entities", store.size());
        stats.time("tick", System.nanoTime() - t0);
    }

    private boolean start() {
        if (!env.enabled()) {
            state = State.OFF;
            Log.info("switched off in sandbox options for this world");
            return false;
        }
        Log.setDebug("core", env.debugLogging());
        LoadResult r = SaveFile.read(env.stateFile());
        store.clear();
        for (Entity e : r.entities) store.restore(e);
        store.setNextId(r.nextId);
        double now = env.worldAgeHours();
        if (r.status == LoadResult.Status.OK || r.status == LoadResult.Status.RECOVERED) {
            double diff = now - r.worldAgeHours;
            if (Math.abs(diff) > MISMATCH_HOURS) {
                Log.warn(String.format(Locale.ROOT,
                    "mod save is from world hour %.2f but the world is at hour %.2f (%.2f h apart); using it anyway",
                    r.worldAgeHours, now, diff));
            }
        }
        lastLoad = r.status + ": " + r.message;
        clock.reset(now);
        state = State.RUNNING;
        Log.info("started with " + store.size() + " entities (" + lastLoad + ")");
        return true;
    }

    /** Writes the mod's state; called from the game's save. Returns true if a file was written. */
    public boolean save() {
        if (state != State.RUNNING || env.isClient()) return false;
        try {
            SaveFile.write(env.stateFile(), store, env.worldAgeHours());
            lastSaveHours = env.worldAgeHours();
            Log.debug("core", "saved " + store.size() + " entities");
            return true;
        } catch (IOException e) {
            Log.error("could not write the mod's save file " + env.stateFile(), e);
            return false;
        }
    }

    /** World unloaded: forget everything (no save here; the game's save already ran). */
    public void stop() {
        if (state == State.RUNNING) Log.info("stopped");
        if (state != State.DISABLED) state = State.IDLE;
        store.clear();
        clock.clear();
        stats.clear();
        lastSaveHours = Double.NaN;
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("True Hordes ").append(truehordesVersion()).append(" | state ").append(state);
        if (state == State.DISABLED) sb.append(" (").append(disabledReason).append(')');
        sb.append(" | mode ").append(env.isClient() ? "MP client" : env.isServer() ? "dedicated server" : "single player");
        sb.append(" | entities ").append(store.size());
        sb.append(" | last load ").append(lastLoad.isEmpty() ? "-" : lastLoad);
        sb.append(" | last save ").append(Double.isNaN(lastSaveHours) ? "-" : String.format(Locale.ROOT, "world hour %.2f", lastSaveHours));
        return sb.toString();
    }

    private static String truehordesVersion() {
        return truehordes.Version.VERSION;
    }

    /** Test only: adds placeholder entities scattered in a disc. Returns how many were added (0 if not running). */
    public int seedTest(int count, float centerX, float centerY, float radius, Random random) {
        if (state != State.RUNNING || count <= 0) return 0;
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(random.nextDouble()) * radius;
            store.create(Entity.KIND_PLACEHOLDER, (float) (centerX + Math.cos(angle) * dist),
                (float) (centerY + Math.sin(angle) * dist), (byte) 0);
        }
        return count;
    }

    /** Test only: removes placeholder entities. */
    public int clearTest() {
        return store.removeKind(Entity.KIND_PLACEHOLDER);
    }
}
```

Note: `truehordes.Version` is a pure class with no game imports, so the core may reference it.

- [ ] **Step 4: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `32 passed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add java
git commit -m "SP1: core lifecycle with save matching and client/sandbox guards

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Game bridge (hooks, environment, entry point, target check)

**Files:**
- Create: `java/src/truehordes/HookTargets.java`, `GameEnvImpl.java`, `Hooks.java`, `Patches.java`, `Main.java`
- Test: `java/test/truehordes/test/ClassFileMethods.java`, `VerifyTargetsTest.java`; Modify: `TestRunner.java` (add `VerifyTargetsTest.class`)

**Interfaces:**
- Consumes: `Core`, `GameEnv` (Task 6); `Log` (Task 2); `Version` (Task 1).
- Produces: `HookTargets.POPMAN`, `HookTargets.MCD`, `HookTargets.TICK`, `HookTargets.SAVE`, `HookTargets.STOP` (String constants usable in annotations), `HookTargets.ALL` (`String[][]` of {class, method}), `HookTargets.missing(Function<String, Set<String>> methodsOfClass) -> List<String>`. `Hooks.core()` -> `Core` (null until installed), `Hooks.install(Core)`, `Hooks.onTick()`, `Hooks.onSave()`, `Hooks.onStop()`. `Main.main(String[])`. `GameEnvImpl implements GameEnv`.

Hook choice (verified against the decompiled 42.21 game): `ZombiePopulationManager.updateMain()` runs every frame on single player and every server frame (`IngameState.java:651`, server via `GameServer.java:1007`); `MapCollisionData.save()` is called by both the single-player save (`GameWindow.java:1219`) and the server save (`ServerMap.java:393`), at the same moment the vanilla population saves; `ZombiePopulationManager.stop()` runs on exit (`IngameState.java:902`, `GameWindow.java:906`, `ServerMap.java:355`). None of the three names is overloaded. On MP clients they run too, and `Core` stays idle there.

- [ ] **Step 1: Write the class-file reader and the failing test**

`java/test/truehordes/test/ClassFileMethods.java`:
```java
package truehordes.test;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads method names from a class file inside a jar without loading the class (no game dependencies needed). */
public final class ClassFileMethods {
    private ClassFileMethods() {}

    public static Set<String> methodNames(ZipFile jar, String className) throws IOException {
        ZipEntry entry = jar.getEntry(className.replace('.', '/') + ".class");
        if (entry == null) return Set.of();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(jar.getInputStream(entry)))) {
            if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file: " + className);
            in.readUnsignedShort();
            in.readUnsignedShort();
            int poolSize = in.readUnsignedShort();
            String[] utf8 = new String[poolSize];
            for (int i = 1; i < poolSize; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; }
                    case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("unknown constant pool tag " + tag + " in " + className);
                }
            }
            in.skipNBytes(6); // access, this, super
            in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
            int fields = in.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                in.skipNBytes(6);
                skipAttributes(in);
            }
            int methods = in.readUnsignedShort();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < methods; i++) {
                in.skipNBytes(2);
                names.add(utf8[in.readUnsignedShort()]);
                in.skipNBytes(2);
                skipAttributes(in);
            }
            return names;
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipNBytes(2);
            in.skipNBytes(in.readInt() & 0xffffffffL);
        }
    }
}
```

`java/test/truehordes/test/VerifyTargetsTest.java`:
```java
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
```
Modify `TestRunner.SUITES` to add `VerifyTargetsTest.class,`.

- [ ] **Step 2: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL, "test compile failed" (cannot find symbol HookTargets).

- [ ] **Step 3: Implement**

`java/src/truehordes/HookTargets.java`:
```java
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
```

`java/src/truehordes/GameEnvImpl.java`:
```java
package truehordes;

import java.nio.file.Path;
import java.nio.file.Paths;
import truehordes.core.GameEnv;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.ZomboidFileSystem;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;

/** GameEnv backed by the running game. */
final class GameEnvImpl implements GameEnv {
    @Override
    public boolean isClient() { return GameClient.client; }

    @Override
    public boolean isServer() { return GameServer.server; }

    @Override
    public boolean isWorldReady() { return IsoWorld.instance != null && IsoWorld.instance.currentCell != null; }

    @Override
    public double worldAgeHours() { return GameTime.getInstance().getWorldAgeHours(); }

    @Override
    public Path stateFile() {
        return Paths.get(ZomboidFileSystem.instance.getFileNameInCurrentSave("truehordes", "state.bin"));
    }

    @Override
    public boolean enabled() { return sandboxBool("TrueHordes.Enabled", true); }

    @Override
    public boolean debugLogging() { return sandboxBool("TrueHordes.DebugLogging", false); }

    private static boolean sandboxBool(String name, boolean fallback) {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(name);
            return option == null ? fallback : Boolean.parseBoolean(option.asConfigOption().getValueAsString());
        } catch (Throwable t) {
            return fallback;
        }
    }
}
```

`java/src/truehordes/Hooks.java`:
```java
package truehordes;

import truehordes.core.Core;
import truehordes.util.Log;

/** The only methods patch advice calls. Every call is wrapped so nothing throws into the game. */
public final class Hooks {
    private static volatile Core core;

    private Hooks() {}

    public static Core core() { return core; }

    static void install(Core c) { core = c; }

    public static void onTick() {
        Core c = core;
        if (c == null) return;
        try {
            c.tick();
        } catch (Throwable t) {
            Log.errorOnce("hook:tick:" + t.getClass().getName(), "update failed", t);
        }
    }

    public static void onSave() {
        Core c = core;
        if (c == null) return;
        try {
            c.save();
        } catch (Throwable t) {
            Log.errorOnce("hook:save:" + t.getClass().getName(), "save failed", t);
        }
    }

    public static void onStop() {
        Core c = core;
        if (c == null) return;
        try {
            c.stop();
        } catch (Throwable t) {
            Log.errorOnce("hook:stop:" + t.getClass().getName(), "stop failed", t);
        }
    }
}
```

`java/src/truehordes/Patches.java`:
```java
package truehordes;

import me.zed_0xff.zombie_buddy.Patch;

/** ZombieBuddy patches. Must stay directly in package truehordes (ZombieBuddy does not scan sub-packages). */
public final class Patches {
    private Patches() {}

    /** Every frame (single player) or server frame, after the vanilla population update. */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.TICK)
    public static class Tick {
        @Patch.OnExit
        public static void exit() { Hooks.onTick(); }
    }

    /** Called from the single-player and the server world save, where the vanilla population also saves. */
    @Patch(className = HookTargets.MCD, methodName = HookTargets.SAVE)
    public static class Save {
        @Patch.OnExit
        public static void exit() { Hooks.onSave(); }
    }

    /** World unloading (exit to menu, quit, server shutdown). */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.STOP)
    public static class Stop {
        @Patch.OnEnter
        public static void enter() { Hooks.onStop(); }
    }
}
```

`java/src/truehordes/Main.java`:
```java
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
```

- [ ] **Step 4: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `34 passed, 0 failed` (the game-bridge classes compile against `projectzomboid.jar` and `ZombieBuddy.jar`; `VerifyTargetsTest` reads the installed game jar). `SandboxOptions.SandboxOption` is the game's inner interface (`SandboxOptions.java:2345` in the decompiled 42.21 source).

- [ ] **Step 5: Commit**

```bash
git add java
git commit -m "SP1: game bridge - ZombieBuddy patches, environment, entry point and target check

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Lua bridge and console commands

**Files:**
- Create: `java/src/truehordes/LuaApi.java`
- Create: `mod/Contents/mods/TrueHordes/42/media/lua/shared/TrueHordes/TH_Core.lua`
- Test: `java/test/truehordes/test/LuaArgsTest.java`; Modify: `TestRunner.java` (add `LuaArgsTest.class`)
- Create: `java/src/truehordes/util/LuaArgs.java` (pure argument conversion, testable)

**Interfaces:**
- Consumes: `Hooks.core()` (Task 7); `Core.status()`, `stats()`, `save()`, `seedTest(...)`, `clearTest()`, `store()` (Task 6); `Store.snapshot(...)`, `Snapshot` (Task 4); `Log.setDebug`, `Log.debugCategories` (Task 2).
- Produces: Lua table `TrueHordesJ` with static functions `status()`, `stats()`, `debug(category, on)`, `save()`, `seedTest(n, x, y)`, `clearTest()`, `snapshot(minX, minY, maxX, maxY, maxPoints)`. `snapshot` returns a Lua table: fields `density` (boolean), `cell` (number), `n` (items), `total`, `maxCount`, and array part `1..` holding points (x, y pairs) or cells (originX, originY, count triples); or nil when the core is not running. Lua globals `TH_status()`, `TH_stats()`, `TH_debug(category, on)`, `TH_save()`, `TH_seedTest(n)`, `TH_clearTest()`, and `TrueHordes.snapshot(...)`. `LuaArgs.toInt(Object, int)`, `LuaArgs.toFloat(Object, float)`, `LuaArgs.toBool(Object, boolean)`.

- [ ] **Step 1: Write the failing test**

`java/test/truehordes/test/LuaArgsTest.java`:
```java
package truehordes.test;

import truehordes.util.LuaArgs;

/** Lua passes numbers as Double, booleans as Boolean, anything else as strings or null. */
public final class LuaArgsTest {
    public static void testNumbers() {
        Check.equal(12, LuaArgs.toInt(12.7, 0), "double to int truncates");
        Check.equal(5, LuaArgs.toInt("5", 0), "numeric string");
        Check.equal(3, LuaArgs.toInt(null, 3), "fallback for nil");
        Check.equal(3, LuaArgs.toInt("abc", 3), "fallback for junk");
        Check.near(1.5f, LuaArgs.toFloat(1.5, 0f), 0, "float");
        Check.near(9f, LuaArgs.toFloat(Double.NaN, 9f), 0, "NaN falls back");
    }

    public static void testBooleans() {
        Check.equal(true, LuaArgs.toBool(Boolean.TRUE, false), "Boolean");
        Check.equal(false, LuaArgs.toBool("false", true), "string false");
        Check.equal(true, LuaArgs.toBool(null, true), "nil falls back");
        Check.equal(true, LuaArgs.toBool(1.0, false), "non-zero number is true");
    }
}
```
Modify `TestRunner.SUITES` to add `LuaArgsTest.class,`.

- [ ] **Step 2: Run the build to verify it fails**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: FAIL, "test compile failed" (cannot find symbol LuaArgs).

- [ ] **Step 3: Implement the Java side**

`java/src/truehordes/util/LuaArgs.java`:
```java
package truehordes.util;

/** Defensive conversion of values arriving from Lua (Kahlua passes Double, Boolean, String or null). */
public final class LuaArgs {
    private LuaArgs() {}

    public static int toInt(Object v, int fallback) {
        double d = toDouble(v, Double.NaN);
        return Double.isNaN(d) ? fallback : (int) d;
    }

    public static float toFloat(Object v, float fallback) {
        double d = toDouble(v, Double.NaN);
        return Double.isNaN(d) ? fallback : (float) d;
    }

    public static boolean toBool(Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }

    private static double toDouble(Object v, double fallback) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
```

`java/src/truehordes/LuaApi.java`:
```java
package truehordes;

import java.util.Random;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import truehordes.core.Core;
import truehordes.store.Snapshot;
import truehordes.util.Log;
import truehordes.util.LuaArgs;
import zombie.Lua.LuaManager;

/**
 * Exposed to Lua by ZombieBuddy as the table TrueHordesJ (works whatever the load order). The TH_* console commands
 * in media/lua/shared/TrueHordes/TH_Core.lua forward to it. Never throws into Lua.
 */
@Exposer.LuaClass(name = "TrueHordesJ")
public final class LuaApi {
    private static final String NOT_INSTALLED = "True Hordes Java core is not installed";

    private LuaApi() {}

    @LuaMethod(name = "TH_j_status", global = true)
    public static String status() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.status();
    }

    @LuaMethod(name = "TH_j_stats", global = true)
    public static String stats() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.stats().format();
    }

    @LuaMethod(name = "TH_j_debug", global = true)
    public static String debug(Object category, Object on) {
        String cat = category == null ? "all" : String.valueOf(category);
        boolean value = LuaArgs.toBool(on, true);
        Log.setDebug(cat, value);
        return "debug " + cat + " = " + value + " (on: " + Log.debugCategories() + ")";
    }

    @LuaMethod(name = "TH_j_save", global = true)
    public static String save() {
        Core c = Hooks.core();
        if (c == null) return NOT_INSTALLED;
        try {
            return c.save() ? "saved " + c.store().size() + " entities (debug save; the world was not saved)" : "not saved (state " + c.state() + ")";
        } catch (Throwable t) {
            Log.error("TH_save failed", t);
            return "save failed: " + t;
        }
    }

    @LuaMethod(name = "TH_j_seedTest", global = true)
    public static String seedTest(Object n, Object x, Object y) {
        Core c = Hooks.core();
        if (c == null) return NOT_INSTALLED;
        int added = c.seedTest(LuaArgs.toInt(n, 100), LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0), 300f, new Random());
        return added == 0 ? "not running (state " + c.state() + ")" : "added " + added + " placeholders; total " + c.store().size();
    }

    @LuaMethod(name = "TH_j_clearTest", global = true)
    public static String clearTest() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : "removed " + c.clearTest() + " placeholders";
    }

    /** Debug-map data for a rectangle, or nil when the core is not running. */
    @LuaMethod(name = "TH_j_snapshot", global = true)
    public static KahluaTable snapshot(Object minX, Object minY, Object maxX, Object maxY, Object maxPoints) {
        Core c = Hooks.core();
        if (c == null || c.state() != Core.State.RUNNING) return null;
        try {
            Snapshot s = c.store().snapshot(LuaArgs.toFloat(minX, 0), LuaArgs.toFloat(minY, 0),
                LuaArgs.toFloat(maxX, 0), LuaArgs.toFloat(maxY, 0), LuaArgs.toInt(maxPoints, 2000));
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("density", s.density);
            t.rawset("cell", (double) s.cellSize);
            t.rawset("n", (double) s.items);
            t.rawset("total", (double) s.total);
            t.rawset("maxCount", (double) s.maxCount);
            for (int i = 0; i < s.data.length; i++) t.rawset((double) (i + 1), (double) s.data[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:snapshot:" + t.getClass().getName(), "snapshot failed", t);
            return null;
        }
    }
}
```
(The `TH_j_*` global names avoid clashing with the Lua wrappers below; Lua uses the `TrueHordesJ` table.)

- [ ] **Step 4: Run the build to verify it passes**

Run: `powershell -ExecutionPolicy Bypass -File tools/build.ps1`
Expected: `36 passed, 0 failed`.

- [ ] **Step 5: Write the Lua helper**

`mod/Contents/mods/TrueHordes/42/media/lua/shared/TrueHordes/TH_Core.lua`:
```lua
-- True Hordes: Lua helper.
-- The Java core is exposed by ZombieBuddy as the table TrueHordesJ. It is nil when ZombieBuddy is missing or the
-- jar was not approved; the TH_* console commands then print that instead of failing.

TrueHordes = TrueHordes or {}

local warned = false

local function J()
    return TrueHordesJ
end

local function notLoaded()
    if not warned then
        warned = true
        print("[TrueHordes] WARN Java core is not loaded (ZombieBuddy missing, or the True Hordes jar was not approved).")
    end
    return "True Hordes Java core is NOT loaded"
end

local function call(name, ...)
    local j = J()
    if not j then return notLoaded() end
    local result = j[name](...)
    print(result)
    return result
end

function TH_status() return call("status") end
function TH_stats() return call("stats") end
function TH_debug(category, on) return call("debug", category, on) end
function TH_save() return call("save") end
function TH_clearTest() return call("clearTest") end

function TH_seedTest(n)
    local player = getPlayer()
    if not player then
        print("TH_seedTest: no player")
        return "no player"
    end
    return call("seedTest", n or 100, player:getX(), player:getY())
end

--- Debug-map data for a rectangle (see LuaApi.snapshot), or nil when the Java core is not running.
function TrueHordes.snapshot(minX, minY, maxX, maxY, maxPoints)
    local j = J()
    if not j then return nil end
    return j.snapshot(minX, minY, maxX, maxY, maxPoints)
end

if Events and Events.OnGameStart then
    Events.OnGameStart.Add(function()
        if not J() then notLoaded() end
    end)
end
```

- [ ] **Step 6: Commit**

```bash
git add java mod
git commit -m "SP1: Lua bridge (TrueHordesJ) and TH_* console commands

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Debug map, deploy script and in-game acceptance

**Files:**
- Create: `mod/Contents/mods/TrueHordes/42/media/lua/client/TrueHordes/TH_DebugMap.lua`
- Create: `tools/deploy.ps1`
- Create: `docs/TESTING.md` (in-game acceptance checklist)

**Interfaces:**
- Consumes: `TrueHordes.snapshot(minX, minY, maxX, maxY, maxPoints)` (Task 8); vanilla `ISPanel`, `ISCollapsableWindow` (`new(x, y, w, h)`, `setTitle`, `titleBarHeight`, `close`), `drawRect(x, y, w, h, a, r, g, b)`, `drawText(text, x, y, r, g, b, a, font)`, `getTimestampMs()`, `getPlayer()`.
- Produces: Lua global `TH_map()` (toggles the window), classes `TH_MapPanel`, `TH_DebugMap`.

- [ ] **Step 1: Write the debug map**

`mod/Contents/mods/TrueHordes/42/media/lua/client/TrueHordes/TH_DebugMap.lua`:
```lua
-- True Hordes debug map: draws the Java core's entities around a point.
-- Mouse wheel zooms, left-drag pans, right-click returns to following the player. TH_map() opens or closes it.

require "ISUI/ISPanel"
require "ISUI/ISCollapsableWindow"

local FETCH_MS = 300
local MAX_POINTS = 4000

TH_MapPanel = ISPanel:derive("TH_MapPanel")

function TH_MapPanel:new(x, y, width, height)
    local o = ISPanel.new(self, x, y, width, height)
    o.background = true
    o.backgroundColor = { r = 0.05, g = 0.05, b = 0.05, a = 0.95 }
    o.zoom = 1.0 -- screen pixels per tile
    o.follow = true
    o.centerX = nil
    o.centerY = nil
    o.snapshot = nil
    o.lastFetch = 0
    o.dragging = false
    return o
end

function TH_MapPanel:worldToScreen(wx, wy)
    return (wx - self.centerX) * self.zoom + self.width / 2, (wy - self.centerY) * self.zoom + self.height / 2
end

function TH_MapPanel:fetch()
    local now = getTimestampMs()
    if self.snapshot and now - self.lastFetch < FETCH_MS then return end
    self.lastFetch = now
    local halfW = self.width / 2 / self.zoom
    local halfH = self.height / 2 / self.zoom
    self.snapshot = TrueHordes.snapshot(self.centerX - halfW, self.centerY - halfH,
        self.centerX + halfW, self.centerY + halfH, MAX_POINTS)
end

function TH_MapPanel:prerender()
    ISPanel.prerender(self)
    local player = getPlayer()
    if self.follow and player then
        self.centerX, self.centerY = player:getX(), player:getY()
    end
    if not self.centerX then return end
    self:fetch()
    local s = self.snapshot
    if s then
        local n = s.n or 0
        if s.density then
            local size = math.max(1, s.cell * self.zoom)
            local maxCount = math.max(1, s.maxCount or 1)
            for i = 1, n * 3, 3 do
                local sx, sy = self:worldToScreen(s[i], s[i + 1])
                local alpha = 0.25 + 0.75 * (s[i + 2] / maxCount)
                self:drawRect(sx, sy, size, size, alpha, 0.9, 0.2, 0.2)
            end
        else
            for i = 1, n * 2, 2 do
                local sx, sy = self:worldToScreen(s[i], s[i + 1])
                self:drawRect(sx - 1, sy - 1, 3, 3, 1, 0.9, 0.3, 0.3)
            end
        end
    end
    if player then
        local px, py = self:worldToScreen(player:getX(), player:getY())
        self:drawRect(px - 3, py - 3, 7, 7, 1, 0.2, 1.0, 0.2)
    end
    local status
    if not s then
        status = "no data (Java core not running?)"
    elseif s.density then
        status = string.format("%d in view, density cells of %d tiles", s.total, s.cell)
    else
        status = string.format("%d in view", s.total)
    end
    self:drawText(string.format("zoom %.2f px/tile | %s%s", self.zoom, status, self.follow and " | following" or ""),
        4, 4, 1, 1, 1, 1, UIFont.Small)
end

function TH_MapPanel:onMouseWheel(del)
    local factor = del > 0 and 0.8 or 1.25
    self.zoom = math.max(0.02, math.min(8, self.zoom * factor))
    self.snapshot = nil
    return true
end

function TH_MapPanel:onMouseDown(x, y)
    self.dragging = true
    self.follow = false
    return true
end

function TH_MapPanel:onMouseUp(x, y)
    self.dragging = false
    return true
end

function TH_MapPanel:onMouseUpOutside(x, y)
    self.dragging = false
    return true
end

function TH_MapPanel:onMouseMove(dx, dy)
    if self.dragging and self.centerX then
        self.centerX = self.centerX - dx / self.zoom
        self.centerY = self.centerY - dy / self.zoom
        self.snapshot = nil
    end
    return true
end

function TH_MapPanel:onRightMouseDown(x, y)
    self.follow = true
    self.snapshot = nil
    return true
end

TH_DebugMap = ISCollapsableWindow:derive("TH_DebugMap")

function TH_DebugMap:createChildren()
    ISCollapsableWindow.createChildren(self)
    local top = self:titleBarHeight()
    self.map = TH_MapPanel:new(0, top, self.width, self.height - top)
    self.map:initialise()
    self:addChild(self.map)
end

function TH_DebugMap:close()
    ISCollapsableWindow.close(self)
    self:removeFromUIManager()
    TH_DebugMap.instance = nil
end

function TH_map()
    if TH_DebugMap.instance then
        TH_DebugMap.instance:close()
        print("True Hordes map closed")
        return "closed"
    end
    local window = TH_DebugMap:new(100, 100, 640, 640)
    window:setTitle("True Hordes debug map (wheel zoom, drag pan, right-click follow)")
    window:setResizable(false)
    window:initialise()
    window:addToUIManager()
    TH_DebugMap.instance = window
    print("True Hordes map opened")
    return "opened"
end
```

- [ ] **Step 2: Check the UI helper names exist in this game version**

Run:
```bash
grep -nE "function ISCollapsableWindow:(new|setTitle|titleBarHeight|close|setResizable)" "/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/media/lua/client/ISUI/ISCollapsableWindow.lua"
grep -nE "function ISUIElement:(drawRect|drawText|onMouseMove)" "/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/media/lua/client/ISUI/ISUIElement.lua"
```
Expected: every function name listed above is found. If one is missing, adjust only that call and note it in the commit message.

- [ ] **Step 3: Write the deploy script**

`tools/deploy.ps1`:
```powershell
# Copies the mod folder into the Workshop folder for in-game testing. Builds first unless -NoBuild is given.
# Usage: powershell -ExecutionPolicy Bypass -File tools\deploy.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
$src = Join-Path $root 'mod'
$dst = Join-Path $env:USERPROFILE 'Zomboid\Workshop\TrueHordes'
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
Copy-Item -Recurse $src $dst
Write-Host "Deployed to $dst"
```

- [ ] **Step 4: Build and deploy**

Run: `powershell -ExecutionPolicy Bypass -File tools/deploy.ps1`
Expected: `36 passed, 0 failed`, `Built ...TrueHordes.jar`, `Deployed to C:\Users\rowan\Zomboid\Workshop\TrueHordes`. Then `ls "$USERPROFILE/Zomboid/Workshop/TrueHordes/Contents/mods/TrueHordes/42/media/java/TrueHordes.jar"` shows the jar.

- [ ] **Step 5: Write the in-game acceptance checklist**

`docs/TESTING.md`:
````markdown
# True Hordes: in-game tests

## Sub-project 1 acceptance (single player, debug)

Setup: run `tools\deploy.ps1`, start the game with ZombieBuddy active, enable **True Hordes** (and ZombieBuddy) for a
new or existing save. ZombieBuddy may ask to approve the True Hordes jar the first time: approve it.

| # | Do | Expect |
|---|---|---|
| 1 | Load the save. Open `console.txt` | `[TrueHordes] True Hordes 0.1.0 loading`, `hook targets found: 3`, then `started with 0 entities` |
| 2 | Lua console: `TH_status()` | `state RUNNING`, `mode single player`, `entities 0` |
| 3 | `TH_seedTest(1000)` then `TH_map()` | Map window; about 1,000 red dots within 300 tiles of the green player square; wheel zooms (density squares when zoomed out), drag pans, right-click follows |
| 4 | `TH_stats()` | `entities=1000` and a `tick:` timing line |
| 5 | Save and quit to the main menu, reload the save | `console.txt`: `stopped`, then `started with 1000 entities (OK: loaded)`; the dots are back on `TH_map()` |
| 6 | Look in the save folder | `truehordes\state.bin` and, after a second save, `state.bin.bak` |
| 7 | `TH_clearTest()`, save, reload | `started with 0 entities` |
| 8 | Sandbox: set True Hordes > Enabled off on a new world | `switched off in sandbox options`; `TH_status()` shows `state OFF` |
| 9 | Without ZombieBuddy (or jar not approved): `TH_status()` | `True Hordes Java core is NOT loaded`, no Lua errors |
| 10 (optional) | Dedicated server with ZombieBuddy and the jar | Server `console.txt` shows `loading (dedicated server)` and `started`; no errors |
````

- [ ] **Step 6: Commit**

```bash
git add mod tools docs
git commit -m "SP1: debug map, deploy script and in-game acceptance checklist

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Hand over for in-game acceptance**

Ask the user to run `docs/TESTING.md` rows 1–9 (row 10 optional) and report results. Sub-project 1 is done when rows 1–9 pass.
