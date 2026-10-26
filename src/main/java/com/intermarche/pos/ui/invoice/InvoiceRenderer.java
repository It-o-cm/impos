package com.intermarche.pos.ui.invoice;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a laid-out document into fixed-width lines, for the receipt roll and the
 * document station.
 *
 * <p>THE DEGRADED RENDERING, and it says so. The reference layout of a commercial
 * document is the A4 page — framed blocks, a bordered table, columns that line up —
 * and that page is what the screen shows and what a network printer produces. This
 * renderer exists because a register also has a 42-column roll and a slip station,
 * which can print text and nothing else. It states the same document with less.
 *
 * <p>It reads an {@link InvoiceDocument} and formats NOTHING: every amount, rate,
 * quantity and date arrives already written. That is the whole point of there being
 * a model — two renderers that each format would be two chances to disagree, and the
 * French decimal comma is exactly the detail that gets fixed in one and forgotten in
 * the other.
 */
public final class InvoiceRenderer {

    /** Standard 80 mm width, in characters — the receipt's own. */
    public static final int WIDTH = 42;

    /** The separator between zones. */
    private static final String RULE = "-".repeat(WIDTH);

    /** How far the lines under a heading are pushed in. */
    private static final String INDENT = "  ";

    /**
     * Not instantiable: rendering is a function, not an object.
     */
    private InvoiceRenderer() {
    }

    /**
     * Renders a document.
     *
     * @param document the laid-out document
     * @return the document, one entry per line, without trailing newlines
     */
    public static List<String> render(InvoiceDocument document, com.intermarche.pos.ui.Messages i18n) {
        List<String> out = new ArrayList<>();
        seller(out, document.seller, i18n);
        out.add(RULE);
        out.add(center(document.title));
        if (document.isDuplicate()) {
            out.add(center(i18n.format("doc.duplicataEncadre", document.duplicateNumber)));
        }
        out.add(RULE);
        out.add(pair(i18n.format("doc.numeroDe", document.title), document.number));
        out.add(pair(i18n.get("doc.dateCourt"), document.issueDate));
        // BO-02-04-08: l'échéance figure obligatoirement sur le document, et
        // seulement quand le client en porte une — une ligne « Échéance » vide
        // se lirait comme un paiement immédiat.
        if (document.statesDueDate()) {
            out.add(pair(i18n.get("doc.echeanceCourt"), document.dueDate));
        } else {
            // BO-03-03-02: without terms, the document says it is due on receipt.
            out.add(i18n.get("doc.paiementReception"));
        }
        out.add(pair(i18n.get("doc.ticketCourt"), document.ticketNumber));
        // BO-03-03-02: the customer's order number, and no line at all when the
        // operator typed none.
        if (document.statesOrderNumber()) {
            out.add(pair(i18n.get("doc.commandeCourt"), document.orderNumber));
        }
        out.add(RULE);
        customer(out, document.customer,
                document.statesCustomerTaxId() ? document.customerTaxId : "", i18n);
        out.add(RULE);
        articles(out, document.lines, i18n);
        out.add(RULE);
        vat(out, document.vatRows, i18n);
        out.add(RULE);
        out.add(pair(i18n.get("doc.totalHtPapier"), document.totalExcludingTax + " E"));
        out.add(pair(i18n.get("doc.totalTvaPapier"), document.totalVat + " E"));
        out.add(pair(i18n.get("doc.totalTtcPapier"), document.totalIncludingTax + " E"));
        tenders(out, document.tenders, i18n);
        footerUrl(out, document);
        return out;
    }

    /**
     * Appends the address the shop prints at the foot of its documents
     * ({@code BO-03-05-08}), as the roll prints it: a QR code by default, a
     * barcode, or the address written out.
     *
     * @param out the lines so far
     * @param document the laid-out document
     */
    private static void footerUrl(List<String> out, InvoiceDocument document) {
        if (!document.statesFooterUrl()) {
            return;
        }
        out.add(RULE);
        if ("TEXTE".equals(document.footerUrlFormat)) {
            for (int from = 0; from < document.footerUrl.length(); from += WIDTH) {
                out.add(center(document.footerUrl.substring(from,
                        Math.min(from + WIDTH, document.footerUrl.length()))));
            }
            return;
        }
        out.add(("BARCODE".equals(document.footerUrlFormat) ? "[[BARCODE " : "[[QR ")
                + document.footerUrl + "]]");
    }

    /**
     * Cuts a document into the sheets of the slip station, each sheet headed by
     * its page number out of the whole ({@code BO-03-03-02}, « page X / Y »).
     *
     * @param lines the document, line by line
     * @param linesPerPage how many lines one sheet takes, zero or less for one
     *        sheet
     * @param i18n the operator wordings
     * @return the sheets, each opening with its « page X / Y » line
     */
    public static List<List<String>> paginateNumbered(List<String> lines, int linesPerPage,
            com.intermarche.pos.ui.Messages i18n) {
        List<List<String>> pages = paginate(lines, linesPerPage);
        for (int index = 0; index < pages.size(); index++) {
            pages.get(index).add(0, pageLine(index + 1, pages.size(), i18n));
        }
        return pages;
    }

