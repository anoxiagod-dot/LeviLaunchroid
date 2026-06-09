package org.levimc.launcher.core.mods.inbuilt.nativemod;

public class FreeCamMod {
    public static native boolean nativeInit();
    public static native void nativeActivate(float x, float y, float z, float yaw, float pitch);
    public static native void nativeDeactivate();
    public static native void nativeMoveCamera(float dx, float dy, float dz);
    public static native void nativeRotateCamera(float dyaw, float dpitch);
    public static native void nativeSetSpeed(float speed);
    public static native float nativeGetCamX();
    public static native float nativeGetCamY();
    public static native float nativeGetCamZ();
    public static native boolean nativeIsActive();
    public static native boolean nativeIsInitialized();
}