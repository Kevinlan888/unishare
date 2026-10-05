package com.unishare.sdk.internal;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
public final class ProbeProvider extends ContentProvider {
    File file(Uri uri){return new File(getContext().getCacheDir(),uri.getLastPathSegment());}
    public boolean onCreate(){return true;}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if("pipe".equals(uri.getLastPathSegment()))try{ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();pipe[0].close();return pipe[1];}catch(IOException e){throw new FileNotFoundException(e.toString());}
        return ParcelFileDescriptor.open(file(uri),mode.contains("w")?ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE:ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public int delete(Uri uri,String selection,String[] args){return file(uri).delete()?1:0;}
    public String getType(Uri uri){return "image/png";}
    public Cursor query(Uri u,String[] p,String s,String[] a,String order){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
