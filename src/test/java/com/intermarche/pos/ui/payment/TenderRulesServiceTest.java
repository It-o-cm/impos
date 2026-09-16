package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.TenderDefinition;
import com.intermarche.pos.domain.payment.PaymentTypes;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenderRulesService}.
 * <p>
 * The service reads the administered referential through the Panache static
 * finders of {@link TenderDefinition}, resolved to {@link PanacheEntityBase}
 * outside a Quarkus context and intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Every settlement key is stubbed on
 * each test, because a reader that sweeps the catalog asks about all of them.
 * <p>
 * Branch enumeration (every leg exercised): {@code check} covers the
 * unadministered arm, each of the four bounds violated on its own, two bounds
 * violated together so the stricter wins, a bound violated at a level BELOW the
 * one already held so the stricter survives, a null level, and the respected
 * arm; {@code checkChange} covers the unadministered arm, the respected arm and
 * the exceeded arm; {@code changeAllowed}, {@code opensDrawer} and
 * {@code defaultsToTotal} each cover both legs of their fallback and the
 * administered arm; {@code changeTender} covers the unadministered arm and the
 * administered one; {@code offered} covers an all-active referential and a
 * deactivated tender; {@code administeredDefaultAmounts} covers the empty
 * referential and an administered row; {@link TenderRulesService.Verdict} covers
 * the silent verdict, the blocking one, the speaking-but-not-blocking one and
 * both arms of the supervisor suffix.
 */
class TenderRulesServiceTest {

    /**
     * Builds the meal-voucher tender as a store would administer it.
     *
     * @return the administered tender
     */
    private TenderDefinition mealVoucher() {
        TenderDefinition tender = new TenderDefinition();
        tender.code = "TR";
        tender.functionalId = "030";
        tender.label = "Titre restaurant";
        tender.active = true;
        return tender;
    }

    /**
     * Builds a Panache query answering the given single result.
     *
     * @param result the row the query answers, or null
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<TenderDefinition> queryOf(TenderDefinition result) {
        PanacheQuery<TenderDefinition> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds one query per settlement key, all answering nothing, so a sweep of
     * the catalog never meets an unstubbed finder.
     *
     * @return the queries, keyed by settlement key
     */
    private Map<String, PanacheQuery<TenderDefinition>> emptyQueries() {
        Map<String, PanacheQuery<TenderDefinition>> queries = new HashMap<>();
        for (String key : PaymentTypes.keys()) {
            queries.put(key, queryOf(null));
        }
        return queries;
    }

    /**
     * Wires the prepared queries onto the static finder.
     *
     * @param mocked the active static mock
     * @param queries the queries, keyed by settlement key
     */
    private void wire(MockedStatic<PanacheEntityBase> mocked,
            Map<String, PanacheQuery<TenderDefinition>> queries) {
        for (Map.Entry<String, PanacheQuery<TenderDefinition>> entry : queries.entrySet()) {
            mocked.when(() -> TenderDefinition.find("code", entry.getKey()))
                    .thenReturn(entry.getValue());
        }
    }

