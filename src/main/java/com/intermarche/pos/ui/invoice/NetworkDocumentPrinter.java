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

    private static final Logger LOG = Logger.getLogger(NetworkDocumentPrinter.class);

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
        return printUrl.isPresent() && !printUrl.get().isBlank();
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(printUrl.get()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/html; charset=utf-8")
                    .header("X-Document-Number", document.number == null ? "" : document.number)
                    .header("X-Document-Text-Length",
                            String.valueOf(fallbackLines == null ? 0 : fallbackLines.size()))
                    .POST(HttpRequest.BodyPublishers.ofString(html(document), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("Impression réseau refusée pour le document %s (HTTP %d)",
                        document.number, response.statusCode());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.warnf("Imprimante réseau injoignable pour le document %s : %s",
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
    public static String html(InvoiceDocument document) {
        StringBuilder page = new StringBuilder();
        page.append("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"utf-8\">")
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
                .append("</style></head><body>");
        page.append("<h1>").append(escape(document.title)).append(' ')
                .append(escape(document.number)).append("</h1>");
        if (document.isDuplicate()) {
            page.append("<p class=\"dup\">DUPLICATA N°")
                    .append(document.duplicateNumber).append("</p>");
        }
        page.append("<p>Date : ").append(escape(document.issueDate))
                .append(" — Ticket : ").append(escape(document.ticketNumber))
                .append(" — Caisse : ").append(escape(document.terminal)).append("</p>");
        page.append("<div class=\"parties\">");
        party(page, "Vendeur", document.seller);
        party(page, "Client", document.customer);
        page.append("</div>");
        articles(page, document.lines);
        vat(page, document.vatRows);
        totals(page, document);
        tenders(page, document.tenders);
        page.append("<footer>").append(escape(document.legalFooter)).append("<br>")
                .append(escape(document.getLegalMentions())).append("</footer>");
        page.append("</body></html>");
        return page.toString();
    }

    /**
     * Writes one framed party block.
     *
     * @param page  the page being built
     * @param title the block's heading
     * @param party the party to write
     */
    private static void party(StringBuilder page, String title, InvoiceDocument.Party party) {
        page.append("<div class=\"box\"><h2>").append(escape(title)).append("</h2>");
        if (!party.accountNumber().isEmpty()) {
            page.append("N° compte : ").append(escape(party.accountNumber())).append("<br>");
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
            page.append("Tél. ").append(escape(party.phone())).append("<br>");
        }
        if (!party.siret().isEmpty()) {
            page.append("SIRET ").append(escape(party.siret())).append("<br>");
        }
        if (!party.vatNumber().isEmpty()) {
            page.append("TVA ").append(escape(party.vatNumber())).append("<br>");
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
    private static void articles(StringBuilder page, List<InvoiceDocument.Line> lines) {
        page.append("<table><thead><tr><th>Désignation</th><th class=\"num\">Qté</th>")
                .append("<th class=\"num\">TVA</th><th class=\"num\">PU HT</th>")
                .append("<th class=\"num\">PU TTC</th><th class=\"num\">Total TTC</th>")
                .append("</tr></thead><tbody>");
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
    private static void vat(StringBuilder page, List<InvoiceDocument.VatRow> rows) {
        page.append("<table><thead><tr><th>Taux</th><th class=\"num\">Base HT</th>")
                .append("<th class=\"num\">TVA</th><th class=\"num\">Total TTC</th>")
                .append("</tr></thead><tbody>");
        for (InvoiceDocument.VatRow row : rows) {
            page.append("<tr><td>").append(escape(row.rate()))
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
    private static void totals(StringBuilder page, InvoiceDocument document) {
        page.append("<table class=\"totals\"><tbody>")
                .append("<tr><td>Total HT</td><td class=\"num\">")
                .append(escape(document.totalExcludingTax)).append(" €</td></tr>")
                .append("<tr><td>Total TVA</td><td class=\"num\">")
                .append(escape(document.totalVat)).append(" €</td></tr>")
                .append("<tr><td><strong>Total TTC</strong></td><td class=\"num\"><strong>")
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
    private static void tenders(StringBuilder page, List<InvoiceDocument.Tender> tenders) {
        if (tenders.isEmpty()) {
            return;
        }
        page.append("<table class=\"totals\"><thead><tr><th colspan=\"2\">Règlement</th>")
                .append("</tr></thead><tbody>");
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
