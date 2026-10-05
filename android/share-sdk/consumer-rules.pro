-keep class com.unishare.sdk.internal.NativeBridge { *; }
-keep class com.unishare.sdk.internal.NativePortException { *; }
-keep class com.unishare.sdk.internal.SdkNative { *; }
-keep class * implements com.unishare.sdk.internal.SdkNative$Receiver {
    public void completion(long, byte[]);
    public void event(byte[]);
}
-keep class com.unishare.sdk.internal.FrameworkPorts {
    long nativeKey;
    byte[] call(int, byte[]);
    void start(int, long, byte[]);
    void shutdown();
}
