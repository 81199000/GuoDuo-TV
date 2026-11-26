package ink.pengfei.lvguo.ui.widget;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.ui.Ui;

/**
 * 页面背后的氛围底：取焦点那部剧封面的主色，从右上角铺一层柔和的色光，往左下渐隐进背景色
 * （GuoDuo iOS 奈飞式首页同款「大海报染底色」）。
 *
 * 以前是把竖封面放大铺在右上角，很多封面裁出来人脸 / 字被切、和看板对不上；现在只用颜色，任何封面都协调。
 * 首页（hero）色光更浓，其它页面只留淡淡一层；换剧时颜色平滑过渡，焦点停稳 300ms 才换。
 */
public final class CoverBackdrop extends FrameLayout {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final View glow,wash;
    private final GradientDrawable glowDrawable,washDrawable;
    private final Probe probe;
    private String wanted="",shown="";
    private int generation;
    private Runnable pending;
    private float strength=.45f;
    private int color=0, shownColor=0;
    private ValueAnimator anim;
    private static final java.util.Map<String,Integer> CACHE=new java.util.LinkedHashMap<String,Integer>(64,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Integer> e){return size()>200;}
    };

    /** 看不见的取色器：ImageLoader 把封面解码给它，它只算颜色、不显示。 */
    private final class Probe extends ImageView {
        int requestGeneration;String requestUrl="";
        Probe(Context c){super(c);setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}   // 1×1 且盖在色光下面，看不见；不能 alpha=0（ImageLoader 只给 isShown 的视图发图）
        @Override public void setImageBitmap(Bitmap bitmap){
            if(bitmap==null||requestGeneration!=generation||!requestUrl.equals(wanted))return;
            int c=dominant(bitmap);
            synchronized(CACHE){CACHE.put(requestUrl,c);}
            setTint(c,requestUrl);
            super.setImageDrawable(null);
        }
    }

    public CoverBackdrop(Context context){
        super(context);setFocusable(false);
        glowDrawable=new GradientDrawable();glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientCenter(.82f,.0f);
        glow=new View(context);glow.setBackground(glowDrawable);
        addView(glow,new FrameLayout.LayoutParams(-1,-1));
        washDrawable=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0,0,0});
        wash=new View(context);wash.setBackground(washDrawable);
        addView(wash,new FrameLayout.LayoutParams(-1,-1));
        probe=new Probe(context);
        addView(probe,0,new FrameLayout.LayoutParams(1,1));
        paint(0);
    }

    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        super.onSizeChanged(w,h,ow,oh);
        glowDrawable.setGradientRadius(Math.max(w,h)*.85f);
    }

    /** hero：首页看板，色光浓一些；其它页面只留淡淡一层。 */
    public void setHero(boolean hero){
        float next=hero?1f:.45f;if(next==strength)return;strength=next;paint(shownColor);
    }

    public void seed(String url){if(wanted.isEmpty())showCover(url,true);}
    public void showCover(String url,boolean immediate){
        if(url==null||url.isEmpty()||url.equals(wanted))return;
        wanted=url;generation++;if(pending!=null)handler.removeCallbacks(pending);
        if(url.equals(shown))return;
        Integer known;synchronized(CACHE){known=CACHE.get(url);}
        final int version=generation;
        pending=()->{
            if(version!=generation)return;
            if(known!=null){setTint(known,url);return;}
            probe.requestGeneration=version;probe.requestUrl=url;
            ImageLoader.load(url,probe,72,96);
        };
        handler.postDelayed(pending,immediate?0:300);
    }

    private void setTint(int c,String url){
        shown=url;
        if(c==color)return;
        color=c;
        if(anim!=null)anim.cancel();
        final int from=shownColor;
        anim=ValueAnimator.ofFloat(0f,1f);anim.setDuration(520);
        final ArgbEvaluator ev=new ArgbEvaluator();
        anim.addUpdateListener(a->paint((Integer)ev.evaluate(a.getAnimatedFraction(),from,c)));
        anim.start();
    }

    /** 右上角径向色光 + 自上而下的整片染色，都渐到背景色。 */
    private void paint(int c){
        shownColor=c;
        float k=strength;
        int a1=(int)(0xB0*k),a2=(int)(0x55*k),a3=(int)(0x58*k),a4=(int)(0x20*k);
        glowDrawable.setColors(new int[]{withAlpha(c,a1),withAlpha(c,a2),withAlpha(c,0)});
        washDrawable.setColors(new int[]{withAlpha(c,a3),withAlpha(c,a4),withAlpha(c,0)});
        glow.invalidate();wash.invalidate();
    }

    private static int withAlpha(int c,int a){return (c&0x00FFFFFF)|(Math.max(0,Math.min(255,a))<<24);}

    /**
     * 封面主色：缩到 16×20，按色相分 12 桶，饱和度 × 亮度加权，取最重的一桶求平均；
     * 再压暗 / 限饱和，保证深色底上不刺眼、白字看得清。整张图几乎无彩色时用一个偏冷的深灰。
     */
    static int dominant(Bitmap src){
        Bitmap b;
        try{b=Bitmap.createScaledBitmap(src.getConfig()==Bitmap.Config.HARDWARE?src.copy(Bitmap.Config.ARGB_8888,false):src,16,20,true);}
        catch(Throwable e){return 0xFF2A2A33;}
        float[] wsum=new float[12],r=new float[12],g=new float[12],bl=new float[12];
        float[] hsv=new float[3];
        for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++){
            int p=b.getPixel(x,y);
            Color.colorToHSV(p,hsv);
            if(hsv[2]<.12f||hsv[1]<.18f)continue;
            float w=hsv[1]*hsv[2];
            int bucket=Math.min(11,(int)(hsv[0]/30f));
            wsum[bucket]+=w;r[bucket]+=Color.red(p)*w;g[bucket]+=Color.green(p)*w;bl[bucket]+=Color.blue(p)*w;
        }
        int best=-1;float top=0;
        for(int i=0;i<12;i++)if(wsum[i]>top){top=wsum[i];best=i;}
        if(best<0||top<1.2f)return 0xFF2A2A33;
        int c=Color.rgb((int)(r[best]/wsum[best]),(int)(g[best]/wsum[best]),(int)(bl[best]/wsum[best]));
        Color.colorToHSV(c,hsv);
        hsv[1]=Math.min(hsv[1],.72f);
        hsv[2]=Math.max(.38f,Math.min(hsv[2],.62f));
        if(!Ui.isDark){hsv[1]=Math.min(hsv[1],.45f);hsv[2]=Math.max(hsv[2],.80f);}
        return Color.HSVToColor(hsv);
    }

    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        if(!wanted.isEmpty()&&!wanted.equals(shown)){String url=wanted;wanted="";showCover(url,true);}
    }
    @Override protected void onDetachedFromWindow(){
        generation++;if(pending!=null)handler.removeCallbacks(pending);if(anim!=null)anim.cancel();
        super.onDetachedFromWindow();
    }
}
