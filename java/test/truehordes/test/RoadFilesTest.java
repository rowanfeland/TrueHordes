package truehordes.test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import truehordes.world.RoadFiles;
import truehordes.world.Terrain;

public final class RoadFilesTest {
    private static ByteArrayInputStream xml(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }

    private static String street(String width, int x1, int y1, int x2, int y2) {
        return "<street name=\"A\" width=\"" + width + "\"><points><point x=\"" + x1 + ".0\" y=\"" + y1 + ".0\"/>"
            + "<point x=\"" + x2 + ".0\" y=\"" + y2 + ".0\"/></points></street>";
    }

    private static String feature(String type, String highway, String width, int... pts) {
        StringBuilder sb = new StringBuilder("<feature><geometry type=\"" + type + "\"><coordinates>");
        for (int i = 0; i < pts.length; i += 2) sb.append("<point x=\"").append(pts[i]).append("\" y=\"").append(pts[i + 1]).append("\"/>");
        sb.append("</coordinates></geometry><properties><property name=\"highway\" value=\"").append(highway).append("\"/>");
        if (width != null) sb.append("<property name=\"width\" value=\"").append(width).append("\"/>");
        return sb.append("</properties></feature>").toString();
    }

    public static void testStreetBecomesWidePolyline() throws Exception {
        RoadFiles.Result r = new RoadFiles.Result();
        RoadFiles.readStreets(xml("<streets version=\"1\">" + street("6", 100, 200, 140, 200) + "</streets>"), r);
        Check.equal(1, r.streets, "one street");
        Check.equal(Terrain.ROAD, r.shapes.get(0).terrain, "road");
        Check.isTrue(r.shapes.get(0).contains(120, 202), "within half the width");
        Check.isTrue(!r.shapes.get(0).contains(120, 204), "beyond half the width");
    }

    public static void testWorldMapCellOffset() throws Exception {
        RoadFiles.Result r = new RoadFiles.Result();
        RoadFiles.readWorldMap(xml("<world version=\"1.0\"><cell x=\"2\" y=\"3\">"
            + feature("Polygon", "tertiary", null, 10, 10, 20, 10, 20, 20, 10, 20) + "</cell></world>"), r);
        Check.equal(1, r.worldRoads, "one road");
        Check.isTrue(r.shapes.get(0).contains(615, 915), "cell (2,3) of 300 tiles: local (15,15) is tile (615,915)");
        Check.isTrue(!r.shapes.get(0).contains(605, 905), "outside the polygon");
    }

    public static void testTrailAndLineStringWidth() throws Exception {
        RoadFiles.Result r = new RoadFiles.Result();
        RoadFiles.readWorldMap(xml("<world><cell x=\"0\" y=\"0\">"
            + feature("LineString", "trail", null, 0, 50, 50, 50)
            + feature("LineString", "secondary", "8", 0, 100, 50, 100) + "</cell></world>"), r);
        Check.equal(1, r.trails, "one trail");
        Check.equal(1, r.worldRoads, "one road");
        Check.equal(Terrain.TRAIL, r.shapes.get(0).terrain, "trail terrain");
        Check.isTrue(r.shapes.get(0).contains(10, 51) && !r.shapes.get(0).contains(10, 53), "default width 4");
        Check.isTrue(r.shapes.get(1).contains(10, 103) && !r.shapes.get(1).contains(10, 105), "width property 8");
    }

    public static void testOtherHighwaysAndFeaturesIgnored() throws Exception {
        RoadFiles.Result r = new RoadFiles.Result();
        RoadFiles.readWorldMap(xml("<world><cell x=\"0\" y=\"0\">"
            + feature("Polygon", "footway", null, 0, 0, 5, 0, 5, 5)
            + "<feature><geometry type=\"Polygon\"><coordinates><point x=\"0\" y=\"0\"/><point x=\"9\" y=\"0\"/>"
            + "<point x=\"9\" y=\"9\"/></coordinates></geometry><properties><property name=\"water\" value=\"river\"/>"
            + "</properties></feature></cell></world>"), r);
        Check.equal(0, r.shapes.size(), "nothing kept");
    }

    public static void testBrokenXmlKeepsEarlierShapes() throws Exception {
        RoadFiles.Result r = new RoadFiles.Result();
        RoadFiles.readStreets(xml("<streets>" + street("5", 0, 0, 10, 0) + street("5", 0, 20, 10, 20)
            + "<street name=\"broken\" width=\"5\"><points><point x=\"1"), r);
        Check.equal(2, r.shapes.size(), "shapes before the error kept");
        Check.equal(1, r.problems.size(), "one problem: " + r.problems);
    }

    public static void testMissingFilesAreSkipped() throws Exception {
        Path empty = Files.createTempDirectory("th-roads");
        RoadFiles.Result r = RoadFiles.read(List.of(empty));
        Check.equal(0, r.shapes.size(), "no shapes");
        Check.equal(0, r.problems.size(), "no problems");
        Path binOnly = Files.createTempDirectory("th-roads");
        Files.write(binOnly.resolve("worldmap.xml.bin"), new byte[] {1, 2, 3});
        RoadFiles.Result b = RoadFiles.read(List.of(binOnly));
        Check.equal(1, b.problems.size(), "reported");
        Check.isTrue(b.problems.get(0).contains("binary world map only"), b.problems.get(0));
    }

    public static void testReadsBothFilesFromFolders() throws Exception {
        Path dir = Files.createTempDirectory("th-roads");
        Files.writeString(dir.resolve("streets.xml"), "<streets>" + street("5", 0, 0, 10, 0) + "</streets>");
        Files.writeString(dir.resolve("worldmap.xml"), "<world><cell x=\"0\" y=\"0\">"
            + feature("Polygon", "trail", null, 0, 0, 5, 0, 5, 5) + "</cell></world>");
        RoadFiles.Result r = RoadFiles.read(List.of(dir));
        Check.equal(1, r.streets, "street");
        Check.equal(1, r.trails, "trail");
        Check.equal(2, r.shapes.size(), "both shapes");
        Check.equal(0, r.problems.size(), "clean: " + r.problems);
    }
}
