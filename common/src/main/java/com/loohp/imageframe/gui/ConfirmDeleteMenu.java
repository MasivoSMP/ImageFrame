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
import com.loohp.imageframe.gui.GuiConfig.GuiItemTemplate;
import com.loohp.imageframe.objectholders.ImageMap;
import com.loohp.platformscheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ConfirmDeleteMenu {

    public static final int SIZE = 27;

    public static final int SLOT_CONFIRM = 11;
    public static final int SLOT_CANCEL = 15;

    public static void open(Player viewer, UUID ownerUuid, String ownerName, int page, int imageId) {
        Scheduler.executeOrScheduleSync(ImageFrame.plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            ImageMap imageMap = ImageFrame.imageMapManager.getFromImageId(imageId);
            String mapName = imageMap == null ? ("#" + imageId) : imageMap.getName();
            Map<String, String> titlePlaceholders = new HashMap<>();
            titlePlaceholders.put("ImageID", String.valueOf(imageId));
            titlePlaceholders.put("Name", mapName);
            titlePlaceholders.put("OwnerUUID", ownerUuid.toString());
            titlePlaceholders.put("Owner", ownerName == null ? ownerUuid.toString() : ownerName);
            titlePlaceholders.put("Page", String.valueOf(page + 1));
            String title = ImageFrame.guiConfig.confirmDeleteTitle(titlePlaceholders);

            ConfirmDeleteMenuHolder holder = new ConfirmDeleteMenuHolder(ownerUuid, ownerName, page, imageId);
            Inventory inv = Bukkit.createInventory(holder, SIZE, title);
            holder.setInventory(inv);

            GuiItemTemplate fillerTemplate = ImageFrame.guiConfig.getConfirmDeleteFiller();
            ItemStack filler = fillerTemplate.create(Collections.emptyMap());
            GuiItemTemplate confirmTemplate = ImageFrame.guiConfig.getConfirmDeleteConfirm();
            GuiItemTemplate cancelTemplate = ImageFrame.guiConfig.getConfirmDeleteCancel();

            for (int i = 0; i < SIZE; i++) {
                inv.setItem(i, filler.clone());
            }

            inv.setItem(SLOT_CONFIRM, confirmTemplate.create(Collections.emptyMap()));
            inv.setItem(SLOT_CANCEL, cancelTemplate.create(Collections.emptyMap()));

            viewer.openInventory(inv);
        }, viewer);
    }
}
