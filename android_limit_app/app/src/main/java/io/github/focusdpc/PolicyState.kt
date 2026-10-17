package io.github.focusdpc

/**
 * 状態は毎回AndroidのAPIから読みます。前回の「適用成功」を現在の状態として使いません。
 * nullは「権限がない、または読み取りに失敗した」です。無効と誤表示しないために区別します。
 */
data class PolicyState(
    val deviceOwner: Boolean?,
    val nextDnsConfigured: Boolean?,
    val privateDnsChangesBlocked: Boolean?,
    val playStoreBlocked: Boolean?,
    val unknownSourcesBlocked: Boolean?,
    val adbInstallationAllowed: Boolean?,
    val selfUninstallBlocked: Boolean?,
    val currentDnsHostname: String?,
    val readErrors: List<String> = emptyList(),
)

/** 1つの操作の実行結果。複数のAndroid APIは一括トランザクションにはなりません。 */
data class PolicyStepResult(val label: String, val success: Boolean, val detail: String)

data class PolicyActionResult(val summary: String, val steps: List<PolicyStepResult> = emptyList())

/** 画面回転後もApplicationに保持される、画面表示用の状態です。 */
data class PolicyUiState(
    val status: PolicyState? = null,
    val busy: Boolean = false,
    val actionResult: PolicyActionResult? = null,
)
