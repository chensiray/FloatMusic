package org.floatmusic.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.*;

/** Small, bounded, independent artwork loader. No work is done on the audio or UI thread. */
final class AlbumArt {
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(48),r->{Thread t=new Thread(r,"FloatMusic-Art");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardPolicy());
    private static final android.util.LruCache<String,Bitmap> memory=new android.util.LruCache<String,Bitmap>(4*1024*1024){
        protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    static void load(Context context,ImageView view,String raw){
        if(raw==null||raw.isEmpty())return;
        final String url=raw.startsWith("http://")?"https://"+raw.substring(7):raw;
        try{URI u=new URI(url);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)return;}catch(Exception invalid){return;}
        view.setTag(url);Bitmap cached=memory.get(url);if(cached!=null){view.setImageBitmap(cached);return;}
        final File dir=new File(context.getCacheDir(),"covers");
        worker.execute(()->{
            try{
                StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8")))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                File file=new File(dir,hash+".img");byte[] bytes=null;
                if(file.isFile()&&file.length()<=2*1024*1024)try(InputStream in=new FileInputStream(file)){bytes=read(in);}
                if(bytes==null){
                    URL target=new URL(url);HttpURLConnection connection=null;
                    try{
                        for(int redirects=0;redirects<4;redirects++){
                            connection=(HttpURLConnection)target.openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);
                            connection.setRequestProperty("User-Agent","FloatMusic/1.7");int status=connection.getResponseCode();
                            if(status>=300&&status<400){URL next=new URL(target,connection.getHeaderField("Location"));if(!"https".equals(next.getProtocol()))return;connection.disconnect();target=next;continue;}
                            if(status!=200)return;try(InputStream in=connection.getInputStream()){bytes=read(in);}break;
                        }
                    }finally{if(connection!=null)connection.disconnect();}
                    if(bytes==null)return;
                }
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0)return;
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(bounds.outWidth/options.inSampleSize>512||bounds.outHeight/options.inSampleSize>512)options.inSampleSize*=2;
                Bitmap art=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(art==null)return;memory.put(url,art);
                if(!file.exists()&&dir.isDirectory()||!file.exists()&&dir.mkdirs()){
                    File temporary=File.createTempFile("cover-",".tmp",dir);
                    try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);}temporary.renameTo(file);}finally{temporary.delete();}
                    File[] files=dir.listFiles((d,name)->name.endsWith(".img"));if(files!=null){Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));long total=0;for(File f:files){total+=f.length();if(total>32*1024*1024)f.delete();}}
                }
                view.post(()->{if(url.equals(view.getTag()))view.setImageBitmap(art);});
            }catch(Exception ignored){} // Artwork failure must never affect playback.
        });
    }
    private static byte[] read(InputStream in)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Artwork too large");out.write(buffer,0,n);}return out.toByteArray();
    }
}
