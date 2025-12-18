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

package com.loohp.imageframe.gui;

import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.objectholders.ImageMap;
import com.loohp.imageframe.objectholders.ImageMapAccessPermissionType;
import com.loohp.imageframe.utils.MapUtils;
import com.loohp.platformscheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.loohp.imageframe.language.TranslationKey.DUPLICATE_MAP_NAME;
import static com.loohp.imageframe.language.TranslationKey.IMAGE_MAP_DELETED;
import static com.loohp.imageframe.language.TranslationKey.IMAGE_MAP_RENAMED;
import static com.loohp.imageframe.language.TranslationKey.INVALID_IMAGE_MAP;
import static com.loohp.imageframe.language.TranslationKey.NO_PERMISSION;
import static com.loohp.imageframe.utils.CommandSenderUtils.sendMessage;
import static net.kyori.adventure.text.Component.translatable;

public class GuiListener implements Listener {

    private static final long MENU_INTERACTION_COOLDOWN_MS = 500L; // 10 ticks

    private final RenameSessionManager renameSessionManager;
    private final ConcurrentHashMap<UUID, Long> lastMenuInteraction;

    public GuiListener(RenameSessionManager renameSessionManager) {
        this.renameSessionManager = renameSessionManager;
        this.lastMenuInteraction = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top == null || top.getHolder() == null) {
            return;
        }

