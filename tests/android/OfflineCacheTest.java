package org.floatmusic.player;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Real files and HTTP, isolated from application/user data; runnable on the host JVM. */
public final class OfflineCacheTest {
    static int checks;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    static JSONObject track(String id)throws Exception{return new JSONObject().put("source","netease").put("songId",id).put("id","netease:"+id).put("name","fixture "+id).put("artist","test").put("duration",1);}
    static JSONObject json(String key,Object value)throws Exception{return new JSONObject().put(key,value);}
    static JSONArray lines(String original,String translation)throws Exception{return new JSONArray().put(new JSONObject().put("time",0).put("original",original).put("translation",translation));}
    static JSONObject lyricPayload(String key,String original,String translation,String originalState,String translationState)throws Exception{return new JSONObject().put("key",key).put("original",original).put("translation",translation).put("originalState",originalState).put("translationState",translationState).put("lyricLines",original.isEmpty()&&translation.isEmpty()?new JSONArray():lines(original,translation));}
    static void lyricRetryChecks(File base,OfflineCache.Resolver resolver,AtomicBoolean wifi)throws Exception{
        File dir=new File(base,"lyrics");OfflineCache c=new OfflineCache(dir,resolver,wifi::get,listener);
        try{
            String saved=c.enqueue(track("101").put("unrelatedPayload",new JSONArray().put("large data")),"standard",true);await(c,saved,"ready");
            c.lyrics(lyricPayload(saved,"Original","Translation","available","available"));
            JSONObject listed=c.listing().getJSONObject(0);check(saved.equals(listed.optString("key"))&&"ready".equals(listed.optString("status"))&&"available".equals(listed.optString("originalState"))&&listed.optBoolean("manual"),"lightweight listing retains management state");
            check(!listed.has("original")&&!listed.has("translation")&&!listed.has("lyricLines")&&!listed.has("digest")&&!listed.getJSONObject("track").has("unrelatedPayload"),"lightweight listing excludes lyrics checksums and unrelated track data");
            listed.getJSONObject("track").put("name","Changed externally");check("fixture 101".equals(c.entry(saved).getJSONObject("track").optString("name")),"listing snapshots cannot mutate stored track metadata");
            c.lyrics(lyricPayload(saved,"","","failed","failed"));
            JSONObject e=c.entry(saved);
            check("Original".equals(e.optString("original"))&&"Translation".equals(e.optString("translation"))&&"available".equals(e.optString("originalState"))&&"available".equals(e.optString("translationState")),"failed lyric retry preserves known text and availability");
            check("Translation".equals(e.getJSONArray("lyricLines").getJSONObject(0).optString("translation")),"failed lyric retry preserves synchronized bilingual rows");
            c.lyrics(lyricPayload(saved,"Updated","","available","failed"));e=c.entry(saved);
            check("Updated".equals(e.optString("original"))&&"Translation".equals(e.optString("translation"))&&"available".equals(e.optString("translationState")),"partial lyric success preserves the other known stream");
            check("Updated".equals(e.getJSONArray("lyricLines").getJSONObject(0).optString("original"))&&"Translation".equals(e.getJSONArray("lyricLines").getJSONObject(0).optString("translation")),"partial lyric success merges synchronized rows");
            String instrumental=c.enqueue(track("102"),"standard",true);await(c,instrumental,"ready");
            c.lyrics(lyricPayload(instrumental,"","","none","none"));c.lyrics(lyricPayload(instrumental,"","","failed","unknown"));
            check("none".equals(c.entry(instrumental).optString("originalState"))&&"none".equals(c.entry(instrumental).optString("translationState")),"retry failure preserves explicitly confirmed no-content states");
            String unknown=c.enqueue(track("103"),"standard",true);await(c,unknown,"ready");
            c.lyrics(lyricPayload(unknown,"","","available","available"));
            check("unknown".equals(c.entry(unknown).optString("originalState"))&&"unknown".equals(c.entry(unknown).optString("translationState")),"empty strings cannot claim saved lyrics");
            c.close();
            ConcurrentMap<String,AtomicInteger> lyricRequests=new ConcurrentHashMap<>();
            OfflineCache.Listener requests=new OfflineCache.Listener(){public void changed(){}public void lyrics(String key,JSONObject t){lyricRequests.computeIfAbsent(key,k->new AtomicInteger()).incrementAndGet();}};
            c=new OfflineCache(dir,(t,q,a)->{throw new IOException("audio network forbidden");},wifi::get,requests);c.start();c.start();
            check(lyricRequests.containsKey(unknown)&&lyricRequests.get(unknown).get()==1,"restart retries unknown lyrics once without audio resolution");
            check(!lyricRequests.containsKey(saved)&&!lyricRequests.containsKey(instrumental),"restart leaves complete lyrics and confirmed instrumental entries offline");
            JSONObject hit=c.lookup(track("101"),"lossless");check(hit!=null&&"Updated".equals(hit.optString("original"))&&"Translation".equals(hit.optString("translation")),"restart retains known lyrics after failed and partial retries");
        }finally{c.close();}
    }
    static byte[] wav(){byte[] bytes=new byte[16044];ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(bytes.length-8).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(16000);return bytes;}
    static final OfflineCache.Listener listener=new OfflineCache.Listener(){public void changed(){}public void lyrics(String key,JSONObject track){}};
    static JSONObject await(OfflineCache c,String key,String state)throws Exception{long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){JSONObject e=c.entry(key);if(e!=null&&state.equals(e.optString("status")))return e;Thread.sleep(15);}throw new AssertionError("Expected "+state+" : "+c.entries());}
    public static void main(String[] args)throws Exception{
        File base=Files.createTempDirectory("floatmusic-offline-test-").toFile();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] audio=wav();AtomicInteger requests=new AtomicInteger();AtomicBoolean wifi=new AtomicBoolean(true);AtomicBoolean slow=new AtomicBoolean(false);CountDownLatch streaming=new CountDownLatch(1);
        ExecutorService http=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});server.setExecutor(http);
        server.createContext("/audio",ex->{requests.incrementAndGet();ex.getResponseHeaders().set("Content-Type","audio/wav");ex.sendResponseHeaders(200,audio.length);try(OutputStream out=ex.getResponseBody()){if(slow.get()){out.write(audio,0,1024);out.flush();streaming.countDown();try{Thread.sleep(500);}catch(InterruptedException ignored){}out.write(audio,1024,audio.length-1024);}else out.write(audio);}catch(IOException ignored){}});
        server.createContext("/html",ex->{byte[] b="<html>Error</html>".getBytes();ex.sendResponseHeaders(200,b.length);try(OutputStream out=ex.getResponseBody()){out.write(b);}});
        server.createContext("/error",ex->{ex.sendResponseHeaders(503,-1);ex.close();});
        server.createContext("/unknown",ex->{ex.sendResponseHeaders(200,0);try(OutputStream out=ex.getResponseBody()){out.write(audio);}catch(IOException ignored){}});
        server.createContext("/short",ex->{ex.sendResponseHeaders(200,audio.length+100);try(OutputStream out=ex.getResponseBody()){out.write(audio);}catch(IOException ignored){}});
        server.start();String url="http://127.0.0.1:"+server.getAddress().getPort();AtomicReference<String> path=new AtomicReference<>("/audio");AtomicInteger resolves=new AtomicInteger();
        OfflineCache.Resolver resolver=(t,q,alive)->{resolves.incrementAndGet();return new JSONObject().put("address",url+path.get()).put("quality","standard").put("format","wav");};
        OfflineCache c=new OfflineCache(new File(base,"normal"),resolver,wifi::get,listener);
        try{
            lyricRetryChecks(base,resolver,wifi);
            check(c.settings().optBoolean("automatic")&&c.settings().optBoolean("onlyWifi")&&c.settings().optLong("limitBytes")==1073741824L,"defaults");
            String k=c.enqueue(track("1"),"standard",true);await(c,k,"ready");check(c.lookup(track("1"),"lossless")!=null,"offline quality fallback");
            c.lyrics(new JSONObject().put("key",k).put("original","[00:00]Original").put("translation","[00:00]Translation").put("originalState","present").put("translationState","present").put("lyricLines",new JSONArray().put(new JSONObject().put("time",0).put("text","Original").put("translation","Translation"))));
            c.close();int before=resolves.get();c=new OfflineCache(new File(base,"normal"),(t,q,alive)->{throw new IOException("network forbidden");},wifi::get,listener);c.start();JSONObject hit=c.lookup(track("1"),"hires");check(hit!=null&&hit.optString("translation").contains("Translation"),"restart restores bilingual lyrics without network");check(resolves.get()==before,"offline lookup did not resolve");
            c.pin(k,false);c.action("remove",k);check(new File(hit.optString("path")).isFile()&&c.lookup(track("1"),"standard")==null,"active delete deferred but unavailable to new playback");c.pin("",false);check(!new File(hit.optString("path")).exists(),"deferred delete released");c.close();
            c=new OfflineCache(new File(base,"policy"),resolver,wifi::get,listener);wifi.set(false);k=c.enqueue(track("2"),"standard",false);Thread.sleep(100);check("queued".equals(c.entry(k).optString("status")),"automatic waits for wifi");String manual=c.enqueue(track("3"),"standard",true);await(c,manual,"ready");check(c.entry(manual).optBoolean("manual"),"manual works without wifi");wifi.set(true);c.networkChanged();await(c,k,"ready");
            c.settings(json("limitBytes",audio.length));String next=c.enqueue(track("4"),"standard",false);await(c,next,"ready");check(c.entry(k)==null&&c.entry(manual)!=null,"LRU only removes automatic");c.pin(next,false);String blocked=c.enqueue(track("5"),"standard",false);await(c,blocked,"failed");check(c.entry(next)!=null,"playing automatic protected against quota");c.pin("",false);c.settings(json("limitBytes",1));check(c.entry(next)==null&&c.entry(manual)!=null,"lowering quota trims immediately, manual survives");c.settings(json("limitBytes",audio.length*4));
            path.set("/html");String html=c.enqueue(track("6"),"standard",true);await(c,html,"failed");check(c.lookup(track("6"),"standard")==null,"HTML rejected");path.set("/error");String error=c.enqueue(track("7"),"standard",true);await(c,error,"failed");path.set("/short");String shortKey=c.enqueue(track("8"),"standard",true);await(c,shortKey,"failed");check(c.lookup(track("8"),"standard")==null,"truncated response rejected");path.set("/audio");
            slow.set(true);String cancelled=c.enqueue(track("9"),"standard",true);check(streaming.await(3,TimeUnit.SECONDS),"stream began");c.action("cancel",cancelled);Thread.sleep(700);check("cancelled".equals(c.entry(cancelled).optString("status")),"cancel cannot publish ready");check(!new File(base,"policy/"+cancelled+".part").exists(),"cancel cleans temporary file");slow.set(false);c.action("retry",cancelled);await(c,cancelled,"ready");check(c.lookup(track("9"),"standard")!=null,"retry after cancel");
            c.settings(json("limitBytes",audio.length*10));String requested=c.enqueue(track("10"),"lossless",true);String canonical=OfflineCache.key(track("10"),"standard");await(c,canonical,"ready");check(c.entry(requested)==null,"actual quality is canonical file identity");check(c.lookup(track("10"),"lossless").optString("quality").equals("standard"),"requested mapping retains actual quality");int count=requests.get();c.enqueue(track("10"),"standard",true);Thread.sleep(150);check(requests.get()==count,"actual/requested quality dedup avoids download");
            c.pin(canonical,false);c.action("remove",canonical);String savedAgain=c.enqueue(track("10"),"hires",true);long dedupDeadline=System.currentTimeMillis()+3000;while(c.entry(savedAgain)!=null&&System.currentTimeMillis()<dedupDeadline)Thread.sleep(10);
            check(!c.entry(canonical).optBoolean("deletePending")&&c.lookup(track("10"),"hires")!=null,"saving a fallback quality revives a playing item pending deletion");c.pin("",false);
            JSONObject corrupted=c.lookup(track("10"),"standard");File f=new File(corrupted.optString("path"));try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.seek(100);out.write(77);}check(c.lookup(track("10"),"standard")==null,"same-size corruption revokes availability");
            c.action("invalidate",manual);check(c.lookup(track("3"),"standard")==null,"decoder failure invalidates cache");
            path.set("/unknown");c.settings(json("limitBytes",audio.length-1));String unknown=c.enqueue(track("13"),"standard",false);await(c,unknown,"failed");check(c.lookup(track("13"),"standard")==null,"unknown-length task stops at automatic quota");path.set("/audio");c.settings(json("limitBytes",audio.length*10));
            slow.set(true);String autoCancel=c.enqueue(track("14"),"standard",false);await(c,autoCancel,"downloading");c.settings(json("automatic",false));Thread.sleep(650);check("queued".equals(c.entry(autoCancel).optString("status")),"disabling automatic suspends its unfinished task");c.action("promote",autoCancel);await(c,autoCancel,"ready");check(c.entry(autoCancel).optBoolean("manual"),"promotion resumes as protected manual while auto off");slow.set(false);c.settings(json("automatic",true));
            slow.set(true);String wifiCancel=c.enqueue(track("15"),"standard",false);await(c,wifiCancel,"downloading");wifi.set(false);c.networkChanged();Thread.sleep(650);check("queued".equals(c.entry(wifiCancel).optString("status")),"wifi loss suspends active auto download");slow.set(false);wifi.set(true);c.networkChanged();await(c,wifiCancel,"ready");check(c.lookup(track("15"),"standard")!=null,"wifi restoration resumes task");
            c.close();
            // Removal while resolver is running must not resurrect the entry or an orphan file.
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);c=new OfflineCache(new File(base,"race"),(t,q,a)->{entered.countDown();release.await(3,TimeUnit.SECONDS);return resolver.resolve(t,q,a);},wifi::get,listener);k=c.enqueue(track("11"),"standard",true);entered.await(3,TimeUnit.SECONDS);c.action("remove",k);release.countDown();Thread.sleep(300);check(c.entries().length()==0,"removed resolver task stays removed");c.close();
            File lateFailure=new File(base,"late-persistence");slow.set(true);c=new OfflineCache(lateFailure,resolver,wifi::get,listener);k=c.enqueue(track("16"),"standard",true);await(c,k,"downloading");File lateIndex=new File(lateFailure,"index.json");check(lateIndex.delete()&&lateIndex.mkdir()&&new File(lateIndex,"keep").createNewFile(),"inject final index commit failure after download starts");await(c,k,"failed");check(c.lookup(track("16"),"standard")==null&&!new File(lateFailure,k+".audio").exists(),"final index commit failure cannot publish audio availability");c.close();slow.set(false);
            // Index commit failure cannot escape the service caller or advertise availability.
            File broken=new File(base,"broken");broken.mkdirs();new File(broken,"index.json").mkdir();new File(broken,"index.json/keep").createNewFile();c=new OfflineCache(broken,resolver,wifi::get,listener);k=c.enqueue(track("12"),"standard",true);await(c,k,"failed");check(!c.summary().optString("error").isEmpty()&&c.lookup(track("12"),"standard")==null,"failed persistence never publishes ready");
            c.close();c=new OfflineCache(new File(base,"audible-auto"),resolver,wifi::get,listener);c.playingTrack(track("17"));
            k=c.enqueue(track("17"),"standard",false);await(c,k,"ready");c.settings(json("limitBytes",1));
            check(c.entry(k)!=null,"currently audible online track remains protected when automatic cache completes");
            c.action("remove",k);check(c.entry(k).optBoolean("deletePending"),"online audible automatic cache deletion is deferred");
            c.playingTrack(null);c.pin("",false);check(c.entry(k)==null,"switching away releases automatic song protection");
            wifi.set(false);c.settings(json("limitBytes",audio.length*10));k=c.enqueue(track("18"),"standard",false);
            check("waitingWifi".equals(c.listing().getJSONObject(0).optString("status")),"management explains queued Wi-Fi restriction");
            System.out.println("OfflineCache: "+checks+" checks passed; isolated fixtures "+base);
        }finally{c.close();server.stop(0);http.shutdownNow();}
    }
}
