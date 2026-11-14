"""MLIT N02-25 rail geometry -> binary (CC BY 4.0).

Usage: python3 tools/build_rail_catalog.py input.geojson output.bin
Coordinates remain in Japan/WGS84 degrees; simplify within 5 m and split
long straight segments to <= 450 m for the on-device spatial index.
"""
import json, math, pathlib, struct, sys

def distance(a,b):
    x=(a[0]-b[0])*111320*math.cos(math.radians((a[1]+b[1])/2))
    return math.hypot(x,(a[1]-b[1])*111320)

def simplify(points):
    if len(points)<3: return points
    a,b=points[0],points[-1]
    sx=111320*math.cos(math.radians(a[1])); sy=111320
    dx=(b[0]-a[0])*sx;dy=(b[1]-a[1])*sy
    best,index=0,0
    for i,p in enumerate(points[1:-1],1):
        px=(p[0]-a[0])*sx;py=(p[1]-a[1])*sy
        t=max(0,min(1,(px*dx+py*dy)/(dx*dx+dy*dy))) if dx or dy else 0
        d=math.hypot(px-t*dx,py-t*dy)
        if d>best:best,index=d,i
    return simplify(points[:index+1])[:-1]+simplify(points[index:]) if best>5 else [a,b]

def main():
    features=json.loads(pathlib.Path(sys.argv[1]).read_text())['features']
    lines={};segments=[]
    for f in features:
        p=f['properties']; key=(p['N02_004'],p['N02_003'])
        line=lines.setdefault(key,len(lines))
        geometry=f['geometry'];paths=[geometry['coordinates']] if geometry['type']=='LineString' else geometry['coordinates']
        for path in paths:
            for a,b in zip(simplify(path),simplify(path)[1:]):
                count=max(1,math.ceil(distance(a,b)/450))
                for i in range(count):
                    x=[a[k]+(b[k]-a[k])*i/count for k in range(2)]
                    y=[a[k]+(b[k]-a[k])*(i+1)/count for k in range(2)]
                    segments.append((round(x[1]*1e6),round(x[0]*1e6),round(y[1]*1e6),round(y[0]*1e6),line))
    out=pathlib.Path(sys.argv[2]);out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('wb') as z:
        z.write(struct.pack('>ii',0x4e524c31,len(segments)))
        for s in segments:z.write(struct.pack('>iiiii',*s))
    print(len(lines),'lines;',len(segments),'segments;',out.stat().st_size,'bytes')

if __name__=='__main__':main()
