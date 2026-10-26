package io.github.focusdpc

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** LINEの既知の内部ブラウザだけを閉じます。ポリシーや既存アプリの起動には関与しません。 */
class LineBrowserAccessibilityService : AccessibilityService() {
    private val guard = LineBrowserBackGuard()
    private val debugLogging: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onServiceConnected() {
        debugLog("connected; exact Activity match only; no content inspection")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.packageName?.toString() != LineBrowserBackGuard.LINE_PACKAGE
        ) return

        val className = event.className?.toString()
        val now = SystemClock.uptimeMillis() // AccessibilityEvent.eventTimeと同じクロックです。
        // root/source/textには触れず、現在のウィンドウのID・種類・フォーカスだけ照合します。
        // トークや他アプリへ既に切り替わっていれば、過去のブラウザイベントではBACKしません。
        val foregroundMatches = try {
            event.windowId >= 0 && windows.any {
                it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.isActive && it.isFocused
            }
        } catch (_: RuntimeException) {
            debugLog("window metadata unavailable; no BACK")
            false
        }
        val decision = guard.evaluate(
            event.packageName?.toString(), className, event.eventTime, now, foregroundMatches
        )
        debugLog(
            "type=WINDOW_STATE_CHANGED package=${event.packageName} class=$className " +
                "window=${event.windowId} ageMs=${now - event.eventTime} " +
                "foreground=$foregroundMatches decision=$decision"
        )
        if (decision == LineBrowserBackGuard.Decision.BACK) {
            // 遅延タスク・ループ・自動リトライは作りません。1回の起動につき最大1回です。
            val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
            debugLog("BACK accepted=$accepted (completion is not guaranteed)")
        }
    }

    override fun onInterrupt() {
        // 復帰時の重複BACKを避けるため、判定状態は消しません。
        debugLog("interrupted; no action")
    }

    private fun debugLog(message: String) {
        if (debugLogging) Log.d("LineBrowserBlock", message)
    }
}
