package com.intermarche.pos.ui.journal;

import jakarta.ws.rs.core.MultivaluedMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The parsed multi-criteria form of the electronic journal search
 * (BO-04-01-01..55). It is a pure value object: the raw query strings the
 * back-office form posts are turned into typed, already-validated fields
 * here, so the {@link JournalService} query builder never re-parses a String
 * and the resource stays a thin adapter.
 * <p>
 * Every field is optional and independent — an absent field contributes no
 * clause (BO-04-01-13, the "choix multiple": any combination of the criteria
 * simply ANDs together). A field that fails to parse (a non-numeric amount, a
 * malformed date) is treated as absent rather than raising: the journal is a
 * read screen, a bad filter narrows nothing, it does not fault.
 * <p>
 * Only the criteria backed by consolidated data are modelled. Criteria whose
 * source data does not exist on the consolidated node (cash movements,
 * self-scanning flags...) are deliberately NOT fields here: they are reported
 * as residue rather than rendered as dead controls. The card authorization
 * number and the degraded-mode indicator ARE now modelled (BO-04-01-08/47/49):
 * the card payment carries both since campaign lot C2 opened the gisement.
 */
public class JournalCriteria {

    /** The recognized boolean event/predicate flags (BO-04-01-14/25/31/32/34/46). */
    public enum Flag {
        /** The ticket is a cancelled draft (événement « annulé »). */
        CANCELLED,
        /** The ticket carries an applied global discount (événement « remise »). */
        DISCOUNT,
        /** A refund references the ticket (événement « retour » / BO-04-01-31). */
        RETURN,
        /** The ticket total is strictly negative (événement « transaction négative »). */
        NEGATIVE,
        /** The ticket has at least one zero-priced article line (BO-04-01-25). */
        ZERO_PRICE,
        /** The ticket has at least one unknown-item line (BO-04-01-34). */
        UNKNOWN_ITEM,
        /** The ticket was paid, in part, with a store voucher (BO-04-01-32). */
        VOUCHER,
        /** The ticket carries at least one card payment (BO-04-01-46, total monétique). */
        CARD,
        /** The ticket carries a card payment accepted in degraded mode (BO-04-01-47). */
        DEGRADED,
        /**
         * The ticket carries a card payment accepted in MANUAL degraded mode
         * (BO-04-01-49). The register's only degraded mode is the manual
         * back-office toggle (BO-03-12-05), so this matches the same rows as
         * {@link #DEGRADED}; both are kept because the questionnaire poses them
         * as two distinct criteria.
         */
        DEGRADED_MANUAL
    }

    /** Free text searched in the article labels, ticket number and fidelity card (BO-04-01-01). */
    public String text;

    /** Inclusive lower bound of the cashier badge range (BO-04-01-02), or null. */
    public String cashierMin;
    /** Inclusive upper bound of the cashier badge range (BO-04-01-02), or null. */
    public String cashierMax;

    /** Inclusive lower bound of the register (TPV) range (BO-04-01-03), or null. */
    public String terminalMin;
    /** Inclusive upper bound of the register (TPV) range (BO-04-01-03), or null. */
    public String terminalMax;

    /** Inclusive lower bound of the transaction number range (BO-04-01-04), or null. */
    public String txMin;
    /** Inclusive upper bound of the transaction number range (BO-04-01-04), or null. */
    public String txMax;

    /** Inclusive lower bound of the ticket amount range (BO-04-01-05/07), or null. */
    public BigDecimal amountMin;
    /** Inclusive upper bound of the ticket amount range (BO-04-01-05/07), or null. */
    public BigDecimal amountMax;

    /** The selected payment method keys (BO-04-01-06/07), never null (possibly empty). */
    public Set<String> methods = new LinkedHashSet<>();

    /** Inclusive lower bound of the transaction datetime range (BO-04-01-09), or null. */
    public LocalDateTime dateFrom;
    /** Inclusive upper bound of the transaction datetime range (BO-04-01-09), or null. */
    public LocalDateTime dateTo;

    /** Inclusive lower bound of the article PLU range (BO-04-01-10), or null. */
    public String pluMin;
    /** Inclusive upper bound of the article PLU range (BO-04-01-10), or null. */
    public String pluMax;

    /** A VAT rate the ticket must carry on at least one line (BO-04-01-55), or null. */
    public BigDecimal vatRate;

    /** Inclusive lower bound of the card authorization number range (BO-04-01-08), or null. */
    public String authMin;
    /** Inclusive upper bound of the card authorization number range (BO-04-01-08), or null. */
    public String authMax;

    /** Inclusive lower bound of the manual-reduction amount range (BO-04-01-26), or null. */
    public BigDecimal reductionMin;
    /** Inclusive upper bound of the manual-reduction amount range (BO-04-01-26), or null. */
    public BigDecimal reductionMax;

