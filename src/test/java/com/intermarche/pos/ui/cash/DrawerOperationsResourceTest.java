package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two drawer gestures ({@code LC-12-03} withdrawal and
 * {@code LC-12-10} settlement transfer).
 *
 * <p>Both posts are a chain of refusals before a movement is written, and every link of
 * both chains is exercised on the leg that refuses AND on the leg that lets through:
 * the training mode, the missing session, the tender the shop does not offer, the
 * amount that adds up to nothing, the transfer onto itself, the transfer above the
 * theoretical — including its boundary, where the amount EQUALS the theoretical and
 * must pass — the endorsement on each of its three outcomes, and the service refusing
 * the movement after all of that.
 *
 * <p>What the movement CARRIES is asserted as carefully as whether it was written: a
 * withdrawal names the tender it took out and nothing to transfer to, a transfer names
 * both ends, and a non-cash withdrawal takes the theoretical the register states rather
 * than the amount the form posted — the check that {@code LC-12-03-06} cannot be
 * defeated by a forged post.
 *
 * <p>Every collaborator is a hand-written stand-in, so the class runs without Mockito
 * and without a database — no test lets the resource reach {@code Employee.findById},
 * which is why the fixture logs no operator id.
 */
class DrawerOperationsResourceTest {

    /** Settings whose printing rules each test sets directly. */
    private static class FakeSettings extends PosSettingsService {

        /** Whether a withdrawal ticket is printed. */
        private boolean withdrawalPrint = false;

        /** Whether a transfer ticket is printed. */
        private boolean transferPrint = false;

        /** The administered withdrawal list. */
        private String withdrawalMethods = "";

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

        /** {@inheritDoc} */
        @Override
        public boolean drawerWithdrawalPrint() {
            return withdrawalPrint;
        }

        /** {@inheritDoc} */
        @Override
        public boolean drawerTransferPrint() {
            return transferPrint;
        }

        /** {@inheritDoc} */
        @Override
        public BigDecimal cashMovementEndorsementThreshold() {
            return new BigDecimal("100.00");
        }
    }

    /** A session service that hands out the session the test decided on. */
    private static class FakeSessions extends CashSessionService {

        /** The session the register believes is open, or null when none is. */
        private CashSession open = new CashSession();

        /** {@inheritDoc} */
        @Override
        public CashSession getOpenSession() {
            return open;
        }
    }

    /** The tenders offered, with theoreticals the test states. */
    private static class FakeMethods extends DrawerMethodService {

        /** The theoretical amount of each tender. */
        private Map<String, BigDecimal> totals = Map.of(
                CashMovement.CASH, new BigDecimal("200.00"),
                "CHEQUE", new BigDecimal("80.00"));

        /** {@inheritDoc} */
        @Override
        public Map<String, BigDecimal> theoreticalByMethod() {
            return totals;
        }

        /** {@inheritDoc} */
        @Override
        public Map<String, Integer> countByMethod() {
            return Map.of(CashMovement.CASH, 5, "CHEQUE", 2);
        }

        /** {@inheritDoc} */
        @Override
        public List<String[]> transactionsOf(String key) {
            return List.of(new String[] {"T-1", "40,00 E"}, new String[] {"T-2", "40,00 E"});
        }
    }

    /** A movement service that records what it was asked to write, and writes nothing. */
    private static class FakeMovements extends CashMovementService {

        /** The movement handed to the last call, or null when it refused. */
        private CashMovement recorded;

        /** Whether the next call refuses the movement. */
        private boolean refuse = false;

        /** The endorsing badge the last call was given. */
        private String endorsedBy;

        /** {@inheritDoc} */
        @Override
        public boolean requiresEndorsement(BigDecimal amount) {
            return amount != null && amount.compareTo(new BigDecimal("100.00")) > 0;
        }

