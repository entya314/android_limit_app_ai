package io.github.focusdpc

/** Android APIから独立した判定。履歴はメモリ内だけに保持し、時間経過だけでは再試行しません。 */
internal class LineBrowserBackGuard {
    private var latestEventTime = -1L
    private var backAttempted = false

    fun evaluate(
        packageName: String?,
        className: String?,
        eventTime: Long,
        now: Long,
        foregroundMatches: Boolean
    ): Decision {
        if (packageName != LINE_PACKAGE) return Decision.NOT_LINE
        if (eventTime <= latestEventTime || eventTime < 0 || eventTime > now ||
            now - eventTime > MAX_EVENT_AGE_MS
        ) return Decision.STALE
        latestEventTime = eventTime
        if (!foregroundMatches) return Decision.UNCONFIRMED_WINDOW

        // 汎用View・Dialog・未知のActivityでは再許可しません。既知の通常画面への復帰が必要です。
        if (className == CHAT_ACTIVITY || className == MAIN_ACTIVITY) {
            backAttempted = false
            return Decision.REARMED
        }
        if (className != BROWSER_ACTIVITY) return Decision.NOT_BROWSER
        if (backAttempted) return Decision.DUPLICATE
        // APIがfalseを返しても消費します。自動リトライでトークを閉じる危険を避けます。
        backAttempted = true
        return Decision.BACK
    }

    enum class Decision { NOT_LINE, STALE, UNCONFIRMED_WINDOW, REARMED, NOT_BROWSER, DUPLICATE, BACK }

    companion object {
        const val LINE_PACKAGE = "jp.naver.line.android"
        const val BROWSER_ACTIVITY = "com.linecorp.line.iab.browser.impl.InAppBrowserActivity"
        const val CHAT_ACTIVITY = "jp.naver.line.android.activity.chathistory.ChatHistoryActivity"
        const val MAIN_ACTIVITY = "jp.naver.line.android.activity.main.MainActivity"
        const val MAX_EVENT_AGE_MS = 500L
    }
}
