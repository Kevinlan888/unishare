package com.unishare.sdk.internal;
import java.util.*;
import java.util.function.*;
final class DnsPort {
    static final class Record {
        String instance="",scope="",target="";int port;Map<String,byte[]> txt=new LinkedHashMap<>();List<String> addresses=new ArrayList<>();
    }
    static final class Admission {
        private final java.util.concurrent.atomic.AtomicBoolean retired=new java.util.concurrent.atomic.AtomicBoolean();
        boolean active(){return !retired.get();}
        void retire(){retired.set(true);}
    }
    interface Boundary {
        interface Events {void found(Record r);void lost(Record r);void error(int code);void failure(Throwable failure);}
        void publish(Record r,Admission admission,IntConsumer result)throws Exception;
        void browse(Admission admission,Events events)throws Exception;
        void stop();
    }
    private final Boundary boundary;private final BiConsumer<Long,byte[]> sink;private long generation;private int stopsInProgress;private Admission admission=new Admission();private final Set<Long> pending=new HashSet<>();
    DnsPort(Boundary b,BiConsumer<Long,byte[]> sink){boundary=b;this.sink=sink;}
    private synchronized boolean alive(long current){return current==generation;}
    void publish(long id,Record r)throws Exception{
        if(r.port<=0||r.port>65535)throw new IllegalArgumentException("bound port required");final long current;final Admission submitted;
        synchronized(this){if(!admission.active())throw new PortFailure(8,"NSD Stop in progress",null);current=generation;submitted=admission;pending.add(id);}
        try{boundary.publish(r,submitted,code->{boolean deliver;synchronized(this){deliver=current==generation&&pending.remove(id);}
            if(deliver)sink.accept(id,code==-1?Packet.success(new byte[0]):Packet.failure(new PortFailure(code==7?1:2,"NSD registration failed",(long)code),2));});}
        catch(Exception e){synchronized(this){pending.remove(id);}throw e;}
    }
    void browse(long id)throws Exception{
        final long current;final Admission submitted;synchronized(this){if(!admission.active())throw new PortFailure(8,"NSD Stop in progress",null);current=generation;submitted=admission;}
        boundary.browse(submitted,new Boundary.Events(){
            public void found(Record r){if(!alive(current))return;try{sink.accept(id,Packet.success(record(r)));}catch(Exception e){sink.accept(id,Packet.failure(e,2));}}
            public void lost(Record r){if(!alive(current)||r.scope.equals("legacy")||r.scope.equals("unscoped"))return;try{sink.accept(id,Packet.success(Packet.make(d->{d.writeInt(1);Packet.text(d,r.instance);Packet.text(d,r.scope);})));}catch(Exception e){sink.accept(id,Packet.failure(e,2));}}
            public void error(int code){if(alive(current))sink.accept(id,Packet.failure(new PortFailure(code==7?1:2,"NSD browse/resolve failed",(long)code),2));}
            public void failure(Throwable failure){if(alive(current))sink.accept(id,Packet.failure(failure,2));}
        });
    }
    static byte[] record(Record r)throws Exception{return Packet.make(d->{d.writeInt(0);Packet.text(d,r.instance);Packet.text(d,r.scope);Packet.text(d,r.target);d.writeInt(r.port);
        d.writeInt(r.txt.size());for(Map.Entry<String,byte[]> e:r.txt.entrySet()){Packet.text(d,e.getKey());Packet.bytes(d,e.getValue());}
        d.writeInt(r.addresses.size());for(String address:r.addresses)Packet.text(d,address);});}
    void stop(){List<Long> cancelled;synchronized(this){++generation;++stopsInProgress;admission.retire();cancelled=new ArrayList<>(pending);pending.clear();}
        try{boundary.stop();}finally{synchronized(this){if(--stopsInProgress==0)admission=new Admission();}for(long id:cancelled)sink.accept(id,Packet.failure(new PortFailure(8,"NSD stopped",null),2));}
    }
}
