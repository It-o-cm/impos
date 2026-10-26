package com.intermarche.pos.ui.invoice;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Sends a document to the store's network printer, as an A4 page
 * ({@code LC-08-04-11}).
 *
 * <p>THE A4 PAGE IS THE REFERENCE LAYOUT, and the forty-two columns of the roll are the
 * degraded one — the framed parties, the bordered article table and the columns that
 * line up are what a commercial document looks like, and a network printer is the only
 * output of this register able to draw them. That is why the page is rendered here as
 * HTML rather than pushed as the roll's text: a print service that receives text can
 * only reprint the degraded rendering on a bigger sheet.
 *
 * <p>IT NEVER SWALLOWS THE DOCUMENT. A print service that is not configured, is down,
 * or refuses the page leaves the operator with a customer waiting at the till and
 * nothing in their hand, so a failure is reported rather than logged and forgotten —
 * the flow then falls back to the roll, which is always there.
 */
@ApplicationScoped
public class NetworkDocumentPrinter {

    private static final Logger LOGGER = Logger.getLogger(NetworkDocumentPrinter.class);

    /**
     * The localised labels of the A4 document (LC-13-06-11).
     *
     * <p>Handed to the static layout rather than read from it: the layout is a
     * pure function of the document, and the page it writes must carry the
     * language the operator chose, including in its {@code <html lang>}.
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;


    /**
     * The store's print service, absent on a register that has none.
     * <p>
     * It is an ordinary HTTP endpoint receiving an HTML page — a print server, the
     * store node, or the printer's own embedded server. Nothing here is specific to a
     * printer brand, and nothing has to be: the page is finished when it leaves.
     */
    @ConfigProperty(name = "pos.print.a4-url")
    Optional<String> printUrl;

    /** Shared HTTP client toward the print service. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Whether this register knows a network printer at all.
     *
     * @return true when a print service is configured
     */
    public boolean isAvailable() {
        return secondPrinterConfigured() || (printUrl.isPresent() && !printUrl.get().isBlank());
    }

    /**
     * The register's administered parameters (BO-03-09-22), null on an
     * instance built by hand: the second printer is then simply not there.
     */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The hardware module, which drives a second printer plugged locally (BO-03-09-22). */
    @jakarta.inject.Inject
    @org.eclipse.microprofile.rest.client.inject.RestClient
    com.intermarche.pos.ui.hardware.HardwareClient hardwareClient;

    /**
     * Whether the back office gave this register a SECOND PRINTER, active and
     * addressed (BO-03-09-22). It then replaces the deployment's print service.
     *
     * @return true when the second printer is to be used
     */
    boolean secondPrinterConfigured() {
        return posSettingsService != null && posSettingsService.secondPrinterEnabled()
                && !posSettingsService.secondPrinterAddress().isEmpty();
    }

    /**
     * The administered layouts (BO-03-03).
     *
     * <p>The A4 page below is built by hand in Java, which on a register that
     * already runs Qute was always a debt. An administered layout replaces it
     * whole; a shop that administers none keeps it, unchanged.
     */
    @jakarta.inject.Inject
    com.intermarche.pos.service.DocumentTemplateService documentTemplateService;

    /**
     * Lays the A4 page out, through the ADMINISTERED layout when the shop has
     * one (BO-03-03).
     *
     * @param document the laid-out document
     * @return the page's HTML
     */
    String page(InvoiceDocument document) {
        String administered = documentTemplateService == null ? null
                : administeredPage(document);
        return administered != null ? administered : html(document, i18n);
    }

    /**
     * The administered A4 page: the template of the administered document the
     * paper is drawn as when it lays out an A4 page ({@code BO-03-04-09}), the
     * shop's A4 invoice template otherwise.
     *
     * @param document the laid-out document
     * @return the page, or null when the register lays it out itself
     */
    private String administeredPage(InvoiceDocument document) {
        java.util.Map<String, Object> data = document.asDocumentData();
        if (!document.templateCode.isEmpty()) {
            com.intermarche.pos.domain.setting.DocumentTemplate template =
                    com.intermarche.pos.domain.setting.DocumentTemplate.findByCode(document.templateCode);
            if (template != null && template.documentType
                    == com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.INVOICE_A4) {
                String own = documentTemplateService.renderTemplate(template, data);
                if (own != null) {
                    return own;
                }
            }
        }
        return documentTemplateService.render(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.INVOICE_A4, data);
    }

    /**
     * Prints a document on the network printer.
     *
     * @param document the laid-out document
     * @param fallbackLines the same document rendered for the roll, sent as a plain-text
     *        companion so a service that cannot render HTML still has something to print
     * @return true when the print service accepted the page, false when there is none or
     *         it refused — in which case the caller must print somewhere else
     */
    public boolean print(InvoiceDocument document, List<String> fallbackLines) {
        if (!isAvailable() || document == null) {
            return false;
        }
        if (secondPrinterConfigured()) {
            return printOnSecondPrinter(document, fallbackLines);
        }
        return post(printUrl.get(), "text/html; charset=utf-8", page(document), document, fallbackLines);
    }

