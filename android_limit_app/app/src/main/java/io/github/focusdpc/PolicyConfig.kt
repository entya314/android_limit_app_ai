package io.github.focusdpc

/** 後から変更する設定をここに集めます。端末の画面からは変更できません。 */
object PolicyConfig {
    // ★ NextDNSの管理画面に表示される自分のホスト名に、この1行だけ変更してください。
    // 例のIDをそのまま使用せず、自分のNextDNS構成IDを使います。URLやIPアドレスは不可です。
    const val NEXTDNS_HOSTNAME = "YOUR_ID.dns.nextdns.io"

    /** プレースホルダーや不正な文字列を、DNS設定APIに渡す前に検出します。 */
    fun isUsableHostname(hostname: String): Boolean {
        // YOUR_IDは「_」を含むため、下のDNSラベル検証でプレースホルダーも拒否されます。
        if (hostname.length !in 1..253) return false
        val labels = hostname.split('.')
        if (labels.size < 2) return false
        return labels.all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
}
