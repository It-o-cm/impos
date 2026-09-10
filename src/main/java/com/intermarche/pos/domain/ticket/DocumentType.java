package com.intermarche.pos.domain.ticket;

/**
 * A kind of commercial document the register issues on a closed ticket.
 * <p>
 * The questionnaire never describes the delivery note on its own: it always writes
 * "Facture/Bon de livraison", and {@code BO-03-03-02} puts the document type INSIDE
 * the printed template ("Type de document : facture/bon de livraison /...., issu du
 * nom du document") next to "N° de séquence : unique, séquentiel et sans rupture par
 * type de document". A document kind is therefore a name, a template and a sequence
 * of its own — nothing more — and the list is open, which is why the register offers
 * what the back office activated rather than knowing any type by heart.
 * <p>
 * The Portuguese documents (FS, FT, NC, RC) are the same shape and will land here
 * when that market enters the perimeter; giving the sequence a per-type prefix from
 * the start is what avoids numbering them a second way.
 */
public enum DocumentType {

    /** An invoice: the sale is billed to a professional customer. */
    FACTURE("F", "FACTURE"),

    /** A delivery note: the goods leave, the billing follows its own course. */
    BON_LIVRAISON("L", "BON DE LIVRAISON");

    /** The letter that separates this type's sequence from the others. */
    private final String prefix;

    /** The title printed at the top of the document. */
    private final String title;

    /**
     * Builds a document type.
     *
     * @param prefix the letter of its number sequence
     * @param title  the title printed on the document
     */
    DocumentType(String prefix, String title) {
        this.prefix = prefix;
        this.title = title;
    }

    /**
     * Returns the letter that separates this type's sequence from the others.
     *
     * @return the sequence prefix
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Returns the title printed at the top of the document.
     *
     * @return the printed title
     */
    public String getTitle() {
        return title;
    }
}
