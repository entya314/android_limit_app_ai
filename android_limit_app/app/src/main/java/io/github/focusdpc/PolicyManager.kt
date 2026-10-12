package io.github.focusdpc

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.os.UserManager
import android.util.Log

/**
 * 端末管理APIを呼び出す唯一のクラスです。画面やReceiverからポリシーを直接変更しません。
 * UIからはPolicyTaskRunnerを経由し、ブロッキングするDNS設定を必ず別スレッドで実行します。
 *
 * 公式仕様: https://developer.android.com/reference/android/app/admin/DevicePolicyManager
 */
class PolicyManager(
    context: Context,
    // テストでは到達不能なホストを注入できます。通常のアプリはPolicyConfigの定数を使います。
    private val nextDnsHostname: String = PolicyConfig.NEXTDNS_HOSTNAME,
) {
    private val appContext = context.applicationContext
    private val devicePolicyManager = requireNotNull(appContext.getSystemService(DevicePolicyManager::class.java))
    private val userManager = requireNotNull(appContext.getSystemService(UserManager::class.java))
    private val admin = ComponentName(appContext, MyDeviceAdminReceiver::class.java)

    /** 制限は実効値を表示します。他の管理者による制限もUserManagerで検出できます。 */
    fun readStatus(): PolicyState {
        val errors = mutableListOf<String>()

        fun <T> read(label: Int, block: () -> T): T? = try {
            block()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Cannot read ${appContext.getString(label)}", exception)
            errors += appContext.getString(R.string.read_error, appContext.getString(label))
            null
        }

        val owner = read(R.string.label_device_owner) {
            devicePolicyManager.isDeviceOwnerApp(appContext.packageName)
        }

        // DNSとアンインストール禁止の取得はDevice Owner権限が必要です。
        // 権限がないときはAPIを呼ばず、未確認(null)として画面に伝えます。
        val mode = if (owner == true) read(R.string.label_nextdns) {
            devicePolicyManager.getGlobalPrivateDnsMode(admin)
        } else null
        val host = if (owner == true) read(R.string.label_nextdns) {
            devicePolicyManager.getGlobalPrivateDnsHost(admin)
        } else null
        val configured = when {
            mode == null || mode == DevicePolicyManager.PRIVATE_DNS_MODE_UNKNOWN -> null
            mode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME -> false
            host == null -> null
            else -> PolicyConfig.isUsableHostname(nextDnsHostname) &&
                host.equals(nextDnsHostname, ignoreCase = true)
        }

        return PolicyState(
            deviceOwner = owner,
            nextDnsConfigured = configured,
            privateDnsChangesBlocked = read(R.string.label_dns_restriction) {
                userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            },
            appInstallationBlocked = read(R.string.label_install_restriction) {
                userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
            },
            selfUninstallBlocked = if (owner == true) read(R.string.label_uninstall_restriction) {
                devicePolicyManager.isUninstallBlocked(admin, appContext.packageName)
            } else null,
            currentDnsHostname = host,
            readErrors = errors.distinct(),
        )
    }

    /**
     * 制限を個別に適用します。1つ失敗しても結果を報告し、他の操作を続けます。
     * DNS設定に失敗した場合だけは、DNS変更禁止を新たに設定しません。
     * これにより、接続できないDNSやプレースホルダーを固定してしまうことを防ぎます。
     */
    fun applyPolicies(): PolicyActionResult {
        requireWorkerThread()
        ownerError()?.let { return it }
        val steps = mutableListOf<PolicyStepResult>()

        val dnsResult = configurePrivateDns()
        steps += dnsResult
        if (dnsResult.success) {
            steps += runStep(R.string.label_dns_restriction) {
                devicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
        } else {
            steps += PolicyStepResult(
                appContext.getString(R.string.label_dns_restriction),
                false,
                appContext.getString(R.string.dns_restriction_skipped),
            )
        }

        steps += runStep(R.string.label_install_restriction) {
            devicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }
        steps += runStep(R.string.label_uninstall_restriction) {
            devicePolicyManager.setUninstallBlocked(admin, appContext.packageName, true)
        }

        // 将来の検討箇所: DISALLOW_DEBUGGING_FEATURESをここで追加すればADB等を制限できます。
        // ★ β版では実行しないこと。更新・ログ取得・Device Owner解除の経路を失うためです。
        // DISALLOW_FACTORY_RESETもβ版では追加しません。
        // clearDeviceOwnerApp()は非推奨です。解除はtestOnly APKとADBのremove-active-adminで行います。

        return summarize(steps, R.string.apply_complete, R.string.apply_partial)
    }

    /**
     * β版の回復用操作です。Device Owner登録自体は残し、制限とDNS固定を解除します。
     * 「以前のDNS値」の復元ではなく「自動」への変更です。画面でもその旨を確認します。
     * 解除も個別実行し、1つ失敗してもインストールやDNS編集の回復を試みます。
     */
    fun clearBetaPolicies(): PolicyActionResult {
        requireWorkerThread()
        ownerError()?.let { return it }
        val steps = mutableListOf<PolicyStepResult>()
        steps += runStep(R.string.label_dns_restriction, R.string.step_cleared) {
            devicePolicyManager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
        steps += runStep(R.string.label_install_restriction, R.string.step_cleared) {
            devicePolicyManager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }
        steps += runStep(R.string.label_uninstall_restriction, R.string.step_cleared) {
            devicePolicyManager.setUninstallBlocked(admin, appContext.packageName, false)
        }
        steps += runStep(R.string.label_nextdns, R.string.dns_automatic) {
            val code = devicePolicyManager.setGlobalPrivateDnsModeOpportunistic(admin)
            check(code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) { dnsErrorMessage(code) }
        }
        return summarize(steps, R.string.clear_complete, R.string.clear_partial)
    }

    private fun configurePrivateDns(): PolicyStepResult {
        val label = appContext.getString(R.string.label_nextdns)
        if (!PolicyConfig.isUsableHostname(nextDnsHostname)) {
            return PolicyStepResult(label, false, appContext.getString(R.string.dns_configuration_missing))
        }

        return runStep(R.string.label_nextdns) {
            // 公式APIはDNS over TLSへの接続確認を行います。数秒以上かかることがあります。
            val code = devicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost(admin, nextDnsHostname)
            check(code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) { dnsErrorMessage(code) }

            // 戻り値だけでなく実際の設定も確認してから、ユーザーによるDNS編集を制限します。
            check(devicePolicyManager.getGlobalPrivateDnsMode(admin) ==
                DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                devicePolicyManager.getGlobalPrivateDnsHost(admin).equals(nextDnsHostname, ignoreCase = true)) {
                appContext.getString(R.string.dns_verification_failed)
            }
        }
    }

    private fun ownerError(): PolicyActionResult? = try {
        if (devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) null
        else PolicyActionResult(appContext.getString(R.string.not_device_owner))
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Device Owner check failed", exception)
        PolicyActionResult(appContext.getString(R.string.owner_check_failed))
    }

    /** Androidの権限エラーや引数エラーを、クラッシュではなく操作結果として返します。 */
    private fun runStep(
        label: Int,
        successMessage: Int = R.string.step_applied,
        block: () -> Unit,
    ): PolicyStepResult {
        val labelText = appContext.getString(label)
        return try {
            block()
            PolicyStepResult(labelText, true, appContext.getString(successMessage))
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Policy operation failed: $labelText", exception)
            val detail = when (exception) {
                is SecurityException -> appContext.getString(R.string.permission_error)
                is IllegalArgumentException -> appContext.getString(R.string.argument_error)
                else -> exception.message?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.operation_failed)
            }
            PolicyStepResult(labelText, false, detail)
        }
    }

    private fun dnsErrorMessage(code: Int): String = when (code) {
        DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
            appContext.getString(R.string.dns_host_not_serving)
        DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING ->
            appContext.getString(R.string.dns_setting_failed)
        else -> appContext.getString(R.string.dns_unknown_error, code)
    }

    private fun summarize(steps: List<PolicyStepResult>, complete: Int, partial: Int) =
        PolicyActionResult(appContext.getString(if (steps.all { it.success }) complete else partial), steps)

    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "PolicyManagerの変更操作はPolicyTaskRunner経由で実行してください。"
        }
    }

    private companion object {
        const val TAG = "FocusDPC.Policy"
    }
}
