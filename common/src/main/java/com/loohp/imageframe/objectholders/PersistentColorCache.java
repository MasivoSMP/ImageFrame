/*
 * This file is part of ImageFrame.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.imageframe.objectholders;

import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.utils.MapUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.atomic.AtomicLong;

public class PersistentColorCache {

    private static final int FORMAT_VERSION = 1;
    private static final int STATIC_MAGIC = 0x49464353; // IFCS
    private static final int ANIMATED_MAGIC = 0x49464341; // IFCA
    private static final int TILE_BYTES = MapUtils.MAP_WIDTH * MapUtils.MAP_WIDTH;

    public static final String STATIC_CACHE_FILE_NAME = ".ifcache-static-v1.bin";
    public static final String ANIMATED_CACHE_FILE_NAME = ".ifcache-animated-v1.bin";

    private static final AtomicLong CACHE_HITS = new AtomicLong(0L);
    private static final AtomicLong CACHE_MISSES = new AtomicLong(0L);
    private static final AtomicLong CACHE_CORRUPTIONS = new AtomicLong(0L);

    private static boolean canUseCache(ImageMap imageMap) {
        return ImageFrame.startupPersistentColorCache && imageMap.getImageIndex() >= 0;
    }

    public static byte[][] loadStatic(ImageMap imageMap, String mapType, int width, int height, int mapCount, DitheringType ditheringType, long imageDataRevision) {
        if (!canUseCache(imageMap)) {
            return null;
        }
        LazyDataSource source = imageMap.getManager().getStorage().getSource(imageMap.getImageIndex(), STATIC_CACHE_FILE_NAME);
        try {
            byte[][] colors = source.load(inputStream -> {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(inputStream))) {
                    if (in.readInt() != STATIC_MAGIC) {
                        return null;
                    }
                    if (in.readInt() != FORMAT_VERSION) {
                        return null;
                    }
                    if (!mapType.equals(in.readUTF())) {
                        return null;
                    }
                    if (imageDataRevision != in.readLong()) {
                        return null;
                    }
                    if (width != in.readInt() || height != in.readInt() || mapCount != in.readInt()) {
                        return null;
                    }
                    String ditheringName = in.readUTF();
                    String expectedDitheringName = ditheringType == null ? "" : ditheringType.getName();
                    if (!expectedDitheringName.equals(ditheringName)) {
                        return null;
                    }

                    byte[][] cachedColors = new byte[mapCount][];
                    for (int i = 0; i < mapCount; i++) {
                        byte[] bytes = new byte[TILE_BYTES];
                        in.readFully(bytes);
                        cachedColors[i] = bytes;
                    }
                    return cachedColors;
                }
            });
            if (colors == null) {
                CACHE_MISSES.incrementAndGet();
            } else {
                CACHE_HITS.incrementAndGet();
            }
            return colors;
        } catch (Throwable e) {
            if (isCacheMiss(e)) {
                CACHE_MISSES.incrementAndGet();
            } else {
                CACHE_CORRUPTIONS.incrementAndGet();
            }
            return null;
        }
    }

    public static void saveStatic(ImageMap imageMap, String mapType, int width, int height, int mapCount, DitheringType ditheringType, long imageDataRevision, byte[][] cachedColors) {
        if (!canUseCache(imageMap) || cachedColors == null) {
            return;
        }
        LazyDataSource source = imageMap.getManager().getStorage().getSource(imageMap.getImageIndex(), STATIC_CACHE_FILE_NAME);
        try {
            source.save(outputStream -> {
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(outputStream))) {
                    out.writeInt(STATIC_MAGIC);
                    out.writeInt(FORMAT_VERSION);
                    out.writeUTF(mapType);
                    out.writeLong(imageDataRevision);
                    out.writeInt(width);
                    out.writeInt(height);
                    out.writeInt(mapCount);
                    out.writeUTF(ditheringType == null ? "" : ditheringType.getName());
                    for (byte[] colors : cachedColors) {
                        if (colors == null || colors.length != TILE_BYTES) {
                            return;
                        }
                        out.write(colors);
                    }
                    out.flush();
                }
            });
        } catch (Throwable ignored) {
            // Cache writes are best-effort and should never fail map loading.
        }
    }

    public static byte[][][] loadAnimated(ImageMap imageMap, String mapType, int width, int height, int mapCount, int[] frameCounts, DitheringType ditheringType, long imageDataRevision) {
        if (!canUseCache(imageMap)) {
            return null;
        }
        LazyDataSource source = imageMap.getManager().getStorage().getSource(imageMap.getImageIndex(), ANIMATED_CACHE_FILE_NAME);
        try {
            byte[][][] colors = source.load(inputStream -> {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(inputStream))) {
                    if (in.readInt() != ANIMATED_MAGIC) {
                        return null;
                    }
                    if (in.readInt() != FORMAT_VERSION) {
                        return null;
                    }
                    if (!mapType.equals(in.readUTF())) {
                        return null;
                    }
                    if (imageDataRevision != in.readLong()) {
                        return null;
                    }
                    if (width != in.readInt() || height != in.readInt() || mapCount != in.readInt()) {
                        return null;
                    }
                    String ditheringName = in.readUTF();
                    String expectedDitheringName = ditheringType == null ? "" : ditheringType.getName();
                    if (!expectedDitheringName.equals(ditheringName)) {
                        return null;
                    }

                    byte[][][] cachedColors = new byte[mapCount][][];
                    for (int i = 0; i < mapCount; i++) {
                        int expectedFrameCount = frameCounts[i];
                        if (expectedFrameCount != in.readInt()) {
                            return null;
                        }
                        byte[][] mapFrames = new byte[expectedFrameCount][];
                        for (int u = 0; u < expectedFrameCount; u++) {
                            boolean hasFrameData = in.readBoolean();
                            if (hasFrameData) {
                                byte[] frame = new byte[TILE_BYTES];
                                in.readFully(frame);
                                mapFrames[u] = frame;
                            }
                        }
                        cachedColors[i] = mapFrames;
                    }
                    return cachedColors;
                }
            });
            if (colors == null) {
                CACHE_MISSES.incrementAndGet();
            } else {
                CACHE_HITS.incrementAndGet();
            }
            return colors;
        } catch (Throwable e) {
            if (isCacheMiss(e)) {
                CACHE_MISSES.incrementAndGet();
            } else {
                CACHE_CORRUPTIONS.incrementAndGet();
            }
            return null;
        }
    }

    public static void saveAnimated(ImageMap imageMap, String mapType, int width, int height, int mapCount, int[] frameCounts, DitheringType ditheringType, long imageDataRevision, byte[][][] cachedColors) {
        if (!canUseCache(imageMap) || cachedColors == null) {
            return;
        }
        LazyDataSource source = imageMap.getManager().getStorage().getSource(imageMap.getImageIndex(), ANIMATED_CACHE_FILE_NAME);
        try {
            source.save(outputStream -> {
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(outputStream))) {
                    out.writeInt(ANIMATED_MAGIC);
                    out.writeInt(FORMAT_VERSION);
                    out.writeUTF(mapType);
                    out.writeLong(imageDataRevision);
                    out.writeInt(width);
                    out.writeInt(height);
                    out.writeInt(mapCount);
                    out.writeUTF(ditheringType == null ? "" : ditheringType.getName());
                    for (int i = 0; i < mapCount; i++) {
                        int expectedFrameCount = frameCounts[i];
                        byte[][] mapFrames = cachedColors[i];
                        if (mapFrames == null || mapFrames.length != expectedFrameCount) {
                            return;
                        }
                        out.writeInt(expectedFrameCount);
                        for (int u = 0; u < expectedFrameCount; u++) {
                            byte[] frame = mapFrames[u];
                            boolean hasFrameData = frame != null;
                            out.writeBoolean(hasFrameData);
                            if (hasFrameData) {
                                if (frame.length != TILE_BYTES) {
                                    return;
                                }
                                out.write(frame);
                            }
                        }
                    }
                    out.flush();
                }
            });
        } catch (Throwable ignored) {
            // Cache writes are best-effort and should never fail map loading.
        }
    }

    public static CacheStats snapshotAndResetStats() {
        return new CacheStats(CACHE_HITS.getAndSet(0L), CACHE_MISSES.getAndSet(0L), CACHE_CORRUPTIONS.getAndSet(0L));
    }

    private static boolean isCacheMiss(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof NoSuchFileException || current instanceof FileNotFoundException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static class CacheStats {

        private final long hits;
        private final long misses;
        private final long corruptions;

        public CacheStats(long hits, long misses, long corruptions) {
            this.hits = hits;
            this.misses = misses;
            this.corruptions = corruptions;
        }

        public long getHits() {
            return hits;
        }

        public long getMisses() {
            return misses;
        }

        public long getCorruptions() {
            return corruptions;
        }
    }

}
