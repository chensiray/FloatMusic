package org.floatmusic.player;

import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.widget.TextView;

/** Color-only reveal over TextView's existing layout, including wrapped paragraphs. */
final class ProgressLyricTextView extends TextView {
    private boolean progressEnabled;
    private float progress;
    private int progressColor;
    private Layout measured;
    private float[] left,right,before,total;
    ProgressLyricTextView(Context context){super(context);}
    void setColorProgress(boolean enabled,float value,int color){
        value=Math.max(0,Math.min(1,value));
        if(progressEnabled==enabled&&progress==value&&progressColor==color)return;
        progressEnabled=enabled;progress=value;progressColor=color;invalidate();
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);measured=null;}
    private void measureLines(Layout layout){
        if(measured==layout)return;
        int count=layout.getLineCount();left=new float[count];right=new float[count];before=new float[count];total=new float[count];
        int first=0;float width=0;CharSequence text=getText();
        for(int i=0;i<count;i++){
            left[i]=layout.getLineLeft(i);right[i]=layout.getLineRight(i);before[i]=width;width+=Math.max(0,right[i]-left[i]);
            int end=layout.getLineEnd(i);
            // Original and translation are separate paragraphs and reveal together.
            if(i+1==count||(end>0&&text.charAt(end-1)=='\n')){for(int j=first;j<=i;j++)total[j]=width;first=i+1;width=0;}
        }
        measured=layout;
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        Layout layout=getLayout();if(!progressEnabled||progress<=0||layout==null)return;
        measureLines(layout);int oldColor=getPaint().getColor();getPaint().setColor(progressColor);
        int save=canvas.save();canvas.translate(getTotalPaddingLeft()-getScrollX(),getTotalPaddingTop()-getScrollY());
        for(int i=0;i<layout.getLineCount();i++){
            float covered=(right[i]-left[i])*LyricProgress.lineFraction(progress,before[i],right[i]-left[i],total[i]);if(covered<=0)continue;
            int clip=canvas.save();canvas.clipRect(left[i],layout.getLineTop(i),left[i]+covered,layout.getLineBottom(i));layout.draw(canvas);canvas.restoreToCount(clip);
        }
        canvas.restoreToCount(save);getPaint().setColor(oldColor);
    }
}
