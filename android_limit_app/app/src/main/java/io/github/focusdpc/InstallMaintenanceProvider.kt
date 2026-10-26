package io.github.focusdpc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject

/**
 * 許可済みPCのADB shell専用の受け口です。Activityや通常アプリからは実行できません。
 * ManifestのDUMP権限に加え、Binderの呼出元UIDを確認します。自己申告のIntent値は信用しません。
 * 認証はAndroidのADB接続許可（PCの鍵）を使用し、独自の解除パスワードは設けません。
 */
class InstallMaintenanceProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkShellCaller()
        require(arg == null && (extras == null || extras.isEmpty)) { "Arguments are not supported" }
        // 呼出元の確認後、FocusDPC自身の権限で管理APIを実行します。
        val identity = Binder.clearCallingIdentity()
        try {
            val app = requireNotNull(context).applicationContext as FocusDpcApplication
            val result = when (method) {
                "open" -> app.installMaintenance.open()
                "status" -> app.installMaintenance.status()
                "close" -> app.installMaintenance.close()
                else -> throw IllegalArgumentException("Unknown maintenance command")
            }
            val json = JSONObject().put("success", result.success).put("code", result.code)
                .put("remainingSeconds", result.remainingSeconds).toString()
            // contentコマンドのBundle表示に含まれる記号やロケールに左右されない応答形式です。
            return Bundle().apply {
                putString("payload", Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun checkShellCaller() {
        // Androidのshell UIDは2000です。通常アプリにDUMP権限が付与されてもこの検査で拒否します。
        if (Binder.getCallingUid() != 2000) {
            throw SecurityException("Only the authorized ADB shell may call this provider")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = unsupported()
    override fun getType(uri: Uri): String? = unsupported()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Use the ADB maintenance command")
}
