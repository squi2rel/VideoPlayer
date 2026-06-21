package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.ClientPacketHandler;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.video.ClientVideoArea;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.ScreenGeometry;
import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.VideoScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class VideoCreationEditor {
    private static final MinecraftClient CLIENT = MinecraftClient.getInstance();
    private static final float EPSILON = 0.02f;
    private static final float SNAP_SCALE = 16.0f;
    private static final float POINT_NORMAL_OFFSET = 1.0f / 16.0f;
    private static final double POINT_HIT_RADIUS = 0.10;
    private static final double GIZMO_HIT_RADIUS = 0.08;
    private static final double GIZMO_REACH = 64.0;
    private static final float GIZMO_START = 0.08f;
    private static final float GIZMO_LENGTH = 0.55f;
    private static final double DRAG_PLANE_EPSILON = 1.0E-5;
    private static final Identifier HUD_LAYER = Identifier.of("videoplayer", "creation_editor");
    private static final VideoCreationEditor INSTANCE = new VideoCreationEditor();

    private final Draft draft = new Draft();
    private final ArrayList<SelectionPoint> points = new ArrayList<>();

    private KeyBinding openKey;
    private boolean selecting;
    private boolean selectingSpherePreset;
    private Text status = Text.empty();
    private boolean statusError;
    private int selectedPointIndex = -1;
    private GizmoAxis hoveredAxis;
    private GizmoAxis draggingAxis;
    private int draggingPointIndex = -1;
    private Vector3f dragStartPoint;
    private Vector3f dragStartIntersection;
    private Vector3f dragPlaneNormal;

    private VideoCreationEditor() {
    }

    public static VideoCreationEditor instance() {
        return INSTANCE;
    }

    public static void register() {
        INSTANCE.registerInternal();
    }

    private void registerInternal() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.videoplayer.creation_editor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.create(Identifier.of("videoplayer", "videoplayer"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            if (!selecting) return false;
            if (clickCount != 0) handlePrimaryClick();
            return true;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!selecting) return ActionResult.PASS;
            if (hand == Hand.MAIN_HAND) {
                if (draggingAxis != null) {
                    stopDragging();
                } else {
                    undoLastPoint();
                }
            }
            return ActionResult.FAIL;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        WorldRenderEvents.AFTER_ENTITIES.register(SelectionPreviewRenderer::renderWorld);
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CROSSHAIR,
                HUD_LAYER,
                SelectionPreviewRenderer::renderHud
        );
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            clear();
            return;
        }
        if (selecting && client.currentScreen == null) {
            tickSelectionInput();
        } else {
            stopDragging();
            hoveredAxis = null;
        }
        while (openKey.wasPressed()) {
            stopDragging();
            openConfigScreen();
        }
    }

    public Draft draft() {
        return draft;
    }

    public List<SelectionPoint> points() {
        return points;
    }

    public boolean selecting() {
        return selecting;
    }

    public boolean selectingSpherePreset() {
        return selectingSpherePreset;
    }

    public boolean active() {
        return selecting || CLIENT.currentScreen instanceof VideoManagementScreen || !points.isEmpty();
    }

    public Text status() {
        return status;
    }

    public boolean statusError() {
        return statusError;
    }

    public int selectedPointIndex() {
        return selectedPointIndex;
    }

    public SelectionPoint selectedPoint() {
        return validSelectedPoint() ? points.get(selectedPointIndex) : null;
    }

    public GizmoAxis hoveredAxis() {
        return hoveredAxis;
    }

    public GizmoAxis draggingAxis() {
        return draggingAxis;
    }

    public boolean screenGizmoVisible() {
        return selecting
                && CLIENT.currentScreen == null
                && !selectingSpherePreset
                && target() == Target.SCREEN
                && validSelectedPoint();
    }

    public boolean showCurrentTargetPoint() {
        if (!selecting) return false;
        if (hoveredAxis != null || draggingAxis != null) return false;
        if (hitTestPoint() >= 0) return false;
        if (selectingSpherePreset || target() == Target.AREA) return points.size() < requiredPoints();
        if (target() == Target.SCREEN && draft.screenMode == ScreenMode.FREE) {
            return points.size() < ScreenGeometry.MAX_VERTICES;
        }
        return points.size() < requiredPoints();
    }

    public float gizmoLength() {
        return GIZMO_LENGTH;
    }

    public float gizmoStart() {
        return GIZMO_START;
    }

    public int requiredPoints() {
        if (selectingSpherePreset) return 2;
        if (target() == Target.AREA) return 2;
        return draft.screenMode == ScreenMode.RECTANGLE ? 2 : ScreenGeometry.MAX_VERTICES;
    }

    public String pointProgress() {
        if (selectingSpherePreset) return points.size() + "/" + requiredPoints();
        if (target() == Target.SCREEN && draft.screenMode == ScreenMode.FREE) {
            return points.size() + "/" + ScreenGeometry.MAX_VERTICES + " (>= " + ScreenGeometry.MIN_VERTICES + ")";
        }
        return points.size() + "/" + requiredPoints();
    }

    public String modeName() {
        return modeText().getString();
    }

    public Text modeText() {
        if (selectingSpherePreset) return VpTexts.tr("label.videoplayer.mode.sphere_preset", "360 Preset");
        if (target() == Target.AREA) return VpTexts.tr("label.videoplayer.area", "Area");
        return switch (draft.operation) {
            case EDIT_SCREEN_GEOMETRY -> draft.screenMode == ScreenMode.RECTANGLE
                    ? VpTexts.tr("label.videoplayer.mode.edit_screen_rectangle", "Edit Screen Rectangle")
                    : VpTexts.tr("label.videoplayer.mode.edit_screen_free", "Edit Screen Custom");
            default -> draft.screenMode == ScreenMode.RECTANGLE
                    ? VpTexts.tr("label.videoplayer.mode.screen_rectangle", "Screen Rectangle")
                    : VpTexts.tr("label.videoplayer.mode.screen_free", "Screen Custom");
        };
    }

    public void openConfigScreen() {
        openConfigScreen(null);
    }

    public void openConfigScreen(ClientVideoScreen selectedScreen) {
        ensureDefaults();
        CLIENT.setScreen(new VideoManagementScreen(this, selecting ? null : selectedScreen));
    }

    public void beginSelection(Draft updated) {
        draft.copyFrom(updated);
        if (!validateDraft(false)) return;
        points.clear();
        resetGizmoState();
        selecting = true;
        selectingSpherePreset = false;
        if (target() == Target.SCREEN && draft.screenMode == ScreenMode.FREE) {
            setStatus("status.videoplayer.add_vertices", "Left-click to add vertices, at least 3. Right-click undo, V returns to confirm", false);
        } else {
            setStatus("status.videoplayer.select_point", "Left-click to select point %s", false, 1);
        }
        CLIENT.setScreen(null);
    }

    public void beginSpherePresetSelection(Draft updated) {
        draft.copyFrom(updated);
        if (!validateDraft(false)) return;
        points.clear();
        resetGizmoState();
        selecting = true;
        selectingSpherePreset = true;
        setStatus("status.videoplayer.select_sphere_center", "Left-click to select sphere center, then select radius point", false);
        CLIENT.setScreen(null);
    }

    public void clearSelection() {
        selecting = false;
        selectingSpherePreset = false;
        points.clear();
        resetGizmoState();
        setStatus("status.videoplayer.selection_cleared", "Current selection cleared", false);
    }

    public void clear() {
        selecting = false;
        selectingSpherePreset = false;
        points.clear();
        resetGizmoState();
        status = Text.empty();
        statusError = false;
    }

    public boolean ready() {
        if (target() == Target.AREA) return areaPreview() != null && points.size() >= 2;
        return completeVertices() != null;
    }

    public boolean confirm() {
        return confirm(null);
    }

    public boolean confirm(Consumer<ClientPacketHandler.RequestResult> callback) {
        if (!validateDraft(true)) return false;
        if (draft.operation == Operation.CREATE_AREA) {
            Box box = areaPreview();
            if (box == null) {
                setStatus("error.videoplayer.select_two_blocks", "Select two blocks first", true);
                return false;
            }
            ClientPacketHandler.createArea(
                    new Vector3f((float) box.minX, (float) box.minY, (float) box.minZ),
                    new Vector3f((float) box.maxX, (float) box.maxY, (float) box.maxZ),
                    draft.name.trim(),
                    result -> handleSubmitResult(result, callback)
            );
            setStatus("status.videoplayer.create_area_sent", "Sent create Area request: %s", false, draft.name.trim());
            return true;
        }

        ClientVideoArea area = VideoPlayerClient.areas.get(draft.areaName.trim());
        List<Vector3f> vertices = completeVertices();
        if (area == null || vertices == null) {
            setStatus("error.videoplayer.screen_selection_incomplete", "Screen selection is incomplete", true);
            return false;
        }
        if (!screenInsideArea(area, vertices)) {
            setStatus("error.videoplayer.screen_vertices_outside_area", "Screen vertices must be inside the Area", true);
            return false;
        }
        if (draft.surface == ScreenSurface.SPHERE_360 && !sphereReady()) {
            setStatus("error.videoplayer.sphere_preset_required", "Define 360 parameters first", true);
            return false;
        }
        if (draft.spherePreset && !spherePresetInsideArea(area)) {
            setStatus("error.videoplayer.sphere_center_outside_area", "360 sphere center must be inside the Area", true);
            return false;
        }
        if (draft.operation == Operation.CREATE_SCREEN) {
            VideoScreen screen = new VideoScreen(
                    area,
                    draft.name.trim(),
                    vertices,
                    draft.source.trim()
            );
            applyDraftDisplay(screen);
            ClientPacketHandler.createScreen(screen, result -> handleSubmitResult(result, callback));
            setStatus("status.videoplayer.create_screen_sent", "Sent create Screen request: %s", false, draft.name.trim());
        } else {
            ClientVideoScreen screen = area.getScreen(draft.name.trim());
            if (screen == null) {
                setStatus("error.videoplayer.select_loaded_screen", "Select a loaded Screen", true);
                return false;
            }
            VideoScreen displayConfig = new VideoScreen(area, screen.name, vertices, draft.source.trim());
            applyDraftDisplay(displayConfig);
            ClientPacketHandler.updateScreen(screen, vertices, draft.source.trim(), displayConfig, result -> handleSubmitResult(result, callback));
            setStatus("status.videoplayer.update_screen_sent", "Sent update Screen request: %s", false, draft.name.trim());
        }
        return true;
    }

    private void handleSubmitResult(ClientPacketHandler.RequestResult result, Consumer<ClientPacketHandler.RequestResult> callback) {
        if (ClientPacketHandler.denied(result)) {
            setStatus("error.videoplayer.permission_denied", "Permission denied", true);
            if (callback != null) callback.accept(result);
            return;
        }
        if (result != null && result.status() == com.github.squi2rel.vp.network.RequestResultStatus.OK) {
            clearAfterSubmit();
        }
        if (callback != null) callback.accept(result);
    }

    private void clearAfterSubmit() {
        selecting = false;
        selectingSpherePreset = false;
        points.clear();
        resetGizmoState();
        if (draft.operation != Operation.EDIT_SCREEN_GEOMETRY) {
            draft.name = "";
        }
        ensureDefaults();
    }

    public List<String> areaNames() {
        return VideoPlayerClient.areas.values().stream()
                .map(area -> area.name)
                .sorted()
                .toList();
    }

    public List<String> realScreenNames(String areaName) {
        ClientVideoArea area = VideoPlayerClient.areas.get(areaName);
        if (area == null) return List.of();
        return area.screens.stream()
                .filter(screen -> screen.source == null || screen.source.isEmpty())
                .map(screen -> screen.name)
                .sorted()
                .toList();
    }

    public String suggestedAreaName() {
        return uniqueAreaName();
    }

    public String suggestedScreenName(String areaName) {
        return uniqueScreenName(areaName);
    }

    public Box areaPreview() {
        if (target() != Target.AREA) return null;
        if (points.size() >= 2) {
            return areaBox(points.get(0).blockPos, points.get(1).blockPos);
        }
        if (selecting && points.size() == 1) {
            BlockHitResult target = currentBlockHit();
            if (target != null) return areaBox(points.getFirst().blockPos, target.getBlockPos());
        }
        return null;
    }

    public List<Vector3f> previewVertices() {
        if (target() != Target.SCREEN) return null;
        if (draft.screenMode == ScreenMode.RECTANGLE) {
            if (points.size() >= 2) return asList(rectangleQuad(points.get(0), points.get(1)));
            if (showCurrentTargetPoint() && points.size() == 1) {
                BlockHitResult target = currentBlockHit();
                if (target != null) return asList(rectangleQuad(points.getFirst(), selectionPointFrom(target)));
            }
            return null;
        }

        ArrayList<SelectionPoint> preview = new ArrayList<>(points);
        if (showCurrentTargetPoint() && points.size() < ScreenGeometry.MAX_VERTICES) {
            BlockHitResult target = currentBlockHit();
            if (target != null) preview.add(selectionPointFrom(target));
        }
        if (preview.size() >= ScreenGeometry.MIN_VERTICES) return freeVertices(preview, false);
        return null;
    }

    public List<Vector3f> completeVertices() {
        if (target() != Target.SCREEN) return null;
        if (draft.screenMode == ScreenMode.RECTANGLE) {
            return points.size() >= 2 ? asList(rectangleQuad(points.get(0), points.get(1))) : null;
        }
        return points.size() >= ScreenGeometry.MIN_VERTICES ? freeVertices(points, false) : null;
    }

    public SelectionPoint currentTargetPoint() {
        BlockHitResult target = currentBlockHit();
        return target == null ? null : selectionPointFrom(target);
    }

    public void ensureDefaults() {
        if (draft.operation == null) {
            draft.operation = draft.target == Target.SCREEN ? Operation.CREATE_SCREEN : Operation.CREATE_AREA;
        }
        draft.target = draft.operation.target();
        if (draft.target == null) draft.target = Target.AREA;
        if (draft.screenMode == null) draft.screenMode = ScreenMode.RECTANGLE;
        if (draft.surface == null) draft.surface = ScreenSurface.FLAT;
        if (draft.sphereRadius <= 0 || !Float.isFinite(draft.sphereRadius)) draft.sphereRadius = 10;
        draft.sphereLat = VideoScreen.clampSphereSegments(draft.sphereLat);
        draft.sphereLon = VideoScreen.clampSphereSegments(draft.sphereLon);
        if (!Float.isFinite(draft.sphereRotX)) draft.sphereRotX = 0;
        if (!Float.isFinite(draft.sphereRotY)) draft.sphereRotY = 0;
        if (!Float.isFinite(draft.sphereRotZ)) draft.sphereRotZ = 0;
        if (draft.areaName == null) draft.areaName = "";
        if (draft.name == null) draft.name = "";
        if (draft.source == null) draft.source = "";

        if (target() == Target.SCREEN) {
            if (!VideoPlayerClient.areas.containsKey(draft.areaName)) {
                draft.areaName = areaNames().stream().findFirst().orElse("");
            }
            if (draft.operation == Operation.CREATE_SCREEN && draft.name.isBlank()) {
                draft.name = uniqueScreenName(draft.areaName);
            } else if (draft.operation == Operation.EDIT_SCREEN_GEOMETRY && !draft.areaName.isBlank()) {
                ClientVideoArea area = VideoPlayerClient.areas.get(draft.areaName);
                if (area != null && (draft.name.isBlank() || area.getScreen(draft.name) == null)) {
                    draft.name = area.screens.stream().map(screen -> screen.name).sorted().findFirst().orElse("");
                }
            }
        } else if (draft.name.isBlank()) {
            draft.name = uniqueAreaName();
        }
    }

    private boolean validateDraft(boolean requireSelection) {
        if (!VideoPlayerClient.connected && !VideoPlayerClient.config.alwaysConnected) {
            setStatus("error.videoplayer.not_connected", "Not connected to server", true);
            return false;
        }
        String name = draft.name.trim();
        if (name.isEmpty()) {
            setStatus("error.videoplayer.name_empty", "Name must not be empty", true);
            return false;
        }
        if (name.length() > VideoScreen.MAX_NAME_LENGTH) {
            setStatus("error.videoplayer.name_too_long_plain", "Name must not exceed %s characters", true, VideoScreen.MAX_NAME_LENGTH);
            return false;
        }
        if (draft.operation == Operation.CREATE_AREA) {
            if (VideoPlayerClient.areas.containsKey(name)) {
                setStatus("error.videoplayer.area_same_name_exists", "An Area with the same name already exists", true);
                return false;
            }
            if (requireSelection && areaPreview() == null) {
                setStatus("error.videoplayer.select_two_blocks", "Select two blocks first", true);
                return false;
            }
            return true;
        }

        ClientVideoArea area = VideoPlayerClient.areas.get(draft.areaName.trim());
        if (area == null) {
            setStatus("error.videoplayer.select_loaded_area", "Select a loaded Area", true);
            return false;
        }
        ClientVideoScreen existingScreen = area.getScreen(name);
        if (draft.operation == Operation.CREATE_SCREEN && existingScreen != null) {
            setStatus("error.videoplayer.screen_same_name_exists", "This Area already contains a Screen with the same name", true);
            return false;
        }
        if (draft.operation == Operation.EDIT_SCREEN_GEOMETRY && existingScreen == null) {
            setStatus("error.videoplayer.select_loaded_screen", "Select a loaded Screen", true);
            return false;
        }
        String source = draft.source.trim();
        if (!source.isEmpty() && source.equals(name)) {
            setStatus("error.videoplayer.source_screen_self", "Source Screen cannot point to itself", true);
            return false;
        }
        if (!source.isEmpty() && area.getScreen(source) == null) {
            setStatus("error.videoplayer.source_screen_missing", "Source Screen does not exist", true);
            return false;
        }
        if (draft.surface == ScreenSurface.SPHERE_360 && !sphereReady()) {
            setStatus("error.videoplayer.sphere_preset_required", "Define 360 parameters first", true);
            return false;
        }
        if (draft.spherePreset && !spherePresetInsideArea(area)) {
            setStatus("error.videoplayer.sphere_center_outside_area", "360 sphere center must be inside the Area", true);
            return false;
        }
        if (requireSelection) {
            Text previousStatus = status;
            boolean previousError = statusError;
            if (completeVerticesForSubmit() == null) {
                if (status == previousStatus && statusError == previousError) {
                    setStatus("error.videoplayer.complete_screen_points", "Complete screen point selection", true);
                }
                return false;
            }
        }
        return true;
    }

    private void handlePrimaryClick() {
        if (handleScreenPointClick()) return;
        if (handleExistingPointClick()) return;
        selectCurrentTarget();
    }

    private boolean handleScreenPointClick() {
        if (!screenPointEditingEnabled()) return false;
        if (draggingAxis != null) return true;
        updateHoveredAxis();
        if (hoveredAxis != null && beginDragging(hoveredAxis)) {
            return true;
        }
        int pointIndex = hitTestPoint();
        if (pointIndex >= 0) {
            selectedPointIndex = pointIndex;
            setStatus("status.videoplayer.point_selected_with_gizmo", "Selected point %s. Drag X/Y/Z arrows to adjust", false, pointIndex + 1);
            return true;
        }
        return false;
    }

    private boolean handleExistingPointClick() {
        if (!selecting || CLIENT.currentScreen != null || points.isEmpty()) return false;
        int pointIndex = hitTestPoint();
        if (pointIndex < 0) return false;
        selectedPointIndex = pointIndex;
        setStatus("status.videoplayer.point_selected", "Selected point %s", false, pointIndex + 1);
        return true;
    }

    private void selectCurrentTarget() {
        if (selectionPointLimitReached()) {
            if (target() == Target.SCREEN && !selectingSpherePreset) {
                setStatus("status.videoplayer.vertex_limit_reached", "Vertex limit reached. Drag arrows to adjust, press V to return and confirm, right-click undo to keep selecting", false);
            } else {
                setStatus("status.videoplayer.selection_complete_adjustable", "Selection complete. Press V to return and confirm, right-click undo to adjust", false);
                openConfigScreen();
            }
            return;
        }
        BlockHitResult target = currentBlockHit();
        if (target == null) {
            setStatus("error.videoplayer.look_at_block", "Look at a block, then left-click to select a point", true);
            return;
        }
        SelectionPoint point = selectionPointFrom(target);
        if (target() == Target.SCREEN && draft.screenMode == ScreenMode.FREE && points.size() >= ScreenGeometry.MAX_VERTICES) {
            setStatus("error.videoplayer.vertex_limit", "Vertex count has reached the limit %s", true, ScreenGeometry.MAX_VERTICES);
            return;
        }
        points.add(point);
        if (target() == Target.SCREEN && !selectingSpherePreset) {
            selectedPointIndex = points.size() - 1;
        }
        if (selectingSpherePreset) {
            updateSphereDraftFromPoints();
            if (points.size() >= requiredPoints()) {
                selecting = false;
                selectingSpherePreset = false;
                points.clear();
                setStatus("status.videoplayer.sphere_preset_updated", "360 preset updated", false);
                openConfigScreen();
                return;
            }
            setStatus("status.videoplayer.select_radius_point", "Left-click to select radius point", false);
            return;
        }
        if (target() == Target.SCREEN && draft.screenMode == ScreenMode.FREE) {
            List<Vector3f> vertices = completeVerticesForSubmit();
            if (points.size() < ScreenGeometry.MIN_VERTICES) {
                setStatus("status.videoplayer.select_free_point_min", "Left-click to select point %s. At least %s points are required. Current point can be adjusted with arrows", false, points.size() + 1, ScreenGeometry.MIN_VERTICES);
                return;
            }
            if (vertices == null) {
                return;
            }
            if (points.size() >= ScreenGeometry.MAX_VERTICES) {
                setStatus("status.videoplayer.selection_complete_drag", "Selection complete. Drag arrows to adjust, press V to return and confirm", false);
                return;
            }
            setStatus("status.videoplayer.free_points_selected", "Selected %s vertices. Press V to return and confirm, keep left-clicking to add points. Current point can be adjusted with arrows", false, points.size());
            return;
        }
        if (points.size() >= requiredPoints()) {
            if (target() == Target.SCREEN && completeVerticesForSubmit() == null) {
                setStatus("error.videoplayer.screen_points_invalid_adjust", "Screen points are invalid. Drag arrows to adjust or right-click undo", true);
                return;
            }
            if (target() == Target.SCREEN) {
                setStatus("status.videoplayer.selection_complete_drag", "Selection complete. Drag arrows to adjust, press V to return and confirm", false);
            } else {
                setStatus("status.videoplayer.selection_complete", "Selection complete. Press V to return and confirm", false);
                openConfigScreen();
            }
            return;
        }
        setStatus("status.videoplayer.select_point", "Left-click to select point %s", false, points.size() + 1);
    }

    private boolean selectionPointLimitReached() {
        if (selectingSpherePreset) return points.size() >= requiredPoints();
        if (target() == Target.SCREEN && draft.screenMode == ScreenMode.FREE) {
            return points.size() >= ScreenGeometry.MAX_VERTICES;
        }
        return points.size() >= requiredPoints();
    }

    private void undoLastPoint() {
        stopDragging();
        if (points.isEmpty()) {
            setStatus("status.videoplayer.no_point_to_undo", "There are no points to undo", false);
            return;
        }
        points.removeLast();
        selectedPointIndex = Math.min(selectedPointIndex, points.size() - 1);
        if (selectedPointIndex < 0 && !points.isEmpty() && target() == Target.SCREEN && !selectingSpherePreset) {
            selectedPointIndex = points.size() - 1;
        }
        if (selectingSpherePreset) {
            updateSphereDraftFromPoints();
            setStatus("status.videoplayer.undo_select_point", "Undone. Left-click to select point %s", false, points.size() + 1);
            return;
        }
        if (target() == Target.SCREEN && draft.screenMode == ScreenMode.FREE) {
            setStatus("status.videoplayer.undo_free_points", "Undone. Current vertices: %s. Left-click to continue, V to return", false, points.size());
            return;
        }
        setStatus("status.videoplayer.undo_select_point", "Undone. Left-click to select point %s", false, points.size() + 1);
    }

    private BlockHitResult currentBlockHit() {
        HitResult target = CLIENT.crosshairTarget;
        if (target == null || target.getType() != HitResult.Type.BLOCK) return null;
        return (BlockHitResult) target;
    }

    private SelectionPoint selectionPointFrom(BlockHitResult hit) {
        return SelectionPoint.from(hit, selectingSpherePreset || target() == Target.SCREEN);
    }

    private void tickSelectionInput() {
        if (!screenPointEditingEnabled()) {
            stopDragging();
            hoveredAxis = null;
            return;
        }
        if (draggingAxis != null) {
            if (leftMousePressed()) {
                dragSelectedPoint();
            } else {
                stopDragging();
                updateHoveredAxis();
            }
            return;
        }
        updateHoveredAxis();
    }

    private boolean leftMousePressed() {
        return GLFW.glfwGetMouseButton(CLIENT.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private boolean screenPointEditingEnabled() {
        return selecting
                && CLIENT.currentScreen == null
                && !selectingSpherePreset
                && target() == Target.SCREEN
                && !points.isEmpty();
    }

    private boolean validSelectedPoint() {
        return selectedPointIndex >= 0 && selectedPointIndex < points.size();
    }

    private void updateHoveredAxis() {
        hoveredAxis = hitTestGizmo();
    }

    private int hitTestPoint() {
        Ray ray = currentRay();
        if (ray == null) return -1;

        double bestDistance = POINT_HIT_RADIUS * POINT_HIT_RADIUS;
        int bestIndex = -1;
        for (int i = 0; i < points.size(); i++) {
            double distance = distanceRayPointSq(ray, points.get(i).point);
            if (distance <= bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private GizmoAxis hitTestGizmo() {
        if (!validSelectedPoint()) return null;
        Ray ray = currentRay();
        if (ray == null) return null;

        Vector3f point = points.get(selectedPointIndex).point;
        double bestDistance = GIZMO_HIT_RADIUS * GIZMO_HIT_RADIUS;
        GizmoAxis bestAxis = null;
        for (GizmoAxis axis : GizmoAxis.values()) {
            Vector3f axisVector = axis.vector();
            Vector3f start = new Vector3f(point).add(new Vector3f(axisVector).mul(GIZMO_START));
            Vector3f end = new Vector3f(point).add(new Vector3f(axisVector).mul(GIZMO_LENGTH));
            double distance = distanceRaySegmentSq(ray, start, end);
            if (distance <= bestDistance) {
                bestDistance = distance;
                bestAxis = axis;
            }
        }
        return bestAxis;
    }

    private boolean beginDragging(GizmoAxis axis) {
        if (!validSelectedPoint()) return false;
        Vector3f point = points.get(selectedPointIndex).point;
        Vector3f normal = createDragPlaneNormal(axis, point);
        Vector3f intersection = intersectDragPlane(point, normal);
        if (intersection == null) return false;

        draggingAxis = axis;
        draggingPointIndex = selectedPointIndex;
        dragStartPoint = new Vector3f(point);
        dragStartIntersection = intersection;
        dragPlaneNormal = normal;
        hoveredAxis = axis;
        setStatus("status.videoplayer.drag_axis", "Dragging %s axis. Coordinates snap to 1/16 block", false, axis.label());
        return true;
    }

    private void dragSelectedPoint() {
        if (draggingAxis == null || draggingPointIndex < 0 || draggingPointIndex >= points.size()) {
            stopDragging();
            return;
        }
        Vector3f intersection = intersectDragPlane(dragStartPoint, dragPlaneNormal);
        if (intersection == null) return;

        Vector3f axisVector = draggingAxis.vector();
        float along = new Vector3f(intersection).sub(dragStartIntersection).dot(axisVector);
        Vector3f next = new Vector3f(dragStartPoint).add(new Vector3f(axisVector).mul(along));
        snapPoint(next);
        points.get(draggingPointIndex).point.set(next);
        selectedPointIndex = draggingPointIndex;
        updateMovedPointStatus();
    }

    private void updateMovedPointStatus() {
        if (!validSelectedPoint()) return;
        boolean enoughPoints = draft.screenMode == ScreenMode.RECTANGLE
                ? points.size() >= 2
                : points.size() >= ScreenGeometry.MIN_VERTICES;
        if (enoughPoints && completeVerticesForSubmit() == null) {
            if (!statusError) {
                setStatus("error.videoplayer.screen_points_invalid_continue", "Screen points are invalid. Continue adjusting", true);
            }
            return;
        }
        setStatus("status.videoplayer.point_position", "Point %s %s. Press V to return and confirm", false, selectedPointIndex + 1, points.get(selectedPointIndex).format());
    }

    private void stopDragging() {
        draggingAxis = null;
        draggingPointIndex = -1;
        dragStartPoint = null;
        dragStartIntersection = null;
        dragPlaneNormal = null;
    }

    private void resetGizmoState() {
        selectedPointIndex = -1;
        hoveredAxis = null;
        stopDragging();
    }

    private Vector3f createDragPlaneNormal(GizmoAxis axis, Vector3f point) {
        Vec3d eye = CLIENT.player == null ? new Vec3d(point.x, point.y, point.z) : CLIENT.player.getEyePos();
        Vector3f toCamera = new Vector3f((float) (eye.x - point.x), (float) (eye.y - point.y), (float) (eye.z - point.z));
        if (toCamera.lengthSquared() < EPSILON * EPSILON) {
            toCamera.set(0, 0, 1);
        } else {
            toCamera.normalize();
        }

        Vector3f axisVector = axis.vector();
        Vector3f normal = new Vector3f(toCamera).sub(new Vector3f(axisVector).mul(toCamera.dot(axisVector)));
        if (normal.lengthSquared() < EPSILON * EPSILON) {
            normal = axis == GizmoAxis.Y ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            normal.sub(new Vector3f(axisVector).mul(normal.dot(axisVector)));
        }
        return normal.normalize();
    }

    private Vector3f intersectDragPlane(Vector3f planePoint, Vector3f planeNormal) {
        if (planePoint == null || planeNormal == null) return null;
        Ray ray = currentRay();
        if (ray == null) return null;
        double denominator = planeNormal.x * ray.direction.x + planeNormal.y * ray.direction.y + planeNormal.z * ray.direction.z;
        if (Math.abs(denominator) < DRAG_PLANE_EPSILON) return null;

        double t = ((planePoint.x - ray.origin.x) * planeNormal.x
                + (planePoint.y - ray.origin.y) * planeNormal.y
                + (planePoint.z - ray.origin.z) * planeNormal.z) / denominator;
        if (t < 0 || t > GIZMO_REACH) return null;
        return new Vector3f(
                (float) (ray.origin.x + ray.direction.x * t),
                (float) (ray.origin.y + ray.direction.y * t),
                (float) (ray.origin.z + ray.direction.z * t)
        );
    }

    private Ray currentRay() {
        if (CLIENT.player == null) return null;
        Vec3d direction = CLIENT.player.getRotationVec(1.0f);
        if (direction.lengthSquared() <= 0) return null;
        return new Ray(CLIENT.player.getEyePos(), direction.normalize());
    }

    private double distanceRayPointSq(Ray ray, Vector3f point) {
        Vec3d target = toVec3d(point);
        Vec3d toTarget = target.subtract(ray.origin);
        double along = toTarget.dotProduct(ray.direction);
        if (along < 0 || along > GIZMO_REACH) return Double.POSITIVE_INFINITY;
        Vec3d closest = ray.origin.add(ray.direction.multiply(along));
        return target.squaredDistanceTo(closest);
    }

    private double distanceRaySegmentSq(Ray ray, Vector3f start, Vector3f end) {
        Vec3d rayStart = ray.origin;
        Vec3d rayEnd = ray.origin.add(ray.direction.multiply(GIZMO_REACH));
        return distanceSegmentSegmentSq(rayStart, rayEnd, toVec3d(start), toVec3d(end));
    }

    private double distanceSegmentSegmentSq(Vec3d p1, Vec3d q1, Vec3d p2, Vec3d q2) {
        Vec3d d1 = q1.subtract(p1);
        Vec3d d2 = q2.subtract(p2);
        Vec3d r = p1.subtract(p2);
        double a = d1.dotProduct(d1);
        double e = d2.dotProduct(d2);
        double f = d2.dotProduct(r);
        double s;
        double t;

        if (a <= DRAG_PLANE_EPSILON && e <= DRAG_PLANE_EPSILON) {
            return p1.squaredDistanceTo(p2);
        }
        if (a <= DRAG_PLANE_EPSILON) {
            s = 0;
            t = clamp(f / e, 0, 1);
        } else {
            double c = d1.dotProduct(r);
            if (e <= DRAG_PLANE_EPSILON) {
                t = 0;
                s = clamp(-c / a, 0, 1);
            } else {
                double b = d1.dotProduct(d2);
                double denominator = a * e - b * b;
                if (Math.abs(denominator) > DRAG_PLANE_EPSILON) {
                    s = clamp((b * f - c * e) / denominator, 0, 1);
                } else {
                    s = 0;
                }
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a, 0, 1);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a, 0, 1);
                }
            }
        }

        Vec3d closest1 = p1.add(d1.multiply(s));
        Vec3d closest2 = p2.add(d2.multiply(t));
        return closest1.squaredDistanceTo(closest2);
    }

    private Vec3d toVec3d(Vector3f point) {
        return new Vec3d(point.x, point.y, point.z);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void snapPoint(Vector3f point) {
        point.set(snap(point.x), snap(point.y), snap(point.z));
    }

    private static float snap(float value) {
        return Math.round(value * SNAP_SCALE) / SNAP_SCALE;
    }

    private Box areaBox(BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX()) + 1;
        int maxY = Math.max(a.getY(), b.getY()) + 1;
        int maxZ = Math.max(a.getZ(), b.getZ()) + 1;
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private Vector3f[] rectangleQuad(SelectionPoint first, SelectionPoint second) {
        Vector3f normal = directionVector(first.side);
        Vector3f p1 = new Vector3f(first.point);
        Vector3f p3 = projectToPlane(second.point, p1, normal);
        Vector3f delta = new Vector3f(p3).sub(p1);
        Vector3f right = screenRight(first.side);
        Vector3f down = screenDown(first.side);
        float dr = delta.dot(right);
        float dd = delta.dot(down);
        if (Math.abs(dr) < EPSILON || Math.abs(dd) < EPSILON) return null;

        float minR = Math.min(0, dr);
        float maxR = Math.max(0, dr);
        float minD = Math.min(0, dd);
        float maxD = Math.max(0, dd);

        Vector3f topLeft = rectanglePoint(p1, right, down, minR, minD);
        Vector3f topRight = rectanglePoint(p1, right, down, maxR, minD);
        Vector3f bottomRight = rectanglePoint(p1, right, down, maxR, maxD);
        Vector3f bottomLeft = rectanglePoint(p1, right, down, minR, maxD);
        return transformRectangle(new Vector3f[]{topLeft, topRight, bottomRight, bottomLeft});
    }

    private List<Vector3f> completeVerticesForSubmit() {
        if (target() != Target.SCREEN) return null;
        if (draft.screenMode == ScreenMode.RECTANGLE) return completeVertices();
        return points.size() >= ScreenGeometry.MIN_VERTICES ? freeVertices(points, true) : null;
    }

    private List<Vector3f> freeVertices(List<SelectionPoint> sourcePoints, boolean updateStatus) {
        if (sourcePoints.size() < ScreenGeometry.MIN_VERTICES || sourcePoints.size() > ScreenGeometry.MAX_VERTICES) {
            return null;
        }
        ArrayList<Vector3f> vertices = new ArrayList<>(sourcePoints.size());
        for (SelectionPoint point : sourcePoints) {
            vertices.add(new Vector3f(point.point));
        }
        try {
            ScreenGeometry.create(vertices);
            return vertices;
        } catch (IllegalArgumentException e) {
            if (updateStatus) setStatus("error.videoplayer.screen_points_invalid_reason", "Screen points are invalid: %s", true, e.getMessage());
            return null;
        }
    }

    private List<Vector3f> asList(Vector3f[] vertices) {
        if (vertices == null) return null;
        return List.of(vertices);
    }

    private Vector3f projectToPlane(Vector3f point, Vector3f planePoint, Vector3f normal) {
        float distance = new Vector3f(point).sub(planePoint).dot(normal);
        return new Vector3f(point).sub(new Vector3f(normal).mul(distance));
    }

    private Vector3f directionVector(Direction direction) {
        return new Vector3f(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    private Vector3f rectanglePoint(Vector3f origin, Vector3f right, Vector3f down, float r, float d) {
        return new Vector3f(origin)
                .add(new Vector3f(right).mul(r))
                .add(new Vector3f(down).mul(d));
    }

    private Vector3f screenRight(Direction side) {
        if (side.getAxis() == Direction.Axis.Y) {
            return new Vector3f(1, 0, 0);
        }
        Vector3f up = new Vector3f(0, 1, 0);
        return up.cross(directionVector(side)).normalize();
    }

    private Vector3f screenDown(Direction side) {
        if (side == Direction.UP) {
            return new Vector3f(0, 0, 1);
        }
        if (side == Direction.DOWN) {
            return new Vector3f(0, 0, -1);
        }
        return new Vector3f(0, -1, 0);
    }

    private Vector3f[] transformRectangle(Vector3f[] corners) {
        Vector3f[] transformed = corners.clone();
        int rotation = Math.floorMod(draft.rectangleRotation, 4);
        for (int i = 0; i < rotation; i++) {
            transformed = new Vector3f[]{transformed[3], transformed[0], transformed[1], transformed[2]};
        }
        if (draft.rectangleFlipHorizontal) {
            transformed = new Vector3f[]{transformed[1], transformed[0], transformed[3], transformed[2]};
        }
        if (draft.rectangleFlipVertical) {
            transformed = new Vector3f[]{transformed[3], transformed[2], transformed[1], transformed[0]};
        }
        return transformed;
    }

    private boolean screenInsideArea(ClientVideoArea area, List<Vector3f> vertices) {
        for (Vector3f point : vertices) {
            if (point.x < area.min.x - EPSILON || point.y < area.min.y - EPSILON || point.z < area.min.z - EPSILON) {
                return false;
            }
            if (point.x > area.max.x + EPSILON || point.y > area.max.y + EPSILON || point.z > area.max.z + EPSILON) {
                return false;
            }
        }
        return true;
    }

    private boolean spherePresetInsideArea(ClientVideoArea area) {
        return draft.sphereCenter != null
                && draft.sphereCenter.x >= area.min.x - EPSILON && draft.sphereCenter.y >= area.min.y - EPSILON && draft.sphereCenter.z >= area.min.z - EPSILON
                && draft.sphereCenter.x <= area.max.x + EPSILON && draft.sphereCenter.y <= area.max.y + EPSILON && draft.sphereCenter.z <= area.max.z + EPSILON;
    }

    public Vector3f spherePreviewCenter() {
        if (target() != Target.SCREEN || !selectingSpherePreset) return null;
        if (!points.isEmpty()) return new Vector3f(points.getFirst().point);
        return draft.sphereCenter == null ? null : new Vector3f(draft.sphereCenter);
    }

    public float spherePreviewRadius() {
        if (target() != Target.SCREEN || !selectingSpherePreset) return 0;
        if (points.size() >= 2) return new Vector3f(points.get(1).point).sub(points.getFirst().point).length();
        if (selectingSpherePreset && points.size() == 1) {
            SelectionPoint current = currentTargetPoint();
            if (current != null) return new Vector3f(current.point).sub(points.getFirst().point).length();
        }
        return draft.sphereRadius;
    }

    private boolean sphereReady() {
        return draft.spherePreset && draft.sphereCenter != null && Float.isFinite(draft.sphereRadius) && draft.sphereRadius > EPSILON;
    }

    private void updateSphereDraftFromPoints() {
        if (points.isEmpty()) {
            draft.spherePreset = false;
            return;
        }
        draft.spherePreset = true;
        draft.sphereCenter = new Vector3f(points.getFirst().point);
        if (points.size() >= 2) {
            draft.sphereRadius = Math.max(EPSILON, new Vector3f(points.get(1).point).sub(points.getFirst().point).length());
        }
    }

    void applyDraftDisplay(VideoScreen screen) {
        screen.surface = draft.surface == null ? ScreenSurface.FLAT : draft.surface;
        screen.stereo3d = draft.stereo3d;
        screen.spherePreset = draft.spherePreset;
        screen.sphereCenter = draft.sphereCenter == null ? new Vector3f() : new Vector3f(draft.sphereCenter);
        screen.sphereRadius = draft.sphereRadius;
        screen.sphereLat = VideoScreen.clampSphereSegments(draft.sphereLat);
        screen.sphereLon = VideoScreen.clampSphereSegments(draft.sphereLon);
        screen.sphereRotX = draft.sphereRotX;
        screen.sphereRotY = draft.sphereRotY;
        screen.sphereRotZ = draft.sphereRotZ;
        screen.sphereSkybox = draft.sphereSkybox;
        screen.ensureValidState();
    }

    private String uniqueAreaName() {
        return uniqueName("area", VideoPlayerClient.areas::containsKey);
    }

    private String uniqueScreenName(String areaName) {
        ClientVideoArea area = VideoPlayerClient.areas.get(areaName);
        if (area == null) return "screen1";
        return uniqueName("screen", name -> area.getScreen(name) != null);
    }

    private String uniqueName(String prefix, NameExists exists) {
        for (int i = 1; i < 1000; i++) {
            String name = prefix + i;
            if (!exists.test(name)) return name;
        }
        return prefix + System.currentTimeMillis();
    }

    private void setStatus(Text status, boolean error) {
        this.status = status;
        this.statusError = error;
    }

    private void setStatus(String key, String fallback, boolean error, Object... args) {
        setStatus(VpTexts.tr(key, fallback, args), error);
    }

    private Target target() {
        if (draft.operation == null) {
            return draft.target == null ? Target.AREA : draft.target;
        }
        return draft.operation.target();
    }

    @FunctionalInterface
    private interface NameExists {
        boolean test(String name);
    }

    public enum Target {
        AREA,
        SCREEN;

        public Target next() {
            return this == AREA ? SCREEN : AREA;
        }

        public String label() {
            return labelText().getString();
        }

        public Text labelText() {
            return this == AREA
                    ? VpTexts.tr("label.videoplayer.target.area", "Area")
                    : VpTexts.tr("label.videoplayer.target.screen", "Screen");
        }
    }

    public enum Operation {
        CREATE_AREA,
        CREATE_SCREEN,
        EDIT_SCREEN_GEOMETRY;

        public Target target() {
            return this == CREATE_AREA ? Target.AREA : Target.SCREEN;
        }

        public String label() {
            return labelText().getString();
        }

        public Text labelText() {
            return switch (this) {
                case CREATE_AREA -> VpTexts.tr("label.videoplayer.operation.create_area", "Create Area");
                case CREATE_SCREEN -> VpTexts.tr("label.videoplayer.operation.create_screen", "Create Screen");
                case EDIT_SCREEN_GEOMETRY -> VpTexts.tr("label.videoplayer.operation.edit_screen", "Edit Screen");
            };
        }
    }

    public enum ScreenMode {
        RECTANGLE,
        FREE;

        public ScreenMode next() {
            return this == RECTANGLE ? FREE : RECTANGLE;
        }

        public String label() {
            return labelText().getString();
        }

        public Text labelText() {
            return this == RECTANGLE
                    ? VpTexts.tr("label.videoplayer.screen_mode.rectangle", "Two-point Rectangle")
                    : VpTexts.tr("label.videoplayer.screen_mode.free", "Freeform Polygon");
        }
    }

    public enum GizmoAxis {
        X,
        Y,
        Z;

        public Vector3f vector() {
            return switch (this) {
                case X -> new Vector3f(1, 0, 0);
                case Y -> new Vector3f(0, 1, 0);
                case Z -> new Vector3f(0, 0, 1);
            };
        }

        public String label() {
            return switch (this) {
                case X -> "X";
                case Y -> "Y";
                case Z -> "Z";
            };
        }
    }

    public static final class Draft {
        public Operation operation = Operation.CREATE_AREA;
        public Target target = Target.AREA;
        public ScreenMode screenMode = ScreenMode.RECTANGLE;
        public ScreenSurface surface = ScreenSurface.FLAT;
        public boolean stereo3d;
        public boolean spherePreset;
        public Vector3f sphereCenter;
        public float sphereRadius = 10;
        public int sphereLat = 32;
        public int sphereLon = 32;
        public float sphereRotX;
        public float sphereRotY;
        public float sphereRotZ;
        public boolean sphereSkybox;
        public int rectangleRotation;
        public boolean rectangleFlipHorizontal;
        public boolean rectangleFlipVertical;
        public String areaName = "";
        public String name = "";
        public String source = "";

        public Draft copy() {
            Draft copy = new Draft();
            copy.copyFrom(this);
            return copy;
        }

        public void copyFrom(Draft other) {
            operation = other.operation == null ? Operation.CREATE_AREA : other.operation;
            target = other.target;
            screenMode = other.screenMode;
            surface = other.surface == null ? ScreenSurface.FLAT : other.surface;
            stereo3d = other.stereo3d;
            spherePreset = other.spherePreset;
            sphereCenter = other.sphereCenter == null ? null : new Vector3f(other.sphereCenter);
            sphereRadius = other.sphereRadius;
            sphereLat = other.sphereLat;
            sphereLon = other.sphereLon;
            sphereRotX = other.sphereRotX;
            sphereRotY = other.sphereRotY;
            sphereRotZ = other.sphereRotZ;
            sphereSkybox = other.sphereSkybox;
            rectangleRotation = Math.floorMod(other.rectangleRotation, 4);
            rectangleFlipHorizontal = other.rectangleFlipHorizontal;
            rectangleFlipVertical = other.rectangleFlipVertical;
            areaName = normalize(other.areaName);
            name = normalize(other.name);
            source = normalize(other.source);
            target = operation.target();
        }

        private String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public static final class SelectionPoint {
        public final Vector3f point;
        public final BlockPos blockPos;
        public final Direction side;

        private SelectionPoint(Vector3f point, BlockPos blockPos, Direction side) {
            this.point = point;
            this.blockPos = blockPos;
            this.side = side;
        }

        public static SelectionPoint from(BlockHitResult hit, boolean snap) {
            Vec3d pos = hit.getPos();
            Direction side = hit.getSide();
            Vector3f point = new Vector3f(
                    (float) pos.x + side.getOffsetX() * POINT_NORMAL_OFFSET,
                    (float) pos.y + side.getOffsetY() * POINT_NORMAL_OFFSET,
                    (float) pos.z + side.getOffsetZ() * POINT_NORMAL_OFFSET
            );
            if (snap) snapPoint(point);
            return new SelectionPoint(
                    point,
                    hit.getBlockPos().toImmutable(),
                    side
            );
        }

        public String format() {
            return String.format(Locale.ROOT, "%.4f %.4f %.4f", point.x, point.y, point.z);
        }
    }

    private record Ray(Vec3d origin, Vec3d direction) {
    }
}
