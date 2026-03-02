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
import com.loohp.imageframe.api.events.ImageMapDeletedEvent;
import com.loohp.imageframe.api.events.ImageMapUpdatedEvent;
import com.loohp.imageframe.hooks.viaversion.ViaHook;
import com.loohp.imageframe.language.TranslationKey;
import com.loohp.imageframe.nms.NMS;
import com.loohp.imageframe.utils.CommandSenderUtils;
import com.loohp.imageframe.utils.FakeItemUtils;
import com.loohp.imageframe.utils.MapUtils;
import com.loohp.imageframe.utils.ModernEventsUtils;
import com.loohp.platformscheduler.Scheduler;
import com.loohp.platformscheduler.platform.folia.FoliaScheduler;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AnimatedFakeMapManager implements Listener, Runnable {

    private final Map<UUID, TrackedItemFrameData> itemFrames;
    private final Map<Player, Set<Integer>> knownMapIds;
    private final Map<Player, Set<Integer>> pendingKnownMapIds;
    private final Map<Player, PlayerAnimationState> playerStates;

    public AnimatedFakeMapManager() {
        this.itemFrames = new ConcurrentHashMap<>();
        this.knownMapIds = new ConcurrentHashMap<>();
        this.pendingKnownMapIds = new ConcurrentHashMap<>();
        this.playerStates = new ConcurrentHashMap<>();
        Scheduler.runTaskTimerAsynchronously(ImageFrame.plugin, this, 0, 1);
        Bukkit.getPluginManager().registerEvents(this, ImageFrame.plugin);
        if (ModernEventsUtils.modernEventsExists()) {
            Bukkit.getPluginManager().registerEvents(new ModernEvents(), ImageFrame.plugin);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ItemFrame) {
                    Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> handleEntity(entity), entity);
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            knownMapIds.put(player, ConcurrentHashMap.newKeySet());
            pendingKnownMapIds.put(player, ConcurrentHashMap.newKeySet());
            playerStates.put(player, new PlayerAnimationState());
        }
    }

    private Map<UUID, CompletableFuture<ItemFrameInfo>> collectItemFramesInfo(boolean async) {
        boolean isFolia = Scheduler.getPlatform() instanceof FoliaScheduler;
        Map<UUID, CompletableFuture<ItemFrameInfo>> futures = new HashMap<>();
        for (Map.Entry<UUID, TrackedItemFrameData> entry : itemFrames.entrySet()) {
            UUID uuid = entry.getKey();
            ItemFrame itemFrame = entry.getValue().getItemFrame();
            CompletableFuture<ItemFrameInfo> future = new CompletableFuture<>();
            Runnable task = () -> {
                try {
                    if (itemFrame.isValid()) {
                        Set<Player> trackedPlayers;
                        if (isFolia) {
                            try {
                                //noinspection deprecation
                                trackedPlayers = itemFrame.getTrackedPlayers();
                            } catch (Throwable e) {
                                trackedPlayers = NMS.getInstance().getEntityTrackers(itemFrame);
                            }
                        } else {
                            trackedPlayers = NMS.getInstance().getEntityTrackers(itemFrame);
                        }
                        Location location = itemFrame.getLocation();
                        future.complete(new ItemFrameInfo(
                                itemFrame.getEntityId(),
                                trackedPlayers,
                                itemFrame.getItem(),
                                location.getWorld() == null ? null : location.getWorld().getUID(),
                                location.getX(),
                                location.getY(),
                                location.getZ()));
                    } else {
                        future.complete(null);
                    }
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            };
            if (async && !isFolia) {
                task.run();
            } else {
                Scheduler.executeOrScheduleSync(ImageFrame.plugin, task, itemFrame);
            }
            futures.put(uuid, future);
        }
        return futures;
    }

    @Override
    public void run() {
        Map<UUID, CompletableFuture<ItemFrameInfo>> entityTrackers = collectItemFramesInfo(!ImageFrame.handleAnimatedMapsOnMainThread);
        List<FrameUpdateCandidate> candidates = new ArrayList<>();
        Map<Player, Set<Integer>> imagesWithinEnterByPlayer = new HashMap<>();
        Map<Player, Set<Integer>> imagesWithinExitByPlayer = new HashMap<>();
        Map<Player, PlayerLocationInfo> playerLocationInfo = new HashMap<>();
        Map<Player, Boolean> viewAnimatedCache = new HashMap<>();
        long deadline = System.currentTimeMillis() + 2000;

        for (Map.Entry<UUID, CompletableFuture<ItemFrameInfo>> entry : entityTrackers.entrySet()) {
            UUID uuid = entry.getKey();
            ItemFrameInfo frameInfo;
            try {
                frameInfo = entry.getValue().get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                frameInfo = null;
            }
            if (frameInfo == null) {
                itemFrames.remove(uuid);
                continue;
            }

            TrackedItemFrameData data = itemFrames.get(uuid);
            if (data == null) {
                continue;
            }

            int entityId = frameInfo.getEntityId();
            Set<Player> trackedPlayers = frameInfo.getTrackedPlayers();
            ItemStack itemStack = frameInfo.getItemStack();

            AnimationData animationData = data.getAnimationData();
            MapView mapView = MapUtils.getItemMapView(itemStack);

            if (mapView == null) {
                data.setAnimationData(AnimationData.EMPTY);
                continue;
            }

            if (animationData.isEmpty() || !animationData.getMapView().equals(mapView)) {
                ImageMap map = ImageFrame.imageMapManager.getFromMapView(mapView);
                if (map == null || !map.requiresAnimationService()) {
                    if (!animationData.isEmpty()) {
                        data.setAnimationData(AnimationData.EMPTY);
                    }
                    continue;
                }
                data.setAnimationData(animationData = new AnimationData(map, mapView, map.getMapViews().indexOf(mapView)));
            } else if (!animationData.isEmpty() && !animationData.getImageMap().isValid()) {
                for (Player player : trackedPlayers) {
                    FakeItemUtils.sendFakeItemChange(player, entityId, itemStack);
                }
                data.setAnimationData(AnimationData.EMPTY);
                continue;
            }

            ImageMap imageMap = animationData.getImageMap();
            if (!imageMap.requiresAnimationService()) {
                data.setAnimationData(AnimationData.EMPTY);
                continue;
            }

            int index = animationData.getIndex();
            int currentPosition = imageMap.getCurrentPositionInSequenceWithOffset();
            int mapId = imageMap.getAnimationFakeMapId(currentPosition, index, imageMap.isAnimationPaused());
            if (mapId < 0 || frameInfo.getWorldUUID() == null) {
                continue;
            }

            candidates.add(new FrameUpdateCandidate(
                    imageMap,
                    imageMap.getImageIndex(),
                    mapId,
                    currentPosition,
                    mapView,
                    entityId,
                    itemStack,
                    trackedPlayers,
                    frameInfo.getWorldUUID(),
                    frameInfo.getX(),
                    frameInfo.getY(),
                    frameInfo.getZ()));
        }

        double enterDistance = Math.max(0, ImageFrame.animatedUpdateDistanceBlocks);
        double exitDistance = enterDistance + Math.max(0, ImageFrame.animatedUpdateDistanceHysteresisBlocks);
        double enterDistanceSq = enterDistance * enterDistance;
        double exitDistanceSq = exitDistance * exitDistance;

        for (FrameUpdateCandidate candidate : candidates) {
            for (Player player : candidate.getTrackedPlayers()) {
                if (!player.isOnline()) {
                    continue;
                }
                PlayerLocationInfo playerLoc = playerLocationInfo.computeIfAbsent(player, PlayerLocationInfo::fromPlayer);
                if (playerLoc == null || !candidate.getWorldUUID().equals(playerLoc.getWorldUUID())) {
                    continue;
                }
                double distanceSq = distanceSquared(playerLoc.getX(), playerLoc.getY(), playerLoc.getZ(), candidate.getX(), candidate.getY(), candidate.getZ());
                if (distanceSq <= exitDistanceSq) {
                    imagesWithinExitByPlayer.computeIfAbsent(player, k -> new HashSet<>()).add(candidate.getImageIndex());
                }
                if (distanceSq <= enterDistanceSq) {
                    imagesWithinEnterByPlayer.computeIfAbsent(player, k -> new HashSet<>()).add(candidate.getImageIndex());
                }
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerAnimationState state = playerStates.computeIfAbsent(player, k -> new PlayerAnimationState());
            Set<Integer> previousInRange = new HashSet<>(state.getInRangeAnimatedImageIds());
            Set<Integer> withinEnter = imagesWithinEnterByPlayer.getOrDefault(player, Collections.emptySet());
            Set<Integer> withinExit = imagesWithinExitByPlayer.getOrDefault(player, Collections.emptySet());

            state.getInRangeAnimatedImageIds().clear();
            state.getInRangeAnimatedImageIds().addAll(withinEnter);
            for (Integer imageIndex : previousInRange) {
                if (withinExit.contains(imageIndex)) {
                    state.getInRangeAnimatedImageIds().add(imageIndex);
                }
            }
        }

        Map<Player, List<FakeItemUtils.ItemFrameUpdateData>> throttledUpdateData = new HashMap<>();
        Map<Player, List<FakeItemUtils.ItemFrameUpdateData>> resetUpdateData = new HashMap<>();
        Map<Player, Set<Integer>> activeAnimatedImageIds = new HashMap<>();

        for (FrameUpdateCandidate candidate : candidates) {
            FakeItemUtils.ItemFrameUpdateData throttledItemFrameUpdateData = new FakeItemUtils.ItemFrameUpdateData(
                    candidate.getEntityId(),
                    getMapItem(candidate.getMapId()),
                    candidate.getMapView().getId(),
                    candidate.getMapView(),
                    candidate.getCurrentPosition());
            FakeItemUtils.ItemFrameUpdateData resetItemFrameUpdateData = new FakeItemUtils.ItemFrameUpdateData(
                    candidate.getEntityId(),
                    candidate.getItemStack(),
                    candidate.getMapView().getId(),
                    candidate.getMapView(),
                    candidate.getCurrentPosition());

            for (Player player : candidate.getTrackedPlayers()) {
                if (!canViewAnimated(player, viewAnimatedCache)) {
                    continue;
                }
                PlayerAnimationState state = playerStates.get(player);
                if (state == null || !state.getInRangeAnimatedImageIds().contains(candidate.getImageIndex())) {
                    continue;
                }

                MapMarkerEditManager.MapMarkerEditData edit = ImageFrame.mapMarkerEditManager.getActiveEditing(player);
                if (edit != null && Objects.equals(edit.getImageMap(), candidate.getImageMap())) {
                    resetUpdateData.computeIfAbsent(player, k -> new ArrayList<>()).add(resetItemFrameUpdateData);
                    continue;
                }

                Set<Integer> knownIds = knownMapIds.get(player);
                Set<Integer> pendingIds = pendingKnownMapIds.get(player);
                if (knownIds == null || pendingIds == null) {
                    continue;
                }
                if (!knownIds.contains(candidate.getMapId())) {
                    enqueueInitialSend(state, candidate.getImageMap(), pendingIds);
                    continue;
                }

                throttledUpdateData.computeIfAbsent(player, k -> new ArrayList<>()).add(throttledItemFrameUpdateData);
                activeAnimatedImageIds.computeIfAbsent(player, k -> new HashSet<>()).add(candidate.getImageIndex());
            }
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<Player, PlayerAnimationState> entry : playerStates.entrySet()) {
            Player player = entry.getKey();
            PlayerAnimationState state = entry.getValue();
            if (!player.isOnline() || now < state.getNextInitialSendAtMs()) {
                continue;
            }
            processInitialSendQueue(player, state, now);
        }

        Map<Player, Boolean> allowThrottledUpdates = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerAnimationState state = playerStates.computeIfAbsent(player, k -> new PlayerAnimationState());
            if (!canViewAnimated(player, viewAnimatedCache)) {
                state.setThrottleActive(false);
                continue;
            }
            int activeCount = activeAnimatedImageIds.getOrDefault(player, Collections.emptySet()).size();
            boolean throttleActive = activeCount >= ImageFrame.animatedThrottleStartCount;
            handleThrottleNotice(player, state, throttleActive, now);
            int divisor = getThrottleDivisor(activeCount);
            allowThrottledUpdates.put(player, state.shouldSendThrottled(divisor));
        }

        Map<Player, List<Runnable>> sendingTasks = new HashMap<>();
        addSendingTasks(throttledUpdateData, sendingTasks, allowThrottledUpdates, false, viewAnimatedCache);
        addSendingTasks(resetUpdateData, sendingTasks, allowThrottledUpdates, true, viewAnimatedCache);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canViewAnimated(player, viewAnimatedCache)) {
                ItemStack mainhand = player.getEquipment().getItemInMainHand();
                ItemStack offhand = player.getEquipment().getItemInOffHand();
                MapView mainHandView = MapUtils.getItemMapView(mainhand);
                MapView offhandView = MapUtils.getItemMapView(offhand);
                if (mainHandView != null) {
                    ImageMap mainHandMap = ImageFrame.imageMapManager.getFromMapView(mainHandView);
                    if (mainHandMap != null && mainHandMap.requiresAnimationService()) {
                        sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(() -> mainHandMap.send(player));
                    }
                }
                if (offhandView != null && !offhandView.equals(mainHandView)) {
                    ImageMap offHandMap = ImageFrame.imageMapManager.getFromMapView(offhandView);
                    if (offHandMap != null && offHandMap.requiresAnimationService()) {
                        sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(() -> offHandMap.send(player));
                    }
                }
            }
        }

        if (ImageFrame.sendAnimatedMapsOnMainThread) {
            for (Map.Entry<Player, List<Runnable>> entry : sendingTasks.entrySet()) {
                Scheduler.runTask(ImageFrame.plugin, () -> entry.getValue().forEach(Runnable::run), entry.getKey());
            }
        } else {
            sendingTasks.values().forEach(list -> list.forEach(Runnable::run));
        }
    }

    private void addSendingTasks(Map<Player, List<FakeItemUtils.ItemFrameUpdateData>> updateData, Map<Player, List<Runnable>> sendingTasks, Map<Player, Boolean> allowThrottledUpdates, boolean bypassThrottle, Map<Player, Boolean> viewAnimatedCache) {
        for (Map.Entry<Player, List<FakeItemUtils.ItemFrameUpdateData>> entry : updateData.entrySet()) {
            Player player = entry.getKey();
            if (!canViewAnimated(player, viewAnimatedCache)) {
                continue;
            }
            if (!bypassThrottle && !allowThrottledUpdates.getOrDefault(player, true)) {
                continue;
            }
            if (ImageFrame.viaHook && ViaHook.isPlayerLegacy(player)) {
                if (!ImageFrame.viaDisableSmoothAnimationForLegacyPlayers) {
                    List<FakeItemUtils.ItemFrameUpdateData> list = entry.getValue();
                    for (FakeItemUtils.ItemFrameUpdateData data : list) {
                        sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(
                                () -> MapUtils.sendImageMap(data.getRealMapId(), data.getMapView(), data.getCurrentPosition(), Collections.singleton(player), true));
                    }
                }
            } else {
                sendingTasks.computeIfAbsent(player, k -> new ArrayList<>()).add(() -> FakeItemUtils.sendFakeItemChange(player, entry.getValue()));
            }
        }
    }

    private void processInitialSendQueue(Player player, PlayerAnimationState state, long now) {
        int attempts = state.getInitialSendQueue().size();
        for (int i = 0; i < attempts; i++) {
            Integer imageIndex = state.getInitialSendQueue().poll();
            if (imageIndex == null) {
                return;
            }

            state.getQueuedInitialSendImageIds().remove(imageIndex);
            Set<Integer> queuedFakeMapIds = state.getQueuedInitialSendFakeMapIds().remove(imageIndex);
            ImageMap imageMap = ImageFrame.imageMapManager.getFromImageId(imageIndex);
            if (imageMap == null || !imageMap.isValid() || !imageMap.requiresAnimationService()) {
                removePendingFakeMapIds(player, queuedFakeMapIds);
                continue;
            }
            if (!state.getInRangeAnimatedImageIds().contains(imageIndex)) {
                state.getInitialSendQueue().add(imageIndex);
                state.getQueuedInitialSendImageIds().add(imageIndex);
                if (queuedFakeMapIds != null && !queuedFakeMapIds.isEmpty()) {
                    state.getQueuedInitialSendFakeMapIds().put(imageIndex, queuedFakeMapIds);
                }
                continue;
            }

            imageMap.sendAnimationFakeMaps(Collections.singleton(player), (p, mapId, success) -> {
                Set<Integer> pending = pendingKnownMapIds.get(p);
                if (pending != null && pending.remove(mapId) && success) {
                    Set<Integer> known = knownMapIds.get(p);
                    if (known != null) {
                        known.add(mapId);
                    }
                }
            });
            state.setNextInitialSendAtMs(now + ImageFrame.initialSendSpacingMs);
            return;
        }
    }

    private void enqueueInitialSend(PlayerAnimationState state, ImageMap imageMap, Set<Integer> pendingIds) {
        int imageIndex = imageMap.getImageIndex();
        if (!state.getQueuedInitialSendImageIds().add(imageIndex)) {
            return;
        }
        Set<Integer> fakeMapIds = imageMap.getFakeMapIds();
        if (fakeMapIds == null || fakeMapIds.isEmpty()) {
            state.getQueuedInitialSendImageIds().remove(imageIndex);
            return;
        }
        Set<Integer> copiedFakeMapIds = new HashSet<>(fakeMapIds);
        state.getInitialSendQueue().add(imageIndex);
        state.getQueuedInitialSendFakeMapIds().put(imageIndex, copiedFakeMapIds);
        pendingIds.addAll(copiedFakeMapIds);
    }

    private void handleThrottleNotice(Player player, PlayerAnimationState state, boolean throttleActive, long now) {
        if (state.isThrottleActive() == throttleActive) {
            return;
        }
        long cooldown = Math.max(0L, ImageFrame.animatedThrottleNoticeCooldownMs);
        if (throttleActive) {
            if (now - state.getLastThrottleNoticeAtMs() >= cooldown) {
                CommandSenderUtils.sendMessage(player, ChatMessageType.ACTION_BAR, Component.translatable(TranslationKey.ANIMATED_UPDATES_THROTTLED_ACTION_BAR));
                state.setLastThrottleNoticeAtMs(now);
            }
        } else if (ImageFrame.animatedThrottleShowRestoreNotice && now - state.getLastRestoreNoticeAtMs() >= cooldown) {
            CommandSenderUtils.sendMessage(player, ChatMessageType.ACTION_BAR, Component.translatable(TranslationKey.ANIMATED_UPDATES_RESTORED_ACTION_BAR));
            state.setLastRestoreNoticeAtMs(now);
        }
        state.setThrottleActive(throttleActive);
    }

    private void removeQueuedImage(PlayerAnimationState state, int imageIndex) {
        state.getQueuedInitialSendImageIds().remove(imageIndex);
        state.getQueuedInitialSendFakeMapIds().remove(imageIndex);
        while (state.getInitialSendQueue().remove(imageIndex)) {
            // Remove all duplicates if present.
        }
        state.getInRangeAnimatedImageIds().remove(imageIndex);
    }

    private void removeQueuedImageForAllPlayers(int imageIndex) {
        for (PlayerAnimationState state : playerStates.values()) {
            removeQueuedImage(state, imageIndex);
        }
    }

    private void removePendingFakeMapIds(Player player, Set<Integer> fakeMapIds) {
        if (fakeMapIds == null || fakeMapIds.isEmpty()) {
            return;
        }
        Set<Integer> pending = pendingKnownMapIds.get(player);
        if (pending != null) {
            pending.removeAll(fakeMapIds);
        }
    }

    private int getThrottleDivisor(int activeCount) {
        int startCount = Math.max(1, ImageFrame.animatedThrottleStartCount);
        if (activeCount < startCount) {
            return 1;
        }
        int shift = Math.min(30, activeCount - startCount + 1);
        return 1 << shift;
    }

    private boolean canViewAnimated(Player player, Map<Player, Boolean> cache) {
        Boolean cached = cache.get(player);
        if (cached != null) {
            return cached;
        }
        boolean value = ImageFrame.ifPlayerManager.getIFPlayer(player.getUniqueId())
                .getPreference(IFPlayerPreference.VIEW_ANIMATED_MAPS, BooleanState.class)
                .getCalculatedValue(() -> ImageFrame.getPreferenceUnsetValue(player, IFPlayerPreference.VIEW_ANIMATED_MAPS).getRawValue(true));
        cache.put(player, value);
        return value;
    }

    private static double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    @SuppressWarnings("deprecation")
    private ItemStack getMapItem(int mapId) {
        ItemStack itemStack = new ItemStack(Material.FILLED_MAP);
        MapMeta mapMeta = (MapMeta) itemStack.getItemMeta();
        mapMeta.setMapId(mapId);
        itemStack.setItemMeta(mapMeta);
        return itemStack;
    }

    private void handleEntity(Entity entity) {
        if (!(entity instanceof ItemFrame)) {
            return;
        }
        ItemFrame itemFrame = (ItemFrame) entity;
        UUID uuid = itemFrame.getUniqueId();
        if (itemFrames.containsKey(uuid)) {
            return;
        }
        MapView mapView = MapUtils.getItemMapView(itemFrame.getItem());
        if (mapView == null) {
            itemFrames.put(uuid, new TrackedItemFrameData(itemFrame, AnimationData.EMPTY));
            return;
        }
        ImageMap map = ImageFrame.imageMapManager.getFromMapView(mapView);
        if (map == null || !map.requiresAnimationService()) {
            itemFrames.put(uuid, new TrackedItemFrameData(itemFrame, AnimationData.EMPTY));
            return;
        }
        itemFrames.put(uuid, new TrackedItemFrameData(itemFrame, new AnimationData(map, mapView, map.getMapViews().indexOf(mapView))));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> {
            for (Entity entity : chunk.getEntities()) {
                handleEntity(entity);
            }
        }, chunk);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        handleEntity(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        handleEntity(event.getRightClicked());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduler.runTaskLater(ImageFrame.plugin, () -> {
            if (player.isOnline()) {
                knownMapIds.put(player, ConcurrentHashMap.newKeySet());
                pendingKnownMapIds.put(player, ConcurrentHashMap.newKeySet());
                playerStates.put(player, new PlayerAnimationState());
            }
        }, 20, player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        knownMapIds.remove(player);
        pendingKnownMapIds.remove(player);
        playerStates.remove(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof ItemFrame) {
            UUID uuid = entity.getUniqueId();
            Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
                TrackedItemFrameData data = itemFrames.remove(uuid);
                if (data != null) {
                    itemFrames.put(uuid, data);
                }
            });
        }
    }

    @EventHandler
    public void onImageMapUpdate(ImageMapUpdatedEvent event) {
        ImageMap imageMap = event.getImageMap();
        if (!imageMap.requiresAnimationService()) {
            return;
        }
        Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
            Set<Integer> ids = imageMap.getFakeMapIds();
            if (ids != null) {
                for (Set<Integer> knownIds : knownMapIds.values()) {
                    knownIds.removeAll(ids);
                }
                for (Set<Integer> pendingKnownIds : pendingKnownMapIds.values()) {
                    pendingKnownIds.removeAll(ids);
                }
            }
            removeQueuedImageForAllPlayers(imageMap.getImageIndex());
        });
    }

    @EventHandler
    public void onImageMapDelete(ImageMapDeletedEvent event) {
        ImageMap imageMap = event.getImageMap();
        if (!imageMap.requiresAnimationService()) {
            return;
        }
        Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
            Set<Integer> ids = imageMap.getFakeMapIds();
            if (ids != null) {
                for (Set<Integer> knownIds : knownMapIds.values()) {
                    knownIds.removeAll(ids);
                }
                for (Set<Integer> pendingKnownIds : pendingKnownMapIds.values()) {
                    pendingKnownIds.removeAll(ids);
                }
            }
            removeQueuedImageForAllPlayers(imageMap.getImageIndex());
        });
    }

    public class ModernEvents implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEntityLoad(EntitiesLoadEvent event) {
            for (Entity entity : event.getEntities()) {
                handleEntity(entity);
            }
        }

    }

    public static class TrackedItemFrameData {

        private final ItemFrame itemFrame;
        private AnimationData animationData;

        public TrackedItemFrameData(ItemFrame itemFrame, AnimationData animationData) {
            this.itemFrame = itemFrame;
            this.animationData = animationData;
        }

        public ItemFrame getItemFrame() {
            return itemFrame;
        }

        public AnimationData getAnimationData() {
            return animationData;
        }

        public void setAnimationData(AnimationData animationData) {
            this.animationData = animationData;
        }

    }

    public static class AnimationData {

        public static final AnimationData EMPTY = new AnimationData(null, null, -1);

        private final ImageMap imageMap;
        private final MapView mapView;
        private final int index;

        public AnimationData(ImageMap imageMap, MapView mapView, int index) {
            this.imageMap = imageMap;
            this.mapView = mapView;
            this.index = index;
        }

        public boolean isEmpty() {
            return imageMap == null;
        }

        public ImageMap getImageMap() {
            return imageMap;
        }

        public MapView getMapView() {
            return mapView;
        }

        public int getIndex() {
            return index;
        }
    }

    public static class ItemFrameInfo {

        private final int entityId;
        private final Set<Player> trackedPlayers;
        private final ItemStack itemStack;
        private final UUID worldUUID;
        private final double x;
        private final double y;
        private final double z;

        public ItemFrameInfo(int entityId, Set<Player> trackedPlayers, ItemStack itemStack, UUID worldUUID, double x, double y, double z) {
            this.entityId = entityId;
            this.trackedPlayers = trackedPlayers;
            this.itemStack = itemStack;
            this.worldUUID = worldUUID;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getEntityId() {
            return entityId;
        }

        public Set<Player> getTrackedPlayers() {
            return trackedPlayers;
        }

        public ItemStack getItemStack() {
            return itemStack;
        }

        public UUID getWorldUUID() {
            return worldUUID;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }
    }

    public static class FrameUpdateCandidate {

        private final ImageMap imageMap;
        private final int imageIndex;
        private final int mapId;
        private final int currentPosition;
        private final MapView mapView;
        private final int entityId;
        private final ItemStack itemStack;
        private final Set<Player> trackedPlayers;
        private final UUID worldUUID;
        private final double x;
        private final double y;
        private final double z;

        public FrameUpdateCandidate(ImageMap imageMap, int imageIndex, int mapId, int currentPosition, MapView mapView, int entityId, ItemStack itemStack, Set<Player> trackedPlayers, UUID worldUUID, double x, double y, double z) {
            this.imageMap = imageMap;
            this.imageIndex = imageIndex;
            this.mapId = mapId;
            this.currentPosition = currentPosition;
            this.mapView = mapView;
            this.entityId = entityId;
            this.itemStack = itemStack;
            this.trackedPlayers = trackedPlayers;
            this.worldUUID = worldUUID;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public ImageMap getImageMap() {
            return imageMap;
        }

        public int getImageIndex() {
            return imageIndex;
        }

        public int getMapId() {
            return mapId;
        }

        public int getCurrentPosition() {
            return currentPosition;
        }

        public MapView getMapView() {
            return mapView;
        }

        public int getEntityId() {
            return entityId;
        }

        public ItemStack getItemStack() {
            return itemStack;
        }

        public Set<Player> getTrackedPlayers() {
            return trackedPlayers;
        }

        public UUID getWorldUUID() {
            return worldUUID;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }
    }

    public static class PlayerLocationInfo {

        public static PlayerLocationInfo fromPlayer(Player player) {
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world == null) {
                return null;
            }
            return new PlayerLocationInfo(world.getUID(), location.getX(), location.getY(), location.getZ());
        }

        private final UUID worldUUID;
        private final double x;
        private final double y;
        private final double z;

        public PlayerLocationInfo(UUID worldUUID, double x, double y, double z) {
            this.worldUUID = worldUUID;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public UUID getWorldUUID() {
            return worldUUID;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }
    }

    public static class PlayerAnimationState {

        private final Set<Integer> inRangeAnimatedImageIds;
        private final Queue<Integer> initialSendQueue;
        private final Set<Integer> queuedInitialSendImageIds;
        private final Map<Integer, Set<Integer>> queuedInitialSendFakeMapIds;
        private long nextInitialSendAtMs;
        private long throttleTickCounter;
        private boolean throttleActive;
        private long lastThrottleNoticeAtMs;
        private long lastRestoreNoticeAtMs;

        public PlayerAnimationState() {
            this.inRangeAnimatedImageIds = ConcurrentHashMap.newKeySet();
            this.initialSendQueue = new ConcurrentLinkedQueue<>();
            this.queuedInitialSendImageIds = ConcurrentHashMap.newKeySet();
            this.queuedInitialSendFakeMapIds = new ConcurrentHashMap<>();
            this.nextInitialSendAtMs = 0;
            this.throttleTickCounter = 0;
            this.throttleActive = false;
            this.lastThrottleNoticeAtMs = 0;
            this.lastRestoreNoticeAtMs = 0;
        }

        public Set<Integer> getInRangeAnimatedImageIds() {
            return inRangeAnimatedImageIds;
        }

        public Queue<Integer> getInitialSendQueue() {
            return initialSendQueue;
        }

        public Set<Integer> getQueuedInitialSendImageIds() {
            return queuedInitialSendImageIds;
        }

        public Map<Integer, Set<Integer>> getQueuedInitialSendFakeMapIds() {
            return queuedInitialSendFakeMapIds;
        }

        public long getNextInitialSendAtMs() {
            return nextInitialSendAtMs;
        }

        public void setNextInitialSendAtMs(long nextInitialSendAtMs) {
            this.nextInitialSendAtMs = nextInitialSendAtMs;
        }

        public boolean shouldSendThrottled(int divisor) {
            long current = throttleTickCounter++;
            return divisor <= 1 || (current % divisor) == 0;
        }

        public boolean isThrottleActive() {
            return throttleActive;
        }

        public void setThrottleActive(boolean throttleActive) {
            this.throttleActive = throttleActive;
        }

        public long getLastThrottleNoticeAtMs() {
            return lastThrottleNoticeAtMs;
        }

        public void setLastThrottleNoticeAtMs(long lastThrottleNoticeAtMs) {
            this.lastThrottleNoticeAtMs = lastThrottleNoticeAtMs;
        }

        public long getLastRestoreNoticeAtMs() {
            return lastRestoreNoticeAtMs;
        }

        public void setLastRestoreNoticeAtMs(long lastRestoreNoticeAtMs) {
            this.lastRestoreNoticeAtMs = lastRestoreNoticeAtMs;
        }
    }

}
