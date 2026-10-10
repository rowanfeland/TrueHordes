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
