package ink.pengfei.lvguo.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * 接收 PackageInstaller 会话状态：需要用户确认时拉起系统安装确认界面。
 * 比老式 ACTION_VIEW 更可靠——不依赖设备上有「安装包查看器」Activity（TV/投影仪常没有）。
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                boolean started = false;
                if (confirm != null && ctx.getPackageManager().resolveActivity(confirm, 0) != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); started = true; } catch (Throwable ignored) {}
                }
                // 系统没有会话确认页：放弃这个会话，改走标准安装页
                if (!started) {
                    int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                    if (id >= 0) try { ctx.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Throwable ignored) {}
                    UpdateInstaller.fallbackFromReceiver(ctx);
                }
                break;
            }
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                break; // 用户取消，静默
            case PackageInstaller.STATUS_SUCCESS:
                break; // 安装成功，系统会自行处理
            default: {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                try { Toast.makeText(ctx, "安装失败" + (msg == null ? "" : "：" + msg), Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            }
        }
    }
}
