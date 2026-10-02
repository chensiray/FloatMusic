package org.floatmusic.player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.json.JSONArray;

final class AudioResolver {
    static final class Result {
        String address,sourceId,sourceName;
        int bitrate;
        Result(String address,String id,String name,int bitrate) {this.address=address;sourceId=id;sourceName=name;this.bitrate=bitrate;}
    }
    private static final class Source {
        final String id,name,format,request;
        Source(String id,String name,String format,String request) {this.id=id;this.name=name;this.format=format;this.request=request;}
    }
    private static final class Response {
        final byte[] bytes; final String address;
        Response(byte[] bytes,String address){this.bytes=bytes;this.address=address;}
    }
    private final String gd,legacy,meting;
    private final int timeout,total;
    private final Map<String,Long> cooldown=new java.util.concurrent.ConcurrentHashMap<>();
    private final Deque<Long> gdRequests=new ArrayDeque<>();
    private volatile HttpURLConnection connection;
    AudioResolver() {this("https://music-api.gdstudio.xyz/api.php","https://www.byfuns.top/api/1/","https://api.injahow.cn/meting/",6000,24000);}
    AudioResolver(String gd,String legacy,String meting,int timeout,int total) {this.gd=gd;this.legacy=legacy;this.meting=meting;this.timeout=timeout;this.total=total;}
    private static long now(){return System.nanoTime()/1000000;}
    private static boolean valid(String address) {
        try {URI uri=new URI(address);return address.length()<=8192&&uri.getHost()!=null&&uri.getRawUserInfo()==null
            &&("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()));}
        catch(Exception e){return false;}
    }
    private static boolean starts(byte[] bytes,int offset,String magic) {
        if(bytes.length<offset+magic.length())return false;
        for(int i=0;i<magic.length();i++)if(bytes[offset+i]!=(byte)magic.charAt(i))return false;
        return true;
    }
    private static boolean audioHeader(byte[] bytes) {
        return starts(bytes,0,"fLaC")||starts(bytes,0,"ID3")||starts(bytes,0,"OggS")||starts(bytes,4,"ftyp")
            ||((starts(bytes,0,"RIFF")||starts(bytes,0,"RF64"))&&starts(bytes,8,"WAVE"))
            ||(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&0xe0)==0xe0);
    }
    private static void check(BooleanSupplier active,long deadline) throws IOException {
        if(!active.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("音源请求已取消");
        if(now()>=deadline)throw new SocketTimeoutException("连接超时");
    }
    private Response request(String address,boolean sample,long operationDeadline,BooleanSupplier active) throws IOException {
        long deadline=Math.min(operationDeadline,now()+timeout);
        for(int redirects=0;redirects<=5;redirects++) {
            check(active,deadline); if(!valid(address))throw new IOException("播放地址无效");
            HttpURLConnection current=(HttpURLConnection)URI.create(address).toURL().openConnection(); connection=current;
            try {
                current.setInstanceFollowRedirects(false);
                int remaining=(int)Math.max(1,deadline-now());
                current.setConnectTimeout(remaining);current.setReadTimeout(remaining);
                current.setRequestProperty("User-Agent","FloatMusic/0.8");
                if(sample)current.setRequestProperty("Range","bytes=0-63");
                int code=current.getResponseCode(); check(active,deadline);
                if(code==301||code==302||code==303||code==307||code==308) {
                    String location=current.getHeaderField("Location"),next;
                    try {if(location==null)throw new IllegalArgumentException();next=new URI(address).resolve(location).toASCIIString();}
                    catch(Exception e){throw new IOException("重定向地址无效");}
                    if(!valid(next)||("https".equalsIgnoreCase(URI.create(address).getScheme())&&!"https".equalsIgnoreCase(URI.create(next).getScheme())))
                        throw new IOException("重定向地址不安全");
                    address=next;continue;
                }
                if(code<200||code>=300)throw new IOException("HTTP "+code);
                try(InputStream input=current.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    int limit=sample?64:64*1024;
                    byte[] buffer=new byte[sample?64:4096];int count;
                    while(true) {
                        check(active,deadline); current.setReadTimeout((int)Math.max(1,deadline-now()));
                        count=input.read(buffer,0,Math.min(buffer.length,limit+1-output.size()));
                        if(count<0)break;output.write(buffer,0,count);
                        if(sample&&output.size()>=16)break;
                        if(output.size()>limit)throw new IOException("API 响应过大");
                    }
                    byte[] bytes=output.toByteArray();
                    if(sample&&!audioHeader(bytes))throw new IOException("地址已失效或返回的不是音频");
                    return new Response(bytes,address);
                }
            } finally {current.disconnect();if(connection==current)connection=null;}
        }
        throw new IOException("重定向次数过多");
    }
    private static String reason(Exception error) {
        if(error instanceof SocketTimeoutException)return "连接超时";
        if(error instanceof UnknownHostException)return "无法解析域名";
        if(error instanceof javax.net.ssl.SSLException)return "TLS 安全连接失败";
        // Never publish a provider response, signed URL or arbitrary networking exception.
        String text=error.getMessage();
        return text!=null&&(text.matches("HTTP [0-9]{3}")||text.equals("音源请求已取消")||text.equals("播放地址无效")
            ||text.equals("API 响应过大")||text.equals("地址已失效或返回的不是音频")||text.equals("重定向地址无效")
            ||text.equals("重定向地址不安全")||text.equals("重定向次数过多"))?text:"连接失败，请检查网络或系统代理";
    }
    Result resolve(String song,String quality,String api,Set<String> excluded,BooleanSupplier active) throws IOException {
        if(!song.matches("[1-9][0-9]{0,18}")||!Arrays.asList("standard","higher","exhigh","lossless","hires").contains(quality))throw new IOException("歌曲 ID 或音质无效");
        long deadline=now()+total;
        List<Source> sources=new ArrayList<>();
        if(!api.isEmpty())sources.add(new Source("custom","自定义服务","custom",api+"/song/url/v1?id="+song+"&level="+quality));
        else {
            String br="hires".equals(quality)?"999":"lossless".equals(quality)?"740":"exhigh".equals(quality)?"320":"higher".equals(quality)?"192":"128";
            if(!gd.isEmpty())sources.add(new Source("gd","GD 音乐台","gd",gd+"?types=url&source=netease&id="+song+"&br="+br));
            if(!legacy.isEmpty())sources.add(new Source("byfuns","原接口","plain",legacy+"?id="+song+"&level="+quality));
            if(!meting.isEmpty())sources.add(new Source("injahow","INJAHOW","meting",meting+"?server=netease&type=song&id="+song));
        }
        List<String> failures=new ArrayList<>();
        for(Source source:sources) {
            check(active,deadline); if(excluded.contains(source.id))continue;
            if(cooldown.getOrDefault(source.id,0L)>now()){failures.add(source.name+"：暂不可用，稍后重试");continue;}
            if(source.id.equals("gd")) {
                long current=now();while(!gdRequests.isEmpty()&&gdRequests.peekFirst()<=current-300000)gdRequests.removeFirst();
                if(gdRequests.size()>=45){failures.add(source.name+"：访问较频繁，稍后重试");continue;}
                gdRequests.addLast(current);
            }
            Response response;
            try {response=request(source.request,false,deadline,active);}
            catch(IOException e) {cooldown.put(source.id,now()+30000);failures.add(source.name+"："+reason(e));continue;}
            String address="";int bitrate=0;
            try {
                String body=new String(response.bytes,StandardCharsets.UTF_8).trim();
                if(source.format.equals("plain"))address=body;
                else if(source.format.equals("gd")) {JSONObject json=new JSONObject(body);address=json.optString("url","");bitrate=json.optInt("br");}
                else if(source.format.equals("meting")) {JSONArray array=new JSONArray(body);JSONObject songData=array.optJSONObject(0);if(songData!=null)address=songData.optString("url","");}
                else {JSONObject json=new JSONObject(body);if(json.optInt("code")==200){JSONArray data=json.optJSONArray("data");JSONObject item=data==null?null:data.optJSONObject(0);if(item!=null)address=item.optString("url","");}}
            } catch(Exception ignored){}
            if(!valid(address)){failures.add(source.name+"：该歌曲或音质未返回有效地址");continue;}
            try {Response sample=request(address,true,deadline,active);check(active,deadline);return new Result(sample.address,source.id,source.name,bitrate);}
            catch(IOException e){failures.add(source.name+"："+reason(e));}
        }
        check(active,deadline);
        throw new IOException("暂未取得可播放音频。"+(failures.isEmpty()?"没有更多可用来源":String.join("；",failures))+"。请检查网络后重试或更换音质。");
    }
    void clearFailures() {cooldown.clear();}
    void cancel() {HttpURLConnection active=connection;if(active!=null)active.disconnect();}
}
