package com.unishare.sdk.internal;

import android.content.Context;
import com.unishare.sdk.*;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Compares real core-generated identity material without exposing it in the API. */
public final class IdentityAcceptance {
    public static void run(Context context) throws Exception {
        String name="accept-identity-"+UUID.randomUUID();
        try(ShareSdk sdk=new ShareSdk(context,name)) {
            if(!sdk.initialize(new SdkConfig("identity",8,4,4194304,8388608,104857600,21474836480L,65536,262144,60,5)).getResult().get(20,TimeUnit.SECONDS).isSuccess())throw new AssertionError("identity initialize");
        }
        byte[] first=new IdentityStore(context,name).transact(1,new byte[0]);
        try(ShareSdk sdk=new ShareSdk(context,name)) {
            if(!sdk.initialize(new SdkConfig("restart",8,4,4194304,8388608,104857600,21474836480L,65536,262144,60,5)).getResult().get(20,TimeUnit.SECONDS).isSuccess())throw new AssertionError("identity restart");
        }
        byte[] second=new IdentityStore(context,name).transact(1,new byte[0]);
        if(first.length<100||!Arrays.equals(first,second))throw new AssertionError("DeviceId/key/certificate changed");
        Arrays.fill(first,(byte)0);Arrays.fill(second,(byte)0);
        File dbFile=new File(context.getNoBackupFilesDir(),"unishare-"+name+"/records.db");
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.getPath(),null,0)) {
            db.execSQL("UPDATE records SET v=? WHERE k='identity'",new Object[]{new byte[28]});
        }
        failed(context,name,"corrupt identity");
        String denied="accept-denied-"+UUID.randomUUID();
        File obstruction=new File(context.getNoBackupFilesDir(),"unishare-"+denied);
        if(!obstruction.createNewFile())throw new AssertionError("storage obstruction");
        try { failed(context,denied,"unwritable identity directory"); } finally { obstruction.delete(); }
    }
    private static void failed(Context context,String name,String why) throws Exception {
        try(ShareSdk sdk=new ShareSdk(context,name)) {
            OperationResult result=sdk.initialize(new SdkConfig("failure",8,4,4194304,8388608,104857600,21474836480L,65536,262144,60,5)).getResult().get(20,TimeUnit.SECONDS);
            if(result.getError()==null||result.getError().getCategory()!=ErrorCategory.STORAGE_FAILED)throw new AssertionError(why+": "+result);
        }
    }
}
