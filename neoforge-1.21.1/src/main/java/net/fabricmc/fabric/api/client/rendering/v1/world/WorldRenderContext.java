package net.fabricmc.fabric.api.client.rendering.v1.world;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;

public record WorldRenderContext(PoseStack matrices, MultiBufferSource consumers, Camera camera) {
}
