package com.intermarche.pos.domain.sale;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where a kind of document comes out ({@code LC-08-04-11}).
 *
 * <p>THE PAPER IS A PROPERTY OF THE DOCUMENT, NOT OF THE REGISTER. A store bills its
 * trade customers on A4 from the office printer and hands a delivery note over the
 * counter on the receipt roll, on the same till, in the same minute — so the target is
 * administered per KIND of document and not once for the machine. That is exactly what
 * the questionnaire's own example says: "Facture professionnel" and "Facture
 * professionnel A4" are two kinds precisely because they come out of two printers.
 *
 * <p>The three targets differ in what they cost the operator, which is why they are
 * three and not two: the roll prints unattended, the slip station stops between sheets
 * and waits for a hand ({@code LC-08-04-12}), and the network printer prints somewhere
 * else entirely and the operator has to go and fetch it.
 */
public enum DocumentOutput {

    /** The receipt roll: continuous paper, no hand needed, no page. */
    TICKET("Rouleau de caisse"),

    /** The slip station: one sheet at a time, inserted by the operator. */
    FACTURETTE("Facturette à insertion"),

    /** A network printer: an A4 page, printed away from the till. */
    A4("Imprimante réseau A4");

    /** The target as the back office and the screen name it. */
    private final String label;

    /**
     * Builds a target.
     *
     * @param label the operator-facing name
     */
    DocumentOutput(String label) {
        this.label = label;
    }

    /**
     * Returns the target as the back office and the screen name it.
     *
     * @return the operator-facing name
     */
    public String getLabel() {
        return label;
    }

    /**
     * Tells whether this target needs the operator to feed it a sheet at a time
     * ({@code LC-08-04-12}).
     *
     * @return true for the slip station, false for the roll and the network printer
     */
    public boolean needsInsertion() {
        return this == FACTURETTE;
    }

    /**
     * Returns the target carrying that name, tolerating case and padding.
     *
     * @param name the enum name as administered, possibly null
     * @return the target, or null when no target carries that name
     */
    public static DocumentOutput byName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT);
        for (DocumentOutput candidate : values()) {
            if (candidate.name().equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reads the administered mapping of document kinds to printers
     * ({@code LC-08-04-11}).
     *
     * <p>The syntax is the register's usual semicolon list, each entry pairing a kind
     * and a target with a colon: {@code FACTURE:A4;BON_LIVRAISON:TICKET}. An entry
     * naming a kind or a target this version does not know is dropped, and so is one
     * that is not a pair at all.
     *
     * <p>WHAT IS NOT NAMED COMES OUT ON THE ROLL. The roll is the one printer a
     * register always has: a kind left out of the parameter — or a parameter a store
     * mistyped — must still produce its document somewhere rather than nowhere.
     *
     * @param administered the administered list, possibly null or blank
     * @return the target of each named kind; kinds absent from it are absent from the map
     */
    public static Map<DocumentType, DocumentOutput> administered(String administered) {
        Map<DocumentType, DocumentOutput> targets = new EnumMap<>(DocumentType.class);
        if (administered == null || administered.isBlank()) {
            return targets;
        }
        for (String part : administered.split(";")) {
            String[] pair = part.split(":");
            if (pair.length != 2) {
                continue;
            }
            DocumentType type = DocumentType.byName(pair[0]);
            DocumentOutput target = byName(pair[1]);
            if (type != null && target != null) {
                targets.put(type, target);
            }
        }
        return targets;
    }
}
