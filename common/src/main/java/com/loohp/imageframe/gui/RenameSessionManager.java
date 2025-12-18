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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RenameSessionManager {

    private final Map<UUID, RenameSession> sessions;

    public RenameSessionManager() {
        this.sessions = new ConcurrentHashMap<>();
    }

    public void begin(UUID playerUuid, UUID ownerUuid, String ownerName, int page, int imageId) {
        sessions.put(playerUuid, new RenameSession(ownerUuid, ownerName, page, imageId));
    }

    public RenameSession get(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    public RenameSession end(UUID playerUuid) {
        return sessions.remove(playerUuid);
    }

    public void clear(UUID playerUuid) {
        sessions.remove(playerUuid);
    }

    public static class RenameSession {
        private final UUID ownerUuid;
        private final String ownerName;
        private final int page;
        private final int imageId;

        public RenameSession(UUID ownerUuid, String ownerName, int page, int imageId) {
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
    }
}

