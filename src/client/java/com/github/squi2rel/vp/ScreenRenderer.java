package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.danmaku.ClientDanmakuRenderer;
import com.github.squi2rel.vp.video.ExternalGlTexture;
import com.github.squi2rel.vp.mixin.client.DrawContextAccessor;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.VideoPlayerClient.*;

@SuppressWarnings({"resource", "DataFlowIssue"})
public class ScreenRenderer {
    private static final String SAMPLER = "Sampler0";
    private static final Identifier PLACEHOLDER_TEXTURE = Identifier.of("videoplayer", "placeholder.png");
    private static final Map<Integer, Identifier> textureIds = new HashMap<>();
    private static final Map<LayerKey, RenderLayer> layers = new HashMap<>();
    private static final RenderPipeline VIDEO_WORLD_QUADS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("videoplayer", "video_world_quads"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline VIDEO_WORLD_TRIANGLE_STRIP = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("videoplayer", "video_world_triangle_strip"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLE_STRIP)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline VIDEO_WORLD_PREMULTIPLIED_QUADS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("videoplayer", "video_world_premultiplied_quads"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .withDepthWrite(false)
            .build());
    private static final RenderPipeline VIDEO_GUI_QUADS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("videoplayer", "video_gui_quads"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline VIDEO_GUI_TRIANGLES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("videoplayer", "video_gui_triangles"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline GUI_COLOR_QUADS_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("videoplayer", "gui_color_quads"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());

    static {
        registerIrisPipeline(VIDEO_WORLD_QUADS, "TEXTURED_COLOR");
        registerIrisPipeline(VIDEO_WORLD_TRIANGLE_STRIP, "TEXTURED_COLOR");
    }

    private static final Quaternionf rotation = new Quaternionf();
    public static float cameraX, cameraY, cameraZ;
    public static boolean skybox;

    public static void render(WorldRenderContext ctx) {
        if (CameraRenderer.rendering) return;
        skybox = false;
        Profiler profiler = Profilers.get();
        profiler.push("video");
        profiler.push("render");
        MatrixStack matrices = ctx.matrices();
        matrices.push();
        Camera cameraObject = MinecraftClient.getInstance().gameRenderer.getCamera();
        Vec3d camera = cameraObject.getCameraPos();
        cameraX = (float) camera.x;
        cameraY = (float) camera.y;
        cameraZ = (float) camera.z;
        if (Vivecraft.loaded && Vivecraft.isVRActive()) {
            rotation.setFromNormalized(Vivecraft.getRotation()).invert();
        } else {
            cameraObject.getRotation().invert(rotation);
        }
        ClientDanmakuRenderer.beginFrame(screens);
        BufferAllocator allocator = new BufferAllocator(4096);
        try {
            VertexConsumerProvider.Immediate consumers = VertexConsumerProvider.immediate(allocator);
            for (ClientVideoScreen screen : screens) {
                try {
                    screen.draw(matrices, consumers);
                } catch (Exception e) {
                    VideoPlayerMain.LOGGER.error("Exception while rendering", e);
                }
            }
            consumers.draw();
        } catch (RuntimeException e) {
            VideoPlayerMain.LOGGER.warn("Failed to draw video screen buffers", e);
        } finally {
            allocator.close();
        }
        matrices.pop();
        profiler.pop();
        profiler.pop();
    }

    public static RenderLayer getLayer(int textureId) {
        return texturedLayer(textureId, LayerKind.WORLD);
    }

    public static RenderLayer getLayer(Identifier texture) {
        return texturedLayer(texture, LayerKind.WORLD);
    }

    public static RenderLayer getTranslucentLayer(int textureId) {
        return texturedLayer(textureId, LayerKind.WORLD_TRANSLUCENT);
    }

    public static RenderLayer getTranslucentLayer(Identifier texture) {
        return texturedLayer(texture, LayerKind.WORLD_TRANSLUCENT);
    }

    public static RenderLayer getPremultipliedTranslucentLayer(Identifier texture) {
        return texturedLayer(texture, LayerKind.WORLD_PREMULTIPLIED_TRANSLUCENT);
    }

    public static void removeTextureLayers(Identifier texture) {
        layers.keySet().removeIf(key -> key.textureId instanceof Identifier identifier && identifier.equals(texture));
    }

    public static RenderLayer getBackingLayer(int textureId) {
        return texturedLayer(textureId, LayerKind.WORLD_BACKING);
    }

    public static RenderLayer getGuiLayer(int textureId) {
        return texturedLayer(textureId, LayerKind.GUI_QUADS);
    }

    public static RenderLayer getGuiTriangleLayer(int textureId) {
        return texturedLayer(textureId, LayerKind.GUI_TRIANGLES);
    }

    public static RenderLayer getGuiColorQuadLayer() {
        return layers.computeIfAbsent(new LayerKey(0, LayerKind.GUI_COLOR_QUADS), key ->
                RenderLayer.of("videoplayer_gui_color_quads", RenderSetup.builder(GUI_COLOR_QUADS_PIPELINE)
                        .expectedBufferSize(256)
                        .translucent()
                        .build()));
    }

    public static void drawGuiLayer(RenderLayer layer, Consumer<VertexConsumer> drawer) {
        BufferBuilder buffer = Tessellator.getInstance().begin(layer.getDrawMode(), layer.getVertexFormat());
        drawer.accept(buffer);
        BuiltBuffer built = buffer.endNullable();
        if (built != null) {
            layer.draw(built);
        }
    }

    public static void drawWorldTexturedMesh(int textureId, Matrix4f modelMatrix, GpuBuffer vertexBuffer,
                                             int vertexCount, int textureColor) {
        if (textureId < 0 || modelMatrix == null || vertexBuffer == null || vertexBuffer.isClosed() || vertexCount <= 0) {
            return;
        }

        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(textureIdentifier(textureId));
        if (texture.getGlTextureView() == null || texture.getSampler() == null) return;

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(modelMatrix);
        Matrix4f textureTransform = new Matrix4f();
        Vector3f modelOffset = new Vector3f();
        GpuBufferSlice textureTransforms = RenderSystem.getDynamicUniforms().write(
                modelView,
                color(textureColor),
                modelOffset,
                textureTransform
        );

        var framebuffer = MinecraftClient.getInstance().getFramebuffer();
        GpuTextureView colorTarget = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : framebuffer.getColorAttachmentView();
        GpuTextureView depthTarget = framebuffer.useDepthAttachment
                ? (RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride
                : framebuffer.getDepthAttachmentView())
                : null;

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "videoplayer_360_mesh",
                colorTarget,
                OptionalInt.empty(),
                depthTarget,
                OptionalDouble.empty()
        )) {
            pass.setPipeline(VIDEO_WORLD_TRIANGLE_STRIP);
            var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissor.isEnabled()) {
                pass.enableScissor(scissor.getX(), scissor.getY(), scissor.getWidth(), scissor.getHeight());
            }
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture(SAMPLER, texture.getGlTextureView(), texture.getSampler());
            pass.setVertexBuffer(0, vertexBuffer);
            pass.setUniform("DynamicTransforms", textureTransforms);
            pass.draw(0, vertexCount);
        }
    }

