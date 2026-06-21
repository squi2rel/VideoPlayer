package com.github.squi2rel.vp.permission;

@FunctionalInterface
public interface GlobalPermissionChecker {
    boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context);
}
