package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

public record VideoInfo(String playerName, String name, String path, String rawPath, long expire, boolean seekable, String[] params, long durationMs) {
    private static final int MAX_NAME_BYTES = 256;
    private static final int MAX_PATH_BYTES = 8192;
    private static final int MAX_PARAM_BYTES = 8192;

    public VideoInfo {
        if (params == null) params = new String[0];
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
        String[] params = new String[length];
        for (int i = 0; i < length; i++) {
            params[i] = ByteBufUtils.readString(buf, MAX_PARAM_BYTES);
        }
        long durationMs = buf.readLong();
        return new VideoInfo(playerName, name, path, rawPath, expire, seekable, params, durationMs);
    }
}
