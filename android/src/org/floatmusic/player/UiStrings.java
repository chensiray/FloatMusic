package org.floatmusic.player;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation-only translation. Track/playlist titles and lyric text stay raw. */
final class UiStrings {
    private static volatile String code="zh_CN";
    private static final Pattern ARGUMENT=Pattern.compile("%([1-9][0-9]*)");
    private static final List<Template> templates=new ArrayList<>();
    static {
        for(java.util.Map.Entry<String,String> entry:UiCatalog.EN.entrySet()){
            Matcher args=ARGUMENT.matcher(entry.getKey());StringBuilder expression=new StringBuilder("^");int end=0;ArrayList<Integer> indices=new ArrayList<>();
            while(args.find()){expression.append(Pattern.quote(entry.getKey().substring(end,args.start()))).append("([\\s\\S]*?)");indices.add(Integer.parseInt(args.group(1)));end=args.end();}
            if(!indices.isEmpty())templates.add(new Template(Pattern.compile(expression.append(Pattern.quote(entry.getKey().substring(end))).append('$').toString()),entry.getValue(),indices));
        }
        Collections.sort(templates,(a,b)->Integer.compare(b.pattern.pattern().length(),a.pattern.pattern().length()));
    }
    static void initialize(Context context){code="en".equals(context.getSharedPreferences("interface",0).getString("language","zh_CN"))?"en":"zh_CN";}
    static String code(){return code;}
    static boolean english(){return code.equals("en");}
    static void setLanguage(Context context,String value){code="en".equals(value)?"en":"zh_CN";context.getSharedPreferences("interface",0).edit().putString("language",code).apply();}
    static String text(String source){return text(source,code);}
    static String text(String source,String language){
        if(source==null)return "";if(!"en".equals(language)||source.isEmpty())return source;
        String exact=UiCatalog.EN.get(source);if(exact!=null)return exact;
        for(Template template:templates){
            Matcher match=template.pattern.matcher(source);if(!match.matches())continue;
            Matcher args=ARGUMENT.matcher(template.result);StringBuffer result=new StringBuffer();
            while(args.find()){int slot=template.indices.indexOf(Integer.parseInt(args.group(1)));String value=slot>=0?text(match.group(slot+1),language):args.group();args.appendReplacement(result,Matcher.quoteReplacement(value));}
            args.appendTail(result);return result.toString();
        }
        for(String separator:new String[]{"\n"," · "})if(source.contains(separator)){
            String[] pieces=source.split(Pattern.quote(separator),-1);StringBuilder result=new StringBuilder();
            for(int i=0;i<pieces.length;i++){if(i>0)result.append(separator);result.append(text(pieces[i],language));}return result.toString();
        }
        return source;
    }
    private static final class Template {final Pattern pattern;final String result;final List<Integer> indices;Template(Pattern p,String r,List<Integer> i){pattern=p;result=r;indices=i;}}
}
