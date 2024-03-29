package cc.colorcat.diskcache;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Author: colocate
 * Date: 2024-03-14
 * GitHub: https://github.com/ccolorcat
 */
@SuppressWarnings("unused")
public final class DiskCache {
    private static final String DIRTY_SUFFIX = ".tmp";
    private static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");

    private final LinkedHashMap<String, Snapshot> map;
    private final File directory;

    private final long maxSize;
    private long size;
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    private final Callable<Void> cleanupCallable = new Callable<Void>() {
        @Override
        public Void call() throws Exception {
            synchronized (DiskCache.this) {
                trimToSize(maxSize);
                return null;
            }
        }
    };

    private DiskCache(File directory, long maxSize) {
        this.directory = directory;
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<>(0, 0.75F, true);
    }

    public static DiskCache open(File directory, long maxSize) throws IOException {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (directory.isFile()) {
            throw new IOException(directory + " is not a directory!");
        }
        synchronized (DiskCache.class) {
            File dir = new File(directory, "diskCache");
            if (dir.exists() || dir.mkdirs()) {
                DiskCache cache = new DiskCache(dir, maxSize);
                cache.cleanDirtyFile();
                cache.readSnapshots();
                cache.asyncTrimToSize();
                return cache;
            }
            throw new IOException("failed to create directory: " + dir);
        }
    }

