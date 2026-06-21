package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.danmaku.BiliQrLoginClient;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public class BiliLoginScreen extends Screen {
    private static final VpUiTheme THEME = VpUiTheme.classic();
    private static final AtomicInteger TEXTURE_COUNTER = new AtomicInteger();
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 328;
    private static final int QR_PIXELS = 256;
    private static final long POLL_INTERVAL_MS = 1000L;

    private final Screen parent;

    private VpButtonWidget refreshButton;
    private VpButtonWidget closeButton;
    private BiliQrLoginClient.QrCode qrCode;
    private BiliQrLoginClient.State state = BiliQrLoginClient.State.WAITING;
    private VpTranslation status = VpTranslation.of("message.videoplayer.bili_login_loading", "Loading QR code");
    private CompletableFuture<?> activeRequest;
    private long nextPollAt;
    private int requestToken;
    private boolean closing;

    private Identifier qrIdentifier;
    private NativeImageBackedTexture qrTexture;

    public BiliLoginScreen(Screen parent) {
        super(VpTexts.tr("screen.videoplayer.bili_login", "Bilibili Login"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelW = panelWidth();
        int left = (width - panelW) / 2;
        int top = panelTop();
        int buttonY = top + panelHeight() - 34;
        int buttonW = Math.max(88, Math.min(120, (panelW - 56) / 2));
        int gap = 8;
        int rightButtonX = left + panelW - 24 - buttonW;

        refreshButton = new VpButtonWidget(rightButtonX - gap - buttonW, buttonY, buttonW, 22,
                VpTexts.tr("button.videoplayer.refresh", "Refresh"), ignored -> startGenerate(), THEME);
        closeButton = new VpButtonWidget(rightButtonX, buttonY, buttonW, 22,
                VpTexts.tr("button.videoplayer.close", "Close"), ignored -> close(), THEME);
        addDrawableChild(refreshButton);
        addDrawableChild(closeButton);
        syncButtons();

        if (qrCode == null && activeRequest == null) {
            startGenerate();
        }
    }

    @Override
    public void tick() {
        if (closing || activeRequest != null || qrCode == null) {
            syncButtons();
            return;
        }
        if ((state == BiliQrLoginClient.State.WAITING || state == BiliQrLoginClient.State.SCANNED)
                && System.currentTimeMillis() >= nextPollAt) {
            startPoll();
        }
        syncButtons();
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void removed() {
        closing = true;
        requestToken++;
        CompletableFuture<?> task = activeRequest;
        activeRequest = null;
        if (task != null) task.cancel(true);
        destroyQrTexture();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xB0000000);

        int panelW = panelWidth();
        int panelH = panelHeight();
        int left = (width - panelW) / 2;
        int top = panelTop();
        int right = left + panelW;
        int bottom = top + panelH;

        context.fill(left, top, right, bottom, THEME.panelBackgroundColor());
        context.drawStrokedRectangle(left, top, panelW, panelH, THEME.panelBorderColor());
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top + 14, THEME.primaryTextColor());

        int qrSize = qrDisplaySize(panelW, panelH);
        int qrX = left + (panelW - qrSize) / 2;
        int qrY = top + 42;
        VpUiRenderer.drawBox(context, qrX - 4, qrY - 4, qrSize + 8, qrSize + 8, 0xFFFFFFFF, THEME.panelBorderColor());
        if (qrIdentifier != null) {
            context.drawTexturedQuad(qrIdentifier, qrX, qrY, qrX + qrSize, qrY + qrSize, 0, 1, 0, 1);
        }

        Text statusText = VpTexts.text(status);
        int statusY = Math.min(bottom - 62, qrY + qrSize + 16);
        context.drawCenteredTextWithShadow(textRenderer, statusText, width / 2, statusY, statusColor());

        super.render(context, mouseX, mouseY, delta);
    }

    private void startGenerate() {
        if (closing) return;
        int token = ++requestToken;
        CompletableFuture<?> old = activeRequest;
        if (old != null) old.cancel(true);
        qrCode = null;
        state = BiliQrLoginClient.State.WAITING;
        status = VpTranslation.of("message.videoplayer.bili_login_loading", "Loading QR code");
        destroyQrTexture();
        syncButtons();

        CompletableFuture<BiliQrLoginClient.QrCode> task = BiliQrLoginClient.generateAsync();
        activeRequest = task;
        task.whenComplete((qr, error) -> runOnClient(() -> {
            if (!isCurrent(token)) return;
            activeRequest = null;
            if (error != null) {
                if (!isCancellation(error)) setError(error);
                syncButtons();
                return;
            }
            try {
                qrCode = qr;
                createQrTexture(qr.url());
                state = BiliQrLoginClient.State.WAITING;
                status = VpTranslation.of("message.videoplayer.bili_login_waiting", "Waiting for scan");
                nextPollAt = 0L;
            } catch (Exception e) {
                setError(e);
            }
            syncButtons();
        }));
    }

    private void startPoll() {
        if (closing || qrCode == null) return;
        int token = ++requestToken;
        nextPollAt = System.currentTimeMillis() + POLL_INTERVAL_MS;
        CompletableFuture<BiliQrLoginClient.PollResult> task = BiliQrLoginClient.pollAsync(qrCode.qrcodeKey());
        activeRequest = task;
        syncButtons();
        task.whenComplete((result, error) -> runOnClient(() -> {
            if (!isCurrent(token)) return;
            activeRequest = null;
            if (error != null) {
                if (!isCancellation(error)) setError(error);
                syncButtons();
                return;
            }
            state = result.state();
            status = result.message();
            if (state == BiliQrLoginClient.State.WAITING || state == BiliQrLoginClient.State.SCANNED) {
                nextPollAt = System.currentTimeMillis() + POLL_INTERVAL_MS;
            }
            syncButtons();
        }));
    }

    private void createQrTexture(String url) throws WriterException {
        NativeImage image = createQrImage(url);
        Identifier identifier = Identifier.of("videoplayer", "bili_login/qr/" + TEXTURE_COUNTER.incrementAndGet());
        NativeImageBackedTexture texture = null;
        boolean registered = false;
        try {
            texture = new NativeImageBackedTexture(() -> "VideoPlayer Bilibili QR", image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(identifier, texture);
            registered = true;
            qrIdentifier = identifier;
            qrTexture = texture;
        } finally {
            if (!registered) {
                if (texture != null) texture.close();
                else image.close();
            }
        }
    }

    private NativeImage createQrImage(String url) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, QR_PIXELS, QR_PIXELS, hints);
        NativeImage image = new NativeImage(QR_PIXELS, QR_PIXELS, false);
        for (int y = 0; y < QR_PIXELS; y++) {
            for (int x = 0; x < QR_PIXELS; x++) {
                image.setColorArgb(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return image;
    }

    private void destroyQrTexture() {
        Identifier identifier = qrIdentifier;
        NativeImageBackedTexture texture = qrTexture;
        qrIdentifier = null;
        qrTexture = null;
        if (identifier != null) {
            TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
            try {
                textureManager.destroyTexture(identifier);
                return;
            } catch (RuntimeException ignored) {
                // Fall through to close the texture directly.
            }
        }
        if (texture != null) texture.close();
    }

    private void setError(Throwable error) {
        state = BiliQrLoginClient.State.ERROR;
        status = VpTranslation.of("message.videoplayer.bili_login_failed", "Bilibili login failed: %s", publicMessage(error));
    }

    private String publicMessage(Throwable error) {
        Throwable root = unwrap(error);
        String message = root.getMessage();
        if (message == null || message.isBlank()) return root.getClass().getSimpleName();
        return message;
    }

    private Throwable unwrap(Throwable error) {
        Throwable result = error == null ? new RuntimeException("unknown") : error;
        while (result instanceof CompletionException && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private boolean isCancellation(Throwable error) {
        Throwable root = unwrap(error);
        return root instanceof CancellationException;
    }

    private boolean isCurrent(int token) {
        return !closing && token == requestToken;
    }

    private void runOnClient(Runnable task) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(task);
    }

    private void syncButtons() {
        if (refreshButton != null) refreshButton.active = activeRequest == null && !closing;
        if (closeButton != null) closeButton.active = true;
    }

    private int statusColor() {
        return switch (state) {
            case SCANNED -> THEME.accentColor();
            case EXPIRED, ERROR -> THEME.errorColor();
            case SUCCESS -> THEME.executionColor();
            default -> THEME.secondaryTextColor();
        };
    }

    private int panelWidth() {
        return Math.max(160, Math.min(PANEL_WIDTH, Math.max(0, width - 32)));
    }

    private int panelHeight() {
        return Math.max(220, Math.min(PANEL_HEIGHT, Math.max(0, height - 32)));
    }

    private int panelTop() {
        return Math.max(16, (height - panelHeight()) / 2);
    }

    private int qrDisplaySize(int panelW, int panelH) {
        return Math.max(96, Math.min(220, Math.max(0, Math.min(panelW - 76, panelH - 128))));
    }
}