    /**
     * Prints on the administered SECOND PRINTER (BO-03-09-22): an A4 page or
     * the roll's text, according to its format, through the hardware module
     * when it is plugged on the register, posted to its address when it is on
     * the network.
     *
     * @param document the laid-out document
     * @param fallbackLines the same document rendered for the roll
     * @return true when the printer accepted the document
     */
    boolean printOnSecondPrinter(InvoiceDocument document, List<String> fallbackLines) {
        boolean a4 = "A4".equals(posSettingsService.secondPrinterFormat());
        String body = a4 ? page(document) : String.join("\n", fallbackLines == null ? List.of() : fallbackLines);
        String address = posSettingsService.secondPrinterAddress();
        if (!"LOCAL".equals(posSettingsService.secondPrinterConnection())) {
            return post(address, a4 ? "text/html; charset=utf-8" : "text/plain; charset=utf-8", body,
                    document, fallbackLines);
        }
        try {
            hardwareClient.printOnSecondPrinter(address, body);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Seconde imprimante locale injoignable pour le document %s : %s",
                    document.number, e.getMessage());
            return false;
        }
    }

    /**
     * Posts a document to a print service.
     *
     * @param url the service's address
     * @param contentType the media type of the body
     * @param body the page or the text
     * @param document the laid-out document
     * @param fallbackLines the same document rendered for the roll
     * @return true when the service accepted it
     */
    private boolean post(String url, String contentType, String body, InvoiceDocument document,
                         List<String> fallbackLines) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", contentType)
                    .header("X-Document-Number", document.number == null ? "" : document.number)
                    .header("X-Document-Text-Length",
                            String.valueOf(fallbackLines == null ? 0 : fallbackLines.size()))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warnf("Impression réseau refusée pour le document %s (HTTP %d)",
                        document.number, response.statusCode());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOGGER.warnf("Imprimante réseau injoignable pour le document %s : %s",
                    document.number, e.getMessage());
            return false;
        }
    }

    /**
     * Renders the document as a self-contained A4 page.
     *
     * <p>Self-contained on purpose: the page leaves the register and is rendered by
     * something this register knows nothing about, so it carries its own style sheet
     * and no external reference of any kind. It formats NOTHING — every amount, rate
     * and date arrives already written on the model, for the same reason the roll
     * renderer formats nothing.
     *
     * @param document the laid-out document
     * @return the page, as HTML
     */
    public static String html(InvoiceDocument document, com.intermarche.pos.ui.Messages i18n) {
        StringBuilder page = new StringBuilder();
        page.append("<!DOCTYPE html><html lang=\"").append(i18n.getLang())
                .append("\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(escape(document.title)).append(' ')
                .append(escape(document.number)).append("</title><style>")
                .append("@page { size: A4; margin: 14mm; }")
                .append("body { font-family: Arial, Helvetica, sans-serif; font-size: 10pt; color: #000; }")
                .append("h1 { font-size: 16pt; margin: 0 0 2mm; }")
                .append(".dup { color: #b00; font-weight: bold; }")
                .append(".parties { display: flex; gap: 6mm; margin: 4mm 0; }")
                .append(".box { flex: 1; border: 1px solid #000; padding: 3mm; }")
                .append(".box h2 { font-size: 9pt; margin: 0 0 1.5mm; text-transform: uppercase; }")
                .append("table { width: 100%; border-collapse: collapse; margin: 3mm 0; }")
                .append("th, td { border: 1px solid #000; padding: 1.5mm 2mm; text-align: left; }")
                .append("th { background: #eee; font-size: 9pt; }")
                .append("td.num, th.num { text-align: right; }")
                .append(".attr { color: #444; font-size: 8pt; }")
                .append(".totals { width: 60mm; margin-left: auto; }")
                .append("footer { margin-top: 6mm; font-size: 8pt; color: #333; }")
                .append(".pageno { text-align: right; font-size: 8pt; margin: 0; }")
                .append(".sheet { page-break-after: always; }")
                .append("</style></head><body>");
        // BO-03-03-02: the articles are cut into pages, each page restating
        // the head of the document and its « page X / Y »; the tables of the
        // totals close the last one.
        List<List<InvoiceDocument.Line>> sheets = sheets(document.lines, document.linesPerPage);
        for (int index = 0; index < sheets.size(); index++) {
            boolean last = index == sheets.size() - 1;
            page.append(last ? "<div>" : "<div class=\"sheet\">");
            head(page, document, i18n, index + 1, sheets.size());
            articles(page, sheets.get(index), i18n);
            if (last) {
                vat(page, document.vatRows, i18n);
                totals(page, document, i18n);
                tenders(page, document.tenders, i18n);
                // BO-03-05-08: the address the shop prints at the foot of its
                // documents, written out on a page.
                if (document.statesFooterUrl()) {
                    page.append("<p>").append(escape(document.footerUrl)).append("</p>");
                }
                page.append("<footer>").append(escape(document.legalFooter)).append("<br>")
                        .append(escape(document.getLegalMentions())).append("</footer>");
            }
            page.append("</div>");
        }
        page.append("</body></html>");
        return page.toString();
    }

    /**
     * Writes the HEAD of one page: the title and number, the duplicate mark,
     * the dates, the order number, the parties and « page X / Y »
     * ({@code BO-03-03-02}).
     *
     * @param page the page being written
     * @param document the laid-out document
     * @param i18n the operator wordings
     * @param number the page's rank, one-based
     * @param count how many pages the document takes
     */
    private static void head(StringBuilder page, InvoiceDocument document,
            com.intermarche.pos.ui.Messages i18n, int number, int count) {
        page.append("<p class=\"pageno\">").append(escape(i18n.format("doc.pageSur", number, count)))
                .append("</p>");
        page.append("<h1>").append(escape(document.title)).append(' ')
                .append(escape(document.number)).append("</h1>");
        if (document.isDuplicate()) {
            page.append("<p class=\"dup\">").append(escape(i18n.get("doc.duplicata")))
                    .append(document.duplicateNumber).append("</p>");
        }
        page.append("<p>").append(escape(i18n.get("doc.date"))).append(escape(document.issueDate))
                .append(escape(i18n.get("doc.ticket"))).append(escape(document.ticketNumber))
                .append(escape(i18n.get("doc.caisse"))).append(escape(document.terminal));
        // BO-02-04-08: l'échéance figure obligatoirement sur le document.
        if (document.statesDueDate()) {
            page.append(escape(i18n.get("doc.echeance"))).append(escape(document.dueDate));
        } else {
            // BO-03-03-02: without terms, the document is due on receipt.
            page.append(" — ").append(escape(i18n.get("doc.paiementReception")));
        }
        if (document.statesOrderNumber()) {
            page.append(" — ").append(escape(i18n.get("doc.commandeCourt"))).append(" : ")
                    .append(escape(document.orderNumber));
        }
        page.append("</p>");
        page.append("<div class=\"parties\">");
        party(page, i18n.get("doc.vendeur"), document.seller, i18n);
        party(page, i18n.get("doc.client"), document.customer, i18n);
        page.append("</div>");
    }

    /**
     * Cuts the article rows into the pages of the document; a document without
     * rows, or without a page size, is one page.
     *
     * @param lines the article rows
     * @param perPage the rows of one page, zero or less for a single page
     * @return the pages, never empty
     */
    static List<List<InvoiceDocument.Line>> sheets(List<InvoiceDocument.Line> lines, int perPage) {
        List<List<InvoiceDocument.Line>> sheets = new java.util.ArrayList<>();
        if (perPage <= 0 || lines.size() <= perPage) {
            sheets.add(lines);
            return sheets;
        }
        for (int from = 0; from < lines.size(); from += perPage) {
            sheets.add(lines.subList(from, Math.min(from + perPage, lines.size())));
        }
        return sheets;
    }

    /**
     * Writes one framed party block.
     *
     * @param page  the page being built
     * @param title the block's heading
     * @param party the party to write
     */
    private static void party(StringBuilder page, String title, InvoiceDocument.Party party,
            com.intermarche.pos.ui.Messages i18n) {
        page.append("<div class=\"box\"><h2>").append(escape(title)).append("</h2>");
        if (!party.accountNumber().isEmpty()) {
            page.append(escape(i18n.get("doc.numeroCompte"))).append(escape(party.accountNumber()))
                    .append("<br>");
        }
        page.append("<strong>").append(escape(party.name())).append("</strong><br>");
        if (!party.legalName().isEmpty()) {
            page.append(escape(party.legalName())).append("<br>");
        }
        if (!party.contact().isEmpty()) {
            page.append(escape(party.contact())).append("<br>");
        }
        for (String line : party.addressLines()) {
            page.append(escape(line)).append("<br>");
        }
        if (!party.phone().isEmpty()) {
            page.append(escape(i18n.get("doc.tel"))).append(escape(party.phone())).append("<br>");
        }
        if (!party.siret().isEmpty()) {
            page.append(escape(i18n.get("doc.siret"))).append(escape(party.siret())).append("<br>");
        }
        if (!party.vatNumber().isEmpty()) {
            page.append(escape(i18n.get("doc.tvaNumero"))).append(escape(party.vatNumber()))
                    .append("<br>");
        }
        page.append("</div>");
    }

    /**
     * Writes the article table, the unit price shown both excluding and including tax
     * as the reference layout does.
     *
     * @param page  the page being built
     * @param lines the article rows
     */
    private static void articles(StringBuilder page, List<InvoiceDocument.Line> lines,
            com.intermarche.pos.ui.Messages i18n) {
        page.append("<table><thead><tr><th>").append(escape(i18n.get("doc.designation")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.qte")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.tvaCourt")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.puHt")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.puTtc")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.totalHt")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.totalTtc")))
                .append("</th></tr></thead><tbody>");
        for (InvoiceDocument.Line line : lines) {
            page.append("<tr><td>").append(escape(line.label()));
            if (!line.attributes().isEmpty()) {
                page.append("<br><span class=\"attr\">").append(escape(line.attributes()))
                        .append("</span>");
            }
            if (!line.ean().isEmpty()) {
                page.append("<br><span class=\"attr\">").append(escape(line.ean()))
                        .append("</span>");
            }
            page.append("</td><td class=\"num\">").append(escape(line.quantity()))
                    .append("</td><td class=\"num\">").append(escape(line.vatRate()))
                    .append("</td><td class=\"num\">").append(escape(line.unitExcludingTax()))
                    .append("</td><td class=\"num\">").append(escape(line.unitIncludingTax()))
                    .append("</td><td class=\"num\">").append(escape(line.totalExcludingTax()))
                    .append("</td><td class=\"num\">").append(escape(line.totalIncludingTax()))
                    .append("</td></tr>");
        }
        page.append("</tbody></table>");
    }

    /**
     * Writes the VAT table.
     *
     * @param page the page being built
     * @param rows the VAT rows
     */
    private static void vat(StringBuilder page, List<InvoiceDocument.VatRow> rows,
            com.intermarche.pos.ui.Messages i18n) {
        page.append("<table><thead><tr><th>").append(escape(i18n.get("doc.taux")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.baseHt")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.tvaCourt")))
                .append("</th><th class=\"num\">").append(escape(i18n.get("doc.totalTtc")))
                .append("</th></tr></thead><tbody>");
        for (InvoiceDocument.VatRow row : rows) {
            // BO-03-03-04: the regime's code before its rate.
            String code = row.code() == null || row.code().isEmpty() ? "" : row.code() + " ";
            page.append("<tr><td>").append(escape(code + row.rate()))
                    .append("</td><td class=\"num\">").append(escape(row.excludingTax()))
                    .append("</td><td class=\"num\">").append(escape(row.vat()))
                    .append("</td><td class=\"num\">").append(escape(row.includingTax()))
                    .append("</td></tr>");
        }
        page.append("</tbody></table>");
    }

    /**
     * Writes the totals block.
     *
     * @param page     the page being built
     * @param document the document whose totals are written
     */
    private static void totals(StringBuilder page, InvoiceDocument document,
            com.intermarche.pos.ui.Messages i18n) {
        page.append("<table class=\"totals\"><tbody>")
                .append("<tr><td>").append(escape(i18n.get("doc.totalHt"))).append("</td><td class=\"num\">")
                .append(escape(document.totalExcludingTax)).append(" €</td></tr>")
                .append("<tr><td>").append(escape(i18n.get("doc.totalTva"))).append("</td><td class=\"num\">")
                .append(escape(document.totalVat)).append(" €</td></tr>")
                .append("<tr><td><strong>").append(escape(i18n.get("doc.totalTtc")))
                .append("</strong></td><td class=\"num\"><strong>")
                .append(escape(document.totalIncludingTax)).append(" €</strong></td></tr>")
                .append("</tbody></table>");
    }

    /**
     * Writes the settlement breakdown, nothing at all when the document states no
     * tender.
     *
     * @param page    the page being built
     * @param tenders the settlement rows
     */
    private static void tenders(StringBuilder page, List<InvoiceDocument.Tender> tenders,
            com.intermarche.pos.ui.Messages i18n) {
        if (tenders.isEmpty()) {
            return;
        }
        page.append("<table class=\"totals\"><thead><tr><th colspan=\"2\">")
                .append(escape(i18n.get("doc.reglement"))).append("</th></tr></thead><tbody>");
        for (InvoiceDocument.Tender tender : tenders) {
            page.append("<tr><td>").append(escape(tender.label()))
                    .append("</td><td class=\"num\">").append(escape(tender.amount()))
                    .append(" €</td></tr>");
        }
        page.append("</tbody></table>");
    }

    /**
     * Escapes a value for the page.
     *
     * <p>Every field of the document comes from a store's or a customer's own typing —
     * a business name, an address, an article label — so none of it may be written into
     * the markup as it stands.
     *
     * @param value the value, possibly null
     * @return the escaped value, empty when there was none
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