        /** {@inheritDoc} */
        @Override
        public CashMovement record(CashSession session, Employee cashier,
                                   CashMovement.MovementType type, BigDecimal amount,
                                   String reason, String endorsedBy, String paymentMethod,
                                   String transferTo, String denominationDetail) {
            this.endorsedBy = endorsedBy;
            if (refuse) {
                recorded = null;
                return null;
            }
            CashMovement movement = new CashMovement();
            movement.type = type;
            movement.amount = amount;
            movement.reason = reason;
            movement.endorsedBy = endorsedBy;
            movement.paymentMethod = paymentMethod;
            movement.transferTo = transferTo;
            movement.denominationDetail = denominationDetail;
            recorded = movement;
            return movement;
        }
    }

    /** An endorsement service whose two answers each test sets directly. */
    private static class FakeEndorsements extends EndorsementService {

        /** Whether the logged operator holds a supervising role. */
        private boolean supervisor = false;

        /** Whether the presented credentials are accepted. */
        private boolean authorized = false;

        /** {@inheritDoc} */
        @Override
        public boolean operatorIsSupervisor(PosState state) {
            return supervisor;
        }

        /** {@inheritDoc} */
        @Override
        public boolean authorize(String login, String password, String actionCode) {
            return authorized;
        }
    }

    /** A printer that keeps what it was asked to print instead of printing it. */
    private static class FakePrinter extends TicketPrinterService {

        /** The tender wording of the last withdrawal ticket, or null when none. */
        private String withdrawalLabel;

        /** The lines of the last withdrawal ticket. */
        private List<String[]> withdrawalLines = new ArrayList<>();

        /** The amount of the last withdrawal ticket. */
        private BigDecimal withdrawalAmount;

        /** The two ends of the last transfer ticket, or null when none. */
        private String transferFrom;

        /** The destination of the last transfer ticket. */
        private String transferTo;

        /** {@inheritDoc} */
        @Override
        public void printWithdrawalTicket(String methodLabel, BigDecimal amount,
                                          List<String[]> lines, String operator,
                                          String terminalId) {
            withdrawalLabel = methodLabel;
            withdrawalAmount = amount;
            withdrawalLines = lines;
        }

        /** {@inheritDoc} */
        @Override
        public void printTransferTicket(String fromLabel, String toLabel, BigDecimal amount,
                                        String operator, String terminalId) {
            transferFrom = fromLabel;
            transferTo = toLabel;
        }
    }

    /** A number service that names the register without reading a sequence. */
    private static class FakeNumbers extends TicketNumberService {

        /** {@inheritDoc} */
        @Override
        public String getTerminalId() {
            return "C04";
        }
    }

    /** The resource under test. */
    private DrawerOperationsResource resource;

    /** The real register state the resource reads. */
    private PosState state;

    /** The stand-in settings. */
    private FakeSettings settings;

    /** The stand-in session service. */
    private FakeSessions sessions;

    /** The stand-in tender list. */
    private FakeMethods methods;

    /** The stand-in movement service. */
    private FakeMovements movements;

    /** The stand-in endorsement service. */
    private FakeEndorsements endorsements;

    /** The stand-in printer. */
    private FakePrinter printer;

    /**
     * Wires a fresh resource to fresh stand-ins before each test.
     */
    @BeforeEach
    void setUp() {
        resource = new DrawerOperationsResource();
        state = new PosState();
        settings = new FakeSettings();
        sessions = new FakeSessions();
        methods = new FakeMethods();
        movements = new FakeMovements();
        endorsements = new FakeEndorsements();
        printer = new FakePrinter();
        methods.posSettingsService = settings;
        resource.state = state;
        resource.posSettingsService = settings;
        resource.cashSessionService = sessions;
        resource.drawerMethodService = methods;
        resource.cashMovementService = movements;
        resource.endorsementService = endorsements;
        resource.ticketPrinterService = printer;
        resource.ticketNumberService = new FakeNumbers();
        resource.cashCountService = new CashCountService();
        settingsList("CASH:Espèces;CHEQUE:Chèques", "");
    }

