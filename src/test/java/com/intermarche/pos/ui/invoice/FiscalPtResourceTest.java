package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.sale.FiscalDocument;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FiscalPtResource} (LC-08-04-18, -19, -21, -22): the screen's
 * modes and fields, the invoice request of the sale under way, the conversion of a
 * simplified invoice and the receipt of an invoice, with every refusal.
 */
class FiscalPtResourceTest {

    /** A legally constructed Portuguese NIF. */
    private static final String NIF = "123456789";

    /** Today as the keypad types it. */
    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMuuuu"));

    /**
     * Builds the resource, its screen answering one view whatever data it is given.
     *
     * @param enabled whether the register issues the Portuguese documents
     * @return the resource
     */
    private FiscalPtResource resource(boolean enabled) {
        FiscalPtResource resource = new FiscalPtResource();
        resource.state = new PosState();
        resource.settings = mock(PosSettingsService.class);
        when(resource.settings.taxIdPortugueseCheck()).thenReturn(true);
        resource.fiscal = mock(PortugueseFiscalService.class);
        when(resource.fiscal.enabled()).thenReturn(enabled);
        resource.printer = mock(TicketPrinterService.class);
        resource.i18n = TestMessages.french();
        resource.page = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.page.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        resource.terminalId = "C04";
        return resource;
    }

    /**
     * The view the screen rendered.
     *
     * @param resource the resource
     * @return the view
     */
    private TemplateInstance view(FiscalPtResource resource) {
        return resource.page.data("x", "x");
    }

    /**
     * Reads one datum the screen was rendered with.
     *
     * @param resource the resource
     * @param key the datum
     * @return its value
     */
    private Object datum(FiscalPtResource resource, String key) {
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(view(resource)).data(eq(key), value.capture());
        return value.getValue();
    }

    /**
     * Reads the mode the screen was rendered in.
     *
     * @param resource the resource
     * @return the mode
     */
    private Object mode(FiscalPtResource resource) {
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(resource.page).data(eq("mode"), value.capture());
        return value.getValue();
    }

    /**
     * Words the fields the screen was rendered with as {@code name=value}.
     *
     * @param resource the resource
     * @return the fields
     */
    @SuppressWarnings("unchecked")
    private String fields(FiscalPtResource resource) {
        StringBuilder words = new StringBuilder();
        for (EntryField field : (List<EntryField>) datum(resource, "fields")) {
            words.append(field.name()).append('=').append(field.value()).append(';');
        }
        return words.toString();
    }

    /**
     * Builds a numbered document.
     *
     * @param number its number
     * @return the document
     */
    private static FiscalDocument document(String number) {
        FiscalDocument document = new FiscalDocument();
        document.number = number;
        return document;
    }

