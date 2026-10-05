package com.unishare.sdk.internal;
import android.content.*;
import android.net.*;
import android.net.nsd.NsdManager;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

final class FrameworkPorts {
    private final Context context;private final IdentityStore identity;private final ContentStreams streams;private final DnsPort dns;private final ClipboardPort clipboard;
    private final ExecutorService io=Executors.newCachedThreadPool();private final Handler main=new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback watch;private volatile boolean foreground,closed,retired;private volatile long dnsEpoch;private volatile long watchEpoch;long nativeKey;
    FrameworkPorts(Context c,String store){context=c.getApplicationContext();identity=new IdentityStore(context,store);streams=new ContentStreams(context.getContentResolver());
        dns=new DnsPort(new AndroidNsd(context.getSystemService(NsdManager.class),command->main.post(command)),this::emit);
        ClipboardManager manager=context.getSystemService(ClipboardManager.class);
        clipboard=new ClipboardPort(context,new ClipboardPort.Boundary(){public ClipData get(){return manager.getPrimaryClip();}public void set(ClipData c){manager.setPrimaryClip(c);}});
    }
    void setForeground(boolean value){foreground=value;if(!value)NativeBridge.suspended(nativeKey);}
    private void permission()throws PortFailure{PermissionPolicy.check(new PermissionPolicy.Boundary(){
        public boolean foreground(){return foreground;}public int sdk(){return Build.VERSION.SDK_INT;}public int target(){return context.getApplicationInfo().targetSdkVersion;}
        public boolean granted(){return context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    });}
    byte[] call(int command,byte[] data){try{
        DataInputStream d=Packet.input(data);byte[] result;
        if(command>=1&&command<=5)result=identity.transact(command,data);
        else switch(command){
        case 10:case 11: {long id=streams.open(Packet.text(d),command==11);result=Packet.make(out->out.writeLong(id));break;}
        case 14:case 15:streams.close(d.readLong(),command==15);result=new byte[0];break;
        case 42:++dnsEpoch;dns.stop();result=new byte[0];break;
        case 60:permission();result=new byte[0];break;
        case 61:watch(d.readBoolean());result=new byte[0];break;
        case 62:retired=true;foreground=false;result=new byte[0];break;
        default:throw new IllegalArgumentException("bridge command");
        }byte[] response=Packet.success(result);if(command==1)java.util.Arrays.fill(result,(byte)0);return response;
    }catch(Throwable e){return Packet.failure(e,command>=40&&command<=42?2:11);}}
    void start(int command,long operation,byte[] data){
        long submittedDnsEpoch=dnsEpoch;
        Runnable task=()->{try{DataInputStream d=Packet.input(data);byte[] result;
            if(closed||(retired&&command>=30)||(command>=40&&submittedDnsEpoch!=dnsEpoch))throw new PortFailure(8,"adapter operation retired",null);
            switch(command){
            case 12:result=streams.read(d.readLong(),d.readInt());break;
            case 13:streams.write(d.readLong(),Packet.bytes(d));result=new byte[0];break;
            case 30:result=clipboard.read();break;
            case 31:clipboard.write(data);result=new byte[0];break;
            case 40:{permission();DnsPort.Record r=new DnsPort.Record();r.instance=Packet.text(d);r.port=d.readInt();int n=d.readInt();if(n<0||n>32)throw new IllegalArgumentException("TXT count");
                for(int i=0;i<n;i++)r.txt.put(Packet.text(d),Packet.bytes(d));dns.publish(operation,r);return;}
            case 41:permission();dns.browse(operation);return;
            default:throw new IllegalArgumentException("async bridge command");
            }emit(operation,Packet.success(result));
        }catch(Throwable e){emit(operation,Packet.failure(e,command>=40?2:11));}};
        try{if(command>=30)main.post(task);else io.execute(task);}catch(RuntimeException e){emit(operation,Packet.failure(e,14));}
    }
    private void emit(long operation,byte[] result){NativeBridge.complete(nativeKey,operation,result);}
    @SuppressWarnings("deprecation")
    private synchronized void watch(boolean enabled){ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        long epoch=++watchEpoch;
        if(watch!=null){manager.unregisterNetworkCallback(watch);watch=null;}
        if(enabled){java.util.Map<Network,LinkProperties> baseline=new java.util.HashMap<>();for(Network network:manager.getAllNetworks())baseline.put(network,manager.getLinkProperties(network));
            ConnectivityManager.NetworkCallback next=new ConnectivityManager.NetworkCallback(){
            public void onAvailable(Network network){if(epoch!=watchEpoch)return;synchronized(baseline){if(baseline.containsKey(network))return;baseline.put(network,manager.getLinkProperties(network));}NativeBridge.networkChanged(nativeKey);}
            public void onLost(Network network){if(epoch!=watchEpoch)return;synchronized(baseline){if(!baseline.containsKey(network))return;baseline.remove(network);}NativeBridge.networkChanged(nativeKey);}
            public void onLinkPropertiesChanged(Network network,LinkProperties properties){if(epoch!=watchEpoch)return;synchronized(baseline){LinkProperties old=baseline.put(network,properties);if(java.util.Objects.equals(old,properties))return;}NativeBridge.networkChanged(nativeKey);}
        };manager.registerNetworkCallback(new NetworkRequest.Builder().build(),next);watch=next;}
    }
    void shutdown(){closed=true;++dnsEpoch;try{watch(false);}catch(RuntimeException ignored){}try{dns.stop();}catch(RuntimeException ignored){}io.shutdown();}
}
