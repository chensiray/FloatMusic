package org.floatmusic.player;

/** Shared by the overlay renderer and device-free regression checks. */
final class FloatingLyricRules {
    static String cleanWords(String value){
        if(value==null)return "";
        StringBuilder result=new StringBuilder();
        for(String part:value.split("[\\r\\n]+")){
            part=part.replace("\u200b","").replace("\ufeff","").replaceAll("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$","");
            if(part.isEmpty())continue;
            if(result.length()>0)result.append(" · ");
            result.append(part);
        }
        return result.toString();
    }
    static int rows(int value){return Math.max(1,Math.min(9,Math.floorDiv(value,2)*2+1));}
    static int highlightOpacity(int value){return Math.max(10,Math.min(100,value));}
    static int edgeOpacity(int value,int highlight){return Math.max(0,Math.min(highlightOpacity(highlight),value));}
    static float opacity(float distance,int rowCount,int highlight,int edge){
        float peak=highlightOpacity(highlight)/100f,outerOpacity=edgeOpacity(edge,highlight)/100f,at=Math.abs(distance);
        int outer=rows(rowCount)/2;
        if(outer==0)return peak*Math.max(0,1-at);
        if(at<=outer){
            // Same normalized exponential as DesktopLyrics.qml; keep both
            // configured endpoints exact while separating nearby context rows.
            double floor=Math.exp(-2);
            float weight=(float)((Math.exp(-2d*at/outer)-floor)/(1-floor));
            return outerOpacity+(peak-outerOpacity)*weight;
        }
        return outerOpacity*Math.max(0,1-(at-outer));
    }
    static float scale(float distance,boolean active){return (active?1.12f:1f)*Math.max(.94f,1f-Math.abs(distance)*.015f);}
    static float emphasis(float distance){return Math.max(0,1-Math.abs(distance));}
    static float visualScale(float distance){return scale(distance,false)*(1+.12f*emphasis(distance));}
    static int rowHeight(float desiredTextPx,int rows,int availableHeight){return Math.max(1,Math.min((int)Math.ceil(desiredTextPx*1.75f),Math.max(1,availableHeight/rows)));}
    static int current(long[] times,long position){int low=0,high=times.length;while(low<high){int middle=(low+high)/2;if(times[middle]<=position)low=middle+1;else high=middle;}return low-1;}
}