    /** The selected boolean flags (BO-04-01-14/25/31/32/34/46), never null. */
    public Set<Flag> flags = new LinkedHashSet<>();

    /** The selected functional event types (functional journal tab), never null. */
    public Set<String> eventTypes = new LinkedHashSet<>();

    /** The sort column key (one of {@link JournalSort}), or null for the default. */
    public String sort;

    /** Whether the sort is descending. */
    public boolean descending;

    /**
     * The 1-based page number of the result list (BO-04-01-13's free search is
     * paged, never truncated). Defaults to the first page.
     */
    public int page = 1;

    /**
     * Default constructor for an empty (match-everything) criteria.
     */
    public JournalCriteria() {
    }

    /**
     * Parses a criteria from the raw query parameters of the search form.
     * Every value is trimmed; a blank or unparseable value is dropped so the
     * corresponding criterion contributes nothing.
     *
     * @param params the query parameters, or null for an empty criteria
     * @return the parsed criteria (never null)
     */
    public static JournalCriteria fromParams(MultivaluedMap<String, String> params) {
        JournalCriteria criteria = new JournalCriteria();
        if (params == null) {
            return criteria;
        }
        criteria.text = blankToNull(params.getFirst("text"));
        criteria.cashierMin = blankToNull(params.getFirst("cashierMin"));
        criteria.cashierMax = blankToNull(params.getFirst("cashierMax"));
        criteria.terminalMin = blankToNull(params.getFirst("terminalMin"));
        criteria.terminalMax = blankToNull(params.getFirst("terminalMax"));
        criteria.txMin = blankToNull(params.getFirst("txMin"));
        criteria.txMax = blankToNull(params.getFirst("txMax"));
        criteria.amountMin = parseAmount(params.getFirst("amountMin"));
        criteria.amountMax = parseAmount(params.getFirst("amountMax"));
        criteria.dateFrom = parseDateTime(params.getFirst("dateFrom"));
        criteria.dateTo = parseDateTime(params.getFirst("dateTo"));
        criteria.pluMin = blankToNull(params.getFirst("pluMin"));
        criteria.pluMax = blankToNull(params.getFirst("pluMax"));
        criteria.vatRate = parseAmount(params.getFirst("vatRate"));
        criteria.authMin = blankToNull(params.getFirst("authMin"));
        criteria.authMax = blankToNull(params.getFirst("authMax"));
        criteria.reductionMin = parseAmount(params.getFirst("reductionMin"));
        criteria.reductionMax = parseAmount(params.getFirst("reductionMax"));
        addNonBlank(criteria.methods, params.get("method"));
        addNonBlank(criteria.eventTypes, params.get("eventType"));
        addFlags(criteria.flags, params.get("flag"));
        criteria.sort = blankToNull(params.getFirst("sort"));
        criteria.descending = "desc".equals(params.getFirst("dir"));
        criteria.page = parsePage(params.getFirst("page"));
        return criteria;
    }

    /**
     * Parses a 1-based page number, falling back to the first page on a blank,
     * malformed or out-of-range value: a bad page never faults the read screen,
     * it simply shows the beginning of the list.
     *
     * @param raw the raw value
     * @return the page number, at least 1
     */
    static int parsePage(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return 1;
        }
        try {
            int page = Integer.parseInt(value);
            return page < 1 ? 1 : page;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Trims a raw value, returning null when it is null or blank.
     *
     * @param raw the raw value
     * @return the trimmed value, or null
     */
    static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Parses an amount, accepting a French comma, returning null on a blank or
     * malformed value.
     *
     * @param raw the raw value
     * @return the parsed amount, or null
     */
    static BigDecimal parseAmount(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a datetime, accepting either a full ISO local datetime
     * ({@code yyyy-MM-ddTHH:mm}) or a bare ISO date (taken at start of day),
     * returning null on a blank or malformed value.
     *
     * @param raw the raw value
     * @return the parsed datetime, or null
     */
    static LocalDateTime parseDateTime(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /**
     * Adds the trimmed non-blank values of a raw list to a target set.
     *
     * @param target the set to fill
     * @param raw the raw values, or null
     */
    static void addNonBlank(Set<String> target, List<String> raw) {
        if (raw == null) {
            return;
        }
        for (String value : raw) {
            String trimmed = blankToNull(value);
            if (trimmed != null) {
                target.add(trimmed);
            }
        }
    }

    /**
     * Adds the recognized flag names of a raw list to a target set, ignoring
     * blank or unknown names.
     *
     * @param target the set to fill
     * @param raw the raw values, or null
     */
    static void addFlags(Set<Flag> target, List<String> raw) {
        if (raw == null) {
            return;
        }
        for (String value : raw) {
            String trimmed = blankToNull(value);
            if (trimmed == null) {
                continue;
            }
            try {
                target.add(Flag.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                // Unknown flag name: ignore rather than fault the read screen.
            }
        }
    }
}