    /**
     * Administers the two tender lists.
     *
     * @param withdrawal the withdrawal list
     * @param transfer   the transfer list
     */
    private void settingsList(String withdrawal, String transfer) {
        settings.withdrawalMethods = withdrawal;
        settings.transferMethods = transfer;
    }

    /**
     * Reads the target of a redirect.
     *
     * @param response the response the resource returned
     * @return the location it redirects to
     */
    private String target(Response response) {
        return response.getLocation().toString();
    }

    // ------------------------------------------------------------- WITHDRAWAL

    /**
     * A register in training mode records nothing: the movement would be a lie about a
     * drawer nobody touched.
     */
    @Test
    void aWithdrawalIsRefusedInTrainingMode() {
        state.trainingMode = true;
        Response response = resource.recordWithdrawal(CashMovement.CASH, "50", "{}", null, null);
        assertEquals("/withdrawal?error=training", target(response));
        assertNull(movements.recorded);
    }

    /**
     * Without an open session there is no drawer to take anything out of.
     */
    @Test
    void aWithdrawalIsRefusedWithoutAnOpenSession() {
        sessions.open = null;
        Response response = resource.recordWithdrawal(CashMovement.CASH, "50", "{}", null, null);
        assertEquals("/withdrawal?error=no-session", target(response));
        assertNull(movements.recorded);
    }

    /**
     * A tender the shop did not administer as manually withdrawable is refused, even
     * posted straight at the action ({@code LC-12-03-03}).
     */
    @Test
    void aWithdrawalIsRefusedForATenderTheShopDoesNotOffer() {
        Response response = resource.recordWithdrawal("TR", "50", null, null, null);
        assertEquals("/withdrawal?error=bad-method", target(response));
        assertNull(movements.recorded);
    }

    /**
     * A cash count that adds up to nothing is refused, and the screen comes back on the
     * tender so the count can be redone.
     */
    @Test
    void aCashWithdrawalOfNothingIsRefused() {
        Response response = resource.recordWithdrawal(CashMovement.CASH, "0", "{}", null, null);
        assertEquals("/withdrawal?error=amount&method=CASH", target(response));
        assertNull(movements.recorded);
    }

    /**
     * An unreadable amount is refused rather than read as zero and written.
     */
    @Test
    void aCashWithdrawalOfAnUnreadableAmountIsRefused() {
        Response response = resource.recordWithdrawal(CashMovement.CASH, "abc", "{}", null, null);
        assertEquals("/withdrawal?error=amount&method=CASH", target(response));
    }

    /**
     * A blank amount is refused too — the blank arm of the reading.
     */
    @Test
    void aCashWithdrawalOfABlankAmountIsRefused() {
        Response response = resource.recordWithdrawal(CashMovement.CASH, "  ", "{}", null, null);
        assertEquals("/withdrawal?error=amount&method=CASH", target(response));
    }

    /**
     * A tender the drawer holds none of is refused: there is nothing to hand over.
     */
    @Test
    void aWithdrawalOfATenderTheDrawerHoldsNoneOfIsRefused() {
        methods.totals = Map.of(CashMovement.CASH, new BigDecimal("200.00"));
        Response response = resource.recordWithdrawal("CHEQUE", "80", null, null, null);
        assertEquals("/withdrawal?error=amount&method=CHEQUE", target(response));
    }

    /**
     * A cash withdrawal writes the counted total, names the cash and carries the
     * per-denomination detail the screen produced ({@code LC-12-03-04}).
     */
    @Test
    void aCashWithdrawalWritesTheCountedTotalWithItsDetail() {
        Response response = resource.recordWithdrawal(CashMovement.CASH, "50,50", "{\"b50\":1}", null, null);
        assertEquals("/withdrawal?ok=1", target(response));
        assertNotNull(movements.recorded);
        assertEquals(CashMovement.MovementType.WITHDRAWAL, movements.recorded.type);
        assertEquals(new BigDecimal("50.50"), movements.recorded.amount);
        assertEquals(CashMovement.CASH, movements.recorded.paymentMethod);
        assertNull(movements.recorded.transferTo);
        assertEquals("{\"b50\":1}", movements.recorded.denominationDetail);
        assertEquals("Prélèvement Espèces", movements.recorded.reason);
    }

