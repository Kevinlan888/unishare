package com.unishare.sdk.internal;

import android.content.ClipData;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** Real adapter contracts; framework registration is the external boundary. */
@SuppressWarnings("deprecation")
public final class ReleaseAuditCases {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}

    public static void clipboardReplacement(Context context)throws Exception {
        class Boundary implements ClipboardPort.Boundary {
            ClipData clip;
            public ClipData get(){return clip;}
            public void set(ClipData value){clip=value;}
        }
        Boundary boundary=new Boundary();ClipboardPort port=new ClipboardPort(context,boundary);
        port.write(Packet.make(out->{out.writeInt(2);Packet.text(out,"");Packet.text(out,"image/png");Packet.bytes(out,new byte[]{(byte)137,80,78,71});}));
        check(boundary.clip.getItemAt(0).getUri()!=null,"image write must publish a URI");
        boundary.clip=ClipData.newPlainText("external writer","replacement 世界");
        DataInputStream read=Packet.input(port.read());
        check(read.readInt()==1&&Packet.text(read).equals("replacement 世界"),"read must return the current external text, never cached image");
        boundary.clip=null;
        boolean empty=false;try{port.read();}catch(PortFailure error){empty=error.code==13;}
        check(empty,"clear must return InvalidInput, never cached content");
    }

    public static void nsdVersionDispatch()throws Exception {
        for(int sdk:new int[]{26,32,33,34}) {
            class Framework implements AndroidNsd.Framework {
                NsdManager.DiscoveryListener browser;Object update;int resolves,updates,unregisters;
                public void register(NsdServiceInfo info,NsdManager.RegistrationListener listener){}
                public void unregister(NsdManager.RegistrationListener listener){}
                public void discover(int version,Executor executor,NsdManager.DiscoveryListener listener){browser=listener;}
                public void stopDiscovery(NsdManager.DiscoveryListener listener){}
                public void resolve(NsdServiceInfo info,NsdManager.ResolveListener listener){resolves++;listener.onServiceResolved(info);}
                public void registerUpdates(NsdServiceInfo info,Executor executor,Object callback){updates++;update=callback;}
                public void unregisterUpdates(Object callback){unregisters++;}
            }
            Framework framework=new Framework();AndroidNsd nsd=new AndroidNsd(framework,Runnable::run,sdk);
            List<DnsPort.Record> found=new ArrayList<>();List<DnsPort.Record> lost=new ArrayList<>();
            nsd.browse(new DnsPort.Admission(),new DnsPort.Boundary.Events(){
                public void found(DnsPort.Record value){found.add(value);}
                public void lost(DnsPort.Record value){lost.add(value);}
                public void error(int code){throw new AssertionError("unexpected NSD failure "+code);}
                public void failure(Throwable error){throw new AssertionError(error);}
            });
            NsdServiceInfo info=new NsdServiceInfo();info.setServiceName("compat-peer");info.setPort(12345);
            InetAddress address=InetAddress.getByAddress(new byte[]{(byte)192,0,2,42});
            if(Build.VERSION.SDK_INT>=34)info.setHostAddresses(Collections.singletonList(address));else info.setHost(address);
            framework.browser.onServiceFound(info);
            if(sdk==34&&Build.VERSION.SDK_INT>=34) {
                check(framework.resolves==0&&framework.updates==1,"API34 must register one update subscription");
                NsdManager.ServiceInfoCallback callback=(NsdManager.ServiceInfoCallback)framework.update;
                callback.onServiceUpdated(info);callback.onServiceLost();
                check(lost.size()==1&&lost.get(0).instance.equals("compat-peer"),"update loss preserves resolved peer identity");
            } else check(framework.resolves==1&&framework.updates==0,"legacy API must resolve without loading update callbacks: "+sdk);
            check(found.size()==1&&found.get(0).port==12345&&found.get(0).addresses.contains("192.0.2.42"),"version dispatch retains resolved address and port");
            nsd.stop();check(framework.unregisters==framework.updates,"Stop retires every registered update subscription");
        }
    }
}
