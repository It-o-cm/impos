package com.intermarche.pos.domain.barcode.gs1;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One GS1 Application Identifier: what its digits mean, and — the part that actually
 * decides whether a payload can be read at all — how long its data is.
 *
 * <p>THE WHOLE DIFFICULTY OF GS1 IS DELIMITING, not naming. A GS1 payload is a run of
 * digits with no punctuation: {@code 010952600013436710ABC123}. Splitting it back into
 * elements is possible only because SOME identifiers have a length fixed by the
 * standard, and the others are closed by a separator character (FNC1, transmitted as
 * {@code GS}, 0x1D). Get one length wrong and every element after it is garbage — which
 * is why this table exists and why it carries the predefined-length families of the GS1
 * General Specifications and not only the identifiers this register has a rule for.
 *
 * <p>The predefined-length families are keyed on the FIRST TWO DIGITS: 00, 01, 02, 03,
 * 04, 11 to 20, 31 to 36 and 41 (whose identifiers are the three-digit 410 to 417).
 * Everything else is variable-length and closed by a
 * separator or by the end of the payload. An identifier this version does not know is
 * therefore still readable when it is separated — which is exactly what
 * {@code LC-11-03-03} requires of an unknown AI.
 *
 * <p>Some identifiers end in a DECIMAL-PLACE DIGIT: {@code 3103} is a net weight in
 * kilograms with three decimals, {@code 3922} an amount with two. The stem and that
 * digit are told apart here, so a caller reads a number and not a string it would have
 * to know how to shift itself.
 *
 * @param code       the identifier's digits, the decimal-place digit excluded
 * @param label      what it means, as the technical journal writes it
 * @param dataLength the data's length in characters, or zero when it is variable
 * @param maxLength  the longest data the standard allows, for a variable one
 * @param decimal    whether one more digit follows {@code code} and gives the number of
 *                   decimal places of the value
 */