    /**
     * A NON-cash withdrawal takes the theoretical the register states and ignores the
     * amount the form posted: {@code LC-12-03-06} says the cashier cannot change it,
     * and a forged post must not be able to either.
     */
    @Test
    void aNonCashWithdrawalTakesTheTheoreticalAndIgnoresThePostedAmount() {
        Response response = resource.recordWithdrawal("CHEQUE", "5000", "{\"b50\":1}", null, null);
        assertEquals("/withdrawal?ok=1", target(response));
        assertEquals(new BigDecimal("80.00"), movements.recorded.amount);
        assertEquals("CHEQUE", movements.recorded.paymentMethod);
        assertNull(movements.recorded.denominationDetail);
    }

    /**
     * Above the threshold with no manager at all, the withdrawal is refused and the
     * screen comes back on the tender.
     */
    @Test
    void anAboveThresholdWithdrawalWithoutAManagerIsRefused() {
        Response response = resource.recordWithdrawal(CashMovement.CASH, "150", "{}", "9", "0000");
        assertEquals("/withdrawal?error=endorsement&method=CASH", target(response));
        assertNull(movements.recorded);
    }

    /**
     * A supervising operator endorses their own withdrawal, and their badge is what the
     * movement carries.
     */
    @Test
    void aSupervisorEndorsesTheirOwnWithdrawal() {
        endorsements.supervisor = true;
        state.auth.operatorBadgeId = "11111111";
        Response response = resource.recordWithdrawal(CashMovement.CASH, "150", "{}", null, null);
        assertEquals("/withdrawal?ok=1", target(response));
        assertEquals("11111111", movements.recorded.endorsedBy);
    }

    /**
     * A manager presenting accepted credentials endorses the withdrawal, and their
     * login stands as the badge.
     */
    @Test
    void anAuthorizedManagerEndorsesTheWithdrawal() {
        endorsements.authorized = true;
        Response response = resource.recordWithdrawal(CashMovement.CASH, "150", "{}", "22222222", "1234");
        assertEquals("/withdrawal?ok=1", target(response));
        assertEquals("22222222", movements.recorded.endorsedBy);
    }

    /**
     * At or below the threshold no credential is asked and the movement carries no
     * endorser.
     */
    @Test
    void aWithdrawalAtTheThresholdAsksNoManager() {
        Response response = resource.recordWithdrawal(CashMovement.CASH, "100", "{}", null, null);
        assertEquals("/withdrawal?ok=1", target(response));
        assertNull(movements.endorsedBy);
    }

    /**
     * A movement the service refuses lands back on the endorsement message rather than
     * announcing a withdrawal that was never written.
     */
    @Test
    void aRefusedWithdrawalIsNotAnnouncedAsRecorded() {
        movements.refuse = true;
        Response response = resource.recordWithdrawal(CashMovement.CASH, "50", "{}", null, null);
        assertEquals("/withdrawal?error=endorsement&method=CASH", target(response));
    }

    /**
     * With the printing switched off no paper is produced.
     */
    @Test
    void noWithdrawalTicketIsPrintedWhenTheShopSwitchedItOff() {
        settings.withdrawalPrint = false;
        resource.recordWithdrawal(CashMovement.CASH, "50", "{\"b50\":1}", null, null);
        assertNull(printer.withdrawalLabel);
    }

    /**
     * The cash withdrawal ticket lists every counted denomination with its quantity and
     * its amount ({@code LC-12-03-07}).
     */
    @Test
    void theCashWithdrawalTicketListsTheDenominations() {
        settings.withdrawalPrint = true;
        resource.recordWithdrawal(CashMovement.CASH, "70", "{\"b50\":1,\"c1\":20}", null, null);
        assertEquals("Espèces", printer.withdrawalLabel);
        assertEquals(new BigDecimal("70"), printer.withdrawalAmount);
        assertEquals(2, printer.withdrawalLines.size());
        assertEquals("Billet 50 € x1", printer.withdrawalLines.get(0)[0]);
        assertEquals("50,00 E", printer.withdrawalLines.get(0)[1]);
        assertEquals("Pièce 1 € x20", printer.withdrawalLines.get(1)[0]);
        assertEquals("20,00 E", printer.withdrawalLines.get(1)[1]);
    }

