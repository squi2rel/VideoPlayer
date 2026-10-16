package com.github.squi2rel.vp;

import com.github.squi2rel.vp.danmaku.ClientDanmakuRenderer;
import com.github.squi2rel.vp.render.ExternalTextureRegistry;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.ExternalGlTexture;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ScreenRenderer extends RenderStateShard {
    private static final ResourceLocation PLACEHOLDER = ResourceLocation.fromNamespaceAndPath("videoplayer", "placeholder.png");
    private static final ExternalTextureRegistry EXTERNAL_TEXTURES = new ExternalTextureRegistry();
    private static final Map<LayerKey, RenderType> LAYERS = new HashMap<>();
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final TransparencyStateShard PREMULTIPLIED = new TransparencyStateShard("vp_premultiplied", () -> {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }, () -> { RenderSystem.disableBlend(); RenderSystem.defaultBlendFunc(); });
    public static float cameraX, cameraY, cameraZ;
    public static double preciseCameraX, preciseCameraY, preciseCameraZ;
    public static boolean skybox;

    private ScreenRenderer() { super("videoplayer", () -> {}, () -> {}); }

    public static void render(WorldRenderContext context) {
        if (CameraRenderer.rendering) return;
        skybox = false;
        Minecraft client = Minecraft.getInstance();
        Camera camera = context.camera();
        Vec3 position = camera.getPosition();
        preciseCameraX = position.x; preciseCameraY = position.y; preciseCameraZ = position.z;
        cameraX = (float) position.x; cameraY = (float) position.y; cameraZ = (float) position.z;
        if (Vivecraft.loaded && Vivecraft.isVRActive()) ROTATION.setFromNormalized(Vivecraft.getRotation()).invert();
        else camera.rotation().invert(ROTATION);
        client.getProfiler().push("videoplayer");
        PoseStack matrices = context.matrices();
        matrices.pushPose();
        try (ByteBufferBuilder allocator = new ByteBufferBuilder(4096)) {
            ClientDanmakuRenderer.beginFrame(VideoPlayerClient.screens);
            MultiBufferSource.BufferSource consumers = MultiBufferSource.immediate(allocator);
            for (ClientVideoScreen screen : VideoPlayerClient.screens) {
                try { screen.draw(matrices, consumers); }
                catch (Exception exception) { VideoPlayerMain.LOGGER.error("Exception while rendering video", exception); }
            }
            consumers.endBatch();
        } finally {
            matrices.popPose();
            client.getProfiler().pop();
        }
    }

    public static RenderType getLayer(int texture) { return getLayer(textureIdentifier(texture)); }
    public static RenderType getLayer(ResourceLocation texture) { return layer(texture, Kind.WORLD); }
    public static RenderType getTranslucentLayer(int texture) { return getTranslucentLayer(textureIdentifier(texture)); }
    public static RenderType getTranslucentLayer(ResourceLocation texture) { return layer(texture, Kind.TRANSLUCENT); }
    public static RenderType getPremultipliedTranslucentLayer(ResourceLocation texture) { return layer(texture, Kind.PREMULTIPLIED); }
    public static RenderType getBackingLayer(int texture) { return getLayer(texture); }
    public static RenderType getGuiLayer(int texture) { return layer(textureIdentifier(texture), Kind.GUI); }
    public static RenderType getGuiTriangleLayer(int texture) { return layer(textureIdentifier(texture), Kind.GUI_TRIANGLES); }
    public static RenderType getGuiColorQuadLayer() { return layer(PLACEHOLDER, Kind.GUI_COLOR); }
    public static void removeTextureLayers(ResourceLocation texture) { LAYERS.keySet().removeIf(key -> key.texture.equals(texture)); }

    private static RenderType layer(ResourceLocation texture, Kind kind) {
        return LAYERS.computeIfAbsent(new LayerKey(texture, kind), key -> {
            boolean gui = kind == Kind.GUI || kind == Kind.GUI_PREMULTIPLIED || kind == Kind.GUI_TRIANGLES || kind == Kind.GUI_COLOR;
            boolean colorOnly = kind == Kind.GUI_COLOR;
            RenderType.CompositeState.CompositeStateBuilder state = RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(colorOnly ? GameRenderer::getPositionColorShader : GameRenderer::getPositionTexColorShader))
                    .setCullState(NO_CULL)
                    .setDepthTestState(gui ? NO_DEPTH_TEST : LEQUAL_DEPTH_TEST)
                    .setTransparencyState(kind == Kind.PREMULTIPLIED || kind == Kind.GUI_PREMULTIPLIED ? PREMULTIPLIED : TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(kind == Kind.PREMULTIPLIED || gui ? COLOR_WRITE : COLOR_DEPTH_WRITE);
            if (!colorOnly) state.setTextureState(new TextureStateShard(texture, false, false));
            return RenderType.create("videoplayer_" + kind.name().toLowerCase() + "_" + texture,
                    colorOnly ? DefaultVertexFormat.POSITION_COLOR : DefaultVertexFormat.POSITION_TEX_COLOR,
                    kind == Kind.GUI_TRIANGLES ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS,
                    4096, false, kind == Kind.TRANSLUCENT || kind == Kind.PREMULTIPLIED, state.createCompositeState(false));
        });
    }

    public static void drawGuiLayer(RenderType layer, Consumer<VertexConsumer> drawer) {
        BufferBuilder builder = Tesselator.getInstance().begin(layer.mode(), layer.format());
        drawer.accept(builder);
        MeshData mesh = builder.build();
        if (mesh != null) layer.draw(mesh);
    }

    public static void drawWorldTexturedMesh(int textureId, Matrix4f model, VertexBuffer buffer, int count, int color) {
        if (textureId < 0 || buffer == null || buffer.isInvalid() || count <= 0) return;
        RenderType layer = getLayer(textureId);
        layer.setupRenderState();
        RenderSystem.setShaderColor(((color >>> 16) & 255) / 255f, ((color >>> 8) & 255) / 255f,
                (color & 255) / 255f, (color >>> 24) / 255f);
        try {
            buffer.bind();
            buffer.drawWithShader(new Matrix4f(RenderSystem.getModelViewMatrix()).mul(model), RenderSystem.getProjectionMatrix(), GameRenderer.getPositionTexColorShader());
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            layer.clearRenderState();
        }
    }

    public static void drawGuiTexturedTriangles(GuiGraphics context, int textureId, List<GuiVertex> vertices) {
        if (vertices == null || vertices.size() < 3) return;
        context.flush();
        Matrix4f matrix = context.pose().last().pose();
        drawGuiLayer(getGuiTriangleLayer(textureId), consumer -> {
            for (int i = 0; i < vertices.size() - vertices.size() % 3; i++) {
                GuiVertex vertex = vertices.get(i);
                consumer.addVertex(matrix, vertex.x, vertex.y, 0).setUv(vertex.u, vertex.v).setColor(vertex.color);
            }
        });
    }

    public static void drawGuiPremultipliedTexturedQuad(GuiGraphics context, ResourceLocation texture,
            int x1, int y1, int x2, int y2, float u1, float u2, float v1, float v2, int color) {
        drawGuiTexturedQuad(context, texture, x1, y1, x2, y2, u1, u2, v1, v2, color, Kind.GUI_PREMULTIPLIED);
    }

    public static void drawGuiTexturedQuad(GuiGraphics context, ResourceLocation texture,
            int x1, int y1, int x2, int y2, float u1, float u2, float v1, float v2, int color) {
        drawGuiTexturedQuad(context, texture, x1, y1, x2, y2, u1, u2, v1, v2, color, Kind.GUI);
    }

    private static void drawGuiTexturedQuad(GuiGraphics context, ResourceLocation texture,
            int x1, int y1, int x2, int y2, float u1, float u2, float v1, float v2, int color, Kind kind) {
        context.flush();
        Matrix4f matrix = context.pose().last().pose();
        drawGuiLayer(layer(texture, kind), consumer -> {
            consumer.addVertex(matrix, x1, y1, 0).setUv(u1, v1).setColor(color);
            consumer.addVertex(matrix, x1, y2, 0).setUv(u1, v2).setColor(color);
            consumer.addVertex(matrix, x2, y2, 0).setUv(u2, v2).setColor(color);
            consumer.addVertex(matrix, x2, y1, 0).setUv(u2, v1).setColor(color);
        });
    }

    public static ResourceLocation textureIdentifier(int texture) {
        if (texture < 0) return PLACEHOLDER;
        ExternalTextureRegistry.Acquisition acquisition = EXTERNAL_TEXTURES.acquire(texture);
        ResourceLocation identifier = identifier(acquisition.registration());
        if (acquisition.created()) Minecraft.getInstance().getTextureManager().register(identifier, new ExternalGlTexture(texture, 1, 1));
        return identifier;
    }

    public static void releaseTexture(int texture) {
        if (texture < 0) return;
        EXTERNAL_TEXTURES.release(texture).ifPresent(ScreenRenderer::releaseTexture);
    }

    public static void clearExternalTextures() {
        List<ExternalTextureRegistry.Registration> registrations = EXTERNAL_TEXTURES.clear();
        onClient(() -> {
            for (var registration : registrations) Minecraft.getInstance().getTextureManager().release(identifier(registration));
            LAYERS.clear();
        });
    }

    private static void releaseTexture(ExternalTextureRegistry.Registration registration) {
        ResourceLocation texture = identifier(registration);
        onClient(() -> { Minecraft.getInstance().getTextureManager().release(texture); removeTextureLayers(texture); });
    }
    private static ResourceLocation identifier(ExternalTextureRegistry.Registration registration) {
        return ResourceLocation.fromNamespaceAndPath("videoplayer", registration.identifierPath());
    }
    private static void onClient(Runnable action) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) action.run(); else client.execute(action);
    }
    public static int placeholderTextureId() { return Minecraft.getInstance().getTextureManager().getTexture(PLACEHOLDER).getId(); }
    public static void rotateMatrix(PoseStack matrices) { matrices.mulPose(ROTATION); }
    public static void drawWorldTexturedVertex(Matrix4f matrix, VertexConsumer consumer, Vector3f vertex, float u, float v, int color, Vector3f normal) {
        drawWorldTexturedVertex(matrix, consumer, vertex.x, vertex.y, vertex.z, u, v, color, 0, 0, 0);
    }
    public static void drawWorldTexturedVertex(Matrix4f matrix, VertexConsumer consumer,
            float x, float y, float z, float u, float v, int color, float nx, float ny, float nz) {
        consumer.addVertex(matrix, x, y, z).setColor(color).setUv(u, v);
    }
    public record GuiVertex(float x, float y, float u, float v, int color) {}
    private record LayerKey(ResourceLocation texture, Kind kind) {}
    private enum Kind { WORLD, TRANSLUCENT, PREMULTIPLIED, GUI, GUI_PREMULTIPLIED, GUI_TRIANGLES, GUI_COLOR }
}
