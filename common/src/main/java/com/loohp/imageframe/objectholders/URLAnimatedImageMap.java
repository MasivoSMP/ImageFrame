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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.api.events.ImageMapUpdatedEvent;
import com.loohp.imageframe.media.TimedMediaFrameIterator;
import com.loohp.imageframe.storage.ImageFrameStorage;
import com.loohp.imageframe.utils.MapUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapView;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

public class URLAnimatedImageMap extends URLImageMap {

    private static final long FNV1A_64_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV1A_64_PRIME = 0x100000001b3L;

    protected final LazyMappedBufferedImage[][] cachedImages;

    protected byte[][][] cachedColors;
    protected int[][] fakeMapIds;
    protected Set<Integer> fakeMapIdsSet;
    protected int pausedAt;
    protected int tickOffset;

    protected URLAnimatedImageMap(ImageMapManager manager, ImageMapLoader<?, ?> loader, int imageIndex, String name, String url, LazyMappedBufferedImage[][] cachedImages, List<MapView> mapViews, List<Integer> mapIds, List<Map<String, MapCursor>> mapMarkers, int width, int height, DitheringType ditheringType, UUID creator, Map<UUID, ImageMapAccessPermissionType> hasAccess, long creationTime, int pausedAt, int tickOffset) {
        super(manager, loader, imageIndex, name, url, mapViews, mapIds, mapMarkers, width, height, ditheringType, creator, hasAccess, creationTime);
        this.cachedImages = cachedImages;
        this.pausedAt = pausedAt;
        this.tickOffset = tickOffset;
        this.cacheControlTask.loadCacheIfManual();
    }

