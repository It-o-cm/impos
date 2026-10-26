package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.sale.FiscalDocument;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.customer.TaxIdRules;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The PORTUGUESE FISCAL DOCUMENTS screen, behind the FACTURE key of a register
 * issuing them (LC-08-04-18, -19, -21, -22).
 *
 * <p>During a sale — or its payment — it records the customer's request for a
 * FATURA: the sale is then stated on an invoice in the customer's name INSTEAD of
 * a simplified invoice. Outside a sale it turns a past simplified invoice into an
 * invoice, the simplified invoice being cancelled by a credit note, or issues the
 * receipt of a past invoice; the operator names the document by its day, its
 * register and its number, as the customer's paper states them.
 */
@Path("/fiscal-pt")
@DrawerMustBeClosed
public class FiscalPtResource {

    /** The date as the operator types it on the keypad. */
    private static final DateTimeFormatter TYPED_DATE =
            DateTimeFormatter.ofPattern("ddMMuuuu").withResolverStyle(ResolverStyle.STRICT);

    /** The mode recording the invoice request of the sale under way. */
    static final String MODE_REQUEST = "request";

    /** The mode turning a simplified invoice into an invoice. */
    static final String MODE_CONVERT = "convert";

    /** The mode issuing the receipt of an invoice. */
    static final String MODE_RECEIPT = "receipt";

    /** The sale under way and its invoice request. */
    @Inject
    PosState state;

    /** The NIF check the store applies (LC-08-06-04). */
    @Inject
    PosSettingsService settings;

    /** The documents themselves. */
    @Inject
    PortugueseFiscalService fiscal;

    /** Prints the documents issued from this screen. */
    @Inject
    TicketPrinterService printer;

    /** Translated labels and refusals. */
    @Inject
    Messages i18n;

    /** The screen. */
    @Inject
    @Location("fiscal-pt")
    Template page;

    /** This register, proposed as the register of the document named. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Shows the screen: the invoice request during a sale, the conversion or the
     * receipt otherwise.
     *
     * @param mode {@code receipt} for the receipt, anything else for the conversion
     * @param next {@code pay} when the payment screen sent the cashier here
     * @return the screen, or the sale screen where the register issues no
     *         Portuguese document
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Object show(@QueryParam("mode") String mode, @QueryParam("next") String next) {
        if (!fiscal.enabled()) {
            return Response.seeOther(URI.create("/")).build();
        }
        TicketState.InvoiceRequest request = state.ticket.invoiceRequest;
        return render(modeFor(mode), next, "", "",
                request == null ? "" : request.taxId(),
                request == null ? "" : request.name(),
                request == null ? "" : request.address(),
                LocalDate.now().format(TYPED_DATE), terminalId, "");
    }

    /**
     * Records the customer's request for an invoice on the sale under way
     * (LC-08-04-18, -19).
     *
     * @param taxId the customer's NIF
     * @param name the customer's name
     * @param address the customer's address
     * @param next {@code pay} to go back to the payment screen
     * @return the sale or the payment screen, or the screen with its refusal
     */
    @POST
    @Path("/request")
    @Produces(MediaType.TEXT_HTML)
    public Object request(@FormParam("taxId") String taxId, @FormParam("name") String name,
            @FormParam("address") String address, @FormParam("next") String next) {
        synchronized (state) {
            String typedTaxId = upper(taxId);
            String typedName = upper(name);
            String typedAddress = upper(address);
            String refusal = customerRefusal(typedTaxId, typedName, typedAddress);
            if (refusal == null && state.payment.ticketDbId != null
                    && !FiscalDocument.activeForTicket(state.payment.ticketDbId).isEmpty()) {
                refusal = i18n.get("fiscalpt.dejaEmis");
            }
            if (refusal != null) {
                return render(MODE_REQUEST, next, refusal, "", typedTaxId, typedName, typedAddress, "", "", "");
            }
            state.ticket.invoiceRequest = new TicketState.InvoiceRequest(typedTaxId, typedName, typedAddress);
            state.touch();
            if ("pay".equals(next)) {
                // The payment screen states the request on its FATURA key: its
                // message zone speaks refusals only.
                return Response.seeOther(URI.create("/pay")).build();
            }
            state.ticket.setNotice(i18n.format("fiscalpt.faturaDemandee", typedName));
            return Response.seeOther(URI.create("/")).build();
        }
    }

