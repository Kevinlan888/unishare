package com.unishare.sdk.internal;
import android.net.nsd.*;
import android.os.Build;
import java.net.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

@SuppressWarnings("deprecation")
final class AndroidNsd implements DnsPort.Boundary {
    interface Framework {
        void register(NsdServiceInfo info,NsdManager.RegistrationListener listener);
        void unregister(NsdManager.RegistrationListener listener);
        void discover(int sdk,Executor executor,NsdManager.DiscoveryListener listener);
        void stopDiscovery(NsdManager.DiscoveryListener listener);
        void resolve(NsdServiceInfo info,NsdManager.ResolveListener listener);
        void registerUpdates(NsdServiceInfo info,Executor executor,Object callback);
        void unregisterUpdates(Object callback);
    }
    private final Framework framework;private final Executor executor;private final int sdk;
    private final List<NsdManager.RegistrationListener> publications=new ArrayList<>();
    private final List<NsdManager.DiscoveryListener> browsers=new ArrayList<>();
    private final List<NsdManager.ServiceInfoCallback> updates=new ArrayList<>();
    private final Set<String> updateKeys=new HashSet<>();
    private final ArrayDeque<Resolution> resolutions=new ArrayDeque<>();private Resolution activeResolution;private long generation;
    AndroidNsd(NsdManager nsd,Executor executor){this(new Framework(){
        public void register(NsdServiceInfo i,NsdManager.RegistrationListener l){nsd.registerService(i,NsdManager.PROTOCOL_DNS_SD,l);}
        public void unregister(NsdManager.RegistrationListener l){nsd.unregisterService(l);}
        public void discover(int sdk,Executor e,NsdManager.DiscoveryListener l){if(sdk>=33)nsd.discoverServices("_share._tcp.",NsdManager.PROTOCOL_DNS_SD,new android.net.NetworkRequest.Builder().build(),e,l);else nsd.discoverServices("_share._tcp.",NsdManager.PROTOCOL_DNS_SD,l);}
        public void stopDiscovery(NsdManager.DiscoveryListener l){nsd.stopServiceDiscovery(l);}
        public void resolve(NsdServiceInfo i,NsdManager.ResolveListener l){nsd.resolveService(i,l);}
        public void registerUpdates(NsdServiceInfo i,Executor e,Object c){nsd.registerServiceInfoCallback(i,e,(NsdManager.ServiceInfoCallback)c);}
        public void unregisterUpdates(Object c){nsd.unregisterServiceInfoCallback((NsdManager.ServiceInfoCallback)c);}
    },executor,Build.VERSION.SDK_INT);}
    AndroidNsd(Framework framework,Executor executor,int sdk){this.framework=framework;this.executor=executor;this.sdk=sdk;}
    private synchronized boolean alive(long current,DnsPort.Admission admission){return current==generation&&admission.active();}
    private static void ignore(Runnable action){try{action.run();}catch(RuntimeException ignored){}}
    public void publish(DnsPort.Record r,DnsPort.Admission admission,IntConsumer result){
        final long current;synchronized(this){if(!admission.active())return;current=generation;}
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceName(r.instance);info.setServiceType("_share._tcp.");info.setPort(r.port);
        for(Map.Entry<String,byte[]> item:r.txt.entrySet())info.setAttribute(item.getKey(),new String(item.getValue(),java.nio.charset.StandardCharsets.UTF_8));
        NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener(){
            public void onServiceRegistered(NsdServiceInfo i){if(!alive(current,admission)){ignore(()->framework.unregister(this));return;}result.accept(-1);}
            public void onRegistrationFailed(NsdServiceInfo i,int code){if(alive(current,admission))result.accept(code);}
            public void onServiceUnregistered(NsdServiceInfo i){}
            public void onUnregistrationFailed(NsdServiceInfo i,int code){}
        };
        synchronized(this){if(current!=generation||!admission.active())return;publications.add(listener);}
        try{framework.register(info,listener);}finally{if(!alive(current,admission))ignore(()->framework.unregister(listener));}
    }
    static DnsPort.Record record(NsdServiceInfo info){DnsPort.Record r=new DnsPort.Record();r.instance=info.getServiceName();
        r.scope=Build.VERSION.SDK_INT>=33?(info.getNetwork()==null?"unscoped":"net:"+info.getNetwork().getNetworkHandle()):"legacy";
        r.port=info.getPort();r.txt.putAll(info.getAttributes());
        List<InetAddress> addresses=Build.VERSION.SDK_INT>=34?info.getHostAddresses():info.getHost()==null?Collections.emptyList():Collections.singletonList(info.getHost());
        for(InetAddress ip:addresses){String host=ip.getHostAddress();if(host!=null)r.addresses.add(host);}
        // Routing metadata only: neither hostname nor numeric endpoint authenticates a peer.
        String hostname=Build.VERSION.SDK_INT>=36?info.getHostname():null;
        if(hostname!=null&&!hostname.isEmpty())r.target=hostname+".local.";
        else if(!r.addresses.isEmpty())r.target=r.addresses.get(0);
        return r;
    }
    public void browse(DnsPort.Admission admission,Events events){
        final long current;synchronized(this){if(!admission.active())return;current=generation;}
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String t){if(!alive(current,admission))ignore(()->framework.stopDiscovery(this));}
            public void onStartDiscoveryFailed(String t,int code){if(alive(current,admission))events.error(code);}
            public void onStopDiscoveryFailed(String t,int code){if(alive(current,admission))events.error(code);}
            public void onDiscoveryStopped(String t){}
            public void onServiceLost(NsdServiceInfo info){if(alive(current,admission))events.lost(record(info));}
            public void onServiceFound(NsdServiceInfo info){if(sdk>=34)observe(current,admission,info,events);else enqueue(new Resolution(current,admission,info,events));}
        };
        synchronized(this){if(current!=generation||!admission.active())return;browsers.add(listener);}
        try{framework.discover(sdk,executor,listener);}finally{if(!alive(current,admission))ignore(()->framework.stopDiscovery(listener));}
    }
    private void observe(long current,DnsPort.Admission admission,NsdServiceInfo info,Events events){
        DnsPort.Record discovered=record(info);
        NsdManager.ServiceInfoCallback update=new NsdManager.ServiceInfoCallback(){
            private volatile DnsPort.Record last=discovered;
            public void onServiceInfoCallbackRegistrationFailed(int code){if(alive(current,admission))events.error(code);}
            public void onServiceUpdated(NsdServiceInfo resolved){if(alive(current,admission)){last=record(resolved);events.found(last);}}
            public void onServiceLost(){if(alive(current,admission))events.lost(last);}
            public void onServiceInfoCallbackUnregistered(){}
        };
        synchronized(this){if(current!=generation||!admission.active()||!updateKeys.add(discovered.instance+"\u0000"+discovered.scope))return;updates.add(update);}
        try{framework.registerUpdates(info,executor,update);}catch(RuntimeException e){if(alive(current,admission))events.failure(e);}
        finally{if(!alive(current,admission))ignore(()->framework.unregisterUpdates(update));}
    }
    private static final class Resolution {
        final long generation;final DnsPort.Admission admission;final NsdServiceInfo info;final Events events;
        Resolution(long g,DnsPort.Admission a,NsdServiceInfo i,Events e){generation=g;admission=a;info=i;events=e;}
    }
    private void enqueue(Resolution request){Resolution submit=null;
        synchronized(this){if(request.generation!=generation||!request.admission.active())return;resolutions.add(request);if(activeResolution==null){activeResolution=resolutions.remove();submit=activeResolution;}}
        if(submit!=null)resolve(submit);
    }
    private void finish(Resolution request){Resolution submit=null;
        synchronized(this){if(request.generation!=generation||!request.admission.active()||activeResolution!=request)return;activeResolution=null;if(!resolutions.isEmpty()){activeResolution=resolutions.remove();submit=activeResolution;}}
        if(submit!=null)resolve(submit);
    }
    private void resolve(Resolution request){
        try{framework.resolve(request.info,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo info,int code){try{if(alive(request.generation,request.admission))request.events.error(code);}finally{finish(request);}}
            public void onServiceResolved(NsdServiceInfo info){try{if(alive(request.generation,request.admission))request.events.found(record(info));}finally{finish(request);}}
        });}catch(RuntimeException e){try{if(alive(request.generation,request.admission))request.events.failure(e);}finally{finish(request);}}
    }
    public void stop(){
        List<NsdManager.DiscoveryListener> oldBrowsers;List<NsdManager.RegistrationListener> oldPublications;List<NsdManager.ServiceInfoCallback> oldUpdates;
        synchronized(this){++generation;resolutions.clear();activeResolution=null;
            oldBrowsers=new ArrayList<>(browsers);oldPublications=new ArrayList<>(publications);oldUpdates=new ArrayList<>(updates);
            browsers.clear();publications.clear();updates.clear();updateKeys.clear();}
        for(NsdManager.DiscoveryListener listener:oldBrowsers)ignore(()->framework.stopDiscovery(listener));
        for(NsdManager.RegistrationListener listener:oldPublications)ignore(()->framework.unregister(listener));
        if(sdk>=34)for(NsdManager.ServiceInfoCallback callback:oldUpdates)ignore(()->framework.unregisterUpdates(callback));
    }
}