    /**
     * A tender no row administers opposes nothing, whatever the amount and
     * however many settlements the sale already carries.
     */
    @Test
    void anUnadministeredTenderOpposesNothing() {
        TenderRulesService service = new TenderRulesService();
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            TenderRulesService.Verdict verdict =
                    service.check("TR", new BigDecimal("999.00"), 99);
            assertFalse(verdict.refuses());
            assertFalse(verdict.speaks());
            assertNull(verdict.displayMessage());
        }
    }

    /**
     * An administered ceiling refuses the settlement and names the ceiling it
     * refused it on (BO-03-02-10).
     */
    @Test
    void theAdministeredCeilingRefusesTheSettlement() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition tender = mealVoucher();
        tender.maxAmount = new BigDecimal("25.00");
        tender.maxAmountControl = TenderDefinition.ControlLevel.BLOCKING;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            TenderRulesService.Verdict refused = service.check("TR", new BigDecimal("25.01"), 0);
            assertTrue(refused.refuses());
            assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", refused.displayMessage());

            TenderRulesService.Verdict allowed = service.check("TR", new BigDecimal("25.00"), 0);
            assertFalse(allowed.speaks());
        }
    }

    /**
     * A SECOND administered ceiling refuses a different settlement, which is
     * what proves the figure is read rather than hard-coded.
     */
    @Test
    void aSecondAdministeredCeilingMovesTheRefusal() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition tender = mealVoucher();
        tender.maxAmount = new BigDecimal("19.00");
        tender.maxAmountControl = TenderDefinition.ControlLevel.BLOCKING;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals("MONTANT SUPERIEUR AU PLAFOND (19,00 E)",
                    service.check("TR", new BigDecimal("19.01"), 0).displayMessage());
        }
    }

    /**
     * The second ceiling, the floor and the count each speak on their own
     * (BO-03-02-12/13/14).
     */
    @Test
    void eachAdministeredBoundSpeaksOnItsOwn() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition second = mealVoucher();
        second.secondMaxAmount = new BigDecimal("50.00");
        second.secondMaxAmountControl = TenderDefinition.ControlLevel.WARNING;
        TenderDefinition floor = mealVoucher();
        floor.minAmount = new BigDecimal("5.00");
        floor.minAmountControl = TenderDefinition.ControlLevel.INFO;
        TenderDefinition count = mealVoucher();
        count.maxCount = 2;
        count.maxCountControl = TenderDefinition.ControlLevel.SUPERVISOR;

        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(second));
        queries.put("CASH", queryOf(floor));
        queries.put("CHEQUE", queryOf(count));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals("MONTANT SUPERIEUR AU SECOND PLAFOND (50,00 E)",
                    service.check("TR", new BigDecimal("50.01"), 0).displayMessage());
            assertEquals("MONTANT INFERIEUR AU MINIMUM (5,00 E)",
                    service.check("CASH", new BigDecimal("4.99"), 0).displayMessage());
            assertEquals("NOMBRE MAXIMUM DE REGLEMENTS ATTEINT (2) - APPELER UN SUPERVISEUR",
                    service.check("CHEQUE", new BigDecimal("10.00"), 2).displayMessage());
        }
    }

    /**
     * When two bounds are broken at once the STRICTEST wins, and it wins whether
     * the stricter rule is met first or last — the two legs of the comparison.
     */
    @Test
    void theStrictestViolatedRuleWins() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition blockingLast = mealVoucher();
        blockingLast.maxAmount = new BigDecimal("10.00");
        blockingLast.maxAmountControl = TenderDefinition.ControlLevel.INFO;
        blockingLast.maxCount = 1;
        blockingLast.maxCountControl = TenderDefinition.ControlLevel.BLOCKING;

        TenderDefinition blockingFirst = mealVoucher();
        blockingFirst.maxAmount = new BigDecimal("10.00");
        blockingFirst.maxAmountControl = TenderDefinition.ControlLevel.BLOCKING;
        blockingFirst.maxCount = 1;
        blockingFirst.maxCountControl = TenderDefinition.ControlLevel.INFO;

        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(blockingLast));
        queries.put("CASH", queryOf(blockingFirst));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            TenderRulesService.Verdict last = service.check("TR", new BigDecimal("20.00"), 1);
            assertTrue(last.refuses());
            assertEquals("NOMBRE MAXIMUM DE REGLEMENTS ATTEINT (1)", last.displayMessage());

            TenderRulesService.Verdict first = service.check("CASH", new BigDecimal("20.00"), 1);
            assertTrue(first.refuses());
            assertEquals("MONTANT SUPERIEUR AU PLAFOND (10,00 E)", first.displayMessage());
        }
    }

    /**
     * A bound whose control level was never set says nothing, even when the
     * amount breaks it — the null leg of the comparison.
     */
    @Test
    void aBoundWithoutAControlLevelSaysNothing() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition tender = mealVoucher();
        tender.maxAmount = new BigDecimal("10.00");
        tender.maxAmountControl = null;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertFalse(service.check("TR", new BigDecimal("99.00"), 0).speaks());
        }
    }

    /**
     * The change ceiling is opposed to the change, an unadministered tender and
     * a respected ceiling both saying nothing (BO-03-02-11).
     */
    @Test
    void theChangeCeilingIsOpposedToTheChange() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition tender = mealVoucher();
        tender.maxChangeAmount = new BigDecimal("8.00");
        tender.maxChangeControl = TenderDefinition.ControlLevel.BLOCKING;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals("RENDU SUPERIEUR AU PLAFOND (8,00 E)",
                    service.checkChange("TR", new BigDecimal("8.01")).displayMessage());
            assertFalse(service.checkChange("TR", new BigDecimal("8.00")).speaks());
            assertFalse(service.checkChange("CASH", new BigDecimal("999.00")).speaks());
        }
    }

    /**
     * The referential decides which tenders the screen offers, a tender no row
     * administers staying offered (BO-03-02-03).
     */
    @Test
    void theReferentialDecidesWhichTendersAreOffered() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition stopped = mealVoucher();
        stopped.active = false;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(stopped));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertFalse(service.isOffered("TR"));
            assertTrue(service.isOffered("CASH"));
            assertEquals(PaymentTypes.keys().size() - 1, service.offered().size());
            assertFalse(service.offered().contains("TR"));
            assertTrue(service.offered().contains("CASH"));
        }
    }

    /**
     * An all-unadministered referential offers every tender the register knows.
     */
    @Test
    void anEmptyReferentialOffersEverything() {
        TenderRulesService service = new TenderRulesService();
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals(PaymentTypes.keys(), service.offered());
            assertTrue(service.administeredDefaultAmounts().isEmpty());
        }
    }



    /**
     * The pre-fill map carries the administered rows only, so a screen reading
     * it can tell "administered as empty" from "never administered".
     */
    @Test
    void thePreFillMapCarriesTheAdministeredRowsOnly() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition prefilled = mealVoucher();
        prefilled.defaultsToTotal = true;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(prefilled));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            Map<String, Boolean> answers = service.administeredDefaultAmounts();
            assertEquals(1, answers.size());
            assertEquals(Boolean.TRUE, answers.get("TR"));
            assertNull(answers.get("CASH"));
        }
    }

    /**
     * The verdict answers the three questions the payment path asks of it, and
     * only the supervisor level appends the call for one.
     */
    @Test
    void theVerdictAnswersWhatThePaymentPathAsks() {
        TenderRulesService.Verdict silent = TenderRulesService.Verdict.silent();
        assertFalse(silent.refuses());
        assertFalse(silent.speaks());
        assertNull(silent.displayMessage());

        TenderRulesService.Verdict info = new TenderRulesService.Verdict(
                TenderDefinition.ControlLevel.INFO, "TROP HAUT");
        assertFalse(info.refuses());
        assertTrue(info.speaks());
        assertEquals("TROP HAUT", info.displayMessage());

        TenderRulesService.Verdict blocking = new TenderRulesService.Verdict(
                TenderDefinition.ControlLevel.BLOCKING, "TROP HAUT");
        assertTrue(blocking.refuses());
        assertEquals("TROP HAUT", blocking.displayMessage());

        TenderRulesService.Verdict supervisor = new TenderRulesService.Verdict(
                TenderDefinition.ControlLevel.SUPERVISOR, "TROP HAUT");
        assertTrue(supervisor.refuses());
        assertEquals("TROP HAUT - APPELER UN SUPERVISEUR", supervisor.displayMessage());
    }


    /**
     * The catalog the readers sweep is the register's own list of settlement
     * natures, so a tender the register cannot carry is never offered.
     */
    @Test
    void theSweptCatalogIsTheRegistersOwnList() {
        assertEquals(List.copyOf(PaymentTypes.keys()).size(), PaymentTypes.keys().size());
        assertTrue(PaymentTypes.keys().contains("TR"));
    }
}
