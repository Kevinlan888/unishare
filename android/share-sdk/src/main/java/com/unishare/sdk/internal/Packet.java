package com.unishare.sdk.internal;
import java.io.*;
import java.nio.charset.StandardCharsets;
final class Packet {
    interface Writer {void write(DataOutputStream out)throws Exception;}
    static byte[] make(Writer writer)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();writer.write(new DataOutputStream(b));return b.toByteArray();}
    static void bytes(DataOutputStream d,byte[] b)throws IOException{d.writeInt(b.length);d.write(b);}
    static byte[] bytes(DataInputStream d)throws IOException{int n=d.readInt();if(n<0||n>100*1024*1024||n>d.available())throw new IOException("bridge packet length");byte[] b=new byte[n];d.readFully(b);return b;}
    static void text(DataOutputStream d,String s)throws IOException{bytes(d,s.getBytes(StandardCharsets.UTF_8));}
    static String text(DataInputStream d)throws IOException{return new String(bytes(d),StandardCharsets.UTF_8);}
    static DataInputStream input(byte[] b){return new DataInputStream(new ByteArrayInputStream(b));}
    static byte[] success(byte[] payload){try{return make(d->{d.writeInt(0);d.writeLong(Long.MIN_VALUE);text(d,"");d.write(payload);});}catch(Exception e){throw new IllegalStateException(e);}}
    static byte[] failure(Throwable failure,int fallback){try{
        int code=failure instanceof PortFailure?((PortFailure)failure).code:failure instanceof SecurityException?1:failure instanceof IllegalArgumentException?13:fallback;
        Long nativeCode=failure instanceof PortFailure?((PortFailure)failure).nativeCode:null;
        for(Throwable e=failure;e!=null;e=e.getCause()){
            if(e instanceof android.system.ErrnoException){nativeCode=(long)((android.system.ErrnoException)e).errno;break;}
            if(android.os.Build.VERSION.SDK_INT>=33&&e instanceof android.security.KeyStoreException){nativeCode=(long)((android.security.KeyStoreException)e).getNumericErrorCode();break;}
        }
        final Long raw=nativeCode;return make(d->{d.writeInt(code);d.writeLong(raw==null?Long.MIN_VALUE:raw);text(d,failure.getClass().getSimpleName()+": "+failure.getMessage());});
    }catch(Exception e){throw new IllegalStateException(e);}}
}
