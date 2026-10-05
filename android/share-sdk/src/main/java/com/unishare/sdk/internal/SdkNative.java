package com.unishare.sdk.internal;

/** Wire packets are private to the AAR; only the frozen C ABI reaches the core. */
public final class SdkNative {
    static { System.loadLibrary("unishare_android"); }
    public interface Receiver {
        void completion(long key, byte[] copiedResult);
        void event(byte[] copiedEvent);
    }
    public static native long create(long platform, byte[] config, long key, Receiver receiver);
    public static native long submit(long sdk, int command, byte[] uuid, long request, long stream,
                                    long number, byte[] text, byte[] name, byte[] mime,
                                    byte[] bytes, long byteCount, long key);
    public static native byte[] snapshot(long sdk, boolean trusted);
    public static native int state(long sdk);
    public static native void destroy(long sdk);
    private SdkNative() {}
}
