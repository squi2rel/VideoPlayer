package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.InflaterInputStream;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

final class BiliLiveDanmakuClient {
    private static final String DANMU_INFO_URL = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo";
    private static final int OP_HEARTBEAT = 2;
    private static final int OP_MESSAGE = 5;
    private static final int OP_AUTH = 7;
    private static final int VERSION_NORMAL = 0;
    private static final int VERSION_ZLIB = 2;
    private static final int VERSION_BROTLI = 3;
    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "VideoPlayer-bili-live-danmaku");
        thread.setDaemon(true);
        return thread;
    });

    private final BiliBiliSourceInfo source;
    private final Consumer<DanmakuEntry> receiver;
    private volatile boolean stopped;
    private volatile WebSocket socket;
    private ScheduledFuture<?> heartbeatTask;
    private int reconnectAttempts;

    BiliLiveDanmakuClient(BiliBiliSourceInfo source, Consumer<DanmakuEntry> receiver) {
        this.source = source;
        this.receiver = receiver;
    }

    void start() {
        stopped = false;
        connectSoon(0);
    }

    void stop() {
        stopped = true;
        cancelHeartbeat();
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
            } catch (Exception ignored) {
                current.abort();
            }
        }
    }

    private void connectSoon(long delaySeconds) {
        if (stopped) return;
        EXECUTOR.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    private void connect() {
        if (stopped) return;
        try {
            BiliLiveDanmuInfo info = fetchDanmuInfo();
            BiliLiveListener listener = new BiliLiveListener(info.token());
            BiliHttp.CLIENT.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("User-Agent", BiliBiliProvider.UA)
                    .header("Origin", "https://live.bilibili.com")
                    .buildAsync(info.uri(), listener)
                    .thenAccept(webSocket -> {
                        socket = webSocket;
                        reconnectAttempts = 0;
                    })
                    .exceptionally(e -> {
                        LOGGER.warn("Failed to connect Bilibili live danmaku", e);
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.warn("Failed to prepare Bilibili live danmaku connection", e);
            scheduleReconnect();
        }
    }

    private BiliLiveDanmuInfo fetchDanmuInfo() throws Exception {
        String referer = "https://live.bilibili.com/" + source.roomId();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", Long.toString(source.roomId()));
        params.put("type", "0");
        URI uri;
        try {
            uri = BiliWbiSigner.signedUri(DANMU_INFO_URL, params, referer);
        } catch (Exception e) {
            uri = URI.create(DANMU_INFO_URL + "?id=" + source.roomId() + "&type=0");
        }
        JsonObject data = JsonParser.parseString(BiliHttp.getString(uri, referer)).getAsJsonObject().getAsJsonObject("data");
        String token = data.get("token").getAsString();
        JsonArray hosts = data.getAsJsonArray("host_list");
        if (hosts == null || hosts.isEmpty()) throw new IllegalStateException("No live danmaku hosts");
        JsonObject host = hosts.get(0).getAsJsonObject();
        String hostName = host.get("host").getAsString();
        int port = host.has("wss_port") && !host.get("wss_port").isJsonNull() ? host.get("wss_port").getAsInt() : 443;
        return new BiliLiveDanmuInfo(URI.create("wss://" + hostName + ":" + port + "/sub"), token);
    }

    private void scheduleReconnect() {
        if (stopped) return;
        cancelHeartbeat();
        long delay = Math.min(60, 1L << Math.min(6, reconnectAttempts++));
        connectSoon(delay);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private ByteBuffer packet(int operation, byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        ByteBuffer buffer = ByteBuffer.allocate(16 + payload.length);
        buffer.putInt(16 + payload.length);
        buffer.putShort((short) 16);
        buffer.putShort((short) 1);
        buffer.putInt(operation);
        buffer.putInt(1);
        buffer.put(payload);
        buffer.flip();
        return buffer;
    }

    private final class BiliLiveListener implements WebSocket.Listener {
        private final String token;
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        private BiliLiveListener(String token) {
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
            webSocket.sendBinary(packet(OP_AUTH, authPayload(token)), true);
            heartbeatTask = EXECUTOR.scheduleAtFixedRate(() -> {
                WebSocket current = socket;
                if (!stopped && current != null) current.sendBinary(packet(OP_HEARTBEAT, "{}".getBytes(StandardCharsets.UTF_8)), true);
            }, 30, 30, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partial.writeBytes(chunk);
            if (last) {
                parsePackets(partial.toByteArray());
                partial.reset();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) socket = null;
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) socket = null;
            if (!stopped) LOGGER.warn("Bilibili live danmaku socket error", error);
            scheduleReconnect();
        }
    }

    private byte[] authPayload(String token) {
        BiliCookie.ensureBuvids();
        JsonObject object = new JsonObject();
        object.addProperty("uid", authUid());
        object.addProperty("roomid", source.roomId());
        object.addProperty("protover", 3);
        object.addProperty("platform", "web");
        object.addProperty("type", 2);
        object.addProperty("key", token);
        String buvid = BiliCookie.value("buvid3");
        if (!buvid.isBlank()) object.addProperty("buvid", buvid);
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    private long authUid() {
        long storedMid = BiliAuthStore.snapshot().bilibiliMid;
        if (storedMid > 0) return storedMid;
        return parsePositiveLong(BiliCookie.value("DedeUserID"));
    }

    private long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void parsePackets(byte[] data) {
        int offset = 0;
        while (offset + 16 <= data.length) {
            int packetLength = int32(data, offset);
            int headerLength = uint16(data, offset + 4);
            int version = uint16(data, offset + 6);
            int operation = int32(data, offset + 8);
            if (packetLength <= 0 || offset + packetLength > data.length || headerLength < 16 || headerLength > packetLength) break;
            byte[] body = new byte[packetLength - headerLength];
            System.arraycopy(data, offset + headerLength, body, 0, body.length);
            handlePacket(version, operation, body);
            offset += packetLength;
        }
    }

    private void handlePacket(int version, int operation, byte[] body) {
        try {
            if (version == VERSION_ZLIB) {
                parsePackets(inflate(body));
                return;
            }
            if (version == VERSION_BROTLI) {
                parsePackets(brotli(body));
                return;
            }
            if (operation != OP_MESSAGE || version != VERSION_NORMAL && version != 1) return;
            handleCommand(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.debug("Failed to parse Bilibili live danmaku packet", e);
        }
    }

    private void handleCommand(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (!object.has("cmd")) return;
        String cmd = object.get("cmd").getAsString();
        if (!cmd.startsWith("DANMU_MSG")) return;
        JsonArray info = object.getAsJsonArray("info");
        if (info == null || info.size() < 2) return;
        String content = info.get(1).getAsString();
        JsonArray attrs = info.get(0).getAsJsonArray();
        int mode = attr(attrs, 1, 1);
        int fontSize = attr(attrs, 2, 25);
        int color = attr(attrs, 3, 0xFFFFFF);
        DanmakuEntry entry = DanmakuEntry.live(mode, fontSize, color, content);
        if (entry.renderable()) receiver.accept(entry);
    }

    private int attr(JsonArray attrs, int index, int fallback) {
        if (attrs == null || attrs.size() <= index) return fallback;
        JsonElement element = attrs.get(index);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    private byte[] inflate(byte[] data) throws Exception {
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(data))) {
            return input.readAllBytes();
        }
    }

    private byte[] brotli(byte[] data) throws Exception {
        try (BrotliInputStream input = new BrotliInputStream(new ByteArrayInputStream(data))) {
            return input.readAllBytes();
        }
    }

    private int int32(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24
                | (data[offset + 1] & 0xFF) << 16
                | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }

    private int uint16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    private record BiliLiveDanmuInfo(URI uri, String token) {
    }
}
