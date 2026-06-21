package com.github.squi2rel.vp.creation;

import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.fabric.client.GraphEditorBounds;
import com.github.squi2rel.mcng.fabric.client.GraphEditorComponent;
import com.github.squi2rel.mcng.fabric.client.GraphEditorHost;
import com.github.squi2rel.mcng.fabric.client.GraphEditorI18n;
import com.github.squi2rel.mcng.fabric.client.GraphEditorSession;
import com.github.squi2rel.mcng.fabric.client.GraphEditorTheme;
import com.github.squi2rel.mcng.fabric.client.GraphEditorUiConfig;
import com.github.squi2rel.mcng.fabric.client.NodeComponentRegistry;
import com.github.squi2rel.vp.filtergraph.MpvFilterGraphCompiler;
import com.github.squi2rel.vp.filtergraph.MpvFilterGraphManager;
import com.github.squi2rel.vp.filtergraph.MpvFilterGraphNodes;
import com.github.squi2rel.vp.filtergraph.MpvFilterGraphTypes;
import com.github.squi2rel.vp.filtergraph.MpvLavfiFilterCatalog;
import com.github.squi2rel.vp.i18n.VpTexts;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class MpvFilterGraphScreen extends Screen implements GraphEditorHost {
    private static final VpUiTheme THEME = VpUiTheme.classic();
    private static final int TOP_BAR_HEIGHT = 28;
    private static final long AUTO_APPLY_DELAY_MS = 500L;
    private static final GraphEditorI18n MINECRAFT_I18N = (key, fallback, args) ->
            I18n.hasTranslation(key) ? I18n.translate(key, args) : GraphEditorI18n.formatFallback(fallback, key, args);

    private final Screen parent;
    private final GraphJsonCodec codec = new GraphJsonCodec();
    private final GraphEditorSession session;
    private final GraphEditorComponent editor;

    private VpButtonWidget applyButton;
    private VpButtonWidget autoApplyButton;
    private String status = "";
    private long autoApplyAt = -1L;

    public MpvFilterGraphScreen(Screen parent) {
        super(VpTexts.tr("screen.videoplayer.mpv_filter_graph", "MPV Filter Graph"));
        this.parent = parent;
        GraphDocument document = MpvFilterGraphManager.document();
        this.session = new GraphEditorSession(
                MpvFilterGraphNodes.createRegistry(),
                MpvFilterGraphTypes.createRegistry(),
                codec,
                document,
                this
        );
        this.editor = new GraphEditorComponent(
                session,
                MpvFilterGraphNodes.createPalette(),
                new NodeComponentRegistry(),
                GraphEditorUiConfig.defaultConfig().withTheme(GraphEditorTheme.classic())
        );
    }

    @Override
    protected void init() {
        applyButton = new VpButtonWidget(width - 176, 5, 72, 18, Text.literal("Apply"), button -> applyNow(), THEME);
        autoApplyButton = new VpButtonWidget(width - 96, 5, 88, 18, autoApplyText(), button -> toggleAutoApply(), THEME)
                .selected(MpvFilterGraphManager.autoApply());
        addDrawableChild(applyButton);
        addDrawableChild(autoApplyButton);
        editor.init(textRenderer, editorBounds());
        syncStatusFromCompile();
    }

    @Override
    public void tick() {
        super.tick();
        if (autoApplyAt > 0 && System.currentTimeMillis() >= autoApplyAt) {
            autoApplyAt = -1L;
            applyNow();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        editor.setBounds(editorBounds());
        editor.render(context, textRenderer, mouseX, mouseY, delta);
        context.fill(0, 0, width, TOP_BAR_HEIGHT, THEME.panelBackgroundColor());
        context.drawTextWithShadow(textRenderer, title, 8, 10, THEME.primaryTextColor());
        int statusRight = Math.max(80, width - 184);
        String visible = textRenderer.trimToWidth(status == null ? "" : status, statusRight - 90);
        context.drawTextWithShadow(textRenderer, Text.literal(visible), 90, 10, statusColor());
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        return editor.mouseClicked(click.x(), click.y(), click.button());
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return editor.mouseDragged(click.x(), click.y(), click.button(), deltaX, deltaY) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return editor.mouseReleased(click.x(), click.y(), click.button()) || super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return editor.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount) || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (editor.keyPressed(input.key(), input.scancode(), input.modifiers())) return true;
        if (input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE) {
            session.removeSelectedNodes();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar()) {
            String value = input.asString();
            if (value.length() == 1 && editor.charTyped(value.charAt(0), input.modifiers())) return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        editor.close();
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void onDocumentChanged(GraphDocument document) {
        MpvFilterGraphManager.setDocument(document);
        syncStatusFromCompile();
        if (MpvFilterGraphManager.autoApply()) {
            autoApplyAt = System.currentTimeMillis() + AUTO_APPLY_DELAY_MS;
        }
    }

    @Override
    public void copyToClipboard(String value) {
        if (client != null) client.keyboard.setClipboard(value);
    }

    @Override
    public String readClipboard() {
        return client == null ? "" : client.keyboard.getClipboard();
    }

    @Override
    public void showMessage(String message) {
        status = message == null ? "" : message;
    }

    @Override
    public GraphEditorI18n i18n() {
        return MINECRAFT_I18N;
    }

    private void toggleAutoApply() {
        MpvFilterGraphManager.setAutoApply(!MpvFilterGraphManager.autoApply());
        autoApplyAt = -1L;
        if (autoApplyButton != null) {
            autoApplyButton.setMessage(autoApplyText());
            autoApplyButton.selected(MpvFilterGraphManager.autoApply());
        }
        status = MpvFilterGraphManager.autoApply() ? "Auto apply enabled" : "Auto apply disabled";
    }

    private void applyNow() {
        MpvFilterGraphManager.ApplyResult result = MpvFilterGraphManager.applyToActivePlayers();
        if (result.success()) {
            status = result.message() + " (" + result.playerCount() + ")";
        } else {
            status = result.message();
        }
    }

    private void syncStatusFromCompile() {
        MpvLavfiFilterCatalog.Catalog catalog = MpvLavfiFilterCatalog.get();
        if (!catalog.usable()) {
            status = catalog.available()
                    ? "MPV filter API returned no lavfi filters."
                    : "MPV filter API unavailable: " + catalog.error();
            return;
        }
        MpvFilterGraphCompiler.CompileResult compiled = MpvFilterGraphManager.compileCurrent();
        if (compiled.success()) {
            status = compiled.graph().isBlank() ? "Saved. No active graph." : "Saved. Graph ready.";
        } else {
            status = "Compile error: " + compiled.error();
        }
    }

    private int statusColor() {
        return status != null && (status.startsWith("Compile error") || status.startsWith("MPV filter API"))
                ? THEME.errorColor()
                : THEME.secondaryTextColor();
    }

    private Text autoApplyText() {
        return Text.literal(MpvFilterGraphManager.autoApply() ? "Auto: On" : "Auto: Off");
    }

    private GraphEditorBounds editorBounds() {
        return new GraphEditorBounds(0, TOP_BAR_HEIGHT, width, Math.max(1, height - TOP_BAR_HEIGHT));
    }
}
