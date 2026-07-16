package com.github.squi2rel.vp;

import com.github.squi2rel.vp.mixin.client.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.entity.Entity;

public final class CameraRenderer {
    public static boolean rendering;
    public static int width;
    public static int height;
    public static int fov = 70;

    private CameraRenderer() {
    }

    public static void renderWorld(Entity entity, Framebuffer framebuffer, int cameraFov) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || entity == null || framebuffer == null || rendering) return;
        MinecraftClientAccessor access = (MinecraftClientAccessor) client;
        Framebuffer oldFramebuffer = access.videoplayer$getFramebuffer();
        Entity oldCamera = client.getCameraEntity();
        width = framebuffer.textureWidth;
        height = framebuffer.textureHeight;
        fov = Math.clamp(cameraFov, 1, 179);
        rendering = true;
        try {
            access.videoplayer$setFramebuffer(framebuffer);
            client.setCameraEntity(entity);
            client.gameRenderer.renderWorld(client.getRenderTickCounter());
        } finally {
            client.setCameraEntity(oldCamera);
            access.videoplayer$setFramebuffer(oldFramebuffer);
            rendering = false;
        }
    }
}
