package ink.pengfei.lvguo.remote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import ink.pengfei.lvguo.ui.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.util.*;

/**
 * 果多「手机遥控」配对页：左边果多标 + 说明（三步），右边二维码（中间嵌果多图标）+ 地址。
 * 二维码打开的是电视内置的果多遥控网页（局域网直连）；手机版 GuoDuo 以后可在同一 Wi-Fi 下自动发现这台电视，无需扫码。
 */
public final class Pairing {
    private static java.lang.ref.WeakReference<Dialog> active=new java.lang.ref.WeakReference<>(null);
    public static void dismiss(){Dialog dialog=active.get();if(dialog!=null)dialog.dismiss();active.clear();}
    public static void show(Activity host) { show(host, "手机遥控", "手机扫一扫，就是电视的遥控器", "#remote"); }
    /** 搜索页用：扫码后手机直接打开搜索页（hash = "#search"）。 */
    public static void showSearch(Activity host) { show(host, "扫码输入", "手机上打字，电视搜索框同步显示；点选剧集直接在电视播放", "#search"); }

    private static int dp(Context c,float v){return Ui.dp(c,v);}

    /** 果多二维码（中间嵌图标，H 级纠错）。 */
    public static Bitmap qr(Context c,String text,int px)throws WriterException{
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN,0);
        hints.put(EncodeHintType.ERROR_CORRECTION,com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H);
        BitMatrix matrix=new MultiFormatWriter().encode(text,BarcodeFormat.QR_CODE,px,px,hints);
        int[] pixels=new int[px*px];for(int y=0;y<px;y++)for(int x=0;x<px;x++)pixels[y*px+x]=matrix.get(x,y)?0xFF111113:Color.WHITE;
        Bitmap qr=Bitmap.createBitmap(px,px,Bitmap.Config.ARGB_8888);qr.setPixels(pixels,0,px,0,0,px,px);
        Bitmap logo=BitmapFactory.decodeResource(c.getResources(),ink.pengfei.lvguo.R.drawable.app_icon);
        if(logo!=null){
            Canvas cv=new Canvas(qr);int s=(int)(px*.22f),pad=(int)(px*.02f),o=(px-s)/2;
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);
            cv.drawRoundRect(new RectF(o-pad,o-pad,o+s+pad,o+s+pad),s*.28f,s*.28f,p);
            cv.drawBitmap(logo,null,new Rect(o,o,o+s,o+s),new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG));
        }
        return qr;
    }

    public static void show(Activity host, String titleText, String hintText, String hash) {
        dismiss();
        try{host.startService(new Intent(host,LocalService.class));}catch(IllegalStateException ignored){}
        Dialog dialog=new ink.pengfei.lvguo.ui.TrackedDialog(host);
        Ui.closeOnBack(dialog);
        active=new java.lang.ref.WeakReference<>(dialog);

        LinearLayout body=Ui.row(host);body.setGravity(Gravity.CENTER_VERTICAL);Ui.padding(body,36,32,32,32);
        body.setBackground(Ui.liquidGlass(host,26));

        // ---- 左：品牌 + 标题 + 三步说明
        LinearLayout left=Ui.column(host);
        LinearLayout brand=Ui.row(host);brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark=new ImageView(host);mark.setImageResource(ink.pengfei.lvguo.R.drawable.logo_mark);mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.addView(mark,Ui.lp(dp(host,40),dp(host,40)));
        LinearLayout words=Ui.column(host);
        words.addView(Ui.text(host,"GuoDuo",18,0xFFFFFFFF,true),Ui.lp(-2,-2));
        LinearLayout.LayoutParams wl=Ui.lp(-2,-2);wl.topMargin=dp(host,3);
        words.addView(Ui.text(host,"短剧 · 电视版",11,0x99FFFFFF,false),wl);
        LinearLayout.LayoutParams wlp=Ui.lp(-2,-2);wlp.leftMargin=dp(host,12);brand.addView(words,wlp);
        left.addView(brand,Ui.lp(-2,-2));
        TextView title=Ui.text(host,titleText,26,0xFFFFFFFF,true);
        LinearLayout.LayoutParams tl=Ui.lp(-2,-2);tl.topMargin=dp(host,26);left.addView(title,tl);
        TextView hint=Ui.text(host,hintText,14,0xCCFFFFFF,false);
        LinearLayout.LayoutParams hl=Ui.lp(-2,-2);hl.topMargin=dp(host,8);left.addView(hint,hl);
        String[] steps={"手机连上和电视同一个 Wi-Fi","用相机或微信扫右边的二维码","打开后就是遥控器，也能直接选剧"};
        LinearLayout list=Ui.column(host);
        for(int i=0;i<steps.length;i++){
            LinearLayout row=Ui.row(host);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView n=Ui.text(host,String.valueOf(i+1),12,0xFFFFFFFF,true);n.setGravity(Gravity.CENTER);n.setBackground(Ui.pill(host,Ui.PRIMARY));
            row.addView(n,Ui.lp(dp(host,22),dp(host,22)));
            LinearLayout.LayoutParams sl=Ui.lp(-2,-2);sl.leftMargin=dp(host,10);
            row.addView(Ui.text(host,steps[i],14,0xE6FFFFFF,false),sl);
            LinearLayout.LayoutParams rl=Ui.lp(-2,-2);rl.topMargin=dp(host,i==0?22:12);list.addView(row,rl);
        }
        left.addView(list,Ui.lp(-2,-2));
        TextView soon=Ui.text(host,"手机版 GuoDuo 即将支持同一 Wi-Fi 自动连接，免扫码",12,0x80FFFFFF,false);
        LinearLayout.LayoutParams so=Ui.lp(-2,-2);so.topMargin=dp(host,22);left.addView(soon,so);
        body.addView(left,Ui.lp(0,-2,1));

        // ---- 右：二维码卡片
        LinearLayout codeCard=Ui.column(host);codeCard.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout codeBox=new FrameLayout(host);codeBox.setBackground(Ui.round(host,0xFFFFFFFF,18));
        int qrSize=dp(host,196);
        TextView waiting=Ui.text(host,"正在开启手机遥控…",13,0xFF6E6E73,false);waiting.setGravity(Gravity.CENTER);
        codeBox.addView(waiting,new FrameLayout.LayoutParams(-1,-1));
        codeCard.addView(codeBox,Ui.lp(qrSize+dp(host,20),qrSize+dp(host,20)));
        TextView address=Ui.text(host,"",12,0x99FFFFFF,false);address.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams al=Ui.lp(-2,-2);al.topMargin=dp(host,12);codeCard.addView(address,al);
        LinearLayout.LayoutParams cl=Ui.lp(-2,-2);cl.leftMargin=dp(host,32);body.addView(codeCard,cl);

        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable refresh=new Runnable(){int attempts;public void run(){
            if(!dialog.isShowing() || host.isFinishing())return;
            String url=LocalService.address();
            if(url==null){if(++attempts<40)handler.postDelayed(this,250);else waiting.setText("电视没连网\n请先连接 Wi-Fi");return;}
            codeBox.removeAllViews();
            try {
                Bitmap qr=qr(host,url+hash,480);
                ImageView view=new ImageView(host);view.setImageBitmap(qr);
                codeBox.addView(view,new FrameLayout.LayoutParams(qrSize,qrSize,Gravity.CENTER));
                address.setText(url.split("\\?",2)[0].replace("http://",""));
            }catch(Exception e){
                TextView failure=Ui.text(host,"二维码生成失败\n请重新打开",13,0xFF6E6E73,false);failure.setGravity(Gravity.CENTER);
                codeBox.addView(failure,new FrameLayout.LayoutParams(-1,-1));
                android.util.Log.w("TVPairing","QR presentation failed",e);
            }
        }};
        dialog.setOnDismissListener(d->handler.removeCallbacksAndMessages(null));
        dialog.setCanceledOnTouchOutside(true);
        dialog.setContentView(body);Ui.popIn(body);dialog.show();
        Window w=dialog.getWindow();
        if(w!=null){
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.getDecorView().setSystemUiVisibility(host.getWindow().getDecorView().getSystemUiVisibility());
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setDimAmount(.6f);
            w.setLayout(Math.min(dp(host,720),host.getResources().getDisplayMetrics().widthPixels-dp(host,48)),-2);
        }
        handler.post(refresh);
    }
}
