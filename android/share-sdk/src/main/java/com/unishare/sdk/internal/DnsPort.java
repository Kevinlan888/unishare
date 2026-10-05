package com.unishare.sdk.internal;
import java.util.*;
import java.util.function.*;
final class DnsPort {
    static final class Record {
        String instance="",scope="",target="";int port;Map<String,byte[]> txt=new LinkedHashMap<>();List<String> addresses=new ArrayList<>();
    }
    interface Boundary {
        interface Events {void found(Record r);void lost(Record r);void error(int code);void failure(Throwable failure);}
        void publish(Record r,IntConsumer result)throws Exception;
        void browse(Events events)throws Exception;
        void stop();
    }
    private final Boundary boundary;private final BiConsumer<Long,byte[]> sink;private long generation;private final Set<Long> pending=new HashSet<>();
    DnsPort(Boundary b,BiConsumer<Long,byte[]> sink){boundary=b;this.sink=sink;}
    synchronized void publish(long id,Record r)throws Exception{
        if(r.port<=0||r.port>65535)throw new IllegalArgumentException("bound port required");long current=generation;pending.add(id);
        try{boundary.publish(r,code->{synchronized(this){if(current!=generation||!pending.remove(id))return;
            sink.accept(id,code==-1?Packet.success(new byte[0]):Packet.failure(new PortFailure(code==7?1:2,"NSD registration failed",(long)code),2));}});}
        catch(Exception e){pending.remove(id);throw e;}
    }
    synchronized void browse(long id)throws Exception{
        long current=generation;boundary.browse(new Boundary.Events(){
            public void found(Record r){synchronized(DnsPort.this){if(current!=generation)return;try{sink.accept(id,Packet.success(record(r)));}catch(Exception e){sink.accept(id,Packet.failure(e,2));}}}
            public void lost(Record r){synchronized(DnsPort.this){if(current!=generation||r.scope.equals("legacy")||r.scope.equals("unscoped"))return;try{sink.accept(id,Packet.success(Packet.make(d->{d.writeInt(1);Packet.text(d,r.instance);Packet.text(d,r.scope);})));}catch(Exception e){sink.accept(id,Packet.failure(e,2));}}}
            public void error(int code){synchronized(DnsPort.this){if(current==generation)sink.accept(id,Packet.failure(new PortFailure(code==7?1:2,"NSD browse/resolve failed",(long)code),2));}}
            public void failure(Throwable failure){synchronized(DnsPort.this){if(current==generation)sink.accept(id,Packet.failure(failure,2));}}
        });
    }
    static byte[] record(Record r)throws Exception{return Packet.make(d->{d.writeInt(0);Packet.text(d,r.instance);Packet.text(d,r.scope);Packet.text(d,r.target);d.writeInt(r.port);
        d.writeInt(r.txt.size());for(Map.Entry<String,byte[]> e:r.txt.entrySet()){Packet.text(d,e.getKey());Packet.bytes(d,e.getValue());}
        d.writeInt(r.addresses.size());for(String address:r.addresses)Packet.text(d,address);});}
    synchronized void stop(){++generation;boundary.stop();for(long id:pending)sink.accept(id,Packet.failure(new PortFailure(8,"NSD stopped",null),2));pending.clear();}
}
