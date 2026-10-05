package com.unishare.sdk.internal;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
final class ContentStreams {
    private final ContentResolver resolver;
    private final AtomicLong ids=new AtomicLong();
    private final ConcurrentHashMap<Long,Entry> entries=new ConcurrentHashMap<>();
    private static final class Entry {
        final Uri uri;final boolean destination;final ParcelFileDescriptor fd;final InputStream in;final OutputStream out;
        volatile boolean closed;
        Entry(Uri u,boolean w,ParcelFileDescriptor p)throws IOException{uri=u;destination=w;fd=p;
            in=w?null:new FileInputStream(p.getFileDescriptor());out=w?new FileOutputStream(p.getFileDescriptor()):null;}
    }
    ContentStreams(ContentResolver resolver) {this.resolver=resolver;}
    long open(String text,boolean write)throws Exception{
        Uri uri=Uri.parse(text);if(!"content".equals(uri.getScheme()))throw new IllegalArgumentException("host-selected content URI required");
        ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,write?"rwt":"r");
        if(fd==null)throw new IOException("provider did not open URI");
        try{if(write&&!OsConstants.S_ISREG(Os.fstat(fd.getFileDescriptor()).st_mode))throw new IOException("destination must be a regular staging file, not a commit-on-close pipe");
            Entry entry=new Entry(uri,write,fd);long id=ids.incrementAndGet();entries.put(id,entry);return id;}
        catch(Exception e){fd.close();throw e;}
    }
    private Entry get(long id)throws IOException{Entry e=entries.get(id);if(e==null||e.closed)throw new IOException("stream closed");return e;}
    byte[] read(long handle,int count)throws Exception{
        if(count<=0||count>4*1024*1024)throw new IllegalArgumentException("read size");Entry e=get(handle);
        synchronized(e){if(e.destination)throw new IOException("destination is not readable");byte[] bytes=new byte[count];int n=e.in.read(bytes);
            if(e.closed)throw new PortFailure(8,"stream closed",null);return n<0?new byte[0]:Arrays.copyOf(bytes,n);}
    }
    void write(long handle,byte[] bytes)throws Exception{
        if(bytes.length>4*1024*1024)throw new IllegalArgumentException("write size");Entry e=get(handle);
        synchronized(e){if(!e.destination)throw new IOException("source is not writable");e.out.write(bytes);e.out.flush();
            if(OsConstants.S_ISREG(Os.fstat(e.fd.getFileDescriptor()).st_mode))e.fd.getFileDescriptor().sync();
            if(e.closed)throw new PortFailure(8,"stream closed",null);}
    }
    void close(long handle,boolean abort)throws Exception{
        Entry e=entries.remove(handle);if(e==null)return;e.closed=true;
        // Retire the entry and close its descriptor before deleting the staged inode.
        try{e.fd.close();}finally{try{if(e.in!=null)e.in.close();if(e.out!=null)e.out.close();}finally{
            if(abort&&e.destination&&resolver.delete(e.uri,null,null)==0)throw new IOException("provider did not delete staging destination");}}
    }
}
