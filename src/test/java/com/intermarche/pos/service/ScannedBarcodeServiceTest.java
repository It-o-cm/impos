package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.barcode.ScannedBarcode;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link ScannedBarcodeService} (BO-03-06-63), targeting 100%
 * branch coverage: a null and a blank code recording nothing; a reading with
 * its range and its sale; one without either; a code longer than the journal
 * keeps.
 */
class ScannedBarcodeServiceTest {

    /**
     * A service on register POS01 over a mocked outbox.
     *
     * @return the service
     */
    private ScannedBarcodeService service() {
        ScannedBarcodeService service = new ScannedBarcodeService();
        service.terminalId = "POS01";
        service.syncOutboxService = mock(SyncOutboxService.class);
        return service;
    }

    /**
     * A null or blank code records nothing.
     */
    @Test
    void aBlankCodeRecordsNothing() {
        ScannedBarcodeService service = service();
        service.record(null, null, null, null, true, null, false);
        service.record("  ", null, null, null, true, null, false);
        verify(service.syncOutboxService, never()).enqueue(any(), any());
    }

    /**
     * A reading keeps the register, the range, the sale, the amount, the end of
     * validity, the date of use and whether the code was known, and is pushed.
     */
    @Test
    void aReadingIsJournalledAndPushed() {
        ScannedBarcodeService service = service();
        CouponType type = new CouponType();
        type.code = "AVOIR";
        type.label = "Avoir magasin";
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "POS01-00000005";
        LocalDateTime end = LocalDateTime.of(2027, 1, 31, 0, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ScannedBarcode> rows = mockConstruction(ScannedBarcode.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(ticket);
            service.record(" 297000000000001 ", type, new BigDecimal("12.40"), end, true, 5L, true);
            ScannedBarcode reading = rows.constructed().get(0);
            assertEquals("POS01", reading.terminalId);
            assertEquals("297000000000001", reading.code);
            assertEquals("AVOIR", reading.typeCode);
            assertEquals("Avoir magasin", reading.typeLabel);
            assertEquals("POS01-00000005", reading.ticketNumber);
            assertEquals(new BigDecimal("12.40"), reading.amount);
            assertEquals(end, reading.validUntil);
            assertTrue(reading.scannedAt != null);
            assertTrue(reading.found);
            assertTrue(reading.gs1);
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.SCANNED_BARCODE, reading.id);
        }
    }

    /**
     * A reading with no range and no sale keeps neither, and a code longer
     * than the journal keeps is cut; an unknown sale names none.
     */
    @Test
    void aBareReadingIsJournalled() {
        ScannedBarcodeService service = service();
        String longCode = "9".repeat(80);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ScannedBarcode> rows = mockConstruction(ScannedBarcode.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            service.record(longCode, null, null, null, false, null, false);
            service.record("123", null, null, null, false, 9L, false);
            ScannedBarcode cut = rows.constructed().get(0);
            assertEquals(64, cut.code.length());
            assertNull(cut.typeCode);
            assertNull(cut.typeLabel);
            assertNull(cut.ticketNumber);
            assertFalse(cut.found);
            assertNull(rows.constructed().get(1).ticketNumber);
        }
    }
}
