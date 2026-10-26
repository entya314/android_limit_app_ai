package io.github.focusdpc

import org.junit.Assert.assertEquals
import org.junit.Test

class LineBrowserBackGuardTest {
    private val guard = LineBrowserBackGuard()

    private fun event(
        className: String? = LineBrowserBackGuard.BROWSER_ACTIVITY,
        time: Long = 1_000,
        now: Long = time,
        foreground: Boolean = true,
        packageName: String? = LineBrowserBackGuard.LINE_PACKAGE
    ) = guard.evaluate(packageName, className, time, now, foreground)

    @Test fun onlyExactLineBrowserClassCanTriggerBack() {
        assertEquals(LineBrowserBackGuard.Decision.NOT_LINE, event(packageName = "other.app"))
        assertEquals(LineBrowserBackGuard.Decision.NOT_LINE, event(packageName = null))
        for ((index, name) in listOf(null, "android.webkit.WebView", "android.widget.FrameLayout",
            "InAppBrowserActivity", "com.linecorp.line.iab.browser.impl.InAppBrowserActivityExtra",
            "jp.naver.line.android.activity.call.CallActivity").withIndex()) {
            assertEquals(LineBrowserBackGuard.Decision.NOT_BROWSER, event(name, 1_000L + index))
        }
        assertEquals(LineBrowserBackGuard.Decision.BACK, event(time = 2_000))
    }

    @Test fun duplicateEventsNeverRetryEvenAfterLongDelayOrApiFailure() {
        assertEquals(LineBrowserBackGuard.Decision.BACK, event())
        assertEquals(LineBrowserBackGuard.Decision.STALE, event())
        assertEquals(LineBrowserBackGuard.Decision.DUPLICATE, event(time = 1_001))
        assertEquals(LineBrowserBackGuard.Decision.DUPLICATE, event(time = 60_000))
    }

    @Test fun everyReopenFromConfirmedChatOrMainCanCloseWithoutCooldown() {
        assertEquals(LineBrowserBackGuard.Decision.BACK, event())
        assertEquals(LineBrowserBackGuard.Decision.REARMED, event(LineBrowserBackGuard.CHAT_ACTIVITY, 1_001))
        assertEquals(LineBrowserBackGuard.Decision.BACK, event(time = 1_002))
        assertEquals(LineBrowserBackGuard.Decision.REARMED, event(LineBrowserBackGuard.MAIN_ACTIVITY, 1_003))
        assertEquals(LineBrowserBackGuard.Decision.BACK, event(time = 1_004))
    }

    @Test fun dialogOrUnknownClassCannotRearmAfterBack() {
        event()
        assertEquals(LineBrowserBackGuard.Decision.NOT_BROWSER, event("android.app.Dialog", 1_001))
        assertEquals(LineBrowserBackGuard.Decision.NOT_BROWSER, event(null, 1_002))
        assertEquals(LineBrowserBackGuard.Decision.DUPLICATE, event(time = 1_003))
    }

    @Test fun staleBrowserEventAfterReturnToChatCannotCloseChat() {
        event()
        event(LineBrowserBackGuard.CHAT_ACTIVITY, 1_010)
        assertEquals(LineBrowserBackGuard.Decision.STALE, event(time = 1_005, now = 1_011))
        assertEquals(LineBrowserBackGuard.Decision.BACK, event(time = 1_012))
    }

    @Test fun delayedAndFutureEventsAreIgnoredWithoutConsumingNextAttempt() {
        assertEquals(LineBrowserBackGuard.Decision.STALE, event(now = 1_501))
        assertEquals(LineBrowserBackGuard.Decision.STALE, event(time = 2_001, now = 2_000))
        assertEquals(LineBrowserBackGuard.Decision.STALE, event(time = -1, now = 2_000))
        assertEquals(LineBrowserBackGuard.Decision.BACK, event(time = 2_000))
    }

    @Test fun missingOrChangedForegroundWindowNeverTriggersBack() {
        assertEquals(LineBrowserBackGuard.Decision.UNCONFIRMED_WINDOW, event(foreground = false))
        assertEquals(LineBrowserBackGuard.Decision.BACK, event(time = 1_001))
    }

    @Test fun queuedChatEventCannotRearmWhileBrowserStillHasFocus() {
        event()
        assertEquals(LineBrowserBackGuard.Decision.UNCONFIRMED_WINDOW,
            event(LineBrowserBackGuard.CHAT_ACTIVITY, 1_001, foreground = false))
        assertEquals(LineBrowserBackGuard.Decision.DUPLICATE, event(time = 1_002))
    }

    @Test fun eventsFromOtherAppsCannotRearmOrChangeOrdering() {
        event()
        assertEquals(LineBrowserBackGuard.Decision.NOT_LINE,
            event(LineBrowserBackGuard.CHAT_ACTIVITY, 2_000, packageName = "other.app"))
        assertEquals(LineBrowserBackGuard.Decision.DUPLICATE, event(time = 1_001))
    }
}
