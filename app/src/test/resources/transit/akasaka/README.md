2026-10-13にTransit APIから取得した実応答。再現用の現在地は赤坂駅の同梱データの代表点（35.6721169, 139.7363869）、目的地は朝霞台駅。利用者の実GPSではない。

座標検索は池袋までアクセス徒歩110.8分、または西高島平から目的地への徒歩100.6分を含んだ。これらを除外し、近い駅のIDから27:59到着で検索する経路を検証する。駅候補は同じ名前の別地域の駅とバス停も含む。feedsは応答が使った4件へ絞り、bboxとcoverageCellsを除去。出典・ライセンス・帰属は残す。

https://api.transit.ls8h.com/api/openapi.json
https://api.transit.ls8h.com/api/v1/plan
https://api.transit.ls8h.com/api/v1/locations/suggest