    @Override
    public void loadColorCache() {
        if (cachedImages == null) {
            return;
        }
        if (cachedImages[0] == null) {
            return;
        }
        int[] frameCounts = new int[cachedImages.length];
        for (int i = 0; i < cachedImages.length; i++) {
            LazyMappedBufferedImage[] images = cachedImages[i];
            frameCounts[i] = images == null ? 0 : images.length;
        }
        byte[][][] persistentCache = PersistentColorCache.loadAnimated(this, loader.getIdentifier().asString(), width, height, cachedImages.length, frameCounts, ditheringType, imageDataRevision);
        if (persistentCache != null) {
            applyCachedAnimationColors(persistentCache);
            return;
        }
        int frameCount = cachedImages[0].length;
        byte[][][] cachedColors = new byte[cachedImages.length][][];
        int[][] fakeMapIds = new int[cachedColors.length][];
        Set<Integer> fakeMapIdsSet = new HashSet<>();
        byte[][] combinedData = new byte[frameCount][];

        BufferedImage combinedImage = new BufferedImage(width * MapUtils.MAP_WIDTH, height * MapUtils.MAP_WIDTH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = combinedImage.createGraphics();
        try {
            for (int frame = 0; frame < frameCount; frame++) {
                ensureNotInterrupted();
                if (frame > 0 && isFrameStateIdentical(frame, frame - 1)) {
                    combinedData[frame] = combinedData[frame - 1];
                    continue;
                }
                int index = 0;
                for (LazyMappedBufferedImage[] images : cachedImages) {
                    //noinspection SuspiciousNameCombination
                    g.drawImage(images[frame].get(), (index % width) * MapUtils.MAP_WIDTH, (index / width) * MapUtils.MAP_WIDTH, MapUtils.MAP_WIDTH, MapUtils.MAP_WIDTH, null);
                    index++;
                }
                combinedData[frame] = MapUtils.toMapPaletteBytes(combinedImage, ditheringType);
            }
        } finally {
            g.dispose();
        }

        for (int i = 0; i < cachedImages.length; i++) {
            LazyMappedBufferedImage[] images = cachedImages[i];
            byte[][] data = new byte[images.length][];
            int[] mapIds = new int[data.length];
            Arrays.fill(mapIds, -1);
            byte[] lastDistinctFrame = null;
            int mapFrameCount = Math.min(images.length, combinedData.length);
            for (int u = 0; u < mapFrameCount; u++) {
                ensureNotInterrupted();
                if (u > 0 && combinedData[u] == combinedData[u - 1]) {
                    continue;
                }
                byte[] b = new byte[MapUtils.MAP_WIDTH * MapUtils.MAP_WIDTH];
                for (int y = 0; y < MapUtils.MAP_WIDTH; y++) {
                    int offset = ((i / width) * MapUtils.MAP_WIDTH + y) * (width * MapUtils.MAP_WIDTH) + ((i % width) * MapUtils.MAP_WIDTH);
                    System.arraycopy(combinedData[u], offset, b, y * MapUtils.MAP_WIDTH, MapUtils.MAP_WIDTH);
                }
                if (u == 0 || !Arrays.equals(b, lastDistinctFrame)) {
                    data[u] = b;
                    int mapId = ImageMapManager.getNextFakeMapId();
                    mapIds[u] = mapId;
                    fakeMapIdsSet.add(mapId);
                    lastDistinctFrame = b;
                }
            }
            cachedColors[i] = data;
            fakeMapIds[i] = mapIds;
        }
        this.cachedColors = cachedColors;
        this.fakeMapIds = fakeMapIds;
        this.fakeMapIdsSet = fakeMapIdsSet;
        PersistentColorCache.saveAnimated(this, loader.getIdentifier().asString(), width, height, cachedImages.length, frameCounts, ditheringType, imageDataRevision, cachedColors);
    }

    private void applyCachedAnimationColors(byte[][][] cachedColors) {
        int[][] fakeMapIds = new int[cachedColors.length][];
        Set<Integer> fakeMapIdsSet = new HashSet<>();
        for (int i = 0; i < cachedColors.length; i++) {
            byte[][] frames = cachedColors[i];
            int[] mapIds = new int[frames.length];
            Arrays.fill(mapIds, -1);
            for (int u = 0; u < frames.length; u++) {
                if (frames[u] != null) {
                    int mapId = ImageMapManager.getNextFakeMapId();
                    mapIds[u] = mapId;
                    fakeMapIdsSet.add(mapId);
                }
            }
            fakeMapIds[i] = mapIds;
        }
        this.cachedColors = cachedColors;
        this.fakeMapIds = fakeMapIds;
        this.fakeMapIdsSet = fakeMapIdsSet;
    }

    @Override
    public boolean applyUpdate(JsonObject json) {
        this.pausedAt = json.get("pausedAt").getAsInt();
        this.tickOffset = json.get("tickOffset").getAsInt();
        return super.applyUpdate(json);
    }

    @Override
    public boolean hasColorCached() {
        return cachedColors != null;
    }

    @Override
    public void unloadColorCache() {
        cachedColors = null;
    }

    @Override
    public void update(boolean save) throws Exception {
        List<FrameRun> frameRuns = collectFrameRuns(new TimedMediaFrameIterator(loader.tryLoadMedia(url), 50));
        if (frameRuns.isEmpty()) {
            throw new IllegalArgumentException("No image frames found");
        }
        int totalTicks = 0;
        for (FrameRun frameRun : frameRuns) {
            totalTicks += frameRun.getTicks();
        }
        for (int i = 0; i < cachedImages.length; i++) {
            cachedImages[i] = new LazyMappedBufferedImage[totalTicks];
        }
        int tickIndex = 0;
        Map<IntPosition, TileState> previousImages = new HashMap<>();
        for (FrameRun frameRun : frameRuns) {
            ensureNotInterrupted();
            BufferedImage image = MapUtils.resize(frameRun.getImage(), width, height);
            int i = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    IntPosition intPosition = new IntPosition(x, y);
                    BufferedImage subImage = MapUtils.getSubImage(image, x, y);
                    long hash = hashImage(subImage);
                    TileState previousFile = previousImages.get(intPosition);
                    LazyMappedBufferedImage file;
                    if (previousFile != null && previousFile.getHash() == hash && areImagesEqual(subImage, previousFile.getImage())) {
                        file = previousFile.getImage();
                    } else {
                        file = StandardLazyMappedBufferedImage.fromImage(copyImage(subImage));
                    }
                    Arrays.fill(cachedImages[i++], tickIndex, tickIndex + frameRun.getTicks(), file);
                    previousImages.put(intPosition, new TileState(hash, file));
                }
            }
            tickIndex += frameRun.getTicks();
        }
        touchImageDataRevision();
        reloadColorCache();
        Bukkit.getPluginManager().callEvent(new ImageMapUpdatedEvent(this));
        if (save) {
            save();
        }
    }

    @Override
    public boolean requiresAnimationService() {
        return true;
    }

    @Override
    public int getCurrentPositionInSequenceWithOffset() {
        if (isAnimationPaused()) {
            return pausedAt;
        }
        int sequenceLength = getSequenceLength();
        int currentPosition = (int) (manager.getCurrentAnimationTick() % sequenceLength) - tickOffset;
        if (currentPosition < 0) {
            currentPosition = sequenceLength + currentPosition;
        }
        return currentPosition;
    }

    @Override
    public boolean isAnimationPaused() {
        return pausedAt >= 0;
    }

    @Override
    public synchronized void setAnimationPause(boolean pause) throws Exception {
        if (pausedAt < 0 && pause) {
            pausedAt = getCurrentPositionInSequenceWithOffset();
            save();
        } else if (pausedAt >= 0 && !pause) {
            setCurrentPositionInSequence(pausedAt);
            pausedAt = -1;
            save();
        }
    }

    @Override
    public void setCurrentPositionInSequence(int position) {
        int sequenceLength = getSequenceLength();
        tickOffset = (int) (manager.getCurrentAnimationTick() % sequenceLength) - position % sequenceLength;
    }

    @Override
    public synchronized void setAnimationPlaybackTime(double seconds) throws Exception {
        int totalTicks = getSequenceLength();
        int ticks;
        if (seconds < 0) {
            ticks = totalTicks + (int) Math.ceil((seconds + 1) * 20);
        } else {
            ticks = (int) Math.floor(seconds * 20);
        }
        ticks = Math.min(Math.max(0, ticks), totalTicks - 1);
        if (isAnimationPaused()) {
            pausedAt = ticks;
            save();
        } else {
            setCurrentPositionInSequence(ticks);
            save();
        }
    }

    @Override
    public byte[] getRawAnimationColors(int currentTick, int index) {
        if (cachedColors == null) {
            return null;
        }
        byte[][] colors = cachedColors[index];
        if (colors == null) {
            return null;
        }
        return colors[currentTick % colors.length];
    }

    @Override
    public int getAnimationFakeMapId(int currentTick, int index, boolean lookbehind) {
        if (fakeMapIds == null) {
            return -1;
        }
        int[] mapIds = fakeMapIds[index];
        if (mapIds == null) {
            return -1;
        }
        int mapIdIndex = currentTick % mapIds.length;
        int mapId = mapIds[mapIdIndex];
        if (mapId >= 0 || !lookbehind) {
            return mapId;
        }
        for (; mapIdIndex >= 0; mapIdIndex--) {
            mapId = mapIds[mapIdIndex];
            if (mapId >= 0) {
                return mapId;
            }
        }
        return mapId;
    }

    @Override
    public void sendAnimationFakeMaps(Collection<? extends Player> players, MapPacketSentCallback completionCallback) {
        int length = getSequenceLength();
        for (int currentTick = 0; currentTick < length; currentTick++) {
            for (int index = 0; index < fakeMapIds.length; index++) {
                int[] mapIds = fakeMapIds[index];
                if (mapIds != null && currentTick < mapIds.length) {
                    int mapId = mapIds[currentTick];
                    if (mapId >= 0) {
                        MapUtils.sendImageMap(mapId, mapViews.get(index), currentTick, players, completionCallback);
                    }
                }
            }
        }
    }

    @Override
    public Set<Integer> getFakeMapIds() {
        return fakeMapIdsSet;
    }

    @Override
    public MapView getMapViewFromMapId(int mapId) {
        MapView mapView = super.getMapViewFromMapId(mapId);
        if (mapView != null) {
            return mapView;
        }
        if (!fakeMapIdsSet.contains(mapId)) {
            return null;
        }
        for (int i = 0; i < fakeMapIds.length; i++) {
            int[] ids = fakeMapIds[i];
            if (ids != null && Arrays.stream(ids).anyMatch(id -> id == mapId)) {
                return mapViews.get(i);
            }
        }
        return null;
    }

    @Override
    public int getSequenceLength() {
        return cachedImages[0].length;
    }

    @Override
    public ImageMap deepClone(String name, UUID creator) throws Exception {
        URLAnimatedImageMap imageMap = ((URLAnimatedImageMapLoader) loader).create(new URLImageMapCreateInfo(manager, name, url, width, height, ditheringType, creator)).get();
        List<Map<String, MapCursor>> newList = imageMap.getMapMarkers();
        int i = 0;
        for (Map<String, MapCursor> map : getMapMarkers()) {
            Map<String, MapCursor> newMap = newList.get(i++);
            for (Map.Entry<String, MapCursor> entry : map.entrySet()) {
                MapCursor mapCursor = entry.getValue();
                newMap.put(entry.getKey(), new MapCursor(mapCursor.getX(), mapCursor.getY(), mapCursor.getDirection(), mapCursor.getType(), mapCursor.isVisible(), mapCursor.getCaption()));
            }
        }
        return imageMap;
    }

    @Override
    public void save(ImageFrameStorage storage, boolean saveAsCopy) throws Exception {
        if (imageIndex < 0) {
            throw new IllegalStateException("ImageMap with index < 0 cannot be saved");
        }
        JsonObject json = new JsonObject();
        json.addProperty("type", loader.getIdentifier().asString());
        json.addProperty("index", imageIndex);
        json.addProperty("name", name);
        json.addProperty("url", url);
        json.addProperty("width", width);
        json.addProperty("height", height);
        if (ditheringType != null) {
            json.addProperty("ditheringType", ditheringType.getName());
        }
        json.addProperty("imageDataRevision", imageDataRevision);
        json.addProperty("creator", creator.toString());
        json.addProperty("pausedAt", pausedAt);
        json.addProperty("tickOffset", tickOffset);
        JsonObject accessJson = new JsonObject();
        for (Map.Entry<UUID, ImageMapAccessPermissionType> entry : accessControl.getPermissions().entrySet()) {
            accessJson.addProperty(entry.getKey().toString(), entry.getValue().name());
        }
        json.add("hasAccess", accessJson);
        json.addProperty("creationTime", creationTime);
        JsonArray mapDataJson = new JsonArray();
        IdentityHashMap<LazyMappedBufferedImage, PendingImageSave> pendingSourceWrites = new IdentityHashMap<>();
        int u = 0;
        for (int i = 0; i < mapViews.size(); i++) {
            JsonObject dataJson = new JsonObject();
            dataJson.addProperty("mapid", mapIds.get(i));
            JsonArray framesArray = new JsonArray();
            for (LazyMappedBufferedImage image : cachedImages[i]) {
                int index = u++;
                LazyDataSource source = storage.getSource(imageIndex, index + ".png");
                if (saveAsCopy) {
                    if (image.canSetSource(source)) {
                        image.saveCopy(source);
                        framesArray.add(index + ".png");
                    } else {
                        String fileName = image.getSource().getFileName();
                        image.saveCopy(source.withFileName(fileName));
                        framesArray.add(fileName);
                    }
                    continue;
                }

                LazyDataSource existingSource = image.getSource();
                if (existingSource != null) {
                    framesArray.add(existingSource.getFileName());
                } else {
                    PendingImageSave pendingSave = pendingSourceWrites.get(image);
                    if (pendingSave == null) {
                        String fileName = index + ".png";
                        pendingSave = new PendingImageSave(image, source, fileName);
                        pendingSourceWrites.put(image, pendingSave);
                    }
                    framesArray.add(pendingSave.getFileName());
                }
            }
            dataJson.add("images", framesArray);
            JsonArray markerArray = new JsonArray();
            for (Map.Entry<String, MapCursor> entry : mapMarkers.get(i).entrySet()) {
                MapCursor marker = entry.getValue();
                JsonObject markerData = new JsonObject();
                markerData.addProperty("name", entry.getKey());
                markerData.addProperty("x", marker.getX());
                markerData.addProperty("y", marker.getY());
                markerData.addProperty("type", marker.getType().name());
                markerData.addProperty("direction", marker.getDirection());
                markerData.addProperty("visible", marker.isVisible());
                markerData.addProperty("caption", marker.getCaption());
                markerArray.add(markerData);
            }
            dataJson.add("markers", markerArray);
            mapDataJson.add(dataJson);
        }
        if (!saveAsCopy) {
            savePendingSourcesInParallel(pendingSourceWrites.values());
        }
        json.add("mapdata", mapDataJson);
        storage.saveImageMapData(imageIndex, json);
        if (cachedColors != null) {
            int[] frameCounts = new int[cachedImages.length];
            for (int i = 0; i < cachedImages.length; i++) {
                frameCounts[i] = cachedImages[i] == null ? 0 : cachedImages[i].length;
            }
            PersistentColorCache.saveAnimated(this, loader.getIdentifier().asString(), width, height, cachedImages.length, frameCounts, ditheringType, imageDataRevision, cachedColors);
        }
    }

    private void savePendingSourcesInParallel(Collection<PendingImageSave> pendingWrites) throws Exception {
        if (pendingWrites.isEmpty()) {
            return;
        }
        int workerCount = Math.min(pendingWrites.size(), ImageFrame.resolveProcessingThreadCount(0));
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("ImageFrame Image Save Thread #%d").build();
        ExecutorService executor = Executors.newFixedThreadPool(workerCount, threadFactory);
        List<Future<?>> futures = new ArrayList<>(pendingWrites.size());
        try {
            for (PendingImageSave pendingWrite : pendingWrites) {
                futures.add(executor.submit(() -> {
                    ensureNotInterrupted();
                    pendingWrite.getImage().setSource(pendingWrite.getSource());
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    }
                    throw new RuntimeException(cause);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean isFrameStateIdentical(int currentFrame, int previousFrame) {
        for (LazyMappedBufferedImage[] images : cachedImages) {
            if (images == null || currentFrame >= images.length || previousFrame >= images.length || images[currentFrame] != images[previousFrame]) {
                return false;
            }
        }
        return true;
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = copy.getGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    private static List<FrameRun> collectFrameRuns(Iterator<BufferedImage> frames) {
        List<FrameRun> frameRuns = new ArrayList<>();
        BufferedImage previous = null;
        int ticks = 0;
        while (frames.hasNext()) {
            ensureNotInterrupted();
            BufferedImage current = frames.next();
            if (current == previous) {
                ticks++;
            } else {
                if (previous != null) {
                    frameRuns.add(new FrameRun(previous, ticks));
                }
                previous = current;
                ticks = 1;
            }
        }
        if (previous != null) {
            frameRuns.add(new FrameRun(previous, ticks));
        }
        return frameRuns;
    }

    private static boolean areImagesEqual(BufferedImage image, LazyMappedBufferedImage mappedImage) {
        BufferedImage previous = mappedImage.getIfLoaded();
        if (previous == null) {
            previous = mappedImage.get();
        }
        return MapUtils.areImagesEqual(image, previous);
    }

    private static long hashImage(BufferedImage image) {
        long hash = FNV1A_64_OFFSET;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash ^= image.getRGB(x, y);
                hash *= FNV1A_64_PRIME;
            }
        }
        hash ^= image.getWidth();
        hash *= FNV1A_64_PRIME;
        hash ^= image.getHeight();
        hash *= FNV1A_64_PRIME;
        return hash;
    }

    private static void ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Interrupted while processing animated map");
        }
    }

    private static class TileState {

        private final long hash;
        private final LazyMappedBufferedImage image;

        private TileState(long hash, LazyMappedBufferedImage image) {
            this.hash = hash;
            this.image = image;
        }

        public long getHash() {
            return hash;
        }

        public LazyMappedBufferedImage getImage() {
            return image;
        }
    }

    private static class FrameRun {

        private final BufferedImage image;
        private final int ticks;

        private FrameRun(BufferedImage image, int ticks) {
            this.image = image;
            this.ticks = ticks;
        }

        public BufferedImage getImage() {
            return image;
        }

        public int getTicks() {
            return ticks;
        }
    }

    private static class PendingImageSave {

        private final LazyMappedBufferedImage image;
        private final LazyDataSource source;
        private final String fileName;

        private PendingImageSave(LazyMappedBufferedImage image, LazyDataSource source, String fileName) {
            this.image = image;
            this.source = source;
            this.fileName = fileName;
        }

        public LazyMappedBufferedImage getImage() {
            return image;
        }

        public LazyDataSource getSource() {
            return source;
        }

        public String getFileName() {
            return fileName;
        }
    }

    public static class URLAnimatedImageMapRenderer extends ImageMapRenderer {

        private final URLAnimatedImageMap parent;

        public URLAnimatedImageMapRenderer(URLAnimatedImageMap parent, int index) {
            super(parent.getManager(), parent, index);
            this.parent = parent;
        }

        @Override
        public MutablePair<byte[], Collection<MapCursor>> renderMap(MapView mapView, int currentTick, Player player) {
            byte[] colors = parent.getRawAnimationColors(currentTick, index);
            Collection<MapCursor> cursors = parent.getMapMarkers().get(index).values();
            return new MutablePair<>(colors, cursors);
        }

        @Override
        public MutablePair<byte[], Collection<MapCursor>> renderMap(MapView mapView, Player player) {
            return renderMap(mapView, parent.getCurrentPositionInSequenceWithOffset(), player);
        }
    }

}
