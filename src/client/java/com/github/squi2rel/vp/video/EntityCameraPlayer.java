package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.CameraRenderer;
import com.github.squi2rel.vp.provider.EntityViewProvider;
import com.github.squi2rel.vp.provider.VideoInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

import java.util.UUID;

public class EntityCameraPlayer extends AbstractCameraPlayer {
    private Entity entity;
    private UUID uuid;
    private int fov = 70;

    public EntityCameraPlayer(ClientVideoScreen screen) {
        super(screen);
    }

    @Override
    public void play(VideoInfo info) {
        uuid = EntityViewProvider.canonicalUuid(info.rawPath());
        entity = findEntity(uuid);
        rendered = false;
    }

    @Override
    public void stop() {
        entity = null;
        uuid = null;
        rendered = false;
    }

    @Override
    public void updateTexture() {
        if (uuid == null) return;
        if (entity == null || entity.isRemoved()) entity = findEntity(uuid);
        if (entity == null) {
            rendered = false;
            return;
        }
        super.updateTexture();
        CameraRenderer.renderWorld(entity, framebuffer, fov);
        rendered = true;
    }

    @Override
    public void onMetaChanged() {
        super.onMetaChanged();
        fov = screen.metadata.getInt(ScreenMetadata.KEY_CAMERA_FOV, 70);
    }

    private static Entity findEntity(UUID uuid) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (uuid == null || client.world == null) return null;
        for (Entity candidate : client.world.getEntities()) {
            if (uuid.equals(candidate.getUuid())) return candidate;
        }
        return null;
    }
}