    /**
     * The « page X / Y » line of a sheet, right-aligned.
     *
     * @param page the sheet's rank, one-based
     * @param pageCount how many sheets the document takes
     * @param i18n the operator wordings
     * @return the line
     */
    static String pageLine(int page, int pageCount, com.intermarche.pos.ui.Messages i18n) {
        String text = i18n.format("doc.pageSur", page, pageCount);
        return " ".repeat(Math.max(WIDTH - text.length(), 0)) + text;
    }

    /**
     * Cuts a rendered document into sheets ({@code LC-08-04-12/13}).
     *
     * <p>The receipt roll has no page: it is continuous paper and the document simply
     * comes out. A SLIP STATION does — the operator feeds it one sheet, it prints what
     * fits, and it stops. Knowing how many sheets a document takes is therefore not a
     * layout detail here but the thing the operator has to be told before starting, and
     * that count is what this returns the shape of.
     *
     * <p>At or below zero lines a sheet, the document is one sheet: a station whose
     * capacity nobody administered still has to print, and printing everything on the
     * first sheet is what an unconfigured slip station does anyway.
     *
     * @param lines        the rendered document, one entry per line
     * @param linesPerPage how many lines one sheet takes, zero or less for one sheet
     * @return the sheets, in printing order, ALWAYS at least one
     */
    public static List<List<String>> paginate(List<String> lines, int linesPerPage) {
        List<String> all = lines == null ? List.of() : lines;
        List<List<String>> pages = new ArrayList<>();
        if (linesPerPage <= 0 || all.size() <= linesPerPage) {
            pages.add(new ArrayList<>(all));
            return pages;
        }
        for (int from = 0; from < all.size(); from += linesPerPage) {
            int to = Math.min(from + linesPerPage, all.size());
            pages.add(new ArrayList<>(all.subList(from, to)));
        }
        return pages;
    }

    /**
     * Writes the seller's block, centred as on the receipt.
     *
     * @param out    the lines being built
     * @param seller who sells
     */
    private static void seller(List<String> out, InvoiceDocument.Party seller,
            com.intermarche.pos.ui.Messages i18n) {
        out.add(center(i18n.get("paper.intermarche")));
        out.add(center(seller.name()));
        if (!seller.legalName().isEmpty()) {
            out.add(center(seller.legalName()));
        }
        for (String addressLine : seller.addressLines()) {
            out.add(center(addressLine));
        }
        if (!seller.phone().isEmpty()) {
            out.add(center(i18n.get("doc.telCourt") + seller.phone()));
        }
        if (!seller.siret().isEmpty()) {
            out.add(center(i18n.get("doc.siret") + seller.siret()));
        }
        if (!seller.vatNumber().isEmpty()) {
            out.add(center(i18n.get("doc.tvaNumero") + seller.vatNumber()));
        }
    }

    /**
     * Writes the addressee's block, flush left so it reads as a postal address.
     *
     * @param out      the lines being built
     * @param customer who is billed
     * @param taxId    the fiscal identifier as printed ({@code BO-10-04-15/-16}),
     *                 empty when the document carries none
     */
    private static void customer(List<String> out, InvoiceDocument.Party customer,
            String taxId, com.intermarche.pos.ui.Messages i18n) {
        out.add(i18n.get("doc.clientMajuscule"));
        if (!customer.accountNumber().isEmpty()) {
            out.add(indented(i18n.get("doc.numeroCompte") + customer.accountNumber()));
        }
        out.add(indented(customer.name()));
        if (!customer.contact().isEmpty()) {
            out.add(indented(customer.contact()));
        }
        for (String addressLine : customer.addressLines()) {
            out.add(indented(addressLine));
        }
        if (!customer.siret().isEmpty()) {
            out.add(indented(i18n.get("doc.siret") + customer.siret()));
        }
        if (!customer.vatNumber().isEmpty()) {
            out.add(indented(i18n.get("doc.tvaNumero") + customer.vatNumber()));
        }
        if (!taxId.isEmpty()) {
            out.add(indented(i18n.get("doc.nif") + taxId));
        }
    }

    /**
     * Writes the article rows.
     *
     * <p>The A4 page gives each of the unit price excluding tax, the unit price
     * including tax and the line total its own column. Forty-two characters cannot,
     * so the tax-excluded unit price rides in parentheses beside the tax-included
     * one and the rate moves to the VAT table, where it is stated anyway.
     * The line total excluding tax takes a line of its own beneath, its
     * figure aligned with the tax-included total above it (BO-10-02-19).
     *
     * @param out   the lines being built
     * @param lines the article rows
     */
    private static void articles(List<String> out, List<InvoiceDocument.Line> lines,
            com.intermarche.pos.ui.Messages i18n) {
        for (InvoiceDocument.Line line : lines) {
            out.add(truncate(line.label()));
            if (!line.attributes().isEmpty()) {
                out.addAll(wrapped(line.attributes()));
            }
            if (!line.ean().isEmpty()) {
                out.add(indented(line.ean()));
            }
            out.add(pair("  " + line.quantity() + " x " + line.unitIncludingTax()
                            + i18n.format("doc.htEntreParentheses", line.unitExcludingTax()),
                    line.totalIncludingTax()));
            // BO-10-02-19: the line total excluding tax the A4 page gives its
            // own column, on a line of its own under the tax-included one.
            out.add(pair("  " + i18n.get("doc.totalHt"), line.totalExcludingTax()));
        }
    }

