package com.unishare.sdk.internal;
import android.content.*;
import android.net.Uri;
import java.io.*;
import java.util.UUID;
final class ClipboardPort {
    interface Boundary {ClipData get();void set(ClipData data);}
    private final Context context;private final Boundary boundary;
    ClipboardPort(Context c,Boundary b){context=c;boundary=b;}
    byte[] read()throws Exception{
        ClipData clip=boundary.get();if(clip==null||clip.getItemCount()!=1)throw new PortFailure(13,"single text or image clip required",null);
        ClipData.Item item=clip.getItemAt(0);
        if(clip.getDescription().hasMimeType("text/plain")&&item.getText()!=null)return content(1,item.getText().toString(),"",new byte[0]);
        if(item.getUri()!=null){String mime=context.getContentResolver().getType(item.getUri());if(mime!=null&&mime.startsWith("image/")){
            try(InputStream stream=context.getContentResolver().openInputStream(item.getUri())){if(stream==null)throw new IOException("image URI not readable");
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[65536];int count;
                while((count=stream.read(buffer))!=-1){if(out.size()+count>100*1024*1024)throw new PortFailure(14,"image clip limit",null);out.write(buffer,0,count);}return content(2,"",mime,out.toByteArray());}
        }}throw new PortFailure(13,"clipboard format unsupported",null);
    }
    static byte[] content(int type,String text,String mime,byte[] bytes)throws Exception{return Packet.make(d->{d.writeInt(type);Packet.text(d,text);Packet.text(d,mime);Packet.bytes(d,bytes);});}
    void write(byte[] data)throws Exception{
        DataInputStream in=Packet.input(data);int type=in.readInt();String text=Packet.text(in),mime=Packet.text(in);byte[] bytes=Packet.bytes(in);
        if(in.available()!=0)throw new IllegalArgumentException("trailing clipboard bytes");
        if(type==1){boundary.set(ClipData.newPlainText("UniShare",text));return;}
        if(type!=2||!mime.startsWith("image/")||bytes.length==0)throw new PortFailure(13,"clipboard type unsupported",null);
        File directory=new File(context.getCacheDir(),"unishare-clipboard");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("clipboard directory");
        String name=UUID.randomUUID().toString();File file=new File(directory,name);
        try{try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
            Uri uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".unishare.images").appendPath(name).appendQueryParameter("mime",mime).build();
            boundary.set(ClipData.newUri(context.getContentResolver(),"UniShare image",uri));
        }catch(Exception e){file.delete();throw e;}
    }
}
