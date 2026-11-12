package com.intermarche.pos.ui.home;

import com.intermarche.pos.domain.store.CashFunction;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Refuses, server-side, every till function the back-office menu model hides
 * ({@code LS-12-02-03}, {@code BO-03-09-06}).
 *
 * <p>ONE GUARD FOR EVERY FUNCTION. The model hides a function by removing its key from
 * the grid; until this filter, the function's address still answered — {@code /invoice}
 * served the invoice screen of a shop that issues no invoice. The refusal is the till's
 * usual one: the message band says the function is not available on this register and
 * the operator is sent back to the sale screen, exactly as a function refused in
 * training mode.
 *
 * <p>The session screen is the one exception while no session is open: it is where the
 * register itself sends an operator who must open one, and refusing it there would
 * bounce the operator between the two forever. Once a session is open, a model that
 * hides it closes it like any other function.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 30)
public class HiddenFunctionFilter implements ContainerRequestFilter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HiddenFunctionFilter.class);

    /** The register state, whose message band carries the refusal. */
    @Inject
    PosState state;

    /** The menu model in force and the functions it hides. */
    @Inject
    CashMenuService cashMenuService;

    /** The open session, which the session screen needs to be refused at all. */
    @Inject
    CashSessionService cashSessionService;

    /** The operator messages. */
    @Inject
    Messages i18n;

    /**
     * Aborts a request reaching a function the model in force hides, before its resource
     * can run.
     *
     * @param context the incoming request
     */
    @Override
    public void filter(ContainerRequestContext context) {
        String raw = context.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        CashFunction hidden = cashMenuService.hiddenFunctionReached(path,
                context.getUriInfo().getQueryParameters());
        if (hidden == null) {
            return;
        }
        if (hidden == CashFunction.SESSION && cashSessionService.getOpenSession() == null) {
            return;
        }
        LOGGER.infof("Fonction %s masquée par le modèle de menu : %s refusé", hidden.name(), path);
        state.ticket.setError(i18n.get("op.fonctionMasqueeMenu"));
        state.touch();
        context.abortWith(Response.seeOther(URI.create("/")).build());
    }
}
