package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.PosList;
import com.intermarche.pos.domain.setting.PosListItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the ADMINISTERED LISTS the register taps ({@code BO-04-02-07},
 * {@code BO-04-03-12}) — the service-request reasons, the justifications, the
 * return types and the tare containers.
 *
 * <p>ONE RULE, APPLIED FOUR TIMES: when the store node has distributed a list
 * of the kind asked for, that list is the answer; when it has not, the
 * semicolon-separated setting the register has always read is the answer. So
 * a shop that never opens the new screen keeps exactly the behaviour it had,
 * and a shop that opens it stops editing strings.
 *
 * <p>This service, and not {@link PosSettingsService}, holds the resolution:
 * the settings service answers from the parameter table alone and is injected
 * nearly everywhere, and teaching it to read a referential would have made
 * every one of its callers depend on the entity.
 */
@ApplicationScoped
public class PosListService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PosListService.class);

    /** The register function the expense justifications are bound to. */
    public static final String FUNCTION_EXPENSE = "EXPENSE";

    /** The register function the line-cancellation justifications are bound to. */
    public static final String FUNCTION_CANCEL_LINE = "CANCEL_LINE";
    /** The register function the forced drawer-opening justifications are bound to (LC-12-01-02). */
    public static final String FUNCTION_DRAWER_OPEN = "DRAWER_OPEN";

    /** The administered parameters, read when no list has been distributed. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * ONE service-request type offered on the supervisor-call screen
     * ({@code BO-04-02-07}).
     *
     * @param code the type's code, which the back office files the call under
     * @param label what the key is called in front of the cashier
     * @param severity the priority the back office shows, 0 when none is set
     */
    public record ServiceRequestType(String code, String label, int severity) {
    }

    /**
     * The service-request types offered on the supervisor-call screen
     * ({@code BO-04-02-07}).
     *
     * <p>Falls back to the semicolon-separated
     * {@code supervisor.service-reasons}, each reason then being its own code
     * and carrying no severity.
     *
     * @return the types, in display order, possibly empty
     */
    public List<ServiceRequestType> serviceRequestTypes() {
        LOGGER.info("Entering method serviceRequestTypes");
        List<ServiceRequestType> types = new ArrayList<>();
        List<PosListItem> items = itemsOf(PosList.Kind.SERVICE_REQUEST, null);
        if (items != null) {
            for (PosListItem item : items) {
                types.add(new ServiceRequestType(item.code, item.label, item.severity));
            }
            LOGGER.info("Exiting method serviceRequestTypes");
            return types;
        }
        for (String raw : posSettingsService.supervisorServiceReasons().split(";")) {
            String reason = raw.trim();
            if (!reason.isEmpty()) {
                types.add(new ServiceRequestType(reason, reason, 0));
            }
        }
        LOGGER.info("Exiting method serviceRequestTypes");
        return types;
    }

    /**
     * The justifications offered for one register function
     * ({@code BO-04-03-12}).
     *
     * <p>Falls back to the semicolon-separated {@code cash.movement-reasons}
     * for the EXPENSE function ALONE: that key names the motives of a drawer
     * expense and nothing else, so answering it to another gesture would
     * offer "Achat de timbres" as the reason for a cancellation. Any other
     * function with no list offers nothing.
     *
     * @param function the function code asked about, possibly null
     * @return the justifications, in display order, possibly empty
     */
    public List<String> justifications(String function) {
        LOGGER.info("Entering method justifications with function: " + function);
        List<String> labels = new ArrayList<>();
        for (ListChoice justification : justificationsOf(function)) {
            labels.add(justification.label());
        }
        LOGGER.info("Exiting method justifications");
        return labels;
    }

    /**
     * ONE entry offered by an administered list — a justification, a return
     * reason — with the CODE a back-office state reports it under
     * ({@code BO-04-03-12}, {@code BO-06-06-04}, {@code BO-06-06-05}).
     *
     * @param code the entry's code, which the states group by
     * @param label what the key is called in front of the cashier
     */
    public record ListChoice(String code, String label) {
    }

    /**
     * The reasons offered for a return, each carrying its code
     * ({@code LC-05-05-01}, {@code BO-06-06-05}).
     *
     * <p>Falls back to the semicolon-separated {@code return.reasons}, each
     * reason then being its own code — there is nothing else to use, and it
     * is the reading every other fallback of this service already makes.
     *
     * @return the reasons, in display order, possibly empty
     */
    public List<ListChoice> returnReasons() {
        LOGGER.info("Entering method returnReasons");
        List<ListChoice> reasons = new ArrayList<>();
        List<PosListItem> items = itemsOf(PosList.Kind.RETURN_REASON, null);
        if (items != null) {
            for (PosListItem item : items) {
                reasons.add(new ListChoice(item.code, item.label));
            }
            LOGGER.info("Exiting method returnReasons");
            return reasons;
        }
        for (String reason : posSettingsService.returnReasons()) {
            reasons.add(new ListChoice(reason, reason));
        }
        LOGGER.info("Exiting method returnReasons");
        return reasons;
    }

    /**
     * The return reason carrying a LABEL, or null when none does
     * ({@code BO-06-06-05}).
     *
     * <p>By label and not by code, unlike the justifications: the return
     * screen has always posted the label back and the refund state keeps it,
     * so the label is what there is to resolve.
     *
     * @param label the label to resolve, possibly null
     * @return the reason, or null when nothing carries that label
     */
    public ListChoice returnReason(String label) {
        LOGGER.info("Entering method returnReason with label: " + label);
        if (label == null || label.isBlank()) {
            LOGGER.info("Exiting method returnReason");
            return null;
        }
        String typed = label.trim();
        for (ListChoice candidate : returnReasons()) {
            if (candidate.label() != null && candidate.label().equalsIgnoreCase(typed)) {
                LOGGER.info("Exiting method returnReason");
                return candidate;
            }
        }
        LOGGER.info("Exiting method returnReason");
        return null;
    }

    /**
     * The justifications offered for one register function, each carrying its
     * code ({@code BO-04-03-12}, {@code BO-06-06-04}).
     *
     * <p>Falls back to the semicolon-separated {@code cash.movement-reasons}
     * for the EXPENSE function ALONE, exactly as {@link #justifications}
     * describes; a fallen-back reason is its own code, there being nothing
     * else to use — the same reading the service-request types already make
     * of their own fallback.
     *
     * @param function the function code asked about, possibly null
     * @return the justifications, in display order, possibly empty
     */
    public List<ListChoice> justificationsOf(String function) {
        LOGGER.info("Entering method justificationsOf with function: " + function);
        List<ListChoice> justifications = new ArrayList<>();
        List<PosListItem> items = itemsOf(PosList.Kind.JUSTIFICATION, function);
        if (items != null) {
            for (PosListItem item : items) {
                justifications.add(new ListChoice(item.code, item.label));
            }
            LOGGER.info("Exiting method justificationsOf");
            return justifications;
        }
        if (FUNCTION_EXPENSE.equalsIgnoreCase(function == null ? "" : function.trim())) {
            for (String reason : posSettingsService.cashMovementReasons()) {
                justifications.add(new ListChoice(reason, reason));
            }
        }
        LOGGER.info("Exiting method justificationsOf");
        return justifications;
    }

    /**
     * ONE administered EXPENSE (BO-03-02-45/-46/-47): an entry of a
     * justification list serving the {@code EXPENSE} function, with what the
     * back office states about it beside its label.
     *
     * @param code the entry's code
     * @param label what the key is called in front of the cashier
     * @param tenderCode the tender the expense is paid in (option
     *        {@code TENDER:code}), or null for cash
     * @param maxAmount the largest amount one expense may carry (the entry's
     *        value), or null when unbounded
     * @param control what the register does above that amount (option
     *        {@code CONTROL:level}), never null
     */
    public record Expense(String code, String label, String tenderCode, BigDecimal maxAmount,
                          com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel control) {

        /**
         * What the register does about one amount of this expense
         * (BO-03-02-47).
         *
         * @param amount the amount the cashier keyed, or null
         * @return the control level when the amount goes over the maximum, NONE otherwise
         */
        public com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel levelFor(BigDecimal amount) {
            if (maxAmount == null || amount == null || amount.compareTo(maxAmount) <= 0) {
                return com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE;
            }
            return control;
        }
    }

    /**
     * The ACTIVE expenses the cash-movement screen offers (BO-03-02-45/-46):
     * the entries of the justification lists serving the {@code EXPENSE}
     * function, each with its tender, its maximum and its control.
     *
     * <p>Activating an entry on the back office is what makes the expense
     * available at the register as soon as the lists reach it; deactivating
     * or deleting it takes it away the same way. Without any list, the
     * semicolon-separated {@code cash.movement-reasons} still answers, each
     * reason then being a cash expense with no maximum.
     *
     * @return the expenses, in display order, possibly empty
     */
    public List<Expense> expenses() {
        LOGGER.info("Entering method expenses");
        List<Expense> expenses = new ArrayList<>();
        List<PosListItem> items = itemsOf(PosList.Kind.JUSTIFICATION, FUNCTION_EXPENSE);
        if (items == null) {
            for (String reason : posSettingsService.cashMovementReasons()) {
                expenses.add(new Expense(reason, reason, null, null,
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE));
            }
            LOGGER.info("Exiting method expenses");
            return expenses;
        }
        for (PosListItem item : items) {
            String tender = option(item.options, "TENDER");
            expenses.add(new Expense(item.code, item.label,
                    tender == null || "CASH".equalsIgnoreCase(tender) ? null : tender.toUpperCase(java.util.Locale.ROOT),
                    item.numericValue, controlLevel(option(item.options, "CONTROL"))));
        }
        LOGGER.info("Exiting method expenses");
        return expenses;
    }

    /**
     * The active expense carrying a label or a code, or null when none does
     * (BO-03-02-45/-46/-47).
     *
     * @param reason the label or code the screen posted, possibly null
     * @return the expense, or null
     */
    public Expense expense(String reason) {
        LOGGER.info("Entering method expense with reason: " + reason);
        if (reason == null || reason.isBlank()) {
            LOGGER.info("Exiting method expense");
            return null;
        }
        String typed = reason.trim();
        for (Expense candidate : expenses()) {
            if (candidate.label().equalsIgnoreCase(typed) || candidate.code().equalsIgnoreCase(typed)) {
                LOGGER.info("Exiting method expense");
                return candidate;
            }
        }
        LOGGER.info("Exiting method expense");
        return null;
    }

    /**
     * ONE ready-made discount or rebate KEY of the register
     * (BO-03-07-03/-04/-06/-09).
     *
     * @param label what the key is called on the screen
     * @param percent true for a percentage, false for an amount in euros
     * @param value the figure the key applies, or null for a key whose figure
     *        the cashier types
     * @param cumulative whether the key may pile on an already-reduced line
     * @param ticketLevel true for a key on the ticket total, false for a line key
     * @param maximum the ceiling of a typed figure (option {@code MAX:n}), or null
     */
    public record DiscountKey(String label, boolean percent, BigDecimal value, boolean cumulative,
                              boolean ticketLevel, BigDecimal maximum) {

        /**
         * Tells whether the cashier types this key's figure.
         *
         * @return true when the key carries no figure of its own
         */
        public boolean open() {
            return value == null;
        }
    }

    /**
     * The ACTIVE discount and rebate keys (BO-03-07-03/-04/-06/-09): the
     * entries of the distributed {@code DISCOUNT} lists, or, without any, the
     * keys of the {@code discount.presets} parameter.
     *
     * <p>An entry names its nature ({@code PERCENT} or {@code AMOUNT}) or it is
     * dropped: a key that takes off a figure in a unit nobody stated is worse
     * than a key that is not offered. Deleting or deactivating an entry on the
     * back office removes the key from the register with the next lists it
     * receives.
     *
     * @return the keys, in display order, possibly empty
     */
    public List<DiscountKey> discountKeys() {
        LOGGER.info("Entering method discountKeys");
        List<DiscountKey> keys = new ArrayList<>();
        List<PosListItem> items = itemsOf(PosList.Kind.DISCOUNT, null);
        if (items == null) {
            for (PosSettingsService.DiscountPreset preset : posSettingsService.discountPresets()) {
                keys.add(new DiscountKey(preset.label(), preset.percent(), preset.value(),
                        preset.cumulative(), false, null));
            }
            LOGGER.info("Exiting method discountKeys");
            return keys;
        }
        for (PosListItem item : items) {
            boolean percent = marked(item.options, "PERCENT");
            boolean amount = marked(item.options, "AMOUNT");
            if (percent == amount || item.numericValue != null && item.numericValue.signum() <= 0) {
                continue;
            }
            keys.add(new DiscountKey(item.label, percent, item.numericValue,
                    marked(item.options, "CUMUL"), marked(item.options, "TICKET"),
                    decimal(option(item.options, "MAX"))));
        }
        LOGGER.info("Exiting method discountKeys");
        return keys;
    }

    /**
     * The active discount key carrying a label, or null when none does
     * (BO-03-07-03/-06/-09).
     *
     * @param label the label the screen posted, possibly null
     * @return the key, or null
     */
    public DiscountKey discountKey(String label) {
        LOGGER.info("Entering method discountKey with label: " + label);
        if (label == null || label.isBlank()) {
            LOGGER.info("Exiting method discountKey");
            return null;
        }
        for (DiscountKey candidate : discountKeys()) {
            if (candidate.label().equalsIgnoreCase(label.trim())) {
                LOGGER.info("Exiting method discountKey");
                return candidate;
            }
        }
        LOGGER.info("Exiting method discountKey");
        return null;
    }

    /**
     * Tells whether an entry's option markers carry a bare marker.
     *
     * @param options the comma-separated markers, possibly null
     * @param name the marker, upper case
     * @return true when the marker is present
     */
    static boolean marked(String options, String name) {
        if (options == null) {
            return false;
        }
        for (String part : options.split(",")) {
            if (part.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a decimal written with a point or a comma, an unreadable or
     * non-positive one meaning none.
     *
     * @param raw the written figure, possibly null
     * @return the figure, or null
     */
    static BigDecimal decimal(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.replace(',', '.'));
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads one {@code NAME:value} option of an entry's option markers.
     *
     * @param options the comma-separated markers, possibly null
     * @param name the option name, upper case
     * @return the trimmed value, or null when the option is absent or empty
     */
    static String option(String options, String name) {
        if (options == null) {
            return null;
        }
        for (String part : options.split(",")) {
            String marker = part.trim();
            int colon = marker.indexOf(':');
            if (colon > 0 && marker.substring(0, colon).trim().equalsIgnoreCase(name)) {
                String value = marker.substring(colon + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Reads an administered control level, an unknown or absent one meaning
     * none.
     *
     * @param raw the written level, possibly null
     * @return the level, never null
     */
    static com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel controlLevel(String raw) {
        for (com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel level
                : com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.values()) {
            if (level.name().equalsIgnoreCase(raw == null ? "" : raw.trim())) {
                return level;
            }
        }
        return com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE;
    }

    /**
     * The justification carrying a code, for one register function, or null
     * when the function offers none under that code ({@code BO-06-06-04}).
     *
     * <p>Matched case-insensitively BY CODE, like the value a screen posts
     * back: what travels between the chooser and the gesture is the code, and
     * the administered list is what it means.
     *
     * @param function the function code asked about
     * @param code the code to resolve, possibly null
     * @return the justification, or null when nothing carries that code
     */
    public ListChoice justificationOf(String function, String code) {
        LOGGER.info("Entering method justificationOf with function: " + function + ", code: " + code);
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method justificationOf");
            return null;
        }
        String typed = code.trim();
        for (ListChoice candidate : justificationsOf(function)) {
            if (candidate.code() != null && candidate.code().equalsIgnoreCase(typed)) {
                LOGGER.info("Exiting method justificationOf");
                return candidate;
            }
        }
        LOGGER.info("Exiting method justificationOf");
        return null;
    }

    /**
     * The return types offered on the return screen ({@code LC-05-04-01}).
     *
     * <p>Falls back to the semicolon-separated {@code return.types}. The four
     * option markers are the same on both roads, so a type reads the same
     * whichever one it came by.
     *
     * @return the return types, in display order, possibly empty
     */
    public List<PosSettingsService.ReturnType> returnTypes() {
        LOGGER.info("Entering method returnTypes");
        // BO-03-13-01/-03: the dedicated referential wins the moment one type
        // exists; its active types are what the register offers.
        List<com.intermarche.pos.domain.setting.ReturnTypeDefinition> definitions = definitions();
        if (definitions != null) {
            List<PosSettingsService.ReturnType> offered = new ArrayList<>();
            for (com.intermarche.pos.domain.setting.ReturnTypeDefinition definition : definitions) {
                if (definition.active) {
                    offered.add(returnTypeOf(definition));
                }
            }
            LOGGER.info("Exiting method returnTypes");
            return offered;
        }
        List<PosListItem> items = itemsOf(PosList.Kind.RETURN_TYPE, null);
        if (items == null) {
            LOGGER.info("Exiting method returnTypes");
            return posSettingsService.returnTypes();
        }
        List<PosSettingsService.ReturnType> types = new ArrayList<>();
        for (PosListItem item : items) {
            types.add(returnTypeOf(item));
        }
        LOGGER.info("Exiting method returnTypes");
        return types;
    }

    /**
     * The active return type an administered RETURN barcode range is attached
     * to (BO-03-13-12): the entry whose option {@code RANGE:code} names the
     * range, or null when none does.
     *
     * @param rangeCode the code of the barcode range scanned, possibly null
     * @return the attached type, or null
     */
    public PosSettingsService.ReturnType returnTypeOfRange(String rangeCode) {
        LOGGER.info("Entering method returnTypeOfRange with rangeCode: " + rangeCode);
        List<com.intermarche.pos.domain.setting.ReturnTypeDefinition> definitions =
                rangeCode == null ? null : definitions();
        if (definitions != null) {
            for (com.intermarche.pos.domain.setting.ReturnTypeDefinition definition : definitions) {
                if (definition.active && rangeCode.equalsIgnoreCase(definition.rangeCode)) {
                    LOGGER.info("Exiting method returnTypeOfRange");
                    return returnTypeOf(definition);
                }
            }
            LOGGER.info("Exiting method returnTypeOfRange");
            return null;
        }
        List<PosListItem> items = rangeCode == null ? null : itemsOf(PosList.Kind.RETURN_TYPE, null);
        if (items != null) {
            for (PosListItem item : items) {
                if (rangeCode.equalsIgnoreCase(option(item.options, "RANGE"))) {
                    LOGGER.info("Exiting method returnTypeOfRange");
                    return returnTypeOf(item);
                }
            }
        }
        LOGGER.info("Exiting method returnTypeOfRange");
        return null;
    }

    /**
     * Reads an administered return type off its list entry, its four option
     * markers included.
     *
     * @param item the entry
     * @return the return type
     */
    private PosSettingsService.ReturnType returnTypeOf(PosListItem item) {
        return new PosSettingsService.ReturnType(item.label, marked(item.options, "TICKET"),
                marked(item.options, "NOENDORSE"), marked(item.options, "NOORIGIN"),
                marked(item.options, "PRICE"));
    }

    /**
     * Reads an administered return type off its dedicated referential row
     * (BO-03-13-01).
     *
     * @param definition the referential row
     * @return the return type
     */
    private PosSettingsService.ReturnType returnTypeOf(
            com.intermarche.pos.domain.setting.ReturnTypeDefinition definition) {
        return new PosSettingsService.ReturnType(definition.label, definition.ticketMode,
                definition.endorsementExempt, definition.originOptional, definition.priceConfirmed);
    }

    /**
     * The dedicated return-type referential (BO-03-13-01), or null when the
     * node distributed no type — which is what sends the caller back to the
     * administered list, then to the setting.
     *
     * @return every distributed type, active or not, or null when there is none
     */
    private List<com.intermarche.pos.domain.setting.ReturnTypeDefinition> definitions() {
        List<com.intermarche.pos.domain.setting.ReturnTypeDefinition> all =
                com.intermarche.pos.domain.setting.ReturnTypeDefinition.findAllOrdered();
        return all == null || all.isEmpty() ? null : all;
    }

    /**
     * The CODE of the administered return type carrying a label
     * ({@code BO-06-06-05}), or the label itself when the shop administers no
     * coded list.
     *
     * <p>A lookup of its own rather than a component on
     * {@link PosSettingsService.ReturnType}: the register works by label — it
     * is what the operator taps and what the state keeps — and only the
     * document being written needs the code. Falling back on the label is the
     * reading the service-request types already make of their own fallback: a
     * reason with no referential is its own code.
     *
     * @param label the label to resolve, possibly null
     * @return the code, the label, or null when the label itself is null
     */
    public String returnTypeCode(String label) {
        LOGGER.info("Entering method returnTypeCode with label: " + label);
        if (label == null || label.isBlank()) {
            LOGGER.info("Exiting method returnTypeCode");
            return null;
        }
        String typed = label.trim();
        List<com.intermarche.pos.domain.setting.ReturnTypeDefinition> definitions = definitions();
        if (definitions != null) {
            for (com.intermarche.pos.domain.setting.ReturnTypeDefinition definition : definitions) {
                if (definition.label != null && definition.label.equalsIgnoreCase(typed)) {
                    LOGGER.info("Exiting method returnTypeCode");
                    return definition.code;
                }
            }
            LOGGER.info("Exiting method returnTypeCode");
            return typed;
        }
        List<PosListItem> items = itemsOf(PosList.Kind.RETURN_TYPE, null);
        if (items != null) {
            for (PosListItem item : items) {
                if (item.label != null && item.label.equalsIgnoreCase(typed)) {
                    LOGGER.info("Exiting method returnTypeCode");
                    return item.code;
                }
            }
        }
        LOGGER.info("Exiting method returnTypeCode");
        return typed;
    }

    /**
     * The administered return type carrying a label, or null when none does
     * ({@code BO-03-13-06/-08/-10}).
     *
     * <p>Matched case-insensitively BY LABEL, like the key the operator taps:
     * the label is what the register keeps on its state, and the list is what
     * it means.
     *
     * @param label the label to resolve, possibly null
     * @return the type, or null when nothing carries that label
     */
    public PosSettingsService.ReturnType returnType(String label) {
        LOGGER.info("Entering method returnType with label: " + label);
        if (label == null || label.isBlank()) {
            LOGGER.info("Exiting method returnType");
            return null;
        }
        String typed = label.trim();
        for (PosSettingsService.ReturnType candidate : returnTypes()) {
            if (candidate.label().equalsIgnoreCase(typed)) {
                LOGGER.info("Exiting method returnType");
                return candidate;
            }
        }
        LOGGER.info("Exiting method returnType");
        return null;
    }

    /**
     * The containers offered for a one-off tare ({@code LC-14-01-04}).
     *
     * <p>Falls back to the semicolon-separated {@code balance.tare-containers}.
     * An entry carrying no weight is dropped rather than weighed as zero: a
     * container that takes nothing off is a container nobody meant to create.
     *
     * @return the tare containers, in display order, possibly empty
     */
    public List<PosSettingsService.TareContainer> tareContainers() {
        LOGGER.info("Entering method tareContainers");
        List<PosListItem> items = itemsOf(PosList.Kind.TARE_CONTAINER, null);
        if (items == null) {
            LOGGER.info("Exiting method tareContainers");
            return posSettingsService.tareContainers();
        }
        List<PosSettingsService.TareContainer> containers = new ArrayList<>();
        for (PosListItem item : items) {
            BigDecimal weight = item.numericValue;
            if (weight == null || weight.signum() < 0) {
                continue;
            }
            containers.add(new PosSettingsService.TareContainer(item.code, item.label, weight));
        }
        LOGGER.info("Exiting method tareContainers");
        return containers;
    }

    /**
     * The ACTIVE entries of the distributed lists of one kind, or null when
     * the node has distributed no list of that kind — which is what tells the
     * caller to fall back on the administered string.
     *
     * <p>Several lists of the same kind are concatenated in code order: a shop
     * grouping its justifications under two headings offers both, which is the
     * grouping the requirement asks for.
     *
     * @param kind the kind looked for
     * @param function the register function to filter on, or null for no filter
     * @return the entries, or null when no list of that kind exists
     */
    private List<PosListItem> itemsOf(PosList.Kind kind, String function) {
        List<PosList> lists = PosList.findActiveByKind(kind);
        if (lists == null || lists.isEmpty()) {
            return null;
        }
        List<PosListItem> items = new ArrayList<>();
        boolean matched = false;
        for (PosList list : lists) {
            if (function != null && !list.servesFunction(function)) {
                continue;
            }
            matched = true;
            for (PosListItem item : list.items) {
                if (item.active) {
                    items.add(item);
                }
            }
        }
        return matched ? items : null;
    }
}