    /**
     * Writes the VAT table.
     *
     * @param out  the lines being built
     * @param rows the VAT rows
     */
    private static void vat(List<String> out, List<InvoiceDocument.VatRow> rows,
            com.intermarche.pos.ui.Messages i18n) {
        out.add(pair(i18n.get("doc.tvaCourt"), amounts(i18n.get("doc.baseHt"),
                i18n.get("doc.tvaCourt"), i18n.get("doc.ttc"))));
        for (InvoiceDocument.VatRow row : rows) {
            // BO-03-03-04: the regime's code before its rate, as the receipt.
            String code = row.code() == null || row.code().isEmpty() ? "" : row.code() + " ";
            out.add(pair(INDENT + code + row.rate(),
                    amounts(row.excludingTax(), row.vat(), row.includingTax())));
        }
    }

    /**
     * Writes the settlement breakdown, nothing at all when the document states no
     * tender.
     *
     * @param out     the lines being built
     * @param tenders the settlement rows
     */
    private static void tenders(List<String> out, List<InvoiceDocument.Tender> tenders,
            com.intermarche.pos.ui.Messages i18n) {
        if (tenders.isEmpty()) {
            return;
        }
        out.add(RULE);
        out.add(i18n.get("doc.reglementMajuscule"));
        for (InvoiceDocument.Tender tender : tenders) {
            out.add(pair(INDENT + tender.label(), tender.amount() + " E"));
        }
    }

    /**
     * Lays out three amounts in fixed columns, so the figures sit under their
     * heading instead of drifting with the length of what is on the left.
     *
     * @param first  the first amount, or its heading
     * @param second the second amount, or its heading
     * @param third  the third amount, or its heading
     * @return the right-hand block, always the same width
     */
    private static String amounts(String first, String second, String third) {
        return String.format("%8s  %8s  %8s", first, second, third);
    }

    /**
     * Centres a text on the document's width.
     *
     * @param text the text to centre
     * @return the padded line, the text itself when it fills the width
     */
    private static String center(String text) {
        String value = truncate(text);
        if (value.length() >= WIDTH) {
            return value;
        }
        return " ".repeat((WIDTH - value.length()) / 2) + value;
    }

    /**
     * Places a label on the left and a value on the right of one line.
     *
     * @param left  the label
     * @param right the value
     * @return the formatted line
     */
    private static String pair(String left, String right) {
        // The left side is cut, never the value: a label that runs long is still
        // readable shortened, a truncated amount is a wrong amount.
        String label = truncate(left, WIDTH - right.length() - 1);
        return label + " ".repeat(Math.max(WIDTH - label.length() - right.length(), 1)) + right;
    }

    /**
     * Pushes a line in under its heading, cutting it to what is left of the paper.
     *
     * @param text the text to indent
     * @return the indented line, never wider than the paper
     */
    private static String indented(String text) {
        return INDENT + truncate(text, WIDTH - INDENT.length());
    }

    /**
     * Lays an attributes line out on as many indented lines as it needs, cut
     * between words: the attributes the shop prints on its invoices
     * ({@code BO-03-03-21}) must reach the paper whole, where a cut at the
     * width printed « ORIGINE : FR » for « ORIGINE : FRANCE ». A single word
     * wider than a line is still cut to the width.
     *
     * @param text the attributes line, never empty
     * @return the indented lines, none wider than the paper
     */
    private static List<String> wrapped(String text) {
        int room = WIDTH - INDENT.length();
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : text.split(" ")) {
            if (row.length() > 0 && row.length() + 1 + word.length() > room) {
                rows.add(indented(row.toString()));
                row.setLength(0);
            }
            if (row.length() > 0) {
                row.append(' ');
            }
            row.append(word);
        }
        rows.add(indented(row.toString()));
        return rows;
    }

    /**
     * Cuts a text to the document's width.
     *
     * @param text the text
     * @return the text, never longer than the width
     */
    private static String truncate(String text) {
        return truncate(text, WIDTH);
    }

    /**
     * Cuts a text to a given width.
     *
     * @param text  the text
     * @param width the width to fit, treated as zero when negative
     * @return the text, never longer than that width
     */
    private static String truncate(String text, int width) {
        int room = Math.max(width, 0);
        return text.length() <= room ? text : text.substring(0, room);
    }
}
