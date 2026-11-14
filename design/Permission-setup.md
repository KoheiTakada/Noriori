# 権限の初期設定

起動時に未許可の権限を一つずつ要求する。降車・終電のどちらのタブでも同じ。既存の駅、円、オンの状態、家の最寄り駅は変更しない。

|順番|項目|操作|
|---|---|---|
|1|正確な位置情報|OSの許可ダイアログ|
|2|位置情報を常に許可|Android 11以降はOSのアプリ設定。正確な位置情報の許可後に開く|
|3|通知|Android 13以降はOSの許可ダイアログ。以前の版は通知設定|
|4|身体活動|OSの許可ダイアログ|
|5|端末の位置情報|オフのときだけOS設定|
|6|アラームとリマインダー|Android 12以降で未許可のときだけOS設定|
|7|ステータスバーの駅名|Android 16以降で許可されていないときだけOS設定|
|8|省電力の制限解除|このアプリを対象にしたOSの許可ダイアログ|

許可済み、OSで不要な項目は飛ばす。拒否した項目の自動要求は一度だけ。権限の結果やOS設定からの帰りを待ち、次の画面へ進む。画面回転時も処理中の項目を保つ。位置情報を拒否・概略にした場合は「常に許可」を要求せず、ほかの項目は続ける。後から正確な位置情報を許可すれば未実行の「常に許可」へ進む。

自動要求の履歴は乗車検知の状態と別に保存する。乗車検知のリセットや再起動で要求を繰り返さない。未許可の項目には既存の再設定ボタンを残す。永久拒否後の手動再設定はOS設定へ誘導する。

Google Mapsの実際のAndroid位置情報設定を画像で確認。アプリを対象にしたOS画面、常に許可、正確な位置情報の組合せを参照し、専用の説明画面を増やさずOSの操作を使う。
- https://www.guidingtech.com/wp-content/uploads/Fix-Google-Maps-Not-Working-On-Android-6-1443x1536.png

仕様の参照：
- https://developer.android.com/training/permissions/requesting
- https://developer.android.com/develop/sensors-and-location/location/permissions/background
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/develop/background-work/services/alarms
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/reference/android/provider/Settings#ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS

物理端末での許可ダイアログ・設定画面の遷移は未検証。OSの許可そのものは利用者が選ぶ。
