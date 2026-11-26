package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import ink.pengfei.lvguo.App;

/**
 * 竖屏视频两侧的环境光（DouDuo / GuoDuo iOS 同款）：每 ~280ms 用 PixelCopy 把视频画面缩成一张很小的图，
 * 放大铺满整个播放区 = 天然模糊的同色光，再压一层暗；两张图交替淡入淡出，不闪。
 * 只在画面没铺满屏幕（竖屏剧左右有黑边）时工作；横屏铺满时自动隐藏、停止取帧。
 */
public final class AmbientLight extends FrameLayout {
    public static final String KEY = "ambient_level";
    /** 关 / 亮 / 中 / 暗：数值 = 压暗层不透明度（%） */
    public static final int[] LEVELS = {0, 35, 55, 72};
    public static final String[] LABELS = {"关闭", "亮", "中", "暗"};
    private static final long INTERVAL_MS = 280;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageView a, b;
    private final View dim;
    private SurfaceView source;
    private boolean wanted, running, copying, showA = true;
    private Bitmap spare;
    private int failures;

    public AmbientLight(Context c) {
        super(c);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        a = layer(c); b = layer(c);
        dim = new View(c);
        addView(dim, new LayoutParams(-1, -1));
        setVisibility(GONE);
        applyLevel();
    }

    private ImageView layer(Context c) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        addView(v, new LayoutParams(-1, -1));
        return v;
    }

    public static int level() {
        int i = App.prefs().getInt(KEY, 2);
        return Math.max(0, Math.min(LEVELS.length - 1, i));
    }

    /** 设置改了：刷新暗度 / 开关。 */
    public void applyLevel() {
        int l = level();
        dim.setBackgroundColor(((LEVELS[l] * 255 / 100) << 24));
        update();
    }

    public void attach(SurfaceView surface) { source = surface; }

    /** 画面宽 < 屏幕宽（左右有黑边）时才需要环境光。 */
    public void setLetterboxed(boolean on) { wanted = on; update(); }

    public void stop() { wanted = false; update(); }

    private void update() {
        boolean on = wanted && level() > 0 && source != null && android.os.Build.VERSION.SDK_INT >= 24;
        if (on == running) return;
        running = on;
        if (on) { failures = 0; setVisibility(VISIBLE); ui.removeCallbacks(tick); ui.post(tick); }
        else {
            ui.removeCallbacks(tick);
            animate().cancel();
            animate().alpha(0f).setDuration(220).withEndAction(() -> { if (!running) { setVisibility(GONE); a.setAlpha(0f); b.setAlpha(0f); setAlpha(1f); } }).start();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            grab();
            ui.postDelayed(this, INTERVAL_MS);
        }
    };

    private void grab() {
        if (copying || source == null || android.os.Build.VERSION.SDK_INT < 24) return;
        android.view.Surface s = source.getHolder().getSurface();
        if (s == null || !s.isValid() || source.getWidth() <= 0) return;
        // 按视频画面比例取一张 24 像素宽的小图
        int w = 32, h = Math.max(8, Math.round(32f * source.getHeight() / source.getWidth()));
        final Bitmap bmp = spare != null && spare.getWidth() == w && spare.getHeight() == h ? spare : Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        spare = null;
        copying = true;
        try {
            PixelCopy.request(source, bmp, result -> {
                copying = false;
                if (!running) { spare = bmp; return; }
                if (result != PixelCopy.SUCCESS) { spare = bmp; if (++failures > 20) { running = false; setVisibility(GONE); } return; }
                failures = 0;
                show(soften(bmp));
            }, ui);
        } catch (Throwable e) { copying = false; spare = bmp; }
    }

    /** 小图先做两遍 3×3 均值模糊再放大 4 倍（双线性），铺满屏幕时是柔光而不是马赛克色块。 */
    private Bitmap soften(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) { int yy = y + dy; if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) { int xx = x + dx; if (xx < 0 || xx >= w) continue;
                        int c = px[yy * w + xx]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++; } }
                tmp[y * w + x] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
            int[] s = px; px = tmp; tmp = s;
        }
        src.setPixels(px, 0, w, 0, 0, w, h);
        Bitmap big = Bitmap.createScaledBitmap(src, w * 4, h * 4, true);
        spare = src;
        return big;
    }

    private void show(Bitmap bmp) {
        ImageView in = showA ? a : b, out = showA ? b : a;
        showA = !showA;
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
        d.setFilterBitmap(true);
        in.setImageDrawable(d);
        in.bringToFront(); dim.bringToFront();
        in.animate().cancel(); in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(240).withEndAction(() -> out.setAlpha(0f)).start();
        if (getAlpha() < 1f) animate().alpha(1f).setDuration(220).start();
    }

    @Override protected void onDetachedFromWindow() { running = false; ui.removeCallbacks(tick); super.onDetachedFromWindow(); }
}
