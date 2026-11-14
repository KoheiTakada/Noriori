実際のTransit API応答（2026-10-12取得）。池袋から朝霞台。feedsは経路が利用した1件に絞り、coverageCells/bboxを除去。
出典 https://api.transit.ls8h.com/ 。時刻は応答dateの午前0時からの秒数で、24時間超・負数を含む。
lastの応答は翌朝の始発待ちを含むため除外し、arrival 27:59で同じ夜に帰れる便を選ぶ。
