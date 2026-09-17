package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the tenders the drawer offers to a withdrawal or a transfer
 * ({@code LC-12-03-02/03/06}, {@code LC-12-10-02}).
 *
 * <p>Everything here is a reading of an administered string, and every way a shop can
 * mistype one is exercised: a pair without a colon, a pair with two, an empty key, an
 * empty label, the same key twice, and the whole setting left blank. The fallback of
 * the transfer list on the withdrawal list is exercised on both its arms, since a shop
 * that names its tenders once should not have to name them twice — and a shop that
 * names them twice must be obeyed.
 *
 * <p>The settings and the theoreticals are hand-written stand-ins rather than mocks, so
 * the whole class runs without Mockito and without a database.
 */
class DrawerMethodServiceTest {

    /** Settings whose administered lists each test sets directly. */
    private static class FakeSettings extends PosSettingsService {

        /** The administered withdrawal list. */
        private String withdrawalMethods = "CASH:Espèces;CHEQUE:Chèques";

        /** The administered transfer list. */
        private String transferMethods = "";

        /** {@inheritDoc} */
        @Override
        public String drawerWithdrawalMethods() {
            return withdrawalMethods;
        }

        /** {@inheritDoc} */
        @Override
        public String drawerTransferMethods() {
            return transferMethods;
        }
    }

    /** A service whose theoreticals are stated by the test, not read from a session. */
    private static class TestService extends DrawerMethodService {

        /** The theoretical amount of each tender. */
        private Map<String, BigDecimal> totals = Map.of();

        /** How many transactions took each tender in. */
        private Map<String, Integer> counts = Map.of();

        /** {@inheritDoc} */
        @Override
        public Map<String, BigDecimal> theoreticalByMethod() {
            return totals;
        }

        /** {@inheritDoc} */
        @Override
        public Map<String, Integer> countByMethod() {
            return counts;
        }
    }

    /** The service under test. */
    private TestService service;

    /** The stand-in settings. */
    private FakeSettings settings;

    /**
     * Wires a fresh service to fresh settings before each test.
     */
    @BeforeEach
    void setUp() {
        service = new TestService();
        settings = new FakeSettings();
        service.posSettingsService = settings;
    }

    /**
     * The withdrawal list is read in administered order, each tender carrying the
     * transaction count and the theoretical the register states.
     */
    @Test
    void withdrawableReadsTheAdministeredListInOrder() {
        service.totals = Map.of("CASH", new BigDecimal("120.50"), "CHEQUE", new BigDecimal("80.00"));
        service.counts = Map.of("CASH", 7, "CHEQUE", 2);
        List<DrawerMethodService.DrawerMethod> methods = service.withdrawable();
        assertEquals(2, methods.size());
        assertEquals("CASH", methods.get(0).key());
        assertEquals("Espèces", methods.get(0).label());
        assertEquals(7, methods.get(0).count());
        assertEquals(new BigDecimal("120.50"), methods.get(0).amount());
        assertEquals("CHEQUE", methods.get(1).key());
        assertEquals(2, methods.get(1).count());
    }

    /**
     * A tender the drawer took nothing in with is still offered, at zero and zero: the
     * list says what may be withdrawn, not what happens to be there.
     */
    @Test
    void anUnusedTenderIsOfferedAtZero() {
        service.totals = Map.of();
        service.counts = Map.of();
        List<DrawerMethodService.DrawerMethod> methods = service.withdrawable();
        assertEquals(2, methods.size());
        assertEquals(0, methods.get(0).count());
        assertEquals(BigDecimal.ZERO, methods.get(0).amount());
    }

    /**
     * A blank setting offers no tender at all rather than inventing one.
     */
    @Test
    void aBlankListOffersNothing() {
        settings.withdrawalMethods = "";
        assertTrue(service.withdrawable().isEmpty());
    }

    /**
     * A null setting offers no tender either — the null arm of the reading.
     */
    @Test
    void aNullListOffersNothing() {
        settings.withdrawalMethods = null;
        assertTrue(service.withdrawable().isEmpty());
    }

    /**
     * An entry without a colon is dropped: it names no label, so nothing could be
     * shown for it.
     */
    @Test
    void anEntryWithoutASeparatorIsDropped() {
        settings.withdrawalMethods = "CASH:Espèces;CHEQUE";
        List<DrawerMethodService.DrawerMethod> methods = service.withdrawable();
        assertEquals(1, methods.size());
        assertEquals("CASH", methods.get(0).key());
    }

    /**
     * An entry with two colons is dropped: the reading takes a key and a label, and a
     * third part means the shop meant a syntax this setting does not have.
     */
    @Test
    void anEntryWithTwoSeparatorsIsDropped() {
        settings.withdrawalMethods = "CASH:Espèces:extra;CHEQUE:Chèques";
        List<DrawerMethodService.DrawerMethod> methods = service.withdrawable();
        assertEquals(1, methods.size());
        assertEquals("CHEQUE", methods.get(0).key());
    }

