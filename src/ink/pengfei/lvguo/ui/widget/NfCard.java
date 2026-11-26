package ink.pengfei.lvguo.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.img.ImageLoader;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.ui.Ui;

/**
 * 果多首页卡片（奈飞电视版 2025 交互）：平时是一张竖海报；获得焦点后原地横向展开成宽卡——
 * 竖海报放大模糊铺底，左侧剧名 / 集数，右侧清晰竖海报不裁，白色描边。
 * Top 10 行在海报左边画一个描边大数字；继续观看在底部画一条进度。
 */
public final class NfCard extends FrameLayout implements HScrollRow.Visible {
    public static final int W = 112, H = 150, EXPANDED = 290, GAP = 14;

    private final RoundFrame frame;
    private final ImageView poster, blur;
    private final View shade;
    private final LinearLayout text;
    private final TextView title, sub, badge;
    private View progressTrack, progressFill;
    private final int w, h, ew, pad, rankPad;
    private final int rank;
    private final String url, seriesId;
    private boolean loaded, blurLoaded;
    private ValueAnimator anim;
    private Runnable onFocused;
    private Paint rankFill, rankStroke;

    public NfCard(Context c, Series s, int rank) {
        super(c);
        this.rank = rank;
        w = Ui.dp(c, W); h = Ui.dp(c, H); ew = Ui.dp(c, EXPANDED); pad = Ui.dp(c, 3);
        rankPad = rank <= 0 ? 0 : Ui.dp(c, rank >= 10 ? 78 : 48);
        url = s == null ? null : s.cover;
        seriesId = s == null ? null : s.seriesId;
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClipChildren(false);
        setClipToPadding(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        setPadding(pad + rankPad, pad, pad, pad);

        frame = new RoundFrame(c, 10);
        frame.setBackground(Ui.round(c, Ui.SURFACE, 10));
        // 展开后的底：同一张封面低分辨率放大 = 模糊（不依赖 RenderEffect，安卓 10 也能用）
        blur = new ImageView(c);
        blur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blur.setAlpha(0f);
        frame.addView(blur, new LayoutParams(-1, -1));
        shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6101014, 0xB3101014, 0x40101014}));
        shade.setAlpha(0f);
        frame.addView(shade, new LayoutParams(-1, -1));

        text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.BOTTOM);
        text.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 10), Ui.dp(c, 14));
        title = Ui.ellipsis(c, s == null ? "" : s.title, 16, 0xFFFFFFFF, 3);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0x99000000);
        text.addView(title, new LinearLayout.LayoutParams(-1, -2));
        sub = Ui.text(c, "", 12, 0xCCFFFFFF, false);
        sub.setSingleLine(true);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(c, 6);
        text.addView(sub, sl);
        text.setAlpha(0f);
        frame.addView(text, new LayoutParams(ew - w, -1, Gravity.START));

        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(poster, new LayoutParams(w, -1, Gravity.END));

        badge = Ui.text(c, "", 11, 0xFFFFFFFF, true);
        badge.setBackground(Ui.pill(c, 0xB3000000));
        badge.setPadding(Ui.dp(c, 7), Ui.dp(c, 2), Ui.dp(c, 7), Ui.dp(c, 2));
        badge.setVisibility(GONE);
        LayoutParams bp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        bp.setMargins(0, 0, Ui.dp(c, 6), Ui.dp(c, 8));
        frame.addView(badge, bp);

        addView(frame, new LayoutParams(w, h));
        if (s != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (s.episodeCount > 0) parts.add("全 " + s.episodeCount + " 集");
            String heat = s.playText();
            if (!heat.isEmpty()) parts.add(heat);
            sub.setText(android.text.TextUtils.join(" · ", parts));
        }
        if (s != null && ink.pengfei.lvguo.net.OtherSources.is(s.seriesId))
            setBadge(ink.pengfei.lvguo.net.OtherSources.label(ink.pengfei.lvguo.net.OtherSources.sourceOf(s.seriesId)));
        if (rank > 0) {
            Typeface tf = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            rankFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            rankFill.setTypeface(tf); rankFill.setColor(Ui.BG); rankFill.setTextAlign(Paint.Align.RIGHT);
            rankStroke = new Paint(rankFill);
            rankStroke.setStyle(Paint.Style.STROKE); rankStroke.setStrokeWidth(Ui.dp(c, 2.5f));
            rankStroke.setColor(0xFFBDBDC2); rankStroke.setStrokeJoin(Paint.Join.ROUND);
            rankFill.setTextSize(h * 0.9f); rankStroke.setTextSize(h * 0.9f);
            setWillNotDraw(false);
        }
    }

    public String seriesId() { return seriesId; }
    public void setOnFocused(Runnable r) { onFocused = r; }
    /** 收起状态占的宽度（含名次数字和描边留白），首页按它算「焦点停在行首」的滚动位置。 */
    public int collapsedWidth() { return w + rankPad + pad * 2; }

    public void setBadge(String text) {
        badge.setText(text == null ? "" : text);
        badge.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
    }

    /** 底部进度（0..1），负数不显示。 */
    public void setProgress(float fraction) {
        Context c = getContext();
        if (fraction < 0) return;
        if (progressTrack == null) {
            progressTrack = new View(c); progressTrack.setBackgroundColor(0x59FFFFFF);
            progressFill = new View(c); progressFill.setBackgroundColor(Ui.PRIMARY);
            LayoutParams tl = new LayoutParams(w, Ui.dp(c, 3), Gravity.BOTTOM | Gravity.END);
            frame.addView(progressTrack, tl);
            LayoutParams fl = new LayoutParams(Math.max(Ui.dp(c, 4), (int) (w * Math.min(1f, fraction))), Ui.dp(c, 3), Gravity.BOTTOM | Gravity.END);
            fl.rightMargin = w - fl.width;
            frame.addView(progressFill, fl);
            LayoutParams bp = (LayoutParams) badge.getLayoutParams(); bp.bottomMargin = Ui.dp(c, 12); badge.setLayoutParams(bp);
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (rank > 0) {
            String t = String.valueOf(rank);
            float x = getPaddingLeft() + Ui.dp(getContext(), 22), y = pad + h - Ui.dp(getContext(), 4);
            canvas.drawText(t, x, y, rankFill);
            canvas.drawText(t, x, y, rankStroke);
        }
        super.dispatchDraw(canvas);
    }

    @Override protected void onFocusChanged(boolean gain, int direction, Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        frame.setRing(gain, Ui.FOCUS_RING, 3f);
        expand(gain);
        if (gain) {
            ensureLoaded();
            if (onFocused != null) onFocused.run();
        }
    }

    private void expand(boolean on) {
        if (anim != null) anim.cancel();
        final LayoutParams lp = (LayoutParams) frame.getLayoutParams();
        int from = lp.width, to = on ? ew : w;
        if (on && !blurLoaded && url != null && !url.isEmpty()) { blurLoaded = true; ImageLoader.load(url, blur, 24, 32); }
        anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(200);
        anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> { lp.width = (int) a.getAnimatedValue(); frame.setLayoutParams(lp); });
        if (on) anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                // 展开后变宽了：行尾的卡要让横向行再滚一点，整张露出来
                if (hasFocus()) post(() -> requestRectangleOnScreen(new Rect(0, 0, getWidth(), getHeight()), false));
            }
        });
        anim.start();
        float alpha = on ? 1f : 0f;
        blur.animate().cancel(); blur.animate().alpha(alpha).setDuration(on ? 220 : 120).start();
        shade.animate().cancel(); shade.animate().alpha(alpha).setDuration(on ? 220 : 120).start();
        text.animate().cancel(); text.animate().alpha(alpha).setStartDelay(on ? 90 : 0).setDuration(on ? 180 : 80).start();
    }

    private void ensureLoaded() {
        if (loaded || url == null || url.isEmpty()) return;
        loaded = true;
        ImageLoader.load(url, poster, w, h);
    }

    @Override public void onActiveChanged(boolean active) {
        if (active) ensureLoaded();
        else if (loaded && !hasFocus()) {
            loaded = false; blurLoaded = false;
            poster.setTag(ink.pengfei.lvguo.R.id.img_tag, null); poster.setImageDrawable(null);
            blur.setTag(ink.pengfei.lvguo.R.id.img_tag, null); blur.setImageDrawable(null);
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
