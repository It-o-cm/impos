package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaxIdScanHandler}, the scanned customer NIF
 * (LC-08-06-05). The state is a real {@link PosState}, the settings and the
 * draft funnel are mocks. Covered: a code already handled, a locked register,
 * no pattern, no code, a code the pattern does not read, a mistyped pattern, a
 * pattern without group, an empty or absent group, the payment guard on both
 * legs, the character rule, the Portuguese check (LC-08-06-04) and the record.
 */
class TaxIdScanHandlerTest {

    /** The administered default pattern of the catalogue. */
    private static final String PATTERN = "^(?:PT|NIF)(\\d{9})$";

    /**
     * Builds a handler over an unlocked register, the default pattern
     * administered and the Portuguese check off.
     *
     * @return the handler
     */
    private TaxIdScanHandler handler() {
        TaxIdScanHandler handler = new TaxIdScanHandler();
        handler.i18n = TestMessages.french();
        handler.posSettingsService = mock(PosSettingsService.class);
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn(PATTERN);
        handler.persistence = mock(TicketPersistenceService.class);
        return handler;
    }

    /**
     * Builds the token of one scan on an unlocked register.
     *
     * @param code the scanned code
     * @return the token
     */
    private ScanContext scan(String code) {
        PosState state = new PosState();
        state.auth.isLocked = false;
        return new ScanContext(code, state);
    }

    /** A recognised NIF is recorded, counted, synchronised and announced. */
    @Test
    void aScannedNifIsRecordedOnTheSale() {
        TaxIdScanHandler handler = handler();
        ScanContext ctx = scan(" NIF123456789 ");
        long version = ctx.state.version;
        handler.handle(ctx);
        assertTrue(ctx.handled);
        assertEquals("123456789", ctx.state.ticket.customerTaxId);
        assertEquals(1, ctx.state.ticket.customerTaxIdScans);
        verify(handler.persistence).syncDraft(ctx.state);
        assertEquals("NIF CLIENT : 123456789", ctx.state.ticket.transientError);
        assertTrue(ctx.state.version > version);
    }

    /** A token already consumed, or a locked register, is left alone. */
    @Test
    void aHandledCodeOrALockedRegisterIsIgnored() {
        TaxIdScanHandler handler = handler();
        ScanContext handled = scan("PT123456789");
        handled.handled = true;
        handler.handle(handled);
        assertNull(handled.state.ticket.customerTaxId);
        ScanContext locked = scan("PT123456789");
        locked.state.auth.isLocked = true;
        handler.handle(locked);
        assertFalse(locked.handled);
        assertNull(locked.state.ticket.customerTaxId);
    }

    /** No pattern, no code, a foreign code or a mistyped pattern lets the code go on down the chain. */
    @Test
    void unrecognisedCodesGoOnDownTheChain() {
        TaxIdScanHandler handler = handler();
        ScanContext article = scan("3300000000002");
        handler.handle(article);
        assertFalse(article.handled);
        ScanContext none = scan(null);
        handler.handle(none);
        assertFalse(none.handled);
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn("");
        ScanContext unadministered = scan("PT123456789");
        handler.handle(unadministered);
        assertFalse(unadministered.handled);
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn("^NIF(");
        ScanContext mistyped = scan("NIF(");
        handler.handle(mistyped);
        assertFalse(mistyped.handled);
        verify(handler.persistence, never()).syncDraft(org.mockito.ArgumentMatchers.any());
    }

    /** A pattern without group records the whole code; an absent or blank group records nothing. */
    @Test
    void theGroupNamesTheNif() {
        TaxIdScanHandler handler = handler();
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn("^\\d{9}$");
        ScanContext whole = scan("123456789");
        handler.handle(whole);
        assertEquals("123456789", whole.state.ticket.customerTaxId);
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn("^(?:NIF(\\d{9})|NONE)$");
        ScanContext absent = scan("NONE");
        handler.handle(absent);
        assertFalse(absent.handled);
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn("^NIF( *)X$");
        ScanContext blank = scan("NIF  X");
        handler.handle(blank);
        assertFalse(blank.handled);
    }

    /** During a payment, or once the sale is closed, a scanned NIF is refused and consumed. */
    @Test
    void aScannedNifIsRefusedOnceThePaymentStarted() {
        for (boolean inProgress : new boolean[]{true, false}) {
            TaxIdScanHandler handler = handler();
            ScanContext ctx = scan("PT123456789");
            ctx.state.payment.paymentInProgress = inProgress;
            ctx.state.payment.transactionComplete = !inProgress;
            handler.handle(ctx);
            assertTrue(ctx.handled);
            assertNull(ctx.state.ticket.customerTaxId);
            assertEquals("TERMINEZ OU ANNULEZ LE TICKET D'ABORD", ctx.state.ticket.transientError);
        }
    }

    /** A group reading forbidden characters, or a malformed Portuguese NIF under the check, is refused. */
    @Test
    void aScannedNifObeysTheKeyedRules() {
        TaxIdScanHandler handler = handler();
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn("^N:(.+)$");
        ScanContext unsafe = scan("N:[[CUT]]");
        handler.handle(unsafe);
        assertTrue(unsafe.handled);
        assertNull(unsafe.state.ticket.customerTaxId);
        assertTrue(unsafe.state.ticket.transientError.startsWith("NIF invalide"));
        when(handler.posSettingsService.taxIdScanPattern()).thenReturn(PATTERN);
        when(handler.posSettingsService.taxIdPortugueseCheck()).thenReturn(true);
        ScanContext malformed = scan("NIF123456780");
        handler.handle(malformed);
        assertTrue(malformed.handled);
        assertNull(malformed.state.ticket.customerTaxId);
        assertTrue(malformed.state.ticket.transientError.startsWith("NIF portugais invalide"));
        verify(handler.persistence, never()).syncDraft(org.mockito.ArgumentMatchers.any());
    }
}