    /**
     * A roll counts the whole roll's value on the ticket, as it does on the screen.
     */
    @Test
    void theCashWithdrawalTicketValuesARollWhole() {
        settings.withdrawalPrint = true;
        resource.recordWithdrawal(CashMovement.CASH, "50", "{\"r2\":1}", null, null);
        assertEquals("Rouleau 2€ (50€) x1", printer.withdrawalLines.get(0)[0]);
        assertEquals("50,00 E", printer.withdrawalLines.get(0)[1]);
    }

    /**
     * Every unusable entry of the detail is dropped rather than printed as a phantom
     * line: no detail at all, a blank one, an entry without a separator, one whose
     * quantity is not a number, one counted at zero, and one naming a denomination the
     * count screen does not know.
     */
    @Test
    void theCashWithdrawalTicketDropsWhatItCannotRead() {
        settings.withdrawalPrint = true;
        resource.recordWithdrawal(CashMovement.CASH, "50", null, null, null);
        assertTrue(printer.withdrawalLines.isEmpty());
        resource.recordWithdrawal(CashMovement.CASH, "50", "  ", null, null);
        assertTrue(printer.withdrawalLines.isEmpty());
        resource.recordWithdrawal(CashMovement.CASH, "50", "{\"b50\"}", null, null);
        assertTrue(printer.withdrawalLines.isEmpty());
        resource.recordWithdrawal(CashMovement.CASH, "50", "{\"b50\":x}", null, null);
        assertTrue(printer.withdrawalLines.isEmpty());
        resource.recordWithdrawal(CashMovement.CASH, "50", "{\"b50\":0}", null, null);
        assertTrue(printer.withdrawalLines.isEmpty());
        resource.recordWithdrawal(CashMovement.CASH, "50", "{\"b1000\":1}", null, null);
        assertTrue(printer.withdrawalLines.isEmpty());
    }

    /**
     * The withdrawal ticket of a NON-cash tender lists one line per settlement, with
     * its transaction number and its amount ({@code LC-12-03-07}): a cheque bundle is
     * counted against that list, and a total alone would let a missing cheque through.
     */
    @Test
    void theNonCashWithdrawalTicketListsTheTransactions() {
        settings.withdrawalPrint = true;
        resource.recordWithdrawal("CHEQUE", null, null, null, null);
        assertEquals("Chèques", printer.withdrawalLabel);
        assertEquals(2, printer.withdrawalLines.size());
        assertEquals("T-1", printer.withdrawalLines.get(0)[0]);
    }

    // --------------------------------------------------------------- TRANSFER

    /**
     * A register in training mode transfers nothing.
     */
    @Test
    void aTransferIsRefusedInTrainingMode() {
        state.trainingMode = true;
        Response response = resource.recordTransfer(CashMovement.CASH, "CHEQUE", "10", null, null);
        assertEquals("/transfer?error=training", target(response));
        assertNull(movements.recorded);
    }

    /**
     * Without an open session there is no theoretical to move.
     */
    @Test
    void aTransferIsRefusedWithoutAnOpenSession() {
        sessions.open = null;
        Response response = resource.recordTransfer(CashMovement.CASH, "CHEQUE", "10", null, null);
        assertEquals("/transfer?error=no-session", target(response));
    }

    /**
     * A source the shop does not offer is refused ({@code LC-12-10-02}).
     */
    @Test
    void aTransferFromATenderTheShopDoesNotOfferIsRefused() {
        Response response = resource.recordTransfer("TR", "CHEQUE", "10", null, null);
        assertEquals("/transfer?error=bad-method", target(response));
    }

