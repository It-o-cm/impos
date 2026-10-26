package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.service.DocumentTemplateService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelDocumentPrinter}: the administered document is
 * printed after the settlement of a commercial-management document only, when a
 * template is administered, the ticket found and the template rendered; the
 * values handed to it are the settled sale, formatted.
 */
class ChannelDocumentPrinterTest {

    /**
     * A printer over mocks, the template code "BL" administered.
     *
     * @return the printer
     */
    private ChannelDocumentPrinter printer() {
        ChannelDocumentPrinter printer = new ChannelDocumentPrinter();
        printer.rules = mock(ChannelRules.class);
        printer.documentTemplateService = mock(DocumentTemplateService.class);
        printer.hardwareService = mock(HardwareService.class);
        when(printer.rules.commercialAutoPrintTemplate()).thenReturn("BL");
        return printer;
    }

    /**
     * A state whose ticket was loaded from the commercial management tool.
     *
     * @return the state
     */
    private PosState commercialSale() {
        PosState state = new PosState();
        state.ticket.recalledChannel = "COMMERCIAL";
        return state;
    }

    /**
     * A sale loaded from no commercial document prints nothing, nor does one
     * whose store administers no template.
     */
    @Test
    void nothingIsPrintedWithoutACommercialDocumentOrATemplate() {
        ChannelDocumentPrinter printer = printer();
        PosState state = new PosState();
        state.ticket.recalledChannel = "SELF_SCAN";
        assertFalse(printer.printAfterSettlement(state, 1L));
        when(printer.rules.commercialAutoPrintTemplate()).thenReturn("");
        assertFalse(printer.printAfterSettlement(commercialSale(), 1L));
        verify(printer.hardwareService, never()).printReceipt(any());
    }

    /**
     * A ticket that cannot be found, or a template that does not render, prints
     * nothing rather than failing the sale.
     */
    @Test
    void aMissingTicketOrAnUnrenderedTemplatePrintsNothing() {
        ChannelDocumentPrinter printer = printer();
        Ticket ticket = new Ticket();
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class);
             MockedStatic<DocumentTemplate> templates = mockStatic(DocumentTemplate.class)) {
            assertFalse(printer.printAfterSettlement(commercialSale(), 1L));
            base.when(() -> PanacheEntityBase.findById(2L)).thenReturn(ticket);
            assertFalse(printer.printAfterSettlement(commercialSale(), 2L));
        }
        verify(printer.hardwareService, never()).printReceipt(any());
    }

    /**
     * The rendered template is printed and cut, fed with the ticket number,
     * the day, the settled documents, the total and the lines not cancelled.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRenderedDocumentIsPrintedWithTheSettledSale() {
        ChannelDocumentPrinter printer = printer();
        PosState state = commercialSale();
        state.recalledDocumentCodes.add("CM1");
        state.recalledOrderCode = "CM2";
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-00000012";
        ticket.closingDate = LocalDateTime.of(2026, 10, 24, 11, 0);
        ticket.totalIncludingTax = new BigDecimal("12.5");
        TicketLine kept = new TicketLine();
        kept.productLabel = "LAIT";
        kept.quantity = new BigDecimal("2.000");
        kept.totalPrice = new BigDecimal("3.1");
        TicketLine free = new TicketLine();
        free.productLabel = "LIBRE";
        TicketLine cancelled = new TicketLine();
        cancelled.cancelled = true;
        ticket.lines = List.of(kept, free, cancelled);
        DocumentTemplate template = new DocumentTemplate();
        when(printer.documentTemplateService.renderTemplate(eq(template), any())).thenReturn("BON DE LIVRAISON");
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class);
             MockedStatic<DocumentTemplate> templates = mockStatic(DocumentTemplate.class)) {
            base.when(() -> PanacheEntityBase.findById(3L)).thenReturn(ticket);
            templates.when(() -> DocumentTemplate.findByCode("BL")).thenReturn(template);
            assertTrue(printer.printAfterSettlement(state, 3L));
        }
        verify(printer.hardwareService).printReceipt("BON DE LIVRAISON");
        verify(printer.hardwareService).cutPaper();
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(printer.documentTemplateService).renderTemplate(eq(template), data.capture());
        assertEquals("C04-00000012", data.getValue().get("ticketNumber"));
        assertEquals("2026-10-24", data.getValue().get("date"));
        assertEquals("CM1, CM2", data.getValue().get("documentCodes"));
        assertEquals("12,50", data.getValue().get("total"));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.getValue().get("lines");
        assertEquals(2, lines.size());
        assertEquals("LAIT", lines.get(0).get("label"));
        assertEquals("2", lines.get(0).get("quantity"));
        assertEquals("3,10", lines.get(0).get("total"));
        assertEquals("", lines.get(1).get("quantity"));
        assertEquals("0,00", lines.get(1).get("total"));
    }

    /**
     * An undated ticket prints no day, and an order code already among the
     * settled documents, or none at all, is not added twice.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anUndatedTicketAndTheSettledCodes() {
        ChannelDocumentPrinter printer = printer();
        Ticket ticket = new Ticket();
        ticket.lines = List.of();
        DocumentTemplate template = new DocumentTemplate();
        when(printer.documentTemplateService.renderTemplate(eq(template), any())).thenReturn("X");
        PosState same = commercialSale();
        same.recalledDocumentCodes.add("CM1");
        same.recalledOrderCode = "CM1";
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class);
             MockedStatic<DocumentTemplate> templates = mockStatic(DocumentTemplate.class)) {
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(ticket);
            templates.when(() -> DocumentTemplate.findByCode("BL")).thenReturn(template);
            assertTrue(printer.printAfterSettlement(same, 4L));
            assertTrue(printer.printAfterSettlement(commercialSale(), 4L));
        }
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(printer.documentTemplateService, org.mockito.Mockito.times(2)).renderTemplate(eq(template), data.capture());
        assertEquals("", data.getAllValues().get(0).get("date"));
        assertEquals("CM1", data.getAllValues().get(0).get("documentCodes"));
        assertEquals("", data.getAllValues().get(1).get("documentCodes"));
        assertEquals("0,00", data.getAllValues().get(1).get("total"));
    }
}