    /**
     * A register that issues no Portuguese document sends the FACTURE key back
     * to the sale.
     */
    @Test
    void showSendsBackWhereNotEnabled() {
        FiscalPtResource resource = resource(false);
        Response response = (Response) resource.show(null, null);
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().getPath());
    }

    /**
     * Outside a sale the screen opens on the conversion, today and this register
     * proposed; the receipt is opened when asked.
     */
    @Test
    void showOpensTheConversionOrTheReceiptOutsideASale() {
        FiscalPtResource convert = resource(true);
        assertSame(view(convert), convert.show("other", null));
        assertEquals("convert", mode(convert));
        assertEquals("day=" + TODAY + ";terminal=C04;rank=;taxId=;name=;address=;", fields(convert));
        assertEquals("", datum(convert, "next"));
        FiscalPtResource receipt = resource(true);
        receipt.show("receipt", "pay");
        assertEquals("receipt", mode(receipt));
        assertEquals("day=" + TODAY + ";terminal=C04;rank=;", fields(receipt));
        assertEquals("pay", datum(receipt, "next"));
    }

    /**
     * During a sale the screen records the invoice request, showing the request
     * already recorded.
     */
    @Test
    void showOpensTheRequestDuringASale() {
        FiscalPtResource resource = resource(true);
        resource.state.ticket.items.add(new TicketState.TicketItem("1", null, "A", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO));
        resource.state.ticket.invoiceRequest = new TicketState.InvoiceRequest(NIF, "ACME", "RUA A");
        resource.show("receipt", null);
        assertEquals("request", mode(resource));
        assertEquals("taxId=" + NIF + ";name=ACME;address=RUA A;", fields(resource));
    }

    /**
     * LC-08-04-18: a customer without a usable NIF, a NIF the Portuguese check
     * refuses, or a customer without name or address is refused, the typed values
     * kept.
     */
    @Test
    void requestRefusesAnIncompleteCustomer() {
        String[][] cases = {
                {null, "ACME", "RUA A", "LE NIF DU CLIENT EST OBLIGATOIRE"},
                {"12<3", "ACME", "RUA A", "LE NIF DU CLIENT EST OBLIGATOIRE"},
                {"123456780", "ACME", "RUA A", null},
                {NIF, " ", "RUA A", "LE NOM ET L'ADRESSE DU CLIENT SONT OBLIGATOIRES"},
                {NIF, "ACME", null, "LE NOM ET L'ADRESSE DU CLIENT SONT OBLIGATOIRES"}};
        for (String[] typed : cases) {
            FiscalPtResource resource = resource(true);
            assertSame(view(resource), resource.request(typed[0], typed[1], typed[2], "pay"));
            assertEquals("request", mode(resource));
            if (typed[3] != null) {
                assertEquals(typed[3], datum(resource, "error"));
            }
            assertNull(resource.state.ticket.invoiceRequest);
        }
        FiscalPtResource checked = resource(true);
        checked.request("123456780", "acme", "rua a", "");
        assertEquals(TestMessages.french().get("saletaxid.invalidPt"), datum(checked, "error"));
        assertEquals("taxId=123456780;name=ACME;address=RUA A;", fields(checked));
    }

    /**
     * LC-08-04-19: once the sale is stated, an invoice can no longer be asked for.
     */
    @Test
    void requestRefusesASaleAlreadyStated() {
        FiscalPtResource resource = resource(true);
        resource.state.payment.ticketDbId = 5L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenReturn(List.of(document("FS C04/2026/00000001")));
            resource.request(NIF, "ACME", "RUA A", "pay");
        }
        assertEquals("DOCUMENT DEJA EMIS POUR CETTE VENTE", datum(resource, "error"));
        assertNull(resource.state.ticket.invoiceRequest);
    }

    /**
     * LC-08-04-18 / -19: a complete customer is recorded on the sale — during the
     * payment as before it — and the cashier goes back where they came from.
     */
    @Test
    void requestRecordsTheInvoiceAndGoesBack() {
        FiscalPtResource paying = resource(true);
        paying.state.payment.ticketDbId = 5L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Response response = (Response) paying.request(" " + NIF + " ", "acme lda", "rua a 1", "pay");
            assertEquals("/pay", response.getLocation().getPath());
        }
        assertEquals(new TicketState.InvoiceRequest(NIF, "ACME LDA", "RUA A 1"), paying.state.ticket.invoiceRequest);
        assertNull(paying.state.ticket.transientError);
        FiscalPtResource selling = resource(true);
        Response back = (Response) selling.request(NIF, "ACME", "RUA A", null);
        assertEquals("/", back.getLocation().getPath());
        assertEquals("FATURA DEMANDEE : ACME", selling.state.ticket.transientError);
        assertTrue(selling.state.ticket.transientOk);
    }

    /**
     * LC-08-04-21: training, an unreadable day, an unreadable number and an
     * incomplete customer are refused before anything is looked up, the typed
     * values kept.
     */
    @Test
    void convertRefusesWhatCannotBeLookedUp() {
        FiscalPtResource training = resource(true);
        training.state.trainingMode = true;
        training.convert(TODAY, "c04", "5", NIF, "ACME", "RUA A");
        assertEquals("AUCUN DOCUMENT FISCAL EN MODE FORMATION", datum(training, "error"));
        String[][] cases = {
                {null, "5", NIF, "DATE INVALIDE (JJMMAAAA)"},
                {"31022026", "5", NIF, "DATE INVALIDE (JJMMAAAA)"},
                {TODAY, null, NIF, "NUMERO DE DOCUMENT INVALIDE"},
                {TODAY, "5A", NIF, "NUMERO DE DOCUMENT INVALIDE"},
                {TODAY, "5", "", "LE NIF DU CLIENT EST OBLIGATOIRE"}};
        for (String[] typed : cases) {
            FiscalPtResource resource = resource(true);
            resource.convert(typed[0], "c04", typed[1], typed[2], "ACME", "RUA A");
            assertEquals(typed[3], datum(resource, "error"));
            assertEquals("convert", mode(resource));
        }
        FiscalPtResource kept = resource(true);
        kept.convert("2310", "c04", "5", NIF, "acme", "rua a");
        assertEquals("day=2310;terminal=C04;rank=5;taxId=" + NIF + ";name=ACME;address=RUA A;", fields(kept));
        verifyNoInteractions(training.fiscal, kept.fiscal);
    }

    /**
     * LC-08-04-21: a refusal of the service is shown translated and nothing is
     * printed.
     */
    @Test
    void convertShowsTheServiceRefusal() {
        FiscalPtResource resource = resource(true);
        when(resource.fiscal.convertToInvoice(any(), anyString(), anyLong(), any()))
                .thenReturn(new PortugueseFiscalService.Outcome("fiscalpt.introuvable", List.of()));
        resource.convert("23102026", "c04", " 5 ", NIF, "ACME", "RUA A");
        assertEquals("DOCUMENT INTROUVABLE SUR CETTE CAISSE", datum(resource, "error"));
        verify(resource.fiscal).convertToInvoice(LocalDate.of(2026, 10, 23), "C04", 5L,
                new TicketState.InvoiceRequest(NIF, "ACME", "RUA A"));
        verifyNoInteractions(resource.printer);
    }

    /**
     * LC-08-04-21: a conversion prints the credit note and the invoice, names them,
     * and empties the form.
     */
    @Test
    void convertPrintsTheCreditNoteAndTheInvoice() {
        FiscalPtResource resource = resource(true);
        FiscalDocument note = document("NC C04/2026/00000001");
        FiscalDocument invoice = document("FT C04/2026/00000001");
        when(resource.fiscal.convertToInvoice(any(), anyString(), anyLong(), any()))
                .thenReturn(new PortugueseFiscalService.Outcome(null, List.of(note, invoice)));
        resource.convert("23102026", "C04", "5", NIF, "ACME", "RUA A");
        verify(resource.printer).printFiscalDocument(note);
        verify(resource.printer).printFiscalDocument(invoice);
        assertEquals("DOCUMENTS EMIS : NC C04/2026/00000001 - FT C04/2026/00000001", datum(resource, "info"));
        assertEquals("", datum(resource, "error"));
        assertEquals("day=" + TODAY + ";terminal=C04;rank=;taxId=;name=;address=;", fields(resource));
    }

    /**
     * LC-08-04-22: a receipt is refused before the lookup, refused by the service,
     * or printed and named.
     */
    @Test
    void receiptRefusesOrPrints() {
        FiscalPtResource unreadable = resource(true);
        unreadable.receipt("99", "c04", "5");
        assertEquals("DATE INVALIDE (JJMMAAAA)", datum(unreadable, "error"));
        assertEquals("day=99;terminal=C04;rank=5;", fields(unreadable));
        FiscalPtResource refused = resource(true);
        when(refused.fiscal.issueReceipt(any(), anyString(), anyLong()))
                .thenReturn(new PortugueseFiscalService.Outcome("fiscalpt.reciboDejaEmis", List.of()));
        refused.receipt("23102026", "C04", "5");
        assertEquals("RECIBO DEJA EMIS POUR CETTE FATURA", datum(refused, "error"));
        verify(refused.printer, never()).printFiscalDocument(any());
        FiscalPtResource printed = resource(true);
        FiscalDocument receipt = document("RC C04/2026/00000001");
        when(printed.fiscal.issueReceipt(any(), anyString(), anyLong()))
                .thenReturn(new PortugueseFiscalService.Outcome(null, List.of(receipt)));
        printed.receipt("23102026", "C04", "5");
        verify(printed.printer).printFiscalDocument(receipt);
        verify(printed.fiscal).issueReceipt(LocalDate.of(2026, 10, 23), "C04", 5L);
        assertEquals("DOCUMENTS EMIS : RC C04/2026/00000001", datum(printed, "info"));
        assertEquals("receipt", mode(printed));
    }
}
