package jp.kohei.norisugoshi;

import java.time.*;

final class LastTrainEngine {
    static final ZoneId TOKYO=ZoneId.of("Asia/Tokyo");
    private static Clock clock=Clock.systemUTC();
    static long now(){return clock.millis();}
    static boolean activeTime(long now){int hour=Instant.ofEpochMilli(now).atZone(TOKYO).getHour();return hour>=18||hour<4;}
    static long nextBoundary(long now){
        ZonedDateTime time=Instant.ofEpochMilli(now).atZone(TOKYO);
        LocalDate date=time.toLocalDate();
        if(time.getHour()<4)return date.atTime(4,0).atZone(TOKYO).toInstant().toEpochMilli();
        if(time.getHour()<18)return date.atTime(18,0).atZone(TOKYO).toInstant().toEpochMilli();
        return date.plusDays(1).atTime(4,0).atZone(TOKYO).toInstant().toEpochMilli();
    }
    static final long WATCH_LEAD=2*60*60_000L;
    static boolean watchNeeded(LastTrainRoute route,long now){
        return activeTime(now)&&route!=null&&!route.walkOnly&&route.day.equals(day(now))
                &&now>=route.leaveAt-WATCH_LEAD&&now<=route.leaveAt;
    }
    enum Decision { NONE, SCHEDULE, ALERT, MISSED }
    // Japanese overnight services retain the prior service date across midnight.
    static String day(long now){return Instant.ofEpochMilli(now).atZone(TOKYO).minusHours(4).toLocalDate().toString();}
    static long nextDay(long now){return Instant.ofEpochMilli(now).atZone(TOKYO).minusHours(4).toLocalDate()
            .plusDays(1).atTime(4,0).atZone(TOKYO).toInstant().toEpochMilli();}
    static String zone(double lat,double lon,float accuracy,ReturnArea quietArea,ReturnArea notificationArea,String previous) {
        if(quietArea==null||notificationArea==null||!Float.isFinite(accuracy)||accuracy<0)return previous;
        double d=Geo.distanceMeters(lat,lon,quietArea.latitude,quietArea.longitude);
        double distance=Geo.distanceMeters(lat,lon,notificationArea.latitude,notificationArea.longitude);
        if(d+accuracy<=quietArea.radius)return "home";
        // A definite outer exclusion wins even if the quiet-circle boundary is uncertain.
        if(distance-accuracy>notificationArea.radius)return "travel";
        if(d-accuracy<=quietArea.radius)return previous;
        if(distance+accuracy<=notificationArea.radius)return "notify";
        return previous;
    }
    static Decision decide(LastTrainRoute r,int minutes,long now,boolean muted,boolean announced,String zone) {
        if(!activeTime(now)||r==null||muted||announced||!"notify".equals(zone)||r.walkOnly||!r.day.equals(day(now)))return Decision.NONE;
        if(now>r.leaveAt)return Decision.MISSED;
        return now>=r.alarmAt(minutes)?Decision.ALERT:Decision.SCHEDULE;
    }
    static boolean reusable(LastTrainRoute r,double lat,double lon,long now){
        return r!=null&&r.day.equals(day(now))&&now>=r.fetchedAt&&now-r.fetchedAt<15*60_000L
                &&Geo.distanceMeters(lat,lon,r.originLat,r.originLon)<200;
    }
    static String time(long epoch){return java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(epoch).atZone(TOKYO));}
}
