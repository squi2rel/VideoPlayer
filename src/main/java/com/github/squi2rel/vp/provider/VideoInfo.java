package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public record VideoInfo(String playerName, String name, String path, String rawPath, long expire, boolean seekable, String[] params, long durationMs) {
    private static final int MAX_NAME_BYTES = 256;
    private static final int MAX_PATH_BYTES = 8192;
    private static final int MAX_PARAM_BYTES = 8192;
    public static final int MAX_PARAMS = 32;
    public static final int MAX_TOTAL_PARAM_BYTES = 8192;
    public static final int MAX_ENCODED_BYTES = 24_000;

    public VideoInfo {
        playerName = ByteBufUtils.truncateUtf8(safe(playerName), MAX_NAME_BYTES);
        name = ByteBufUtils.truncateUtf8(safe(name), MAX_NAME_BYTES);
        path = safe(path);
        rawPath = safe(rawPath);
        if (params == null) params = new String[0];
        if (params.length > MAX_PARAMS) throw new IllegalArgumentException("Video parameter count exceeds " + MAX_PARAMS);
        params = params.clone();
        int totalParamBytes = 0;
        for (int i = 0; i < params.length; i++) {
            params[i] = safe(params[i]);
            int length = bytes(params[i]);
            if (length > MAX_PARAM_BYTES) throw new IllegalArgumentException("Video parameter exceeds " + MAX_PARAM_BYTES + " bytes");
            totalParamBytes += length;
        }
        if (totalParamBytes > MAX_TOTAL_PARAM_BYTES) {
            throw new IllegalArgumentException("Video parameters exceed " + MAX_TOTAL_PARAM_BYTES + " bytes");
        }
        if (bytes(path) > MAX_PATH_BYTES || bytes(rawPath) > MAX_PATH_BYTES) {
            throw new IllegalArgumentException("Video path exceeds " + MAX_PATH_BYTES + " bytes");
        }
        int encodedBytes = 2 * 4 + bytes(playerName) + bytes(name) + bytes(path) + bytes(rawPath)
                + 8 + 1 + 1 + params.length * 2 + totalParamBytes + 8;
        if (encodedBytes > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Video info exceeds " + MAX_ENCODED_BYTES + " bytes");
        }
        durationMs = Math.max(0L, durationMs);
    }

    public VideoInfo(String playerName, String name, String path, String rawPath, long expire, boolean seekable, String[] params) {
        this(playerName, name, path, rawPath, expire, seekable, params, 0L);
    }

    public static void write(ByteBuf buf, VideoInfo i) {
        ByteBufUtils.writeString(buf, i.playerName);
        ByteBufUtils.writeString(buf, i.name);
        ByteBufUtils.writeString(buf, i.path);
        ByteBufUtils.writeString(buf, i.rawPath);
        buf.writeLong(i.expire);
        buf.writeBoolean(i.seekable);
        buf.writeByte(i.params.length);
        for (String params : i.params) {
            ByteBufUtils.writeString(buf, params);
        }
        buf.writeLong(i.durationMs);
    }

    public static VideoInfo read(ByteBuf buf) {
        String playerName = ByteBufUtils.readString(buf, MAX_NAME_BYTES);
        String name = ByteBufUtils.readString(buf, MAX_NAME_BYTES);
        String path = ByteBufUtils.readString(buf, MAX_PATH_BYTES);
        String rawPath = ByteBufUtils.readString(buf, MAX_PATH_BYTES);
        long expire = buf.readLong();
        boolean seekable = buf.readBoolean();
        int length = buf.readUnsignedByte();
        if (length > MAX_PARAMS) throw new IllegalStateException("Video parameter count exceeds " + MAX_PARAMS);
        String[] params = new String[length];
        for (int i = 0; i < length; i++) {
            params[i] = ByteBufUtils.readString(buf, MAX_PARAM_BYTES);
        }
        long durationMs = buf.readLong();
        return new VideoInfo(playerName, name, path, rawPath, expire, seekable, params, durationMs);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