    /**
     * Turns a past simplified invoice into an invoice (LC-08-04-21), then prints
     * the credit note cancelling it and the invoice.
     *
     * @param day the simplified invoice's day, {@code DDMMYYYY}
     * @param terminal the register that issued it
     * @param rank its number in its series
     * @param taxId the customer's NIF
     * @param name the customer's name
     * @param address the customer's address
     * @return the screen, stating what was issued or why nothing was
     */
    @POST
    @Path("/convert")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance convert(@FormParam("day") String day, @FormParam("terminal") String terminal,
            @FormParam("rank") String rank, @FormParam("taxId") String taxId,
            @FormParam("name") String name, @FormParam("address") String address) {
        String typedTaxId = upper(taxId);
        String typedName = upper(name);
        String typedAddress = upper(address);
        String typedTerminal = upper(terminal);
        String refusal = referenceRefusal(day, rank);
        if (refusal == null) {
            refusal = customerRefusal(typedTaxId, typedName, typedAddress);
        }
        if (refusal == null) {
            PortugueseFiscalService.Outcome outcome = fiscal.convertToInvoice(parseDay(day), typedTerminal,
                    Long.parseLong(rank.trim()),
                    new TicketState.InvoiceRequest(typedTaxId, typedName, typedAddress));
            refusal = printed(outcome);
            if (refusal == null) {
                return render(MODE_CONVERT, "", "", issuedMessage(outcome), "", "", "",
                        LocalDate.now().format(TYPED_DATE), terminalId, "");
            }
        }
        return render(MODE_CONVERT, "", refusal, "", typedTaxId, typedName, typedAddress,
                safe(day), typedTerminal, safe(rank));
    }

    /**
     * Issues the receipt of a past invoice (LC-08-04-22), then prints it.
     *
     * @param day the invoice's day, {@code DDMMYYYY}
     * @param terminal the register that issued it
     * @param rank its number in its series
     * @return the screen, stating what was issued or why nothing was
     */
    @POST
    @Path("/receipt")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance receipt(@FormParam("day") String day, @FormParam("terminal") String terminal,
            @FormParam("rank") String rank) {
        String typedTerminal = upper(terminal);
        String refusal = referenceRefusal(day, rank);
        if (refusal == null) {
            PortugueseFiscalService.Outcome outcome = fiscal.issueReceipt(parseDay(day), typedTerminal,
                    Long.parseLong(rank.trim()));
            refusal = printed(outcome);
            if (refusal == null) {
                return render(MODE_RECEIPT, "", "", issuedMessage(outcome), "", "", "",
                        LocalDate.now().format(TYPED_DATE), terminalId, "");
            }
        }
        return render(MODE_RECEIPT, "", refusal, "", "", "", "", safe(day), typedTerminal, safe(rank));
    }

    /**
     * The mode the screen opens on: the request during a sale, else the one asked.
     *
     * @param asked the mode asked for, possibly null
     * @return the mode
     */
    private String modeFor(String asked) {
        if (!state.ticket.items.isEmpty()) {
            return MODE_REQUEST;
        }
        return MODE_RECEIPT.equals(asked) ? MODE_RECEIPT : MODE_CONVERT;
    }

    /**
     * Why a typed customer cannot be stated on an invoice.
     *
     * @param taxId the typed NIF
     * @param name the typed name
     * @param address the typed address
     * @return the refusal, null when the customer can be stated
     */
    private String customerRefusal(String taxId, String name, String address) {
        if (taxId.isEmpty() || !taxId.matches("[A-Z0-9 ./-]{1,30}")) {
            return i18n.get("fiscalpt.nifObligatoire");
        }
        if (!TaxIdRules.acceptable(taxId, settings.taxIdPortugueseCheck())) {
            return i18n.get("saletaxid.invalidPt");
        }
        if (name.isEmpty() || address.isEmpty()) {
            return i18n.get("fiscalpt.nomObligatoire");
        }
        return null;
    }

