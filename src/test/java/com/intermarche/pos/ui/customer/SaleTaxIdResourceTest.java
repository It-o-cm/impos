package com.intermarche.pos.ui.customer;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Sale-only default, edit, cancellation and payment boundary (LC-08-06-02/-03),
 * the required NIF (LC-08-06-01), the Portuguese check (LC-08-06-04) and the
 * scanned NIF watched by the open screen (LC-08-06-05).
 */
class SaleTaxIdResourceTest {

    /**
     * Wires real cart state, a template capture and the existing persistence seam.
     * @return the resource with no database or invoice collaborator
     */
    private SaleTaxIdResource resource() {
        SaleTaxIdResource resource = new SaleTaxIdResource();
        resource.state = new PosState();
        resource.settings = mock(PosSettingsService.class);
        when(resource.settings.defaultTaxId()).thenReturn("999999990");
        when(resource.settings.taxIdScanPattern()).thenReturn("");
        resource.persistence = mock(TicketPersistenceService.class);
        resource.i18n = mock(Messages.class);
        when(resource.i18n.get(anyString())).thenAnswer(call -> call.getArgument(0));
        resource.page = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.page.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return resource;
    }

    /** GET proposes the inherited anonymous NIF without mutating a ticket or reserving a document. */
    @Test
    void proposesDefaultWithoutSaving() {
        SaleTaxIdResource resource = resource();
        assertEquals("999999990", resource.proposed());
        resource.show(null);
        verify(resource.page).data("value", "999999990");
        assertNull(resource.state.ticket.customerTaxId);
        verifyNoInteractions(resource.persistence);
        when(resource.settings.defaultTaxId()).thenReturn(null);
        assertEquals("", resource.proposed());
        when(resource.settings.defaultTaxId()).thenReturn("  ");
        assertEquals("", resource.proposed());
    }

    /** A previous choice, including an explicit clear, wins over the anonymous default. */
    @Test
    void reopensChosenValue() {
        SaleTaxIdResource resource = resource();
        resource.state.ticket.customerTaxId = "123456789";
        assertEquals("123456789", resource.proposed());
        resource.state.ticket.customerTaxId = "";
        assertEquals("", resource.proposed());
    }

    /** Saving keeps the NIF on the current sale and synchronizes it under the settlement monitor. */
    @Test
    void savesNumericAndForeignIdentifiersWithoutInvoice() {
        for (String value : new String[]{"999999990", "123456789", " fr 123/ab-4.5 "}) {
            SaleTaxIdResource resource = resource();
            when(resource.persistence.syncDraft(resource.state)).thenAnswer(call -> {
                assertTrue(Thread.holdsLock(resource.state));
                return 1L;
            });
            Response response = (Response) resource.save(value, null);
            assertEquals(303, response.getStatus());
            assertEquals("/", response.getLocation().toString());
            assertEquals(value.trim().toUpperCase(java.util.Locale.ROOT), resource.state.ticket.customerTaxId);
            verify(resource.persistence).syncDraft(resource.state);
        }
    }

    /** Empty and absent form values clear the sale choice; the next sale does not inherit it. */
    @Test
    void clearingAndNextSale() {
        for (String value : new String[]{null, "", "  "}) {
            SaleTaxIdResource resource = resource();
            resource.state.ticket.customerTaxId = "123456789";
            assertInstanceOf(Response.class, resource.save(value, null));
            assertEquals("", resource.proposed());
            resource.state.clearTicket();
            assertNull(resource.state.ticket.customerTaxId);
            assertEquals("999999990", resource.proposed());
        }
    }

    /** Control characters, printer directives and overlong values cannot become fiscal identity. */
    @Test
    void refusesUnsafeAndOverlongInputWithoutLosingChoice() {
        for (String value : new String[]{"1".repeat(31), "12\n34", "[[BARCODE 123]]", "123<456"}) {
            SaleTaxIdResource resource = resource();
            resource.state.ticket.customerTaxId = "123456789";
            assertInstanceOf(TemplateInstance.class, resource.save(value, null));
            verify(resource.page).data("value", value);
            assertEquals("123456789", resource.state.ticket.customerTaxId);
            verifyNoInteractions(resource.persistence);
        }
    }

    /** Each payment guard leg refuses an edit without touching the paid or finalized ticket. */
    @Test
    void refusesPaymentAndCompletedSale() {
        for (boolean inProgress : new boolean[]{true, false}) {
            SaleTaxIdResource resource = resource();
            resource.state.payment.paymentInProgress = inProgress;
            resource.state.payment.transactionComplete = !inProgress;
            resource.state.ticket.customerTaxId = "123456789";
            assertInstanceOf(TemplateInstance.class, resource.save("999999990", null));
            assertEquals("123456789", resource.state.ticket.customerTaxId);
            verifyNoInteractions(resource.persistence);
        }
    }

    /** LC-08-06-01: where the NIF is required an empty one is refused, a proposed one accepted. */
    @Test
    void aRequiredNifRefusesTheEmptyEntry() {
        SaleTaxIdResource resource = resource();
        when(resource.settings.taxIdRequired()).thenReturn(true);
        assertInstanceOf(TemplateInstance.class, resource.save("  ", "pay"));
        verify(resource.page).data("value", "");
        assertNull(resource.state.ticket.customerTaxId);
        verifyNoInteractions(resource.persistence);
        Response response = (Response) resource.save("999999990", "pay");
        assertEquals("/pay", response.getLocation().toString());
        assertEquals("999999990", resource.state.ticket.customerTaxId);
    }

    /** LC-08-06-04: with the check on, a malformed Portuguese NIF is refused and a valid or foreign one kept. */
    @Test
    void thePortugueseCheckRefusesAMalformedNif() {
        SaleTaxIdResource resource = resource();
        when(resource.settings.taxIdPortugueseCheck()).thenReturn(true);
        assertInstanceOf(TemplateInstance.class, resource.save("123456780", null));
        verify(resource.page).data("value", "123456780");
        assertNull(resource.state.ticket.customerTaxId);
        assertEquals("/", ((Response) resource.save("pt123456789", "other")).getLocation().toString());
        assertEquals("PT123456789", resource.state.ticket.customerTaxId);
        assertInstanceOf(Response.class, resource.save("ESB12345678", null));
        assertEquals("ESB12345678", resource.state.ticket.customerTaxId);
    }

    /** The screen carries where it goes next, the scan counter and the scan hint when a pattern is administered. */
    @Test
    void theScreenCarriesItsContinuationAndTheScanHint() {
        SaleTaxIdResource resource = resource();
        TemplateInstance instance = resource.page.data("value", "x");
        resource.state.ticket.customerTaxIdScans = 4;
        when(resource.settings.taxIdScanPattern()).thenReturn("^NIF(\\d{9})$");
        resource.show("pay");
        verify(instance).data("next", "pay");
        verify(instance).data("scans", 4L);
        verify(instance).data("scanHint", true);
        when(resource.settings.taxIdScanPattern()).thenReturn("");
        resource.show("elsewhere");
        verify(instance).data("next", "");
        verify(instance).data("scanHint", false);
    }

    /** LC-08-06-05: the open screen reads the scan counter and the recorded NIF, empty when none. */
    @Test
    void scannedReportsTheCounterAndTheNif() {
        SaleTaxIdResource resource = resource();
        assertEquals(java.util.Map.of("scans", 0L, "value", ""), resource.scanned());
        resource.state.ticket.customerTaxId = "123456789";
        resource.state.ticket.customerTaxIdScans = 2;
        assertEquals(java.util.Map.of("scans", 2L, "value", "123456789"), resource.scanned());
    }
}
