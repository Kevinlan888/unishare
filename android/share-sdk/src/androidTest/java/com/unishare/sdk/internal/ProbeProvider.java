package com.unishare.sdk.internal;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
public final class ProbeProvider extends ContentProvider {
    public static volatile java.util.concurrent.CountDownLatch delayedOpenEntered, delayedOpenRelease, delayedDelete;
    File file(Uri uri){return new File(getContext().getCacheDir(),uri.getLastPathSegment());}
    public boolean onCreate(){return true;}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(uri.getLastPathSegment().startsWith("delayed-public-")){
            delayedOpenEntered.countDown();
            try{if(!delayedOpenRelease.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new FileNotFoundException("delayed provider timed out");}
            catch(InterruptedException e){throw new FileNotFoundException(e.toString());}
        }
        if("pipe".equals(uri.getLastPathSegment()))try{ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();pipe[0].close();return pipe[1];}catch(IOException e){throw new FileNotFoundException(e.toString());}
        return ParcelFileDescriptor.open(file(uri),mode.contains("w")?ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE:ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public int delete(Uri uri,String selection,String[] args){boolean deleted=file(uri).delete();if(uri.getLastPathSegment().startsWith("delayed-public-"))delayedDelete.countDown();return deleted?1:0;}
    public String getType(Uri uri){return "image/png";}
    public Cursor query(Uri u,String[] p,String s,String[] a,String order){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
