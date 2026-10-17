package com.github.squi2rel.vp.permission;

@FunctionalInterface
public interface AreaPermissionResolver {
    AreaPermissionDecision resolve(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context);

    default AreaPermissionResolver prepare(VideoPermissionPlayer player, VideoPermissionContext context) {
        return this;
    }
}
