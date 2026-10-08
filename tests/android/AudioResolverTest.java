package org.floatmusic.player;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

// Tests the APK resolver itself with anonymous loopback provider/CDN fixtures.
public final class AudioResolverTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    interface Operation{void run() throws Exception;}
    static String failure(Operation operation) throws Exception {
        try{operation.run();throw new AssertionError("operation must fail");}
        catch(IOException expected){return expected.getMessage();}
    }
    static void ascii(byte[] out,int offset,String text){
        byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);System.arraycopy(bytes,0,out,offset,bytes.length);
    }
    static void number(byte[] out,int offset,long value,int size,boolean little){
        for(int i=0;i<size;i++)out[offset+(little?i:size-1-i)]=(byte)(value>>>(8*i));
    }
    static byte[] flac(int rate,int bits,double seconds){
        byte[] bytes=new byte[256];ascii(bytes,0,"fLaC");bytes[4]=(byte)128;bytes[7]=34;
        number(bytes,8,16,2,false);number(bytes,10,4096,2,false);
        number(bytes,18,((long)rate<<44)|(1L<<41)|((long)(bits-1)<<36)|Math.round(seconds*rate),8,false);return bytes;
    }
    static byte[] mp3(int kbps){
        int index=kbps==320?14:kbps==192?11:9,size=144*kbps*1000/44100;
        byte[] bytes=new byte[size*2];
        for(int i=0;i<2;i++){int at=i*size;bytes[at]=(byte)255;bytes[at+1]=(byte)251;bytes[at+2]=(byte)(index<<4);}return bytes;
    }
    static byte[] ogg(){
        byte[] bytes=new byte[58];ascii(bytes,0,"OggS");bytes[26]=1;bytes[27]=30;bytes[28]=1;ascii(bytes,29,"vorbis");
        bytes[39]=2;number(bytes,40,48000,4,true);number(bytes,48,320000,4,true);bytes[57]=1;return bytes;
    }
    static byte[] aac(){
        byte[] bytes=new byte[372];bytes[0]=(byte)255;bytes[1]=(byte)241;bytes[2]=(byte)(4<<2);
        bytes[3]=(byte)(bytes.length>>11);bytes[4]=(byte)(bytes.length>>3);bytes[5]=(byte)((bytes.length&7)<<5);return bytes;
    }
    static byte[] wav(){
        byte[] bytes=new byte[44];ascii(bytes,0,"RIFF");number(bytes,4,36,4,true);ascii(bytes,8,"WAVE");
        ascii(bytes,12,"fmt ");number(bytes,16,16,4,true);number(bytes,20,1,2,true);number(bytes,22,2,2,true);
        number(bytes,24,48000,4,true);number(bytes,28,192000,4,true);number(bytes,32,4,2,true);number(bytes,34,16,2,true);
        ascii(bytes,36,"data");return bytes;
    }
    static byte[] m4a(boolean specs){
        byte[] bytes=new byte[specs?100:36];number(bytes,0,24,4,false);ascii(bytes,4,"ftyp");ascii(bytes,8,"M4A ");ascii(bytes,16,"M4A ");ascii(bytes,20,"isom");
        if(specs){
            number(bytes,24,76,4,false);ascii(bytes,28,"mp4a");number(bytes,56,44100L<<16,4,false);
            number(bytes,60,40,4,false);ascii(bytes,64,"esds");bytes[72]=4;bytes[73]=13;bytes[74]=0x40;bytes[75]=0x15;
            number(bytes,83,320000,4,false);
        }return bytes;
    }
    static byte[] largeId3(){byte[] bytes=new byte[16384];ascii(bytes,0,"ID3");bytes[3]=4;bytes[7]=1;return bytes;}
    static void delay(int ms){try{if(ms>0)Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService workers=Executors.newCachedThreadPool();final String base;
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());final AtomicInteger probes=new AtomicInteger();
        volatile byte[] netAudio=mp3(128),backupAudio=mp3(128),kwAudio=flac(44100,16,200);
        volatile int gdStatus=200,legacyStatus=200,qqStatus=200,kwStatus=200,qqCode=0,netDelay=0,qqDelay=0;
        volatile boolean qqEmpty=false,qqBadAudio=false,originBad=false,qqMismatch=false,qqMissingMid=false,qqStringCode=false,kwStringOk=false;
        volatile String kwRid="9001",kwType="0",kwDuration="200",kwMode="wrapped";
        volatile long mediaTotal=5000000;volatile CountDownLatch qqStarted=new CountDownLatch(0);
        final Map<String,byte[]> qqAudio=new ConcurrentHashMap<>();
        Fixture() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
            base="http://127.0.0.1:"+server.getAddress().getPort();
            qqAudio.put("14",flac(96000,24,200));qqAudio.put("10",flac(44100,16,200));qqAudio.put("8",mp3(320));qqAudio.put("4",mp3(128));
            server.createContext("/",exchange->{
                try{
                    String path=exchange.getRequestURI().getPath(),query=exchange.getRequestURI().getRawQuery();
                    requests.add(path+(query==null?"":"?"+query));
                    check(exchange.getRequestHeaders().getFirst("Cookie")==null&&exchange.getRequestHeaders().getFirst("Authorization")==null,"all requests must remain anonymous");
                    int status=200;String body="",type="application/json";byte[] binary=null;
                    if(path.equals("/gd")){status=gdStatus;delay(netDelay);body="{\"url\":\""+base+"/net/audio?sig=keep%2Bthis\",\"br\":999}";}
                    else if(path.equals("/legacy")){status=legacyStatus;delay(netDelay);type="text/plain";body=base+"/backup/audio";}
                    else if(path.equals("/meting")){
                        if(query.contains("type=url")){status=302;exchange.getResponseHeaders().set("Location",base+"/backup/audio?sig=signed%2Bvalue");}
                        else body=qqEmpty&&query.contains("server=tencent")?"[]":"[{\"url\":\""+base+"/meting?type=url&id="+query.substring(query.indexOf("id=")+3)+"\"}]";
                    }else if(path.equals("/custom/song/url/v1")){status=gdStatus;body="{\"code\":200,\"data\":[{\"url\":\""+base+"/net/audio\"}]}";}
                    else if(path.equals("/music/tencent/song/link")){
                        status=qqStatus;qqStarted.countDown();delay(qqDelay);
                        String mid=query.substring(4,query.indexOf("&quality=")),level=query.substring(query.indexOf("&quality=")+9,query.indexOf("&type="));
                        body="{\"code\":"+(qqStringCode?"\"0\"":qqCode)+",\"message\":\"secret signed-token\",\"data\":{"
                            +(qqMissingMid?"":"\"songMID\":\""+(qqMismatch?"DifferentMID":mid)+"\",")
                            +"\"url\":\""+base+(qqBadAudio?"/html":"/qq/audio/"+level+"?sig=signed%2Bvalue")+"\",\"kbps\":\"9999kbps\"}}";
                    }else if(path.equals("/mobi")){
                        status=kwStatus;body="{\"code\":200,\"data\":{\"rid\":\""+kwRid+"\",\"type\":"+kwType+",\"duration\":\""+kwDuration+"\",\"url\":\""+base+"/kw/audio?sig=keep%2Bthis\"}}";
                    }else if(path.equals("/api")){
                        String origin=base+"/origin?sig=keep%2Bthis&x=1%2F2";
                        String supplied=kwMode.equals("direct")?origin:base+(kwMode.equals("foreign")?"/untrusted/proxy":"/proxy")+"?url="+URLEncoder.encode(origin,"UTF-8");
                        if(kwMode.equals("unsafe"))supplied=base+"/proxy?url="+URLEncoder.encode("http://user:secret@127.0.0.1/audio","UTF-8");
                        body="{\"ok\":"+(kwStringOk?"\"true\"":"true")+",\"id\":\"9001\",\"url\":\""+supplied+"\"}";
                    }else if(path.equals("/html")||path.equals("/untrusted/proxy")){type="audio/flac";body="<html>Expired token</html>";}
                    else if(path.startsWith("/qq/audio/")){type="audio/flac";binary=qqAudio.get(path.substring(path.lastIndexOf('/')+1));}
                    else if(path.equals("/net/audio")){type="audio/flac";binary=netAudio;}
                    else if(path.equals("/kw/audio")){type="audio/flac";binary=kwAudio;}
                    else if(path.equals("/origin")){type="audio/flac";binary=originBad?"<html>expired</html>".getBytes(StandardCharsets.UTF_8):backupAudio;}
                    else if(path.equals("/backup/audio")||path.equals("/proxy")){type="audio/flac";binary=backupAudio;}
                    else status=404;
                    if(binary!=null){
                        probes.incrementAndGet();check("bytes=0-16383".equals(exchange.getRequestHeaders().getFirst("Range")),"probe must be bounded to 16 KiB");
                        exchange.getResponseHeaders().set("Content-Range","bytes 0-"+(binary.length-1)+"/"+mediaTotal);
                    }
                    byte[] bytes=binary==null?body.getBytes(StandardCharsets.UTF_8):binary;if(status>=400)bytes="unavailable".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
                    if(bytes.length>0)exchange.getResponseBody().write(bytes);
                }finally{exchange.close();}
            });server.start();
        }
        AudioResolver resolver(){return new AudioResolver(base+"/gd",base+"/legacy",base+"/meting",base,base+"/api",base+"/mobi",600,4000);}
        AudioResolver backups(){return new AudioResolver(base+"/gd",base+"/legacy",base+"/meting",base,base+"/api",600,2000);}
        void reset(){requests.clear();probes.set(0);}
        public void close(){server.stop(0);workers.shutdownNow();}
    }
    static AudioResolver.Result resolve(AudioResolver r,String source,String id,String quality) throws Exception{
        return r.resolve(source,id,quality,"",Collections.emptySet(),()->true);
    }
    static AudioResolver.Result timed(AudioResolver r,String source,String id,String quality,double duration) throws Exception{
        return r.resolve(source,id,quality,"",Collections.emptySet(),()->true,duration);
    }
    public static void main(String[] args) throws Exception {
        try(Fixture f=new Fixture()){
            AudioResolver.Result result=resolve(f.resolver(),"tencent","002SongMID9","exhigh");
            check(f.requests.contains("/music/tencent/song/link?mid=002SongMID9&quality=8&type=0")&&result.sourceId.equals("qq-vkeys-exhigh"),"QQ high-bitrate preference must request q8 for the same MID");
            check(result.quality.equals("exhigh")&&result.format.equals("MP3")&&result.bitrate==320&&result.sampleRate==44100,"verified MP3 must override false FLAC MIME and provider bitrate");
            check(result.address.endsWith("sig=signed%2Bvalue"),"signed URL encoding must survive");
            System.out.println("PASS QQ q8 and verified MP3 despite false MIME/metadata");

            result=timed(f.resolver(),"tencent","002SongMID9","master",200);
            check(result.sourceId.equals("qq-vkeys-master")&&result.quality.equals("master")&&result.bitsPerSample==24&&result.sampleRate==96000&&result.notice.contains("不能证明"),"QQ q14 must verify 24-bit/96kHz FLAC and explain provenance limitation");
            byte[] highSpecWav=wav();number(highSpecWav,24,96000,4,true);number(highSpecWav,28,576000,4,true);number(highSpecWav,32,6,2,true);number(highSpecWav,34,24,2,true);
            f.qqAudio.put("14",highSpecWav);result=resolve(f.resolver(),"tencent","002SongMID9","master");
            check(result.format.equals("WAV")&&result.notice.contains("WAV")&&!result.notice.contains("FLAC"),"high-spec lossless notice must name the verified codec, never a MIME/provider claim");
            f.reset();f.qqAudio.put("14",flac(44100,16,200));result=resolve(f.resolver(),"tencent","002SongMID9","master");
            check(result.sourceId.equals("qq-vkeys-lossless")&&result.quality.equals("lossless")&&result.notice.contains("回退")&&f.requests.contains("/music/tencent/song/link?mid=002SongMID9&quality=10&type=0"),"ordinary FLAC must not be called master and must try q10");
            f.qqAudio.put("10",mp3(128));f.qqAudio.put("8",mp3(128));f.reset();result=resolve(f.resolver(),"tencent","002SongMID9","lossless");
            check(result.sourceId.equals("qq-vkeys")&&result.quality.equals("standard")&&result.notice.contains("回退"),"ignored high-quality requests must fall back to verified q4");
            System.out.println("PASS QQ q14/q10/q8/q4 verified fallback");

            f.reset();f.qqMismatch=true;f.qqEmpty=true;String message=failure(()->resolve(f.resolver(),"tencent","002SongMID9","standard"));
            check(f.probes.get()==0&&!message.contains("secret")&&!message.contains("http://"),"wrong QQ MID must never probe or disclose URL");
            f.qqMismatch=false;f.qqMissingMid=true;failure(()->resolve(f.resolver(),"tencent","002SongMID9","standard"));check(f.probes.get()==0,"missing QQ songMID must be rejected");
            f.qqMissingMid=false;f.qqStringCode=true;failure(()->resolve(f.resolver(),"tencent","002SongMID9","standard"));check(f.probes.get()==0,"string success code must not authorize audio");
            f.qqStringCode=false;f.qqCode=110001;failure(()->resolve(f.resolver(),"tencent","002SongMID9","lossless"));check(f.probes.get()==0,"restricted response must not supply audio");
            f.qqCode=0;f.qqEmpty=false;f.qqBadAudio=true;f.reset();result=resolve(f.resolver(),"tencent","002SongMID9","exhigh");
            check(result.sourceId.equals("qq-injahow")&&result.quality.equals("standard")&&result.address.endsWith("sig=signed%2Bvalue"),"QQ must retain its anonymous same-MID backup");
            check(!f.requests.toString().contains("/gd?")&&!f.requests.toString().contains("/legacy?"),"QQ fallback must never cross to NetEase");f.qqBadAudio=false;
            System.out.println("PASS QQ identity/restriction/error sanitation and same-MID backup");

            f.reset();result=timed(f.resolver(),"kuwo","9001","lossless",200);
            check(result.sourceId.equals("kuwo-mobi-lossless")&&result.format.equals("FLAC")&&result.quality.equals("lossless"),"Kuwo public mobi lossless route must verify FLAC");
            check(f.requests.contains("/mobi?f=web&source=jiakong&type=convert_url_with_sign&rid=9001&br=2000kflac")&&result.expectedDuration==200,"Kuwo mobi must request exact RID/format and retain duration");
            f.kwAudio=mp3(320);f.reset();result=timed(f.resolver(),"kuwo","9001","lossless",200);
            check(result.sourceId.equals("kuwo-mobi-exhigh")&&result.bitrate==320&&result.notice.contains("回退")&&f.requests.contains("/mobi?f=web&source=jiakong&type=convert_url_with_sign&rid=9001&br=320kmp3"),"Kuwo FLAC request returning MP3 must try lower public route");
            f.kwAudio=mp3(128);f.reset();result=timed(f.resolver(),"kuwo","9001","lossless",200);
            check(result.sourceId.equals("kuwo-mobi")&&f.requests.contains("/mobi?f=web&source=jiakong&type=convert_url_with_sign&rid=9001&br=128kmp3"),"Kuwo must stop at bounded standard route");
            System.out.println("PASS Kuwo mobi exact RID/query and lossless/320/128 fallback");

            f.reset();f.kwRid="9002";failure(()->timed(f.resolver(),"kuwo","9001","standard",200));
            check(f.probes.get()==0&&!f.requests.toString().contains("/api?"),"wrong Kuwo RID must fail closed before probing or backup");
            f.kwRid="9001";f.kwType="1";failure(()->timed(f.resolver(),"kuwo","9001","standard",200));f.kwType="\"0\"";failure(()->timed(f.resolver(),"kuwo","9001","standard",200));
            f.kwType="0";f.kwDuration="0";failure(()->timed(f.resolver(),"kuwo","9001","standard",200));f.kwDuration="12";failure(()->timed(f.resolver(),"kuwo","9001","standard",200));
            check(f.probes.get()==0,"wrong type/invalid or mismatched duration must never probe Kuwo audio");
            f.kwDuration="200";f.kwAudio=flac(44100,16,12);failure(()->timed(f.resolver(),"kuwo","9001","lossless",200));
            f.kwDuration="12";failure(()->resolve(f.resolver(),"kuwo","9001","lossless"));result=timed(f.resolver(),"kuwo","9001","lossless",12);
            check(result.expectedDuration==12&&result.duration==12,"genuine short song with independent target must remain playable");
            f.kwDuration="200";f.kwAudio=flac(44100,16,200);
            System.out.println("PASS Kuwo identity/type/duration and genuine short songs");

            f.kwStatus=503;f.reset();result=timed(f.resolver(),"kuwo","9001","lossless",200);
            check(result.sourceId.equals("kuwo-origin")&&result.address.equals(f.base+"/origin?sig=keep%2Bthis&x=1%2F2"),"mobi outage must use anonymous original signed URL");
            f.originBad=true;result=timed(f.resolver(),"kuwo","9001","lossless",200);
            check(result.sourceId.equals("kuwo-proxy")&&URI.create(result.address).getPath().equals("/proxy"),"bad origin must try approved wrapper");
            f.originBad=false;f.kwMode="direct";result=resolve(f.backups(),"kuwo","9001","standard");check(result.sourceId.equals("kuwo-origin"),"direct URL must remain available");
            f.kwMode="foreign";f.reset();failure(()->resolve(f.backups(),"kuwo","9001","standard"));check(!f.requests.toString().contains("/origin?"),"unrecognized wrapper must not be unwrapped");
            f.kwMode="unsafe";result=resolve(f.backups(),"kuwo","9001","standard");check(result.sourceId.equals("kuwo-proxy"),"credential-bearing origin must be rejected while safe wrapper remains available");
            f.kwMode="wrapped";f.kwStringOk=true;f.reset();failure(()->resolve(f.backups(),"kuwo","9001","standard"));check(f.probes.get()==0,"non-boolean Kuwo success flag must not supply audio");f.kwStringOk=false;f.kwStatus=200;
            System.out.println("PASS Kuwo original/proxy backup, signed query and wrapper safety");

            f.netAudio=flac(44100,16,200);f.reset();result=resolve(f.resolver(),"netease","101","hires");
            check(result.sourceId.equals("gd")&&result.quality.equals("lossless")&&result.bitsPerSample==16&&result.notice.contains("回退")&&f.requests.contains("/gd?types=url&source=netease&id=101&br=999"),"NetEase request remains Hi-Res while ordinary FLAC reports lossless");
            f.netAudio=flac(96000,24,200);result=resolve(f.resolver(),"netease","101","hires");check(result.quality.equals("hires")&&result.sampleRate==96000,"Hi-Res must derive from measured FLAC specs");
            byte[][] containers={mp3(320),ogg(),aac(),wav(),m4a(true),m4a(false),largeId3()};
            String[] formats={"MP3","Vorbis","AAC","WAV","AAC","",""},qualities={"exhigh","exhigh","standard","lossless","exhigh","standard","standard"};
            for(int i=0;i<containers.length;i++){
                f.netAudio=containers[i];result=resolve(f.resolver(),"netease","101","hires");
                check(result.format.equals(formats[i])&&result.quality.equals(qualities[i]),"codec/container classification case "+i);
                if(i>=5)check(result.bitrate==0&&result.sampleRate==0&&result.bitsPerSample==0,"container-only result must not invent specs");
            }
            f.netAudio="fLaC0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);f.legacyStatus=503;result=resolve(f.resolver(),"netease","101","hires");
            check(result.sourceId.equals("injahow")&&result.format.equals("MP3"),"invalid FLAC magic must fail despite FLAC MIME");f.netAudio=mp3(128);f.legacyStatus=200;
            System.out.println("PASS bounded FLAC/MP3/Vorbis/AAC/M4A/WAV sniff and conservative containers");

            AudioResolver exclusive=f.resolver();f.reset();result=exclusive.resolve("netease","101","hires",f.base+"/custom",Collections.emptySet(),()->true);
            check(result.sourceId.equals("custom")&&!f.requests.toString().contains("/gd?"),"custom NetEase API must stay exclusive");
            f.gdStatus=503;failure(()->exclusive.resolve("netease","101","hires",f.base+"/custom",Collections.emptySet(),()->true));
            f.reset();result=exclusive.resolve("tencent","002SongMID9","standard",f.base+"/custom",Collections.emptySet(),()->true);
            check(result.sourceId.equals("qq-vkeys")&&!f.requests.toString().contains("custom"),"NetEase custom outage must not affect QQ");f.gdStatus=200;
            check(exclusive.hasAudioBackups("tencent","")&&exclusive.hasAudioBackups("kuwo","")&&!exclusive.hasAudioBackups("netease",f.base+"/custom"),"decoder backups follow platform");
            result=exclusive.resolve("tencent","002SongMID9","lossless","",new HashSet<>(Arrays.asList("qq-vkeys-lossless","qq-vkeys-exhigh","qq-vkeys")),()->true);
            check(result.sourceId.equals("qq-injahow"),"excluded decoder routes must not be retried");
            System.out.println("PASS custom exclusivity and decoder route exclusion");

            f.reset();AudioResolver validate=f.resolver();
            for(String[] invalid:new String[][]{{"tencent","bad-mid","standard"},{"tencent","中文","standard"},{"kuwo","0","standard"},{"kuwo","01","standard"},{"netease","0","standard"},{"unknown","101","standard"},{"tencent","002SongMID9","hires"},{"tencent","002SongMID9","higher"},{"kuwo","9001","master"}})
                failure(()->resolve(validate,invalid[0],invalid[1],invalid[2]));
            check(f.requests.isEmpty(),"invalid identities or unsupported quality must fail before network");
            for(String platform:Arrays.asList("tencent","kuwo"))failure(()->validate.resolve(platform,platform.equals("tencent")?"002SongMID9":"9001","standard","",Collections.emptySet(),()->false));
            check(f.requests.isEmpty(),"pre-cancelled requests must not access network");
            System.out.println("PASS platform ID/quality validation and pre-cancellation");

            f.qqStatus=503;f.qqEmpty=true;AudioResolver cooled=f.resolver();f.reset();failure(()->resolve(cooled,"tencent","002SongMID9","standard"));int count=f.requests.size();
            failure(()->resolve(cooled,"tencent","002SongMID9","standard"));check(f.requests.size()==count+1,"outage cooldown skips failed provider but may recheck empty backup");
            cooled.clearFailures();failure(()->resolve(cooled,"tencent","002SongMID9","standard"));check(f.requests.size()>count+2,"explicit retry clears cooldown");f.qqStatus=200;f.qqEmpty=false;
            f.qqCode=110001;AudioResolver songFailure=f.resolver();f.reset();resolve(songFailure,"tencent","002SongMID9","standard");
            f.qqCode=0;result=resolve(songFailure,"tencent","OtherMID","standard");check(result.sourceId.equals("qq-vkeys"),"song-specific restriction must not cool down entire provider");
            System.out.println("PASS outage/song cooldown separation and explicit retry");

            f.qqDelay=500;f.qqStarted=new CountDownLatch(1);AudioResolver cancelled=f.resolver();ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<String> pending=executor.submit(()->failure(()->resolve(cancelled,"tencent","002SongMID9","master")));
                check(f.qqStarted.await(1,TimeUnit.SECONDS),"delayed QQ request must start");cancelled.cancel();
                check(pending.get(2,TimeUnit.SECONDS).contains("取消"),"cancel must prevent fallback and stale publication");
            }finally{executor.shutdownNow();f.qqDelay=0;}
            f.netDelay=140;AudioResolver deadline=new AudioResolver(f.base+"/gd",f.base+"/legacy",f.base+"/meting","","",200,220);f.gdStatus=503;f.legacyStatus=503;
            long start=System.nanoTime();message=failure(()->resolve(deadline,"netease","101","hires"));long elapsed=(System.nanoTime()-start)/1000000;
            check(message.contains("超时")&&elapsed<800,"one deadline must bound all requests");f.netDelay=0;f.gdStatus=200;f.legacyStatus=200;
            System.out.println("PASS cancellation and total deadline");

            for(String host:Arrays.asList("aqqmusic.tc.qq.com","ws.stream.qqmusic.qq.com","isure.stream.qqmusic.qq.com","kw-er.kuwo.cn","other-er.kuwo.cn"))
                check(AudioResolver.redirectAddress("https://provider.test/","http://"+host+"/audio?sig=keep%2Bthis&x=1%2F2",true).equals("https://"+host+"/audio?sig=keep%2Bthis&x=1%2F2"),"known CDN exact HTTPS upgrade must preserve raw signature");
            failure(()->AudioResolver.redirectAddress("https://provider.test/","http://kw-er.kuwo.cn.evil.test/audio",true));
            failure(()->AudioResolver.redirectAddress("https://provider.test/","http://kw-er.kuwo.cn:80/audio",true));
            failure(()->AudioResolver.redirectAddress("https://provider.test/","http://user@kw-er.kuwo.cn/audio",true));
            failure(()->AudioResolver.redirectAddress("https://provider.test/","http://kw-er.kuwo.cn/audio",false));
            check(AudioResolver.redirectAddress(f.base+"/meting","/backup/audio?sig=safe%2Bvalue",false).equals(f.base+"/backup/audio?sig=safe%2Bvalue"),"relative redirects preserve raw signed query");
            System.out.println("PASS HTTPS CDN allowlist and redirect safety");
        }
    }
}