    private void cleanDirtyFile() throws IOException {
        File[] dirty = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.getName().endsWith(DIRTY_SUFFIX);
            }
        });
        Utils.deleteIfExists(dirty);
    }

    private void readSnapshots() throws IOException {
        File[] files = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile();
            }
        });
        assert files != null;
        List<File> list = Arrays.asList(files);
        Collections.sort(list, new FileComparator());
        for (int i = 0, size = list.size(); i < size; ++i) {
            File file = list.get(i);
            this.size += file.length();
            String name = file.getName();
            map.put(name, new Snapshot(name));
        }
    }

    public synchronized Snapshot getSnapshot(String key) {
        checkKey(key);
        Snapshot snapshot = map.get(key);
        if (snapshot == null) {
            snapshot = new Snapshot(key);
            map.put(key, snapshot);
        }
        return snapshot;
    }

    public void clear() throws IOException {
        Utils.deleteContents(directory);
    }

    public long maxSize() {
        return maxSize;
    }

    public long size() {
        return size;
    }

    private void checkKey(String key) {
        Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"");
        }
    }

    private void completeWriteSnapshot(Snapshot snapshot, boolean success) throws IOException {
        try {
            File dirty = snapshot.getDirtyFile();
            File clean = snapshot.getCleanFile();
            if (success) {
                if (dirty.exists()) {
                    long oldLength = clean.length();
                    long newLength = dirty.length();
                    Utils.renameTo(dirty, clean, true);
                    size = size - oldLength + newLength;
//                    asyncTrimToSize();
                }
            } else {
                Utils.deleteIfExists(dirty);
            }
        } finally {
            snapshot.writing = false;
            snapshot.committed = false;
            snapshot.hasErrors = false;
            if (snapshot.requiredDelete) {
                deleteSnapshot(snapshot);
            }
            asyncTrimToSize();
        }
    }

    private void deleteSnapshot(Snapshot snapshot) throws IOException {
        File clean = snapshot.getCleanFile();
        if (clean.exists()) {
            long length = clean.length();
            Utils.deleteIfExists(clean);
            if (map.remove(snapshot.key) != null) {
                size -= length;
            }
        }
    }

    private void asyncTrimToSize() {
        if (size > maxSize) {
            executor.submit(cleanupCallable);
        }
    }

    private void trimToSize(long maxSize) throws IOException {
        Iterator<Map.Entry<String, Snapshot>> iterator = map.entrySet().iterator();
        while (size > maxSize && iterator.hasNext()) {
            Map.Entry<String, Snapshot> toEvict = iterator.next();
            Snapshot value = toEvict.getValue();
            if (value.readCount == 0 && !value.writing) {
                File clean = value.getCleanFile();
                long cleanLength = clean.length();
                Utils.deleteIfExists(clean);
                size -= cleanLength;
                iterator.remove();
            }
        }
    }


    public final class Snapshot {
        private String key;

        private int readCount = 0;

        private boolean writing = false;
        private boolean committed = false;
        private boolean hasErrors = false;

        private boolean requiredDelete = false;

        private Snapshot(String key) {
            this.key = key;
        }

        public InputStream getInputStream() {
            synchronized (DiskCache.this) {
                try {
                    ++readCount;
                    return new SnapshotInputStream(new FileInputStream(getCleanFile()));
                } catch (FileNotFoundException e) {
                    --readCount;
                    return null;
                }
            }
        }

        public long getContentLength() {
            return getCleanFile().length();
        }

        public long getLastModified() {
            return getCleanFile().lastModified();
        }

        public OutputStream getOutputStream() {
            synchronized (DiskCache.this) {
                if (!writing) {
                    try {
                        FileOutputStream fos = new FileOutputStream(getDirtyFile());
                        writing = true;
                        return new SnapshotOutputStream(fos);
                    } catch (FileNotFoundException e) {
                        writing = false;
                        throw new IllegalStateException(directory + " does not exist.");
                    }
                }
                return null;
            }
        }

        public void requireDelete() throws IOException {
            synchronized (DiskCache.this) {
                if (!requiredDelete) {
                    requiredDelete = true;
                    if (readCount == 0 && !writing) {
                        deleteSnapshot(this);
                    }
                }
            }
        }

        private void completeRead() throws IOException {
            synchronized (DiskCache.this) {
                --readCount;
                if (readCount < 0) {
                    throw new IllegalStateException("readCount < 0");
                }
                if (readCount == 0) {
                    if (writing) {
                        if (committed) {
                            completeWriteSnapshot(this, !hasErrors);
                        }
                    } else {
                        if (requiredDelete) {
                            deleteSnapshot(this);
                        }
                    }
                }
            }
        }

        private void commitWrite() throws IOException {
            synchronized (DiskCache.this) {
                if (writing && !committed) {
                    committed = true;
                    if (readCount == 0) {
                        completeWriteSnapshot(this, !hasErrors);
                    }
                } else {
                    throw new IllegalStateException("writing = " + writing + ", committed = " + committed);
                }
            }
        }

        private File getCleanFile() {
            return new File(directory, key);
        }

        private File getDirtyFile() {
            return new File(directory, key + DIRTY_SUFFIX);
        }

        @Override
        public String toString() {
            return "Snapshot{" +
                    "key='" + key + '\'' +
                    ", readCount=" + readCount +
                    ", writing=" + writing +
                    ", committed=" + committed +
                    ", hasErrors=" + hasErrors +
                    ", requiredDelete=" + requiredDelete +
                    '}';
        }

        private class SnapshotInputStream extends FilterInputStream {
            private boolean closed = false;

            private SnapshotInputStream(InputStream in) {
                super(in);
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    try {
                        in.close();
                    } finally {
                        completeRead();
                    }
                }
            }
        }


        private class SnapshotOutputStream extends FilterOutputStream {
            private boolean closed = false;

            private SnapshotOutputStream(OutputStream out) {
                super(out);
            }

            @Override
            public void write(int oneByte) {
                try {
                    out.write(oneByte);
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void write(byte[] buffer) {
                write(buffer, 0, buffer.length);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) {
                try {
                    out.write(buffer, offset, length);
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void flush() {
                try {
                    out.flush();
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    try {
                        out.close();
                    } catch (IOException e) {
                        hasErrors = true;
                    } finally {
                        try {
                            commitWrite();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    private static class FileComparator implements Comparator<File> {
        @Override
        public int compare(File f1, File f2) {
            return Long.compare(f1.lastModified(), f2.lastModified());
        }
    }
}
