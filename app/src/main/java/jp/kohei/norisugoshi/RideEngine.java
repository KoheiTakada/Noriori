package jp.kohei.norisugoshi;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Ride inference and current-station validity are deliberately independent. */
final class RideEngine {
    static final long STATION_TTL=45_000, FIX_MAX_AGE=30_000, RIDE_GRACE=12*60_000;
    static final long MAX_SESSION=2*60*60_000;
    enum Motion { VEHICLE, WALKING, STILL, BICYCLE, UNKNOWN }
    static final class Fix {
        final double latitude,longitude;
        final float accuracy,speed,speedAccuracy;
        final long time;
        Fix(double lat,double lon,float accuracy,float speed,float speedAccuracy,long time) {
            latitude=lat;longitude=lon;this.accuracy=accuracy;this.speed=speed;
            this.speedAccuracy=speedAccuracy;this.time=time;
        }
    }
    boolean riding,vehicle,suppressed,bicycle;
    long started=-1,lastRailMotion=-1,lastFix=-1,lastEvent=-1,walkingSince=-1,stillSince=-1;
    long hubAt=-1,railEvidenceAt=-1;
    long dismissedAt=-1;
    String hubName;
    private String proposedHub;
    private long proposedHubAt=-1;
    private Fix previous,firstRailEvidence;
    private Set<Integer> evidenceLines=Collections.emptySet();
    private long awaySince=-1;

    void motion(Motion motion,boolean enter,long eventTime,long now) {
        if (eventTime<lastEvent || eventTime>now+5_000 || now-eventTime>120_000) return;
        lastEvent=eventTime;
        if (motion==Motion.VEHICLE) {
            vehicle=enter;
            if (enter) {
                if (started<0 || !riding || now-started>=MAX_SESSION) started=now;
                bicycle=false;
                walkingSince=stillSince=-1;
            }
        } else if (motion==Motion.WALKING) {
            walkingSince=enter?eventTime:-1;
        } else if (motion==Motion.STILL) {
            stillSince=enter?eventTime:-1;
        } else if (motion==Motion.BICYCLE) {
            bicycle=enter;
            if (enter) {end();vehicle=false;}
        }
        tick(now);
    }

    void observe(Fix fix,Set<Integer> railLines,String hub,long now) {
        observe(fix,railLines,hub,hub!=null,now);
    }
    void observe(Fix fix,Set<Integer> railLines,String hub,boolean stationComplex,long now) {
        if (!valid(fix,now)) { clearHub();tick(now);return; }
        // A batched older fix must neither revive a previous station nor move time back.
        if (lastFix>=0 && fix.time<=lastFix) return;
        boolean jump=previous!=null && fix.time-previous.time<=90_000
                && fix.time>previous.time && Geo.distanceMeters(previous.latitude,previous.longitude,
                fix.latitude,fix.longitude)-previous.accuracy-fix.accuracy
                >100*(fix.time-previous.time)/1000.0;
        if (jump) { clearHub();previous=fix;resetEvidence();tick(now);return; }
        lastFix=fix.time;
        double speed=speed(fix,previous);
        boolean fastRail=!bicycle && !railLines.isEmpty() && speed>=(vehicle?7:10);
        if (fastRail) {
            if (started<0) started=now;
            Set<Integer> common=new HashSet<>(evidenceLines);common.retainAll(railLines);
            if (firstRailEvidence==null || common.isEmpty()
                    || fix.time-firstRailEvidence.time>120_000) {
                firstRailEvidence=fix;evidenceLines=new HashSet<>(railLines);
            } else {
                evidenceLines=common;
                double movement=Geo.distanceMeters(firstRailEvidence.latitude,firstRailEvidence.longitude,
                        fix.latitude,fix.longitude)-firstRailEvidence.accuracy-fix.accuracy;
                if (fix.time-firstRailEvidence.time>=10_000 && movement>=120) {
                    riding=true;lastRailMotion=fix.time;walkingSince=stillSince=awaySince=-1;
                }
            }
            railEvidenceAt=fix.time;
            if (riding) lastRailMotion=fix.time;
        } else if (railEvidenceAt>=0 && fix.time-railEvidenceAt>60_000) resetEvidence();

        if (riding || suppressed) {
            // Walking inside an interchange is part of the rail trip. Outside a
            // station/rail corridor, sustained slow movement is evidence of alighting.
            if (!stationComplex && railLines.isEmpty() && (walkingSince>=0 || speed>=0 && speed<=2.5)) {
                if (awaySince<0) awaySince=fix.time;
                if (fix.time-awaySince>=120_000) end();
            } else awaySince=-1;
        }
        tick(now);
        if (riding && !suppressed && hub!=null && speed<7) {
            if (hub.equals(hubName)) hubAt=fix.time;
            else if (hub.equals(proposedHub)) {
                if (fix.time-proposedHubAt>=3_000) {
                    hubName=hub;hubAt=fix.time;proposedHub=null;proposedHubAt=-1;
                }
            } else {
                clearHub();proposedHub=hub;proposedHubAt=fix.time;
            }
        } else clearHub();
        previous=fix;
    }

