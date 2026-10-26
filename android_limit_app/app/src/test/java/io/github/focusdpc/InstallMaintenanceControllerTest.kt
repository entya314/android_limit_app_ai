package io.github.focusdpc

import org.junit.Assert.*
import org.junit.Test

/** 実時間を30分待たず、時刻・保存・Android APIの失敗を境界条件ごとに検証します。 */
class InstallMaintenanceControllerTest {
    private class Fake : InstallMaintenanceController.Backend {
        var owner = true
        @JvmField var blocked = true
        var now = 100_000L
        var boot = 7
        var lease: InstallMaintenanceController.Lease? = null
        var alarm: Long? = null
        var retryScheduled = false
        var failSchedule = false
        var failSave = false
        var failRestore = false
        var failOpen = false
        var restoreSilentlyFails = false
        var corrupt = false
        val events = mutableListOf<String>()
        override fun isOwner() = owner
        override fun isBlocked() = blocked
        override fun setBlocked(blocked: Boolean) {
            events += if (blocked) "block" else "unblock"
            if (blocked && failRestore) error("Policy API failure")
            if (!blocked && failOpen) {
                this.blocked = false // APIが変更を反映した後に通信例外を返す場合も想定します。
                error("Policy API failed after applying")
            }
            if (!(blocked && restoreSilentlyFails)) this.blocked = blocked
        }
        override fun elapsedRealtime() = now
        override fun bootCount() = boot
        override fun load(): InstallMaintenanceController.Lease? {
            if (corrupt) error("Corrupt storage")
            return lease
        }
        override fun save(lease: InstallMaintenanceController.Lease?) {
            events += if (lease == null) "clear" else "save"
            if (failSave) error("Storage failure")
            corrupt = false
            this.lease = lease
        }
        override fun schedule(deadline: Long) {
            events += "schedule"
            if (failSchedule) error("No exact alarm permission")
            alarm = deadline
        }
        override fun cancel() { events += "cancel"; alarm = null }
        override fun retry() { retryScheduled = true }
    }

    @Test fun schedulesAndPersistsBeforeUnlocking() {
        val b = Fake()
        val r = InstallMaintenanceController(b).open()
        assertTrue(r.success)
        assertEquals("OPENED", r.code)
        assertEquals(1800L, r.remainingSeconds)
        assertEquals(listOf("save", "schedule", "unblock"), b.events)
        assertEquals(1_900_000L, b.alarm)
        assertFalse(b.blocked)
    }

    @Test fun repeatedRequestsAndProcessRestartsDoNotExtendDeadline() {
        val b = Fake()
        InstallMaintenanceController(b).open()
        val original = b.lease
        b.now += 60_000
        val r = InstallMaintenanceController(b).open()
        assertEquals(1740L, r.remainingSeconds)
        assertEquals(original, b.lease)
        assertEquals(original?.deadline, b.alarm)
    }

