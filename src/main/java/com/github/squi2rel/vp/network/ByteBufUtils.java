package com.github.squi2rel.vp.network;

import io.netty.buffer.ByteBuf;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;

public class ByteBufUtils {
    public static String readString(ByteBuf buf, int maxLength) {
        int len = buf.readUnsignedShort();
        if (len > maxLength) throw new IllegalStateException(String.format("length(%d) exceeds max length(%d)", len, maxLength));
        byte[] data = new byte[len];
        buf.readBytes(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String str) {
        byte[] data = (str == null ? "" : str).getBytes(StandardCharsets.UTF_8);
        if (data.length > 0xFFFF) throw new IllegalArgumentException("UTF-8 string exceeds 65535 bytes");
        buf.writeShort(data.length);
        buf.writeBytes(data);
    }

    public static int utf8Length(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8).length;
    }

    public static String truncateUtf8(String value, int maxBytes) {
        String safe = value == null ? "" : value;
        if (maxBytes <= 0) return "";
        if (utf8Length(safe) <= maxBytes) return safe;
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < safe.length();) {
            int codePoint = safe.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            int bytes = utf8Length(next);
            if (used + bytes > maxBytes) break;
            result.append(next);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    public static Vector3f readVec3(ByteBuf buf) {
        return new Vector3f(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void writeVec3(ByteBuf buf, Vector3f v) {
        buf.writeFloat(v.x);
        buf.writeFloat(v.y);
        buf.writeFloat(v.z);
    }
}
