package io.github.focusdpc

import android.app.Application

/**
 * Activityより長く生きるApplicationに処理を保持します。
 * 画面を回転したり一時的に別アプリへ移動したりしても、DNS設定を二重に実行しません。
 */
class FocusDpcApplication : Application() {
    val policyTasks: PolicyTaskRunner by lazy { PolicyTaskRunner(this) }
    internal val installMaintenance: InstallMaintenanceManager by lazy { InstallMaintenanceManager(this) }

    override fun onCreate() {
        super.onCreate()
        // プロセスが作り直されても、保存済みの期限を確認します。許可の延長は行いません。
        installMaintenance.reconcileAsync()
    }
}
