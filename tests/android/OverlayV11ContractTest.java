package org.floatmusic.player;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.json.JSONObject;

/** Real native-overlay snapshot and popup geometry rules; no device or network. */
public final class OverlayV11ContractTest {
    public static void main(String[] arguments) throws Exception {
        JSONObject legacy = new JSONObject().put("quality", "hires");
        expect("hires", quality(new JSONObject(), legacy, "netease"), "old Net choice migrates when no source map is published");
        expect("standard", quality(new JSONObject(), legacy, "tencent"), "old QQ state starts with the ordinary tier");
        expect("standard", quality(new JSONObject(), legacy, "kuwo"), "old Kuwo state starts with the ordinary tier");
        JSONObject choices = new JSONObject().put("netease", "lossless").put("tencent", "master").put("kuwo", "exhigh");
        JSONObject service = new JSONObject().put("sourceQualities", choices);
        expect("lossless", quality(service, legacy, "netease"), "the service source map wins over a legacy shared quality");
        expect("master", quality(service, legacy, "tencent"), "QQ experimental master stays a distinct requested tier");
        expect("exhigh", quality(service, legacy, "kuwo"), "Kuwo retains its independent high-quality request");
        expect("master", quality(new JSONObject(), service, "tencent"), "the UI source map is available before a service snapshot");
        expect("standard", quality(new JSONObject().put("sourceQualities", new JSONObject()), legacy, "netease"), "an explicit empty map must not revive the legacy shared choice");
        expect("standard", quality(new JSONObject().put("sourceQualities", new JSONObject().put("tencent", "hires")), legacy, "tencent"), "unsupported QQ tiers cannot masquerade as a selectable request");
        expect("standard", quality(new JSONObject().put("sourceQualities", new JSONObject().put("kuwo", "master")), legacy, "kuwo"), "QQ-only master cannot leak into Kuwo");
        System.out.println("PASS independent source quality snapshots, migration and supported tiers");

        bounds(new int[]{252,8,72,164}, new int[]{340,250,276,180,48,72,204,8,8,88}, "normal volume popup opens above its control");
        bounds(new int[]{120,8,72,84}, new int[]{200,100,166,40,48,72,204,8,8,88}, "a short viewport clamps both popup edges and its height");
        bounds(new int[]{8,8,34,32}, new int[]{50,48,-20,-8,48,72,204,8,8,88}, "even a clipped anchor cannot push the popup outside its layer");
        System.out.println("PASS upward popup stays inside normal and short viewport bounds");

        expect(100, volume(8,200,8), "the top of a vertical volume track means maximum volume");
        expect(50, volume(100,200,8), "the track midpoint means half volume");
        expect(0, volume(192,200,8), "the bottom of a vertical volume track means mute");
        expect(100, volume(-30,200,8), "dragging beyond the top clamps to maximum");
        expect(0, volume(240,200,8), "dragging beyond the bottom clamps to mute");
        expect(192f, thumb(0,200,8), "mute thumb sits at the lower endpoint of the vertical track");
        expect(100f, thumb(50,200,8), "half-volume thumb sits at the vertical midpoint");
        expect(8f, thumb(100,200,8), "full-volume thumb sits at the upper endpoint");
        expect(50f, thumb(50,100,8), "thumb uses the shorter vertical track height without using the control width");
        expect(4f, thumb(100,8,8), "a degenerate draw height keeps the thumb center inside the view");
        System.out.println("PASS vertical volume direction, clamp and thumb endpoints");

        for(Object invalid:new Object[]{null,"invalid","NaN",true,Float.NaN,Double.NaN,Float.POSITIVE_INFINITY,-1f,.79f,3.01f})
            expect(1f,spacing(invalid),"missing, malformed and out-of-range saved spacing uses the one-times default");
        expect(.8f,spacing(.8f),"the compact endpoint survives preference loading");
        expect(3f,spacing(3f),"the roomy endpoint survives preference loading");
        expect(1.7f,spacing(1.74d),"saved numeric settings normalize to a tenth");
        expect(0,gap(20,1f),"default single-line lyric rows retain natural height with no fixed gap");
        expect(20,gap(20,2f),"two-times single-line spacing adds one proportional line height after the row");
        expect(40,gap(20,3f),"three-times single-line spacing adds two proportional line heights");
        expect(-4,gap(20,.8f),"compact spacing changes the row step without shrinking its glyph bounds");
        expect(-2,gap(10,.8f),"the same compact multiplier scales with a smaller lyric font");
        System.out.println("PASS lyric spacing saved defaults, allowed range and proportional row advance");
    }
    private static String quality(JSONObject playback, JSONObject ui, String source) throws Exception {
        return (String)call("sourceQuality",new Class<?>[]{JSONObject.class,JSONObject.class,String.class},playback,ui,source);
    }
    private static int volume(float y,int height,int padding) throws Exception {
        return (Integer)call("volumeAtY",new Class<?>[]{float.class,int.class,int.class},y,height,padding);
    }
    private static float thumb(int value,int height,int padding) throws Exception {
        return (Float)call("volumeThumbY",new Class<?>[]{int.class,int.class,int.class},value,height,padding);
    }
    private static float spacing(Object value) throws Exception {
        return (Float)call("lineSpacingValue",new Class<?>[]{Object.class},value);
    }
    private static int gap(int height,float value) throws Exception {
        return (Integer)call("lyricRowGap",new Class<?>[]{int.class,float.class},height,value);
    }
    private static void bounds(int[] expected,int[] input,String message) throws Exception {
        Class<?>[] types=new Class<?>[input.length];Object[] values=new Object[input.length];
        for(int i=0;i<input.length;i++){types[i]=int.class;values[i]=input[i];}
        int[] actual=(int[])call("popupBounds",types,values);
        if(!Arrays.equals(expected,actual))throw new AssertionError(message+": expected "+Arrays.toString(expected)+", got "+Arrays.toString(actual));
    }
    private static Object call(String name,Class<?>[] types,Object... values) throws Exception {
        Method method;
        try {method=Class.forName("org.floatmusic.player.OverlayWindow").getDeclaredMethod(name,types);}
        catch(NoSuchMethodException absent){throw new AssertionError("Missing overlay behavior: "+name,absent);}
        method.setAccessible(true);return method.invoke(null,values);
    }
    private static void expect(Object expected,Object actual,String message) {
        if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);
    }
}
