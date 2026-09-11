package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * The abandon screen ({@code LC-04-04-06} to {@code -12}): the last thing an operator
 * sees before a sale stops existing.
 *
 * <p>ONE SCREEN FOR BOTH PHASES. The abandon is reachable while the articles are being
 * rung and while the customer is paying ({@code LC-04-04-02} and {@code -05}), and the
 * questions it has to ask are the same either way — which reason, what happens to a
 * settlement already taken, and whether a paper comes out. Two screens would be two
 * chances for the shop's rules to be applied on one path and not on the other.
 *
 * <p>It asks and it does not decide: the abandon itself still goes through the manager
 * endorsement, exactly as before.
 */
@Path("/abandon")
@DrawerMustBeClosed
public class TicketAbandonResource {

    /** The register state the screen reads. */
    @Inject
    PosState state;

    /** The shop's abandon rules. */
    @Inject
    TicketAbandonService ticketAbandonService;

    /** Carries the abandon to the manager endorsement, as it always did. */
    @Inject
    com.intermarche.pos.ui.home.HomeService homeService;

    /** The abandon screen. */
    @Inject
    @Location("abandon")
    Template abandonPage;

    /**
     * Shows the abandon screen.
     *
     * @return the abandon page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance showAbandonScreen() {
        return page(null);
    }

    /**
     * Confirms the abandon: records the reason and the printing choice, then hands
     * the gesture to the manager endorsement.
     *
     * @param reason the reason the operator picked, blank when the shop asks for none
     * @param print  {@code on} when the operator asked for the abandon ticket
     * @return a redirect to the sale screen, or the abandon page carrying the refusal
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Object confirmAbandon(@FormParam("reason") String reason,
            @FormParam("print") String print) {
        String refusal = ticketAbandonService.prepare(reason, print != null);
        if (refusal != null) {
            return page(refusal);
        }
        // The abandon is a guarded gesture like the others: the request parks it and
        // the manager credential dispatches it (CANCEL_TICKET).
        homeService.cancelTicket();
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Renders the abandon page.
     *
     * @param error the refusal to show, or null
     * @return the abandon page
     */
    private TemplateInstance page(String error) {
        return abandonPage
                .data("state", state)
                .data("reasons", ticketAbandonService.reasons())
                .data("blocking", ticketAbandonService.blockingReason())
                .data("payments", ticketAbandonService.partialPayments())
                .data("paymentTotal", ticketAbandonService.partialPaymentTotal())
                .data("printOnDemand", ticketAbandonService.printOnDemand())
                .data("error", error);
    }
}
