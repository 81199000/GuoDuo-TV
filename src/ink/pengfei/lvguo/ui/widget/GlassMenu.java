package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 果多右侧「液态玻璃」竖长条胶囊菜单：每项图标在上、一行小字在下（有当前值就显示值，如 1.0x / 1080P，
 * 没有就显示名称），遥控器上下选择、确定执行。外形两头全圆，选中项是圆角白底高亮。
 * 不抢 Android 焦点（页面原来的焦点保持不动），5 秒无操作自动收起。
 */
public final class GlassMenu extends LinearLayout {
    public static final long AUTO_HIDE_MS = 5000;
    /** 竖长条胶囊宽度：图标在上、一行小字在下（参考图样式）。 */
    public static final int WIDTH_DP = 84;
    /** 子面板（倍速 / 弹幕设置…）开着时返回 true：期间不自动收起。 */
    public interface Hold { boolean active(); }

    public final class Item {
        final LinearLayout row; final IconView icon; final TextView title, sub; final Toggle toggle;
        final Runnable action; boolean current;
        String titleText, subText = "";
        Item(LinearLayout row, IconView icon, TextView title, TextView sub, Toggle toggle, Runnable action, String text) {
            this.row = row; this.icon = icon; this.title = title; this.sub = sub; this.toggle = toggle; this.action = action;
            this.titleText = text;
        }
        /** 图标下面那行字：有当前值（倍速 / 画质）显示值，否则显示名称。 */
        private void label() { title.setText(subText.isEmpty() ? titleText : subText); }
        public Item title(String s) { titleText = s == null ? "" : s; label(); return this; }
        public Item sub(String s) { subText = s == null ? "" : s; label(); return this; }
        public Item toggle(Boolean on) { if (on != null) toggle.setOn(on); return this; }   // 长条胶囊里不显示开关
        public Item filled(boolean f) { icon.setFilled(f); return this; }
        public Item current(boolean c) { current = c; paint(); return this; }
    }

    private final List<Item> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Hold hold;
    private boolean autoHideOn = true;
    private final Runnable autoHide = () -> {
        if (hold != null && hold.active()) { poke(); return; }
        hide();
    };
    private int selected;
    private boolean showing;
    private Runnable onHidden;

    public GlassMenu(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Ui.liquidGlass(c, WIDTH_DP / 2f));   // 圆角 = 半宽 → 两头全圆的长条胶囊
        Ui.padding(this, 7, 18, 7, 18);
        setVisibility(GONE);
        setClickable(true);
        setFocusable(false);
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    public Item add(int iconType, String text, Runnable action) {
        Context c = getContext();
        LinearLayout row = Ui.column(c);
        row.setGravity(android.view.Gravity.CENTER);
        Ui.padding(row, 0, 9, 0, 8);
        IconView icon = new IconView(c, iconType, 28);
        row.addView(icon, Ui.lp(dp(28), dp(28)));
        TextView t = Ui.text(c, text, 12, 0xD9FFFFFF, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(android.view.Gravity.CENTER);
        LayoutParams tl = Ui.lp(-1, -2); tl.topMargin = dp(5);
        row.addView(t, tl);
        TextView s = Ui.text(c, "", 11, 0x99FFFFFF, false);   // 留着兼容旧接口，不显示
        s.setVisibility(GONE);
        Toggle tg = new Toggle(c);                              // 同上：菜单上不再显示开关
        tg.setVisibility(GONE);
        final int idx = items.size();
        Item it = new Item(row, icon, t, s, tg, action, text);
        row.setOnClickListener(v -> { selected = idx; paint(); poke(); fire(idx); });
        LayoutParams rl = Ui.lp(-1, -2);
        if (!items.isEmpty()) rl.topMargin = dp(6);
        addView(row, rl);
        items.add(it);
        paint();
        return it;
    }

    public void setOnHidden(Runnable r) { onHidden = r; }
    public void setHold(Hold h) { hold = h; }
    /** 用户主动点开的下拉（如首页「类别」）不自动收起，只有返回键 / 选了一项才收。 */
    public void setAutoHide(boolean on) { autoHideOn = on; poke(); }
    public boolean isShowing() { return showing; }
    public int selectedIndex() { return selected; }

    public void show(int select) {
        selected = Math.max(0, Math.min(items.size() - 1, select));
        paint();
        if (!showing) {
            showing = true;
            animate().cancel();
            setVisibility(VISIBLE);
            setAlpha(0f);
            setTranslationX(dp(28));
            animate().alpha(1f).translationX(0).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
        bringToFront();
        poke();
    }

    public void hide() {
        if (!showing) return;
        showing = false;
        handler.removeCallbacks(autoHide);
        animate().cancel();
        animate().alpha(0f).translationX(dp(28)).setDuration(160)
                .withEndAction(() -> { if (!showing) setVisibility(GONE); }).start();
        if (onHidden != null) onHidden.run();
    }

    /** 有操作：重新计 5 秒。 */
    public void poke() {
        handler.removeCallbacks(autoHide);
        if (showing && autoHideOn) handler.postDelayed(autoHide, AUTO_HIDE_MS);
    }

    /** 子面板打开期间不自动收起；子面板关掉后调 poke() 重新计时。 */
    public void holdTimer() { handler.removeCallbacks(autoHide); }

    /** 菜单显示时由宿主转发按键：上下选择、确定执行，左右吞掉。 */
    public boolean handleKey(KeyEvent e) {
        if (!showing) return false;
        poke();
        int k = e.getKeyCode();
        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean dpad = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!center && !dpad) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (k == KeyEvent.KEYCODE_DPAD_UP) move(-1);
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) move(1);
        else if (center && e.getRepeatCount() == 0) fire(selected);
        return true;
    }

    private void move(int d) {
        int n = selected + d;
        if (n < 0 || n >= items.size()) return;
        selected = n;
        paint();
    }

    private void fire(int idx) {
        if (idx < 0 || idx >= items.size()) return;
        Runnable a = items.get(idx).action;
        if (a != null) a.run();
    }

    private void paint() {
        Context c = getContext();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            boolean sel = i == selected;
            it.row.setBackground(sel ? Ui.round(c, 0xF2F5F5F7, 22) : null);   // 选中：圆角白底
            int fg = sel ? 0xFF111113 : it.current ? Ui.ACCENT_TEXT : 0xFFFFFFFF;
            it.icon.setColor(fg);
            it.title.setTextColor(sel ? 0xCC111113 : it.current ? Ui.ACCENT_TEXT : 0xD9FFFFFF);
        }
    }

    /** 小开关：开 = 果红轨道 + 白圆点靠右，关 = 半透明灰轨道 + 白圆点靠左。 */
    static final class Toggle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;
        Toggle(Context c) { super(c); }
        void setOn(boolean v) { on = v; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            p.setColor(on ? Ui.PRIMARY : 0x59FFFFFF);
            c.drawRoundRect(0, 0, w, h, r, r, p);
            p.setColor(0xFFFFFFFF);
            float kr = r - Ui.dp(getContext(), 2);
            c.drawCircle(on ? w - r : r, h / 2f, kr, p);
        }
    }
}
