# のりおり — UI system

毎日の画面は駅とスイッチ。編集は地図、追加は候補。操作と選択を青に集め、通常の状態を説明文で増やさない。

## Foundations

`tokens.json` が色・文字・余白・形・寸法・動きの定義元。`python3 tools/sync_design_tokens.py` で Android の `DesignTokens.java` と `colors.xml` を生成する。

| 項目 | ルール |
| --- | --- |
| 色 | Canvas / Surface / Container / Ink / Muted / Primary / Danger。青は操作・選択、赤は停止と削除。 |
| 文字 | Android のシステム書体。26 / 22 / 16 / 14 / 12 sp。駅名を優先し、見出しは控えめに。 |
| 余白 | 4 / 8 / 12 / 16 / 24 / 32 dp。画面端は20 dp。 |
| 形 | 小12 / 中20 / 大28 dp。操作はピル、駅の行は大。影は地図上の浮く操作にだけ使う。 |
| 触れる範囲 | 最小48 dp。駅の行88 dp、主操作52 dp。 |
| 幅 | 一覧は最大600 dp。600 dp以上の編集画面は地図と320 dpの操作欄を横に置く。 |
| 動き | 160 / 240 ms。ネイティブスイッチ、押下リップル、円の塗りのフェード。円の寸法は動かさない。OSのアニメーション無効を尊重。 |

## Components

終電画面も同じ部品を使う。幅600dp未満では下部の「降車」「終電」、それ以上では右下のナビゲーションへ切り替える。時刻を大きくし、家の最寄り駅は候補の選択で保存、通知するエリアは地図から編集する。円内だけ終電を通知し、外側は止める。

2026-10-12の追加参照をピクセルで確認：Google Clockの2026年4月の時刻カード・保存操作（上記URL）、Google Homeのタブレットの選択ピルと左ナビ（https://cdn.mos.cms.futurecdn.net/WzjY5XB4J7KPf4cJQ5emTb.jpg）。後者の画像は旧版の配置参照。Google Mapsの2026年紹介画像も確認したが、装飾や対話入力は取り入れない。

`DesignSystem.java` が Android の共通部品。`Ui.java` は既存画面から共通部品への薄い入口。

| 部品 | 通常 | 選択 / オン | 無効 / 通知 |
| --- | --- | --- | --- |
| 駅の行 | 白、駅名、編集の矢印、スイッチ | 青い薄面、右の白いノブ | 通知時は赤い薄面と「停止」 |
| スイッチ | 枠と小さなノブ | 青い面と大きな白いノブ | オフでもオンエリアの監視は継続 |
| ボタン | 補助は青い薄面 | 保存は青い面 | 保存不可は灰。停止は赤い面 |
| タブ | 中性の薄面 | 青い薄面 | 選択はOSの状態説明でも伝える |
| 場所チップ | 中性の薄面 | 青い薄面、末尾に削除の× | チップを選択→削除の2タップ。追加は列の最後 |
| 地図操作 | 24 dpアイコン、48 dpの操作範囲 | 選択中の円は青、他は灰 | 読み込みと失敗は短い文字 |
| 駅候補 | 中性の検索欄、駅名、路線、矢印 | タップですぐ確定・閉じる | 候補なし・読込失敗を同じ位置に表示 |
| 確認ダイアログ | 共通の面、角丸、文字 | 進行操作を青 | 削除だけ赤 |

画面に設定・全体の状態表示・説明用のバナーは足さない。通知対象の駅とオンにする場所の追加動線は変更しない。必要な権限が欠けた場合の操作と、タイトル長押しのログを保つ。

## App icon

電車と向きが反対の二つの矢印。アプリ本体・Androidのアプリ一覧・通知設定は同じマークを使う。通知の小さなアイコンも同じモチーフ。

背景と前景を分けた108 dpのアダプティブアイコン。前景を中央の安全範囲に収め、丸型・角丸のマスクと小さな32 px表示を確認する。Android 13以降には同じマークのmonochrome層を提供する。テーマ色の見本はAndroid描画に色を適用したプレビューであり、実機ランチャーの確認ではない。

## Defaults

初期の通知対象は池袋駅のみ。オンにする場所は目白駅・大塚駅・十条駅・大山駅・椎名町駅。各駅の代表点は同梱の国土交通省の駅データを使う。

