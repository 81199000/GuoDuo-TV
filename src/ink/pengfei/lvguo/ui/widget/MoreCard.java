package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.lvguo.ui.Ui;

/** 首页每一行最后的「更多」卡：和海报同高，玻璃底 + 箭头；聚焦白底黑字（tvOS 语言），点了进这个分类的全部。 */
public final class MoreCard extends FrameLayout {
    private final LinearLayout box;
    private final TextView arrow, label;

    public MoreCard(Context c, String title) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClipChildren(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        int pad = Ui.dp(c, 3);
        setPadding(pad, pad, pad, pad);
        box = Ui.column(c);
        box.setGravity(Gravity.CENTER);
        arrow = Ui.text(c, "›", 34, 0xFFFFFFFF, true);
        arrow.setGravity(Gravity.CENTER);
        arrow.setIncludeFontPadding(false);
        box.addView(arrow, Ui.lp(-2, -2));
        label = Ui.text(c, "更多", 14, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams ll = Ui.lp(-2, -2); ll.topMargin = Ui.dp(c, 4);
        box.addView(label, ll);
        TextView sub = Ui.ellipsis(c, title == null ? "" : title, 11, 0x99FFFFFF, 2);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2); sl.topMargin = Ui.dp(c, 4);
        sl.leftMargin = sl.rightMargin = Ui.dp(c, 8);
        box.addView(sub, sl);
        addView(box, new LayoutParams(Ui.dp(c, NfCard.W), Ui.dp(c, NfCard.H)));
        paint(false);
    }

    private void paint(boolean focus) {
        box.setBackground(Ui.round(getContext(), focus ? Ui.FOCUS_FILL : 0x1FFFFFFF, 10));
        int fg = focus ? Ui.ON_FOCUS : 0xFFFFFFFF;
        arrow.setTextColor(fg); label.setTextColor(fg);
        animate().cancel();
        animate().scaleX(focus ? 1.06f : 1f).scaleY(focus ? 1.06f : 1f).setDuration(150).start();
    }

    @Override protected void onFocusChanged(boolean gain, int direction, Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        paint(gain);
    }
}
