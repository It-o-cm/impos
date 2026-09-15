package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a printed document from its ADMINISTERED template (BO-03-03).
 *
 * <p>The register already runs Qute for every screen it draws, so the template
 * engine the questionnaire asks for did not have to be written: a document
 * layout is a Qute template like any other, stored in a row instead of a file
 * and edited in the back office.
 *
 * <p>THE CONTRACT, in one sentence: {@link #render} answers the laid-out
 * document, or NULL when the register must print it its own way. Null is
 * returned for a document type nobody administered, for a template whose source
 * is empty, and for a template that fails to parse or render — the last one
 * being the important case, because a paramétreur's typo must cost a fallback
 * to the built-in layout and never a sale that cannot be printed.
 *
 * <p>WHAT A TEMPLATE SEES: maps, lists and strings, handed over by the caller
 * and already formatted. Never an entity, never a service, never the database.
 * A template therefore lays out and nothing else — it cannot reach past what it
 * was given, and it cannot format an amount one way while the rest of the
 * register formats it another.
 *
 * <p>Parsed templates are cached per code, keyed on the source itself: the back
 * office rewrites a template far less often than the shop prints, and a source
 * that changed invalidates its own entry without anyone having to remember to
 * clear a cache.
 */
@ApplicationScoped
public class DocumentTemplateService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DocumentTemplateService.class);

    /** The Qute engine the whole register already runs on. */
    @Inject
    Engine engine;

    /** Parsed templates, keyed by template code. */
    private final Map<String, Parsed> cache = new HashMap<>();

    /**
     * Default constructor, used by CDI, which injects the engine.
     */
    public DocumentTemplateService() {
    }

    /**
     * Builds the renderer over a given engine.
     *
     * <p>For the callers that build this service by hand — the printers' unit
     * tests, which run a real Qute engine rather than a stand-in so that what
     * they assert is a layout and not a mock.
     *
     * @param engine the engine to render with
     */
    public DocumentTemplateService(Engine engine) {
        this.engine = engine;
    }

    /**
     * Renders the administered layout of a document type.
     *
     * @param type the document to lay out, or null
     * @param data the already-formatted values the template may read
     * @return the laid-out document, or null when the register must print it
     *         its own way
     */
    public String render(DocumentTemplate.DocumentType type, Map<String, Object> data) {
        LOGGER.info("Entering method render with type: " + type);
        // No guard on the lookup: a type nobody administers answers null, and
        // rendering null answers null too — the same fallback, said once.
        String rendered = renderTemplate(DocumentTemplate.findFor(type), data);
        LOGGER.info("Exiting method render");
        return rendered;
    }

    /**
     * Renders ONE template against the given values, whatever the referential
     * says — what the back office's preview pane needs.
     *
     * @param template the template to render, or null
     * @param data the already-formatted values the template may read
     * @return the laid-out document, or null when the template cannot render
     */
    public String renderTemplate(DocumentTemplate template, Map<String, Object> data) {
        if (template == null || !template.isRenderable()) {
            return null;
        }
        Template parsed = parse(template);
        if (parsed == null) {
            return null;
        }
        try {
            // Null values and empty values are the same thing to the engine
            // (both raise on a template that reads something), and the catch
            // below answers for both: a guard here would never be observed.
            return parsed.data(data).render();
        } catch (RuntimeException e) {
            // A paramétreur's mistake costs a fallback to the built-in layout,
            // never a sale that cannot be printed.
            LOGGER.errorf(e, "Gabarit %s non rendu, mise en page interne utilisée", template.code);
            return null;
        }
    }

    /**
     * Parses a template, reusing the parsed form while its source is unchanged.
     *
     * @param template the administered template
     * @return the parsed template, or null when the source does not parse
     */
    private Template parse(DocumentTemplate template) {
        synchronized (cache) {
            Parsed cached = cache.get(template.code);
            if (cached != null && cached.source.equals(template.source)) {
                return cached.template;
            }
            try {
                Template compiled = engine.parse(template.source);
                cache.put(template.code, new Parsed(template.source, compiled));
                return compiled;
            } catch (RuntimeException e) {
                // The stale entry is left alone on purpose: it is keyed on the
                // source it was parsed from, so the next call reparses whatever
                // the back office has typed since. Removing it would be a line
                // no behaviour could ever tell apart.
                LOGGER.errorf(e, "Gabarit %s illisible, mise en page interne utilisée",
                        template.code);
                return null;
            }
        }
    }

    /**
     * Tells whether a source parses, which is what the back office checks before
     * saving a template (BO-03-03).
     *
     * @param source the Qute source the paramétreur typed, or null
     * @return null when the source parses, the parser's complaint otherwise
     */
    public String parseError(String source) {
        LOGGER.info("Entering method parseError");
        // A blank source parses happily, so only the null one is short-circuited
        // here — and it is, because the engine would raise on it.
        if (source == null) {
            LOGGER.info("Exiting method parseError");
            return null;
        }
        try {
            engine.parse(source);
            LOGGER.info("Exiting method parseError");
            return null;
        } catch (RuntimeException e) {
            LOGGER.info("Exiting method parseError");
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /**
     * Returns a DEMONSTRATION document of the given type, in the exact shape the
     * register hands a template at printing time (BO-03-03).
     *
     * <p>This method IS the contract between the editor and the printers: the
     * preview pane renders against it, and every reader builds the same shape
     * from a real sale. A key that is not here is a key no template may read.
     *
     * @param type the document to describe, or null
     * @return the demonstration values, never null
     */
    public Map<String, Object> sampleData(DocumentTemplate.DocumentType type) {
        LOGGER.info("Entering method sampleData with type: " + type);
        Map<String, Object> data = new HashMap<>();
        data.put("store", Map.of(
                "name", "INTERMARCHE VAUCRESSON",
                "street", "15 RUE DE LA GARE",
                "postalCode", "92420",
                "city", "VAUCRESSON",
                "siret", "12345678900012"));
        data.put("terminal", "C04");
        data.put("operator", "MARIE");
        data.put("date", "15/09/2026");
        data.put("time", "11:24");
        if (type == null) {
            LOGGER.info("Exiting method sampleData");
            return data;
        }
        switch (type) {
            case SALE_RECEIPT, REFUND_RECEIPT, INVOICE, INVOICE_A4 -> {
                data.put("document", type == DocumentTemplate.DocumentType.REFUND_RECEIPT
                        ? Map.of("number", "C04-R000012", "originalNumber", "C04-000123",
                                "method", "ESPECES")
                        : Map.of("number", "C04-000123", "duplicate", false,
                                "duplicateNumber", "0", "digitalPath", "/t/42/3f9a1c"));
                data.put("lines", List.of(
                        Map.of("label", "LAIT DEMI-ECREME 1L", "ean", "3250390000013",
                                "family", "CREMERIE", "unit", "", "quantity", "2",
                                "unitPrice", "1,15", "total", "2,30", "vatRate", "5.5"),
                        Map.of("label", "PAIN COMPLET 500G", "ean", "3250390000020",
                                "family", "BOULANGERIE", "unit", "", "quantity", "1",
                                "unitPrice", "2,30", "total", "2,30", "vatRate", "5.5")));
                data.put("totals", Map.of("excludingTax", "4,36", "vat", "0,24",
                        "includingTax", "4,60", "discount", ""));
                data.put("vatRows", List.of(
                        Map.of("rate", "5,5", "base", "4,36", "vat", "0,24")));
                data.put("payments", List.of(
                        Map.of("label", "CASH", "key", "CASH", "amount", "4,60")));
                data.put("fidelity", Map.of("card", "6045200000123"));
                if (type == DocumentTemplate.DocumentType.INVOICE
                        || type == DocumentTemplate.DocumentType.INVOICE_A4) {
                    // The invoice names a seller and an addressee the receipt
                    // never carries, and states its own kind of document.
                    data.put("document", Map.of("title", "FACTURE",
                            "number", "C04-F000042", "issueDate", "15/09/2026",
                            "saleDate", "15/09/2026", "ticketNumber", "C04-000123",
                            "duplicate", false, "duplicateNumber", "0",
                            "legalFooter", "", "legalMentions", "", "logo", ""));
                    data.put("seller", Map.of("name", "INTERMARCHE VAUCRESSON",
                            "legalName", "ITM VAUCRESSON SAS", "contact", "",
                            "addressLines", List.of("15 RUE DE LA GARE", "92420 VAUCRESSON"),
                            "phone", "", "fax", "", "siret", "12345678900012",
                            "vatNumber", "FR00123456789", "accountNumber", ""));
                    data.put("customer", Map.of("name", "SARL DUPONT",
                            "legalName", "SARL DUPONT", "contact", "",
                            "addressLines", List.of("3 RUE DES LILAS", "92100 BOULOGNE"),
                            "phone", "", "fax", "", "siret", "98765432100011",
                            "vatNumber", "", "accountNumber", "C000123"));
                    data.put("totals", Map.of("excludingTax", "4,36", "vat", "0,24",
                            "includingTax", "4,60", "methods", "ESPECES"));
                }
            }
            case X_REPORT, Z_REPORT -> {
                data.put("session", Map.of("number", "C04-S00012",
                        "openedAt", "15/09/2026 08:02", "closedAt", "15/09/2026 20:14",
                        "closing", type == DocumentTemplate.DocumentType.Z_REPORT));
                data.put("totals", Map.of("ticketCount", "128", "includingTax", "3412,55",
                        "refunds", "24,90", "theoreticalCash", "1204,30",
                        "openingFloat", "100,00", "countedCash", "1204,30",
                        "variance", "0,00", "withdrawn", "1104,30",
                        "otherTenders", "0,00", "bankDeposit", "2258,25"));
                data.put("tenders", List.of(
                        Map.of("label", "CASH", "amount", "1154,30"),
                        Map.of("label", "CARD", "amount", "2258,25")));
            }
            case WITHDRAWAL_TICKET, TRANSFER_TICKET -> {
                boolean transfer = type == DocumentTemplate.DocumentType.TRANSFER_TICKET;
                data.put("movement", Map.of(
                        "label", transfer ? "TRANSFERT REGLEMENT" : "PRELEVEMENT",
                        "tender", transfer ? "" : "CASH",
                        "from", transfer ? "CASH" : "",
                        "to", transfer ? "CHEQUE" : "",
                        "amount", "500,00"));
                data.put("counts", transfer ? List.of() : List.of(
                        Map.of("label", "Billets 50", "amount", "8"),
                        Map.of("label", "Billets 20", "amount", "5")));
            }
            case GIFT_CARD_VOUCHER, CREDIT_NOTE_VOUCHER -> data.put("instrument", Map.of(
                    "number", type == DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER
                            ? "296000000000042" : "297000000000042",
                    "amount", "30,00"));
            case CARD_RECEIPT -> data.put("card", Map.of(
                    "kind", "CREDIT", "amount", "4,60", "frame", ""));
        }
        LOGGER.info("Exiting method sampleData");
        return data;
    }

    /**
     * Forgets every parsed template, so the next print reparses.
     *
     * <p>Needed by the referential pull, which rewrites the rows underneath this
     * service without going through the back-office screen.
     */
    public void clearCache() {
        LOGGER.info("Entering method clearCache");
        synchronized (cache) {
            cache.clear();
        }
        LOGGER.info("Exiting method clearCache");
    }

    /**
     * One parsed template and the source it was parsed from.
     */
    private static final class Parsed {

        /** The source this entry was parsed from. */
        private final String source;

        /** The parsed template. */
        private final Template template;

        /**
         * Builds a cache entry.
         *
         * @param source the source the template was parsed from
         * @param template the parsed template
         */
        private Parsed(String source, Template template) {
            this.source = source;
            this.template = template;
        }
    }
}
