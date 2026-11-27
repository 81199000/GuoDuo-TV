package ink.pengfei.lvguo;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 全局：设备身份、线程池、偏好设置。TV 端不依赖任何外部服务。 */
public final class App extends Application {

    private static App sInst;
    private static ExecutorService sPool;
    private static ExecutorService sIo;
    private SharedPreferences mPrefs;

    /** 服务端注册的设备身份；cdid 是独立的 AppLog 安装标识。 */
    public static volatile String deviceId;
    public static volatile String iid;
    public static volatile String cdid;

    /** 内容接口 User-Agent（与 {@link ink.pengfei.lvguo.net.RedFruitUserAgent} 版本号一致）。 */
    public static final String UA =
            "com.phoenix.read/74132 (Linux; U; Android 13; zh_CN; 22041211AC; "
                    + "Build/TKQ1.220807.001;tt-ok/3.12.13.20)";

    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        sPool = Executors.newFixedThreadPool(4);
        // IO 线程池：接口/播放源/预热与图片并发执行，8 条线程避免封面下载把播放接口堵死。
        sIo = Executors.newFixedThreadPool(8);
        mPrefs = getSharedPreferences("lvguo", Context.MODE_PRIVATE);
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            int version = android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) packageInfo.getLongVersionCode() : packageInfo.versionCode;
            if (mPrefs.getInt("last_seen_app_version", 0) != version)
                mPrefs.edit().putInt("last_seen_app_version", version).remove("update_last_check").commit();
        } catch (Exception ignored) { }

        // 0.2.4 and older caches trusted any MP4 over 1 KiB. Drop only media/temp files once;
        // Store/library history and progress remain intact and continue to sync as before.
        if (mPrefs.getInt("playback_cache_epoch", 0) < 1) {
            ink.pengfei.lvguo.media.VideoCache.clearLegacyForUpgrade(this);
            mPrefs.edit().putInt("playback_cache_epoch", 1).commit();
        }

        // 旧版兼容身份先用于启动；DeviceIdentity 随后恢复独立 cdid 和已注册 DID/IID 对。
        deviceId = mPrefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = String.valueOf(1000000000000000L
                    + (long) (Math.random() * 8999999999999999L));
            mPrefs.edit().putString("device_id", deviceId).apply();
        }
        iid = deviceId;
        cdid = deviceId;
        ink.pengfei.lvguo.media.PlaybackCompatibility.initialize();
        ink.pengfei.lvguo.net.PlaybackConfig.init(this);
        ink.pengfei.lvguo.net.TVPlaybackModule.init(this);
        ink.pengfei.lvguo.net.DeviceIdentity.init();
        ink.pengfei.lvguo.net.LibrarySync.init();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity a) { sTop = new java.lang.ref.WeakReference<>(a); }
            @Override public void onActivityPaused(android.app.Activity a) { if (sTop.get() == a) sTop.clear(); }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
    }

    // ---- 果多：当前在前台的界面（手机遥控的方向键直接投递给它，和电视遥控器一模一样）
    private static java.lang.ref.WeakReference<android.app.Activity> sTop = new java.lang.ref.WeakReference<>(null);
    public static android.app.Activity topActivity() { return sTop.get(); }
    /** 这台电视的固定编号（手机 App 绑定用），首次生成后不变。 */
    public static String tvId() {
        String id = prefs().getString("tv_id", null);
        if (id == null) {
            byte[] b = new byte[8]; new java.security.SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x & 255));
            id = s.toString(); prefs().edit().putString("tv_id", id).apply();
        }
        return id;
    }
    // ---- 果多：所有弹窗都登记在这里，手机遥控的按键先发给最上面那个弹窗（退出确认、选集、选项…）
    private static final java.util.List<java.lang.ref.WeakReference<android.app.Dialog>> sDialogs = new java.util.ArrayList<>();
    public static void dialogShown(android.app.Dialog d) {
        synchronized (sDialogs) {
            for (java.util.Iterator<java.lang.ref.WeakReference<android.app.Dialog>> it = sDialogs.iterator(); it.hasNext(); ) { android.app.Dialog x = it.next().get(); if (x == null || x == d) it.remove(); }
            sDialogs.add(new java.lang.ref.WeakReference<>(d));
        }
    }
    /** 当前最上面、正在显示的弹窗（没有则 null）。 */
    public static android.app.Dialog topDialog() {
        synchronized (sDialogs) {
            for (int i = sDialogs.size() - 1; i >= 0; i--) {
                android.app.Dialog d = sDialogs.get(i).get();
                if (d != null && d.isShowing() && d.getWindow() != null && d.getWindow().getDecorView().isShown()) return d;
                if (d == null || !d.isShowing()) sDialogs.remove(i);
            }
        }
        return null;
    }

    public static String tvName() {
        String n = prefs().getString("tv_name", null);
        return n != null && !n.isEmpty() ? n : "GuoDuo · " + android.os.Build.MODEL;
    }

    @Override public void onTrimMemory(int level){super.onTrimMemory(level);ink.pengfei.lvguo.img.ImageLoader.trimMemory(level);}
    @Override public void onLowMemory(){super.onLowMemory();ink.pengfei.lvguo.img.ImageLoader.clearMemory();}

    public static Context ctx() { return sInst; }

    public static ExecutorService pool() { return sPool; }

    /** IO 密集任务（网络、磁盘）。与 pool 分开，避免下载把接口请求饿死。 */
    public static ExecutorService io() { return sIo; }

    public static SharedPreferences prefs() { return sInst.mPrefs; }
}

