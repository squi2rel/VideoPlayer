package com.github.squi2rel.vp.permission;

@FunctionalInterface
public interface AreaPermissionChecker {
    boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context);
}
