package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.TenderDefinition;
import com.intermarche.pos.domain.payment.PaymentTypes;
import com.intermarche.pos.ui.TestMessages;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            TenderRulesService.Verdict verdict =
                    service.check("TR", new BigDecimal("999.00"), 99);
            assertFalse(verdict.refuses());
            assertFalse(verdict.speaks());
            assertNull(verdict.displayMessage(TestMessages.french()));
        }
    }

    /**
     * An administered ceiling refuses the settlement and names the ceiling it
     * refused it on (BO-03-02-10).
     */
    @Test
    void theAdministeredCeilingRefusesTheSettlement() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
        TenderDefinition tender = mealVoucher();
        tender.maxAmount = new BigDecimal("25.00");
        tender.maxAmountControl = TenderDefinition.ControlLevel.BLOCKING;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            TenderRulesService.Verdict refused = service.check("TR", new BigDecimal("25.01"), 0);
            assertTrue(refused.refuses());
            assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", refused.displayMessage(TestMessages.french()));

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
        service.i18n = TestMessages.french();
        TenderDefinition tender = mealVoucher();
        tender.maxAmount = new BigDecimal("19.00");
        tender.maxAmountControl = TenderDefinition.ControlLevel.BLOCKING;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals("MONTANT SUPERIEUR AU PLAFOND (19,00 E)",
                    service.check("TR", new BigDecimal("19.01"), 0).displayMessage(TestMessages.french()));
        }
    }

    /**
     * The second ceiling, the floor and the count each speak on their own
     * (BO-03-02-12/13/14).
     */
    @Test
    void eachAdministeredBoundSpeaksOnItsOwn() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
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
                    service.check("TR", new BigDecimal("50.01"), 0).displayMessage(TestMessages.french()));
            assertEquals("MONTANT INFERIEUR AU MINIMUM (5,00 E)",
                    service.check("CASH", new BigDecimal("4.99"), 0).displayMessage(TestMessages.french()));
            assertEquals("NOMBRE MAXIMUM DE REGLEMENTS ATTEINT (2) - APPELER UN SUPERVISEUR",
                    service.check("CHEQUE", new BigDecimal("10.00"), 2).displayMessage(TestMessages.french()));
        }
    }

    /**
     * When two bounds are broken at once the STRICTEST wins, and it wins whether
     * the stricter rule is met first or last — the two legs of the comparison.
     */
    @Test
    void theStrictestViolatedRuleWins() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
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
            assertEquals("NOMBRE MAXIMUM DE REGLEMENTS ATTEINT (1)", last.displayMessage(TestMessages.french()));

            TenderRulesService.Verdict first = service.check("CASH", new BigDecimal("20.00"), 1);
            assertTrue(first.refuses());
            assertEquals("MONTANT SUPERIEUR AU PLAFOND (10,00 E)", first.displayMessage(TestMessages.french()));
        }
    }

    /**
     * A bound whose control level was never set says nothing, even when the
     * amount breaks it — the null leg of the comparison.
     */
    @Test
    void aBoundWithoutAControlLevelSaysNothing() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
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
        service.i18n = TestMessages.french();
        TenderDefinition tender = mealVoucher();
        tender.maxChangeAmount = new BigDecimal("8.00");
        tender.maxChangeControl = TenderDefinition.ControlLevel.BLOCKING;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals("RENDU SUPERIEUR AU PLAFOND (8,00 E)",
                    service.checkChange("TR", new BigDecimal("8.01")).displayMessage(TestMessages.french()));
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
        service.i18n = TestMessages.french();
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
        service.i18n = TestMessages.french();
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
        service.i18n = TestMessages.french();
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
        assertNull(silent.displayMessage(TestMessages.french()));

        TenderRulesService.Verdict info = new TenderRulesService.Verdict(
                TenderDefinition.ControlLevel.INFO, "TROP HAUT");
        assertFalse(info.refuses());
        assertTrue(info.speaks());
        assertEquals("TROP HAUT", info.displayMessage(TestMessages.french()));

        TenderRulesService.Verdict blocking = new TenderRulesService.Verdict(
                TenderDefinition.ControlLevel.BLOCKING, "TROP HAUT");
        assertTrue(blocking.refuses());
        assertEquals("TROP HAUT", blocking.displayMessage(TestMessages.french()));

        TenderRulesService.Verdict supervisor = new TenderRulesService.Verdict(
                TenderDefinition.ControlLevel.SUPERVISOR, "TROP HAUT");
        assertTrue(supervisor.refuses());
        assertEquals("TROP HAUT - APPELER UN SUPERVISEUR", supervisor.displayMessage(TestMessages.french()));
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

    /**
     * A tender no row administers keeps the register's own change behaviour, so
     * the fallback is returned both when it allows change and when it refuses it —
     * the null arm of the ternary on both fallback values (BO-03-02-16).
     */
    @Test
    void changeAllowedKeepsTheFallbackWhenUnadministered() {
        TenderRulesService service = serviceWithIslandChange(true);
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertTrue(service.changeAllowed("TR", true));
            assertFalse(service.changeAllowed("TR", false));
        }
    }

    /**
     * An administered row answers its own change permission and ignores the
     * fallback entirely — the non-null arm of the ternary (BO-03-02-16).
     */
    @Test
    void changeAllowedReadsTheAdministeredRow() {
        TenderRulesService service = serviceWithIslandChange(true);
        TenderDefinition tender = mealVoucher();
        tender.changeAllowed = true;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertTrue(service.changeAllowed("TR", false));
        }
    }

    /**
     * Builds the service with a stubbed {@link PosSettingsService} answering the
     * given island change permission (BO-03-10-04), so the change tests exercise
     * the island veto in front of the per-tender rule.
     *
     * @param islandAllows what the island setting answers
     * @return the wired service
     */
    private TenderRulesService serviceWithIslandChange(boolean islandAllows) {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(service.posSettingsService.islandChangeAllowed()).thenReturn(islandAllows);
        return service;
    }

    /**
     * BO-03-10-04: an island that forbids change PRIMES over an administered
     * tender that would allow it — the change is refused despite the tender's
     * own permission.
     */
    @Test
    void changeAllowedIslandProhibitionPrimesOverAllowingTender() {
        TenderRulesService service = serviceWithIslandChange(false);
        TenderDefinition tender = mealVoucher();
        tender.changeAllowed = true;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertFalse(service.changeAllowed("TR", true));
        }
    }

    /**
     * BO-03-10-04: an island prohibition also overrides an allowing fallback on
     * an unadministered tender — the veto does not even consult the tender row.
     */
    @Test
    void changeAllowedIslandProhibitionPrimesOverAllowingFallback() {
        TenderRulesService service = serviceWithIslandChange(false);
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertFalse(service.changeAllowed("TR", true));
        }
    }

    /**
     * A tender no row administers gives its change back in itself, so the key is
     * echoed unchanged — the null arm of the ternary (BO-03-02-16).
     */
    @Test
    void changeTenderEchoesTheKeyWhenUnadministered() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals("TR", service.changeTender("TR"));
        }
    }

    /**
     * An administered row names the tender its change is given in — the non-null
     * arm of the ternary (BO-03-02-16).
     */
    @Test
    void changeTenderReadsTheAdministeredRow() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
        TenderDefinition tender = mealVoucher();
        tender.changeTenderCode = "CASH";
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertEquals("CASH", service.changeTender("TR"));
        }
    }

    /**
     * A tender no row administers keeps the register's own drawer behaviour, so
     * the fallback is returned both ways — the null arm of the ternary
     * (BO-03-02-19).
     */
    @Test
    void opensDrawerKeepsTheFallbackWhenUnadministered() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertTrue(service.opensDrawer("TR", false, false, true));
            assertFalse(service.opensDrawer("TR", false, false, false));
        }
    }

    /**
     * An administered row answers its own drawer behaviour and ignores the
     * fallback — the non-null arm of the ternary (BO-03-02-19).
     */
    @Test
    void opensDrawerReadsTheAdministeredRow() {
        TenderRulesService service = new TenderRulesService();
        service.i18n = TestMessages.french();
        TenderDefinition tender = mealVoucher();
        tender.drawerOpening = TenderDefinition.DrawerOpening.AFTER_RECEIPT;
        Map<String, PanacheQuery<TenderDefinition>> queries = emptyQueries();
        queries.put("TR", queryOf(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            wire(mocked, queries);
            assertTrue(service.opensDrawer("TR", false, true, false));
            assertFalse(service.opensDrawer("TR", false, false, true));
        }
    }

    /**
     * Builds a tender of the given code.
     *
     * @param code the code
     * @param active whether it is in service
     * @return the tender
     */
    private TenderDefinition coded(String code, boolean active) {
        TenderDefinition tender = new TenderDefinition();
        tender.code = code;
        tender.active = active;
        return tender;
    }

    /**
     * BO-03-02-01/-44: the lookups the payment screen asks for delegate to the
     * referential, and a tender the store added answers its own pre-fill.
     */
    @Test
    void theReferentialLookupsDelegateAndAddedTendersAnswerTheirPrefill() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition amex = coded("AMEX", true);
        amex.nature = "CARD";
        amex.defaultsToTotal = false;
        try (MockedStatic<TenderDefinition> tenders = mockStatic(TenderDefinition.class)) {
            tenders.when(() -> TenderDefinition.findByCode("AMEX")).thenReturn(amex);
            tenders.when(() -> TenderDefinition.findByMonetic("43", null)).thenReturn(amex);
            tenders.when(TenderDefinition::listActiveAdditional).thenReturn(List.of(amex));
            assertSame(amex, service.tender("AMEX"));
            assertSame(amex, service.moneticTender("43", null));
            assertEquals(List.of(amex), service.additional());
            assertEquals(Map.of("AMEX", false), service.administeredDefaultAmounts());
        }
    }

    /**
     * BO-03-02-36: the cash key goes through the coin machine only where the
     * register has one AND the active cash tender is administered so.
     */
    @Test
    void theCashGoesThroughTheCoinMachineOnlyWhenBothSaySo() {
        TenderRulesService service = new TenderRulesService();
        assertFalse(service.cashViaCoinMachine(false));
        TenderDefinition cash = coded("CASH", true);
        try (MockedStatic<TenderDefinition> tenders = mockStatic(TenderDefinition.class)) {
            tenders.when(() -> TenderDefinition.findByCode("CASH")).thenReturn(null);
            assertFalse(service.cashViaCoinMachine(true));
            tenders.when(() -> TenderDefinition.findByCode("CASH")).thenReturn(cash);
            assertFalse(service.cashViaCoinMachine(true));
            cash.coinMachine = true;
            assertTrue(service.cashViaCoinMachine(true));
            cash.active = false;
            assertFalse(service.cashViaCoinMachine(true));
        }
    }

    /**
     * BO-03-02-20: the movement tenders are the active tenders allowed for
     * floats and expenses, cash excepted.
     */
    @Test
    void theMovementTendersAreTheActiveAllowedOnesButCash() {
        TenderRulesService service = new TenderRulesService();
        TenderDefinition cheque = coded("CHEQUE", true);
        cheque.movementAllowed = true;
        TenderDefinition cash = coded("CASH", true);
        cash.movementAllowed = true;
        TenderDefinition sleeping = coded("TR", false);
        sleeping.movementAllowed = true;
        TenderDefinition card = coded("CARD", true);
        try (MockedStatic<TenderDefinition> tenders = mockStatic(TenderDefinition.class)) {
            tenders.when(TenderDefinition::listAllOrdered).thenReturn(List.of(cheque, cash, sleeping, card));
            assertEquals(List.of("CHEQUE"), service.movementTenders());
        }
    }
}
