package com.unishare.sdk.internal;
import android.content.Context;
/** Internal ownership bridge, not the business-facing SDK API. */
public final class NativeBridge implements AutoCloseable {
    static {System.loadLibrary("unishare_android");}
    private final FrameworkPorts ports;private long platform;
    public NativeBridge(Context context,String store){ports=new FrameworkPorts(context,store);platform=create(ports);}
    public synchronized long platformHandle(){if(platform==0)throw new IllegalStateException("platform released");return platform;}
    public synchronized void setForeground(boolean foreground){platformHandle();ports.setForeground(foreground);}
    public long openStream(String uri,boolean destination){return open(platformHandle(),uri.getBytes(java.nio.charset.StandardCharsets.UTF_8),destination);}
    public long[] stageText(String text,long limit)throws java.io.IOException {
        long owner=platformHandle();
        java.io.File file=StagedText.create(ports.cacheDirectory(),text,limit);
        String token=ports.stageText(file);
        try{return new long[]{open(owner,token.getBytes(java.nio.charset.StandardCharsets.UTF_8),false),file.length()};}
        finally{ports.discardText(token);}
    }
    public synchronized void close(){if(platform!=0){release(platform);platform=0;}}
    public static native void releaseStream(long stream);
    public static native void abortStream(long stream);
    private static native long create(FrameworkPorts ports);
    private static native void release(long platform);
    private static native long open(long platform,byte[] uri,boolean destination);
    static native void complete(long key,long operation,byte[] result);
    static native void networkChanged(long key);
    static native void suspended(long key);
}
