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

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class ConfirmDeleteMenuHolder implements InventoryHolder {

    private final UUID ownerUuid;
    private final String ownerName;
    private final int page;
    private final int imageId;
    private Inventory inventory;

    public ConfirmDeleteMenuHolder(UUID ownerUuid, String ownerName, int page, int imageId) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.page = page;
        this.imageId = imageId;
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

    public int getImageId() {
        return imageId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

