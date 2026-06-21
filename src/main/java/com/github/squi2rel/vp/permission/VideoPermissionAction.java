package com.github.squi2rel.vp.permission;

public enum VideoPermissionAction {
    PLAY,
    SEEK,
    SYNC,
    VOTE_SKIP,
    FORCE_SKIP,
    SET_SKIP_PERCENT,
    CREATE_AREA,
    REMOVE_AREA,
    CREATE_SCREEN,
    REMOVE_SCREEN,
    UPDATE_SCREEN,
    SET_UV,
    SET_SCALE,
    SET_METADATA,
    SET_IDLE_PLAY,
    AUTO_SYNC,
    OPEN_MENU;

    public long bit() {
        return 1L << ordinal();
    }
}
