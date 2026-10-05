package com.unishare.sdk.internal;
import android.net.nsd.*;
import android.os.Build;
import java.net.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

@SuppressWarnings("deprecation")
final class AndroidNsd implements DnsPort.Boundary {
    private final NsdManager manager;private final Executor executor;
    private final List<NsdManager.RegistrationListener> publications=new ArrayList<>();
    private final List<NsdManager.DiscoveryListener> browsers=new ArrayList<>();
    private final List<NsdManager.ServiceInfoCallback> updates=new ArrayList<>();
    private final Set<String> updateKeys=new HashSet<>();
    private final ArrayDeque<Runnable> resolutions=new ArrayDeque<>();private boolean resolving;private long generation;
    AndroidNsd(NsdManager nsd,Executor executor){manager=nsd;this.executor=executor;}
    public synchronized void publish(DnsPort.Record r,IntConsumer result){
        long current=generation;
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceName(r.instance);info.setServiceType("_share._tcp.");info.setPort(r.port);
        for(Map.Entry<String,byte[]> item:r.txt.entrySet())info.setAttribute(item.getKey(),new String(item.getValue(),java.nio.charset.StandardCharsets.UTF_8));
        NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener(){
            public void onServiceRegistered(NsdServiceInfo i){synchronized(AndroidNsd.this){if(current!=generation){try{manager.unregisterService(this);}catch(RuntimeException ignored){}return;}}result.accept(-1);}
            public void onRegistrationFailed(NsdServiceInfo i,int code){result.accept(code);}
            public void onServiceUnregistered(NsdServiceInfo i){}
            public void onUnregistrationFailed(NsdServiceInfo i,int code){}
        };publications.add(listener);manager.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener);
    }
    static DnsPort.Record record(NsdServiceInfo info){DnsPort.Record r=new DnsPort.Record();r.instance=info.getServiceName();
        r.scope=Build.VERSION.SDK_INT>=33?(info.getNetwork()==null?"unscoped":"net:"+info.getNetwork().getNetworkHandle()):"legacy";
        r.port=info.getPort();r.txt.putAll(info.getAttributes());
        List<InetAddress> addresses=Build.VERSION.SDK_INT>=34?info.getHostAddresses():info.getHost()==null?Collections.emptyList():Collections.singletonList(info.getHost());
        for(InetAddress ip:addresses){String host=ip.getHostAddress();if(host!=null)r.addresses.add(host);}return r;
    }
    public synchronized void browse(Events events){long current=generation;
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String t){synchronized(AndroidNsd.this){if(current!=generation)try{manager.stopServiceDiscovery(this);}catch(RuntimeException ignored){}}}
            public void onStartDiscoveryFailed(String t,int code){events.error(code);}
            public void onStopDiscoveryFailed(String t,int code){events.error(code);}
            public void onDiscoveryStopped(String t){}
            public void onServiceLost(NsdServiceInfo info){events.lost(record(info));}
            public void onServiceFound(NsdServiceInfo info){synchronized(AndroidNsd.this){if(current!=generation)return;
                if(Build.VERSION.SDK_INT>=34){
                    DnsPort.Record discovered=record(info);if(!updateKeys.add(discovered.instance+"\u0000"+discovered.scope))return;
                    NsdManager.ServiceInfoCallback update=new NsdManager.ServiceInfoCallback(){
                        private DnsPort.Record last=record(info);
                        public void onServiceInfoCallbackRegistrationFailed(int code){events.error(code);}
                        public void onServiceUpdated(NsdServiceInfo resolved){last=record(resolved);events.found(last);}
                        public void onServiceLost(){events.lost(last);}
                        public void onServiceInfoCallbackUnregistered(){}
                    };updates.add(update);try{manager.registerServiceInfoCallback(info,executor,update);}catch(RuntimeException e){events.failure(e);}
                }else{
                    resolutions.add(()->{try{manager.resolveService(info,new NsdManager.ResolveListener(){
                        public void onResolveFailed(NsdServiceInfo i,int code){events.error(code);nextResolution(current);}
                        public void onServiceResolved(NsdServiceInfo resolved){if(current==generation){events.found(record(resolved));nextResolution(current);}}
                    });}catch(RuntimeException e){events.failure(e);nextResolution(current);}});if(!resolving)nextResolution(current);
                }
            }}
        };browsers.add(listener);
        if(Build.VERSION.SDK_INT>=33)manager.discoverServices("_share._tcp.",NsdManager.PROTOCOL_DNS_SD,new android.net.NetworkRequest.Builder().build(),executor,listener);
        else manager.discoverServices("_share._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);
    }
    private synchronized void nextResolution(long current){if(current!=generation)return;if(resolving){resolving=false;}if(!resolutions.isEmpty()){resolving=true;resolutions.remove().run();}}
    public synchronized void stop(){++generation;resolutions.clear();resolving=false;
        for(NsdManager.DiscoveryListener l:browsers)try{manager.stopServiceDiscovery(l);}catch(RuntimeException ignored){}
        for(NsdManager.RegistrationListener l:publications)try{manager.unregisterService(l);}catch(RuntimeException ignored){}
        if(Build.VERSION.SDK_INT>=34)for(NsdManager.ServiceInfoCallback l:updates)try{manager.unregisterServiceInfoCallback(l);}catch(RuntimeException ignored){}
        browsers.clear();publications.clear();updates.clear();updateKeys.clear();
    }
}
