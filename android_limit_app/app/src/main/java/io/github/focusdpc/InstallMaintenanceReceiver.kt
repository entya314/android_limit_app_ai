package io.github.focusdpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 期限・起動・本体更新時の復帰用です。DNS処理とは別のワーカーを使い、解除は行いません。 */
class InstallMaintenanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!boot && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != InstallMaintenanceManager.ACTION_EXPIRE &&
            intent.action != InstallMaintenanceManager.ACTION_RETRY &&
            intent.action != "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") return
        val pending = goAsync()
        (context.applicationContext as FocusDpcApplication).installMaintenance.reconcileAsync(
            afterBoot = boot, forceClose = intent.action == InstallMaintenanceManager.ACTION_RETRY) {
            pending.finish()
        }
    }
}
