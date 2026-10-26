package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.sync.SaleActivityProbe;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The register's answer to the referential pull: a sale is in progress while
 * the ticket on screen carries a line or a settlement is under way, and the shop
 * protects it (BO-10-08-25, {@code referential.defer-during-sale}).
 *
 * <p>Read from the pull's own thread, which has no request: it looks at the
 * register's singleton state and its cached parameters, nothing else.
 */
@ApplicationScoped
public class OpenSaleProbe implements SaleActivityProbe {

    /** The register state, holding the ticket being rung up. */
    @Inject
    PosState state;

    /** Whether the shop protects the sale in progress. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Whether an update reaching the sale must wait.
     *
     * @return true while a ticket carries a line, or a settlement runs, and the
     *         shop protects the sale
     */
    @Override
    public boolean saleInProgress() {
        if (!posSettingsService.referentialDeferDuringSale()) {
            return false;
        }
        return !state.ticket.items.isEmpty() || state.payment.paymentInProgress;
    }
}
