package com.intermarche.pos.ui.invoice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ONE typed entry of the invoice screen: a label, the on-screen keyboard its content
 * needs, and what the operator has typed so far.
 *
 * <p>There is no physical keyboard on this machine, so every text entry of the screen
 * goes through the register's on-screen keyboard, ONE FIELD AT A TIME. Describing a
 * field as data rather than as markup is what lets the three masks of the flow — the
 * original ticket ({@code LC-08-04-02}), the customer lookup ({@code LC-08-04-06/07})
 * and the customer creation ({@code LC-08-04-10}) — share a single entry mechanism
 * instead of carrying three hand-written variants.
 *
 * <p>The customer-creation mask is ADMINISTERED ({@code LC-08-04-10}): the back office
 * says which fields are asked for, in which order, and which of them are mandatory.
 * The keyboard, the capitalisation and the length cap are NOT administered — they are
 * properties of the field itself (a postal code is digits, a SIRET is twenty of them),
 * and letting a store change them would only let a store break an entry.
 *
 * @param name the form parameter name
 * @param label the operator-facing label, mandatory fields already marked
 * @param keyboard the on-screen keyboard type ({@code alpha}, {@code numpad},
 *        {@code email})
 * @param uppercase whether the keyboard forces upper case
 * @param maxLength the length cap, never above the column the value is stored in
 * @param required whether the entry refuses to go on while the field is blank
 * @param value what the field carries, empty when nothing was typed
 */
public record EntryField(String name, String label, String keyboard, boolean uppercase,
                         int maxLength, boolean required, String value) {

    /** Marks an administered field as mandatory. */
    public static final String REQUIRED_MARK = "*";

    /** The administered default: every known field, the business name mandatory. */
    public static final String DEFAULT_CUSTOMER_FIELDS =
            "companyName*;contactName;street;postalCode;city;siret;vatNumber;phone;email";

    /**
     * The customer fields the register knows how to ask for, in catalog order.
     *
     * <p>An administered list names a subset of these keys; a key that is not here is
     * ignored rather than rendered blind — the register can only ask for a field it
     * knows how to store.
     */
    private static final Map<String, EntryField> CUSTOMER_CATALOG = customerCatalog();

    /**
     * Builds the catalog of known customer fields.
     *
     * @return the catalog, keyed by form parameter name, in display order
     */
    private static Map<String, EntryField> customerCatalog() {
        Map<String, EntryField> catalog = new LinkedHashMap<>();
        catalog.put("companyName", new EntryField("companyName", "Raison sociale", "alpha", true, 120, false, ""));
        catalog.put("contactName", new EntryField("contactName", "Contact", "alpha", true, 120, false, ""));
        catalog.put("street", new EntryField("street", "Adresse", "email", true, 120, false, ""));
        catalog.put("postalCode", new EntryField("postalCode", "Code postal", "numpad", false, 10, false, ""));
        catalog.put("city", new EntryField("city", "Ville", "alpha", true, 60, false, ""));
        catalog.put("siret", new EntryField("siret", "SIRET", "numpad", false, 20, false, ""));
        catalog.put("vatNumber", new EntryField("vatNumber", "TVA intracom.", "email", true, 20, false, ""));
        catalog.put("phone", new EntryField("phone", "Téléphone", "numpad", false, 30, false, ""));
        catalog.put("email", new EntryField("email", "Courriel", "email", false, 120, false, ""));
        return catalog;
    }

    /**
     * Returns the label as the screen shows it, the mandatory mark appended.
     *
     * @return the label, followed by a star when the field is mandatory
     */
    public String getDisplayLabel() {
        return required ? label + " " + REQUIRED_MARK : label;
    }


    /**
     * Parses the administered customer mask ({@code LC-08-04-10}).
     *
     * <p>The syntax is the register's usual semicolon list: one field name per entry,
     * a trailing star marking it mandatory. An unknown name is dropped, a blank list
     * falls back to the whole catalog with the business name mandatory — a store that
     * empties the parameter by accident must not end up with a customer form that
     * asks for nothing.
     *
     * @param administered the administered list, possibly null or blank
     * @return the fields to ask for, in administered order, never empty
     */
    public static List<EntryField> customerFields(String administered) {
        String raw = administered == null || administered.isBlank()
                ? DEFAULT_CUSTOMER_FIELDS : administered;
        List<EntryField> fields = new ArrayList<>();
        for (String part : raw.split(";")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            boolean required = token.endsWith(REQUIRED_MARK);
            String name = required ? token.substring(0, token.length() - 1).trim() : token;
            EntryField known = CUSTOMER_CATALOG.get(name);
            if (known == null) {
                continue;
            }
            fields.add(new EntryField(known.name(), known.label(), known.keyboard(),
                    known.uppercase(), known.maxLength(), required, ""));
        }
        return fields.isEmpty() ? customerFields(DEFAULT_CUSTOMER_FIELDS) : fields;
    }

    /**
     * Builds the mask naming the original ticket ({@code LC-08-04-02}): its number,
     * its date and the register that issued it — the three things printed on a ticket
     * a customer hands back over the counter.
     *
     * <p>Only the number is mandatory: it identifies the sale on its own in this
     * register's database, and the other two are what tell two identical numbers apart
     * once tickets from other registers are searchable.
     *
     * @param number the number already typed or pre-filled
     * @param date the date already typed or pre-filled, {@code dd/MM/yyyy}
     * @param terminal the register identifier already typed or pre-filled
     * @return the three fields, in entry order
     */
    public static List<EntryField> ticketFields(String number, String date, String terminal) {
        return List.of(
                new EntryField("ticketNumber", "N° de ticket", "email", true, 30, true, safe(number)),
                new EntryField("ticketDate", "Date", "numpad", false, 10, false, safe(date)),
                new EntryField("ticketTerminal", "N° de caisse", "email", true, 20, false, safe(terminal)));
    }

    /**
     * Builds the mask looking a customer up: by name ({@code LC-08-04-07}) or straight
     * by account number ({@code LC-08-04-06}) when the operator knows it.
     *
     * @param search the name fragment already typed
     * @param number the account number already typed
     * @return the two fields, in entry order
     */
    public static List<EntryField> customerLookupFields(String search, String number) {
        return List.of(
                new EntryField("search", "Raison sociale", "alpha", true, 40, false, safe(search)),
                new EntryField("customerNumber", "N° client", "email", true, 30, false, safe(number)));
    }

    /**
     * Turns a missing value into an empty one.
     *
     * @param value the value, possibly null
     * @return the value, or an empty string
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
