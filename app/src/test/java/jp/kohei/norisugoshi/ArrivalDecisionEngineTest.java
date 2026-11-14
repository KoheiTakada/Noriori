package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import org.junit.Test;

public class ArrivalDecisionEngineTest {
    @Test public void arrivalAlertsOnceArmed() {
        assertTrue(ArrivalDecisionEngine.shouldAlert(450, 40, 500, true));
    }
    @Test public void walkingNearDestinationWhileOffDoesNotAlert() {
        assertFalse(ArrivalDecisionEngine.shouldAlert(100, 10, 500, false));
    }
    @Test public void firstGoodFixInsideDestinationCanAlertAfterActivation() {
        assertTrue(ArrivalDecisionEngine.shouldAlert(300, 20, 500, true));
    }
    @Test public void poorAccuracyDoesNotAlert() {
        assertFalse(ArrivalDecisionEngine.shouldAlert(300, 700, 500, true));
    }
    @Test public void outsideDestinationDoesNotAlert() {
        assertFalse(ArrivalDecisionEngine.shouldAlert(501, 20, 500, true));
    }
    @Test public void insideActivationAreaTurnsOn() {
        assertTrue(ArrivalDecisionEngine.shouldActivate(300, 10, 500));
    }
    @Test public void beingOutsideActivationAreaDoesNotTurnOn() {
        assertFalse(ArrivalDecisionEngine.shouldActivate(4_000, 10, 500));
    }
    @Test public void activationNeedsAnAccurateFix() {
        assertFalse(ArrivalDecisionEngine.shouldActivate(300, 700, 500));
    }
}
