package ink.pengfei.lvguo.ui;

import android.content.Context;

/** 果多所有弹窗的基类：显示时登记到 App，手机遥控的按键能送到最上面这个弹窗里。 */
public class TrackedDialog extends android.app.Dialog {
    public TrackedDialog(Context c) { super(c); }
    public TrackedDialog(Context c, int theme) { super(c, theme); }
    @Override public void show() { super.show(); ink.pengfei.lvguo.App.dialogShown(this); }
}
