package com.unishare.sdk.internal;
import android.content.Context;
/** Internal ownership bridge, not the business-facing SDK API. */
public final class NativeBridge implements AutoCloseable {
    static {System.loadLibrary("unishare_android");}
    private final FrameworkPorts ports;private long platform;
    public NativeBridge(Context context,String store){ports=new FrameworkPorts(context,store);platform=create(ports);}
    public synchronized long platformHandle(){if(platform==0)throw new IllegalStateException("platform released");return platform;}
    public synchronized void setForeground(boolean foreground){platformHandle();ports.setForeground(foreground);}
    public synchronized long openStream(String uri,boolean destination){return open(platformHandle(),uri.getBytes(java.nio.charset.StandardCharsets.UTF_8),destination);}
    public synchronized void close(){if(platform!=0){release(platform);platform=0;}}
    public static native void releaseStream(long stream);
    private static native long create(FrameworkPorts ports);
    private static native void release(long platform);
    private static native long open(long platform,byte[] uri,boolean destination);
    static native void complete(long key,long operation,byte[] result);
    static native void networkChanged(long key);
    static native void suspended(long key);
}
