package io.github.focusdpc

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/** 画面の担当です。ポリシーの具体的なAPI呼び出しはPolicyManagerに集約しています。 */
class MainActivity : Activity() {
    private val tasks: PolicyTaskRunner
        get() = (application as FocusDpcApplication).policyTasks
    private val stateListener: (PolicyUiState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureWindowInsets()

        findViewById<TextView>(R.id.configured_hostname).text =
            getString(R.string.configured_hostname, PolicyConfig.NEXTDNS_HOSTNAME)
        // 画面から制限を解除できないよう、適用と状態更新だけを用意します。
        // テスト中の復旧は、手順書にあるADBからのDevice Owner解除で行います。
        findViewById<Button>(R.id.apply_button).setOnClickListener { tasks.applyPolicies() }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { tasks.refresh() }
    }

    override fun onStart() {
        super.onStart()
        tasks.observe(stateListener)
    }

    override fun onResume() {
        super.onResume()
        // 設定画面やADB側で変更されている場合も、戻ってきたときに最新状態を読みます。
        tasks.refresh()
    }

    override fun onStop() {
        // 破棄されたActivityをApplicationが保持し続けることを防ぎます。
        tasks.stopObserving(stateListener)
        super.onStop()
    }

    private fun render(ui: PolicyUiState) {
        val status = ui.status
        showStatus(R.id.owner_status, status?.deviceOwner)
        showStatus(R.id.dns_status, status?.nextDnsConfigured, isDns = true)
        showStatus(R.id.dns_restriction_status, status?.privateDnsChangesBlocked)
        showStatus(R.id.install_status, status?.appInstallationBlocked)
        showStatus(R.id.uninstall_status, status?.selfUninstallBlocked)

        findViewById<Button>(R.id.apply_button).isEnabled = !ui.busy
        findViewById<Button>(R.id.refresh_button).isEnabled = !ui.busy
        findViewById<ProgressBar>(R.id.progress).visibility = if (ui.busy) View.VISIBLE else View.GONE

        val hostView = findViewById<TextView>(R.id.current_hostname)
        hostView.text = getString(R.string.current_hostname, status?.currentDnsHostname ?: getString(R.string.none))
        findViewById<TextView>(R.id.owner_hint).visibility =
            if (status?.deviceOwner == true) View.GONE else View.VISIBLE

        val resultView = findViewById<TextView>(R.id.action_result)
        val details = buildList {
            ui.actionResult?.let { result ->
                add(result.summary)
                result.steps.forEach { step -> add(getString(R.string.result_line, step.label, step.detail)) }
            }
            status?.readErrors?.let { addAll(it) }
        }
        resultView.text = details.joinToString("\n")
        resultView.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showStatus(viewId: Int, value: Boolean?, isDns: Boolean = false) {
        findViewById<TextView>(viewId).apply {
            setText(when (value) {
                true -> if (isDns) R.string.state_configured else R.string.state_enabled
                false -> if (isDns) R.string.state_unconfigured else R.string.state_disabled
                null -> R.string.state_unknown
            })
            setTextColor(getColor(if (value == true) R.color.active else R.color.muted))
        }
    }

    /** Android 15以降のedge-to-edge表示でも、ボタンがシステムバーに隠れないようにします。 */
    private fun configureWindowInsets() {
        if (Build.VERSION.SDK_INT >= 35) {
            val root = findViewById<View>(R.id.main_scroll)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
    }
}
