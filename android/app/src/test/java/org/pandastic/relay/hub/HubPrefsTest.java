package org.pandastic.relay.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HubPrefsTest {
    @Test public void localAndInternationalFormsMatch() {
        assertTrue(HubPrefs.sameNumber("+256700000001", "0700000001"));
        assertTrue(HubPrefs.sameNumber("0700 000 001", "+256 700-000-001"));
        assertTrue(HubPrefs.sameNumber("00256700000001", "+256700000001"));
    }

    @Test public void differentCountryCodesDoNotMatch() {
        assertFalse(HubPrefs.sameNumber("+254700000001", "+256700000001"));
    }

    @Test public void shortCodesAndNamesAreNeverAllowed() {
        assertNull(HubPrefs.matchKey("5555"));
        assertNull(HubPrefs.matchKey("MTN-Promo"));
        assertFalse(HubPrefs.sameNumber("5555", "5555"));
        assertEquals("700000001", HubPrefs.matchKey("+256700000001"));
    }
}
