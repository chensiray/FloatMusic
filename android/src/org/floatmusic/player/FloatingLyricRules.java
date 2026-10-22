package org.floatmusic.player;

/** Shared by the overlay renderer and device-free regression checks. */
final class FloatingLyricRules {
    static int rows(int value){return Math.max(1,Math.min(9,Math.floorDiv(value,2)*2+1));}
    static float opacity(float distance){
        float[] stops={1f,.72f,.48f,.29f,.14f,.04f,0f};
        float at=Math.max(0,Math.min(6,Math.abs(distance)));int first=(int)at,last=Math.min(6,first+1);
        return stops[first]+(stops[last]-stops[first])*(at-first);
    }
    static float scale(float distance,boolean active){return (active?1.12f:1f)*Math.max(.76f,1f-Math.abs(distance)*.055f);}
    static float emphasis(float distance){return Math.max(0,1-Math.abs(distance));}
    static float visualScale(float distance){return scale(distance,false)*(1+.12f*emphasis(distance));}
    static int rowHeight(float desiredTextPx,int rows,int availableHeight){return Math.max(1,Math.min((int)Math.ceil(desiredTextPx*1.75f),Math.max(1,availableHeight/rows)));}
    static int current(long[] times,long position){int low=0,high=times.length;while(low<high){int middle=(low+high)/2;if(times[middle]<=position)low=middle+1;else high=middle;}return low-1;}
}