    /**
     * An entry whose key is empty is dropped: no movement could name that tender.
     */
    @Test
    void anEntryWithoutAKeyIsDropped() {
        settings.withdrawalMethods = " :Espèces;CHEQUE:Chèques";
        List<DrawerMethodService.DrawerMethod> methods = service.withdrawable();
        assertEquals(1, methods.size());
        assertEquals("CHEQUE", methods.get(0).key());
    }

    /**
     * An entry whose label is empty is dropped: an unnamed key on a screen is a button
     * the cashier cannot read.
     */
    @Test
    void anEntryWithoutALabelIsDropped() {
        settings.withdrawalMethods = "CASH: ;CHEQUE:Chèques";
        List<DrawerMethodService.DrawerMethod> methods = service.withdrawable();
        assertEquals(1, methods.size());
        assertEquals("CHEQUE", methods.get(0).key());
    }

    /**
     * The same key named twice is offered once, the first wording winning: two rows
     * that withdraw the same tender would let a cashier withdraw it twice.
     */
    @Test
    void aRepeatedKeyIsOfferedOnce() {
        settings.withdrawalMethods = "CASH:Espèces;CASH:Liquide";
        List<DrawerMethodService.DrawerMethod> methods = service.withdrawable();
        assertEquals(1, methods.size());
        assertEquals("Espèces", methods.get(0).label());
    }

    /**
     * A key is read case-insensitively and kept upper-case, so a shop that typed it in
     * lower case still names the tender the movements record.
     */
    @Test
    void aKeyIsNormalizedToUpperCase() {
        settings.withdrawalMethods = "cheque:Chèques";
        assertEquals("CHEQUE", service.withdrawable().get(0).key());
    }

    /**
     * An unset transfer list falls back on the withdrawal one — the fallback arm.
     */
    @Test
    void anUnsetTransferListFallsBackOnTheWithdrawalOne() {
        settings.transferMethods = "";
        List<DrawerMethodService.DrawerMethod> methods = service.transferable();
        assertEquals(2, methods.size());
        assertEquals("CASH", methods.get(0).key());
    }

    /**
     * A transfer list the shop did name is obeyed — the non-fallback arm.
     */
    @Test
    void anAdministeredTransferListWins() {
        settings.transferMethods = "TR:Titres-restaurant";
        List<DrawerMethodService.DrawerMethod> methods = service.transferable();
        assertEquals(1, methods.size());
        assertEquals("TR", methods.get(0).key());
    }

    /**
     * A transfer list made only of unusable entries falls back too: what the shop
     * typed names no tender, so the withdrawal list is all there is to offer.
     */
    @Test
    void anUnusableTransferListFallsBackToo() {
        settings.transferMethods = "garbage";
        assertEquals(2, service.transferable().size());
    }

    /**
     * A tender the shop administered is withdrawable; one it did not is not.
     */
    @Test
    void isWithdrawableAnswersOnBothArms() {
        assertTrue(service.isWithdrawable("CHEQUE"));
        assertFalse(service.isWithdrawable("TR"));
    }

    /**
     * A tender the shop administered for transfers is transferable; one it did not is
     * not, even when the withdrawal list names it.
     */
    @Test
    void isTransferableAnswersOnBothArms() {
        settings.transferMethods = "TR:Titres-restaurant";
        assertTrue(service.isTransferable("TR"));
        assertFalse(service.isTransferable("CASH"));
    }

    /**
     * A wording is looked up in the transfer list first, then in the withdrawal one,
     * and an unknown key stands for itself rather than showing nothing.
     */
    @Test
    void labelOfWalksBothListsThenGivesTheKeyBack() {
        settings.transferMethods = "TR:Titres-restaurant";
        assertEquals("Titres-restaurant", service.labelOf("TR"));
        assertEquals("Chèques", service.labelOf("CHEQUE"));
        assertEquals("UNKNOWN", service.labelOf("UNKNOWN"));
    }

    /**
     * The theoretical of a tender is what the register believes the drawer holds, and
     * a tender it holds none of answers zero rather than nothing.
     */
    @Test
    void theoreticalOfReadsTheSessionTotals() {
        service.totals = Map.of("CHEQUE", new BigDecimal("80.00"));
        assertEquals(new BigDecimal("80.00"), service.theoreticalOf("CHEQUE"));
        assertEquals(BigDecimal.ZERO, service.theoreticalOf("TR"));
    }

    /**
     * A missing tender key reads as the cash, on both the null and the blank arms: a
     * movement that names no tender took cash out of the drawer.
     */
    @Test
    void aMissingTenderKeyReadsAsCash() {
        service.totals = Map.of(CashMovement.CASH, new BigDecimal("42.00"));
        assertEquals(new BigDecimal("42.00"), service.theoreticalOf(null));
        assertEquals(new BigDecimal("42.00"), service.theoreticalOf("  "));
    }

