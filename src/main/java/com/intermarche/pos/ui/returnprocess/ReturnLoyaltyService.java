package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundFidelityLine;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.ui.fidelity.ImfidClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The loyalty half of a return's recalculation, line by line (LC-05-07-02): what
 * the return takes back of the cagnotte, of the purchase vouchers and of the
 * coupons the sale earned.
 *
 * <p>Every figure is the loyalty programme's. The sale froze what it earned rule
 * by rule; the programme is asked what the basket the customer KEEPS earns, from
 * the very engine couple the kept basket was priced on; earlier returns of the
 * same ticket recorded what they took back. The difference is the décagnottage of
 * this return. A return of the whole ticket keeps nothing and so takes back what
 * was earned and not taken yet, without asking anything.
 *
 * <p>Never blocking: no card, no earn frozen on the sale, no priced kept basket,
 * no programme or a programme that cannot answer, and the return simply carries
 * no line — the paper then says, as before, that the recalculation was handed to
 * the programme.
 */
@ApplicationScoped
public class ReturnLoyaltyService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReturnLoyaltyService.class);

    /** The loyalty programme. */
    @Inject
    ImfidClient imfidClient;

    /**
     * The loyalty advantages a return takes back, rule by rule.
     *
     * @param origin the ticket returned against
     * @param keptQuantities the quantity each original line keeps after this return
     * @param recalculation the engine's recalculation of the kept basket
     * @return the advantages taken back, possibly empty, never null
     */
    public List<RefundFidelityLine> withdrawal(Ticket origin, Map<Long, BigDecimal> keptQuantities,
                                               ReturnValuationService.Recalculation recalculation) {
        LOGGER.info("Entering method withdrawal");
        if (origin.fidelityCard == null || origin.fidelityLines == null || origin.fidelityLines.isEmpty()) {
            LOGGER.info("Exiting method withdrawal (nothing earned)");
            return List.of();
        }
        Map<String, BigDecimal> keptEarn;
        if (nothingKept(keptQuantities)) {
            keptEarn = new HashMap<>();
        } else if (recalculation.status == ReturnValuationService.RecalculationStatus.RECOMPUTED
                && recalculation.requestJson != null && imfidClient.isConfigured()) {
            try {
                keptEarn = byRule(imfidClient.earn(recalculation.requestJson, recalculation.responseJson));
            } catch (Exception e) {
                LOGGER.warnf("Recalcul fidelite du retour indisponible : %s", e.getMessage());
                LOGGER.info("Exiting method withdrawal (programme unavailable)");
                return List.of();
            }
        } else {
            LOGGER.info("Exiting method withdrawal (kept basket not priced)");
            return List.of();
        }
        Map<String, BigDecimal> already = alreadyWithdrawn(origin.id);
        List<RefundFidelityLine> lines = new ArrayList<>();
        for (TicketFidelityLine earned : origin.fidelityLines) {
            String key = keyOf(earned.ruleCode, earned.label);
            BigDecimal amount = earned.amount == null ? BigDecimal.ZERO : earned.amount;
            BigDecimal stillKept = consume(keptEarn, key, amount);
            BigDecimal remaining = amount.subtract(stillKept);
            BigDecimal taken = consume(already, key, remaining);
            BigDecimal withdrawn = remaining.subtract(taken).setScale(2, RoundingMode.HALF_UP);
            if (withdrawn.signum() > 0) {
                lines.add(new RefundFidelityLine(earned.ruleCode, earned.label,
                        earned.advantageTypeLabel, withdrawn));
            }
        }
        LOGGER.info("Exiting method withdrawal (" + lines.size() + " line(s))");
        return lines;
    }

    /**
     * Whether the return leaves the customer nothing of the ticket.
     *
     * @param keptQuantities the kept quantity of each original line
     * @return true when no line keeps a positive quantity
     */
    private static boolean nothingKept(Map<Long, BigDecimal> keptQuantities) {
        for (BigDecimal kept : keptQuantities.values()) {
            if (kept != null && kept.signum() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the programme projects for the kept basket, by rule.
     *
     * @param projection the programme's answer
     * @return the earn by rule
     */
    private static Map<String, BigDecimal> byRule(ImfidClient.EarnProjection projection) {
        Map<String, BigDecimal> earn = new HashMap<>();
        for (ImfidClient.EarnEntry entry : projection.entries) {
            if (entry.amount != null) {
                earn.merge(keyOf(entry.ruleCode, entry.label), entry.amount, BigDecimal::add);
            }
        }
        return earn;
    }

    /**
     * What earlier returns of the ticket already took back, by rule.
     *
     * @param ticketId the ticket
     * @return the amounts by rule
     */
    private static Map<String, BigDecimal> alreadyWithdrawn(Long ticketId) {
        Map<String, BigDecimal> taken = new HashMap<>();
        List<Refund> refunds = Refund.list("originalTicketId", ticketId);
        for (Refund refund : refunds) {
            for (RefundFidelityLine line : refund.fidelityLines) {
                if (line.amount != null) {
                    taken.merge(keyOf(line.ruleCode, line.label), line.amount, BigDecimal::add);
                }
            }
        }
        return taken;
    }

    /**
     * Takes up to a ceiling out of an amount held for a rule, so that a rule
     * earned on two lines is not counted twice.
     *
     * @param pool the amounts by rule, reduced in place
     * @param key the rule
     * @param ceiling the most that may be taken
     * @return what was taken, never more than the ceiling nor negative
     */
    private static BigDecimal consume(Map<String, BigDecimal> pool, String key, BigDecimal ceiling) {
        BigDecimal held = pool.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal taken = held.min(ceiling).max(BigDecimal.ZERO);
        pool.put(key, held.subtract(taken));
        return taken;
    }

    /**
     * The key a rule is matched on: its code, its label when it has none.
     *
     * @param ruleCode the rule code, or null
     * @param label the rule label, or null
     * @return the key
     */
    private static String keyOf(String ruleCode, String label) {
        return ruleCode != null ? ruleCode : "#" + label;
    }
}
