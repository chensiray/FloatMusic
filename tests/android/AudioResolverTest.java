package org.floatmusic.player;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

// Runs the same resolver shipped in the APK against loopback HTTP fixtures.
public final class AudioResolverTest {
    static int gdStatus=200, legacyStatus=200, metingStatus=200;
    static boolean badAudio=false, emptyArray=false;
    static final AtomicInteger gdCalls=new AtomicInteger(), legacyCalls=new AtomicInteger();
    static volatile int vkeysStatus=200,vkeysCode=0,customStatus=200;
    static volatile boolean qqEmpty=false,vkeysBadAudio=false,vkeysMissingCode=false,kwOk=true,kwBadOrigin=false;
    static volatile boolean vkeysStringCode=false,kwStringOk=false;
    static volatile String qqMid="101",kwId="9001",kwMode="wrapped";
    static volatile int vkeysDelay=0,netDelay=0;
    static volatile CountDownLatch vkeysStarted=new CountDownLatch(0);
    static final AtomicInteger vkeysCalls=new AtomicInteger(),qqMetingCalls=new AtomicInteger(),customCalls=new AtomicInteger();
    static final AtomicInteger kwCalls=new AtomicInteger(),originCalls=new AtomicInteger(),proxyCalls=new AtomicInteger();
    static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    static AudioResolver multisourceResolver(String base) throws Exception {
        return new AudioResolver(base+"/gd",base+"/legacy",base+"/meting",base,base+"/api",600,2000);
    }
    static AudioResolver.Result resolveSource(AudioResolver resolver,String source,String id,String quality,String api) throws Exception {
        return resolveSource(resolver,source,id,quality,api,Collections.emptySet(),()->true);
    }
    static AudioResolver.Result resolveSource(AudioResolver resolver,String source,String id,String quality,String api,Set<String> excluded,java.util.function.BooleanSupplier active) throws Exception {
        return resolver.resolve(source,id,quality,api,excluded,active);
    }
    interface Operation { void run() throws Exception; }
    static String failure(Operation operation) throws Exception {
        try {operation.run();throw new AssertionError("operation must fail");}
        catch(IOException expected) {return expected.getMessage();}
    }
    static String redirect(String current,String next,boolean qq) throws Exception {
        return AudioResolver.redirectAddress(current,next,qq);
    }
    static int networkCalls() {return gdCalls.get()+legacyCalls.get()+vkeysCalls.get()+qqMetingCalls.get()+kwCalls.get()+customCalls.get();}
    static void delay(int milliseconds) {try {if(milliseconds>0)Thread.sleep(milliseconds);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
    public static void main(String[] args) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService httpWorkers=Executors.newCachedThreadPool(); server.setExecutor(httpWorkers);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path=exchange.getRequestURI().getPath(), query=exchange.getRequestURI().getRawQuery();
            check(exchange.getRequestHeaders().getFirst("Cookie")==null&&exchange.getRequestHeaders().getFirst("Authorization")==null,"provider requests must remain anonymous");
            int status=200; String body="",type="application/json";
            switch(path) {
                case "/gd": gdCalls.incrementAndGet(); status=gdStatus;
                    check(query.contains("br=999"),"Hi-Res must request GD br=999");
                    delay(netDelay);
                    body="{\"url\":\""+base+(badAudio?"/html":"/audio?sig=keep%2Bthis")+"\",\"br\":740,\"size\":123}";break;
                case "/legacy": legacyCalls.incrementAndGet(); status=legacyStatus;type="text/plain";body=base+"/audio";delay(netDelay);break;
                case "/meting": status=metingStatus;
                    if(query.contains("type=url")) {status=302;exchange.getResponseHeaders().set("Location",base+"/audio?sig=signed%2Bvalue");}
                    else if(query.contains("server=tencent")) {
                        qqMetingCalls.incrementAndGet();check(query.equals("server=tencent&type=song&id="+qqMid),"QQ backup must use the same MID and platform");
                        status=200;body=qqEmpty?"[]":"[{\"name\":\"QQ test\",\"artist\":\"Artist\",\"url\":\""+base+"/meting?type=url&id="+qqMid+"\",\"pic\":\"\",\"lrc\":\"\"}]";
                    }
                    else body=emptyArray?"[]":"[{\"name\":\"Test\",\"artist\":\"Test\",\"url\":\""+base+"/meting?type=url&id=101\",\"pic\":\"\",\"lrc\":\"\"}]";break;
                case "/audio":type="audio/flac";body="fLaC0123456789abcdefghijklmnopqrstuvwxyz";
                    check("bytes=0-63".equals(exchange.getRequestHeaders().getFirst("Range")),"audio probe must be bounded");break;
                case "/html":type="text/html";body="<html>Expired link</html>";break;
                case "/custom/song/url/v1":customCalls.incrementAndGet();status=customStatus;body="{\"code\":200,\"data\":[{\"url\":\""+base+"/audio\"}]}";break;
                case "/music/tencent/song/link":
                    vkeysCalls.incrementAndGet();vkeysStarted.countDown();status=vkeysStatus;
                    check(query.equals("mid="+qqMid+"&quality=4&type=0"),"QQ must request the same MID at ordinary quality");
                    delay(vkeysDelay);
                    body="{"+(vkeysMissingCode?"":"\"code\":"+(vkeysStringCode?"\"0\"":Integer.toString(vkeysCode))+",")+"\"message\":\"signed-token=secret\",\"data\":{\"url\":\""+base+(vkeysBadAudio?"/html":"/audio?qq=signed%2Bvalue")+"\",\"kbps\":\"128kbps\"}}";break;
                case "/api":
                    kwCalls.incrementAndGet();check(query.equals("server=kuwo&type=url&id="+kwId+"&json=1"),"Kuwo must use ordinary URL JSON for the same id");
                    String origin=base+"/origin?sig=keep%2Bthis&x=1%2F2";
                    String supplied=kwMode.equals("direct")?origin:base+(kwMode.equals("foreign")?"/untrusted/proxy":"/proxy")+"?url="+URLEncoder.encode(origin,"UTF-8");
                    if(kwMode.equals("unsafe"))supplied=base+"/proxy?url="+URLEncoder.encode("http://user:secret@127.0.0.1/audio","UTF-8");
                    body="{\"ok\":"+(kwStringOk?"\"true\"":Boolean.toString(kwOk))+",\"url\":\""+supplied+"\",\"id\":\""+kwId+"\"}";break;
                case "/origin":
                    originCalls.incrementAndGet();type=kwBadOrigin?"text/html":"audio/flac";body=kwBadOrigin?"<html>expired</html>":"fLaC0123456789abcdefghijklmnopqrstuvwxyz";
                    check(query.equals("sig=keep%2Bthis&x=1%2F2"),"Kuwo origin signed parameters must survive exactly once decoding");
                    check("bytes=0-63".equals(exchange.getRequestHeaders().getFirst("Range")),"Kuwo origin probe must be bounded");break;
                case "/proxy":proxyCalls.incrementAndGet();type="audio/flac";body="fLaC0123456789abcdefghijklmnopqrstuvwxyz";break;
                case "/untrusted/proxy":type="text/html";body="<html>not an approved wrapper</html>";break;
                default:status=404;
            }
            if(status>=400)body="unavailable";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",type);
            exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
            if(bytes.length>0)exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            AudioResolver resolver=new AudioResolver(base+"/gd",base+"/legacy",base+"/meting",600,2000);
            AudioResolver.Result result=resolver.resolve("101","hires","",Collections.emptySet(),()->true);
            check(result.sourceId.equals("gd"),"GD should be the default source");
            check(result.address.endsWith("sig=keep%2Bthis"),"signed URL encoding must survive");
            check(result.bitrate==740,"returned quality must be distinct from requested Hi-Res");
            System.out.println("PASS GD quality and signed address");
            badAudio=true;legacyStatus=523;
            result=resolver.resolve("101","hires","",Collections.emptySet(),()->true);
            check(result.sourceId.equals("injahow"),"invalid CDN content and HTTP 523 must use the backup");
            check(result.address.endsWith("sig=signed%2Bvalue"),"Meting redirect must preserve query");
            System.out.println("PASS invalid audio / 523 / Meting redirect fallback");
            int before=gdCalls.get();
            result=resolver.resolve("101","hires",base+"/custom",Collections.emptySet(),()->true);
            check(result.sourceId.equals("custom")&&gdCalls.get()==before,"custom service must remain selected");
            System.out.println("PASS custom API remains selected");
            gdStatus=503;metingStatus=502; resolver.clearFailures();
            boolean failed=false;
            try{resolver.resolve("101","hires","",Collections.emptySet(),()->true);}catch(IOException e){failed=e.getMessage().contains("503")&&e.getMessage().contains("523")&&e.getMessage().contains("502");}
            check(failed,"failure must describe attempted source HTTP errors");
            before=gdCalls.get(); int legacyBefore=legacyCalls.get();
            try{resolver.resolve("101","hires","",Collections.emptySet(),()->true);}catch(IOException expected){}
            check(gdCalls.get()==before&&legacyCalls.get()==legacyBefore,"outage cooldown must avoid repeated requests");
            System.out.println("PASS exhausted sources and outage cooldown");
            before=gdCalls.get();failed=false;
            try{resolver.resolve("101","hires","",Collections.emptySet(),()->false);}catch(IOException e){failed=true;}
            check(failed&&gdCalls.get()==before,"cancelled request must not issue network requests");
            System.out.println("PASS cancellation");
            gdStatus=200;legacyStatus=523;metingStatus=200;emptyArray=true;resolver.clearFailures();
            failed=false;try{resolver.resolve("101","hires","",Collections.emptySet(),()->true);}catch(IOException e){failed=true;}
            check(failed,"empty Meting response must fail safely");
            System.out.println("PASS empty Meting response");
            badAudio=false;emptyArray=false;
            result=resolveSource(multisourceResolver(base),"tencent","101","hires","");
            check(result.sourceId.equals("qq-vkeys"),"QQ ordinary playback must use qq-vkeys instead of a NetEase provider");
            check(result.address.endsWith("qq=signed%2Bvalue")&&result.bitrate==128,"QQ must preserve the signed URL and returned bitrate");
            System.out.println("PASS QQ ordinary quality and signed address");
            resolver=multisourceResolver(base);qqMid="002SongMID9";vkeysBadAudio=true;
            before=gdCalls.get();legacyBefore=legacyCalls.get();
            result=resolveSource(resolver,"tencent",qqMid,"lossless",base+"/custom");
            check(result.sourceId.equals("qq-injahow")&&result.address.endsWith("sig=signed%2Bvalue"),"QQ invalid audio must use its same-MID backup");
            check(gdCalls.get()==before&&legacyCalls.get()==legacyBefore,"QQ backup must never fall through to NetEase");
            System.out.println("PASS QQ same-MID backup despite NetEase custom setting");
            vkeysBadAudio=false;
            result=resolveSource(resolver,"tencent",qqMid,"hires",base+"/custom",new HashSet<>(Arrays.asList("qq-vkeys")),()->true);
            check(result.sourceId.equals("qq-injahow"),"failed QQ decoder source must remain excluded on retry");
            System.out.println("PASS source-aware QQ retry exclusion");

            resolver=multisourceResolver(base);kwMode="wrapped";kwBadOrigin=false;before=proxyCalls.get();
            result=resolveSource(resolver,"kuwo",kwId,"hires",base+"/custom");
            check(result.sourceId.equals("kuwo-origin")&&result.address.equals(base+"/origin?sig=keep%2Bthis&x=1%2F2"),"Kuwo must unwrap its approved proxy to the original signed URL");
            check(proxyCalls.get()==before,"healthy Kuwo origin must avoid the wrapper");
            System.out.println("PASS Kuwo original URL preference and signed query");
            kwBadOrigin=true;
            result=resolveSource(resolver,"kuwo",kwId,"lossless",base+"/custom");
            check(result.sourceId.equals("kuwo-proxy")&&URI.create(result.address).getPath().equals("/proxy"),"Kuwo invalid origin must use the approved same-id wrapper backup");
            System.out.println("PASS Kuwo same-id proxy backup despite NetEase custom setting");
            kwBadOrigin=false;kwMode="direct";result=resolveSource(resolver,"kuwo",kwId,"standard","");
            check(result.sourceId.equals("kuwo-origin")&&URI.create(result.address).getPath().equals("/origin"),"Kuwo direct URL must be playable without wrapping");
            System.out.println("PASS Kuwo direct audio address");
            kwMode="foreign";before=originCalls.get();
            final AudioResolver foreignResolver=resolver;
            failure(()->resolveSource(foreignResolver,"kuwo",kwId,"standard",""));
            check(originCalls.get()==before,"unrecognized proxy must not be unwrapped");
            kwMode="unsafe";result=resolveSource(resolver,"kuwo",kwId,"standard","");
            check(result.sourceId.equals("kuwo-proxy")&&originCalls.get()==before,"credential-bearing origin must be rejected while a valid wrapper remains available");
            System.out.println("PASS Kuwo wrapper allowlist and invalid origin");
            kwMode="wrapped";kwOk=false;before=originCalls.get();int proxyBefore=proxyCalls.get();
            failure(()->resolveSource(foreignResolver,"kuwo",kwId,"standard",""));
            check(originCalls.get()==before&&proxyCalls.get()==proxyBefore,"Kuwo ok=false must not supply an audio address");kwOk=true;kwStringOk=true;
            failure(()->resolveSource(foreignResolver,"kuwo",kwId,"standard",""));
            check(originCalls.get()==before&&proxyCalls.get()==proxyBefore,"Kuwo non-boolean success flag must not supply an audio address");kwStringOk=false;
            System.out.println("PASS Kuwo upstream error does not probe audio");

            final AudioResolver errorResolver=multisourceResolver(base);qqEmpty=true;vkeysCode=110001;
            String message=failure(()->resolveSource(errorResolver,"tencent",qqMid,"hires",""));
            check(message.contains("普通音质")&&!message.contains("secret")&&!message.contains("http://")&&!message.contains("更换音质"),"QQ restricted response must report clean platform failures and ordinary-quality recovery");
            vkeysCode=0;vkeysMissingCode=true;
            failure(()->resolveSource(errorResolver,"tencent",qqMid,"standard",""));
            vkeysMissingCode=false;vkeysStringCode=true;
            failure(()->resolveSource(errorResolver,"tencent",qqMid,"standard",""));
            vkeysStringCode=false;qqEmpty=false;
            System.out.println("PASS QQ error and missing success code are rejected without leaking provider text");

            customStatus=503;final AudioResolver customResolver=multisourceResolver(base);before=gdCalls.get();legacyBefore=legacyCalls.get();
            failure(()->resolveSource(customResolver,"netease","101","hires",base+"/custom"));
            check(gdCalls.get()==before&&legacyCalls.get()==legacyBefore,"failed NetEase custom API must remain exclusive");
            result=resolveSource(customResolver,"tencent",qqMid,"higher",base+"/custom");
            check(result.sourceId.equals("qq-vkeys"),"NetEase custom outage must not disable QQ");
            result=resolveSource(customResolver,"kuwo",kwId,"higher",base+"/custom");
            check(result.sourceId.equals("kuwo-origin"),"NetEase custom outage must not disable Kuwo");customStatus=200;
            System.out.println("PASS NetEase custom exclusivity does not affect QQ or Kuwo");
            check(!customResolver.hasAudioBackups("netease",base+"/custom")&&customResolver.hasAudioBackups("netease","")
                &&customResolver.hasAudioBackups("tencent",base+"/custom")&&customResolver.hasAudioBackups("kuwo",base+"/custom")
                &&!customResolver.hasAudioBackups("local",""),"decoder backup availability must follow the track platform rather than the NetEase custom setting");
            System.out.println("PASS decoder backup availability follows platform");

            final AudioResolver validationResolver=multisourceResolver(base);before=networkCalls();
            for(String[] invalid:new String[][]{{"tencent","bad-mid"},{"tencent","中文MID"},{"tencent",String.join("",Collections.nCopies(65,"a"))},{"kuwo","0"},{"kuwo","01"},{"kuwo","12345678901234567890"},{"netease","0"},{"unknown","101"}})
                failure(()->resolveSource(validationResolver,invalid[0],invalid[1],"standard",""));
            failure(()->resolveSource(validationResolver,"tencent",qqMid,"unknown",""));
            check(networkCalls()==before,"invalid platform ids and quality must fail before network access");
            qqMid=String.join("",Collections.nCopies(64,"a"));result=resolveSource(validationResolver,"tencent",qqMid,"standard","");
            check(result.sourceId.equals("qq-vkeys"),"64-character ASCII MID boundary must be accepted");
            kwId="1234567890123456789";result=resolveSource(validationResolver,"kuwo",kwId,"standard","");
            check(result.sourceId.equals("kuwo-origin"),"19-digit positive Kuwo id boundary must be accepted");
            System.out.println("PASS platform ID and quality validation boundaries");

            before=networkCalls();
            for(String platform:Arrays.asList("tencent","kuwo"))failure(()->resolveSource(validationResolver,platform,platform.equals("tencent")?qqMid:kwId,"standard","",Collections.emptySet(),()->false));
            check(networkCalls()==before,"pre-cancelled QQ and Kuwo requests must not access the network");
            System.out.println("PASS cross-platform cancellation before network");
            qqMid="CancelMID1";vkeysDelay=500;vkeysStarted=new CountDownLatch(1);final AudioResolver cancelResolver=multisourceResolver(base);
            ExecutorService requestWorker=Executors.newSingleThreadExecutor();
            try {
                int backupBefore=qqMetingCalls.get();Future<String> cancelled=requestWorker.submit(()->failure(()->resolveSource(cancelResolver,"tencent",qqMid,"standard","")));
                check(vkeysStarted.await(1,TimeUnit.SECONDS),"cancellation fixture request must start");cancelResolver.cancel();
                check(cancelled.get(2,TimeUnit.SECONDS).contains("取消"),"cancel() must end active resolution even when external predicate stays true");
                check(qqMetingCalls.get()==backupBefore,"cancelled QQ request must not launch its backup");
            } finally {vkeysDelay=0;requestWorker.shutdownNow();}
            System.out.println("PASS in-flight cancellation ends the source chain");

            gdStatus=200;legacyStatus=200;metingStatus=200;badAudio=true;netDelay=90;
            final AudioResolver budgetResolver=new AudioResolver(base+"/gd",base+"/legacy",base+"/meting",500,140);
            long started=System.nanoTime();message=failure(()->budgetResolver.resolve("101","hires","",Collections.emptySet(),()->true));
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);netDelay=0;
            check(message.contains("超时")&&elapsed<800,"one total deadline must bound all provider attempts and audio probes");
            System.out.println("PASS total resolution timeout bounds the source chain");

            String secure=redirect("https://api.injahow.cn/meting/?type=url","http://aqqmusic.tc.qq.com/amobile?q=keep%2Bthis&v=1%2F2",true);
            check(secure.equals("https://aqqmusic.tc.qq.com/amobile?q=keep%2Bthis&v=1%2F2"),"QQ downgrade must use exact HTTPS equivalent without changing signed query");
            for(String host:Arrays.asList("ws.stream.qqmusic.qq.com","isure.stream.qqmusic.qq.com"))
                check(redirect("https://api.injahow.cn/","http://"+host+"/audio",true).equals("https://"+host+"/audio"),"known QQ CDN must be upgraded");
            failure(()->redirect("https://api.injahow.cn/","http://aqqmusic.tc.qq.com.evil.test/audio",true));
            failure(()->redirect("https://api.injahow.cn/","http://aqqmusic.tc.qq.com:80/audio",true));
            failure(()->redirect("https://api.injahow.cn/","http://user@aqqmusic.tc.qq.com/audio",true));
            failure(()->redirect("https://api.injahow.cn/","http://aqqmusic.tc.qq.com/audio",false));
            check(redirect(base+"/meting","/audio?sig=safe%2Bvalue",false).equals(base+"/audio?sig=safe%2Bvalue"),"relative safe redirects must retain existing behavior");
            System.out.println("PASS QQ HTTPS equivalent allowlist and normal redirect safety");
        } finally {server.stop(0);httpWorkers.shutdownNow();}
    }
}