public record Gs1ApplicationIdentifier(String code, String label, int dataLength,
                                       int maxLength, boolean decimal) {

    /** Article code — the identifier every sale line is built on ({@code LC-11-03-04}). */
    public static final String GTIN = "01";

    /** Batch or lot number ({@code LC-11-03-07}). */
    public static final String BATCH = "10";

    /** Production date ({@code LC-11-03-08}). */
    public static final String PRODUCTION_DATE = "11";

    /** Packaging date ({@code LC-11-03-09}). */
    public static final String PACKAGING_DATE = "13";

    /** Best-before date ({@code LC-11-03-10}). */
    public static final String BEST_BEFORE = "15";

    /** Sell-by date ({@code LC-11-03-11}). */
    public static final String SELL_BY = "16";

    /** Expiry date of an article or a coupon ({@code LC-11-03-12/13}). */
    public static final String EXPIRY = "17";

    /** Serial number ({@code LC-11-03-14}). */
    public static final String SERIAL = "21";

    /** Consumer product variant ({@code LC-11-03-15}). */
    public static final String VARIANT = "22";

    /** Variable count of items — a quantity ({@code LC-11-03-16}). */
    public static final String COUNT = "30";

    /** Net weight in kilograms, decimal ({@code LC-11-03-20}). */
    public static final String NET_WEIGHT_KG = "310";

    /** Amount payable or coupon value, decimal ({@code LC-11-03-21}). */
    public static final String AMOUNT_PAYABLE = "390";

    /** Amount payable for a variable measure item, decimal ({@code LC-11-03-22}). */
    public static final String AMOUNT_VARIABLE_MEASURE = "392";

    /** Country of origin ({@code LC-11-03-19}). */
    public static final String ORIGIN = "422";

    /** Gift document — a gift cheque ({@code LC-11-03-05}). */
    public static final String GDTI = "253";

    /** Coupon ({@code LC-11-03-06}). */
    public static final String GCN = "255";

    /** Price per unit of measure ({@code LC-11-03-23}). */
    public static final String PRICE_PER_UNIT = "8005";

    /** Information mutually agreed between trading partners ({@code LC-11-03-17}). */
    public static final String PARTNER_INFO = "90";

    /** Company internal information ({@code LC-11-03-18}). */
    public static final String COMPANY_INFO = "91";

    /** The longest identifier the standard defines, in digits. */
    public static final int MAX_CODE_LENGTH = 4;

    /**
     * The identifiers this version knows, longest code first so a lookup that walks
     * the map takes the most specific match.
     */
    private static final Map<String, Gs1ApplicationIdentifier> KNOWN = known();

    /**
     * Builds the table of known identifiers.
     *
     * <p>It carries two populations. The ones the register has a RULE for — the article,
     * the gift document, the coupon, the dates, the quantities and the amounts — and the
     * ones it only needs in order to DELIMIT what follows them, which is every remaining
     * predefined-length family. Dropping the second population would make the first
     * unreadable the moment a supplier encodes an identifier this register ignores.
     *
     * @return the table, keyed by identifier code
     */
    private static Map<String, Gs1ApplicationIdentifier> known() {
        Map<String, Gs1ApplicationIdentifier> table = new LinkedHashMap<>();
        // --- Predefined-length families (GS1 General Specifications, fig. 7.8.6-2).
        //     Their data length is fixed, so they need no separator after them.
        put(table, "00", "SSCC", 18);
        put(table, GTIN, "GTIN (code article)", 14);
        put(table, "02", "GTIN des articles contenus", 14);
        put(table, "03", "GTIN fabriqué sur commande", 14);
        put(table, "04", "Identification cellule", 16);
        put(table, PRODUCTION_DATE, "Date de production", 6);
        put(table, "12", "Date d'échéance", 6);
        put(table, PACKAGING_DATE, "Date d'emballage", 6);
        put(table, "14", "Date de durabilité", 6);
        put(table, BEST_BEFORE, "Date limite de consommation", 6);
        put(table, SELL_BY, "Date limite de vente", 6);
        put(table, EXPIRY, "Date de péremption/expiration", 6);
        put(table, "18", "Date de conditionnement", 6);
        put(table, "19", "Date de durabilité maximale", 6);
        put(table, "20", "Variante interne", 2);
        // The 410 to 417 location numbers: the predefined-length family 41 is keyed on
        // its first two digits, but the identifier itself has three, so the thirteen
        // digits of the location start after the third one.
        put(table, "410", "GLN destinataire", 13);
        put(table, "411", "GLN facturé", 13);
        put(table, "412", "GLN fournisseur", 13);
        put(table, "413", "GLN destinataire final", 13);
        put(table, "414", "GLN localisation physique", 13);
        put(table, "415", "GLN facturant", 13);
        put(table, "416", "GLN lieu de production", 13);
        put(table, "417", "GLN partie", 13);
        // The 31nn to 36nn measure families: four-digit codes whose last digit is the
        // number of decimals, six digits of data, no separator.
        for (String stem : new String[] {"310", "311", "312", "313", "314", "315", "316",
                "320", "321", "322", "323", "324", "325", "326", "327", "328", "329",
                "330", "331", "332", "333", "334", "335", "336", "337",
                "340", "341", "342", "343", "344", "345", "346", "347", "348", "349",
                "350", "351", "352", "353", "354", "355", "356", "357",
                "360", "361", "362", "363", "364", "365", "366", "367", "368", "369"}) {
            table.put(stem, new Gs1ApplicationIdentifier(stem, measureLabel(stem), 6, 6, true));
        }
        // --- Variable-length identifiers, closed by a separator or the end of payload.
        //     Every one of them is listed under its TRUE code: an identifier missing from
        //     this table is read on its first two digits only, so a 240 would be taken
        //     for a 24 and an alert administered on 240 would never fire.
        putVariable(table, BATCH, "Numéro de lot", 20, false);
        putVariable(table, SERIAL, "Numéro de série", 20, false);
        putVariable(table, VARIANT, "Variante du produit", 20, false);
        putVariable(table, "235", "Numéro de série tiers", 28, false);
        putVariable(table, "240", "Identification complémentaire du produit", 30, false);
        putVariable(table, "241", "Référence article client", 30, false);
        putVariable(table, "242", "Variante sur commande", 6, false);
        putVariable(table, "243", "Code composant d'emballage", 20, false);
        putVariable(table, "250", "Numéro de série secondaire", 30, false);
        putVariable(table, "251", "Référence à l'entité source", 30, false);
        putVariable(table, GDTI, "GDTI (chèque cadeau)", 30, false);
        putVariable(table, "254", "Extension de GLN", 20, false);
        putVariable(table, GCN, "GCN (coupon)", 25, false);
        putVariable(table, COUNT, "Quantité", 8, false);
        putVariable(table, "37", "Nombre d'unités contenues", 8, false);
        putVariable(table, AMOUNT_PAYABLE, "Montant à payer / valeur du coupon", 15, true);
        putVariable(table, "391", "Montant à payer avec code devise", 18, true);
        putVariable(table, AMOUNT_VARIABLE_MEASURE, "Montant à payer (unité variable)", 15, true);
        putVariable(table, "393", "Montant unité variable avec code devise", 18, true);
        putVariable(table, "394", "Pourcentage de remise du coupon", 4, true);
        putVariable(table, "395", "Montant par unité de mesure", 6, true);
        putVariable(table, "400", "Numéro de commande client", 30, false);
        putVariable(table, "401", "Numéro d'envoi (GINC)", 30, false);
        putVariable(table, "402", "Numéro d'expédition (GSIN)", 17, false);
        putVariable(table, "403", "Code de routage", 30, false);
        putVariable(table, "420", "Code postal de livraison", 20, false);
        putVariable(table, "421", "Code postal de livraison avec pays", 12, false);
        putVariable(table, ORIGIN, "Pays d'origine", 3, false);
        putVariable(table, "423", "Pays de première transformation", 15, false);
        putVariable(table, "424", "Pays de transformation", 3, false);
        putVariable(table, "425", "Pays de découpe", 15, false);
        putVariable(table, "426", "Pays de transformation complète", 3, false);
        putVariable(table, "427", "Subdivision du pays d'origine", 3, false);
        putVariable(table, "7001", "Numéro de stock OTAN", 13, false);
        putVariable(table, "7002", "Classification carcasse et découpe", 30, false);
        putVariable(table, "7003", "Date et heure d'expiration", 10, false);
        putVariable(table, "7004", "Puissance active", 4, false);
        putVariable(table, "7005", "Zone de capture", 12, false);
        putVariable(table, "7006", "Date de première congélation", 6, false);
        putVariable(table, "7007", "Date de récolte", 12, false);
        putVariable(table, "7008", "Espèce aquatique", 3, false);
        putVariable(table, "7009", "Engin de pêche", 10, false);
        putVariable(table, "7010", "Méthode de production", 2, false);
        putVariable(table, "8001", "Dimensions des rouleaux", 14, false);
        putVariable(table, "8002", "Numéro de téléphone mobile", 20, false);
        putVariable(table, "8003", "GRAI (actif consigné)", 30, false);
        putVariable(table, "8004", "GIAI (actif individuel)", 30, false);
        putVariable(table, PRICE_PER_UNIT, "Prix par unité de mesure", 6, false);
        putVariable(table, "8006", "Composant d'article (ITIP)", 18, false);
        putVariable(table, "8007", "IBAN", 34, false);
        putVariable(table, "8008", "Date et heure de production", 12, false);
        putVariable(table, "8012", "Version du logiciel", 20, false);
        putVariable(table, "8017", "GSRN prestataire", 18, false);
        putVariable(table, "8018", "GSRN bénéficiaire", 18, false);
        putVariable(table, "8019", "Numéro d'instance de service", 10, false);
        putVariable(table, "8020", "Référence du bordereau de paiement", 25, false);
        putVariable(table, "8110", "Code coupon (Amérique du Nord)", 70, false);
        putVariable(table, "8111", "Points de fidélité du coupon", 4, false);
        putVariable(table, "8112", "Coupon dématérialisé (Amérique du Nord)", 70, false);
        putVariable(table, "8200", "URL du produit", 70, false);
        putVariable(table, PARTNER_INFO, "Information entre partenaires", 30, false);
        putVariable(table, COMPANY_INFO, "Information interne entreprise", 90, false);
        return table;
    }

    /**
     * Names a measure identifier of the 31nn-36nn families.
     *
     * @param stem the identifier's first three digits
     * @return the label written in the technical journal
     */
    private static String measureLabel(String stem) {
        return NET_WEIGHT_KG.equals(stem) ? "Poids net (kg)" : "Mesure " + stem;
    }

    /**
     * Registers a predefined-length identifier.
     *
     * @param table  the table being built
     * @param code   the identifier's digits
     * @param label  what it means
     * @param length its fixed data length
     */
    private static void put(Map<String, Gs1ApplicationIdentifier> table, String code,
            String label, int length) {
        table.put(code, new Gs1ApplicationIdentifier(code, label, length, length, false));
    }

    /**
     * Registers a variable-length identifier.
     *
     * @param table   the table being built
     * @param code    the identifier's digits
     * @param label   what it means
     * @param max     the longest data the standard allows
     * @param decimal whether one more digit gives the number of decimal places
     */
    private static void putVariable(Map<String, Gs1ApplicationIdentifier> table, String code,
            String label, int max, boolean decimal) {
        table.put(code, new Gs1ApplicationIdentifier(code, label, 0, max, decimal));
    }

    /**
     * Tells whether this identifier's data has a length fixed by the standard.
     *
     * @return true when no separator is needed after it
     */
    public boolean isFixedLength() {
        return dataLength > 0;
    }

    /**
     * Returns the identifier the payload names at that position, LONGEST MATCH FIRST.
     *
     * <p>Four digits before three before two: {@code 3103} is a net weight and not the
     * date family {@code 31} followed by data, and reading it the short way would shift
     * everything after it.
     *
     * @param payload the payload being read
     * @param from    where the identifier starts
     * @return the identifier, or null when the payload names none this version knows
     */
    public static Gs1ApplicationIdentifier at(String payload, int from) {
        if (payload == null || from < 0 || from >= payload.length()) {
            return null;
        }
        for (int length = MAX_CODE_LENGTH; length >= 2; length--) {
            if (from + length > payload.length()) {
                continue;
            }
            String candidate = payload.substring(from, from + length);
            Gs1ApplicationIdentifier known = KNOWN.get(candidate);
            if (known != null && !known.decimal()) {
                return known;
            }
            // A decimal identifier is named by its stem: the digit that follows is the
            // number of decimal places, not part of the code.
            if (length > 2) {
                Gs1ApplicationIdentifier stem = KNOWN.get(payload.substring(from, from + length - 1));
                if (stem != null && stem.decimal()) {
                    return stem;
                }
            }
        }
        return null;
    }

    /**
     * Returns the identifier carrying that exact code, without reading a payload.
     *
     * @param code the identifier's digits, the decimal-place digit excluded
     * @return the identifier, or null when this version knows none
     */
    public static Gs1ApplicationIdentifier of(String code) {
        return code == null ? null : KNOWN.get(code);
    }
}