    /**
     * A destination the shop does not offer is refused too — the second leg of the same
     * check, which a source-only test would leave unproven.
     */
    @Test
    void aTransferToATenderTheShopDoesNotOfferIsRefused() {
        Response response = resource.recordTransfer(CashMovement.CASH, "TR", "10", null, null);
        assertEquals("/transfer?error=bad-method", target(response));
    }

    /**
     * A transfer onto itself is refused: it moves nothing and would only add a movement
     * the closing has to read.
     */
    @Test
    void aTransferOntoItselfIsRefused() {
        Response response = resource.recordTransfer(CashMovement.CASH, CashMovement.CASH, "10", null, null);
        assertEquals("/transfer?error=same-method", target(response));
    }

    /**
     * An amount of nothing is refused, on the zero and the unreadable arms alike.
     */
    @Test
    void aTransferOfNothingIsRefused() {
        assertEquals("/transfer?error=amount",
                target(resource.recordTransfer(CashMovement.CASH, "CHEQUE", "0", null, null)));
        assertEquals("/transfer?error=amount",
                target(resource.recordTransfer(CashMovement.CASH, "CHEQUE", "abc", null, null)));
        assertEquals("/transfer?error=amount",
                target(resource.recordTransfer(CashMovement.CASH, "CHEQUE", null, null, null)));
    }

    /**
     * More than the source holds is refused and cannot be forced ({@code LC-12-10-03}):
     * it would create a negative theoretical the closing has to explain.
     */
    @Test
    void aTransferAboveTheTheoreticalIsRefused() {
        Response response = resource.recordTransfer("CHEQUE", CashMovement.CASH, "80,01", null, null);
        assertEquals("/transfer?error=insufficient", target(response));
        assertNull(movements.recorded);
    }

    /**
     * Exactly what the source holds passes: the control is a ceiling, not a strict
     * inequality — emptying a tender into another is the very repair this gesture is
     * for.
     */
    @Test
    void aTransferOfExactlyTheTheoreticalPasses() {
        Response response = resource.recordTransfer("CHEQUE", CashMovement.CASH, "80,00", null, null);
        assertEquals("/transfer?ok=1", target(response));
        assertEquals(new BigDecimal("80.00"), movements.recorded.amount);
    }

    /**
     * A transfer names both ends and is written as a transfer, so the two theoreticals
     * move together ({@code LC-12-10-04}).
     */
    @Test
    void aTransferNamesBothEnds() {
        Response response = resource.recordTransfer("CHEQUE", CashMovement.CASH, "20", null, null);
        assertEquals("/transfer?ok=1", target(response));
        assertEquals(CashMovement.MovementType.TRANSFER, movements.recorded.type);
        assertEquals("CHEQUE", movements.recorded.paymentMethod);
        assertEquals(CashMovement.CASH, movements.recorded.transferTo);
        assertNull(movements.recorded.denominationDetail);
        assertEquals("Transfert Chèques vers Espèces", movements.recorded.reason);
    }

    /**
     * Above the threshold with no manager at all, the transfer is refused.
     */
    @Test
    void anAboveThresholdTransferWithoutAManagerIsRefused() {
        Response response = resource.recordTransfer(CashMovement.CASH, "CHEQUE", "150", "9", "0000");
        assertEquals("/transfer?error=endorsement", target(response));
        assertNull(movements.recorded);
    }

    /**
     * A supervising operator endorses their own transfer.
     */
    @Test
    void aSupervisorEndorsesTheirOwnTransfer() {
        endorsements.supervisor = true;
        state.auth.operatorBadgeId = "11111111";
        Response response = resource.recordTransfer(CashMovement.CASH, "CHEQUE", "150", null, null);
        assertEquals("/transfer?ok=1", target(response));
        assertEquals("11111111", movements.recorded.endorsedBy);
    }

