package org.floatmusic.player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Sole owner of managed files. All metadata transitions are serialized; IO uses its own worker. */
final class OfflineCache {
    interface Resolver { JSONObject resolve(JSONObject track,String quality,BooleanSupplier alive) throws Exception; }
    interface Listener { void changed(); void lyrics(String key,JSONObject track); }
    private final File root,index;
    private final Resolver resolver; private final Listener listener; private final BooleanSupplier wifi;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String,JSONObject> entries=new LinkedHashMap<>();
    private final Map<String,Long> revisions=new HashMap<>();
    private final Set<String> lyricAttempts=new HashSet<>();
    private boolean automatic=true,onlyWifi=true,closed=false,running=false;
    private long limit=1024L*1024*1024; private String active="",playing="",preparing="",playingIdentity="";
    private volatile HttpURLConnection connection;
    private String persistenceError="";
    OfflineCache(File root,Resolver resolver,BooleanSupplier wifi,Listener listener) {
        this.root=root;this.index=new File(root,"index.json");this.resolver=resolver;this.wifi=wifi;this.listener=listener;
        root.mkdirs();load();
    }
    static JSONObject copy(JSONObject object){try{return new JSONObject(object.toString());}catch(Exception e){return new JSONObject();}}
    static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception e){throw new IllegalArgumentException(e);}}
    static String key(JSONObject track,String quality){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((track.optString("source")+"\n"+track.optString("songId")+"\n"+quality).getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
    private File file(String key){if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid cache key");return new File(root,key+".audio");}
    synchronized void playingTrack(JSONObject track){playingIdentity=track==null?"":track.optString("source")+"\n"+track.optString("songId");}
    private boolean inUse(String key){JSONObject e=entries.get(key),t=e==null?null:e.optJSONObject("track");return key.equals(playing)||key.equals(preparing)||(t!=null&&!playingIdentity.isEmpty()&&playingIdentity.equals(t.optString("source")+"\n"+t.optString("songId")));}
    private void load(){
        try{JSONObject data=new JSONObject(new String(Files.readAllBytes(index.toPath()),StandardCharsets.UTF_8));JSONObject settings=data.optJSONObject("settings");if(settings!=null){automatic=settings.optBoolean("automatic",true);onlyWifi=settings.optBoolean("onlyWifi",true);limit=Math.max(1,settings.optLong("limitBytes",limit));}
            JSONArray items=data.optJSONArray("entries");if(items!=null)for(int i=0;i<items.length();i++){JSONObject e=items.optJSONObject(i);if(e==null||!e.optString("key").matches("[a-f0-9]{64}"))continue;String k=e.optString("key");if(e.optBoolean("deletePending")){file(k).delete();continue;}String state=e.optString("status");if("ready".equals(state)&&(!validFile(e,file(k)))){put(e,"status","failed");put(e,"error","缓存文件丢失或损坏");file(k).delete();}else if("ready".equals(state)&&!e.has("modifiedTime"))put(e,"modifiedTime",modifiedTime(file(k)));else if("downloading".equals(state)||"resolving".equals(state))put(e,"status","queued");entries.put(k,e);}
        }catch(Exception ignored){}
        File[] files=root.listFiles();if(files!=null)for(File f:files){String n=f.getName();if(n.endsWith(".part")||(n.endsWith(".audio")&&!entries.containsKey(n.substring(0,n.length()-6))))f.delete();}
    }
    synchronized void start(){pump();for(JSONObject e:entries.values())requestIncompleteLyrics(e);}
    synchronized JSONArray entries(){JSONArray a=new JSONArray();for(JSONObject e:entries.values())a.put(copy(e));return a;}
    /** Frequent service snapshots contain only the small fields used by cache management. */
    synchronized JSONArray listing(){JSONArray result=new JSONArray();boolean onWifi=!onlyWifi||wifi.getAsBoolean();for(JSONObject entry:entries.values()){JSONObject row=new JSONObject();for(String field:new String[]{"key","requestedQuality","quality","format","manual","status","bytes","totalBytes","originalState","translationState","error","deletePending","access"})if(entry.has(field))put(row,field,entry.opt(field));
        if("queued".equals(entry.optString("status"))&&!entry.optBoolean("manual")){if(!automatic)put(row,"status","paused");else if(!onWifi)put(row,"status","waitingWifi");}
        JSONObject track=entry.optJSONObject("track"),metadata=new JSONObject();if(track!=null)for(String field:new String[]{"id","source","songId","name","artist","album","duration","sourceName"})if(track.has(field))put(metadata,field,track.opt(field));put(row,"track",metadata);long total=entry.optLong("totalBytes",-1);put(row,"progress",total>0?Math.min(1.0,entry.optDouble("bytes")/total):-1);result.put(row);
    }return result;}
    synchronized JSONObject settings(){JSONObject o=new JSONObject();put(o,"automatic",automatic);put(o,"onlyWifi",onlyWifi);put(o,"limitBytes",limit);return o;}
    synchronized JSONObject summary(){JSONObject o=new JSONObject();long manual=0,auto=0;int count=0;for(JSONObject e:entries.values())if("ready".equals(e.optString("status"))){if(e.optBoolean("manual"))manual+=e.optLong("bytes");else auto+=e.optLong("bytes");count++;}put(o,"bytes",manual+auto);put(o,"manualBytes",manual);put(o,"automaticBytes",auto);put(o,"count",count);put(o,"error",persistenceError);return o;}
    private synchronized boolean save(){try{JSONObject o=new JSONObject();put(o,"settings",settings());put(o,"entries",entries());File temp=new File(root,"index.part");try(FileOutputStream out=new FileOutputStream(temp)){out.write(o.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),index.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);persistenceError="";return true;}catch(Exception e){persistenceError="缓存索引保存失败";return false;}}
    private void changed(){save();listener.changed();}
    synchronized String enqueue(JSONObject track,String quality,boolean manual){
        if(closed||track==null||!AudioResolver.validPlatformId(track.optString("source"),track.optString("songId")))return "";
        if(!manual&&!automatic)return "";String k=key(track,quality);JSONObject found=lookup(track,quality);if(found!=null&&matchesQuality(found,quality))k=found.optString("key");
        JSONObject e=entries.get(k);if(e==null){e=new JSONObject();put(e,"key",k);put(e,"track",copy(track));put(e,"requestedQuality",quality);put(e,"requestedQualities",new JSONArray().put(quality));put(e,"quality",quality);put(e,"originalState","unknown");put(e,"translationState","unknown");put(e,"status","queued");put(e,"manual",manual);entries.put(k,e);}else{if(manual)put(e,"manual",true);if("cancelled".equals(e.optString("status"))||"failed".equals(e.optString("status")))put(e,"status","queued");}
        put(e,"deletePending",false);changed();pump();return k;
    }
    synchronized JSONObject lookup(JSONObject track,String quality){JSONObject fallback=null;for(JSONObject e:entries.values()){JSONObject t=e.optJSONObject("track");if(t==null||!t.optString("source").equals(track.optString("source"))||!t.optString("songId").equals(track.optString("songId"))||e.optBoolean("deletePending")||!"ready".equals(e.optString("status")))continue;File f=file(e.optString("key"));if(!validFile(e,f)){put(e,"status","failed");put(e,"error","缓存文件丢失或损坏");continue;}if(matchesQuality(e,quality)){fallback=e;break;}if(fallback==null)fallback=e;}if(fallback==null){save();return null;}put(fallback,"access",System.currentTimeMillis());save();JSONObject result=copy(fallback);put(result,"path",file(fallback.optString("key")).getAbsolutePath());return result;}
    synchronized JSONObject lookupKey(String key){
        JSONObject e=entries.get(key);
        if(e==null||e.optBoolean("deletePending")||!"ready".equals(e.optString("status")))return null;
        if(!validFile(e,file(key))){put(e,"status","failed");put(e,"error","缓存文件丢失或损坏");changed();return null;}
        put(e,"access",System.currentTimeMillis());save();JSONObject result=copy(e);put(result,"path",file(key).getAbsolutePath());return result;
    }
    synchronized JSONObject entry(String key){JSONObject e=entries.get(key);return e==null?null:copy(e);}
    synchronized void settings(JSONObject patch){automatic=patch.optBoolean("automatic",automatic);onlyWifi=patch.optBoolean("onlyWifi",onlyWifi);if(patch.has("automaticEnabled"))automatic=patch.optBoolean("automaticEnabled");if(patch.has("wifiOnly"))onlyWifi=patch.optBoolean("wifiOnly");if(patch.has("automaticLimitBytes"))limit=Math.max(1,patch.optLong("automaticLimitBytes",limit));if(patch.has("limitBytes"))limit=Math.max(1,patch.optLong("limitBytes",limit));if(!automatic||onlyWifi&&!wifi.getAsBoolean())cancelActiveAuto();trim();changed();pump();}
    synchronized void networkChanged(){if(closed)return;if(onlyWifi&&!wifi.getAsBoolean()){cancelActiveAuto();save();}pump();}
    private void cancelActiveAuto(){JSONObject e=entries.get(active);if(e!=null&&!e.optBoolean("manual")&&(e.optString("status").equals("downloading")||e.optString("status").equals("resolving"))){invalidate(active);put(e,"status","queued");}}
    private void invalidate(String k){revisions.put(k,revisions.getOrDefault(k,0L)+1);if(k.equals(active)&&connection!=null)connection.disconnect();}
    synchronized void action(String action,String k){JSONObject e=entries.get(k);if(e==null)return;if("promote".equals(action)){put(e,"manual",true);}else if("retry".equals(action)){if(!"ready".equals(e.optString("status"))){invalidate(k);put(e,"status","queued");put(e,"error","");}}else if("lyrics".equals(action)){listener.lyrics(k,copy(e.optJSONObject("track")));}else if("cancel".equals(action)){invalidate(k);if(!"ready".equals(e.optString("status")))put(e,"status","cancelled");}else if("invalidate".equals(action)){invalidate(k);put(e,"status","failed");put(e,"error","缓存文件丢失或损坏");}else if("remove".equals(action)){invalidate(k);if(inUse(k))put(e,"deletePending",true);else{file(k).delete();entries.remove(k);}}changed();pump();}
    synchronized void clear(boolean all){for(String k:new ArrayList<>(entries.keySet()))if(all||!entries.get(k).optBoolean("manual"))action("remove",k);}
    synchronized void pin(String key,boolean pending){if(pending)preparing=key;else{playing=key;preparing="";}for(String k:new ArrayList<>(entries.keySet())){JSONObject e=entries.get(k);if(e.optBoolean("deletePending")&&!inUse(k)){file(k).delete();entries.remove(k);}}trim();save();}
    synchronized void lyrics(JSONObject payload){if(payload==null||closed)return;payload=copy(payload);if(payload.has("lyricState")&&!payload.has("originalState"))put(payload,"originalState",payload.opt("lyricState"));for(JSONObject e:entries.values()){JSONObject t=e.optJSONObject("track");if(!payload.optString("key").isEmpty()?!e.optString("key").equals(payload.optString("key")):t==null||!t.optString("source").equals(payload.optString("source"))||!t.optString("songId").equals(payload.optString("songId")))continue;
        boolean original=mergeLyricStream(e,payload,"original"),translation=mergeLyricStream(e,payload,"translation");
        if(payload.has("lyricLines")&&(original||translation))put(e,"lyricLines",mergeLyricLines(e.optJSONArray("lyricLines"),payload.optJSONArray("lyricLines"),original,translation));
    }changed();}
    private static boolean lyricKnown(String state){return "available".equals(state)||"present".equals(state)||"ready".equals(state)||"none".equals(state)||"absent".equals(state);}
    private static boolean mergeLyricStream(JSONObject entry,JSONObject payload,String field){String stateField=field+"State";if(!payload.has(field)&&!payload.has(stateField))return false;String text=payload.optString(field),state=payload.optString(stateField,"unknown");
        if(!text.isEmpty())state="available";else if("none".equals(state)||"absent".equals(state))state="none";else if(!"failed".equals(state))state="unknown";
        if(("failed".equals(state)||"unknown".equals(state))&&lyricKnown(entry.optString(stateField)))return false;
        put(entry,field,text);put(entry,stateField,state);return true;
    }
    private static JSONArray mergeLyricLines(JSONArray old,JSONArray incoming,boolean original,boolean translation){if(original&&translation)return incoming==null?new JSONArray():incoming;
        TreeMap<Long,JSONObject> rows=new TreeMap<>();for(String field:new String[]{"original","translation"}){JSONArray selected=("original".equals(field)?original:translation)?incoming:old;if(selected==null)continue;for(int i=0;i<selected.length();i++){JSONObject line=selected.optJSONObject(i);if(line==null)continue;long time=line.optLong("time");String text=line.optString(field);if(text.isEmpty())continue;JSONObject row=rows.get(time);if(row==null){row=new JSONObject();put(row,"time",time);put(row,"original","");put(row,"translation","");rows.put(time,row);}put(row,field,text);}}
        JSONArray result=new JSONArray();for(JSONObject row:rows.values())result.put(row);return result;
    }
    private void requestIncompleteLyrics(JSONObject entry){if(!"ready".equals(entry.optString("status"))||entry.optBoolean("deletePending")||lyricKnown(entry.optString("originalState"))&&lyricKnown(entry.optString("translationState")))return;String key=entry.optString("key");if(lyricAttempts.add(key))listener.lyrics(key,copy(entry.optJSONObject("track")));}
    private synchronized boolean alive(String k,long revision){JSONObject e=entries.get(k);return !closed&&e!=null&&!e.optBoolean("deletePending")&&revisions.getOrDefault(k,0L)==revision&&(e.optBoolean("manual")||automatic&&(!onlyWifi||wifi.getAsBoolean()));}
    private synchronized void pump(){if(closed||running)return;for(JSONObject e:entries.values())if("queued".equals(e.optString("status"))&&(e.optBoolean("manual")||automatic&&(!onlyWifi||wifi.getAsBoolean()))){String k=e.optString("key");long revision=revisions.getOrDefault(k,0L);active=k;running=true;worker.execute(()->download(k,revision));return;}}
    private synchronized void capacity(String key,long bytes)throws IOException{JSONObject task=entries.get(key);if(task==null)throw new IOException("缓存已取消");if(task.optBoolean("manual"))return;if(bytes>limit)throw new IOException("自动缓存容量不足");long used=0;List<JSONObject> victims=new ArrayList<>();for(JSONObject e:entries.values())if(!e.optString("key").equals(key)&&!e.optBoolean("manual")&&"ready".equals(e.optString("status"))){used+=e.optLong("bytes");if(!inUse(e.optString("key")))victims.add(e);}victims.sort(Comparator.comparingLong(e->e.optLong("access")));for(JSONObject e:victims){if(used+bytes<=limit)break;String k=e.optString("key");if(file(k).delete()){used-=e.optLong("bytes");entries.remove(k);}}if(used+bytes>limit)throw new IOException("自动缓存容量不足");}
    private void download(String key,long revision){File part=new File(root,key+".part");try{
        JSONObject task; synchronized(this){if(!alive(key,revision))return;task=copy(entries.get(key));put(entries.get(key),"status","resolving");if(!save())throw new IOException(persistenceError);listener.changed();}
        JSONObject resolved=resolver.resolve(task.getJSONObject("track"),task.optString("requestedQuality"),()->alive(key,revision));if(!alive(key,revision))throw new IOException("缓存已取消");
        synchronized(this){
            if(!alive(key,revision))throw new IOException("缓存已取消");
            JSONObject taskEntry=entries.get(key);String canonical=key(task.getJSONObject("track"),resolved.optString("quality"));JSONObject existing=entries.get(canonical);
            if(existing!=null&&existing!=taskEntry&&"ready".equals(existing.optString("status"))&&validFile(existing,file(canonical))){mergeRequests(existing,taskEntry);put(existing,"deletePending",false);if(taskEntry.optBoolean("manual"))put(existing,"manual",true);entries.remove(key);if(!save()){entries.put(key,taskEntry);throw new IOException(persistenceError);}listener.changed();return;}
        }
        String address=resolved.getString("address");HttpURLConnection conn=null;
        for(int redirect=0;redirect<6;redirect++){URL url=new URL(address);if(!"https".equals(url.getProtocol())&&!"http".equals(url.getProtocol())||url.getUserInfo()!=null)throw new IOException("下载地址无效");conn=(HttpURLConnection)url.openConnection();connection=conn;conn.setConnectTimeout(6000);conn.setReadTimeout(6000);conn.setInstanceFollowRedirects(false);conn.setRequestProperty("Accept-Encoding","identity");int code=conn.getResponseCode();if(code>=300&&code<400){String next=AudioResolver.redirectAddress(address,conn.getHeaderField("Location"),true);conn.disconnect();address=next;continue;}if(code!=200)throw new IOException("下载失败 HTTP "+code);break;}
        if(conn==null||conn.getResponseCode()!=200)throw new IOException("下载重定向过多");long total=conn.getContentLengthLong();String type=conn.getContentType();if(type!=null&&(type.contains("text/")||type.contains("json")))throw new IOException("返回内容不是音频");
        synchronized(this){if(!alive(key,revision))throw new IOException("缓存已取消");capacity(key,Math.max(0,total));JSONObject e=entries.get(key);for(String field:new String[]{"quality","format","bitrate","sampleRate","bitsPerSample","duration","expectedDuration"})put(e,field,resolved.opt(field));put(e,"status","downloading");put(e,"totalBytes",total);put(e,"bytes",0);changed();}
        long bytes=0,last=0;byte[] buffer=new byte[65536];try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(part)){int n;while((n=in.read(buffer))!=-1){if(!alive(key,revision))throw new IOException("缓存已取消");bytes+=n;synchronized(this){if(!alive(key,revision))throw new IOException("缓存已取消");capacity(key,bytes);}if(root.getUsableSpace()<n+1024*1024)throw new IOException("存储空间不足");out.write(buffer,0,n);if(System.currentTimeMillis()-last>400){synchronized(this){if(!alive(key,revision))throw new IOException("缓存已取消");put(entries.get(key),"bytes",bytes);listener.changed();}last=System.currentTimeMillis();}}out.getFD().sync();}
        if(bytes==0||total>=0&&bytes!=total||!media(part))throw new IOException("音频不完整或格式无效");
        String completedDigest=digest(part);
        synchronized(this){if(!alive(key,revision))throw new IOException("缓存已取消");capacity(key,bytes);JSONObject e=entries.get(key);String canonical=key(e.getJSONObject("track"),e.optString("quality"));JSONObject existing=entries.get(canonical);
            if(existing!=null&&existing!=e&&"ready".equals(existing.optString("status"))&&validFile(existing,file(canonical))){mergeRequests(existing,e);put(existing,"deletePending",false);if(e.optBoolean("manual"))put(existing,"manual",true);entries.remove(key);if(!save()){entries.put(key,e);throw new IOException(persistenceError);}listener.changed();return;}
            if(!canonical.equals(key)&&existing!=null){mergeRequests(e,existing);if(existing.optBoolean("manual"))put(e,"manual",true);invalidate(canonical);}
            put(e,"digest",completedDigest);Files.move(part.toPath(),file(canonical).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);put(e,"modifiedTime",modifiedTime(file(canonical)));put(e,"key",canonical);put(e,"bytes",bytes);put(e,"status","ready");put(e,"error","");put(e,"access",System.currentTimeMillis());entries.remove(key);entries.put(canonical,e);
            if(!save()){put(e,"status","failed");put(e,"error",persistenceError);file(canonical).delete();listener.changed();return;}listener.changed();requestIncompleteLyrics(e);}
    }catch(Exception ex){synchronized(this){JSONObject e=entries.get(key);if(e!=null&&revisions.getOrDefault(key,0L)==revision){put(e,"status",closed?"queued":"failed");put(e,"error",ex.getMessage());try{changed();}catch(Exception ignored){}}}}
        finally{part.delete();HttpURLConnection c=connection;if(c!=null)c.disconnect();connection=null;synchronized(this){active="";running=false;pump();}}
    }
    private static boolean matchesQuality(JSONObject e,String q){if(q.equals(e.optString("quality"))||q.equals(e.optString("requestedQuality")))return true;JSONArray a=e.optJSONArray("requestedQualities");if(a!=null)for(int i=0;i<a.length();i++)if(q.equals(a.optString(i)))return true;return false;}
    private static void mergeRequests(JSONObject target,JSONObject source){JSONArray a=target.optJSONArray("requestedQualities");if(a==null)a=new JSONArray().put(target.optString("requestedQuality"));JSONArray b=source.optJSONArray("requestedQualities");if(b==null)b=new JSONArray().put(source.optString("requestedQuality"));for(int i=0;i<b.length();i++){boolean exists=false;for(int j=0;j<a.length();j++)if(a.optString(j).equals(b.optString(i)))exists=true;if(!exists)a.put(b.optString(i));}put(target,"requestedQualities",a);}
    private static String digest(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)d.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte v:d.digest())out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();}
    private static long modifiedTime(File f)throws IOException{return Files.getLastModifiedTime(f.toPath()).to(TimeUnit.NANOSECONDS);}
    // Called from service startup/lookup: never hash full audio files on the main thread.
    // Managed files have one writer; unexpected rewrites revoke availability. Decoder errors
    // additionally call action("invalidate", key), including corruption with an unchanged stamp.
    private static boolean validFile(JSONObject e,File f){try{return f.isFile()&&f.length()==e.optLong("bytes")&&media(f)&&(!e.has("modifiedTime")||e.optLong("modifiedTime")==modifiedTime(f));}catch(Exception ex){return false;}}
    private void trim(){long used=0;List<JSONObject> victims=new ArrayList<>();for(JSONObject e:entries.values())if(!e.optBoolean("manual")&&"ready".equals(e.optString("status"))){used+=e.optLong("bytes");String k=e.optString("key");if(!inUse(k)&&!k.equals(active))victims.add(e);}victims.sort(Comparator.comparingLong(e->e.optLong("access")));for(JSONObject e:victims){if(used<=limit)break;String k=e.optString("key");if(file(k).delete()){used-=e.optLong("bytes");entries.remove(k);}}}
    static boolean media(File f){try(InputStream in=new FileInputStream(f)){byte[] b=new byte[32];int n=in.read(b);if(n<12)return false;String s=new String(b,StandardCharsets.ISO_8859_1);int a=b[0]&255,v=b[1]&255;return s.startsWith("ID3")||s.startsWith("fLaC")||s.startsWith("OggS")||s.startsWith("RIFF")&&s.substring(8).startsWith("WAVE")||s.substring(4).startsWith("ftyp")||a==255&&(v&0xe0)==0xe0;}catch(Exception e){return false;}}
    synchronized void close(){closed=true;if(!active.isEmpty()){JSONObject e=entries.get(active);if(e!=null&&(e.optString("status").equals("resolving")||e.optString("status").equals("downloading")))put(e,"status","queued");invalidate(active);}save();worker.shutdownNow();}
}
