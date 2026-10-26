package com.intermarche.pos.ui.payment;

import com.intermarche.pos.ui.PosState;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Disarms the Resto Flash scan of the payment screen at every page navigation
 * (EXP-67).
 *
 * <p>The screen arms the scanner when its RESTO FLASH zone opens, by a script
 * call that asks for no page. Any page the browser then navigates to — the
 * payment screen redrawn after a settlement, the sale screen, the lock screen —
 * is drawn with that zone closed, so a code read afterwards must walk the sale's
 * chain again: the flag goes down here, before any resource answers. Script
 * calls and polls, which do not ask for HTML, leave it alone.
 */
@Provider
public class RestoFlashScanFilter implements ContainerRequestFilter {

    /** The register state carrying the flag. */
    @Inject
    PosState state;

    /**
     * Drops the Resto Flash arming on a page navigation.
     *
     * @param request the incoming request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        String accept = request.getHeaderString("Accept");
        if (accept != null && accept.contains("text/html")) {
            state.payment.restoFlashScanArmed = false;
        }
    }
}
