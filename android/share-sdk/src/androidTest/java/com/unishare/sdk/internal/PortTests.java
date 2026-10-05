package com.unishare.sdk.internal;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.util.*;

/** Standalone instrumentation runner: no downloaded test framework or test-only production API. */
@SuppressWarnings("deprecation")
public final class PortTests extends Instrumentation {
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    static void check(boolean yes, String why) { if (!yes) throw new AssertionError(why); }
    static byte[] identity() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        d.write(new byte[16]); d.writeInt(3); d.write(new byte[]{1,2,3}); d.writeInt(2); d.write(new byte[]{4,5}); return b.toByteArray();
    }
    void identityRestart() throws Exception {
        String name="test-"+UUID.randomUUID(); IdentityStore first=new IdentityStore(getTargetContext(),name);
        check(Arrays.equals(first.transact(1,new byte[0]),new byte[]{0}),"missing identity must be distinguished from corruption");
        first.transact(2,identity()); IdentityStore restarted=new IdentityStore(getTargetContext(),name);
        byte[] loaded=restarted.transact(1,new byte[0]);
        check(loaded.length==identity().length+1 && loaded[0]==1,"identity must survive restart");
        check(Arrays.equals(Arrays.copyOfRange(loaded,1,loaded.length),identity()),"DER identity changes on restart");
        boolean duplicate=false; try { restarted.transact(2,identity()); } catch(Exception e) { duplicate=true; }
        check(duplicate,"create-once identity must reject overwrite");
    }
    void contentStreams() throws Exception {
        ContentStreams streams=new ContentStreams(getTargetContext().getContentResolver());
        String uri="content://"+getTargetContext().getPackageName()+".probe/staging-"+UUID.randomUUID();
        long target=streams.open(uri,true);streams.write(target,new byte[]{9,8,7});streams.close(target,false);
        long source=streams.open(uri,false);
        check(Arrays.equals(streams.read(source,2),new byte[]{9,8}),"URI reads must be bounded and preserve data");
        check(Arrays.equals(streams.read(source,2),new byte[]{7}),"URI stream position");
        check(streams.read(source,2).length==0,"URI EOF");streams.close(source,false);
        long aborted=streams.open(uri,true);streams.write(aborted,new byte[]{6});streams.close(aborted,true);
        check(!new File(getTargetContext().getCacheDir(),android.net.Uri.parse(uri).getLastPathSegment()).exists(),"abort must delete staged destination");
        boolean failure=false;try{streams.write(aborted,new byte[]{1});}catch(Exception e){failure=true;}
        check(failure,"closed destination cannot report successful write");
        boolean pipeRejected=false;try{streams.open("content://"+getTargetContext().getPackageName()+".probe/pipe",true);}catch(java.io.IOException e){pipeRejected=true;}
        check(pipeRejected,"commit-on-close destination pipes cannot report durable Write success");
    }
    void dnsOwnership() throws Exception {
        class Fake implements DnsPort.Boundary {
            Events events;int advertised;int registration=-1;
            public void publish(DnsPort.Record r,DnsPort.Admission admission,java.util.function.IntConsumer cb){advertised=r.port;cb.accept(registration);}
            public void browse(DnsPort.Admission admission,Events e){events=e;} public void stop(){}
        }
        Fake boundary=new Fake();List<byte[]> found=new ArrayList<>();DnsPort dns=new DnsPort(boundary,(id,bytes)->found.add(bytes));
        DnsPort.Record service=new DnsPort.Record();service.instance="peer";service.scope="net:42";service.port=12345;service.addresses.add("192.0.2.1");
        dns.browse(1);check(boundary.events!=null,"browse must submit framework registration");
        boundary.events.found(service);boundary.events.lost(service);check(found.size()==2,"found/removal must reach consumer");
        java.io.DataInputStream removal=Packet.input(found.get(1));check(removal.readInt()==0,"DNS event error");removal.readLong();Packet.text(removal);
        check(removal.readInt()==1 && Packet.text(removal).equals("peer") && Packet.text(removal).equals("net:42"),"removal must keep original network scope");
        dns.stop();boundary.events.found(service);check(found.size()==2,"late callbacks after stop must be ignored");
        dns.publish(2,service);check(boundary.advertised==12345,"publish actual bound port");
        Fake legacy=new Fake();List<byte[]> old=new ArrayList<>();DnsPort aggregate=new DnsPort(legacy,(id,b)->old.add(b));aggregate.browse(3);
        service.scope="legacy";legacy.events.found(service);legacy.events.lost(service);check(old.size()==1,"ambiguous legacy loss cannot remove confirmed device");aggregate.stop();
        boundary.events.error(6); // stopped generation ignores errors too
        Fake errors=new Fake();List<byte[]> failures=new ArrayList<>();DnsPort errorPort=new DnsPort(errors,(id,b)->failures.add(b));errorPort.browse(4);
        errors.events.error(6);java.io.DataInputStream bad=Packet.input(failures.get(0));check(bad.readInt()==2 && bad.readLong()==6,"NSD bad parameters is DiscoveryFailed with raw6");
        errors.events.error(7);bad=Packet.input(failures.get(1));check(bad.readInt()==1 && bad.readLong()==7,"NSD permission denied retains raw7");errorPort.stop();
        errors.registration=0;errorPort.publish(5,service);bad=Packet.input(failures.get(2));check(bad.readInt()==2&&bad.readLong()==0,"native NSD internal error0 cannot be success");
    }
    static final class CatalogPorts {
        long nativeKey;final DnsPort dns;
        CatalogPorts(android.net.nsd.NsdServiceInfo info){dns=new DnsPort(new DnsPort.Boundary(){
            public void publish(DnsPort.Record r,DnsPort.Admission admission,java.util.function.IntConsumer c){}
            public void browse(DnsPort.Admission admission,Events e){e.found(AndroidNsd.record(info));}public void stop(){}
        },(id,bytes)->NativeBridge.complete(nativeKey,id,bytes));}
        byte[] call(int command,byte[] data){if(command==42)dns.stop();return Packet.success(new byte[0]);}
        void start(int command,long id,byte[] data){try{dns.browse(id);}catch(Exception e){NativeBridge.complete(nativeKey,id,Packet.failure(e,2));}}
        void shutdown(){dns.stop();}
    }
    void resolvedCatalog()throws Exception{
        for(boolean ipv6:new boolean[]{false,true}){
            android.net.nsd.NsdServiceInfo info=new android.net.nsd.NsdServiceInfo();info.setServiceName("resolved-peer");info.setPort(12345);
            info.setAttribute("id","12345678-1234-4234-8234-123456789abc");info.setAttribute("name","Resolved peer");info.setAttribute("platform","android");info.setAttribute("pv","1.0");
            byte[] ip=new byte[16];ip[0]=(byte)0xfe;ip[1]=(byte)0x80;ip[15]=1;
            java.net.InetAddress address=ipv6?java.net.Inet6Address.getByAddress(null,ip,7):java.net.InetAddress.getByAddress(new byte[]{(byte)192,0,2,42});
            if(android.os.Build.VERSION.SDK_INT>=34)info.setHostAddresses(Collections.singletonList(address));else info.setHost(address);
            String result=NativeProbe.catalog(new CatalogPorts(info));check(result.startsWith("PASS"),"resolved record must produce catalog Online: "+result);
            DnsPort.Record record=AndroidNsd.record(info);check(record.addresses.contains(address.getHostAddress()),"resolved numeric address/scope must survive adapter");
            if(android.os.Build.VERSION.SDK_INT<36)check(record.target.equals(address.getHostAddress()),"older NSD target is resolved endpoint metadata");
        }
    }
    void dnsStopDuringSubmissionFailure()throws Exception{
        for(String mode:new String[]{"updates","resolve","publish","browse"}){
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            class Framework implements AndroidNsd.Framework {
                android.net.nsd.NsdManager.DiscoveryListener discovery;
                void pause(){entered.countDown();try{check(release.await(5,java.util.concurrent.TimeUnit.SECONDS),"test submission release");}catch(InterruptedException e){throw new AssertionError(e);}throw new SecurityException("submission denied");}
                public void register(android.net.nsd.NsdServiceInfo i,android.net.nsd.NsdManager.RegistrationListener l){pause();}
                public void unregister(android.net.nsd.NsdManager.RegistrationListener l){}
                public void discover(int sdk,java.util.concurrent.Executor e,android.net.nsd.NsdManager.DiscoveryListener l){discovery=l;if(mode.equals("browse"))pause();}
                public void stopDiscovery(android.net.nsd.NsdManager.DiscoveryListener l){l.onStopDiscoveryFailed("_share._tcp.",7);}
                public void resolve(android.net.nsd.NsdServiceInfo i,android.net.nsd.NsdManager.ResolveListener l){pause();}
                public void registerUpdates(android.net.nsd.NsdServiceInfo i,java.util.concurrent.Executor e,Object c){pause();}
                public void unregisterUpdates(Object c){}
            }
            Framework framework=new Framework();AndroidNsd nsd=new AndroidNsd(framework,Runnable::run,mode.equals("resolve")?26:34);
            java.util.concurrent.ConcurrentLinkedQueue<byte[]> emitted=new java.util.concurrent.ConcurrentLinkedQueue<>();DnsPort dns=new DnsPort(nsd,(id,b)->emitted.add(b));
            if(mode.equals("updates")||mode.equals("resolve"))dns.browse(1);
            Thread submit=new Thread(()->{try{if(mode.equals("publish")){DnsPort.Record r=new DnsPort.Record();r.instance="peer";r.port=12345;dns.publish(2,r);}
                else if(mode.equals("browse"))dns.browse(1);
                else{android.net.nsd.NsdServiceInfo i=new android.net.nsd.NsdServiceInfo();i.setServiceName("peer");framework.discovery.onServiceFound(i);}
            }catch(Exception expected){}});submit.setDaemon(true);submit.start();
            check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"framework submission must be paused");
            Thread stop=new Thread(dns::stop);stop.setDaemon(true);stop.start();
            boolean stopped;try{stop.join(700);stopped=!stop.isAlive();}finally{release.countDown();}
            submit.join(1000);stop.join(1000);
            check(stopped,"Stop must finish while "+mode+" submission is paused, without nested monitor ordering");
            check(!submit.isAlive()&&!stop.isAlive(),"submission failure and Stop must both retire");
            check(emitted.size()==(mode.equals("publish")?1:0),"retired failure cannot deliver late callback: "+mode);
        }
    }
    void dnsStopBeforeBoundaryAdmission()throws Exception{
        for(boolean publish:new boolean[]{true,false}){
            Set<Object> live=java.util.concurrent.ConcurrentHashMap.newKeySet();java.util.concurrent.atomic.AtomicInteger submitted=new java.util.concurrent.atomic.AtomicInteger();
            class Framework implements AndroidNsd.Framework {
                public void register(android.net.nsd.NsdServiceInfo i,android.net.nsd.NsdManager.RegistrationListener l){submitted.incrementAndGet();live.add(l);l.onServiceRegistered(i);}
                public void unregister(android.net.nsd.NsdManager.RegistrationListener l){live.remove(l);}
                public void discover(int sdk,java.util.concurrent.Executor e,android.net.nsd.NsdManager.DiscoveryListener l){submitted.incrementAndGet();live.add(l);l.onDiscoveryStarted("_share._tcp.");}
                public void stopDiscovery(android.net.nsd.NsdManager.DiscoveryListener l){live.remove(l);}
                public void resolve(android.net.nsd.NsdServiceInfo i,android.net.nsd.NsdManager.ResolveListener l){}
                public void registerUpdates(android.net.nsd.NsdServiceInfo i,java.util.concurrent.Executor e,Object c){}
                public void unregisterUpdates(Object c){}
            }
            AndroidNsd nsd=new AndroidNsd(new Framework(),Runnable::run,34);
            java.util.concurrent.CountDownLatch entering=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            DnsPort.Boundary paused=new DnsPort.Boundary(){
                void pause()throws Exception{entering.countDown();check(release.await(2,java.util.concurrent.TimeUnit.SECONDS),"admission release");}
                public void publish(DnsPort.Record r,DnsPort.Admission admission,java.util.function.IntConsumer c)throws Exception{pause();nsd.publish(r,admission,c);}
                public void browse(DnsPort.Admission admission,Events e)throws Exception{pause();nsd.browse(admission,e);}
                public void stop(){nsd.stop();}
            };
            java.util.concurrent.ConcurrentLinkedQueue<byte[]> emitted=new java.util.concurrent.ConcurrentLinkedQueue<>();DnsPort dns=new DnsPort(paused,(id,b)->emitted.add(b));
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            Thread submit=new Thread(()->{try{if(publish){DnsPort.Record r=new DnsPort.Record();r.instance="peer";r.port=12345;dns.publish(10,r);}else dns.browse(11);}catch(Throwable e){failure.set(e);}});submit.setDaemon(true);submit.start();
            check(entering.await(2,java.util.concurrent.TimeUnit.SECONDS),"pause before AndroidNsd generation capture");
            try{dns.stop();release.countDown();submit.join(1000);check(!submit.isAlive()&&failure.get()==null,"retired admission must finish cleanly");
                check(live.isEmpty(),"retired "+(publish?"publish":"browse")+" cannot leave registration live after Stop-before-admission");
                check(submitted.get()==0,"retired pre-boundary admission never submits to framework");
                check(emitted.size()==(publish?1:0),"retired admission preserves one-shot cancellation");
            }finally{release.countDown();nsd.stop();}
            if(publish){DnsPort.Record r=new DnsPort.Record();r.instance="new-peer";r.port=12345;dns.publish(12,r);}else dns.browse(13);
            check(live.size()==1,"new-generation admission still registers after restart");dns.stop();check(live.isEmpty(),"restart cleanup");
        }
    }
    void dnsStopKeepsAdmissionRetiredUntilCleanup()throws Exception{
        java.util.concurrent.CountDownLatch firstCleaning=new java.util.concurrent.CountDownLatch(1),bothCleaning=new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch releaseFirst=new java.util.concurrent.CountDownLatch(1),releaseSecond=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger submissions=new java.util.concurrent.atomic.AtomicInteger(),stops=new java.util.concurrent.atomic.AtomicInteger();
        DnsPort dns=new DnsPort(new DnsPort.Boundary(){
            public void publish(DnsPort.Record r,DnsPort.Admission a,java.util.function.IntConsumer c){submissions.incrementAndGet();c.accept(-1);}
            public void browse(DnsPort.Admission a,Events e){submissions.incrementAndGet();}
            public void stop(){int which=stops.incrementAndGet();if(which>2)return;firstCleaning.countDown();bothCleaning.countDown();try{check((which==1?releaseFirst:releaseSecond).await(3,java.util.concurrent.TimeUnit.SECONDS),"cleanup release");}catch(InterruptedException e){throw new AssertionError(e);}}
        },(id,b)->{});
        Thread first=new Thread(dns::stop),second=new Thread(dns::stop);first.setDaemon(true);second.setDaemon(true);first.start();
        check(firstCleaning.await(2,java.util.concurrent.TimeUnit.SECONDS),"first Stop cleanup paused");second.start();check(bothCleaning.await(2,java.util.concurrent.TimeUnit.SECONDS),"both Stop cleanups paused");
        try{for(boolean publish:new boolean[]{true,false}){boolean cancelled=false;try{if(publish){DnsPort.Record r=new DnsPort.Record();r.port=12345;dns.publish(20,r);}else dns.browse(21);}catch(PortFailure e){cancelled=e.code==8;}
            check(cancelled,"admission during active Stop must return Cancelled");}check(submissions.get()==0,"cleanup cannot race newly submitted registrations");
            releaseFirst.countDown();first.join(1000);check(!first.isAlive()&&second.isAlive(),"first cleanup completes while second remains paused");
            boolean cancelled=false;try{dns.browse(23);}catch(PortFailure e){cancelled=e.code==8;}check(cancelled,"first completed Stop cannot reopen admission while another cleanup remains");
        }finally{releaseFirst.countDown();releaseSecond.countDown();first.join(1000);second.join(1000);}
        check(!first.isAlive()&&!second.isAlive(),"both Stop cleanups complete");dns.browse(22);check(submissions.get()==1,"restart admission reopens only after all Stop cleanup");dns.stop();
    }
    void clipboardAndPermission() throws Exception {
        class Fake implements ClipboardPort.Boundary {android.content.ClipData data;public android.content.ClipData get(){return data;}public void set(android.content.ClipData d){data=d;}}
        Fake boundary=new Fake();ClipboardPort clipboard=new ClipboardPort(getTargetContext(),boundary);
        byte[] text=Packet.make(d->{d.writeInt(1);Packet.text(d,"你好\u0000world");Packet.text(d,"");Packet.bytes(d,new byte[0]);});
        clipboard.write(text);check(Arrays.equals(clipboard.read(),text),"explicit clipboard UTF-8 text roundtrip");
        boundary.data=android.content.ClipData.newIntent("unsupported",new android.content.Intent("test"));
        boolean unsupported=false;try{clipboard.read();}catch(PortFailure e){unsupported=e.code==13;}check(unsupported,"unsupported clipboard must be InvalidInput");
        class Policy implements PermissionPolicy.Boundary {boolean front=true,grant=false;int sdk=37,target=37;public boolean foreground(){return front;}public int sdk(){return sdk;}public int target(){return target;}public boolean granted(){return grant;}}
        Policy policy=new Policy();boolean denied=false;try{PermissionPolicy.check(policy);}catch(PortFailure e){denied=e.code==1;}check(denied,"target37 local-network denial");
        policy.target=36;PermissionPolicy.check(policy);policy.front=false;denied=false;try{PermissionPolicy.check(policy);}catch(PortFailure e){denied=e.code==1;}check(denied,"background lifecycle must reject network Start as PermissionDenied");
    }
    void nativeBridge()throws Exception{try(NativeBridge bridge=new NativeBridge(getTargetContext(),"native-"+UUID.randomUUID())){
        check(bridge.platformHandle()!=0,"JNI must register a retained C ABI platform");
        bridge.setForeground(true);
        boolean invalid=false;try{bridge.openStream("file:///etc/hosts",false);}catch(NativePortException e){invalid=e.code==13;}check(invalid,"JNI must retain core error code");
        String uri="content://"+getTargetContext().getPackageName()+".probe/registered-"+UUID.randomUUID();
        long stream=bridge.openStream(uri,true);check(stream!=0,"JNI must retain URI stream token");NativeBridge.releaseStream(stream);
        invalid=false;try{NativeBridge.releaseStream(stream);}catch(NativePortException e){invalid=e.code==13;}check(invalid,"released stream token cannot be reused");
    }FrameworkPorts framework=new FrameworkPorts(getTargetContext(),"probe-"+UUID.randomUUID());framework.setForeground(true);
        String uri="content://"+getTargetContext().getPackageName()+".probe/native-"+UUID.randomUUID();String status=NativeProbe.run(framework,uri);check(status.startsWith("PASS"),status);
        NativeBridge.complete(framework.nativeKey,Long.MAX_VALUE,Packet.success(new byte[0])); // a late retired completion has no native owner
    }
    void identityCorruptionAndImages()throws Exception {
        String name="corrupt-"+UUID.randomUUID();IdentityStore store=new IdentityStore(getTargetContext(),name);store.transact(2,identity());
        File dbFile=new File(new File(getTargetContext().getNoBackupFilesDir(),"unishare-"+name),"records.db");
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.toString(),null,0)){
            db.execSQL("UPDATE records SET v=? WHERE k='identity'",new Object[]{new byte[32]});
        }
        boolean corrupt=false;try{new IdentityStore(getTargetContext(),name).transact(1,new byte[0]);}catch(Exception e){corrupt=true;}check(corrupt,"corrupt identity cannot silently become absent");
        class Fake implements ClipboardPort.Boundary {android.content.ClipData clip;public android.content.ClipData get(){return clip;}public void set(android.content.ClipData c){clip=c;}}
        Fake boundary=new Fake();ClipboardPort clipboard=new ClipboardPort(getTargetContext(),boundary);
        byte[] image=Packet.make(d->{d.writeInt(2);Packet.text(d,"");Packet.text(d,"image/png");Packet.bytes(d,new byte[]{(byte)137,80,78,71});});
        clipboard.write(image);check(Arrays.equals(clipboard.read(),image),"explicit image clipboard URI roundtrip");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            runCase("identityRestart",1,this::identityRestart);runCase("contentStreams",2,this::contentStreams);runCase("dnsOwnership",3,this::dnsOwnership);
            runCase("clipboardAndPermission",4,this::clipboardAndPermission);runCase("identityCorruptionAndImages",5,this::identityCorruptionAndImages);runCase("nativeBridge",6,this::nativeBridge);runCase("resolvedCatalog",7,this::resolvedCatalog);runCase("dnsStopDuringSubmissionFailure",8,this::dnsStopDuringSubmissionFailure);runCase("dnsStopBeforeBoundaryAdmission",9,this::dnsStopBeforeBoundaryAdmission);runCase("dnsStopKeepsAdmissionRetiredUntilCleanup",10,this::dnsStopKeepsAdmissionRetiredUntilCleanup);
            result.putString("stream","PASS 10 internal adapter cases\n");finish(-1,result);
        }
        catch(Throwable e) { result.putString("stream","FAIL "+android.util.Log.getStackTraceString(e)); finish(1,result); }
    }
    interface Case {void run()throws Exception;}
    void runCase(String name,int index,Case test)throws Exception{
        Bundle status=new Bundle();status.putString("id","UniSharePortTests");status.putString("class",getClass().getName());status.putString("test",name);
        status.putInt("current",index);status.putInt("numtests",10);sendStatus(1,status);
        try{test.run();sendStatus(0,status);}catch(Throwable e){status.putString("stack",android.util.Log.getStackTraceString(e));sendStatus(-2,status);throw e;}
    }
}
