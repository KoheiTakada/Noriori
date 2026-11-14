package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.Set;
import org.junit.Test;

public class RideEngineTest {
    private static final Set<Integer> RAIL=Set.of(1);
    private static final Set<Integer> ROAD=Collections.emptySet();
    private static RideEngine.Fix fix(double lat,float speed,long t) {
        return new RideEngine.Fix(lat,139.71,10,speed,0.3f,t);
    }
    private static void locate(RideEngine e,double lat,float speed,long t,String hub) {
        e.observe(fix(lat,speed,t),RAIL,hub,t);
    }
    private static RideEngine riding() {
        RideEngine e=new RideEngine();
        e.motion(RideEngine.Motion.VEHICLE,true,1_000,1_000);
        locate(e,35.720,15,1_000,null);locate(e,35.723,15,21_000,null);
        assertTrue(e.riding);return e;
    }
    private static RideEngine atHub() {
        RideEngine e=riding();locate(e,35.730,0,81_000,"池袋駅");
        locate(e,35.730,0,86_000,"池袋駅");assertEquals("池袋駅",e.visibleHub(86_000));return e;
    }
    @Test public void walkingAroundAStationDoesNotStartARide() {
        RideEngine e=new RideEngine();
        for (int i=0;i<20;i++) locate(e,35.730+i*0.0001f,1.5f,1_000+i*10_000,"池袋駅");
        assertFalse(e.riding);assertNull(e.visibleHub(200_000));
    }
    @Test public void vehicleRecognitionAloneDoesNotProveARailTrip() {
        RideEngine e=new RideEngine();e.motion(RideEngine.Motion.VEHICLE,true,1_000,1_000);
        locate(e,35.730,0,2_000,"池袋駅");locate(e,35.730,0,12_000,"池袋駅");
        assertFalse(e.riding);assertNull(e.visibleHub(12_000));
    }
    @Test public void aFastCarAwayFromRailsDoesNotStartARide() {
        RideEngine e=new RideEngine();e.motion(RideEngine.Motion.VEHICLE,true,1_000,1_000);
        e.observe(fix(35.720,20,1_000),ROAD,null,1_000);
        e.observe(fix(35.725,20,31_000),ROAD,null,31_000);
        assertFalse(e.riding);
    }
    @Test public void twoSeparatedFastRailFixesConfirmARide() {assertTrue(riding().riding);}
    @Test public void oneFastFixIsInsufficient() {
        RideEngine e=new RideEngine();locate(e,35.730,20,1_000,"池袋駅");
        assertFalse(e.riding);assertNull(e.visibleHub(1_000));
    }
    @Test public void frozenCoordinatesWithASpeedFieldAreInsufficient() {
        RideEngine e=new RideEngine();locate(e,35.730,20,1_000,null);
        locate(e,35.730,20,31_000,null);assertFalse(e.riding);
    }
    @Test public void differentRailLinesAtARoadCrossingAreInsufficient() {
        RideEngine e=new RideEngine();locate(e,35.720,15,1_000,null);
        e.observe(fix(35.723,15,21_000),Set.of(2),null,21_000);assertFalse(e.riding);
    }
    @Test public void stoppingAtAnInterchangeRetainsRideAndShowsItsName() {assertTrue(atHub().riding);}
    @Test public void ordinaryStationsNeverGetAName() {
        RideEngine e=riding();locate(e,35.724,0,40_000,null);locate(e,35.724,0,45_000,null);
        assertNull(e.visibleHub(45_000));assertTrue(e.riding);
    }
    @Test public void frequentFixesDoNotKeepResettingTheStationConfirmation() {
        RideEngine e=riding();locate(e,35.730,0,81_000,"池袋駅");
        locate(e,35.730,0,83_500,"池袋駅");assertNull(e.visibleHub(83_500));
        locate(e,35.730,0,86_000,"池袋駅");assertEquals("池袋駅",e.visibleHub(86_000));
    }
    @Test public void leavingTheHubImmediatelyClearsTheName() {
        RideEngine e=atHub();locate(e,35.734,15,116_000,null);
        assertNull(e.visibleHub(116_000));assertTrue(e.riding);
    }
    @Test public void missingGpsClearsTheNameButRetainsTheTrip() {
        RideEngine e=atHub();assertNull(e.visibleHub(86_000+RideEngine.STATION_TTL));assertTrue(e.riding);
    }
    @Test public void inaccurateGpsImmediatelyClearsTheName() {
        RideEngine e=atHub();e.observe(new RideEngine.Fix(35.73,139.71,100,0,0,90_000),RAIL,"池袋駅",90_000);
        assertNull(e.visibleHub(90_000));assertTrue(e.riding);
    }
    @Test public void staleOrFutureGpsCannotConfirmAStation() {
        RideEngine e=atHub();e.observe(fix(35.73,0,90_000),RAIL,"池袋駅",130_000);
        assertNull(e.visibleHub(130_000));
        e.observe(fix(35.73,0,160_000),RAIL,"池袋駅",140_000);assertNull(e.visibleHub(140_000));
    }
    @Test public void returningGpsNeedsFreshStationConfirmation() {
        RideEngine e=atHub();e.tick(150_000);
        locate(e,35.730,0,155_000,"池袋駅");assertNull(e.visibleHub(155_000));
        locate(e,35.730,0,160_000,"池袋駅");assertEquals("池袋駅",e.visibleHub(160_000));
    }
    @Test public void walkingInsideAStationComplexIsStillATransfer() {
        RideEngine e=atHub();e.motion(RideEngine.Motion.WALKING,true,90_000,90_000);
        for(long t=100_000;t<=250_000;t+=30_000)
            e.observe(fix(35.731,1.5f,t),ROAD,null,true,t);
        assertTrue(e.riding);assertNull(e.visibleHub(250_000));
    }
    @Test public void sustainedWalkingAwayFromTheStationEndsTheTrip() {
        RideEngine e=atHub();e.motion(RideEngine.Motion.WALKING,true,90_000,90_000);
        e.observe(fix(35.735,1.5f,120_000),ROAD,null,120_000);
        e.observe(fix(35.736,1.5f,180_000),ROAD,null,180_000);
        e.observe(fix(35.737,1.5f,240_000),ROAD,null,240_000);
        assertFalse(e.riding);assertNull(e.visibleHub(240_000));
    }
    @Test public void aGpsTeleportDoesNotChangeStationsOrConfirmATrain() {
        RideEngine e=atHub();locate(e,36.5,15,90_000,"別の駅");assertNull(e.visibleHub(90_000));
        RideEngine fresh=new RideEngine();locate(fresh,35.720,15,1_000,null);
        locate(fresh,36.5,15,5_000,null);assertFalse(fresh.riding);
    }
    @Test public void olderBatchFixesCannotReplaceTheCurrentStation() {
        RideEngine e=atHub();locate(e,35.730,0,83_000,"新宿駅");
        assertEquals("池袋駅",e.visibleHub(86_000));
    }
    @Test public void displacementFallbackWorksWithoutTheSpeedField() {
        RideEngine e=new RideEngine();
        for(int i=0;i<3;i++)e.observe(new RideEngine.Fix(35.720+i*0.006,139.71,10,-1,-1,1_000+i*60_000),RAIL,null,1_000+i*60_000);
        assertTrue(e.riding);
    }
    @Test public void dismissHidesTheWholeTripAndTheNextTripCanShowAgain() {
        RideEngine e=atHub();e.dismiss();locate(e,35.730,0,100_000,"池袋駅");
        locate(e,35.730,0,105_000,"池袋駅");assertNull(e.visibleHub(105_000));assertTrue(e.riding);
        e.end();locate(e,35.720,15,200_000,null);locate(e,35.723,15,220_000,null);
        locate(e,35.730,0,280_000,"池袋駅");locate(e,35.730,0,285_000,"池袋駅");
        assertEquals("池袋駅",e.visibleHub(285_000));
    }
    @Test public void bicycleRecognitionPreventsRailInference() {
        RideEngine e=riding();e.motion(RideEngine.Motion.BICYCLE,true,30_000,30_000);
        locate(e,35.726,15,45_000,null);locate(e,35.729,15,65_000,null);assertFalse(e.riding);
    }
    @Test public void longGpsLossEventuallyEndsTheTrip() {
        RideEngine e=riding();e.tick(21_000+RideEngine.RIDE_GRACE);assertFalse(e.riding);
    }
    @Test public void anotherJourneyCanStartAfterTheSessionLimit() {
        RideEngine e=riding();long t=RideEngine.MAX_SESSION+2_000;e.tick(t);
        e.motion(RideEngine.Motion.VEHICLE,true,t,t);
        locate(e,35.720,15,t,null);locate(e,35.723,15,t+20_000,null);
        assertTrue(e.riding);assertTrue(e.shouldTrack(t+20_000));
    }
    @Test public void stationaryCandidateDoesNotRunGpsIndefinitely() {
        RideEngine e=new RideEngine();e.motion(RideEngine.Motion.VEHICLE,true,1_000,1_000);
        e.motion(RideEngine.Motion.STILL,true,2_000,2_000);
        assertFalse(e.shouldTrack(130_000));
    }
    @Test public void gpsUnavailableCandidateHasABoundedObservationWindow() {
        RideEngine e=new RideEngine();e.motion(RideEngine.Motion.VEHICLE,true,1_000,1_000);
        assertTrue(e.shouldTrack(190_000));assertFalse(e.shouldTrack(1_000+20*60_000));
    }
    @Test public void gpsGapDoesNotUndoTheUsersDismissal() {
        RideEngine e=atHub();e.dismiss();long t=1_000_000;e.tick(t);assertFalse(e.riding);
        locate(e,35.720,15,t,null);locate(e,35.723,15,t+20_000,null);
        locate(e,35.730,0,t+80_000,"池袋駅");locate(e,35.730,0,t+85_000,"池袋駅");
        assertTrue(e.riding);assertNull(e.visibleHub(t+85_000));
    }
    @Test public void verifiedWalkingAwayClearsDismissalEvenAfterAGpsGap() {
        RideEngine e=atHub();e.dismiss();long t=1_000_000;e.tick(t);
        e.observe(fix(35.735,1.5f,t),ROAD,null,t);
        e.observe(fix(35.736,1.5f,t+60_000),ROAD,null,t+60_000);
        e.observe(fix(35.737,1.5f,t+120_000),ROAD,null,t+120_000);
        assertFalse(e.suppressed);
    }
}
