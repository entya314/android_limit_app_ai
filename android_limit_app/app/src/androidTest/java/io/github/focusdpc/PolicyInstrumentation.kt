package io.github.focusdpc

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager

/**
 * 追加のテストライブラリを使わず、実際の端末管理APIを検証します。
 * Device Ownerの登録・解除はscripts/emulator_smoke_test.pyがADBで実施します。
 * 制限を変更するため、実機での実行を明示的に拒否します。
 */
class PolicyInstrumentation : Instrumentation() {
    private var scenario = "non_owner"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenario = arguments?.getString("scenario") ?: "non_owner"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
                "このテストはAndroid Emulator専用です。実機では制限を変更しません。"
            }
            val manager = PolicyManager(targetContext)
            val dpm = requireNotNull(targetContext.getSystemService(DevicePolicyManager::class.java))
            val admin = ComponentName(targetContext, MyDeviceAdminReceiver::class.java)
            val userManager = requireNotNull(targetContext.getSystemService(UserManager::class.java))

            when (scenario) {
                "non_owner" -> {
                    check(manager.readStatus().deviceOwner == false)
                    check(manager.applyPolicies().summary == targetContext.getString(R.string.not_device_owner))
                    check(manager.clearBetaPolicies().summary == targetContext.getString(R.string.not_device_owner))
                    check(manager.readStatus().nextDnsConfigured == null)
                    check(manager.readStatus().selfUninstallBlocked == null)
                }
                "owner_apply", "owner_repeat" -> {
                    check(manager.readStatus().deviceOwner == true)
                    val result = manager.applyPolicies()
                    check(result.steps.size == 4)
                    val state = manager.readStatus()
                    check(state.appInstallationBlocked == true)
                    check(state.selfUninstallBlocked == true)
                    if (!PolicyConfig.isUsableHostname(PolicyConfig.NEXTDNS_HOSTNAME)) {
                        check(!result.steps[0].success)
                        check(!result.steps[1].success)
                        check(state.privateDnsChangesBlocked == false)
                        check(state.nextDnsConfigured == false)
                    }
                    check(!userManager.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES))
                    check(!userManager.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET))
                }
                "owner_dns_failure" -> {
                    check(manager.readStatus().deviceOwner == true)
                    // API 29はアプリ側でTLS接続を確認します。権限不足による失敗を取り違えません。
                    check(targetContext.checkSelfPermission(Manifest.permission.INTERNET) ==
                        PackageManager.PERMISSION_GRANTED)
                    check(manager.clearBetaPolicies().steps.all { it.success })
                    // .invalidは予約ドメインです。実際のDNS APIの失敗経路を検証します。
                    val failureManager = PolicyManager(targetContext, "focusdpc-test.invalid")
                    val result = failureManager.applyPolicies()
                    check(!result.steps[0].success)
                    check(result.steps[0].detail == targetContext.getString(R.string.dns_host_not_serving))
                    check(!result.steps[1].success)
                    check(result.steps[2].success && result.steps[3].success)
                    val state = failureManager.readStatus()
                    check(state.nextDnsConfigured == false)
                    check(state.privateDnsChangesBlocked == false)
                    check(state.appInstallationBlocked == true)
                    check(state.selfUninstallBlocked == true)
                }
                "owner_clear" -> {
                    check(manager.readStatus().deviceOwner == true)
                    check(manager.clearBetaPolicies().steps.all { it.success })
                    val state = manager.readStatus()
                    check(state.appInstallationBlocked == false)
                    check(state.privateDnsChangesBlocked == false)
                    check(state.selfUninstallBlocked == false)
                    check(dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC)
                    // 制限解除ボタンはDevice Owner自体を解除しません。
                    check(state.deviceOwner == true)
                }
                "hostname_validation" -> {
                    check(targetContext.checkSelfPermission(Manifest.permission.INTERNET) ==
                        PackageManager.PERMISSION_GRANTED)
                    check(!PolicyConfig.isUsableHostname("YOUR_ID.dns.nextdns.io"))
                    check(!PolicyConfig.isUsableHostname("your_id.dns.nextdns.io"))
                    check(!PolicyConfig.isUsableHostname("https://abcdef.dns.nextdns.io"))
                    check(!PolicyConfig.isUsableHostname("bad host.dns.nextdns.io"))
                    check(!PolicyConfig.isUsableHostname("-abc.dns.nextdns.io"))
                    check(!PolicyConfig.isUsableHostname("abc-.dns.nextdns.io"))
                    check(!PolicyConfig.isUsableHostname("a".repeat(64) + ".dns.nextdns.io"))
                    check(!PolicyConfig.isUsableHostname(""))
                    check(PolicyConfig.isUsableHostname("abcdef.dns.nextdns.io"))
                    check(PolicyConfig.isUsableHostname("my-phone-abcdef.dns.nextdns.io"))
                }
                else -> error("不明なテスト: $scenario")
            }
            output.putString("stream", "\nFOCUSDPC_TEST_PASS: $scenario\n")
            finish(Activity.RESULT_OK, output)
        } catch (exception: Exception) {
            output.putString("stream", "\nFOCUSDPC_TEST_FAIL: $scenario\n${exception.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