        if (!(top.getHolder() instanceof ImageListMenuHolder) && !(top.getHolder() instanceof ConfirmDeleteMenuHolder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() == null || !top.equals(event.getClickedInventory())) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        if (isOnMenuCooldown(player.getUniqueId())) {
            return;
        }
        int rawSlot = event.getRawSlot();

        if (top.getHolder() instanceof ImageListMenuHolder) {
            ImageListMenuHolder holder = (ImageListMenuHolder) top.getHolder();
            Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> handleListClick(player, holder, rawSlot, event.isLeftClick(), event.isRightClick()), player);
        } else if (top.getHolder() instanceof ConfirmDeleteMenuHolder) {
            ConfirmDeleteMenuHolder holder = (ConfirmDeleteMenuHolder) top.getHolder();
            Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> handleConfirmDeleteClick(player, holder, rawSlot), player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top != null && (top.getHolder() instanceof ImageListMenuHolder || top.getHolder() instanceof ConfirmDeleteMenuHolder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChatRename(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        RenameSessionManager.RenameSession session = renameSessionManager.get(playerUuid);
        if (session == null) {
            return;
        }
        event.setCancelled(true);

        String message = event.getMessage();
        Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> {
            RenameSessionManager.RenameSession ended = renameSessionManager.end(playerUuid);
            if (ended == null) {
                return;
            }
            if (!player.isOnline()) {
                return;
            }

            String trimmed = message == null ? "" : message.trim();
            if (trimmed.equalsIgnoreCase("cancel")) {
                sendMessage(player, Component.text("Rename cancelled.", NamedTextColor.GRAY));
                ImageListMenu.open(player, ended.getOwnerUuid(), ended.getOwnerName(), ended.getPage());
                return;
            }

            if (trimmed.isEmpty() || trimmed.contains(" ")) {
                sendMessage(player, Component.text("Invalid name. Use a single word (no spaces), or type 'cancel'.", NamedTextColor.RED));
                ImageListMenu.open(player, ended.getOwnerUuid(), ended.getOwnerName(), ended.getPage());
                return;
            }

            ImageMap imageMap = ImageFrame.imageMapManager.getFromImageId(ended.getImageId());
            if (imageMap == null || !imageMap.isValid()) {
                sendMessage(player, translatable(INVALID_IMAGE_MAP).color(NamedTextColor.RED));
                ImageListMenu.open(player, ended.getOwnerUuid(), ended.getOwnerName(), ended.getPage());
                return;
            }

            if (!player.hasPermission("imageframe.rename") || !ImageFrame.hasImageMapPermission(imageMap, player, ImageMapAccessPermissionType.EDIT)) {
                sendMessage(player, translatable(NO_PERMISSION).color(NamedTextColor.RED));
                ImageListMenu.open(player, ended.getOwnerUuid(), ended.getOwnerName(), ended.getPage());
                return;
            }

            if (ImageFrame.imageMapManager.getFromCreator(imageMap.getCreator(), trimmed) != null) {
                sendMessage(player, translatable(DUPLICATE_MAP_NAME).color(NamedTextColor.RED));
                ImageListMenu.open(player, ended.getOwnerUuid(), ended.getOwnerName(), ended.getPage());
                return;
            }

            Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
                try {
                    imageMap.rename(trimmed);
                    Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        sendMessage(player, translatable(IMAGE_MAP_RENAMED).color(NamedTextColor.GREEN));
                        ImageListMenu.open(player, ended.getOwnerUuid(), ended.getOwnerName(), ended.getPage());
                    }, player);
                } catch (Exception e) {
                    e.printStackTrace();
                    Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        sendMessage(player, Component.text("Rename failed. See console for details.", NamedTextColor.RED));
                        ImageListMenu.open(player, ended.getOwnerUuid(), ended.getOwnerName(), ended.getPage());
                    }, player);
                }
            });
        }, player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        renameSessionManager.clear(uuid);
        lastMenuInteraction.remove(uuid);
    }

    private boolean isOnMenuCooldown(UUID uuid) {
        long now = System.currentTimeMillis();
        final boolean[] onCooldown = new boolean[1];
        lastMenuInteraction.compute(uuid, (k, last) -> {
            if (last != null && (now - last) < MENU_INTERACTION_COOLDOWN_MS) {
                onCooldown[0] = true;
                return last;
            }
            return now;
        });
        return onCooldown[0];
    }

    private void handleListClick(Player player, ImageListMenuHolder holder, int rawSlot, boolean leftClick, boolean rightClick) {
        if (rawSlot < 0 || rawSlot >= ImageListMenu.SIZE) {
            return;
        }

        if (rawSlot >= 0 && rawSlot < ImageListMenuHolder.IMAGE_SLOTS) {
            int imageId = holder.getImageIdAtSlot(rawSlot);
            if (imageId < 0) {
                return;
            }
            ImageMap imageMap = ImageFrame.imageMapManager.getFromImageId(imageId);
            if (imageMap == null || !imageMap.isValid()) {
                sendMessage(player, translatable(INVALID_IMAGE_MAP).color(NamedTextColor.RED));
                ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage());
                return;
            }

            if (rightClick) {
                ConfirmDeleteMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage(), imageId);
                return;
            }

            if (leftClick) {
                if (!player.hasPermission("imageframe.rename") || !ImageFrame.hasImageMapPermission(imageMap, player, ImageMapAccessPermissionType.EDIT)) {
                    sendMessage(player, translatable(NO_PERMISSION).color(NamedTextColor.RED));
                    return;
                }
                renameSessionManager.begin(player.getUniqueId(), holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage(), imageId);
                player.closeInventory();
                sendMessage(player, Component.text("Type a new name in chat for \"" + imageMap.getName() + "\", or type 'cancel'.", NamedTextColor.YELLOW));
                return;
            }
            return;
        }

        if (rawSlot == ImageListMenu.SLOT_PREV && holder.getPage() > 0) {
            ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage() - 1);
        } else if (rawSlot == ImageListMenu.SLOT_PREV && holder.getPage() == 0) {
            player.closeInventory();
        } else if (rawSlot == ImageListMenu.SLOT_NEXT) {
            ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage() + 1);
        } else if (rawSlot == ImageListMenu.SLOT_REFRESH) {
            ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage());
        }
    }

    private void handleConfirmDeleteClick(Player player, ConfirmDeleteMenuHolder holder, int rawSlot) {
        if (rawSlot < 0 || rawSlot >= ConfirmDeleteMenu.SIZE) {
            return;
        }

        if (rawSlot == ConfirmDeleteMenu.SLOT_CANCEL) {
            ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage());
            return;
        }

        if (rawSlot != ConfirmDeleteMenu.SLOT_CONFIRM) {
            return;
        }

        int imageId = holder.getImageId();
        ImageMap imageMap = ImageFrame.imageMapManager.getFromImageId(imageId);
        if (imageMap == null || !imageMap.isValid()) {
            sendMessage(player, translatable(INVALID_IMAGE_MAP).color(NamedTextColor.RED));
            ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage());
            return;
        }

        if (!player.hasPermission("imageframe.delete") || !ImageFrame.hasImageMapPermission(imageMap, player, ImageMapAccessPermissionType.ALL)) {
            sendMessage(player, translatable(NO_PERMISSION).color(NamedTextColor.RED));
            ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage());
            return;
        }

        player.closeInventory();

        Scheduler.runTask(ImageFrame.plugin, () -> {
            boolean deleted = ImageFrame.imageMapManager.deleteMap(imageId);
            Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (deleted) {
                    sendMessage(player, translatable(IMAGE_MAP_DELETED).color(NamedTextColor.YELLOW));
                    cleanupDeletedMapsInInventory(player);
                } else {
                    sendMessage(player, translatable(INVALID_IMAGE_MAP).color(NamedTextColor.RED));
                }
                ImageListMenu.open(player, holder.getOwnerUuid(), holder.getOwnerName(), holder.getPage());
            }, player);
        }, player);
    }

    private void cleanupDeletedMapsInInventory(Player player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack currentItem = inventory.getItem(i);
            MapView currentMapView = MapUtils.getItemMapView(currentItem);
            if (currentMapView != null) {
                if (ImageFrame.imageMapManager.isMapDeleted(currentMapView) && !ImageFrame.exemptMapIdsFromDeletion.satisfies(currentMapView.getId())) {
                    inventory.setItem(i, new ItemStack(Material.MAP, currentItem.getAmount()));
                }
            }
        }
    }
}
