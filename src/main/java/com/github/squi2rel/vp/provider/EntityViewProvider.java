package com.github.squi2rel.vp.provider;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EntityViewProvider implements IVideoProvider {
    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        UUID uuid = canonicalUuid(str);
        if (uuid == null) uuid = source.onlinePlayerUuid(str);
        if (uuid == null) return null;
        String id = uuid.toString();
        return CompletableFuture.completedFuture(new VideoInfo(source.name(), "ENTITY VIEW", "", id, -1, false, NO_PARAMS));
    }

    public static boolean isEntityView(VideoInfo info) {
        return info != null
                && (info.path() == null || info.path().isEmpty())
                && canonicalUuid(info.rawPath()) != null;
    }

    public static @Nullable UUID canonicalUuid(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        try {
            UUID uuid = UUID.fromString(normalized);
            return uuid.toString().equalsIgnoreCase(normalized) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
