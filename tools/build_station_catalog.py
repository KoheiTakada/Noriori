"""Build the bundled station catalogue from MLIT N02-25 station GeoJSON.

Source: https://nlftp.mlit.go.jp/ksj/gml/data/N02/N02-25/N02-25_GML.zip
License: CC BY 4.0. Extract platform midpoints and group by N02_005g.
Usage: python3 tools/build_station_catalog.py input.geojson output.tsv
"""
import collections
import json
import math
import pathlib
import sys

def midpoint(points):
    if len(points) == 1:
        return points[0]
    lengths = []
    for a, b in zip(points, points[1:]):
        x = (b[0] - a[0]) * math.cos(math.radians((a[1] + b[1]) / 2))
        lengths.append(math.hypot(x, b[1] - a[1]))
    remaining = sum(lengths) / 2
    for a, b, length in zip(points, points[1:], lengths):
        if remaining <= length and length:
            ratio = remaining / length
            return [a[0] + (b[0] - a[0]) * ratio, a[1] + (b[1] - a[1]) * ratio]
        remaining -= length
    return points[-1]

def main():
    data = json.loads(pathlib.Path(sys.argv[1]).read_text())
    grouped = collections.defaultdict(list)
    for feature in data['features']:
        prop = feature['properties']
        grouped[(prop['N02_005g'], prop['N02_005'])].append(feature)
    rows = []
    for (group, name), records in grouped.items():
        representative = next((r for r in records if r['properties']['N02_005c'] == group), records[0])
        geometry = representative['geometry']
        points = geometry['coordinates'] if geometry['type'] == 'LineString' else geometry['coordinates'][0]
        lon, lat = midpoint(points)
        lines = '・'.join(sorted(set(r['properties']['N02_003'] for r in records)))
        name = name if name.endswith('駅') else name + '駅'
        rows.append((name, lat, lon, lines.replace('\t', ' ').replace('\n', ' ')))
    output = pathlib.Path(sys.argv[2])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(''.join(f'{name}\t{lat:.7f}\t{lon:.7f}\t{lines}\n' for name, lat, lon, lines in sorted(rows)))
    print(f'{len(rows)} stations, {output.stat().st_size} bytes')

if __name__ == '__main__':
    main()