    @Test fun expiryRestoresAtTheBoundaryAndCancelsAlarmLast() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        c.open()
        b.now = requireNotNull(b.lease).deadline - 1
        assertEquals(1L, c.status().remainingSeconds)
        assertFalse(b.blocked)
        b.now += 1
        assertEquals("BLOCKED", c.status().code)
        assertTrue(b.blocked)
        assertNull(b.lease)
        assertEquals(listOf("block", "clear", "cancel"), b.events.takeLast(3))
    }

    @Test fun aLateAlarmRestoresInsteadOfOpeningAnotherWindow() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        c.open()
        b.now += 2_000_000
        assertEquals("BLOCKED", c.open().code)
        assertTrue(b.blocked)
    }

    @Test fun rebootClosesEarlyEvenIfDeadlineHasNotExpired() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        c.open()
        assertEquals("BLOCKED", c.onBoot().code)
        assertTrue(b.blocked)
        assertNull(b.lease)
    }

    @Test fun changedBootCounterAndRolledBackElapsedClockFailClosed() {
        for (changeBoot in listOf(true, false)) {
            val b = Fake()
            val c = InstallMaintenanceController(b)
            c.open()
            if (changeBoot) b.boot++ else b.now--
            assertEquals("BLOCKED", c.status().code)
            assertTrue(b.blocked)
        }
    }

    @Test fun cannotOpenWithoutOwnerOrWithoutAnExistingRestriction() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        b.owner = false
        assertEquals("NOT_OWNER", c.open().code)
        assertTrue(b.events.isEmpty())
        b.owner = true
        b.blocked = false
        assertEquals("NOT_BLOCKED", c.open().code)
        assertTrue(b.events.isEmpty())
    }

    @Test fun statusAndStartupDoNotImposeRestrictionsOnAnUnconfiguredDevice() {
        val b = Fake().apply { blocked = false }
        val c = InstallMaintenanceController(b)
        assertEquals("UNRESTRICTED", c.status().code)
        assertEquals("UNRESTRICTED", c.onBoot().code)
        assertTrue(b.events.isEmpty())
    }

    @Test fun missingBootCounterCannotStartAWindow() {
        val b = Fake().apply { boot = -1 }
        assertFalse(InstallMaintenanceController(b).open().success)
        assertFalse(b.events.contains("unblock"))
        assertTrue(b.blocked)
    }

    @Test fun alarmFailureNeverUnlocks() {
        val b = Fake().apply { failSchedule = true }
        assertEquals("FAILED_BLOCKED", InstallMaintenanceController(b).open().code)
        assertTrue(b.blocked)
        assertFalse(b.events.contains("unblock"))
        assertNull(b.lease)
    }

    @Test fun alarmFailureDuringAnActiveWindowRestoresTheRestriction() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        c.open()
        b.failSchedule = true
        assertEquals("FAILED_BLOCKED", c.status().code)
        assertTrue(b.blocked)
        assertNull(b.lease)
    }

    @Test fun storageFailureNeverUnlocksAndKeepsRetry() {
        val b = Fake().apply { failSave = true }
        assertFalse(InstallMaintenanceController(b).open().success)
        assertTrue(b.blocked)
        assertFalse(b.events.contains("unblock"))
        assertTrue(b.retryScheduled)
    }

    @Test fun failedUnlockIsReblocked() {
        val b = Fake().apply { failOpen = true }
        assertEquals("FAILED_BLOCKED", InstallMaintenanceController(b).open().code)
        assertTrue(b.blocked)
        assertNull(b.lease)
    }

    @Test fun failedEarlyCloseKeepsAForcedRestoreRecordForRetry() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        c.open()
        b.failRestore = true
        assertEquals("RESTORE_FAILED", c.close().code)
        assertTrue(b.retryScheduled)
        assertNotNull(b.lease)
        b.failRestore = false
        assertEquals("BLOCKED", c.status().code)
        assertTrue(b.blocked)
    }

    @Test fun verifiesRestorationInsteadOfTrustingApiReturn() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        c.open()
        b.restoreSilentlyFails = true
        assertEquals("RESTORE_FAILED", c.close().code)
        assertTrue(b.retryScheduled)
        assertNotNull(b.lease)
    }

    @Test fun corruptAndOversizedRecordsAreNotAccepted() {
        val b = Fake().apply { blocked = false; corrupt = true }
        val c = InstallMaintenanceController(b)
        assertEquals("FAILED_BLOCKED", c.status().code)
        assertTrue(b.blocked)
        b.blocked = false
        b.lease = InstallMaintenanceController.Lease(b.now, b.now + 3_600_000, b.boot)
        assertEquals("BLOCKED", c.status().code)
        assertTrue(b.blocked)
    }

    @Test fun manualApplyAndDuplicateAlarmsCannotReopen() {
        val b = Fake()
        val c = InstallMaintenanceController(b)
        c.open()
        b.events.clear()
        assertTrue(c.close().success)
        assertEquals("BLOCKED", c.status().code)
        assertEquals("BLOCKED", c.status().code)
        assertFalse(b.events.takeLast(6).contains("unblock"))
    }
}
