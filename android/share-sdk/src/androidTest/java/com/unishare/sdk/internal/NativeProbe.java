package com.unishare.sdk.internal;
final class NativeProbe {
    static {System.loadLibrary("unishare_android_probe");}
    static native String run(FrameworkPorts framework,String uri);
}
