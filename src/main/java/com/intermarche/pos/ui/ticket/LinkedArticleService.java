package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/** LC-02-03-04: companion lines follow their principal without merging with independent sales. */
public final class LinkedArticleService {
    /** Static cart policy, with normal article admission supplied by the caller. */
    private LinkedArticleService() {}

    /**
     * Captures the configured companion on the recorded article, including merged entries.
     * @param ticket current cart
     * @param product resolved article, null for a line without a catalog identity
     */
    public static void snapshot(TicketState ticket, Product product) {
        if (product == null) return;
        String linked = ProductAttributes.raw(product, ProductAttributeCatalog.LINKED_ARTICLE_EAN).trim();
        // BO-02-03-15: the number of companions per unit travels with the link.
        BigDecimal multiplier = ProductAttributes.linkedArticleQuantity(product);
        for (TicketState.TicketItem item : ticket.items) {
            if (Objects.equals(item.ean, product.ean) && item.linkedArticleEan == null) {
                item.linkedArticleEan = linked;
                item.linkedArticleMultiplier = multiplier;
            }
        }
    }

    /**
     * Completes every principal's companion chain before valuation. A refused companion rolls
     * back that principal's new quantity, so a bottle cannot remain without its required deposit.
     * @param state register state
     * @param add normal admission callback, receiving EAN and positive quantity
     * @param i18n localized refusals
     */
    public static void synchronize(PosState state, BiConsumer<String, BigDecimal> add, Messages i18n) {
        if (state.linkedArticlesUpdating || state.ticket.items.stream().noneMatch(LinkedArticleService::hasLink)) return;
        state.linkedArticlesUpdating = true;
        String previousParent = state.linkedArticleParentUid;
        String previousLast = state.lastEnteredItemId;
        boolean previousEntered = state.lineJustEntered;
        boolean previousReturn = state.returnMode;
        boolean previousCollect = state.collectArmed;
        BigDecimal previousArmed = state.armedQuantity;
        try {
            for (TicketState.TicketItem root : new ArrayList<>(state.ticket.items)) {
                if (root.linkedParentUid != null || !hasLink(root)) continue;
                List<LineSnapshot> before = state.ticket.items.stream().map(LineSnapshot::new).toList();
                BigDecimal recorded = root.linkedRecordedQuantity;
                if (!synchronizeLine(state, root, new HashSet<>(), add, i18n)) {
                    state.ticket.items.retainAll(before.stream().map(LineSnapshot::item).toList());
                    before.forEach(LineSnapshot::restore);
                    root.quantity = recorded == null ? BigDecimal.ZERO : recorded;
                    if (root.quantity.signum() == 0) state.ticket.items.remove(root);
                    state.entryPrompt.clear();
                    state.ageCheck.clear();
                    break;
                }
            }
            state.ticket.recomputeTotal();
        } finally {
            state.linkedArticleParentUid = previousParent;
            state.lastEnteredItemId = state.ticket.items.stream()
                    .anyMatch(item -> Objects.equals(item.uid, previousLast)) ? previousLast : null;
            state.lineJustEntered = previousEntered;
            state.returnMode = previousReturn;
            state.collectArmed = previousCollect;
            state.armedQuantity = previousArmed;
            state.linkedArticlesUpdating = false;
        }
    }

    /**
     * Registers or adjusts one companion, then its own companion; repeated EANs refuse the chain.
     * @param state register state
     * @param principal line whose companion is required
     * @param ancestors EANs already traversed on this chain
     * @param add normal admission callback
     * @param i18n localized refusals
     * @return true when the whole chain was admitted
     */
    private static boolean synchronizeLine(PosState state, TicketState.TicketItem principal,
            Set<String> ancestors, BiConsumer<String, BigDecimal> add, Messages i18n) {
        if (!hasLink(principal)) return true;
        ancestors.add(principal.ean);
        String ean = principal.linkedArticleEan;
        if (ancestors.contains(ean)) {
            state.ticket.setError(i18n.format("op.articleLieCycle", ean));
            return false;
        }
        TicketState.TicketItem companion = findCompanion(state.ticket, principal.uid, ean);
        if (companion == null) {
            state.linkedArticleParentUid = principal.uid;
            state.returnMode = principal.quantity.signum() < 0;
            state.collectArmed = principal.toCollect;
            state.armedQuantity = null;
            add.accept(ean, companionQuantity(principal).abs());
            companion = findCompanion(state.ticket, principal.uid, ean);
            if (companion == null) {
                if (Objects.isNull(state.ticket.transientError)) {
                    state.ticket.setError(i18n.format("op.articleLieIndisponible", ean));
                }
                return false;
            }
        }
        BigDecimal expected = companionQuantity(principal);
        if (companion.quantity.compareTo(expected) != 0) {
            companion.quantity = expected;
            companion.valuedTotal = null;
        }
        if (!synchronizeLine(state, companion, ancestors, add, i18n)) return false;
        principal.linkedRecordedQuantity = principal.quantity;
        return true;
    }

    /**
     * The companion quantity a principal requires (BO-02-03-15): its own
     * quantity times the companions per unit captured with the link, one when
     * none was captured.
     * @param principal line whose companion is required
     * @return the companion quantity, signed like the principal
     */
    private static BigDecimal companionQuantity(TicketState.TicketItem principal) {
        BigDecimal multiplier = principal.linkedArticleMultiplier == null
                ? BigDecimal.ONE : principal.linkedArticleMultiplier;
        return principal.quantity.multiply(multiplier);
    }

    /**
     * Distinguishes a captured association from legacy or ordinary lines.
     * @param item cart line
     * @return true when an association was captured
     */
    private static boolean hasLink(TicketState.TicketItem item) {
        return item.linkedArticleEan != null && !item.linkedArticleEan.isBlank();
    }

    /**
     * Finds only this principal's companion, never a separately entered line of the same EAN.
     * @param ticket cart
     * @param parentUid owning principal
     * @param ean companion article
     * @return matching companion, null when not yet admitted
     */
    private static TicketState.TicketItem findCompanion(TicketState ticket, String parentUid, String ean) {
        return ticket.items.stream().filter(item -> Objects.equals(parentUid, item.linkedParentUid)
                && ean.equals(item.ean)).findFirst().orElse(null);
    }

    /**
     * Collects the principal and every descendant for one authorized cancellation and reason.
     * @param ticket cart
     * @param uid principal identifier, null retained for the existing cancellation contract
     * @return stable identifiers of the entire family
     */
    public static List<String> familyUids(TicketState ticket, String uid) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(uid);
        boolean changed;
        do {
            changed = false;
            for (TicketState.TicketItem item : ticket.items) {
                if (item.linkedParentUid != null && ids.contains(item.linkedParentUid)) {
                    changed |= ids.add(item.uid);
                }
            }
        } while (changed);
        return new ArrayList<>(ids);
    }

    /** Cart values restored when any companion in the chain cannot be admitted. */
    private record LineSnapshot(TicketState.TicketItem item, BigDecimal quantity,
            BigDecimal valuedTotal, BigDecimal recordedQuantity) {
        /** @param item line whose pre-admission values must survive a refused companion */
        private LineSnapshot(TicketState.TicketItem item) {
            this(item, item.quantity, item.valuedTotal, item.linkedRecordedQuantity);
        }
        /** Restores this existing line without erasing the refusal shown by normal admission. */
        private void restore() {
            item.quantity = quantity;
            item.valuedTotal = valuedTotal;
            item.linkedRecordedQuantity = recordedQuantity;
        }
    }
}
