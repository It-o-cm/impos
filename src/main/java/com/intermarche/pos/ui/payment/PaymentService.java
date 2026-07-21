package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class);

    @Inject
    HardwareService hardwareService;

    @Inject
    com.intermarche.pos.service.TicketPersistenceService ticketPersistenceService;

    private final DecimalFormat df = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    // --------------------------------------------------
    // 1. INITIALISATION
    // --------------------------------------------------

    public void initPayment(PosState state) {
        hardwareService.displayMessage(String.format("TOTAL   %s E", df.format(state.ticket.totalAmount)));

        // Le ticket Draft n'est créé qu'une fois, à l'entrée en paiement.
        // Les rendus suivants de /pay (redirections, rafraîchissements) ne doivent pas en recréer.
        if (state.payment.ticketDbId != null) {
            return;
        }

        Long cashierId = state.auth.operatorId;
        Store store = Store.findAll().firstResult();
        Employee cashier = Employee.findById(cashierId);

        if (store != null && cashier != null) {
            Long ticketId = ticketPersistenceService.createDraftTicket(state, store, cashier);
            state.payment.ticketDbId = ticketId;
            LOG.info("Ticket créé en BDD (Draft) ID: " + ticketId);
        } else {
            LOG.error("Impossible de créer le ticket (Store ou Cashier manquant)");
        }
    }

    // --------------------------------------------------
    // 2. METHODE PRIVEE COMMUNE
    // --------------------------------------------------

    private void handlePaymentWithChange(PosState state, String methodKey, String displayName, double tendered) {
        if (tendered <= 0) return;

        state.payment.clearPendingVoucher();

        double remaining = state.getRemaining();
        double amountToPay = Math.min(tendered, remaining);
        double change = tendered - amountToPay;

        // Arrondi métier
        change = Math.round(change * 100.0) / 100.0;

        // Mise à jour état UI
        state.payment.lastChangeAmount = (change > 0.001) ? change : 0.0;

        // Mise à jour mémoire
        if ("CASH".equals(methodKey)) {
            state.payment.addCashPayment(amountToPay, tendered);
        } else {
            state.payment.addPayment(methodKey, amountToPay);
        }
        state.touch();

        // Sauvegarde BDD
        savePayment(state, methodKey);

        // Affichage Matériel
        if (change > 0.001) {
            hardwareService.displayMessage(String.format("DONNE %s RENDU %s", df.format(tendered), df.format(change)));
        } else {
            hardwareService.displayMessage(String.format("%-10s%s E", displayName, df.format(amountToPay)));
        }

        checkCompletion(state);
    }

    // --------------------------------------------------
    // 3. ACTIONS PUBLIQUES
    // --------------------------------------------------

    public void processCash(PosState state, double tendered) {
        if (tendered <= 0) return;

        handlePaymentWithChange(state, "CASH", "ESPECES", tendered);

        // Règle : Espèces = Ouverture systématique (dépot + rendu)
        hardwareService.openDrawer();
    }

    public void processCard(PosState state, double amount) {
        if (amount <= 0) amount = state.getRemaining();
        if (amount <= 0) return;

        handlePaymentWithChange(state, "CARD", "CARTE", amount);

        // Règle : Carte = Pas d'ouverture (sauf si rendu géré dans handlePayment, ce qui est rare)
        // Ici, on n'appelle pas openDrawer() explicitement.
    }

    public void processTicketResto(PosState state, double amount) {
        if (amount <= 0) amount = state.getRemaining();
        if (amount <= 0) return;

        handlePaymentWithChange(state, "TR", "TICKET", amount);

        // Règle : Tickets = Ouverture systématique (pour déposer les tickets)
        hardwareService.openDrawer();
    }

    public void processCheque(PosState state, double amount) {
        if (amount <= 0) amount = state.getRemaining();
        if (amount <= 0) return;

        handlePaymentWithChange(state, "CHEQUE", "CHEQUE", amount);

        // Règle : Chèque = Ouverture systématique (pour déposer le chèque)
        hardwareService.openDrawer();
    }

    public void processFidelity(PosState state, double amount) {
        if (amount <= 0) amount = state.getRemaining();
        if (amount <= 0) return;

        state.payment.clearPendingVoucher();

        double amountToPay = Math.min(amount, state.getRemaining());
        state.payment.addPayment("FIDELITY", amountToPay);
        state.touch();
        savePayment(state, "FIDELITY");
        hardwareService.displayMessage(String.format("FIDELITE  %s E", df.format(amountToPay)));

        // Règle : Fidélité = Pas d'ouverture (virtuel)

        checkCompletion(state);
    }

    /**
     * Registers a voucher payment, displays it and persists it.
     * <p>
     * The amount is assumed already capped at the remaining due by the caller.
     *
     * @param state the current POS state
     * @param label the voucher type label shown to the cashier
     * @param number the voucher number, or null when there is none
     * @param amount the paid amount
     */
    public void processVoucher(PosState state, String label, String number, double amount) {
        if (amount <= 0) return;

        state.payment.addVoucherPayment(label, number, amount);
        state.touch();
        saveVoucherPayment(state);
        hardwareService.displayMessage(String.format("%-10s%s E", "BON", df.format(amount)));

        // Règle : Bon d'achat = Pas d'ouverture (virtuel)

        checkCompletion(state);
    }

    // --------------------------------------------------
    // 4. FINALISATION
    // --------------------------------------------------

    public void finalizeTransaction(PosState state) {
        Long ticketId = state.payment.ticketDbId;

        if (ticketId != null) {
            ticketPersistenceService.validateTicket(ticketId);
            state.lastClosedTicketId = ticketId;
            LOG.info("Ticket validé et fermé en BDD ID: " + ticketId);
        }

        hardwareService.displayMessage("MERCI A BIENTOT");
        state.clearTicket();
    }

    // --------------------------------------------------
    // Privés
    // --------------------------------------------------

    private void savePayment(PosState state, String methodKey) {
        if (state.payment.ticketDbId == null) {
            LOG.error("Impossible de sauvegarder le paiement : aucun Ticket ID");
            return;
        }
        PaymentState.PaymentEntry lastEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, lastEntry);
    }

    /**
     * Persists the last registered payment as a voucher payment.
     *
     * @param state the current POS state
     */
    private void saveVoucherPayment(PosState state) {
        if (state.payment.ticketDbId == null) {
            LOG.error("Impossible de sauvegarder le bon : aucun Ticket ID");
            return;
        }
        PaymentState.PaymentEntry lastEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, lastEntry);
    }

    private void checkCompletion(PosState state) {
        if (state.getRemaining() <= 0.001) {
            state.payment.transactionComplete = true;
            state.touch();
        }
    }
}
