package org.floatmusic.player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.json.JSONArray;

final class AudioResolver {
    private static final int PROBE_LIMIT=16*1024;
    static final class Result {
        final String address,sourceId,sourceName,requestedQuality,quality,format,notice;
        final int bitrate,sampleRate,bitsPerSample;
        final double duration,expectedDuration;
        Result(String address,Source source,String requested,String quality,AudioInfo info,String notice,double expectedDuration) {
            this.address=address;sourceId=source.id;sourceName=source.name;requestedQuality=requested;
            this.quality=quality;format=info.format;bitrate=info.bitrate;sampleRate=info.sampleRate;
            bitsPerSample=info.bitsPerSample;duration=info.duration;this.notice=notice;this.expectedDuration=expectedDuration;
        }
    }
    private static final class Source {
        final String id,name,format,request,quality;
        Source(String id,String name,String format,String request,String quality) {
            this.id=id;this.name=name;this.format=format;this.request=request;this.quality=quality;
        }
    }
    private static final class Response {
        final byte[] bytes;final String address;final long totalBytes;
        Response(byte[] bytes,String address,long totalBytes){this.bytes=bytes;this.address=address;this.totalBytes=totalBytes;}
    }
    private static final class AudioInfo {
        String format="";int bitrate,sampleRate,bitsPerSample;double duration;boolean valid,lossless;
    }
    private static final class MpegFrame {
        int bitrate,sampleRate,size,samples,version,layer;boolean mono,crc;
    }
    private final String gd,legacy,meting,vkeys,ourcraft,kuwoMobi;
    private final int timeout,total;
    private final Map<String,Long> cooldown=new java.util.concurrent.ConcurrentHashMap<>();
    private final Deque<Long> gdRequests=new ArrayDeque<>();
    private volatile HttpURLConnection connection;
    private final AtomicInteger generation=new AtomicInteger();
    AudioResolver() {
        this("https://music-api.gdstudio.xyz/api.php","https://www.byfuns.top/api/1/","https://api.injahow.cn/meting/",
            "https://api.vkeys.cn","https://music.yuncan.xyz/api","https://mobi.kuwo.cn/mobi.s",6000,24000);
    }
    AudioResolver(String gd,String legacy,String meting,int timeout,int total){this(gd,legacy,meting,"","","",timeout,total);}
    AudioResolver(String gd,String legacy,String meting,String vkeys,String ourcraft,int timeout,int total){
        this(gd,legacy,meting,vkeys,ourcraft,"",timeout,total);
    }
    AudioResolver(String gd,String legacy,String meting,String vkeys,String ourcraft,String kuwoMobi,int timeout,int total) {
        this.gd=gd;this.legacy=legacy;this.meting=meting;this.vkeys=vkeys;this.ourcraft=ourcraft;this.kuwoMobi=kuwoMobi;
        this.timeout=Math.max(1,Math.min(6000,timeout));this.total=Math.max(1,Math.min(24000,total));
    }
    private static long now(){return System.nanoTime()/1000000;}
    private static boolean valid(String address) {
        try {
            URI uri=new URI(address);
            return address.length()<=8192&&uri.getHost()!=null&&uri.getRawUserInfo()==null
                &&("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()));
        } catch(Exception e){return false;}
    }
    static boolean validPlatformId(String source,String song) {
        return song!=null&&("tencent".equals(source)?song.matches("[A-Za-z0-9]{1,64}")
            :("netease".equals(source)||"kuwo".equals(source))&&song.matches("[1-9][0-9]{0,18}"));
    }
    static boolean validQuality(String quality){return Arrays.asList("standard","higher","exhigh","lossless","hires").contains(quality);}
    static boolean validQuality(String source,String quality) {
        return "netease".equals(source)?validQuality(quality):"tencent".equals(source)
            ?Arrays.asList("standard","exhigh","lossless","master").contains(quality)
            :"kuwo".equals(source)&&Arrays.asList("standard","exhigh","lossless").contains(quality);
    }
    static int qualityRank(String quality) {
        return "master".equals(quality)?5:"hires".equals(quality)?4:"lossless".equals(quality)?3:
            "exhigh".equals(quality)?2:"higher".equals(quality)?1:0;
    }
    static String qualityName(String quality) {
        return "master".equals(quality)?"实验高规格":"hires".equals(quality)?"Hi-Res":"lossless".equals(quality)?"无损":
            "exhigh".equals(quality)?"高码率":"higher".equals(quality)?"较高音质":"普通音质";
    }
    private static boolean knownCdn(URI uri) {
        return uri.getRawUserInfo()==null&&uri.getPort()==-1&&uri.getHost()!=null
            &&Arrays.asList("aqqmusic.tc.qq.com","ws.stream.qqmusic.qq.com","isure.stream.qqmusic.qq.com",
                "kw-er.kuwo.cn","other-er.kuwo.cn").contains(uri.getHost().toLowerCase(Locale.ROOT));
    }
    private static String secureCdnAddress(String address) {
        if(valid(address)) {
            URI uri=URI.create(address);
            if("http".equalsIgnoreCase(uri.getScheme())&&knownCdn(uri))return "https"+address.substring(address.indexOf(':'));
        }
        return address;
    }
    static String redirectAddress(String address,String location,boolean platformAudio) throws IOException {
        final String next;
        try {if(location==null)throw new IllegalArgumentException();next=new URI(address).resolve(location).toASCIIString();}
        catch(Exception e){throw new IOException("重定向地址无效");}
        if(!valid(next))throw new IOException("重定向地址不安全");
        URI current=URI.create(address),target=URI.create(next);
        if("https".equalsIgnoreCase(current.getScheme())&&!"https".equalsIgnoreCase(target.getScheme())) {
            if(platformAudio&&"http".equalsIgnoreCase(target.getScheme())&&knownCdn(target))
                return "https"+next.substring(next.indexOf(':'));
            throw new IOException("重定向地址不安全");
        }
        return next;
    }
    private static boolean starts(byte[] bytes,int offset,String magic) {
        if(offset<0||bytes.length-offset<magic.length())return false;
        for(int i=0;i<magic.length();i++)if(bytes[offset+i]!=(byte)magic.charAt(i))return false;return true;
    }
    private static long integer(byte[] bytes,int offset,int size,boolean little) {
        long value=0;
        for(int i=0;i<size;i++)value=(value<<8)|(bytes[offset+(little?size-1-i:i)]&255);return value;
    }
    private static AudioInfo flacInfo(byte[] bytes) {
        AudioInfo info=new AudioInfo();
        if(bytes.length<42||!starts(bytes,0,"fLaC")||(bytes[4]&127)!=0||integer(bytes,5,3,false)!=34)return info;
        int min=(int)integer(bytes,8,2,false),max=(int)integer(bytes,10,2,false);
        long packed=integer(bytes,18,8,false);
        info.sampleRate=(int)(packed>>>44);info.bitsPerSample=(int)((packed>>>36)&31)+1;
        if(min<16||max<min||info.sampleRate<=0||info.sampleRate>655350||info.bitsPerSample<4||info.bitsPerSample>32)return new AudioInfo();
        info.valid=info.lossless=true;info.format="FLAC";
        long samples=packed&((1L<<36)-1);if(samples>0)info.duration=(double)samples/info.sampleRate;return info;
    }
    private static MpegFrame mpegFrame(byte[] bytes,int offset) {
        MpegFrame frame=new MpegFrame();if(offset<0||offset+4>bytes.length)return frame;
        int a=bytes[offset]&255,b=bytes[offset+1]&255,c=bytes[offset+2]&255;
        frame.version=(b>>3)&3;frame.layer=(b>>1)&3;int br=c>>4,rate=(c>>2)&3;
        if(a!=255||(b&224)!=224||frame.version==1||frame.layer==0||br==0||br==15||rate==3)return new MpegFrame();
        final int[] rates={44100,48000,32000};
        final int[][] mpeg1={{32,64,96,128,160,192,224,256,288,320,352,384,416,448},
            {32,48,56,64,80,96,112,128,160,192,224,256,320,384},{32,40,48,56,64,80,96,112,128,160,192,224,256,320}};
        final int[] layer1={32,48,56,64,80,96,112,128,144,160,176,192,224,256},mpeg2={8,16,24,32,40,48,56,64,80,96,112,128,144,160};
        frame.sampleRate=rates[rate]/(frame.version==3?1:frame.version==2?2:4);
        frame.bitrate=frame.version==3?mpeg1[3-frame.layer][br-1]:frame.layer==3?layer1[br-1]:mpeg2[br-1];
        int padding=(c>>1)&1;
        frame.samples=frame.layer==3?384:frame.layer==1&&frame.version!=3?576:1152;
        frame.size=frame.layer==3?(12*frame.bitrate*1000/frame.sampleRate+padding)*4
            :(frame.layer==1&&frame.version!=3?72:144)*frame.bitrate*1000/frame.sampleRate+padding;
        frame.mono=((bytes[offset+3]&255)>>6)==3;frame.crc=(b&1)==0;return frame;
    }
    private static AudioInfo mpegInfo(byte[] bytes) {
        int start=0;
        if(starts(bytes,0,"ID3")) {
            if(bytes.length<10)return new AudioInfo();int tag=0;
            for(int i=6;i<10;i++){if((bytes[i]&128)!=0)return new AudioInfo();tag=(tag<<7)|(bytes[i]&255);}
            start=10+tag+(((bytes[3]&255)==4&&(bytes[5]&16)!=0)?10:0);
        }
        for(int offset=start;offset+4<=bytes.length;offset++) {
            MpegFrame frame=mpegFrame(bytes,offset);
            if(frame.size==0||offset+frame.size>bytes.length)continue;
            if(offset+frame.size+4<=bytes.length) {
                MpegFrame next=mpegFrame(bytes,offset+frame.size);
                if(next.size==0||next.version!=frame.version||next.layer!=frame.layer||next.sampleRate!=frame.sampleRate)continue;
            }
            AudioInfo info=new AudioInfo();info.valid=true;info.format=frame.layer==1?"MP3":frame.layer==2?"MP2":"MP1";
            info.sampleRate=frame.sampleRate;info.bitrate=frame.bitrate;
            int xing=offset+4+(frame.crc?2:0)+(frame.version==3?(frame.mono?17:32):(frame.mono?9:17)),end=offset+frame.size;
            if(frame.layer==1&&xing+12<=end&&(starts(bytes,xing,"Xing")||starts(bytes,xing,"Info"))) {
                long flags=integer(bytes,xing+4,4,false);int field=xing+8;
                if((flags&1)!=0&&field+4<=end){info.duration=(double)integer(bytes,field,4,false)*frame.samples/frame.sampleRate;field+=4;}
                if((flags&2)!=0&&field+4<=end&&info.duration>0)info.bitrate=(int)Math.round(integer(bytes,field,4,false)*8/info.duration/1000);
            }
            return info;
        }
        return new AudioInfo();
    }
    private static AudioInfo oggInfo(byte[] bytes) {
        if(bytes.length<28||!starts(bytes,0,"OggS")||bytes[4]!=0)return new AudioInfo();
        int segments=bytes[26]&255;if(segments==0||27+segments>bytes.length)return new AudioInfo();
        int length=0;boolean complete=false;
        for(int i=0;i<segments;i++){int size=bytes[27+i]&255;length+=size;if(size<255){complete=true;break;}}
        int offset=27+segments;if(!complete||offset+length>bytes.length)return new AudioInfo();
        byte[] packet=Arrays.copyOfRange(bytes,offset,offset+length);
        if(packet.length>=9&&(packet[0]&255)==127&&starts(packet,1,"FLAC"))return flacInfo(Arrays.copyOfRange(packet,9,packet.length));
        if(packet.length<30||packet[0]!=1||!starts(packet,1,"vorbis")||integer(packet,7,4,true)!=0
            ||packet[11]==0||(packet[29]&1)==0)return new AudioInfo();
        AudioInfo info=new AudioInfo();long rate=integer(packet,12,4,true);
        if(rate<=0||rate>768000)return info;
        info.valid=true;info.format="Vorbis";info.sampleRate=(int)rate;int nominal=(int)integer(packet,20,4,true);
        if(nominal>0)info.bitrate=(int)Math.round(nominal/1000.0);return info;
    }
    private static AudioInfo wavInfo(byte[] bytes) {
        AudioInfo info=new AudioInfo();
        if(bytes.length<12||!(starts(bytes,0,"RIFF")||starts(bytes,0,"RF64"))||!starts(bytes,8,"WAVE"))return info;
        info.valid=true;info.format="WAV";
        for(int offset=12;offset+8<=bytes.length;) {
            long length=integer(bytes,offset+4,4,true);
            if(length>bytes.length-offset-8)break;
            if(starts(bytes,offset,"fmt ")&&length>=16) {
                int codec=(int)integer(bytes,offset+8,2,true),bits=(int)integer(bytes,offset+22,2,true);
                long rate=integer(bytes,offset+12,4,true),byteRate=integer(bytes,offset+16,4,true);
                if((codec==1||codec==3)&&rate>0&&rate<=768000&&bits>0&&bits<=64) {
                    info.sampleRate=(int)rate;info.bitsPerSample=bits;info.lossless=true;info.bitrate=(int)Math.round(byteRate*8/1000.0);
                }
                break;
            }
            offset+=8+(int)length+((int)length&1);
        }
        return info;
    }
    private static int indexOf(byte[] bytes,String value,int start) {
        for(int i=Math.max(0,start);i+value.length()<=bytes.length;i++)if(starts(bytes,i,value))return i;return -1;
    }
    private static AudioInfo aacInfo(byte[] bytes) {
        AudioInfo info=new AudioInfo();
        if(bytes.length>=7&&(bytes[0]&255)==255&&(bytes[1]&246)==240) {
            final int[] rates={96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
            int index=((bytes[2]&255)>>2)&15,size=((bytes[3]&3)<<11)|((bytes[4]&255)<<3)|((bytes[5]&255)>>5);
            if(index>=13||size<7||size>bytes.length)return info;
            info.sampleRate=rates[index];info.format="AAC";info.valid=true;
            info.bitrate=(int)Math.round((double)size*8*info.sampleRate/(1024*((bytes[6]&3)+1))/1000);return info;
        }
        if(bytes.length<8||!starts(bytes,4,"ftyp"))return info;
        for(int type=indexOf(bytes,"mp4a",0);type>=4;type=indexOf(bytes,"mp4a",type+4)) {
            int start=type-4;long size=integer(bytes,start,4,false);
            if(size<36||start+36>bytes.length)continue;int end=(int)Math.min(bytes.length,start+size);
            int rate=(int)(integer(bytes,start+32,4,false)>>16);if(rate<=0)continue;
            int esds=indexOf(bytes,"esds",start+36);if(esds<0||esds+4>=end)continue;
            for(int descriptor=esds+8;descriptor+2<end;descriptor++) {
                if(bytes[descriptor]!=4)continue;int payload=descriptor+1;long length=0;boolean complete=false;
                for(int i=0;i<4&&payload<end;i++){int b=bytes[payload++]&255;length=(length<<7)|(b&127);if((b&128)==0){complete=true;break;}}
                if(!complete||length<13||length>end-payload)continue;int object=bytes[payload]&255;
                if(!(object==64||(object>=102&&object<=104))||((bytes[payload+1]&255)>>2)!=5)continue;
                info.valid=true;info.format="AAC";info.sampleRate=rate;info.bitrate=(int)Math.round(integer(bytes,payload+9,4,false)/1000.0);return info;
            }
        }
        return info;
    }
    private static AudioInfo inspectAudio(byte[] bytes,long totalBytes) {
        AudioInfo info;
        if(starts(bytes,0,"fLaC"))info=flacInfo(bytes);
        else if(starts(bytes,0,"OggS"))info=oggInfo(bytes);
        else if(starts(bytes,0,"RIFF")||starts(bytes,0,"RF64"))info=wavInfo(bytes);
        else{info=aacInfo(bytes);if(!info.valid)info=mpegInfo(bytes);}
        if(info.valid&&info.lossless&&info.duration>0&&totalBytes>bytes.length)
            info.bitrate=(int)Math.min(Integer.MAX_VALUE,Math.round(totalBytes*8.0/info.duration/1000));
        return info;
    }
    private static boolean unprobedContainer(byte[] bytes,long totalBytes) {
        if(bytes.length>=10&&starts(bytes,0,"ID3")) {
            int version=bytes[3]&255;if(version<2||version>4||(bytes[4]&255)==255)return false;
            int allowed=version==2?192:version==3?224:240;if(((bytes[5]&255)&~allowed)!=0)return false;int size=0;
            for(int i=6;i<10;i++){if((bytes[i]&128)!=0)return false;size=(size<<7)|(bytes[i]&255);}
            long end=10L+size+(version==4&&(bytes[5]&16)!=0?10:0);
            return totalBytes>0?totalBytes>end:bytes.length==PROBE_LIMIT&&end>=bytes.length;
        }
        if(bytes.length<16||!starts(bytes,4,"ftyp"))return false;
        long size=integer(bytes,0,4,false);int header=8;
        if(size==1){size=integer(bytes,8,8,false);header=16;}
        if(size<header+8||size>bytes.length||((size-header)&3)!=0)return false;
        for(int i=header;i<header+4;i++)if((bytes[i]&255)<32||(bytes[i]&255)>126)return false;
        return totalBytes>0?totalBytes>size:bytes.length>size;
    }
    private static String actualQuality(AudioInfo info,String platform,String routeQuality) {
        if(info.lossless) {
            if("tencent".equals(platform)&&"master".equals(routeQuality)&&info.bitsPerSample>=24&&info.sampleRate>=96000)return "master";
            if("netease".equals(platform)&&(info.bitsPerSample>16||info.sampleRate>48000))return "hires";
            return "lossless";
        }
        if(info.bitrate>=256)return "exhigh";
        if("netease".equals(platform)&&info.bitrate>=160)return "higher";return "standard";
    }
    private static void check(BooleanSupplier active,long deadline) throws IOException {
        if(!active.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("音源请求已取消");
        if(now()>=deadline)throw new SocketTimeoutException("连接超时");
    }
    private static long totalBytes(HttpURLConnection connection) {
        String range=connection.getHeaderField("Content-Range");
        if(range!=null) {
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("^bytes\\s+[0-9]+-[0-9]+/([0-9]+)$").matcher(range);
            if(match.matches())try{return Long.parseLong(match.group(1));}catch(NumberFormatException ignored){}
        }
        try{return Math.max(0,Long.parseLong(connection.getHeaderField("Content-Length")));}catch(Exception ignored){return 0;}
    }
    private Response request(String address,boolean sample,long operationDeadline,BooleanSupplier active,boolean platformAudio) throws IOException {
        long deadline=Math.min(operationDeadline,now()+timeout);
        for(int redirects=0;redirects<=5;redirects++) {
            check(active,deadline);if(!valid(address))throw new IOException("播放地址无效");
            HttpURLConnection current=(HttpURLConnection)URI.create(address).toURL().openConnection();connection=current;
            try {
                current.setInstanceFollowRedirects(false);int remaining=(int)Math.max(1,deadline-now());
                current.setConnectTimeout(remaining);current.setReadTimeout(remaining);current.setRequestProperty("User-Agent","FloatMusic/1.1");
                if(sample)current.setRequestProperty("Range","bytes=0-16383");
                int code=current.getResponseCode();check(active,deadline);
                if(code==301||code==302||code==303||code==307||code==308){address=redirectAddress(address,current.getHeaderField("Location"),sample&&platformAudio);continue;}
                if(code<200||code>=300)throw new IOException("HTTP "+code);
                long size=sample?totalBytes(current):0;
                try(InputStream input=current.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    int limit=sample?PROBE_LIMIT:64*1024;byte[] buffer=new byte[4096];
                    while(true) {
                        check(active,deadline);current.setReadTimeout((int)Math.max(1,deadline-now()));
                        int count=input.read(buffer,0,Math.min(buffer.length,limit+(sample?0:1)-output.size()));
                        if(count<0)break;output.write(buffer,0,count);
                        if(sample&&output.size()>=limit)break;if(output.size()>limit)throw new IOException("API 响应过大");
                    }
                    return new Response(output.toByteArray(),address,size);
                }
            } finally{current.disconnect();if(connection==current)connection=null;}
        }
        throw new IOException("重定向次数过多");
    }
    private static String reason(Exception error) {
        if(error instanceof SocketTimeoutException)return "连接超时";
        if(error instanceof UnknownHostException)return "无法解析域名";
        if(error instanceof javax.net.ssl.SSLException)return "TLS 安全连接失败";
        // Provider bodies and signed addresses are never published as errors.
        String text=error.getMessage();
        return text!=null&&(text.matches("HTTP [0-9]{3}")||Arrays.asList("音源请求已取消","播放地址无效","API 响应过大",
            "地址已失效或返回的不是音频","重定向地址无效","重定向地址不安全","重定向次数过多").contains(text))
            ?text:"连接失败，请检查网络或系统代理";
    }
    private static boolean serviceFailure(Exception error) {
        if(error instanceof SocketTimeoutException||error instanceof UnknownHostException||error instanceof javax.net.ssl.SSLException
            ||error instanceof ConnectException)return true;
        String text=error.getMessage();
        if(text!=null&&text.matches("HTTP [0-9]{3}")){int code=Integer.parseInt(text.substring(5));return code==429||code>=500;}
        return false;
    }
    Result resolve(String song,String quality,String api,Set<String> excluded,BooleanSupplier active) throws IOException {
        return resolve("netease",song,quality,api,excluded,active,0);
    }
    boolean hasAudioBackups(String platform,String api) {
        if("tencent".equals(platform))return !vkeys.isEmpty()||!meting.isEmpty();
        if("kuwo".equals(platform))return !kuwoMobi.isEmpty()||!ourcraft.isEmpty();
        int count=(!gd.isEmpty()?1:0)+(!legacy.isEmpty()?1:0)+(!meting.isEmpty()?1:0);
        return "netease".equals(platform)&&api.isEmpty()&&count>1;
    }
    int fallbackLimit(String platform,String requestedQuality) {
        if("tencent".equals(platform))return "master".equals(requestedQuality)?5:"lossless".equals(requestedQuality)?4:"exhigh".equals(requestedQuality)?3:2;
        if("kuwo".equals(platform))return "lossless".equals(requestedQuality)?5:"exhigh".equals(requestedQuality)?4:3;
        return "netease".equals(platform)?3:0;
    }
    private static String proxyOrigin(String address) {
        try {
            String query=URI.create(address).getRawQuery();if(query==null)return "";
            for(String item:query.split("&")) {
                int equal=item.indexOf('=');if(equal<0)continue;
                if("url".equals(URLDecoder.decode(item.substring(0,equal).replace("+","%2B"),"UTF-8")))
                    return URLDecoder.decode(item.substring(equal+1).replace("+","%2B"),"UTF-8");
            }
        } catch(Exception ignored){}return "";
    }
    private boolean knownProxy(String address) {
        if(!valid(address))return false;
        try {
            URI supplied=URI.create(address),provider=URI.create(ourcraft);
            return supplied.getScheme().equalsIgnoreCase(provider.getScheme())&&supplied.getHost().equalsIgnoreCase(provider.getHost())
                &&supplied.getPort()==provider.getPort()&&"/proxy".equals(supplied.getPath());
        } catch(Exception e){return false;}
    }
    private static boolean numericCode(JSONObject object,String key,int expected) {
        Object value=object.opt(key);return value instanceof Number&&((Number)value).doubleValue()==expected;
    }
    private static String numericId(Object value) {
        if(value instanceof String)return (String)value;
        if(value instanceof Number) {
            Number number=(Number)value;
            if(number.doubleValue()==number.longValue())return Long.toString(number.longValue());
        }
        return "";
    }
    Result resolve(String platform,String song,String quality,String api,Set<String> excluded,BooleanSupplier active) throws IOException {
        return resolve(platform,song,quality,api,excluded,active,0);
    }
    Result resolve(String platform,String song,String quality,String api,Set<String> excluded,BooleanSupplier active,double targetDuration) throws IOException {
        if(!validPlatformId(platform,song)||!validQuality(platform,quality))throw new IOException("歌曲来源、ID 或音质无效");
        final int ticket=generation.incrementAndGet();final BooleanSupplier current=()->ticket==generation.get()&&active.getAsBoolean();
        final double expectedDuration=TrackState.durationSeconds(targetDuration);
        long deadline=now()+total;List<Source> sources=new ArrayList<>();
        if("tencent".equals(platform)) {
            List<String> levels=Arrays.asList("master","lossless","exhigh","standard");String[] codes={"14","10","8","4"};
            if(!vkeys.isEmpty())for(int i=levels.indexOf(quality);i<levels.size();i++) {
                String level=levels.get(i);
                sources.add(new Source("standard".equals(level)?"qq-vkeys":"qq-vkeys-"+level,"QQ 音乐 · 落月","vkeys",
                    vkeys+"/music/tencent/song/link?mid="+song+"&quality="+codes[i]+"&type=0",level));
            }
            if(!meting.isEmpty())sources.add(new Source("qq-injahow","QQ 音乐 · INJAHOW 普通音质","meting",meting+"?server=tencent&type=song&id="+song,"standard"));
        } else if("kuwo".equals(platform)) {
            List<String> levels=Arrays.asList("lossless","exhigh","standard");String[] codes={"2000kflac","320kmp3","128kmp3"};
            if(!kuwoMobi.isEmpty())for(int i=levels.indexOf(quality);i<levels.size();i++) {
                String level=levels.get(i);
                sources.add(new Source("standard".equals(level)?"kuwo-mobi":"kuwo-mobi-"+level,"酷我音乐 · mobi（实验）","kuwo-mobi",
                    kuwoMobi+"?f=web&source=jiakong&type=convert_url_with_sign&rid="+song+"&br="+codes[i],level));
            }
            if(!ourcraft.isEmpty()) {
                String request=ourcraft+"?server=kuwo&type=url&id="+song+"&json=1";
                sources.add(new Source("kuwo-origin","酷我音乐 · 原始音频","kuwo-origin",request,"standard"));
                sources.add(new Source("kuwo-proxy","酷我音乐 · 中转备用","kuwo-proxy",request,"standard"));
            }
        } else if(!api.isEmpty())sources.add(new Source("custom","自定义服务","custom",api+"/song/url/v1?id="+song+"&level="+quality,quality));
        else {
            String br="hires".equals(quality)?"999":"lossless".equals(quality)?"740":"exhigh".equals(quality)?"320":"higher".equals(quality)?"192":"128";
            if(!gd.isEmpty())sources.add(new Source("gd","GD 音乐台","gd",gd+"?types=url&source=netease&id="+song+"&br="+br,quality));
            if(!legacy.isEmpty())sources.add(new Source("byfuns","原接口","plain",legacy+"?id="+song+"&level="+quality,quality));
            if(!meting.isEmpty())sources.add(new Source("injahow","INJAHOW","meting",meting+"?server=netease&type=song&id="+song,"standard"));
        }
        List<String> failures=new ArrayList<>();
        for(Source source:sources) {
            check(current,deadline);if(excluded.contains(source.id))continue;
            if(cooldown.getOrDefault(source.id,0L)>now()){failures.add(source.name+"：暂不可用，稍后重试");continue;}
            if(source.id.equals("gd")) {
                synchronized(gdRequests) {
                    long time=now();while(!gdRequests.isEmpty()&&gdRequests.peekFirst()<=time-300000)gdRequests.removeFirst();
                    if(gdRequests.size()>=45){failures.add(source.name+"：访问较频繁，稍后重试");continue;}gdRequests.addLast(time);
                }
            }
            Response response;
            try{response=request(source.request,false,deadline,current,false);}
            catch(IOException e){check(current,deadline);if(serviceFailure(e))cooldown.put(source.id,now()+30000);failures.add(source.name+"："+reason(e));continue;}
            String address="";double providerDuration=0;boolean badIdentity=false,badDuration=false,shortUnknown=false;
            try {
                String body=new String(response.bytes,StandardCharsets.UTF_8).trim();
                if(source.format.equals("plain"))address=body;
                else if(source.format.equals("gd"))address=new JSONObject(body).optString("url","");
                else if(source.format.equals("meting")) {
                    JSONArray array=new JSONArray(body);JSONObject songData=array.optJSONObject(0);if(songData!=null)address=songData.optString("url","");
                } else if(source.format.equals("vkeys")) {
                    JSONObject json=new JSONObject(body),data=json.optJSONObject("data");
                    if(numericCode(json,"code",0)&&data!=null) {
                        if(song.equals(data.optString("songMID",""))) {
                            address=data.optString("url","");if(address.isEmpty())address=data.optString("link","");
                        } else failures.add(source.name+"：返回的歌曲 MID 不匹配，已拒绝");
                    }
                } else if(source.format.equals("kuwo-mobi")) {
                    JSONObject json=new JSONObject(body),data=json.optJSONObject("data");
                    if(numericCode(json,"code",200)&&data!=null) {
                        badIdentity=!song.equals(numericId(data.opt("rid")))||!numericCode(data,"type",0);
                        providerDuration=TrackState.durationSeconds(data.opt("duration"));
                        badDuration=providerDuration<=0||(expectedDuration>0&&!TrackState.durationMatches(providerDuration,expectedDuration));
                        shortUnknown=expectedDuration<=0&&providerDuration>0&&providerDuration<20;
                        if(!badIdentity&&!badDuration&&!shortUnknown)address=data.optString("url","");
                    }
                } else if(source.format.startsWith("kuwo-")) {
                    JSONObject json=new JSONObject(body);
                    if(Boolean.TRUE.equals(json.opt("ok"))) {
                        // Some older backups omit their ID. A supplied conflicting ID is never accepted.
                        if(json.has("id")&&!song.equals(numericId(json.opt("id"))))badIdentity=true;
                        String supplied=json.optString("url","");boolean wrapped=knownProxy(supplied);
                        if(source.format.equals("kuwo-proxy")){if(wrapped)address=supplied;}
                        else address=wrapped?proxyOrigin(supplied):supplied;
                    }
                } else {
                    JSONObject json=new JSONObject(body),item=null;JSONArray data=json.optJSONArray("data");
                    if(numericCode(json,"code",200)&&data!=null)item=data.optJSONObject(0);
                    if(item!=null)address=item.optString("url","");
                }
            } catch(Exception ignored){}
            if(badIdentity)throw new IOException("酷我返回的歌曲身份或类型不符，已拒绝该音频；请重试或尝试其他歌曲。");
            if(badDuration)throw new IOException("酷我返回无有效时长或时长与目标歌曲不符，已拒绝该音频；请重试或尝试其他歌曲。");
            if(shortUnknown)throw new IOException("酷我返回短音频但缺少目标时长，无法确认是否为完整歌曲，已拒绝该音频。");
            if("tencent".equals(platform)||"kuwo".equals(platform))address=secureCdnAddress(address);
            if(!valid(address)){failures.add(source.name+"：该歌曲或音质未返回有效地址");continue;}
            Response sample;
            try{sample=request(address,true,deadline,current,"tencent".equals(platform)||"kuwo".equals(platform));check(current,deadline);}
            catch(IOException e){check(current,deadline);if(serviceFailure(e))cooldown.put(source.id,now()+30000);failures.add(source.name+"："+reason(e));continue;}
            AudioInfo info=inspectAudio(sample.bytes,sample.totalBytes);
            if(!info.valid&&!unprobedContainer(sample.bytes,sample.totalBytes)){failures.add(source.name+"：地址已失效或返回的不是音频");continue;}
            double verifiedTarget=expectedDuration>0?expectedDuration:providerDuration;
            if(("tencent".equals(platform)||"kuwo".equals(platform))&&info.duration>0
                &&!TrackState.decodedDurationValid(platform,info.duration,verifiedTarget,expectedDuration>0))
                throw new IOException(TrackState.sourceName(platform)+"音频头时长与目标歌曲不符或缺少目标时长，已拒绝该音频；请重试或尝试其他歌曲。");
            String obtained=actualQuality(info,platform,source.quality);
            if((source.format.equals("vkeys")||source.format.equals("kuwo-mobi"))&&qualityRank(obtained)<qualityRank(source.quality)) {
                failures.add(source.name+"：请求"+qualityName(source.quality)+"，实际为"+qualityName(obtained)+"，继续尝试较低档位");continue;
            }
            String notice=qualityRank(obtained)<qualityRank(quality)?"所选"+qualityName(quality)+"暂未取得，已回退到"+qualityName(obtained)+"。":"";
            if(obtained.equals("master"))notice+="实验高规格 "+info.format+"；采样规格不能证明原始母带来源。";
            if(!info.valid)notice+="音频头只确认容器，规格待系统解码确认；按普通音质显示。";
            return new Result(sample.address,source,quality,obtained,info,notice,verifiedTarget);
        }
        check(current,deadline);
        throw new IOException("暂未取得可播放音频。"+(failures.isEmpty()?"没有更多可用来源":String.join("；",failures))+"。请检查网络后重试、更换音质或尝试其他歌曲。");
    }
    void clearFailures(){cooldown.clear();}
    void cancel(){generation.incrementAndGet();HttpURLConnection active=connection;if(active!=null)active.disconnect();}
}
