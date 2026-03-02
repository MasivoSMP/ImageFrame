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

import java.util.UUID;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ImageListMenuHolder implements InventoryHolder {

    public static final int IMAGE_SLOTS = 36;

    private final UUID ownerUuid;
    private final String ownerName;
    private final int page;
    private final int[] slotToImageId;
    private Inventory inventory;

    public ImageListMenuHolder(UUID ownerUuid, String ownerName, int page) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.page = page;
        this.slotToImageId = new int[IMAGE_SLOTS];
        for (int i = 0; i < IMAGE_SLOTS; i++) {
            slotToImageId[i] = -1;
        }
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public int getPage() {
        return page;
    }

    public void setSlotImageId(int slot, int imageId) {
        if (slot < 0 || slot >= IMAGE_SLOTS) {
            return;
        }
        slotToImageId[slot] = imageId;
    }

    public int getImageIdAtSlot(int slot) {
        if (slot < 0 || slot >= IMAGE_SLOTS) {
            return -1;
        }
        return slotToImageId[slot];
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

