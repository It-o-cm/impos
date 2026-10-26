package com.intermarche.pos.ui.endorsement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SupervisorValidationMode} (BO-10-02-31): the tolerant
 * reading (null, blank, unknown, padded) and what each mode asks.
 */
class SupervisorValidationModeTest {

    /**
     * Nothing readable reads as code and password; a padded, lower-case mode
     * is recognised.
     */
    @Test
    void ofReadsTolerantly() {
        assertEquals(SupervisorValidationMode.CODE_PASSWORD, SupervisorValidationMode.of(null));
        assertEquals(SupervisorValidationMode.CODE_PASSWORD, SupervisorValidationMode.of(" "));
        assertEquals(SupervisorValidationMode.CODE_PASSWORD, SupervisorValidationMode.of("BADGE"));
        assertEquals(SupervisorValidationMode.BARCODE_PASSWORD, SupervisorValidationMode.of(" barcode_password "));
    }

    /**
     * Each mode states the barcode, the code and the password it asks.
     */
    @Test
    void eachModeStatesWhatItAsks() {
        assertFalse(SupervisorValidationMode.CODE_PASSWORD.needsBarcode());
        assertTrue(SupervisorValidationMode.CODE_PASSWORD.needsCode());
        assertTrue(SupervisorValidationMode.CODE_PASSWORD.needsPassword());
        assertTrue(SupervisorValidationMode.BARCODE.needsBarcode());
        assertFalse(SupervisorValidationMode.BARCODE.needsPassword());
        assertTrue(SupervisorValidationMode.BARCODE_CODE_PASSWORD.needsCode());
        assertFalse(SupervisorValidationMode.BARCODE_PASSWORD.needsCode());
        assertFalse(SupervisorValidationMode.DALLAS.needsBarcode());
        assertFalse(SupervisorValidationMode.DALLAS.needsCode());
    }
}
