package com.unishare.sdk.internal;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
/** Read-only, grantable URI for explicitly written clipboard images. */
public final class ImageProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{String name=uri.getLastPathSegment();if(name==null||!name.matches("[0-9a-f-]{36}"))throw new FileNotFoundException("invalid image URI");return new File(new File(getContext().getCacheDir(),"unishare-clipboard"),name);}
    public String getType(Uri uri){String mime=uri.getQueryParameter("mime");return mime!=null&&mime.startsWith("image/")?mime:"application/octet-stream";}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("read-only clipboard image");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});File f=file(uri);c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(IOException e){return null;}}
    public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
}
