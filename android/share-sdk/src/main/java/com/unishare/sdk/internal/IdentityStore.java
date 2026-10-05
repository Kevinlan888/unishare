package com.unishare.sdk.internal;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Core DER is exportable in memory; only the AES wrapping key is non-exportable. */
final class IdentityStore {
    private static final Object KEY_LOCK = new Object();
    private final File directory;
    private final String alias;
    IdentityStore(Context context, String name) {
        if (!name.matches("[A-Za-z0-9-]{1,100}")) throw new IllegalArgumentException("invalid store name");
        directory = new File(context.getNoBackupFilesDir(), "unishare-" + name);
        alias = "unishare-wrap-" + name;
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(alias)) return (SecretKey)ks.getKey(alias,null);
        if (!create) throw new IOException("identity wrapping key missing");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    private byte[] crypt(byte[] value,String row,boolean encrypt,boolean create) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        if(encrypt) cipher.init(Cipher.ENCRYPT_MODE,key(create));
        else { if(value.length<28) throw new IOException("truncated encrypted record");
            cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Arrays.copyOf(value,12))); }
        cipher.updateAAD(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!encrypt) return cipher.doFinal(value,12,value.length-12);
        byte[] encrypted=cipher.doFinal(value),iv=cipher.getIV(),out=new byte[iv.length+encrypted.length];
        System.arraycopy(iv,0,out,0,iv.length);System.arraycopy(encrypted,0,out,iv.length,encrypted.length);return out;
    }
    private byte[] read(SQLiteDatabase db,String row) throws Exception {
        try(Cursor c=db.rawQuery("SELECT v FROM records WHERE k=?",new String[]{row})) {
            return c.moveToFirst()?crypt(c.getBlob(0),row,false,false):null;
        }
    }
    private void write(SQLiteDatabase db,String row,byte[] value,boolean create) throws Exception {
        db.execSQL("INSERT INTO records(k,v) VALUES(?,?)",new Object[]{row,crypt(value,row,true,create)});
    }
    byte[] transact(int command,byte[] input) throws Exception {
        synchronized(KEY_LOCK) {
            if(!directory.isDirectory()&&!directory.mkdirs()) throw new IOException("cannot create identity directory");
            try(RandomAccessFile lock=new RandomAccessFile(new File(directory,"lock"),"rw");
                FileChannel channel=lock.getChannel();FileLock held=channel.lock();
                SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(new File(directory,"records.db"),null)) {
                db.execSQL("PRAGMA synchronous=FULL");
                db.execSQL("CREATE TABLE IF NOT EXISTS records(k TEXT PRIMARY KEY,v BLOB NOT NULL)");
                db.beginTransaction();
                try {
                    byte[] result;
                    switch(command) {
                    case 1: { byte[] value=read(db,"identity"); if(value==null) result=new byte[]{0};
                        else {validateIdentity(value);result=new byte[value.length+1];result[0]=1;System.arraycopy(value,0,result,1,value.length);Arrays.fill(value,(byte)0);} break; }
                    case 2: {validateIdentity(input); byte[] old=read(db,"identity");if(old!=null){Arrays.fill(old,(byte)0);throw new IOException("identity already exists");}
                        write(db,"identity",input,true); result=new byte[0];break;}
                    case 3: {ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
                        try(Cursor c=db.rawQuery("SELECT k,v FROM records WHERE k LIKE 'trust:%' ORDER BY k",null)) {
                            d.writeInt(c.getCount()); while(c.moveToNext()) {byte[] value=crypt(c.getBlob(1),c.getString(0),false,false);
                                if(value.length!=48)throw new IOException("corrupt trust record");d.write(value);Arrays.fill(value,(byte)0);}}
                        result=out.toByteArray();break;}
                    case 4: {if(input.length!=48)throw new IllegalArgumentException("trust length");String row="trust:"+hex(input,16);
                        byte[] old=read(db,row);if(old!=null&&!MessageDigest.isEqual(old,input))throw new PortFailure(6,"identity binding changed",null);
                        if(old==null)write(db,row,input,true);else Arrays.fill(old,(byte)0);result=new byte[0];break;}
                    case 5: {if(input.length!=16)throw new IllegalArgumentException("device id length");db.delete("records","k=?",new String[]{"trust:"+hex(input,16)});result=new byte[0];break;}
                    default: throw new IllegalArgumentException("identity command");
                    }
                    db.setTransactionSuccessful();return result;
                } finally {db.endTransaction();}
            }
        }
    }
    private static String hex(byte[] bytes,int n){StringBuilder s=new StringBuilder();for(int i=0;i<n;i++)s.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));return s.toString();}
    private static void validateIdentity(byte[] bytes) throws IOException {
        DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes));byte[] id=new byte[16];d.readFully(id);
        for(int i=0;i<2;i++){int n=d.readInt();if(n<=0||n>1024*1024||n>d.available())throw new IOException("corrupt identity length");d.skipBytes(n);}
        if(d.available()!=0)throw new IOException("trailing identity bytes");
    }
}
