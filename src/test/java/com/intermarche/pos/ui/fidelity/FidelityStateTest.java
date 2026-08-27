package com.intermarche.pos.ui.fidelity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FidelityState}.
 * <p>
 * A pure in-memory holder with no collaborator: every test builds a fresh
 * instance and asserts absolute values. Three behaviours carry meaning beyond
 * their code — the attachment guard (a length check, NOT a format check: the
 * format lives in the scan handler's pattern), the last-presented-card-wins
 * replacement, and the separation between {@code clear()} (the card leaves
 * with the ticket) and {@code clearEarn()} (only the projection is hidden,
 * the card stays). Both arms of every guard are covered.
 */
class FidelityStateTest {

    /**
     * A fresh state carries no card and no projection — the state a sale must
     * begin from.
     */
    @Test
    void startsPristine() {
        FidelityState fid = new FidelityState();
        assertFalse(fid.active);
        assertEquals("", fid.label);
        assertNull(fid.earnTotal);
        assertNull(fid.burnableBase);
        assertTrue(fid.earnEntries.isEmpty());
        assertNull(fid.lastValuationRequestJson);
        assertNull(fid.lastValuationResponseJson);
    }

    /**
     * A real card number attaches and switches the state ACTIVE — the flag
     * that enables the CAGNOTTE payment button.
     */
    @Test
    void assignCardAttachesRealNumber() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        assertTrue(fid.active);
        assertEquals("2990000000019", fid.label);
    }

    /**
     * A NULL card is silently ignored (first leg of the guard): an empty
     * submit must not attach anything.
     */
    @Test
    void assignCardIgnoresNull() {
        FidelityState fid = new FidelityState();
        fid.assignCard(null);
        assertFalse(fid.active);
        assertEquals("", fid.label);
    }

    /**
     * A TOO-SHORT value is ignored (second leg): the guard is a length floor
     * against empty submits, not a format check — the discriminant pattern
     * lives in the scan handler.
     */
    @Test
    void assignCardIgnoresTooShortValue() {
        FidelityState fid = new FidelityState();
        fid.assignCard("12");
        assertFalse(fid.active);
        assertEquals("", fid.label);
    }

    /**
     * Three characters ARE accepted — the boundary of {@code length() > 2}.
     */
    @Test
    void assignCardAcceptsThreeCharacters() {
        FidelityState fid = new FidelityState();
        fid.assignCard("123");
        assertTrue(fid.active);
        assertEquals("123", fid.label);
    }

    /**
     * A second card REPLACES the first: last presented wins, and there is
     * never more than one card on a ticket.
     */
    @Test
    void assignCardLastPresentedWins() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.assignCard("2990000000040");
        assertEquals("2990000000040", fid.label);
        assertTrue(fid.active);
    }

    /**
     * A rejected card leaves a previously attached one UNTOUCHED: a stray
     * empty submit must not detach the customer's card.
     */
    @Test
    void assignCardRejectionKeepsThePreviousCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.assignCard("");
        assertTrue(fid.active);
        assertEquals("2990000000019", fid.label);
    }

    /**
     * {@code clearEarn()} hides the projection but KEEPS the card: this is
     * the degraded-imfid path — the sale goes on with its card attached, only
     * the advantage badge disappears.
     */
    @Test
    void clearEarnHidesProjectionButKeepsTheCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.earnTotal = new BigDecimal("1.03");
        fid.burnableBase = new BigDecimal("47.11");
        fid.earnEntries.add(new FidelityState.EarnLine("SOCLE", "Cagnotte socle", BigDecimal.ONE));

        fid.clearEarn();

        assertNull(fid.earnTotal);
        assertNull(fid.burnableBase);
        assertTrue(fid.earnEntries.isEmpty());
        assertTrue(fid.active);
        assertEquals("2990000000019", fid.label);
    }

    /**
     * {@code clearEarn()} replaces the entry list rather than emptying it in
     * place, so a snapshot handed out earlier is never mutated behind the
     * caller's back.
     */
    @Test
    void clearEarnReplacesTheEntryList() {
        FidelityState fid = new FidelityState();
        fid.earnEntries.add(new FidelityState.EarnLine("SOCLE", "Cagnotte socle", BigDecimal.ONE));
        java.util.List<FidelityState.EarnLine> snapshot = fid.earnEntries;

        fid.clearEarn();

        assertEquals(1, snapshot.size());
        assertTrue(fid.earnEntries.isEmpty());
    }

    /**
     * {@code clear()} detaches the card — called when the ticket is cleared.
     */
    @Test
    void clearDetachesTheCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.clear();
        assertFalse(fid.active);
        assertEquals("", fid.label);
    }

    /**
     * {@code clear()} does NOT clear the projection: the two concerns are
     * separate, and the end-of-sale broom is what runs both.
     */
    @Test
    void clearLeavesTheProjectionToClearEarn() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.earnTotal = new BigDecimal("1.03");
        fid.clear();
        assertEquals(0, new BigDecimal("1.03").compareTo(fid.earnTotal));
    }

    /**
     * An earn line carries the three fields the printed ticket needs: the
     * rule code (traced at close), the label (printed) and the amount.
     */
    @Test
    void earnLineCarriesCodeLabelAndAmount() {
        FidelityState.EarnLine line =
                new FidelityState.EarnLine("F&L-SAM", "Fruits & légumes samedi", new BigDecimal("0.75"));
        assertEquals("F&L-SAM", line.ruleCode);
        assertEquals("Fruits & légumes samedi", line.label);
        assertEquals(0, new BigDecimal("0.75").compareTo(line.amount));
    }

    /**
     * Re-assigning a card PURGES the previous holder identity, account status
     * and available balance: a scanned card must never inherit the former
     * holder's name or balance (the pseudonymity doctrine).
     */
    @Test
    void assignCardPurgesHolderStatusAndBalance() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.holderLastName = "Dupont";
        fid.holderFirstName = "Jean";
        fid.accountStatus = "ACTIVE";
        fid.availableBalance = new BigDecimal("12.00");
        fid.assignCard("2990000000040");
        assertEquals("2990000000040", fid.label);
        assertNull(fid.holderLastName);
        assertNull(fid.holderFirstName);
        assertNull(fid.accountStatus);
        assertNull(fid.availableBalance);
    }

    /**
     * {@code getDisplaySummary} returns null when no card is attached
     * (inactive arm).
     */
    @Test
    void displaySummaryIsNullWithoutCard() {
        assertNull(new FidelityState().getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} shows the bare card number when no holder and
     * no balance are known (holder-null / balance-null arms).
     */
    @Test
    void displaySummaryShowsBareCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        assertEquals("2990000000019", fid.getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} prefixes the upper-cased holder and first name
     * when both are known (holder-non-null / first-name-present arms).
     */
    @Test
    void displaySummaryShowsHolderAndFirstName() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.holderLastName = "Dupont";
        fid.holderFirstName = "Jean";
        assertEquals("DUPONT Jean · 2990000000019", fid.getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} omits a blank first name (first-name-blank
     * arm), showing only the upper-cased holder before the card.
     */
    @Test
    void displaySummaryOmitsBlankFirstName() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.holderLastName = "Dupont";
        fid.holderFirstName = "  ";
        assertEquals("DUPONT · 2990000000019", fid.getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} appends the French-formatted available balance
     * when known (balance-non-null arm).
     */
    @Test
    void displaySummaryAppendsBalance() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.availableBalance = new BigDecimal("12.5");
        assertEquals("2990000000019 · 12,50 €", fid.getDisplaySummary());
    }
}
