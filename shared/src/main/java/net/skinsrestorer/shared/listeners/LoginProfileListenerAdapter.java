/*
 * SkinsRestorer
 * Copyright (C) 2024  SkinsRestorer Team
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.skinsrestorer.shared.listeners;

import ch.jalu.configme.SettingsManager;
import lombok.RequiredArgsConstructor;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.connections.MineSkinAPI;
import net.skinsrestorer.api.connections.model.MineSkinResponse;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.exception.MineSkinException;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.storage.PlayerStorage;
import net.skinsrestorer.api.storage.SkinStorage;
import net.skinsrestorer.shared.config.AdvancedConfig;
import net.skinsrestorer.shared.config.LoginConfig;
import net.skinsrestorer.shared.listeners.event.SRLoginProfileEvent;
import net.skinsrestorer.shared.log.SRLogger;
import net.skinsrestorer.shared.storage.adapter.AdapterReference;
import net.skinsrestorer.shared.storage.adapter.StorageAdapter;

import javax.inject.Inject;
import java.util.Optional;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class LoginProfileListenerAdapter<R> {
    private final SettingsManager settings;
    private final PlayerStorage playerStorage;
    private final SRLogger logger;
    private final AdapterReference adapterReference;
    private final MineSkinAPI mineSkinAPI;
    private final SkinStorage skinStorage;

    public R handleLogin(SRLoginProfileEvent<R> event) {
        logger.debug("Handling login for %s (%s)".formatted(event.getPlayerName(), event.getPlayerUniqueId()));
        if (handleSync(event)) {
            return null;
        }

        return event.runAsync(() -> {
            try {
                handleAsync(event).ifPresent(skinProperty -> {
                    // Convert to Mojang texture if enabled (your enhancement)
                    SkinProperty finalProperty = settings.getProperty(AdvancedConfig.CONVERT_TO_MOJANG_TEXTURES) 
                        ? ensureMojangTexture(skinProperty) 
                        : skinProperty;
                    event.setResultProperty(finalProperty);
                });
            } catch (DataRequestException e) {
                logger.debug(e);
            }
        });
    }

    private boolean handleSync(SRLoginProfileEvent<R> event) {
        return settings.getProperty(AdvancedConfig.DISABLE_ON_JOIN_SKINS) || (settings.getProperty(LoginConfig.NO_SKIN_IF_LOGIN_CANCELED) && event.isCancelled());
    }

    private Optional<SkinProperty> handleAsync(SRLoginProfileEvent<R> event) throws DataRequestException {
        try {
            adapterReference.get().migrateLegacyPlayer(event.getPlayerName(), event.getPlayerUniqueId());
        } catch (StorageAdapter.StorageException e) {
            logger.severe("There was a bug while migrating a legacy player to the new format, contact us on Discord and provide this error message:", e);
        }

        return playerStorage.getSkinForPlayer(event.getPlayerUniqueId(), event.getPlayerName(), event.hasOnlineProperties());
    }

    /**
     * Enhancement: Converts non-Mojang skins to use textures.minecraft.net
     * This ensures all skins are hosted by Mojang for consistency.
     */
    private SkinProperty ensureMojangTexture(SkinProperty originalProperty) {
        try {
            // Check if already using Mojang textures
            String textureUrl = PropertyUtils.getSkinTextureUrl(originalProperty);
            if (textureUrl.contains("textures.minecraft.net")) {
                logger.debug("Skin already uses Mojang textures: " + textureUrl);
                return originalProperty;
            }

            logger.debug("Converting non-Mojang skin to Mojang textures: " + textureUrl);

            // Check cache first
            String cacheKey = "mojang-converted-" + PropertyUtils.getSkinTextureHash(originalProperty);
            try {
                var cached = adapterReference.get().getCustomSkinData(cacheKey);
                if (cached.isPresent()) {
                    logger.debug("Using cached conversion for: " + textureUrl);
                    return cached.get().getProperty();
                }
            } catch (StorageAdapter.StorageException e) {
                logger.debug("Cache lookup failed: " + e.getMessage());
            }

            // Convert through MineSkin
            MineSkinResponse response = mineSkinAPI.genSkin(textureUrl, null);
            SkinProperty convertedProperty = response.getProperty();
            
            // Cache the conversion
            try {
                skinStorage.setCustomSkinData(cacheKey, convertedProperty);
                logger.debug("Cached skin conversion: " + cacheKey);
            } catch (Exception e) {
                logger.warning("Failed to cache skin conversion: " + e.getMessage());
            }
            
            logger.info("Successfully converted skin to Mojang textures: " + textureUrl + " -> " + 
                       PropertyUtils.getSkinTextureUrl(convertedProperty));
            
            return convertedProperty;

        } catch (DataRequestException | MineSkinException e) {
            logger.warning("Failed to convert skin to Mojang textures, using original: " + e.getMessage());
            return originalProperty; // Fallback to original
        }
    }
}