    /**
     * Why a typed document reference cannot be looked up.
     *
     * @param day the typed day
     * @param rank the typed number
     * @return the refusal, null when the reference is readable
     */
    private String referenceRefusal(String day, String rank) {
        if (state.trainingMode) {
            return i18n.get("fiscalpt.formation");
        }
        if (parseDay(day) == null) {
            return i18n.get("fiscalpt.dateInvalide");
        }
        if (rank == null || !rank.trim().matches("[0-9]{1,9}")) {
            return i18n.get("fiscalpt.numeroInvalide");
        }
        return null;
    }

    /**
     * Prints the documents of an outcome, or says why there are none.
     *
     * @param outcome the service's outcome
     * @return the translated refusal, null when the documents were printed
     */
    private String printed(PortugueseFiscalService.Outcome outcome) {
        if (outcome.error() != null) {
            return i18n.get(outcome.error());
        }
        for (FiscalDocument document : outcome.documents()) {
            printer.printFiscalDocument(document);
        }
        return null;
    }

    /**
     * Names the documents an outcome issued.
     *
     * @param outcome the service's outcome
     * @return the confirmation
     */
    private String issuedMessage(PortugueseFiscalService.Outcome outcome) {
        List<String> numbers = new ArrayList<>();
        for (FiscalDocument document : outcome.documents()) {
            numbers.add(document.number);
        }
        return i18n.format("fiscalpt.emis", String.join(" - ", numbers));
    }

    /**
     * Reads a day typed {@code DDMMYYYY}.
     *
     * @param day the typed day, possibly null
     * @return the day, null when it is not one
     */
    private static LocalDate parseDay(String day) {
        try {
            return LocalDate.parse(safe(day).trim(), TYPED_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Upper-cases and trims a typed value.
     *
     * @param value the typed value, possibly null
     * @return the value, empty when none was typed
     */
    private static String upper(String value) {
        return safe(value).trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Reads a possibly absent value as text.
     *
     * @param value the value, possibly null
     * @return the value, empty when absent
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Renders the screen with its fields, its message and where it goes back to.
     *
     * @param mode the mode
     * @param next {@code pay} when the payment screen sent the cashier here
     * @param error the refusal to show, empty when none
     * @param info the confirmation to show, empty when none
     * @param taxId the NIF field
     * @param name the name field
     * @param address the address field
     * @param day the day field
     * @param terminal the register field
     * @param rank the number field
     * @return the screen
     */
    private TemplateInstance render(String mode, String next, String error, String info, String taxId,
            String name, String address, String day, String terminal, String rank) {
        List<EntryField> fields = new ArrayList<>();
        if (!MODE_REQUEST.equals(mode)) {
            fields.add(new EntryField("day", i18n.get("fiscalpt.date"), "numpad", false, 8, true, day));
            fields.add(new EntryField("terminal", i18n.get("fiscalpt.caisse"), "email", true, 20, true, terminal));
            fields.add(new EntryField("rank", i18n.get("fiscalpt.numero"), "numpad", false, 9, true, rank));
        }
        if (!MODE_RECEIPT.equals(mode)) {
            fields.add(new EntryField("taxId", i18n.get("fiscalpt.nif"), "email", true, 30, true, taxId));
            fields.add(new EntryField("name", i18n.get("fiscalpt.nom"), "email", true, 120, true, name));
            fields.add(new EntryField("address", i18n.get("fiscalpt.adresse"), "email", true, 200, true, address));
        }
        return page.data("mode", mode)
                .data("next", "pay".equals(next) ? "pay" : "")
                .data("error", error)
                .data("info", info)
                .data("fields", fields);
    }
}