更新時は未編集の旧プリセットだけを変更する。保存された駅・円を引き継ぎ、削除した池袋を復活させない。

## Visual references

2026-10-12 に実画像を視覚確認。アプリ全体の動線を保ちながら、以下の表現を採用した。

- [Google Clock：アラーム編集・日付選択（2026年4月）](https://cdn.beebom.com/content/2026/04/scheduling-alarm-android.jpg) — グループ化された面、行末スイッチ、青い主操作に相当する強弱、短いラベルの確認操作。
- [Google Maps：日本語の保存リスト](https://assets.st-note.com/production/uploads/images/208118565/picture_pc_beffe0fac2a842566cfce620da5314fb.png?width=1200) — 名前を先に読むリスト、見つけやすい追加操作、アイコンと行の整列。
- [Google Maps：場所の検索候補](https://cdn.mos.cms.futurecdn.net/cuvew5Uk5SDEELuhFDg3Rb-1200-80.jpg) — 独立した検索面、場所名と補足の2段、選択で確定する候補。
- [Android：アクセシビリティ](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views) — 48 dpのタップ領域と、アイコンの操作名。
- [Google Clock：アプリアイコン](https://s3.elespanol.com/2025/05/05/elandroidelibre/1003743742136_255298707_1706x1280.jpg) — 一つの意味を中央のマークに集め、単色でも読める構成。
- [Android：アダプティブアイコン](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) — 前景・背景・monochrome層と安全範囲。
- ユーザー提供のAndroid「アプリの通知」画面（2026-10-12）も視覚確認。既存の四角い電車アイコンを、システムが形を揃えられるリソースに変更する。

## Review

Androidネイティブ描画で通常・オン・振動時・地図編集・5つのオンエリア・検索候補を確認。360 dp・文字1.3倍と840 dp幅でも保存操作を確認する。`ScreenRenderTest` は地図通信を無効にして画像を作る。終電の広域画面は実際のOpenStreetMapタイルを検証用に読み込む。画像は実機の地図通信や位置取得の確認を意味しない。

通知・オン・オフ・停止、追加・保存・破棄、既存設定の維持は既存の回帰テストで確認する。

### スイッチの余白

2026-10-13に[Google時計の公式配布画像](https://play.google.com/store/apps/details?id=com.google.android.deskclock)を取得してピクセルで確認。行末のスイッチは、丸と外周の間に上下左右の余白を持つ。画像内の「Vibrate」と「Weather forecast」のオン表示を参照した。

- [確認した実画像](https://play-lh.googleusercontent.com/a6L2_rJllNapEAZXcuZg_PJoQs-_IyUcJqyRqNCTMRucJPSRMezmJdcmjdjm63fRN2iWawy3cgjxf9UhsMNjpgA=w1080)
- [MaterialのAndroid実装](https://github.com/material-components/material-components-android/blob/master/docs/components/Switch.md)

トラック52×32dp、オンの丸24dp、オフの丸16dp。ネイティブSwitchの移動位置を、丸い端の中心へ対応させる。トラック幅を広げず、オンの外周の余白を4dpに揃える。右端が1dpまで寄っていた描画を修正。ネイティブの移動、手動切替、読み上げ、監視による自動切替を保つ。

## 終電と下部の操作

2026-10-12にGoogle Clockの時刻・確認操作とGoogle Homeの広い画面を実画像で視覚確認。Google Homeの画像は2023年の配置参考で、現行画面とは扱わない。

- [Google Clock（2026年4月）](https://cdn.beebom.com/content/2026/04/scheduling-alarm-android.jpg)：時刻を大きく、確認・保存は下部へ。
- [Google Home（広い画面）](https://cdn.mos.cms.futurecdn.net/WzjY5XB4J7KPf4cJQ5emTb.jpg)：選択を面で示すサイドナビ。
- [Google Maps（2026年）](https://blog.google/products-and-platforms/products/maps/ask-maps-immersive-navigation/)：地図の下に操作をまとめたシート。

コンパクト幅は下部タブ「降車」「終電」、広い幅は右サイドバー。サイドバーも下側へ寄せる。駅・家の最寄り駅・通知するエリアの操作、時刻の余裕、停止は下部にまとめ、上部は見出しと読み取り情報を置く。内容が増えた場合はスクロールできる。

サイドバーは物理的な右端に固定する。表示方向によって左右を入れ替えず、本文がサイドバーに重ならない幅を確保する。

2026-10-21にGoogle時計の[公式配布画像](https://play.google.com/store/apps/details?id=com.google.android.deskclock)を再取得して視覚確認。主操作を画面下部に集める構成と選択色を継続する。広い画面の機能切り替えは右手で届く右下へ配置する。

地図編集のタブ、場所チップ、検索、スライダー、削除と保存を下の面へ置く。オンにする場所はチップの1回目のタップで選択し、選択中のチップをもう一度タップすると削除する。選択は色で示し、チェックは付けない。選択中だけ末尾に×を置き、読み上げは削除対象の名前を伝える。削除後に隣のチップは自動選択しない。追加はチップ列の末尾に置く。地図の円をタップすると同じ円の編集へ切り替わり、設定した場所を置き換えない。保存時にオンにする場所がなければそのタブへ進める。

新しい通知円の直径は250m。オンにする場所の初期値は従来のまま。数値入力を求めず、地図の大きさで編集する。終電の家の最寄り駅には円を出さない。通知するエリアは1つの地理的な円で、初期半径150km・上限1,000km。円の中だけ通知し、外では止める。降車の駅やオンにする場所とは別に保存する。

## のりおりのマーク

2026-10-13にGoogle Playの実アイコンを取得してピクセルで確認。Citymapperの太い白い矢印と単色の面、Google Homeの大きな外形と余白を参照。ユーザーの修正を反映し、電車と乗る・降りる二つの矢印を採用した。

- https://play.google.com/store/apps/details?id=com.citymapper.app.release
- https://play.google.com/store/apps/details?id=com.google.android.apps.chromecast.app

マークは行きと帰りを限定しない。青い面と白い外形で、32pxとテーマ色でも読める。機能タブでは降車・終電それぞれの操作アイコンを維持する。

## 2026-10-13 の修正

実画像を取得しピクセルで確認：

- [Google Maps の地域選択](https://blog.google/products-and-platforms/products/maps/google-maps-offline/) と [実画面のGIF](https://storage.googleapis.com/gweb-uniblog-publish-prod/original_images/savemap.gif)：地図を動かして地域を囲む。範囲と操作は地図と下部の確認ボタンに集約。駅ピンを地域の意味に使わない。公式が現在公開している2023年の操作画像として参照し、現行版の撮影とは扱わない。
- [Yahoo!乗換案内の現行配布アイコン](https://transit.yahoo.co.jp/smartphone/app/) と [実画像](https://i.yimg.jp/images/transit/icon/ogp_transit1024.png)：車体と窓の横幅が十分にある正面像。のりおりでは車体を40×41の比率へ広げ、矢印を小さくして電車を主役にする。

広域の円は球面距離の境界をメルカトル地図に投影する。画面上の単純な円を距離判定と同一視しない。地図の縮小限界も地域表示に合わせて広げる。地図をドラッグすると地域が動き、ピンチまたはスライダーで範囲を変える。

場所チップは選択中の削除マークだけを持つ。タブも選択色で示す。新しく追加した場所が画面外にあるときは、そのチップを見える位置へ送る。すでに見えているチップは動かさず、同じ場所の2回目のタップで削除できる。


## 乗車中の駅名（2026-10-13）

Google MapsのLive Updates実画面とAndroid公式のステータスチップ画像を視覚確認した。時計の横の小さなピルにアイコンと短い情報だけを置き、通知内には表示を外す操作を置く。独自のピルを描かず、OSの標準通知とLive Updatesへ駅名を渡す。チップには駅名だけを渡し、末尾の「駅」を省く。位置が取れないときの見出し・説明やバージョンは出さない。

- 実UI: https://www.heise.de/news/Android-16-Google-erklaert-wie-Live-Updates-genutzt-werden-koennen-10475468.html
- 確認画像: https://heise.cloudimg.io/bound/1200x1200/q85.png-lossy-85.webp-lossy-85.foil1/_www-heise-de_/imgs/18/4/8/9/1/3/4/2/android-16-live-updates-maps-1-ffa3c3b12995b15f.jpg
- 公式チップ: https://developer.android.com/static/images/ui/notifications/status-chip-3.png
- 公開API: https://developer.android.com/develop/ui/views/notifications/live-update
