package jp.kohei.norisugoshi;

import android.content.Context;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bundled real rail geometry, indexed into ~1 km cells; no route/API request. */
final class RailCatalog {
    private static volatile RailCatalog cached;
    private final Map<Long,List<Segment>> grid = new HashMap<>();
    static final class Match {
        final double distance;
        final Set<Integer> lines;
        Match(double distance, Set<Integer> lines) { this.distance=distance;this.lines=lines; }
    }
    private static final class Segment {
        final double a,b,c,d; final int line;
        Segment(int a,int b,int c,int d,int line) {
            this.a=a/1e6;this.b=b/1e6;this.c=c/1e6;this.d=d/1e6;this.line=line;
        }
    }
    static RailCatalog get(Context context) throws IOException {
        RailCatalog result=cached;
        if (result!=null) return result;
        synchronized (RailCatalog.class) {
            if (cached==null) {
                RailCatalog data=new RailCatalog();
                try (DataInputStream in=new DataInputStream(context.getAssets().open("rails.bin"))) {
                    if (in.readInt()!=0x4e524c31) throw new IOException("rail format");
                    int count=in.readInt();
                    if (count<1 || count>2_000_000) throw new IOException("rail count");
                    for (int i=0;i<count;i++) {
                        Segment s=new Segment(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
                        for (int x=cell(Math.min(s.a,s.c));x<=cell(Math.max(s.a,s.c));x++)
                            for (int y=cell(Math.min(s.b,s.d));y<=cell(Math.max(s.b,s.d));y++)
                                data.grid.computeIfAbsent(key(x,y),k->new ArrayList<>()).add(s);
                    }
                }
                cached=data;
            }
            return cached;
        }
    }
    Match near(double latitude,double longitude,double tolerance) {
        double best=Double.POSITIVE_INFINITY;Set<Integer> lines=new HashSet<>();
        int x=cell(latitude),y=cell(longitude);
        for (int i=x-1;i<=x+1;i++) for (int j=y-1;j<=y+1;j++) {
            List<Segment> segments=grid.get(key(i,j));
            if (segments==null) continue;
            for (Segment s:segments) {
                double distance=distance(latitude,longitude,s);
                best=Math.min(best,distance);
                if (distance<=tolerance) lines.add(s.line);
            }
        }
        return new Match(best,lines);
    }
    private static double distance(double lat,double lon,Segment s) {
        double scale=111320*Math.cos(Math.toRadians(lat));
        double x=(s.b-lon)*scale,y=(s.a-lat)*111320;
        double dx=(s.d-s.b)*scale,dy=(s.c-s.a)*111320;
        double t=dx==0 && dy==0 ? 0 : Math.max(0,Math.min(1,-(x*dx+y*dy)/(dx*dx+dy*dy)));
        return Math.hypot(x+t*dx,y+t*dy);
    }
    private static int cell(double coordinate) { return (int)Math.floor(coordinate*100); }
    private static long key(int x,int y) { return ((long)x<<32)|(y&0xffffffffL); }
}
