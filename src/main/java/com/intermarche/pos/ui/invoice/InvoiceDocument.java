package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.Address;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.Invoice;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VatBreakdown;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A commercial document, laid out and ready to be shown — the ONE truth of what the
 * document says.
 *
 * <p>THE MODEL IS THE TRUTH, NOT ITS OUTPUT. A real Intermarché invoice is a
 * structured page: framed blocks for the seller and the addressee, a bordered table
 * whose article rows carry their origin and category underneath, a VAT table, a
 * settlement breakdown by tender, a legal footer. Its natural rendering is A4 — on
 * screen and on paper — and the register's 42-column roll is a DEGRADED rendering of
 * the same thing, not the reference. Both read this object, so there is one layout
 * decision and not two that drift apart at the third correction.
 *
 * <p>Every field is already FORMATTED. Amounts, quantities, rates and dates are
 * strings by the time they land here, computed once by {@link #of}: a renderer that
 * formats is a renderer that can disagree with its sibling, and the French decimal
 * comma is exactly the kind of detail that ends up applied in one place and forgotten
 * in the other.
 *
 * <p>Pure: built from entities that are passed in, reading no database and injecting
 * nothing, so the whole document can be asserted in a unit test.
 */
public final class InvoiceDocument {

    /** French amount format, as on the receipt. */
    private static final DecimalFormat DF = amountFormat();

    /**
     * Builds the amount format of a commercial document: French decimals, and
     * thousands grouped by a PLAIN SPACE.
     *
     * <p>Grouped because a document states amounts a person reads and checks, where
     * the receipt states a running total; and a plain space rather than the locale's
     * narrow no-break one because the same strings go to a receipt printer whose code
     * page has no such character and would print a question mark on the paper.
     *
     * @return the format
     */
    private static DecimalFormat amountFormat() {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.FRENCH);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", symbols);
    }

    /** How a document dates itself. */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** How a document prints a plain date. */
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** How many decimals a tax-excluded unit price is printed with ({@code BO-10-02-19}). */
    private static final int EXCLUDING_TAX_SCALE = 2;

    /**
     * One side of the document: who sells, or who is billed.
     *
     * @param name          the trade or business name, the block's first line
     * @param legalName     the legal entity when it differs from the name, else empty
     * @param contact       the contact person under the name, else empty
     * @param addressLines  the address, one entry per line, already assembled
     * @param phone         the telephone number, empty when there is none
     * @param fax           the fax number, empty when there is none
     * @param siret         the SIRET, empty when there is none
     * @param vatNumber     the intra-community VAT number, empty when there is none
     * @param accountNumber the customer's account number, empty on the seller's side
     */
    public record Party(String name, String legalName, String contact, List<String> addressLines,
            String phone, String fax, String siret, String vatNumber, String accountNumber) {
    }

    /**
     * One article row of the document.
     *
     * @param label              the article's label
     * @param attributes         the origin, category and variety line shown under the
     *                           label, empty when the article carries none
     * @param ean                the article code, empty when it is not printed
     * @param quantity           the quantity, with its unit for a weighed article
     * @param vatRate            the VAT rate as a percentage
     * @param unitExcludingTax   the unit price excluding tax
     * @param unitIncludingTax   the unit price including tax
     * @param totalIncludingTax  the line total including tax
     */
    public record Line(String label, String attributes, String ean, String quantity,
            String vatRate, String unitExcludingTax, String unitIncludingTax,
            String totalIncludingTax) {
    }

    /**
     * One row of the VAT table.
     *
     * @param rate             the rate as a percentage
     * @param excludingTax     the taxable base
     * @param vat              the tax
     * @param includingTax     the tax-included total
     */
    public record VatRow(String rate, String excludingTax, String vat, String includingTax) {
    }

    /**
     * One tender of the settlement breakdown.
     *
     * @param label  the tender's name, as the customer reads it
     * @param amount the amount settled by it
     */
    public record Tender(String label, String amount) {
    }

    /** The document's title, e.g. {@code FACTURE}. */
    public final String title;

    /** Its number. */
    public final String number;

    /** When it was issued. */
    public final String issueDate;

    /** The number of the ticket it states. */
    public final String ticketNumber;

    /** The date of the sale it states. */
    public final String saleDate;

    /** The register that issued it. */
    public final String terminal;

    /** The duplicate number, zero on the original. */
    public final int duplicateNumber;

    /** Who sells. */
    public final Party seller;

    /** Who is billed. */
    public final Party customer;

    /** The article rows. */
    public final List<Line> lines;

    /** The VAT table. */
    public final List<VatRow> vatRows;

    /** The settlement breakdown, one row per tender used. */
    public final List<Tender> tenders;

    /** The tax-excluded total. */
    public final String totalExcludingTax;

    /** The VAT total. */
    public final String totalVat;

    /** The tax-included total. */
    public final String totalIncludingTax;

    /** The tenders, joined, for the metadata block. */
    public final String paymentMethods;

    /** The legal identity of the operating company, for the page footer. */
    public final String legalFooter;

    /**
     * The late-payment mentions a professional invoice must carry.
     *
     * <p>Articles L441-10 and D441-5 of the French commercial code: a penalty at the
     * ECB refinancing rate plus ten points, and a flat 40 euro recovery indemnity.
     * They are absent from a consumer receipt and mandatory here, which is one of
     * the ways an invoice is not a bigger ticket. Frozen in code with the rest of
     * the layout until the administered footers of {@code BO-03-05-04} exist.
     */
    public static final String LEGAL_MENTIONS =
            "En cas de retard de paiement, une pénalité au taux d'intérêt appliqué par la "
            + "Banque centrale européenne à son opération de refinancement la plus récente "
            + "majoré de 10 points de pourcentage est exigible, ainsi qu'une indemnité "
            + "forfaitaire pour frais de recouvrement de 40 € (articles L441-10 et D441-5 du "
            + "code de commerce). Pas d'escompte pour paiement anticipé.";

    /**
     * Builds a laid-out document.
     *
     * @param title             the document's title
     * @param number            its number
     * @param issueDate         when it was issued
     * @param ticketNumber      the ticket it states
     * @param saleDate          the date of that sale
     * @param terminal          the register that issued it
     * @param duplicateNumber   the duplicate number, zero on the original
     * @param seller            who sells
     * @param customer          who is billed
     * @param lines             the article rows
     * @param vatRows           the VAT table
     * @param tenders           the settlement breakdown
     * @param totalExcludingTax the tax-excluded total
     * @param totalVat          the VAT total
     * @param totalIncludingTax the tax-included total
     * @param paymentMethods    the tenders, joined, for the metadata block
     * @param legalFooter       the legal identity of the operating company
     */
    private InvoiceDocument(String title, String number, String issueDate, String ticketNumber,
            String saleDate, String terminal, int duplicateNumber, Party seller, Party customer,
            List<Line> lines, List<VatRow> vatRows, List<Tender> tenders,
            String totalExcludingTax, String totalVat, String totalIncludingTax,
            String paymentMethods, String legalFooter) {
        this.title = title;
        this.number = number;
        this.issueDate = issueDate;
        this.ticketNumber = ticketNumber;
        this.saleDate = saleDate;
        this.terminal = terminal;
        this.duplicateNumber = duplicateNumber;
        this.seller = seller;
        this.customer = customer;
        this.lines = lines;
        this.vatRows = vatRows;
        this.tenders = tenders;
        this.totalExcludingTax = totalExcludingTax;
        this.totalVat = totalVat;
        this.totalIncludingTax = totalIncludingTax;
        this.paymentMethods = paymentMethods;
        this.legalFooter = legalFooter;
    }

    /**
     * Lays out a document from what it states.
     *
     * @param invoice the document, carrying its number, its addressee and its frozen totals
     * @param ticket  the closed ticket it states
     * @param showEan whether the article codes are printed ({@code BO-03-03-03})
     * @return the laid-out document
     */
    public static InvoiceDocument of(Invoice invoice, Ticket ticket, boolean showEan) {
        List<Tender> tenders = tendersOf(ticket);
        return new InvoiceDocument(
                invoice.documentType.getTitle(),
                invoice.documentNumber,
                invoice.issueDate.format(DATE_TIME),
                invoice.ticketNumber,
                ticket.creationDate == null ? "" : ticket.creationDate.format(DATE),
                invoice.terminalId,
                invoice.printCount,
                sellerOf(ticket.store),
                customerOf(invoice),
                linesOf(ticket, showEan),
                vatRowsOf(ticket),
                tenders,
                DF.format(invoice.totalExcludingTax),
                DF.format(invoice.totalVat),
                DF.format(invoice.totalIncludingTax),
                joinTenders(tenders),
                legalFooterOf(ticket.store));
    }

    /**
     * Joins the tenders used, for the metadata block that names the payment means.
     *
     * @param tenders the settlement rows
     * @return their labels joined, empty when the document states no tender
     */
    private static String joinTenders(List<Tender> tenders) {
        List<String> labels = new ArrayList<>();
        for (Tender tender : tenders) {
            labels.add(tender.label());
        }
        return String.join(", ", labels);
    }

    /**
     * Assembles the legal identity printed at the foot of the page: the operating
     * company, its register entry and its share capital.
     *
     * @param store the store that sold
     * @return the footer line, empty when the store declares none of the three
     */
    private static String legalFooterOf(Store store) {
        List<String> parts = new ArrayList<>();
        if (isFilled(store.legalName)) {
            parts.add(store.legalName.trim());
        }
        if (isFilled(store.rcs)) {
            parts.add("RCS " + store.rcs.trim());
        }
        if (store.shareCapital != null) {
            parts.add("Capital : " + DF.format(store.shareCapital) + " €");
        }
        return String.join(" — ", parts);
    }

    /**
     * The late-payment mentions, as an instance property so a template can read them
     * like any other field of the document.
     *
     * @return the mentions
     */
    public String getLegalMentions() {
        return LEGAL_MENTIONS;
    }

    /**
     * The store logo as a data URI, for a page-rendered document.
     *
     * @return the data URI, empty when this till has no logo
     */
    public String getLogo() {
        return com.intermarche.pos.ui.hardware.ReceiptLogo.dataUri();
    }

    /**
     * Tells whether this document is a duplicate rather than the original.
     *
     * @return true once it has been printed at least once
     */
    public boolean isDuplicate() {
        return duplicateNumber >= 1;
    }

    /**
     * Lays out the seller's block.
     *
     * @param store the store that sold
     * @return the seller's party
     */
    private static Party sellerOf(Store store) {
        return new Party(text(store.name), text(store.legalName), "", addressLines(store.address),
                text(store.phone), text(store.fax), text(store.siret), text(store.vatNumber), "");
    }

    /**
     * Lays out the addressee's block, from what the document froze at issue.
     *
     * @param invoice the document
     * @return the customer's party
     */
    private static Party customerOf(Invoice invoice) {
        return new Party(text(invoice.customerName), "", text(invoice.customerContact),
                addressLines(invoice.customerAddress), "", "", text(invoice.customerSiret),
                text(invoice.customerVatNumber), text(invoice.customerAccountNumber));
    }

    /**
     * Lays out the article rows, excluding the cancelled articles exactly as the
     * totals and the VAT ventilation do.
     *
     * @param ticket  the closed ticket
     * @param showEan whether the article codes are printed
     * @return the rows
     */
    private static List<Line> linesOf(Ticket ticket, boolean showEan) {
        List<Line> rows = new ArrayList<>();
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            BigDecimal rate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
            rows.add(new Line(
                    text(line.productLabel),
                    attributesOf(line),
                    showEan ? text(line.ean) : "",
                    quantityOf(line),
                    percent(rate),
                    DF.format(excludingTax(line.unitPrice, rate)),
                    DF.format(orZero(line.unitPrice)),
                    DF.format(orZero(line.totalPrice))));
        }
        return rows;
    }

    /**
     * Assembles the attributes line shown under an article's label.
     *
     * <p>A real invoice carries "Origine : FRANCE, Categorie : 1, Variete : RONDE EN
     * GRAPPE" there. Only the family is available on the ticket line today; the
     * article attributes of {@code BO-03-04-04} are held by the product referential
     * and are not snapshotted on the sold line, so they cannot be stated by a
     * document issued after the sale without reading the referential back.
     *
     * @param line the ticket line
     * @return the attributes line, empty when the line carries none
     */
    private static String attributesOf(TicketLine line) {
        return isFilled(line.familyLabel) ? "Famille : " + line.familyLabel.trim() : "";
    }

    /**
     * Lays out the VAT table, by the same rule as the receipt — which is what makes
     * the two documents agree, as {@code BO-03-03-04} requires.
     *
     * @param ticket the closed ticket
     * @return the rows, by ascending rate
     */
    private static List<VatRow> vatRowsOf(Ticket ticket) {
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            breakdown.add(line.vatRate, line.totalPrice);
        }
        List<VatRow> rows = new ArrayList<>();
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            // percent() and not the bucket's own getRateFormatted(): the receipt writes
            // "5,50%" and a commercial document writes "5,50 %", and the rate must read
            // the same in the article column and in the VAT table of the SAME page.
            rows.add(new VatRow(percent(bucket.rate),
                    DF.format(bucket.totalExcludingTax),
                    DF.format(bucket.vatAmount),
                    DF.format(bucket.totalIncludingTax)));
        }
        return rows;
    }

    /**
     * Lays out the settlement breakdown, one row per tender actually used, summed
     * when the same tender settled several times.
     *
     * @param ticket the closed ticket
     * @return the rows, in the order the tenders first appear
     */
    private static List<Tender> tendersOf(Ticket ticket) {
        Map<String, BigDecimal> byTender = new LinkedHashMap<>();
        for (TicketPayment payment : ticket.payments) {
            byTender.merge(tenderLabel(payment), orZero(payment.amount), BigDecimal::add);
        }
        List<Tender> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : byTender.entrySet()) {
            rows.add(new Tender(entry.getKey(), DF.format(entry.getValue())));
        }
        return rows;
    }

    /**
     * Names a tender as the customer reads it.
     *
     * @param payment the settled payment
     * @return its French label, the bare type when it has no known name
     */
    private static String tenderLabel(TicketPayment payment) {
        String type = payment.getClass().getSimpleName().replace("Payment", "");
        return switch (type) {
            case "Cash" -> "Espèces";
            case "Card" -> "Carte bancaire";
            case "Cheque" -> "Chèque";
            case "TicketResto" -> "Titre-restaurant";
            case "Voucher" -> "Bon d'achat";
            case "Fidelity" -> "Cagnotte fidélité";
            default -> type;
        };
    }

    /**
     * Assembles the non-blank parts of an address, one per line.
     *
     * @param address the address, possibly null
     * @return the lines, empty when there is no address
     */
    private static List<String> addressLines(Address address) {
        List<String> out = new ArrayList<>();
        if (address == null) {
            return out;
        }
        if (isFilled(address.streetLine1)) {
            out.add(address.streetLine1.trim());
        }
        if (isFilled(address.streetLine2)) {
            out.add(address.streetLine2.trim());
        }
        String town = join(address.postalCode, address.city);
        if (!town.isEmpty()) {
            out.add(town);
        }
        return out;
    }

    /**
     * Joins a postal code and a town, ignoring the blank ones.
     *
     * @param postalCode the postal code, possibly blank
     * @param city       the town, possibly blank
     * @return the joined line, empty when both are blank
     */
    private static String join(String postalCode, String city) {
        String code = isFilled(postalCode) ? postalCode.trim() : "";
        String town = isFilled(city) ? city.trim() : "";
        if (code.isEmpty()) {
            return town;
        }
        if (town.isEmpty()) {
            return code;
        }
        return code + " " + town;
    }

    /**
     * Formats a line's quantity: kilograms for a weighed article, a plain count
     * otherwise.
     *
     * @param line the ticket line
     * @return the formatted quantity
     */
    private static String quantityOf(TicketLine line) {
        BigDecimal value = orZero(line.quantity);
        if (isFilled(line.plu)) {
            return String.format("%.3f kg", value).replace(".", ",");
        }
        if (value.stripTrailingZeros().scale() <= 0) {
            return value.stripTrailingZeros().toPlainString();
        }
        return String.format("%.2f", value).replace(".", ",");
    }

    /**
     * Removes the tax from a tax-included amount.
     *
     * @param includingTax the tax-included amount, null treated as zero
     * @param rate         the VAT rate as a fraction, e.g. 0.20
     * @return the tax-excluded amount
     */
    private static BigDecimal excludingTax(BigDecimal includingTax, BigDecimal rate) {
        return orZero(includingTax)
                .divide(BigDecimal.ONE.add(rate), EXCLUDING_TAX_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Formats a VAT rate as a percentage.
     *
     * @param rate the rate as a fraction
     * @return the rate as a percentage, French decimal
     */
    private static String percent(BigDecimal rate) {
        return String.format("%.2f %%", rate.multiply(BigDecimal.valueOf(100))).replace(".", ",");
    }

    /**
     * Replaces a missing amount by zero.
     *
     * @param amount the amount, possibly null
     * @return the amount, or zero
     */
    private static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /**
     * Replaces a missing text by an empty one, so no renderer has to guard.
     *
     * @param value the text, possibly null
     * @return the text, or an empty string
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Tells whether a text carries something.
     *
     * @param value the text to check
     * @return true when it is neither null nor blank
     */
    private static boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }
}
