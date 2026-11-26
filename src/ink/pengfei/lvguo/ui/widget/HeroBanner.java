package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.media.CompatMediaPlayer;
import ink.pengfei.lvguo.media.StreamingSource;
import ink.pengfei.lvguo.media.VideoDecoderSupport;
import ink.pengfei.lvguo.model.Gear;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.LvguoApi;
import ink.pengfei.lvguo.net.Repo;
import ink.pengfei.lvguo.ui.Ui;

/**
 * 果多首页大海报（照 GuoDuo iOS 新版 HGWideHeroCard）：
 * 同一张封面放大糊开铺底 + 左侧压暗写剧名 / 热度集数题材 / 简介 / 「OK 播放」，右侧完整竖海报（不裁）。
 *
 * 焦点停在某部剧 1.2 秒后，右侧竖海报位置直接静音播放这部剧第 1 集（奈飞电视版的预告自动播放）：
 * 首帧出来前一直显示封面，出来后淡入视频；换焦点 / 离开首页立刻停、释放解码器。
 */
public final class HeroBanner extends FrameLayout {
    public static final long PREVIEW_DELAY_MS = 1200;
    /** 预告只播这么久，之后停在最后一帧（省流量、避免一直占解码器）。 */
    private static final long PREVIEW_MAX_MS = 90_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView blur, poster;
    private final RoundFrame posterFrame;
    private final SurfaceView video;
    private final TextView eyebrow, title, meta, intro, hint;
    private final int posterW, posterH;
    private String coverUrl = "", seriesId = "";
    private boolean surfaceReady, active = true, previewOn;
    private int token;
    private CompatMediaPlayer player;
    private StreamingSource source;
    private Runnable pendingPreview;
    private Series current;
    private LinearLayout textCol;
    private int retries;

