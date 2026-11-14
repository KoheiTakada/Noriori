package jp.kohei.norisugoshi;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Multiple distinct lines, including nearby platforms in the same complex. */
final class HubCatalog {
    private static volatile List<StationCatalog.Entry> hubs;
    static List<StationCatalog.Entry> all(Context context) throws IOException {
        if (hubs!=null) return hubs;
        synchronized (HubCatalog.class) {
            if (hubs!=null) return hubs;
            List<StationCatalog.Entry> entries=StationCatalog.all(context),result=new ArrayList<>();
            java.util.Map<Long,List<StationCatalog.Entry>> grid=new java.util.HashMap<>();
            for (StationCatalog.Entry e:entries)
                grid.computeIfAbsent(key(e.latitude,e.longitude),k->new ArrayList<>()).add(e);
            for (StationCatalog.Entry entry:entries) {
                Set<String> lines=lineSet(entry.lines);
                if (lines.size()<2) {
                    int x=(int)Math.floor(entry.latitude*100),y=(int)Math.floor(entry.longitude*100);
                    for (int i=x-1;i<=x+1;i++) for (int j=y-1;j<=y+1;j++) {
                        List<StationCatalog.Entry> nearby=grid.get(((long)i<<32)|(j&0xffffffffL));
                        if (nearby==null) continue;
                        for (StationCatalog.Entry other:nearby) {
                            if (other==entry) continue;
                            double distance=Geo.distanceMeters(entry.latitude,entry.longitude,other.latitude,other.longitude);
                            if (distance<=(entry.name.equals(other.name)?600:180)) lines.addAll(lineSet(other.lines));
                        }
                    }
                }
                if (lines.size()>1) result.add(entry);
            }
            hubs=java.util.Collections.unmodifiableList(result);return hubs;
        }
    }
    static StationCatalog.Entry at(Context context,double lat,double lon,float accuracy) throws IOException {
        StationCatalog.Entry best=null;double nearest=Double.POSITIVE_INFINITY;
        for (StationCatalog.Entry hub:all(context)) {
            if (Math.abs(hub.latitude-lat)>0.004 || Math.abs(hub.longitude-lon)>0.005) continue;
            double d=Geo.distanceMeters(lat,lon,hub.latitude,hub.longitude);
            if (d+accuracy<=220 && d<nearest) {best=hub;nearest=d;}
        }
        return best;
    }
    static boolean nearComplex(Context context,double lat,double lon) throws IOException {
        for (StationCatalog.Entry hub:all(context)) {
            if (Math.abs(hub.latitude-lat)>0.005 || Math.abs(hub.longitude-lon)>0.006) continue;
            if (Geo.distanceMeters(lat,lon,hub.latitude,hub.longitude)<=450) return true;
        }
        return false;
    }
    private static long key(double lat,double lon) {
        return ((long)(int)Math.floor(lat*100)<<32)|((int)Math.floor(lon*100)&0xffffffffL);
    }
    static Set<String> lineSet(String names) {
        Set<String> lines=new HashSet<>();
        for (String name:names.split("・")) if (!name.isBlank()) lines.add(name);
        return lines;
    }
}
