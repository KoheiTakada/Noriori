# のりおり

降りる前と、終電に間に合う出発時刻の前に知らせるAndroidアプリ。

- **降車**：通知する駅と「オンにする場所」を地図の円で登録。オンエリアを通ると待機し、目的の駅に近づくと通知する。手動の切り替えも可能。
- **終電**：家の最寄り駅と、通知するエリア・通知しないエリアを登録。帰宅できる最後の経路を調べ、出発期限の15分前に知らせる。
- **乗換駅**：移動と線路の位置から乗車中を推定し、近くのハブ駅名を表示する。

警報は停止するまで同じ通知を更新し、振動を繰り返す。毎日04:00（日本時間）に通知待ち・警報を解除し、駅と円の設定は保持する。通常幅では下部タブ、広い画面では右下のサイドバーで機能を切り替える。

地下などで現在地を取得できない間は、降車を検知できない。終電経路は時刻表ベース。

## ビルド

JDK 21、Android SDK 36、Build Tools 35.0.0を使用。Android 10以降に対応する。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APKの出力先は `app/build/outputs/apk/debug/app-debug.apk`。アプリIDは `jp.kohei.norisugoshi.guard`。

通常のdebugビルドではローカルのdebug署名鍵を使う。既存アプリへの上書きには同じ署名鍵が必要。署名鍵を外部から指定する場合は `-PdebugKeystorePath=/path/to/local.keystore` を渡す。

画面の自動描画は `app/build/qa` に出力する。テストでは地図通信を止め、同梱するタイルと経路の応答を使う。実機での測位・通知・省電力動作の確認とは分けて扱う。

## 権限とデータ

位置情報・バックグラウンド位置情報・通知・身体活動の許可を起動時に順番に要求する。通知予約や駅名表示に必要なOS設定も案内する。

駅・円・ログは端末内に保存する。終電検索時には現在地と家の最寄り駅の座標を[Transit API](https://api.transit.ls8h.com/)へ送る。APIキーは不要。駅候補と線路の照合には同梱データを使う。

## データの出典

- 駅・線路：[国土交通省 国土数値情報・鉄道データ2025年度版](https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2025.html)、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。9,046駅と、簡略化した線路597路線を収録。加工内容は[NOTICE_STATIONS.txt](app/src/main/assets/NOTICE_STATIONS.txt)に記載。
- 地図：© [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)。
- 終電：[Transit API](https://api.transit.ls8h.com/)。経路データの出典はアプリ内でも確認できる。

## 開発資料

- [デザインシステム](design/DESIGN_SYSTEM.md)・[部品の見本](design/index.html)
- [終電の仕様](design/Last-train-requirements.md)
- [乗車判定と駅名表示](design/Ride-chip-requirements.md)
- [権限の要求順序](design/Permission-setup.md)
- [検証記録](design/VERIFICATION.md)

デザイントークンの変更後は `python3 tools/sync_design_tokens.py` でAndroid向けの定義を生成する。