    public HeroBanner(Context c, int heightPx) {
        super(c);
        setClipChildren(true);
        posterH = heightPx - Ui.dp(c, 28);
        posterW = posterH * 3 / 4;
        previewOn = App.prefs().getBoolean("home_preview", true);

        // 铺底：同一张封面低分辨率放大（=糊开）
        blur = new ImageView(c);
        blur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blur.setAlpha(.9f);
        addView(blur, new LayoutParams(-1, -1));
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF0101014, 0xD9101014, 0x8C101014, 0x40101014}));
        addView(shade, new LayoutParams(-1, -1));
        View bottom = new View(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, Ui.alpha(Ui.BG, 0xFF)}));
        addView(bottom, new LayoutParams(-1, Ui.dp(c, 40), Gravity.BOTTOM));

        // 左：文字
        LinearLayout text = Ui.column(c);
        text.setGravity(Gravity.CENTER_VERTICAL);
        eyebrow = Ui.text(c, "", 12, Ui.ACCENT_TEXT, true);
        eyebrow.setLetterSpacing(.06f);
        text.addView(eyebrow, Ui.lp(-2, -2));
        title = Ui.text(c, "", 30, 0xFFFFFFFF, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 2), 0x80000000);
        LinearLayout.LayoutParams tl = Ui.lp(-1, -2); tl.topMargin = Ui.dp(c, 8);
        text.addView(title, tl);
        meta = Ui.text(c, "", 13, 0xD9FFFFFF, false);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams ml = Ui.lp(-1, -2); ml.topMargin = Ui.dp(c, 8);
        text.addView(meta, ml);
        intro = Ui.ellipsis(c, "", 13, 0xCCFFFFFF, 3);
        intro.setLineSpacing(Ui.dp(c, 3), 1f);
        LinearLayout.LayoutParams il = Ui.lp(-1, -2); il.topMargin = Ui.dp(c, 10);
        text.addView(intro, il);
        hint = Ui.text(c, "按 OK 进入详情", 12, 0x99FFFFFF, false);
        LinearLayout.LayoutParams hl = Ui.lp(-2, -2); hl.topMargin = Ui.dp(c, 14);
        text.addView(hint, hl);
        textCol = text;
        LayoutParams textLp = new LayoutParams(-1, -1);
        textLp.leftMargin = Ui.dp(c, 48); textLp.rightMargin = posterW + Ui.dp(c, 48 + 36);
        addView(text, textLp);

        // 右：完整竖海报（不裁），预告视频播在它的位置
        posterFrame = new RoundFrame(c, 12);
        posterFrame.setBackground(Ui.round(c, Ui.SURFACE, 12));
        video = new SurfaceView(c);
        video.setZOrderMediaOverlay(true);
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; if (player != null) try { player.setDisplay(h); } catch (Exception ignored) { } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; stopPreview(); }
        });
        posterFrame.addView(video, new LayoutParams(-1, -1));
        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        posterFrame.addView(poster, new LayoutParams(-1, -1));
        posterFrame.setElevation(Ui.dp(c, 12));
        LayoutParams pl = new LayoutParams(posterW, posterH, Gravity.END | Gravity.CENTER_VERTICAL);
        pl.rightMargin = Ui.dp(c, 48);
        addView(posterFrame, pl);
    }

    /** 换成这部剧（焦点变化时调）。 */
    public void show(Series s, String kicker) {
        if (s == null) return;
        eyebrow.setText(kicker == null ? "" : kicker);
        String id = s.seriesId == null ? "" : s.seriesId;
        boolean same = id.equals(seriesId);
        title.setText(s.title == null ? "" : s.title);
        meta.setText(metaText(s));
        String t = s.intro == null ? "" : s.intro.trim();
        intro.setText(t);
        intro.setVisibility(t.isEmpty() ? GONE : VISIBLE);
        current = s;
        if (same) return;
        seriesId = id;
        stopPreview();
        String url = s.cover == null ? "" : s.cover;
        if (!url.equals(coverUrl)) {
            coverUrl = url;
            poster.animate().cancel(); poster.setAlpha(1f);
            ImageLoader.load(url, poster, posterW, posterH);
            ImageLoader.load(url, blur, 40, 54);
        }
        schedulePreview(s);
    }

    private static String metaText(Series s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        String heat = s.playText();
        if (!heat.isEmpty()) parts.add(heat);
        if (s.episodeCount > 0) parts.add("全 " + s.episodeCount + " 集");
        if (s.category != null && !s.category.isEmpty()) {
            int n = 0;
            for (String c : s.category.split("[,，|/]")) { c = c.trim(); if (!c.isEmpty() && n++ < 3) parts.add(c); }
        }
        return android.text.TextUtils.join("  ·  ", parts);
    }

    /** 首页显示 / 隐藏：隐藏时停预告、释放解码器（推荐页 / 播放器要用）。 */
    public void setActive(boolean on) {
        active = on;
        if (!on) { stopPreview(); return; }
        previewOn = App.prefs().getBoolean("home_preview", true);
    }

    /** 回到首页：当前这部重新开始预告。 */
    public void replay() { if (current != null && player == null) schedulePreview(current); }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopPreview();
        else if (active) replay();
    }

    // ------------------------------------------------------------ 预告

    private void schedulePreview(final Series s) {
        if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
        if (!previewOn || !active) return;
        final int my = ++token;
        pendingPreview = () -> startPreview(s, my);
        handler.postDelayed(pendingPreview, PREVIEW_DELAY_MS);
    }

    private void startPreview(final Series s, final int my) {
        if (my != token || !active || !surfaceReady || !isShown()) return;
        final String id = s.seriesId;
        App.io().execute(() -> {
            StreamingSource src = null;
            try {
                String vid = s.firstVid;
                if (vid == null || vid.isEmpty()) {
                    Repo.Detail d = Repo.detail(id);
                    if (d.episodes.isEmpty()) return;
                    vid = d.episodes.get(0).vid;
                }
                if (my != token) return;
                List<Gear> gears = VideoDecoderSupport.compatible(Repo.play(vid, LvguoApi.DEVICE_LEVEL_H265));
                // 预告画面小：挑 ≤540P 的档，省流量也快
                Gear g = Repo.pickGear(gears, "540p", 540);
                if (g == null) g = Repo.pickGear(gears, null, 720);
                if (g == null || my != token) return;
                src = new StreamingSource(g);
                final StreamingSource ready = src; src = null;
                final String mediaId = vid;
                handler.post(() -> attach(ready, mediaId, my));
            } catch (Throwable e) {
                android.util.Log.w("HeroPreview", "preview skipped: " + e);
            } finally {
                if (src != null) src.close();
            }
        });
    }

    private void attach(StreamingSource src, String mediaId, int my) {
        if (my != token || !active || !surfaceReady) { src.close(); return; }
        releasePlayer();
        try {
            final CompatMediaPlayer p = new CompatMediaPlayer(getContext());
            player = p; source = src;
            p.setDisplay(video.getHolder());
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == p) {
                    // 首帧出来：封面淡出，露出视频
                    poster.animate().cancel();
                    poster.animate().alpha(0f).setDuration(380).start();
                }
                return false;
            });
            // 竖屏短剧 9:16 放进 3:4 的海报框：按宽铺满、上下居中裁掉一点（不拉伸）
            p.setOnVideoSizeChangedListener((mp, w, hh) -> { if (player == p && w > 0 && hh > 0) fitVideo(w, hh); });
            retries = 0;
            p.setOnCompletionListener(mp -> { if (player == p) stopPreview(); });
            p.setOnErrorListener((mp, w, e) -> {
                if (player != p) return true;
                final Series s = current; final boolean again = retries++ < 1;
                stopPreview();
                if (again && s != null) { final int tk = token; handler.postDelayed(() -> { if (tk == token && s == current) startPreview(s, tk); }, 700); }
                return true;
            });
            p.setDataSource(src, mediaId);
            p.prepareAsync();   // 默认静音（Media3 起播先 0 音量）
            handler.postDelayed(() -> { if (player == p) { try { p.pause(); } catch (Exception ignored) { } } }, PREVIEW_MAX_MS);
        } catch (Throwable e) {
            android.util.Log.w("HeroPreview", "attach failed: " + e);
            stopPreview();
        }
    }

    /**
     * 横竖屏自适应：画框改成和视频一样的比例（竖屏短剧 = 窄高框，横屏 = 宽框），视频完整显示不裁；
     * 高度不变，宽度最多占屏幕一半（再宽就按宽缩高）。左侧文字区跟着让位。
     */
    private void fitVideo(int vw, int vh) {
        int maxW = Math.round(getWidth() * .52f);
        int h = posterH, w = Math.round(posterH * vw / (float) vh);
        if (maxW > 0 && w > maxW) { w = maxW; h = Math.round(w * vh / (float) vw); }
        resizeFrame(w, h, true);
    }

    private void resizeFrame(int w, int h, boolean animate) {
        final LayoutParams lp = (LayoutParams) posterFrame.getLayoutParams();
        final int fromW = lp.width, fromH = lp.height;
        if (fromW == w && fromH == h) return;
        final LayoutParams tl = (LayoutParams) textCol.getLayoutParams();
        final int gap = Ui.dp(getContext(), 48 + 36);
        if (!animate) { lp.width = w; lp.height = h; posterFrame.setLayoutParams(lp); tl.rightMargin = w + gap; textCol.setLayoutParams(tl); return; }
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(320);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(v -> {
            float f = v.getAnimatedFraction();
            lp.width = Math.round(fromW + (w - fromW) * f); lp.height = Math.round(fromH + (h - fromH) * f);
            posterFrame.setLayoutParams(lp);
            tl.rightMargin = lp.width + gap; textCol.setLayoutParams(tl);
        });
        a.start();
    }

    public void stopPreview() {
        token++;
        if (pendingPreview != null) { handler.removeCallbacks(pendingPreview); pendingPreview = null; }
        poster.animate().cancel();
        poster.setAlpha(1f);
        releasePlayer();
        if (posterFrame.getLayoutParams().width != posterW) resizeFrame(posterW, posterH, true);
    }

    private void releasePlayer() {
        CompatMediaPlayer p = player; player = null;
        if (p != null) try { p.reset(); p.release(); } catch (Throwable ignored) { }
        StreamingSource s = source; source = null;
        if (s != null) s.close();
    }

    @Override protected void onDetachedFromWindow() { stopPreview(); super.onDetachedFromWindow(); }
}
