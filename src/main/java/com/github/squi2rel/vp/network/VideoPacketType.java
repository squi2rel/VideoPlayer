package com.github.squi2rel.vp.network;

import java.util.HashMap;
import java.util.Map;

public enum VideoPacketType {
    CONFIG(0),
    REQUEST(1),
    SYNC(2),
    CREATE_AREA(3),
    REMOVE_AREA(4),
    CREATE_SCREEN(5),
    REMOVE_SCREEN(6),
    LOAD_AREA(7),
    UNLOAD_AREA(8),
    UPDATE_PLAYLIST(9),
    SKIP(10),
    SKIP_PERCENT(11),
    EXECUTE(12),
    IDLE_PLAY(13),
    SET_UV(14),
    OPEN_MENU(15),
    SET_SCALE(16),
    AUTO_SYNC(17),
    UPDATE_SCREEN(18),
    SET_SCREEN_METADATA(19),
    SEEK(20),
    REQUEST_RESULT(21),
    PERMISSIONS(22),
    RESET_CLIENT(23),
    PROTOCOL_REJECT(24),
    HANDSHAKE_ACK(25);

    private static final Map<Integer, VideoPacketType> BY_ID = new HashMap<>();

    static {
        for (VideoPacketType type : values()) {
            BY_ID.put(type.id, type);
        }
    }

    public final int id;

    VideoPacketType(int id) {
        this.id = id;
    }

    public static VideoPacketType fromId(int id) {
        return BY_ID.get(id);
    }
}
