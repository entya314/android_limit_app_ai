package io.github.focusdpc

import android.app.admin.DeviceAdminReceiver

/**
 * Androidがこのクラスを「端末管理者」の登録先として使用します。
 * 登録だけで制限を適用せず、ユーザーが画面のボタンを押したときに適用します。
 * Device Ownerの解除を妨げる処理や、自動再登録の処理は入れません。
 */
class MyDeviceAdminReceiver : DeviceAdminReceiver()
