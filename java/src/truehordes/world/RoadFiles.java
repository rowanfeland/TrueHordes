package truehordes.world;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Roads and trails that the Nav zones miss, from each map folder's streets.xml (centre lines with a width, in tiles)
 * and worldmap.xml (features in the game's old 300-tile cells; highway = primary / secondary / tertiary / trail).
 * Streaming reader, no DTDs or external entities. Never throws: problems are collected and earlier shapes kept.
 */
public final class RoadFiles {
    /** The world map's cells are the game's old 300-tile cells. */
    static final int WORLD_MAP_CELL = 300;
    /** Width of a world-map line without a width property, and of a street without one. */
    static final int DEFAULT_WIDTH = 4;

    public static final class Result {
        public final List<ZoneShape> shapes = new ArrayList<>();
        public int streets;
        public int worldRoads;
        public int trails;
        public final List<String> problems = new ArrayList<>();
    }

    private RoadFiles() {}

    /** Every folder's roads, in the given order (the game's map-folder priority). */
    public static Result read(List<Path> mapFolders) {
        Result r = new Result();
        for (Path folder : mapFolders) {
            Path streets = folder.resolve("streets.xml");
            if (Files.isRegularFile(streets)) readFile(streets, r, true);
            Path worldMap = folder.resolve("worldmap.xml");
            if (Files.isRegularFile(worldMap)) readFile(worldMap, r, false);
            else if (Files.isRegularFile(folder.resolve("worldmap.xml.bin"))) {
                r.problems.add(folder + ": binary world map only, skipped");
            }
        }
        return r;
    }

    private static void readFile(Path file, Result r, boolean streets) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            if (streets) readStreets(in, r, file.toString());
            else readWorldMap(in, r, file.toString());
        } catch (IOException | RuntimeException e) {
            r.problems.add(file + ": " + e);
        }
    }

    public static void readStreets(InputStream in, Result r) { readStreets(in, r, "streets.xml"); }

    public static void readWorldMap(InputStream in, Result r) { readWorldMap(in, r, "worldmap.xml"); }

    static void readStreets(InputStream in, Result r, String source) {
        XMLStreamReader x = null;
        try {
            x = reader(in);
            int width = DEFAULT_WIDTH;
            List<Integer> pts = null;
            while (x.hasNext()) {
                int ev = x.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = x.getLocalName();
                    if (name.equals("street")) {
                        width = width(x.getAttributeValue(null, "width"));
                        pts = new ArrayList<>();
                    } else if (name.equals("point") && pts != null) {
                        addPoint(pts, x.getAttributeValue(null, "x"), x.getAttributeValue(null, "y"), 0, 0);
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && x.getLocalName().equals("street") && pts != null) {
                    if (pts.size() >= 4) {
                        r.shapes.add(ZoneShape.polyline(Terrain.ROAD, toArray(pts), width));
                        r.streets++;
                    }
                    pts = null;
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            r.problems.add(source + ": " + e.getMessage());
        } finally {
            close(x);
        }
    }

    static void readWorldMap(InputStream in, Result r, String source) {
        XMLStreamReader x = null;
        try {
            x = reader(in);
            int ox = 0, oy = 0;
            List<Integer> pts = null;
            String type = null, highway = null, width = null;
            while (x.hasNext()) {
                int ev = x.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (x.getLocalName()) {
                        case "cell":
                            ox = (int) number(x.getAttributeValue(null, "x"), 0) * WORLD_MAP_CELL;
                            oy = (int) number(x.getAttributeValue(null, "y"), 0) * WORLD_MAP_CELL;
                            break;
                        case "feature":
                            pts = new ArrayList<>();
                            type = highway = width = null;
                            break;
                        case "geometry":
                            type = x.getAttributeValue(null, "type");
                            break;
                        case "point":
                            if (pts != null) addPoint(pts, x.getAttributeValue(null, "x"), x.getAttributeValue(null, "y"), ox, oy);
                            break;
                        case "property":
                            String name = x.getAttributeValue(null, "name");
                            if ("highway".equals(name)) highway = x.getAttributeValue(null, "value");
                            else if ("width".equals(name)) width = x.getAttributeValue(null, "value");
                            break;
                        default:
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && x.getLocalName().equals("feature") && pts != null) {
                    addFeature(r, type, highway, width, pts);
                    pts = null;
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            r.problems.add(source + ": " + e.getMessage());
        } finally {
            close(x);
        }
    }

    private static void addFeature(Result r, String type, String highway, String width, List<Integer> pts) {
        Terrain t = highwayTerrain(highway);
        if (t == null) return;
        if ("Polygon".equals(type) && pts.size() >= 6) {
            r.shapes.add(ZoneShape.polygon(t, toArray(pts)));
        } else if ("LineString".equals(type) && pts.size() >= 4) {
            r.shapes.add(ZoneShape.polyline(t, toArray(pts), width(width)));
        } else {
            return;
        }
        if (t == Terrain.TRAIL) r.trails++;
        else r.worldRoads++;
    }

    private static Terrain highwayTerrain(String highway) {
        if (highway == null) return null;
        switch (highway) {
            case "primary":
            case "secondary":
            case "tertiary": return Terrain.ROAD;
            case "trail": return Terrain.TRAIL;
            default: return null;
        }
    }

    private static XMLStreamReader reader(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(in);
    }

    private static void close(XMLStreamReader x) {
        if (x == null) return;
        try {
            x.close();
        } catch (XMLStreamException ignored) {
            // nothing to recover
        }
    }

    private static void addPoint(List<Integer> pts, String xs, String ys, int ox, int oy) {
        double px = number(xs, Double.NaN), py = number(ys, Double.NaN);
        if (Double.isNaN(px) || Double.isNaN(py)) return;
        pts.add(ox + (int) Math.round(px));
        pts.add(oy + (int) Math.round(py));
    }

    private static int width(String s) {
        double w = number(s, DEFAULT_WIDTH);
        return Double.isNaN(w) ? DEFAULT_WIDTH : (int) Math.max(1, Math.round(w));
    }

    private static double number(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int[] toArray(List<Integer> pts) {
        int[] a = new int[pts.size()];
        for (int i = 0; i < a.length; i++) a[i] = pts.get(i);
        return a;
    }
}