    void tick(long now) {
        if (hubAt>=0 && now-hubAt>=STATION_TTL) clearHub();
        if (riding && (lastRailMotion<0 || now-lastRailMotion>=RIDE_GRACE)) expire();
        if (started>=0 && now-started>=MAX_SESSION) {expire();vehicle=false;}
        if (suppressed && dismissedAt>=0 && now-dismissedAt>=MAX_SESSION) {suppressed=false;dismissedAt=-1;}
    }
    boolean shouldTrack(long now) {
        tick(now);
        if (started<0 || now-started>=MAX_SESSION) return false;
        if (riding) return true;
        if (!vehicle) return false;
        if (walkingSince>=0 && now-walkingSince>=120_000
                || stillSince>=0 && now-stillSince>=120_000) return false;
        // No GPS: switch to balanced-power observation rather than spin GNSS.
        if (lastFix<0) return now-started<20*60_000;
        return now-started<3*60_000 || railEvidenceAt>=0 && now-railEvidenceAt<120_000;
    }
    String visibleHub(long now) { tick(now);return riding && !suppressed?hubName:null; }
    void dismiss() {dismiss(hubAt>=0?hubAt:Math.max(0,lastFix));}
    void dismiss(long now) {suppressed=true;dismissedAt=now;clearHub();}
    private void expire() {
        boolean hidden=suppressed;long hiddenAt=dismissedAt;end();suppressed=hidden;dismissedAt=hiddenAt;
    }
    void end() {
        riding=false;suppressed=false;dismissedAt=-1;started=-1;lastRailMotion=-1;walkingSince=stillSince=awaySince=-1;
        clearHub();resetEvidence();
    }
    private void clearHub() {hubName=null;hubAt=-1;proposedHub=null;proposedHubAt=-1;}
    private void resetEvidence() {firstRailEvidence=null;evidenceLines=Collections.emptySet();railEvidenceAt=-1;}
    static boolean valid(Fix fix,long now) {
        return Double.isFinite(fix.latitude) && Double.isFinite(fix.longitude)
                && Math.abs(fix.latitude)<=90 && Math.abs(fix.longitude)<=180
                && Float.isFinite(fix.accuracy) && fix.accuracy>=0 && fix.accuracy<=50
                && fix.time>=0 && now-fix.time<=FIX_MAX_AGE && fix.time<=now+5_000;
    }
    private static double speed(Fix current,Fix previous) {
        if (Float.isFinite(current.speed) && current.speed>=0 && current.speed<=100
                && (current.speedAccuracy<0 || current.speedAccuracy<=3))
            return Math.max(0,current.speed-Math.max(0,current.speedAccuracy));
        if (previous==null) return -1;
        long delta=current.time-previous.time;
        if (delta<5_000 || delta>90_000) return -1;
        double lower=Geo.distanceMeters(previous.latitude,previous.longitude,current.latitude,current.longitude)
                -previous.accuracy-current.accuracy;
        return Math.max(0,lower)*1000/delta;
    }
}