    /**
     * The theoretical is shown in French format, two decimals and a comma, whatever
     * scale the total arrived with.
     */
    @Test
    void theAmountIsFormattedForTheScreen() {
        assertEquals("120,50", new DrawerMethodService.DrawerMethod(
                "CASH", "Espèces", 1, new BigDecimal("120.5")).getAmountFormatted());
        assertEquals("0,00", new DrawerMethodService.DrawerMethod(
                "CASH", "Espèces", 0, BigDecimal.ZERO).getAmountFormatted());
        assertEquals("1,24", new DrawerMethodService.DrawerMethod(
                "CASH", "Espèces", 1, new BigDecimal("1.235")).getAmountFormatted());
    }

    /**
     * Builds a tender row of the referential.
     *
     * @param code the tender code
     * @param source whether an amount may be taken out of it
     * @param destination whether an amount may be moved into it
     * @return the row
     */
    private com.intermarche.pos.domain.payment.TenderDefinition tender(String code,
            boolean source, boolean destination) {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = code;
        tender.transferSource = source;
        tender.transferDestination = destination;
        return tender;
    }

    /**
     * Stubs the referential lookup of one tender code.
     *
     * @param panache the active PanacheEntityBase mock
     * @param code the code looked up
     * @param found the row to resolve, null when the referential does not know it
     */
    @SuppressWarnings("unchecked")
    private void stubTender(org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache
            .PanacheEntityBase> panache, String code,
            com.intermarche.pos.domain.payment.TenderDefinition found) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<
                com.intermarche.pos.domain.payment.TenderDefinition> query =
                org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        org.mockito.Mockito.when(query.firstResult()).thenReturn(found);
        panache.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                .find("code", code)).thenReturn(query);
    }

    /**
     * A tender the referential FORBIDS as a source is dropped from the source
     * list and kept in the destination one ({@code BO-05-02-17}) — the two
     * flags are two rights, and this is the case that proves it.
     */
    @Test
    void asourceTheReferentialForbidsIsDroppedFromTheSourceListAlone() {
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                org.mockito.Mockito.mockStatic(
                        io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubTender(panache, "CASH", tender("CASH", false, true));
            stubTender(panache, "CHEQUE", tender("CHEQUE", true, true));
            List<DrawerMethodService.DrawerMethod> sources = service.transferSources();
            assertEquals(1, sources.size());
            assertEquals("CHEQUE", sources.get(0).key());
            assertEquals(2, service.transferDestinations().size());
            assertFalse(service.isTransferSource("CASH"));
            assertTrue(service.isTransferDestination("CASH"));
        }
    }

    /**
     * A tender the referential FORBIDS as a destination is dropped from the
     * destination list and kept in the source one — the mirror case.
     */
    @Test
    void adestinationTheReferentialForbidsIsDroppedFromTheDestinationListAlone() {
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                org.mockito.Mockito.mockStatic(
                        io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubTender(panache, "CASH", tender("CASH", true, false));
            stubTender(panache, "CHEQUE", tender("CHEQUE", true, true));
            List<DrawerMethodService.DrawerMethod> destinations = service.transferDestinations();
            assertEquals(1, destinations.size());
            assertEquals("CHEQUE", destinations.get(0).key());
            assertEquals(2, service.transferSources().size());
            assertTrue(service.isTransferSource("CASH"));
            assertFalse(service.isTransferDestination("CASH"));
        }
    }

    /**
     * A tender the REFERENTIAL DOES NOT KNOW is allowed on both ends: the
     * administered transfer list is what a shop named on purpose, and a
     * referential row that was never opened must not quietly cancel it.
     */
    @Test
    void atenderTheReferentialDoesNotKnowIsAllowedOnBothEnds() {
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                org.mockito.Mockito.mockStatic(
                        io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubTender(panache, "CASH", null);
            stubTender(panache, "CHEQUE", null);
            assertEquals(2, service.transferSources().size());
            assertEquals(2, service.transferDestinations().size());
            assertTrue(service.isTransferSource("CASH"));
            assertTrue(service.isTransferDestination("CHEQUE"));
        }
    }

    /**
     * A tender OUTSIDE the administered transfer list is refused at both ends
     * whatever the referential says: the first leg of each compound guard,
     * which the flags alone would never reach.
     */
    @Test
    void atenderOutsideTheAdministeredListIsRefusedWhateverTheReferentialSays() {
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                org.mockito.Mockito.mockStatic(
                        io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubTender(panache, "TR", tender("TR", true, true));
            assertFalse(service.isTransferSource("TR"));
            assertFalse(service.isTransferDestination("TR"));
        }
    }
}
