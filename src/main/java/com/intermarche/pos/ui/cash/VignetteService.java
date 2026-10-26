package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The VIGNETTES of the register (BO-11-01-20): the stickers a sale earns and
 * the cashier hands out, counted two ways so the store can compare them — the
 * THEORETICAL count, which the administered formula computes from each sale,
 * and the DECLARED count, which the cashier states for her shift.
 *
 * <p>The formula is the store's: a sale earns, per whole purchase step of its
 * tax-included total, the administered number of vignettes. The declaration is
 * journalled as a technical event of the shift, so it travels to the store
 * node with the rest of the journal and the node's vignettes flow sets it
 * beside the theoretical count, register by register and shift by shift.
 */
@ApplicationScoped
public class VignetteService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(VignetteService.class);

    /** The administered formula and switch. */
    @Inject
    PosSettingsService posSettingsService;

    /** The shift the declaration belongs to. */
    @Inject
    CashSessionService cashSessionService;

    /** Journals the declaration, which the synchronization carries up. */
    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Where the shift stands.
     *
     * @param sessionNumber the shift's session number
     * @param tickets how many sales the shift closed
     * @param theoretical the vignettes the formula makes due over those sales
     * @param declared the vignettes the cashier declared so far
     */
    public record Summary(String sessionNumber, int tickets, long theoretical, long declared) {
    }

    /**
     * The vignettes a sale makes due under the formula: the whole purchase
     * steps of its tax-included total, times the vignettes per step.
     *
     * @param total the tax-included total, possibly null
     * @param step the purchase step, zero meaning no vignette
     * @param perStep the vignettes per whole step
     * @return the vignettes due, never negative
     */
    public static long vignettesFor(BigDecimal total, BigDecimal step, int perStep) {
        if (total == null || total.signum() <= 0 || step.signum() <= 0) {
            return 0L;
        }
        return total.divide(step, 0, RoundingMode.DOWN).longValue() * perStep;
    }

    /**
     * Whether the store manages the vignettes.
     *
     * @return true when the vignettes are administered on
     */
    public boolean enabled() {
        return posSettingsService.vignetteEnabled();
    }

    /**
     * Sums up the open shift: its sales, the theoretical vignettes and the
     * declared ones.
     *
     * @return the summary, or null when no shift is open
     */
    @Transactional
    public Summary summary() {
        LOGGER.info("Entering method summary");
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method summary: no shift");
            return null;
        }
        List<Ticket> tickets = Ticket.list("session = ?1 and status = ?2", session, Ticket.TicketStatus.CLOSED);
        BigDecimal step = posSettingsService.vignetteAmountStep();
        int perStep = posSettingsService.vignettePerStep();
        long theoretical = 0L;
        for (Ticket ticket : tickets) {
            theoretical += vignettesFor(ticket.totalIncludingTax, step, perStep);
        }
        long declared = 0L;
        for (TechnicalEvent event : TechnicalEvent.<TechnicalEvent>list("eventType = ?1 and detail like ?2",
                TechnicalEvent.EventType.VIGNETTES_DECLARED, prefixOf(session) + "%")) {
            declared += countOf(event.detail);
        }
        LOGGER.info("Exiting method summary");
        return new Summary(session.sessionNumber, tickets.size(), theoretical, declared);
    }

    /**
     * Declares the vignettes the cashier handed out on her open shift.
     *
     * @param count the vignettes handed out, positive
     * @param operatorBadgeId the declaring cashier's badge, or null
     * @return true when the declaration was journalled, false without an open
     *     shift or for a count that is not positive
     */
    @Transactional
    public boolean declare(long count, String operatorBadgeId) {
        LOGGER.info("Entering method declare with count: " + count);
        CashSession session = cashSessionService.getOpenSession();
        if (session == null || count <= 0) {
            LOGGER.info("Exiting method declare: refused");
            return false;
        }
        technicalEventService.log(TechnicalEvent.EventType.VIGNETTES_DECLARED,
                prefixOf(session) + "count=" + count, operatorBadgeId);
        LOGGER.info("Exiting method declare");
        return true;
    }

    /**
     * The detail prefix naming the shift of a declaration.
     *
     * @param session the shift
     * @return {@code session=<number>;}
     */
    private static String prefixOf(CashSession session) {
        return "session=" + session.sessionNumber + ";";
    }

    /**
     * Reads the count of a declaration's detail.
     *
     * @param detail the detail, {@code session=<number>;count=<n>}
     * @return the count, zero when unreadable
     */
    static long countOf(String detail) {
        int at = detail == null ? -1 : detail.indexOf("count=");
        if (at < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(detail.substring(at + "count=".length()).trim());
        } catch (NumberFormatException unreadable) {
            return 0L;
        }
    }
}
