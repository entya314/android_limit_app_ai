package io.github.focusdpc

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * ライブラリを追加せずに、直列のワーカースレッドとAndroidのmainExecutorを使います。
 * 処理中フラグ・結果・購読者はメインスレッドだけで変更するため、競合を避けられます。
 */
class PolicyTaskRunner(context: Context) {
    private val appContext = context.applicationContext
    private val policyManager = PolicyManager(appContext)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FocusDPC-policy-worker")
    }
    private val listeners = mutableSetOf<(PolicyUiState) -> Unit>()
    private var state = PolicyUiState()

    fun observe(listener: (PolicyUiState) -> Unit) {
        requireMainThread()
        listeners += listener
        listener(state)
    }

    fun stopObserving(listener: (PolicyUiState) -> Unit) {
        requireMainThread()
        listeners -= listener
    }

    fun refresh() = submit(null)

    fun applyPolicies() = submit { policyManager.applyPolicies() }

    private fun submit(operation: (() -> PolicyActionResult)?) {
        requireMainThread()
        if (state.busy) return // 連打や画面回転による二重適用を防ぎます。
        publish(state.copy(busy = true))

        worker.execute {
            val result = try {
                operation?.invoke()
            } catch (exception: RuntimeException) {
                Log.e("FocusDPC.Worker", "Unexpected policy error", exception)
                PolicyActionResult(appContext.getString(R.string.operation_failed))
            }
            val status = policyManager.readStatus()
            appContext.mainExecutor.execute {
                publish(PolicyUiState(status, false, result ?: state.actionResult))
            }
        }
    }

    private fun publish(newState: PolicyUiState) {
        state = newState
        // 通知中に購読を解除しても走査中のSetを変更しないよう、コピーを使用します。
        listeners.toList().forEach { it(state) }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PolicyTaskRunnerは画面のメインスレッドから呼び出してください。"
        }
    }
}
