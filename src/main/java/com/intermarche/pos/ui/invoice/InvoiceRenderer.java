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
    public static List<String> render(InvoiceDocument document) {
        List<String> out = new ArrayList<>();
        seller(out, document.seller);
        out.add(RULE);
        out.add(center(document.title));
        if (document.isDuplicate()) {
            out.add(center("*** DUPLICATA N°" + document.duplicateNumber + " ***"));
        }
        out.add(RULE);
        out.add(pair("N° " + document.title, document.number));
        out.add(pair("Date", document.issueDate));
        // BO-02-04-08: l'échéance figure obligatoirement sur le document, et
        // seulement quand le client en porte une — une ligne « Échéance » vide
        // se lirait comme un paiement immédiat.
        if (document.hasDueDate()) {
            out.add(pair("Echeance", document.dueDate));
        }
        out.add(pair("Ticket", document.ticketNumber));
        out.add(RULE);
        customer(out, document.customer, document.hasCustomerTaxId() ? document.customerTaxId : "");
        out.add(RULE);
        articles(out, document.lines);
        out.add(RULE);
        vat(out, document.vatRows);
        out.add(RULE);
        out.add(pair("TOTAL HT", document.totalExcludingTax + " E"));
        out.add(pair("TOTAL TVA", document.totalVat + " E"));
        out.add(pair("TOTAL TTC", document.totalIncludingTax + " E"));
        tenders(out, document.tenders);
        return out;
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
    private static void seller(List<String> out, InvoiceDocument.Party seller) {
        out.add(center("INTERMARCHE"));
        out.add(center(seller.name()));
        if (!seller.legalName().isEmpty()) {
            out.add(center(seller.legalName()));
        }
        for (String addressLine : seller.addressLines()) {
            out.add(center(addressLine));
        }
        if (!seller.phone().isEmpty()) {
            out.add(center("Tel. " + seller.phone()));
        }
        if (!seller.siret().isEmpty()) {
            out.add(center("SIRET " + seller.siret()));
        }
        if (!seller.vatNumber().isEmpty()) {
            out.add(center("TVA " + seller.vatNumber()));
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
            String taxId) {
        out.add("CLIENT");
        if (!customer.accountNumber().isEmpty()) {
            out.add(indented("N° compte : " + customer.accountNumber()));
        }
        out.add(indented(customer.name()));
        if (!customer.contact().isEmpty()) {
            out.add(indented(customer.contact()));
        }
        for (String addressLine : customer.addressLines()) {
            out.add(indented(addressLine));
        }
        if (!customer.siret().isEmpty()) {
            out.add(indented("SIRET " + customer.siret()));
        }
        if (!customer.vatNumber().isEmpty()) {
            out.add(indented("TVA " + customer.vatNumber()));
        }
        if (!taxId.isEmpty()) {
            out.add(indented("NIF " + taxId));
        }
    }

    /**
     * Writes the article rows.
     *
     * <p>The A4 page gives each of the unit price excluding tax, the unit price
     * including tax and the line total its own column. Forty-two characters cannot,
     * so the tax-excluded unit price rides in parentheses beside the tax-included
     * one and the rate moves to the VAT table, where it is stated anyway.
     *
     * @param out   the lines being built
     * @param lines the article rows
     */
    private static void articles(List<String> out, List<InvoiceDocument.Line> lines) {
        for (InvoiceDocument.Line line : lines) {
            out.add(truncate(line.label()));
            if (!line.attributes().isEmpty()) {
                out.add(indented(line.attributes()));
            }
            if (!line.ean().isEmpty()) {
                out.add(indented(line.ean()));
            }
            out.add(pair("  " + line.quantity() + " x " + line.unitIncludingTax()
                            + " (HT " + line.unitExcludingTax() + ")",
                    line.totalIncludingTax()));
        }
    }

    /**
     * Writes the VAT table.
     *
     * @param out  the lines being built
     * @param rows the VAT rows
     */
    private static void vat(List<String> out, List<InvoiceDocument.VatRow> rows) {
        out.add(pair("TVA", amounts("Base HT", "TVA", "TTC")));
        for (InvoiceDocument.VatRow row : rows) {
            out.add(pair(INDENT + row.rate(),
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
    private static void tenders(List<String> out, List<InvoiceDocument.Tender> tenders) {
        if (tenders.isEmpty()) {
            return;
        }
        out.add(RULE);
        out.add("REGLEMENT");
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
