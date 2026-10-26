package io.github.focusdpc

/**
 * Android APIから分離した一時許可の状態管理です。時刻・保存・予約の失敗をテストできます。
 * 期限を先に保存・予約してから解除し、復旧時は「禁止の確認→記録削除→予約取消」の順にします。
 */
internal class InstallMaintenanceController(private val backend: Backend) {
    interface Backend {
        fun isOwner(): Boolean
        fun isBlocked(): Boolean
        fun setBlocked(blocked: Boolean)
        fun elapsedRealtime(): Long
        fun bootCount(): Int
        fun load(): Lease?
        fun save(lease: Lease?)
        fun schedule(deadline: Long)
        fun cancel()
        fun retry()
    }

    data class Lease(val started: Long, val deadline: Long, val boot: Int)
    data class Result(val success: Boolean, val code: String, val remainingSeconds: Long = 0)

    // 利用者の指定によりソースへ固定します。外部ファイル・Intent・PC引数では変更できません。
    private val windowMillis = 30L * 60L * 1000L

    fun open(): Result = guarded {
        val lease = backend.load()
        if (lease != null) {
            // 二重実行でも開始時刻・期限は更新しません。不正・期限切れなら禁止へ戻します。
            return@guarded reconcile(lease)
        }
        if (!backend.isBlocked()) return@guarded Result(false, "NOT_BLOCKED")
        val now = backend.elapsedRealtime()
        val boot = backend.bootCount()
        check(boot >= 0 && now >= 0)
        val newLease = Lease(now, Math.addExact(now, windowMillis), boot)
        backend.save(newLease)
        backend.schedule(newLease.deadline) // 予約・権限の確認が失敗したら解除には進みません。
        backend.setBlocked(false)
        check(!backend.isBlocked())
        Result(true, "OPENED", windowMillis / 1000)
    }

    /** 状態確認は解除を行いません。プロセス再起動後も元の期限を使って予約し直します。 */
    fun status(): Result = guarded {
        backend.load()?.let { reconcile(it) }
            ?: Result(true, if (backend.isBlocked()) "BLOCKED" else "UNRESTRICTED")
    }

    /** PCからの即時終了と、画面の「ポリシーを適用」から使用します。DNSには触れません。 */
    fun close(): Result = guarded { restore() }

    /** 再起動時は、30分未満でも保留中の一時許可を終了します。未設定端末には制限しません。 */
    fun onBoot(): Result = guarded {
        if (backend.load() != null) restore() else status()
    }

    private fun reconcile(lease: Lease): Result {
        val now = backend.elapsedRealtime()
        val valid = lease.started >= 0 && lease.boot >= 0 && lease.deadline > lease.started &&
            lease.deadline - lease.started == windowMillis &&
            lease.boot == backend.bootCount() && now >= lease.started && now < lease.deadline
        if (!valid || backend.isBlocked()) return restore()
        backend.schedule(lease.deadline)
        return Result(true, "OPEN", (lease.deadline - now + 999) / 1000)
    }

    private fun restore(): Result {
        backend.setBlocked(true)
        check(backend.isBlocked())
        backend.save(null)
        backend.cancel()
        return Result(true, "BLOCKED")
    }

    private inline fun guarded(action: () -> Result): Result {
        return try {
            if (!backend.isOwner()) Result(false, "NOT_OWNER") else action()
        } catch (_: RuntimeException) {
            // 状態を読めない／予約できないときも、許可したまま正常終了とは報告しません。
            try {
                if (!backend.isOwner()) return Result(false, "NOT_OWNER")
                restore()
            } catch (_: RuntimeException) {
                // 禁止のAPI呼出しが失敗した場合は記録を残し、Android側の再試行を予約します。
                // 期限前の明示的終了に失敗しても、再試行時に許可を継続しない印を保存します。
                try { backend.save(Lease(-1, -1, -1)) } catch (_: RuntimeException) { /* 保存障害も再試行 */ }
                try { backend.retry() } catch (_: RuntimeException) { /* 次の起動・状態確認でも再試行 */ }
                return Result(false, "RESTORE_FAILED")
            }
            Result(false, "FAILED_BLOCKED")
        }
    }
}
