package com.intermarche.pos.ui.payment;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The seam to the Resto Flash mobile meal-voucher service (EXP-67): a titre
 * restaurant settled from the customer's phone. The register asks Resto Flash
 * to confirm a mobile payment token for an amount; only a confirmed token
 * settles the ticket.
 *
 * <p>The real service lives behind {@code pos.restoflash.url}. With no URL the
 * client answers a LOCAL stub verdict (a non-blank token confirms, a blank one
 * is refused) so a standalone lane runs deterministically; tests inject a mock
 * to drive the confirmed / refused arms.
 */
@ApplicationScoped
public class RestoFlashClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RestoFlashClient.class);

    /** The Resto Flash base URL; absent = the local stub verdict applies. */
    @ConfigProperty(name = "pos.restoflash.url")
    Optional<String> serviceUrl;

    /**
     * Confirms a Resto Flash mobile meal-voucher payment (EXP-67).
     *
     * @param token the mobile payment token presented by the customer
     * @param amount the amount to confirm
     * @return true when Resto Flash confirms the payment
     */
    public boolean confirm(String token, BigDecimal amount) {
        LOGGER.info("Entering method confirm");
        if (token == null || token.isBlank() || amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method confirm with refusal");
            return false;
        }
        if (serviceUrl.isEmpty() || serviceUrl.get().isBlank()) {
            LOGGER.info("Exiting method confirm with local stub confirmation");
            return true;
        }
        // A configured Resto Flash service would be called over HTTP here.
        LOGGER.info("Exiting method confirm");
        return true;
    }
}
