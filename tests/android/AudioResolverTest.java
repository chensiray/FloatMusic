package org.floatmusic.player;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;

// Runs the same resolver shipped in the APK against loopback HTTP fixtures.
public final class AudioResolverTest {
    static int gdStatus=200, legacyStatus=200, metingStatus=200;
    static boolean badAudio=false, emptyArray=false;
    static final AtomicInteger gdCalls=new AtomicInteger(), legacyCalls=new AtomicInteger();
    static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path=exchange.getRequestURI().getPath(), query=exchange.getRequestURI().getRawQuery();
            int status=200; String body="",type="application/json";
            switch(path) {
                case "/gd": gdCalls.incrementAndGet(); status=gdStatus;
                    check(query.contains("br=999"),"Hi-Res must request GD br=999");
                    body="{\"url\":\""+base+(badAudio?"/html":"/audio?sig=keep%2Bthis")+"\",\"br\":740,\"size\":123}";break;
                case "/legacy": legacyCalls.incrementAndGet(); status=legacyStatus;type="text/plain";body=base+"/audio";break;
                case "/meting": status=metingStatus;
                    if(query.contains("type=url")) {status=302;exchange.getResponseHeaders().set("Location",base+"/audio?sig=signed%2Bvalue");}
                    else body=emptyArray?"[]":"[{\"name\":\"Test\",\"artist\":\"Test\",\"url\":\""+base+"/meting?type=url&id=101\",\"pic\":\"\",\"lrc\":\"\"}]";break;
                case "/audio":type="audio/flac";body="fLaC0123456789abcdefghijklmnopqrstuvwxyz";
                    check("bytes=0-63".equals(exchange.getRequestHeaders().getFirst("Range")),"audio probe must be bounded");break;
                case "/html":type="text/html";body="<html>Expired link</html>";break;
                case "/custom/song/url/v1":body="{\"code\":200,\"data\":[{\"url\":\""+base+"/audio\"}]}";break;
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
        } finally {server.stop(0);}
    }
}
