package cc.colorcat.diskcache;

import java.io.File;
import java.io.IOException;

/**
 * Author: colocate
 * Date: 2024-03-14
 * GitHub: https://github.com/ccolorcat
 */
final class Utils {

    static void deleteIfExists(File... files) throws IOException {
        for (File file : files) {
            deleteIfExists(file);
        }
    }

    static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("failed to delete file: " + file);
        }
    }

    static void deleteContents(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("not a readable directory: " + dir);
        for (File file : files) {
            if (file.isDirectory()) {
                deleteContents(file);
            }
            if (!file.delete()) {
                throw new IOException("failed to delete file: " + file);
            }
        }
    }

    static void renameTo(File from, File to, boolean deleteDest) throws IOException {
        if (deleteDest) {
            deleteIfExists(to);
        }
        if (!from.renameTo(to)) {
            throw new IOException("failed to rename from " + from + " to " + to);
        }
    }

    private Utils() {
        throw new AssertionError("no instance");
    }
}
