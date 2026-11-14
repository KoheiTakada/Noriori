package jp.kohei.norisugoshi;

import android.net.Uri;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Public read-only provider. Times are service-midnight seconds, not Unix seconds. */
final class TransitLastTrainClient {
    static final double MAX_STATION_WALK_SECS=15*60;
    interface Reader { JSONObject get(Uri uri)throws IOException,JSONException; }
    private final Reader reader;
    private final Map<String,JSONObject> stationCache=new LinkedHashMap<>();
    private JSONObject feedMetadata;
    private long feedsFetchedAt;
    TransitLastTrainClient(){reader=null;}
    TransitLastTrainClient(Reader reader){this.reader=reader;}
    static final class DataUnavailable extends IOException {
        DataUnavailable(String message){super(message);}
    }
    LastTrainRoute search(Context context,double lat,double lon,HomeStation home,long now)throws IOException,JSONException{
        if(!LastTrainEngine.activeTime(now))throw new DataUnavailable("確認する時間外");
        if(reader==null&&Boolean.getBoolean("norisugoshi.map.offlineTest"))throw new IOException("Test network disabled");
        String day=LastTrainEngine.day(now);
        if(feedMetadata==null||now-feedsFetchedAt>24*60*60_000L){
            feedMetadata=read(Uri.parse("https://api.transit.ls8h.com/api/v1/feeds"));feedsFetchedAt=now;
        }
        JSONObject bounded=read(request("geo:"+lat+","+lon,"geo:"+home.latitude+","+home.longitude,day));
        try{return parse(bounded,feedMetadata,day,lat,lon,now);}
        catch(DataUnavailable ignored){/* A missing train can be replaced with hours of walking in geo results. */}
        List<JSONObject> destinations=stations(home.name,home.latitude,home.longitude);
        if(destinations.isEmpty())throw new DataUnavailable("家の最寄り駅の時刻表を確認できない");
        String destination=destinations.get(0).getString("id");LastTrainRoute best=null;
        for(StationCatalog.Entry entry:StationCatalog.suggest(context,"",lat,lon,3)){
            for(JSONObject station:stations(entry.name,entry.latitude,entry.longitude)){
                double walk=walkSeconds(lat,lon,station.getDouble("lat"),station.getDouble("lon"));
                if(walk>MAX_STATION_WALK_SECS)continue;
                try{
                    JSONObject response=withAccessWalk(read(request(station.getString("id"),destination,day)),walk);
                    LastTrainRoute route=parse(response,feedMetadata,day,lat,lon,now);
                    if(best==null||route.leaveAt>best.leaveAt)best=route;
                }catch(IOException|JSONException ignored){/* Another nearby station or line may have a usable route. */}
            }
        }
        if(best==null)throw new DataUnavailable("現在地から乗れる終電を確認できない");
        return best;
    }
    private static Uri request(String from,String to,String day){
        return Uri.parse("https://api.transit.ls8h.com/api/v1/plan").buildUpon()
                .appendQueryParameter("from",from).appendQueryParameter("to",to)
                .appendQueryParameter("date",day.replace("-","")).appendQueryParameter("type","arrival")
                .appendQueryParameter("allowModes","rail,subway,tram,monorail").appendQueryParameter("numItineraries","6")
                .appendQueryParameter("time","27:59:00").build();
    }
    private JSONObject read(Uri uri)throws IOException,JSONException{
        if(reader==null&&!LastTrainEngine.activeTime(LastTrainEngine.now()))throw new DataUnavailable("確認する時間外");
        return reader==null?get(uri):reader.get(uri);
    }
    private List<JSONObject> stations(String text,double lat,double lon)throws IOException,JSONException{
        String query=name(text).replaceFirst("駅$","");JSONObject data=stationCache.get(query);
        if(data==null){data=read(Uri.parse("https://api.transit.ls8h.com/api/v1/locations/suggest").buildUpon()
                .appendQueryParameter("q",query).appendQueryParameter("limit","30").build());
            if(stationCache.size()>=32)stationCache.clear();stationCache.put(query,data);}
        List<JSONObject> result=new ArrayList<>();JSONArray array=data.optJSONArray("stations");if(array==null)return result;
        for(int i=0;i<array.length();i++){
            JSONObject item=array.getJSONObject(i);
            if(!"station".equals(item.optString("kind"))||!query.equals(name(item.optString("name")).replaceFirst("駅$","")))continue;
            double distance=Geo.distanceMeters(lat,lon,item.optDouble("lat",Double.NaN),item.optDouble("lon",Double.NaN));
            if(Double.isFinite(distance)&&distance<=500)result.add(item);
        }
        result.sort(Comparator.comparingDouble(item->Geo.distanceMeters(lat,lon,item.optDouble("lat"),item.optDouble("lon"))));
        return result.subList(0,Math.min(3,result.size()));
    }
    static double walkSeconds(double lat,double lon,double stationLat,double stationLon){
        // Conservative access estimate: 1.2 m/s with a 35% detour allowance.
        return Math.ceil(Geo.distanceMeters(lat,lon,stationLat,stationLon)*1.35/1.2);
    }
    static JSONObject withAccessWalk(JSONObject original,double walk)throws JSONException{
        JSONObject copy=new JSONObject(original.toString());JSONArray journeys=copy.optJSONArray("journeys");
        if(journeys!=null)for(int i=0;i<journeys.length();i++){
            JSONObject journey=journeys.getJSONObject(i);journey.put("accessWalkSecs",journey.optDouble("accessWalkSecs",0)+walk);
        }
        return copy;
    }
    private JSONObject get(Uri uri)throws IOException,JSONException{
        HttpURLConnection c=(HttpURLConnection)new URL(uri.toString()).openConnection();
        c.setConnectTimeout(15_000);c.setReadTimeout(30_000);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) NorisugoshiGuard/"+BuildConfig.VERSION_NAME);
        try{
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int length;
                while((length=in.read(buffer))!=-1){if(out.size()+length>3_000_000)throw new IOException("Response too large");out.write(buffer,0,length);}
                JSONObject result=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                if(result.has("error"))throw new DataUnavailable("終電を確認できない");
                return result;
            }
        }finally{c.disconnect();}
    }
    static LastTrainRoute parse(JSONObject data,JSONObject metadata,String day,double lat,double lon,long now)throws JSONException,IOException{
        if(data.has("error")||(!data.optString("type").equals("last")&&!data.optString("type").equals("arrival")))
            throw new DataUnavailable("終電を確認できない");
        ZoneId zone=ZoneId.of(data.getString("timezone"));
        if(!zone.equals(LastTrainEngine.TOKYO))throw new DataUnavailable("対応していない地域");
        LocalDate returned=LocalDate.parse(data.getString("date"),DateTimeFormatter.BASIC_ISO_DATE);
        long midnight=returned.atStartOfDay(zone).toInstant().toEpochMilli();
        LocalDate night=LocalDate.parse(day);
        long lower=night.atStartOfDay(zone).toInstant().toEpochMilli();
        long upper=night.plusDays(1).atTime(4,0).atZone(zone).toInstant().toEpochMilli();
        Map<String,JSONObject> feeds=new HashMap<>();
        JSONArray feedList=metadata.getJSONArray("feeds");
        for(int i=0;i<feedList.length();i++){JSONObject f=feedList.getJSONObject(i);feeds.put(f.getString("feedId"),f);}
        LastTrainRoute best=null;boolean stale=false;
        JSONArray journeys=data.getJSONArray("journeys");
        for(int i=0;i<journeys.length();i++){
            try{
                JSONObject j=journeys.getJSONObject(i);JSONArray legs=j.getJSONArray("legs");
                if(legs.length()==0)continue;
                double access=j.optDouble("accessWalkSecs",0),egress=j.optDouble("egressWalkSecs",0);
                if(!Double.isFinite(access)||access<0||access>MAX_STATION_WALK_SECS
                        ||!Double.isFinite(egress)||egress<0||egress>MAX_STATION_WALK_SECS)continue;
                long departure=midnight+seconds(j,"departureSecs"),arrival=midnight+seconds(j,"arrivalSecs");
                long previous=Long.MIN_VALUE;
                boolean rail=false,valid=true;String station="";LinkedHashSet<String> path=new LinkedHashSet<>(),credits=new LinkedHashSet<>();
                for(int k=0;k<legs.length();k++){
                    JSONObject leg=legs.getJSONObject(k);
                    long depart=midnight+seconds(leg,"departureSecs"),arrive=midnight+seconds(leg,"arrivalSecs");
                    if(depart<previous||arrive<depart){valid=false;break;}previous=arrive;
                    String kind=leg.getString("kind");
                    if(kind.equals("walk")){if(arrive-depart>MAX_STATION_WALK_SECS*1000&&legs.length()>1){valid=false;break;}continue;}
                    if(!kind.equals("transit")||!Arrays.asList("rail","subway","tram","monorail").contains(leg.optString("mode"))
                            ||leg.optBoolean("headwayBased",true)){valid=false;break;}
                    String train=leg.optString("routeName")+" "+leg.optString("trainType")+" "+leg.optString("headsign");
                    if(train.matches("(?is).*(新幹線|特急|TJライナー|THライナー|S-TRAIN|ホームライナー|寝台|Shinkansen|Limited.?Express|Sleeper|TJ.?Liner|TH.?Liner|Home.?Liner|Skyliner).*")){valid=false;break;}
                    String id=leg.getJSONObject("from").getString("id");
                    String feed=id.contains(":")?id.substring(0,id.indexOf(':')):"";
                    JSONObject f=feeds.get(feed);
                    if(f==null){valid=false;break;}
                    if(f.optBoolean("stale",false)||f.optString("serviceEnd","99999999").compareTo(day.replace("-",""))<0
                            ||f.optString("serviceStart","00000000").compareTo(day.replace("-",""))>0){
                        stale=true;valid=false;break;
                    }
                    credits.add(f.optString("name","Transit API")+"\n"+f.optString("license","")+"\n"+f.optString("attribution",""));
                    String from=name(leg.getJSONObject("from").getString("name")),to=name(leg.getJSONObject("to").getString("name"));
                    if(!rail)station=from;rail=true;path.add(from);path.add(to);
                }
                if(!valid)continue;
                if(arrival<departure)continue;
                long leave=departure-Math.round(access*1000)-(rail?5*60_000L:0);
                boolean walkOnly=!rail;
                if(walkOnly){
                    if(arrival-departure>30*60_000L)continue;
                    leave=now;arrival=now+seconds(j,"durationSecs");
                }else if(leave<lower||arrival>=upper||arrival<departure||departure>=upper)continue;
                LastTrainRoute r=new LastTrainRoute(day,station,String.join(" → ",path),leave,arrival,now,lat,lon,walkOnly);
                r.credits=String.join("\n",credits);
                if(best==null||walkOnly||(!best.walkOnly&&leave>best.leaveAt))best=r;
            }catch(JSONException|DateTimeException|IllegalArgumentException e){/* Invalid data cannot create an alarm. */}
        }
        if(best==null)throw new DataUnavailable(stale?"時刻表が古い":"同じ夜に帰れる終電を確認できない");
        return best;
    }
    private static long seconds(JSONObject o,String key)throws JSONException{
        double n=o.getDouble(key);if(!Double.isFinite(n)||Math.abs(n)>7*86400)throw new JSONException("Invalid time");
        return Math.round(n*1000);
    }
    private static String name(String text){
        return text.matches(".*[ぁ-んァ-ヶ一-龯].*")?text.replaceFirst("\\s+[A-Za-z].*$",""):text;
    }
}
