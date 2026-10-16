package com.github.squi2rel.vp.vivecraft;

import com.github.squi2rel.vp.VideoPlayerMain;
import java.lang.reflect.Method;
import org.joml.Matrix4f;

/** Optional Vivecraft 1.21.1 integration without a bundled VR dependency. */
final class VivecraftImpl {
    private static final Object HOLDER;
    private static final Class<?> STATE;
    static {
        Object holder = null;
        Class<?> state = null;
        try {
            Class<?> type = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            holder = type.getMethod("getInstance").invoke(null);
            state = Class.forName("org.vivecraft.client_vr.VRState");
        } catch (ReflectiveOperationException | LinkageError error) {
            VideoPlayerMain.LOGGER.warn("Could not initialize Vivecraft integration", error);
        }
        HOLDER = holder;
        STATE = state;
    }
    static boolean isRightEye() {
        try { return HOLDER != null && "RIGHT".equals(HOLDER.getClass().getField("currentPass").get(HOLDER).toString()); }
        catch (ReflectiveOperationException error) { return false; }
    }
    static boolean isVRActive() {
        try { return STATE != null && STATE.getField("VR_RUNNING").getBoolean(null); }
        catch (ReflectiveOperationException error) { return false; }
    }
    static Matrix4f getRotation() {
        try {
            Object player = HOLDER.getClass().getField("vrPlayer").get(HOLDER);
            Object data = player.getClass().getMethod("getVRDataWorld").invoke(player);
            Object pass = HOLDER.getClass().getField("currentPass").get(HOLDER);
            Method eyeMethod = data.getClass().getMethod("getEye", pass.getClass());
            Object eye = eyeMethod.invoke(data, pass);
            return new Matrix4f((Matrix4f) eye.getClass().getMethod("getMatrix").invoke(eye));
        } catch (ReflectiveOperationException | NullPointerException error) { return new Matrix4f(); }
    }
}
