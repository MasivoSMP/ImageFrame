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

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.gui.GuiConfig.GuiItemTemplate;
import com.loohp.imageframe.objectholders.BooleanState;
import com.loohp.imageframe.objectholders.IFPlayer;
import com.loohp.imageframe.objectholders.IFPlayerPreference;
import com.loohp.imageframe.objectholders.ImageMap;
import static com.loohp.imageframe.utils.CommandSenderUtils.sendMessage;
import com.loohp.platformscheduler.Scheduler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ImageListMenu {

    public static final int SIZE = 54;
    public static final int IMAGE_SLOTS = ImageListMenuHolder.IMAGE_SLOTS;

    public static final int SLOT_PREV = 45;
    public static final int SLOT_CREATE = 49;
    public static final int SLOT_VIEW_ANIMATED_MAPS = 51;
    public static final int SLOT_NEXT = 53;
    public static final int SLOT_LOADING = 47;

    public static void open(Player viewer, OfflinePlayer owner) {
        open(viewer, owner.getUniqueId(), owner.getName(), 0);
    }

    public static void open(Player viewer, UUID ownerUuid, String ownerName, int page) {
        Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            buildAndOpen(viewer, ownerUuid, ownerName, page);
        }, viewer);
    }

    private static void buildAndOpen(Player viewer, UUID ownerUuid, String ownerName, int page) {
        List<ImageMap> maps = ImageFrame.imageMapManager.getFromCreator(ownerUuid, Comparator.comparing(ImageMap::getCreationTime));
        int total = maps.size();
        int maxPage = total == 0 ? 0 : Math.max(0, (total - 1) / IMAGE_SLOTS);
        int safePage = Math.max(0, Math.min(page, maxPage));
        boolean isLoading = ImageFrame.imageMapManager.isLoading();

        String ownerDisplay = ownerName == null ? ownerUuid.toString() : ownerName;
        Map<String, String> titlePlaceholders = new HashMap<>();
        titlePlaceholders.put("Owner", ownerDisplay);
        titlePlaceholders.put("OwnerUUID", ownerUuid.toString());
        titlePlaceholders.put("Page", String.valueOf(safePage + 1));
        titlePlaceholders.put("PageMax", String.valueOf(maxPage + 1));
        titlePlaceholders.put("Total", String.valueOf(total));
        String title = ImageFrame.guiConfig.listTitle(titlePlaceholders);

        ImageListMenuHolder holder = new ImageListMenuHolder(ownerUuid, ownerName, safePage);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inv);

        GuiItemTemplate fillerTemplate = ImageFrame.guiConfig.getListFiller();
        ItemStack filler = fillerTemplate.create(Collections.emptyMap());
        GuiItemTemplate prevTemplate = ImageFrame.guiConfig.getListPrev();
        GuiItemTemplate prevDisabledTemplate = ImageFrame.guiConfig.getListPrevDisabled();
        GuiItemTemplate nextTemplate = ImageFrame.guiConfig.getListNext();
        GuiItemTemplate nextDisabledTemplate = ImageFrame.guiConfig.getListNextDisabled();
        GuiItemTemplate createTemplate = ImageFrame.guiConfig.getListCreate();
        GuiItemTemplate viewAnimatedMapsOnTemplate = ImageFrame.guiConfig.getListViewAnimatedMapsOn();
        GuiItemTemplate viewAnimatedMapsOffTemplate = ImageFrame.guiConfig.getListViewAnimatedMapsOff();
        GuiItemTemplate closeTemplate = ImageFrame.guiConfig.getListClose();
        GuiItemTemplate loadingTemplate = ImageFrame.guiConfig.getListLoading();

        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler.clone());
        }

        int startIndex = safePage * IMAGE_SLOTS;
        int endIndex = Math.min(total, startIndex + IMAGE_SLOTS);
        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            ImageMap imageMap = maps.get(i);
            holder.setSlotImageId(slot, imageMap.getImageIndex());
            inv.setItem(slot, iconFor(imageMap, ImageFrame.guiConfig.getListImageIcon()));
        }

        inv.setItem(SLOT_PREV, (safePage > 0 ? prevTemplate : closeTemplate).create(Collections.emptyMap()));
        inv.setItem(SLOT_NEXT, (safePage < maxPage ? nextTemplate : nextDisabledTemplate).create(Collections.emptyMap()));
        inv.setItem(SLOT_CREATE, createTemplate.create(Collections.emptyMap()));
        IFPlayer ifPlayer = ImageFrame.ifPlayerManager.getIFPlayer(viewer.getUniqueId());
        boolean viewAnimatedMaps = ifPlayer.getPreference(IFPlayerPreference.VIEW_ANIMATED_MAPS, BooleanState.class)
                .getCalculatedValue(() -> ImageFrame.getPreferenceUnsetValue(viewer, IFPlayerPreference.VIEW_ANIMATED_MAPS).getRawValue(true));
        inv.setItem(SLOT_VIEW_ANIMATED_MAPS, (viewAnimatedMaps ? viewAnimatedMapsOnTemplate : viewAnimatedMapsOffTemplate).create(Collections.emptyMap()));

        if (isLoading) {
            inv.setItem(SLOT_LOADING, loadingTemplate.create(titlePlaceholders));
        }

        if (total == 0 && !isLoading) {
            String msg = ImageFrame.guiConfig.getListEmptyMessage();
            if (msg != null && !msg.isEmpty()) {
                sendMessage(viewer, msg);
            } else {
                sendMessage(viewer, Component.text("You have no ImageMaps.", NamedTextColor.GRAY));
            }
        }

        viewer.openInventory(inv);
    }

    private static ItemStack iconFor(ImageMap imageMap, GuiItemTemplate template) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("ImageID", String.valueOf(imageMap.getImageIndex()));
        placeholders.put("Name", imageMap.getName());
        placeholders.put("Width", String.valueOf(imageMap.getWidth()));
        placeholders.put("Height", String.valueOf(imageMap.getHeight()));
        placeholders.put("CreatorName", imageMap.getCreatorName());
        placeholders.put("CreatorUUID", imageMap.getCreator().toString());
        placeholders.put("TimeCreated", ImageFrame.dateFormat.format(new Date(imageMap.getCreationTime())));

        return template.create(placeholders, meta -> {
            if (meta instanceof MapMeta) {
                ((MapMeta) meta).setMapView(imageMap.getMapViews().get(0));
            }
        });
    }
}