    /**
     * A manager presenting accepted credentials endorses the transfer.
     */
    @Test
    void anAuthorizedManagerEndorsesTheTransfer() {
        endorsements.authorized = true;
        Response response = resource.recordTransfer(CashMovement.CASH, "CHEQUE", "150", "22222222", "1234");
        assertEquals("/transfer?ok=1", target(response));
        assertEquals("22222222", movements.recorded.endorsedBy);
    }

    /**
     * A transfer the service refuses is not announced as recorded.
     */
    @Test
    void aRefusedTransferIsNotAnnouncedAsRecorded() {
        movements.refuse = true;
        Response response = resource.recordTransfer(CashMovement.CASH, "CHEQUE", "20", null, null);
        assertEquals("/transfer?error=endorsement", target(response));
    }

    /**
     * With the printing switched on, the transfer ticket names both ends
     * ({@code LC-12-10-05}); switched off, no paper is produced.
     */
    @Test
    void theTransferTicketFollowsTheAdministeredRule() {
        settings.transferPrint = false;
        resource.recordTransfer("CHEQUE", CashMovement.CASH, "20", null, null);
        assertNull(printer.transferFrom);
        settings.transferPrint = true;
        resource.recordTransfer("CHEQUE", CashMovement.CASH, "20", null, null);
        assertEquals("Chèques", printer.transferFrom);
        assertEquals("Espèces", printer.transferTo);
    }

    /**
     * A shop that named its own transfer list is obeyed on both ends: the tender it
     * left out of that list is refused even though the withdrawal list offers it.
     */
    @Test
    void theTransferListIsWhatDecidesTheTwoEnds() {
        settingsList("CASH:Espèces;CHEQUE:Chèques", "CHEQUE:Chèques;TR:Titres");
        assertEquals("/transfer?error=bad-method",
                target(resource.recordTransfer(CashMovement.CASH, "CHEQUE", "10", null, null)));
        assertEquals("/transfer?ok=1",
                target(resource.recordTransfer("CHEQUE", "TR", "10", null, null)));
    }

    // ------------------------------------------------------------------ PAGES

    /**
     * The chosen tender is read back from the offered list, and every way that can miss
     * lands on the tender list instead: no key at all, and a key the shop no longer
     * administers.
     */
    @Test
    void theChosenTenderIsReadBackFromTheOfferedList() {
        List<DrawerMethodService.DrawerMethod> offered = methods.withdrawable();
        assertNull(resource.chosen(offered, null));
        assertNull(resource.chosen(offered, "TR"));
        assertNotNull(resource.chosen(offered, "CHEQUE"));
        assertEquals("Chèques", resource.chosen(offered, "CHEQUE").label());
    }

    /**
     * Every outcome a redirect can carry has its own wording, and a code the screen
     * does not know shows nothing rather than a raw code.
     */
    @Test
    void everyOutcomeHasItsOwnWording() {
        assertNull(resource.messageOf(null));
        assertEquals("AUCUNE SESSION OUVERTE", resource.messageOf("no-session"));
        assertEquals("MOYEN DE PAIEMENT NON AUTORISÉ", resource.messageOf("bad-method"));
        assertEquals("SOURCE ET DESTINATION IDENTIQUES", resource.messageOf("same-method"));
        assertEquals("MONTANT INVALIDE", resource.messageOf("amount"));
        assertEquals("MONTANT SUPÉRIEUR AU THÉORIQUE DU TIROIR", resource.messageOf("insufficient"));
        assertEquals("AVAL MANAGER REFUSÉ OU MANQUANT", resource.messageOf("endorsement"));
        assertEquals("INDISPONIBLE EN FORMATION", resource.messageOf("training"));
        assertNull(resource.messageOf("whatever"));
    }

    /**
     * A withdrawal always leaves the register state touched, so a screen polling the
     * sale notices the drawer gesture.
     */
    @Test
    void aRecordedWithdrawalTouchesTheState() {
        long before = state.version;
        resource.recordWithdrawal(CashMovement.CASH, "50", "{}", null, null);
        assertFalse(state.version == before);
    }
}
