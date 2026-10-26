package io.github.focusdpc

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

/**
 * インストール禁止だけを操作するAndroid側の窓口です。Device Owner・DNS・削除禁止は変更しません。
 * 全ての呼出しを同じロックで直列化し、画面操作とPC操作・アラームの競合を防ぎます。
 */
internal class InstallMaintenanceManager(context: Context) {
    private val app = context.applicationContext
    private val storage = app.createDeviceProtectedStorageContext()
    private val prefs = storage.getSharedPreferences("install_maintenance", Context.MODE_PRIVATE)
    private val dpm = requireNotNull(app.getSystemService(DevicePolicyManager::class.java))
    private val users = requireNotNull(app.getSystemService(UserManager::class.java))
    private val alarms = requireNotNull(app.getSystemService(AlarmManager::class.java))
    private val admin = ComponentName(app, MyDeviceAdminReceiver::class.java)
    private val alarmIntent: PendingIntent
        get() = PendingIntent.getBroadcast(app, 0,
            Intent(app, InstallMaintenanceReceiver::class.java).setAction(ACTION_EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val retryIntent: PendingIntent
        get() = PendingIntent.getBroadcast(app, 1,
            Intent(app, InstallMaintenanceReceiver::class.java).setAction(ACTION_RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private val controller = InstallMaintenanceController(object : InstallMaintenanceController.Backend {
        override fun isOwner() = dpm.isDeviceOwnerApp(app.packageName)
        override fun isBlocked() = users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
        override fun setBlocked(blocked: Boolean) {
            if (blocked) dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            else dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun bootCount() = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        override fun load(): InstallMaintenanceController.Lease? {
            if (!prefs.contains("pending")) {
                check(prefs.all.isEmpty()) { "Incomplete maintenance record" }
                return null
            }
            check(prefs.getBoolean("pending", false))
            return InstallMaintenanceController.Lease(
                prefs.getLong("started", -1), prefs.getLong("deadline", -1), prefs.getInt("boot", -1))
        }
        override fun save(lease: InstallMaintenanceController.Lease?) {
            val edit = prefs.edit().clear()
            if (lease != null) edit.putBoolean("pending", true).putLong("started", lease.started)
                .putLong("deadline", lease.deadline).putInt("boot", lease.boot)
            check(edit.commit()) { "Cannot persist maintenance deadline" }
        }
        override fun schedule(deadline: Long) {
            check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                "Exact alarm permission is required before opening maintenance"
            }
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarmIntent)
            alarms.cancel(retryIntent)
        }
        override fun cancel() {
            alarms.cancel(alarmIntent)
            alarms.cancel(retryIntent)
        }
        override fun retry() {
            // 保存にも失敗して記録が読めない場合でも、次回は状態確認ではなく禁止操作を実行します。
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 30_000L, retryIntent)
        }
    })

    fun open() = synchronized(LOCK) { report(controller.open()) }
    fun status() = synchronized(LOCK) { report(controller.status()) }
    fun close() = synchronized(LOCK) { report(controller.close()) }
    fun onBoot() = synchronized(LOCK) { report(controller.onBoot()) }

    /** DNS接続確認とは別のワーカーです。期限の復帰をネットワーク処理の後ろへ並べません。 */
    fun reconcileAsync(afterBoot: Boolean = false, forceClose: Boolean = false, onComplete: () -> Unit = {}) {
        RECOVERY_WORKER.execute {
            try {
                if (forceClose) close() else if (afterBoot) onBoot() else status()
            } catch (exception: RuntimeException) {
                Log.e("FocusDPC.Maintenance", "Cannot reconcile maintenance", exception)
            } finally {
                onComplete()
            }
        }
    }

    /** テスト後片付け専用。PCからの一時許可操作と後片付けが競合しないようにします。 */
    fun <T> withLock(action: () -> T): T = synchronized(LOCK) { action() }

    private fun report(result: InstallMaintenanceController.Result): InstallMaintenanceController.Result {
        if (!result.success) Log.w("FocusDPC.Maintenance", "Maintenance: ${result.code}")
        return result
    }

    companion object {
        const val ACTION_EXPIRE = "io.github.focusdpc.INSTALL_MAINTENANCE_EXPIRE"
        const val ACTION_RETRY = "io.github.focusdpc.INSTALL_MAINTENANCE_RETRY"
        private val LOCK = Any()
        private val RECOVERY_WORKER = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FocusDPC-install-recovery")
        }
    }
}
