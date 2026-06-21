package com.github.squi2rel.vp.permission;

import java.util.UUID;

public interface VideoPermissionPlayer {
    UUID uuid();

    String name();

    boolean opOrGameMaster();
}