    public static void drawGuiTexturedTriangles(DrawContext context, int textureId, List<GuiVertex> vertices) {
        if (vertices == null || vertices.size() < 3) return;
        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(textureIdentifier(textureId));
        Matrix3x2f pose = new Matrix3x2f(context.getMatrices());
        int vertexCount = vertices.size() - vertices.size() % 3;
        List<GuiVertex> copiedVertices = List.copyOf(vertices.subList(0, vertexCount));
        ((DrawContextAccessor) context).videoplayer$getState().addSimpleElement(new GuiTexturedTrianglesRenderState(
                TextureSetup.of(texture.getGlTextureView(), texture.getSampler()),
                pose,
                copiedVertices,
                bounds(copiedVertices, pose)
        ));
    }

    public static void drawGuiPremultipliedTexturedQuad(DrawContext context, Identifier identifier,
                                                        int x1, int y1, int x2, int y2,
                                                        float u1, float u2, float v1, float v2,
                                                        int color) {
        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(identifier);
        ((DrawContextAccessor) context).videoplayer$getState().addSimpleElement(new TexturedQuadGuiElementRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.of(texture.getGlTextureView(), texture.getSampler()),
                new Matrix3x2f(context.getMatrices()),
                x1,
                y1,
                x2,
                y2,
                u1,
                u2,
                v1,
                v2,
                color,
                context.scissorStack.peekLast()
        ));
    }

    private static ScreenRect bounds(List<GuiVertex> vertices, Matrix3x2f pose) {
        Vector2f transformed = new Vector2f();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (GuiVertex vertex : vertices) {
            pose.transformPosition(vertex.x, vertex.y, transformed);
            minX = Math.min(minX, transformed.x);
            minY = Math.min(minY, transformed.y);
            maxX = Math.max(maxX, transformed.x);
            maxY = Math.max(maxY, transformed.y);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        int width = Math.max(1, (int) Math.ceil(maxX) - x);
        int height = Math.max(1, (int) Math.ceil(maxY) - y);
        return new ScreenRect(x, y, width, height);
    }

    private static RenderLayer texturedLayer(int textureId, LayerKind kind) {
        return layers.computeIfAbsent(new LayerKey(textureId, kind), key ->
                RenderLayer.of("videoplayer_" + kind.name().toLowerCase() + "_" + textureId, setup(textureId, kind)));
    }

    private static RenderLayer texturedLayer(Identifier texture, LayerKind kind) {
        return layers.computeIfAbsent(new LayerKey(texture, kind), key ->
                RenderLayer.of("videoplayer_" + kind.name().toLowerCase() + "_" + texture.toString().replace(':', '_').replace('/', '_'), setup(texture, kind)));
    }

    private static RenderSetup setup(int textureId, LayerKind kind) {
        return setup(textureIdentifier(textureId), kind);
    }

    private static RenderSetup setup(Identifier texture, LayerKind kind) {
        RenderSetup.Builder builder = RenderSetup.builder(kind.pipeline)
                .texture(SAMPLER, texture)
                .expectedBufferSize(kind.expectedBufferSize);
        if (kind.useOverlay) builder.useOverlay();
        if (kind.useLightmap) builder.useLightmap();
        if (kind.translucent) builder.translucent();
        return builder.build();
    }

    public static Identifier textureIdentifier(int textureId) {
        if (textureId < 0) return PLACEHOLDER_TEXTURE;
        return textureIds.computeIfAbsent(textureId, id -> {
            Identifier identifier = Identifier.of("videoplayer", "external_texture/" + id);
            MinecraftClient.getInstance().getTextureManager().registerTexture(identifier, new ExternalGlTexture(id, 1, 1));
            return identifier;
        });
    }

    public static int placeholderTextureId() {
        if (MinecraftClient.getInstance().getTextureManager().getTexture(PLACEHOLDER_TEXTURE).getGlTexture() instanceof GlTexture texture) {
            return texture.getGlId();
        }
        return -1;
    }

    public static void rotateMatrix(MatrixStack matrices) {
        matrices.multiply(rotation);
    }

    public static void drawWorldTexturedVertex(Matrix4f matrix, VertexConsumer consumer, Vector3f vertex,
                                               float u, float v, int color, Vector3f normal) {
        drawWorldTexturedVertex(matrix, consumer, vertex.x, vertex.y, vertex.z, u, v, color, 0.0f, 0.0f, 0.0f);
    }

    public static void drawWorldTexturedVertex(Matrix4f matrix, VertexConsumer consumer,
                                               float x, float y, float z, float u, float v, int color,
                                               float nx, float ny, float nz) {
        consumer.vertex(matrix, x, y, z)
                .color(color)
                .texture(u, v);
    }

    private static Vector4f color(int color) {
        float a = ((color >>> 24) & 0xFF) / 255.0f;
        float r = ((color >>> 16) & 0xFF) / 255.0f;
        float g = ((color >>> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        return new Vector4f(r, g, b, a);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerIrisPipeline(RenderPipeline pipeline, String shaderKeyName) {
        try {
            Class<?> shaderKeyClass = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey");
            Object shaderKey = Enum.valueOf((Class<? extends Enum>) shaderKeyClass.asSubclass(Enum.class), shaderKeyName);
            Class<?> pipelinesClass = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
            pipelinesClass.getMethod("assignPipeline", RenderPipeline.class, shaderKeyClass).invoke(null, pipeline, shaderKey);
            VideoPlayerMain.LOGGER.info("Registered Iris shader mapping {} -> {}", pipeline.getLocation(), shaderKeyName);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // Iris is optional.
        } catch (LinkageError e) {
            VideoPlayerMain.LOGGER.warn("Failed to load Iris shader mapping hooks for {}", pipeline.getLocation(), e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException && cause.getMessage() != null && cause.getMessage().contains("Shader already assigned")) {
                return;
            }
            VideoPlayerMain.LOGGER.warn("Failed to register Iris shader mapping for {}", pipeline.getLocation(), e);
        } catch (RuntimeException e) {
            VideoPlayerMain.LOGGER.warn("Failed to register Iris shader mapping for {}", pipeline.getLocation(), e);
        }
    }

    private record LayerKey(Object textureId, LayerKind kind) {
    }

    public record GuiVertex(float x, float y, float u, float v, int color) {
    }

    private record GuiTexturedTrianglesRenderState(TextureSetup textureSetup, Matrix3x2f pose, List<GuiVertex> vertices,
                                                   ScreenRect bounds) implements SimpleGuiElementRenderState {
        @Override
        public void setupVertices(VertexConsumer consumer) {
            // The vanilla GUI renderer indexes simple elements with a quad index buffer.
            // Submit each triangle as a degenerate quad so every triangle survives batching.
            for (int i = 0; i + 2 < vertices.size(); i += 3) {
                GuiVertex a = vertices.get(i);
                GuiVertex b = vertices.get(i + 1);
                GuiVertex c = vertices.get(i + 2);
                setupVertex(consumer, pose, a);
                setupVertex(consumer, pose, b);
                setupVertex(consumer, pose, c);
                setupVertex(consumer, pose, c);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return VIDEO_GUI_QUADS;
        }

        @Override
        public ScreenRect scissorArea() {
            return null;
        }
    }

    private static void setupVertex(VertexConsumer consumer, Matrix3x2f pose, GuiVertex vertex) {
        consumer.vertex(pose, vertex.x, vertex.y).texture(vertex.u, vertex.v).color(vertex.color);
    }

    private enum LayerKind {
        WORLD(VIDEO_WORLD_QUADS, 4096, false, false, false),
        WORLD_TRANSLUCENT(VIDEO_WORLD_QUADS, 4096, false, false, true),
        WORLD_PREMULTIPLIED_TRANSLUCENT(VIDEO_WORLD_PREMULTIPLIED_QUADS, 4096, false, false, true),
        WORLD_BACKING(VIDEO_WORLD_QUADS, 4096, false, false, false),
        GUI_QUADS(VIDEO_GUI_QUADS, 256, false, false, true),
        GUI_TRIANGLES(VIDEO_GUI_TRIANGLES, 256, false, false, true),
        GUI_COLOR_QUADS(GUI_COLOR_QUADS_PIPELINE, 256, false, false, true);

        private final RenderPipeline pipeline;
        private final int expectedBufferSize;
        private final boolean useOverlay;
        private final boolean useLightmap;
        private final boolean translucent;

        LayerKind(RenderPipeline pipeline, int expectedBufferSize, boolean useOverlay, boolean useLightmap, boolean translucent) {
            this.pipeline = pipeline;
            this.expectedBufferSize = expectedBufferSize;
            this.useOverlay = useOverlay;
            this.useLightmap = useLightmap;
            this.translucent = translucent;
        }
    }
}
