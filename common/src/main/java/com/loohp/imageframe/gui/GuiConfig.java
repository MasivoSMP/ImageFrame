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

import com.loohp.imageframe.utils.ChatColorUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.simpleyaml.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GuiConfig {

    public static final String ROOT = "Gui";

    private final String listTitle;
    private final String listEmptyMessage;

    private final String renamePromptMessage;
    private final String renameCancelledMessage;
    private final String renameInvalidNameMessage;
    private final String renameFailedMessage;

    private final GuiItemTemplate listFiller;
    private final GuiItemTemplate listPrev;
    private final GuiItemTemplate listPrevDisabled;
    private final GuiItemTemplate listNext;
    private final GuiItemTemplate listNextDisabled;
    private final GuiItemTemplate listCreate;
    private final GuiItemTemplate listViewAnimatedMapsOn;
    private final GuiItemTemplate listViewAnimatedMapsOff;
    private final GuiItemTemplate listClose;
    private final GuiItemTemplate listImageIcon;
    private final GuiItemTemplate listLoading;

    private final String confirmDeleteTitle;
    private final GuiItemTemplate confirmDeleteFiller;
    private final GuiItemTemplate confirmDeleteConfirm;
    private final GuiItemTemplate confirmDeleteCancel;

    public GuiConfig(
            String listTitle,
            String listEmptyMessage,
            String renamePromptMessage,
            String renameCancelledMessage,
            String renameInvalidNameMessage,
            String renameFailedMessage,
            GuiItemTemplate listFiller,
            GuiItemTemplate listPrev,
            GuiItemTemplate listPrevDisabled,
            GuiItemTemplate listNext,
            GuiItemTemplate listNextDisabled,
            GuiItemTemplate listCreate,
            GuiItemTemplate listViewAnimatedMapsOn,
            GuiItemTemplate listViewAnimatedMapsOff,
            GuiItemTemplate listClose,
            GuiItemTemplate listImageIcon,
            GuiItemTemplate listLoading,
            String confirmDeleteTitle,
            GuiItemTemplate confirmDeleteFiller,
            GuiItemTemplate confirmDeleteConfirm,
            GuiItemTemplate confirmDeleteCancel
    ) {
        this.listTitle = listTitle;
        this.listEmptyMessage = listEmptyMessage;
        this.renamePromptMessage = renamePromptMessage;
        this.renameCancelledMessage = renameCancelledMessage;
        this.renameInvalidNameMessage = renameInvalidNameMessage;
        this.renameFailedMessage = renameFailedMessage;
        this.listFiller = listFiller;
        this.listPrev = listPrev;
        this.listPrevDisabled = listPrevDisabled;
        this.listNext = listNext;
        this.listNextDisabled = listNextDisabled;
        this.listCreate = listCreate;
        this.listViewAnimatedMapsOn = listViewAnimatedMapsOn;
        this.listViewAnimatedMapsOff = listViewAnimatedMapsOff;
        this.listClose = listClose;
        this.listImageIcon = listImageIcon;
        this.listLoading = listLoading;
        this.confirmDeleteTitle = confirmDeleteTitle;
        this.confirmDeleteFiller = confirmDeleteFiller;
        this.confirmDeleteConfirm = confirmDeleteConfirm;
        this.confirmDeleteCancel = confirmDeleteCancel;
    }

    public static GuiConfig from(ConfigurationSection config) {
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root == null) {
            root = config.createSection(ROOT);
        }

        ConfigurationSection messages = section(root, "Messages");
        String renamePromptMessage = color(messages.getString("RenamePrompt", "&eType a new name in chat for \"{Name}\", or type 'cancel'."));
        String renameCancelledMessage = color(messages.getString("RenameCancelled", "&7Rename cancelled."));
        String renameInvalidNameMessage = color(messages.getString("RenameInvalidName", "&cInvalid name. Use a single word (no spaces), or type 'cancel'."));
        String renameFailedMessage = color(messages.getString("RenameFailed", "&cRename failed. See console for details."));

        ConfigurationSection list = section(root, "List");
        String listTitle = color(list.getString("Title", "&3ImageFrame - &b{Owner}"));
        String listEmptyMessage = color(list.getString("EmptyMessage", "&7You have no ImageMaps."));

        GuiItemTemplate listFiller = GuiItemTemplate.from(section(list, "Filler"), Material.GRAY_STAINED_GLASS_PANE, " ");
        GuiItemTemplate listPrev = GuiItemTemplate.from(section(list, "Previous"), Material.ARROW, "&ePrevious Page");
        GuiItemTemplate listPrevDisabled = GuiItemTemplate.from(section(list, "PreviousDisabled"), Material.BARRIER, "&8Previous Page");
        GuiItemTemplate listNext = GuiItemTemplate.from(section(list, "Next"), Material.ARROW, "&eNext Page");
        GuiItemTemplate listNextDisabled = GuiItemTemplate.from(section(list, "NextDisabled"), Material.BARRIER, "&8Next Page");
        ConfigurationSection createButtonSection = list.getConfigurationSection("Create");
        if (createButtonSection == null) {
            ConfigurationSection legacyRefreshSection = list.getConfigurationSection("Refresh");
            createButtonSection = legacyRefreshSection == null ? list.createSection("Create") : legacyRefreshSection;
        }
        GuiItemTemplate listCreate = GuiItemTemplate.from(createButtonSection, Material.LIME_DYE, "&aCreate Image");
        GuiItemTemplate listViewAnimatedMapsOn = GuiItemTemplate.from(section(list, "ViewAnimatedMapsOn"), Material.LIME_DYE, "&aAnimated Maps: ON");
        GuiItemTemplate listViewAnimatedMapsOff = GuiItemTemplate.from(section(list, "ViewAnimatedMapsOff"), Material.GRAY_DYE, "&cAnimated Maps: OFF");
        GuiItemTemplate listClose = GuiItemTemplate.from(section(list, "Close"), Material.BARRIER, "&cClose");
        GuiItemTemplate listImageIcon = GuiItemTemplate.from(section(list, "ImageIcon"), Material.FILLED_MAP, "&b{Name}");
        GuiItemTemplate listLoading = GuiItemTemplate.from(section(list, "Loading"), Material.RED_STAINED_GLASS_PANE, "&cLoading images...");

        ConfigurationSection confirm = section(root, "ConfirmDelete");
        String confirmDeleteTitle = color(confirm.getString("Title", "&cDelete &e{Name}&c?"));
        GuiItemTemplate confirmDeleteFiller = GuiItemTemplate.from(section(confirm, "Filler"), Material.GRAY_STAINED_GLASS_PANE, " ");
        GuiItemTemplate confirmDeleteConfirm = GuiItemTemplate.from(section(confirm, "Confirm"), Material.LIME_WOOL, "&aConfirm Delete");
        GuiItemTemplate confirmDeleteCancel = GuiItemTemplate.from(section(confirm, "Cancel"), Material.RED_WOOL, "&eCancel");

        return new GuiConfig(
                listTitle,
                listEmptyMessage,
                renamePromptMessage,
                renameCancelledMessage,
                renameInvalidNameMessage,
                renameFailedMessage,
                listFiller,
                listPrev,
                listPrevDisabled,
                listNext,
                listNextDisabled,
                listCreate,
                listViewAnimatedMapsOn,
                listViewAnimatedMapsOff,
                listClose,
                listImageIcon,
                listLoading,
                confirmDeleteTitle,
                confirmDeleteFiller,
                confirmDeleteConfirm,
                confirmDeleteCancel
        );
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection sec = parent.getConfigurationSection(key);
        if (sec == null) {
            sec = parent.createSection(key);
        }
        return sec;
    }

    private static String color(String text) {
        return ChatColorUtils.translateAlternateColorCodes('&', text);
    }

    public String listTitle(Map<String, String> placeholders) {
        return apply(listTitle, placeholders);
    }

    public String getListEmptyMessage() {
        return listEmptyMessage;
    }

    public String renamePromptMessage(Map<String, String> placeholders) {
        return apply(renamePromptMessage, placeholders);
    }

    public String getRenameCancelledMessage() {
        return renameCancelledMessage;
    }

    public String getRenameInvalidNameMessage() {
        return renameInvalidNameMessage;
    }

    public String getRenameFailedMessage() {
        return renameFailedMessage;
    }

    public GuiItemTemplate getListFiller() {
        return listFiller;
    }

    public GuiItemTemplate getListPrev() {
        return listPrev;
    }

    public GuiItemTemplate getListPrevDisabled() {
        return listPrevDisabled;
    }

    public GuiItemTemplate getListNext() {
        return listNext;
    }

    public GuiItemTemplate getListNextDisabled() {
        return listNextDisabled;
    }

    public GuiItemTemplate getListCreate() {
        return listCreate;
    }

    public GuiItemTemplate getListViewAnimatedMapsOn() {
        return listViewAnimatedMapsOn;
    }

    public GuiItemTemplate getListViewAnimatedMapsOff() {
        return listViewAnimatedMapsOff;
    }

    public GuiItemTemplate getListClose() {
        return listClose;
    }

    public GuiItemTemplate getListImageIcon() {
        return listImageIcon;
    }

    public GuiItemTemplate getListLoading() {
        return listLoading;
    }

    public String confirmDeleteTitle(Map<String, String> placeholders) {
        return apply(confirmDeleteTitle, placeholders);
    }

    public GuiItemTemplate getConfirmDeleteFiller() {
        return confirmDeleteFiller;
    }

    public GuiItemTemplate getConfirmDeleteConfirm() {
        return confirmDeleteConfirm;
    }

    public GuiItemTemplate getConfirmDeleteCancel() {
        return confirmDeleteCancel;
    }

    public static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return null;
        }
        String output = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    public static Map<String, String> imagePlaceholders(int imageId, String name, int width, int height, String creatorName, String creatorUuid, Date created) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("ImageID", String.valueOf(imageId));
        placeholders.put("Name", name == null ? "" : name);
        placeholders.put("Width", String.valueOf(width));
        placeholders.put("Height", String.valueOf(height));
        placeholders.put("CreatorName", creatorName == null ? "" : creatorName);
        placeholders.put("CreatorUUID", creatorUuid == null ? "" : creatorUuid);
        placeholders.put("TimeCreated", created == null ? "" : created.toString());
        return placeholders;
    }

    public static class GuiItemTemplate {

        private final Material material;
        private final String name;
        private final List<String> lore;
        private final Integer customModelData;

        public GuiItemTemplate(Material material, String name, List<String> lore, Integer customModelData) {
            this.material = material;
            this.name = name;
            this.lore = lore == null ? Collections.emptyList() : lore;
            this.customModelData = customModelData;
        }

        public static GuiItemTemplate from(ConfigurationSection section, Material defaultMaterial, String defaultName) {
            Material material = parseMaterial(section.getString("Material"), defaultMaterial);
            String name = color(section.getString("Name", defaultName));
            List<String> lore = section.getStringList("Lore");
            if (lore != null) {
                lore.replaceAll(GuiConfig::color);
            }
            Integer customModelData = parseCustomModelData(section.get("CustomModelData"));
            return new GuiItemTemplate(material, name, lore, customModelData);
        }

        public Material getMaterial() {
            return material;
        }

        public ItemStack create(Map<String, String> placeholders) {
            return create(placeholders, meta -> {});
        }

        public ItemStack create(Map<String, String> placeholders, java.util.function.Consumer<ItemMeta> metaCustomizer) {
            ItemStack itemStack = new ItemStack(material);
            ItemMeta meta = itemStack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(GuiConfig.apply(name, placeholders));
                if (!lore.isEmpty()) {
                    java.util.ArrayList<String> newLore = new java.util.ArrayList<>(lore.size());
                    for (String line : lore) {
                        newLore.add(GuiConfig.apply(line, placeholders));
                    }
                    meta.setLore(newLore);
                }
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                if (customModelData != null && customModelData > 0) {
                    meta.setCustomModelData(customModelData);
                }
                metaCustomizer.accept(meta);
                itemStack.setItemMeta(meta);
            }
            return itemStack;
        }

        private static Integer parseCustomModelData(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                float f = ((Number) value).floatValue();
                int rounded = Math.round(f);
                return rounded <= 0 ? null : rounded;
            }
            try {
                float f = Float.parseFloat(value.toString());
                int rounded = Math.round(f);
                return rounded <= 0 ? null : rounded;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static Material parseMaterial(String materialName, Material fallback) {
            if (materialName == null) {
                return fallback;
            }
            Material material = Material.matchMaterial(materialName);
            return material == null ? fallback : material;
        }
    }
}
