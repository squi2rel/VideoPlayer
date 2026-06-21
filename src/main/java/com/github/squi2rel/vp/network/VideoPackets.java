package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.ServerConfig;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.MetaType;
import com.github.squi2rel.vp.video.MetaValue;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.squi2rel.vp.network.ByteBufUtils.writeString;

public final class VideoPackets {
    private VideoPackets() {
    }

    public static VideoPacketType readType(ByteBuf buf) {
        return VideoPacketType.fromId(buf.readUnsignedByte());
    }

    public static String readName(ByteBuf buf) {
        return ByteBufUtils.readString(buf, VideoScreen.MAX_NAME_LENGTH);
    }

    public static ByteBuf create(VideoPacketType type) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        buf.writeByte((byte) type.id);
        return buf;
    }

    public static byte[] toByteArray(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    public static void readUv(ByteBuf buf, VideoScreen screen) {
        screen.u1 = buf.readFloat();
        screen.v1 = buf.readFloat();
        screen.u2 = buf.readFloat();
        screen.v2 = buf.readFloat();
    }

    public static void writeUv(ByteBuf buf, VideoScreen screen) {
        buf.writeFloat(screen.u1);
        buf.writeFloat(screen.v1);
        buf.writeFloat(screen.u2);
        buf.writeFloat(screen.v2);
    }

    public static void readScale(ByteBuf buf, VideoScreen screen) {
        screen.fill = buf.readBoolean();
        screen.scaleX = buf.readFloat();
        screen.scaleY = buf.readFloat();
    }

    public static void writeScale(ByteBuf buf, VideoScreen screen) {
        buf.writeBoolean(screen.fill);
        buf.writeFloat(screen.scaleX);
        buf.writeFloat(screen.scaleY);
    }

    public static void readIdlePlayConfig(ByteBuf buf, VideoScreen screen) {
        boolean random = buf.readBoolean();
        int count = buf.readUnsignedByte();
        ArrayList<String> urls = new ArrayList<>(Math.min(count, VideoScreen.MAX_IDLE_PLAY_ITEMS));
        for (int i = 0; i < count; i++) {
            String url = ByteBufUtils.readString(buf, VideoScreen.MAX_IDLE_PLAY_URL_LENGTH);
            if (urls.size() < VideoScreen.MAX_IDLE_PLAY_ITEMS) urls.add(url);
        }
        screen.setIdlePlayConfig(urls, random);
    }

    public static void writeIdlePlayConfig(ByteBuf buf, VideoScreen screen) {
        screen.ensureValidState();
        buf.writeBoolean(screen.idlePlayRandom);
        int count = Math.min(screen.idlePlayUrls.size(), VideoScreen.MAX_IDLE_PLAY_ITEMS);
        buf.writeByte(count);
        for (int i = 0; i < count; i++) {
            ByteBufUtils.writeString(buf, screen.idlePlayUrls.get(i));
        }
    }

    public static void readMeta(ByteBuf buf, VideoScreen screen) {
        short size = buf.readUnsignedByte();
        ScreenMetadata metadata = new ScreenMetadata();
        for (int i = 0; i < size; i++) {
            metadata.set(ByteBufUtils.readString(buf, 64), readMetaValue(buf));
        }
        screen.metadata = metadata;
    }

    public static void writeMeta(ByteBuf buf, VideoScreen screen) {
        ScreenMetadata metadata = screen.metadata == null ? new ScreenMetadata() : screen.metadata;
        metadata.ensureValid();
        Map<String, MetaValue> values = metadata.entries();
        buf.writeByte(values.size());
        for (Map.Entry<String, MetaValue> entry : values.entrySet()) {
            ByteBufUtils.writeString(buf, entry.getKey());
            writeMetaValue(buf, entry.getValue());
        }
    }

    public static MetaValue readMetaValue(ByteBuf buf) {
        MetaType type = MetaType.fromId(buf.readUnsignedByte());
        if (type == null) throw new IllegalStateException("Unknown meta type");
        return switch (type) {
            case BOOL -> MetaValue.ofBool(buf.readBoolean());
            case INT -> MetaValue.ofInt(buf.readInt());
            case LONG -> MetaValue.ofLong(buf.readLong());
            case FLOAT -> MetaValue.ofFloat(buf.readFloat());
            case DOUBLE -> MetaValue.ofDouble(buf.readDouble());
            case STRING -> MetaValue.ofString(ByteBufUtils.readString(buf, MetaValue.MAX_STRING_BYTES));
            case BOOL_ARRAY -> {
                int length = readMetaArrayLength(buf);
                boolean[] values = new boolean[length];
                for (int i = 0; i < length; i++) values[i] = buf.readBoolean();
                yield MetaValue.ofBoolArray(values);
            }
            case INT_ARRAY -> {
                int length = readMetaArrayLength(buf);
                int[] values = new int[length];
                for (int i = 0; i < length; i++) values[i] = buf.readInt();
                yield MetaValue.ofIntArray(values);
            }
            case FLOAT_ARRAY -> {
                int length = readMetaArrayLength(buf);
                float[] values = new float[length];
                for (int i = 0; i < length; i++) values[i] = buf.readFloat();
                yield MetaValue.ofFloatArray(values);
            }
            case STRING_ARRAY -> {
                int length = readMetaArrayLength(buf);
                String[] values = new String[length];
                int totalBytes = 0;
                for (int i = 0; i < length; i++) {
                    values[i] = ByteBufUtils.readString(buf, MetaValue.MAX_STRING_BYTES);
                    totalBytes += values[i].getBytes(StandardCharsets.UTF_8).length;
                    if (totalBytes > MetaValue.MAX_STRING_BYTES) {
                        throw new IllegalStateException("Meta string[] total length exceeds " + MetaValue.MAX_STRING_BYTES);
                    }
                }
                yield MetaValue.ofStringArray(values);
            }
        };
    }

    private static int readMetaArrayLength(ByteBuf buf) {
        int length = buf.readUnsignedShort();
        if (length > MetaValue.MAX_ARRAY_LENGTH) {
            throw new IllegalStateException("Meta array length exceeds " + MetaValue.MAX_ARRAY_LENGTH);
        }
        return length;
    }

    public static void writeMetaValue(ByteBuf buf, MetaValue value) {
        value.validateValue();
        buf.writeByte(value.type.ordinal());
        switch (value.type) {
            case BOOL -> buf.writeBoolean(value.boolValue);
            case INT -> buf.writeInt(value.intValue);
            case LONG -> buf.writeLong(value.longValue);
            case FLOAT -> buf.writeFloat(value.floatValue);
            case DOUBLE -> buf.writeDouble(value.doubleValue);
            case STRING -> ByteBufUtils.writeString(buf, value.stringValue == null ? "" : value.stringValue);
            case BOOL_ARRAY -> {
                boolean[] values = value.boolArray == null ? new boolean[0] : value.boolArray;
                buf.writeShort(values.length);
                for (boolean element : values) buf.writeBoolean(element);
            }
            case INT_ARRAY -> {
                int[] values = value.intArray == null ? new int[0] : value.intArray;
                buf.writeShort(values.length);
                for (int element : values) buf.writeInt(element);
            }
            case FLOAT_ARRAY -> {
                float[] values = value.floatArray == null ? new float[0] : value.floatArray;
                buf.writeShort(values.length);
                for (float element : values) buf.writeFloat(element);
            }
            case STRING_ARRAY -> {
                String[] values = value.stringArray == null ? new String[0] : value.stringArray;
                buf.writeShort(values.length);
                for (String element : values) ByteBufUtils.writeString(buf, element == null ? "" : element);
            }
        }
    }

    public static byte[] config(String version, ServerConfig config) {
        ByteBuf buf = create(VideoPacketType.CONFIG);
        writeString(buf, version);
        writeString(buf, config.remoteControlName);
        buf.writeFloat(config.remoteControlId);
        buf.writeFloat(config.remoteControlRange);
        buf.writeFloat(config.noControlRange);
        return toByteArray(buf);
    }

    public static void writeTranslation(ByteBuf buf, VpTranslation translation) {
        VpTranslation safe = translation == null ? VpTranslation.EMPTY : translation;
        writeString(buf, safe.key());
        writeString(buf, safe.fallback());
        List<String> args = safe.args();
        buf.writeByte(Math.min(args.size(), 16));
        for (int i = 0; i < Math.min(args.size(), 16); i++) {
            writeString(buf, args.get(i));
        }
    }

    public static VpTranslation readTranslation(ByteBuf buf) {
        String key = ByteBufUtils.readString(buf, 256);
        String fallback = ByteBufUtils.readString(buf, 1024);
        int argCount = buf.readUnsignedByte();
        ArrayList<String> args = new ArrayList<>(Math.min(argCount, 16));
        for (int i = 0; i < argCount; i++) {
            args.add(ByteBufUtils.readString(buf, 1024));
        }
        return new VpTranslation(key, fallback, args);
    }

    public static byte[] requestResult(int requestId, RequestResultStatus status, VpTranslation message) {
        ByteBuf buf = create(VideoPacketType.REQUEST_RESULT);
        buf.writeInt(requestId);
        buf.writeByte(status.id);
        writeTranslation(buf, message);
        return toByteArray(buf);
    }

    public static byte[] permissions(String areaName, String screenName, long allowedMask) {
        ByteBuf buf = create(VideoPacketType.PERMISSIONS);
        writeString(buf, areaName == null ? "" : areaName);
        writeString(buf, screenName == null ? "" : screenName);
        buf.writeLong(allowedMask);
        return toByteArray(buf);
    }

    public static byte[] request(VideoScreen screen, VideoInfo info) {
        return request(screen, info, false);
    }

    public static byte[] request(VideoScreen screen, VideoInfo info, boolean idle) {
        ByteBuf buf = create(VideoPacketType.REQUEST);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        VideoInfo.write(buf, info);
        buf.writeBoolean(idle);
        return toByteArray(buf);
    }

    public static byte[] sync(VideoScreen screen, long time) {
        ByteBuf buf = create(VideoPacketType.SYNC);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(time);
        return toByteArray(buf);
    }

    public static byte[] seek(VideoScreen screen, long progress) {
        ByteBuf buf = create(VideoPacketType.SEEK);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(progress);
        return toByteArray(buf);
    }

    public static byte[] createArea(VideoArea area) {
        ByteBuf buf = create(VideoPacketType.CREATE_AREA);
        writeString(buf, area.name);
        VideoArea.write(buf, area);
        return toByteArray(buf);
    }

    public static byte[] removeArea(VideoArea area) {
        ByteBuf buf = create(VideoPacketType.REMOVE_AREA);
        writeString(buf, area.name);
        return toByteArray(buf);
    }

    public static byte[] createScreen(List<VideoScreen> screens) {
        ByteBuf buf = create(VideoPacketType.CREATE_SCREEN);
        writeString(buf, screens.getFirst().area.name);
        buf.writeByte(screens.size());
        for (VideoScreen screen : screens) {
            VideoScreen.write(buf, screen);
            writeUv(buf, screen);
            writeScale(buf, screen);
            writeMeta(buf, screen);
        }
        return toByteArray(buf);
    }

    public static byte[] removeScreen(VideoScreen screen) {
        ByteBuf buf = create(VideoPacketType.REMOVE_SCREEN);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] loadArea(VideoArea area) {
        ByteBuf buf = create(VideoPacketType.LOAD_AREA);
        writeString(buf, area.name);
        for (VideoScreen screen : area.screens) {
            VideoInfo info = screen.currentPlayback();
            if (info == null) continue;
            writeString(buf, screen.name);
            VideoInfo.write(buf, info);
            buf.writeLong(screen.getProgress());
            buf.writeBoolean(screen.currentPlaybackIdle());
        }
        return toByteArray(buf);
    }

    public static byte[] unloadArea(VideoArea area) {
        ByteBuf buf = create(VideoPacketType.UNLOAD_AREA);
        writeString(buf, area.name);
        return toByteArray(buf);
    }

    public static byte[] updatePlaylist(List<VideoScreen> screens) {
        ByteBuf buf = create(VideoPacketType.UPDATE_PLAYLIST);
        writeString(buf, screens.getFirst().area.name);
        buf.writeByte(screens.size());
        for (VideoScreen screen : screens) {
            writeString(buf, screen.name);
            buf.writeByte(screen.infos.size());
            for (VideoInfo info : screen.infos) {
                writeString(buf, info.playerName());
                writeString(buf, info.name());
                buf.writeBoolean(info.seekable());
            }
        }
        return toByteArray(buf);
    }

    public static byte[] skip(VideoScreen screen) {
        ByteBuf buf = create(VideoPacketType.SKIP);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] idlePlay(VideoScreen screen) {
        ByteBuf buf = create(VideoPacketType.IDLE_PLAY);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        writeIdlePlayConfig(buf, screen);
        return toByteArray(buf);
    }

    public static byte[] execute(String command) {
        ByteBuf buf = create(VideoPacketType.EXECUTE);
        writeString(buf, command);
        return toByteArray(buf);
    }

    public static byte[] setUv(VideoScreen screen, float u1, float v1, float u2, float v2) {
        ByteBuf buf = create(VideoPacketType.SET_UV);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeFloat(u1);
        buf.writeFloat(v1);
        buf.writeFloat(u2);
        buf.writeFloat(v2);
        return toByteArray(buf);
    }

    public static byte[] setMetadata(VideoScreen screen, String key, MetaValue value) {
        ByteBuf buf = create(VideoPacketType.SET_SCREEN_METADATA);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        ByteBufUtils.writeString(buf, key);
        buf.writeBoolean(false);
        writeMetaValue(buf, value);
        return toByteArray(buf);
    }

    public static byte[] removeMetadata(VideoScreen screen, String key) {
        ByteBuf buf = create(VideoPacketType.SET_SCREEN_METADATA);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        ByteBufUtils.writeString(buf, key);
        buf.writeBoolean(true);
        return toByteArray(buf);
    }

    public static byte[] setScale(VideoScreen screen, boolean fill, float scaleX, float scaleY) {
        ByteBuf buf = create(VideoPacketType.SET_SCALE);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeBoolean(fill);
        buf.writeFloat(scaleX);
        buf.writeFloat(scaleY);
        return toByteArray(buf);
    }

    public static byte[] autoSync(VideoScreen screen, long clientTime, long progress, long serverDelay) {
        ByteBuf buf = create(VideoPacketType.AUTO_SYNC);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(clientTime);
        buf.writeLong(progress);
        buf.writeLong(Math.max(0L, serverDelay));
        return toByteArray(buf);
    }

    public static byte[] updateScreen(VideoScreen screen, List<Vector3f> vertices, String source) {
        return updateScreen(screen, vertices, source, screen);
    }

    public static byte[] updateScreen(VideoScreen screen, List<Vector3f> vertices, String source, VideoScreen displayConfig) {
        ByteBuf buf = create(VideoPacketType.UPDATE_SCREEN);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeByte(vertices.size());
        for (Vector3f vertex : vertices) {
            ByteBufUtils.writeVec3(buf, vertex);
        }
        writeString(buf, source == null ? "" : source);
        VideoScreen.writeDisplayConfig(buf, displayConfig == null ? screen : displayConfig);
        return toByteArray(buf);
    }
}
