package com.intermarche.pos.ui.home;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.VoucherService;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The intake of the SECOND, customer-side scanner (BO-03-09-18): the customer
 * scans his fidelity card or his coupons there while the cashier goes on
 * scanning the articles on the first one.
 *
 * <p>What it takes is NARROWER than the main intake on purpose: a fidelity
 * card or a coupon goes through the very same scan chain — card attachment,
 * voucher recognition and its controls — and anything else is refused, so a
 * customer cannot ring an article from his side of the counter. A register
 * whose second scanner is not activated ignores the intake.
 */
@Path("/api/pos/scan/customer")
public class CustomerScanResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CustomerScanResource.class);

    /** The scan chain. */
    @Inject
    TicketService ticketService;

    /** The voucher ranges. */
    @Inject
    VoucherService voucherService;

    /** The administered activation and the fidelity card range. */
    @Inject
    PosSettingsService posSettingsService;

    /** The register state, where a refusal is said. */
    @Inject
    PosState state;

    /** The localised operator messages. */
    @Inject
    Messages i18n;

    /** The fidelity card range of the deployment, the fallback of the administered one. */
    @ConfigProperty(name = "scan.pattern.fidelity", defaultValue = "^299\\d{10}$")
    String fidelityPattern;

    /**
     * Handles a code read by the customer-side scanner.
     *
     * @param code the scanned code
     * @return 200 when the code went through the chain, 422 when it is not a
     *         card or a coupon, 204 when the second scanner is not activated,
     *         400 on an empty code
     */
    @POST
    @Consumes("text/plain")
    public Response handle(String code) {
        LOGGER.info("Entering method handle");
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method handle");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (!posSettingsService.secondScannerEnabled()) {
            LOGGER.info("Exiting method handle");
            return Response.noContent().build();
        }
        String trimmed = code.trim();
        if (!trimmed.matches(cardPattern()) && voucherService.resolveType(trimmed) == null) {
            state.ticket.setError(i18n.get("scan.clientRefuse"));
            state.touch();
            LOGGER.info("Exiting method handle");
            return Response.status(422).build();
        }
        ticketService.processScan(trimmed);
        LOGGER.info("Exiting method handle");
        return Response.ok().build();
    }

    /**
     * The fidelity card range: the administered one, else the deployment's.
     *
     * @return the recognition regex
     */
    String cardPattern() {
        String administered = posSettingsService.fidelityCardPattern();
        return administered == null || administered.isBlank() ? fidelityPattern : administered.trim();
    }
}
