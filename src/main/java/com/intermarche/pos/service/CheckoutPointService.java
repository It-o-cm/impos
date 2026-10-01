package com.intermarche.pos.service;

import com.intermarche.pos.domain.store.CheckoutPoint;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * THE one reader of this register's own CHECKOUT POINT (BO-03-09, BO-03-10): the
 * administered description of the lane — its island, its kind, its technical
 * hostess, its displays, its cash-menu model, and whether it is in service.
 *
 * <p>ONE reader and not eleven. Every screen that needs one of these attributes
 * goes through here, because the lookup has a shape that must not be repeated:
 * the point is pulled from the node, so it can legitimately be ABSENT — a lane
 * that has never completed a pull, or a node that has not been updated, has no
 * row. Each caller re-deriving "no row means no constraint" is how one of them
 * ends up deriving the opposite.
 *
 * <p>Lives in {@code pos.service} and not in {@code ui}: it injects no
 * {@code PosState}, serves no single screen flow, and answers the same thing to
 * the session guard, to the coupon control and to the hardware supervision.
 */
@ApplicationScoped
public class CheckoutPointService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CheckoutPointService.class);

    /**
     * This register's own identifier, the same {@code pos.terminal.id} the pull
     * sends to the node and the ticket numbering reads.
     */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Returns this register's checkout point, or null when the node has not sent
     * one.
     *
     * <p>Null is a NORMAL answer and not a failure: a lane whose referential
     * pull has never completed, or whose node does not serve the
     * {@code CHECKOUT_POINTS} domain yet, has no row — and must keep selling.
     * Every caller treats null as "nothing administered", never as "refused".
     *
     * @return the checkout point of this register, or null
     */
    public CheckoutPoint own() {
        LOGGER.info("Entering method own");
        CheckoutPoint point = CheckoutPoint.findByTerminal(terminalId);
        LOGGER.info("Exiting method own");
        return point;
    }

    /**
     * Tells whether this register is OUT OF SERVICE by administration
     * (BO-03-09-01).
     *
     * <p>True only when a point exists and says so. An absent point never closes
     * a lane: a register cannot be forbidden to work by a referential it has not
     * received.
     *
     * @return true when the administered point exists and is deactivated
     */
    public boolean isDeactivated() {
        LOGGER.info("Entering method isDeactivated");
        CheckoutPoint point = own();
        boolean deactivated = point != null && !point.active;
        LOGGER.info("Exiting method isDeactivated");
        return deactivated;
    }

    /**
     * Returns the code of the island this register belongs to, as administered,
     * or null when no point or no island is known.
     *
     * <p>This is the DIRECT answer to a question the register used to answer
     * backwards, by scanning every island's semicolon list of terminals for its
     * own identifier. The scan stays as a fallback for a node that does not send
     * the points yet, but it is the fallback, not the truth.
     *
     * @return the island code, or null
     */
    public String ownIslandCode() {
        LOGGER.info("Entering method ownIslandCode");
        CheckoutPoint point = own();
        String islandCode = point == null ? null : point.islandCode;
        LOGGER.info("Exiting method ownIslandCode");
        return islandCode;
    }

    /**
     * Tells whether a customer display is EXPECTED on this register
     * (BO-03-09-13).
     *
     * <p>A point administered without one must not be reported in fault for not
     * polling a display it does not have. An absent point expects one, because
     * that is what every register did before the points descended, and silencing
     * a real display's absence is worse than warning about one that was never
     * installed.
     *
     * @return false only when the administered point explicitly declares none
     */
    public boolean expectsCustomerDisplay() {
        LOGGER.info("Entering method expectsCustomerDisplay");
        CheckoutPoint point = own();
        boolean expected = point == null || point.hasCustomerDisplay();
        LOGGER.info("Exiting method expectsCustomerDisplay");
        return expected;
    }
}
