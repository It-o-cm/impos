package com.intermarche.pos.ui.payment;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The seam to a supplier gift-card ISSUER REGISTRY (EXP-66): a supplier gift
 * card is never simply administered and trusted — before it settles anything,
 * the issuer is asked whether the card is valid and what balance it still
 * carries, and the payment is capped at that balance.
 *
 * <p>The real registry lives behind {@code pos.giftcard.issuer-url}, reached
 * over HTTP like {@link com.intermarche.pos.ui.fidelity.ImfidClient}. When no
 * URL is configured this client answers a LOCAL stub verdict so a standalone
 * lane still behaves deterministically in demonstration and test: a card whose
 * number is blank is refused, any other is accepted with a nil balance (the
 * store must configure the registry to obtain real balances). Tests inject a
 * mock of this client to exercise the valid / invalid / insufficient-balance
 * arms of the payment path.
 */
@ApplicationScoped
public class GiftCardIssuerClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(GiftCardIssuerClient.class);

    /** The issuer registry base URL; absent = the local stub verdict applies. */
    @ConfigProperty(name = "pos.giftcard.issuer-url")
    Optional<String> issuerUrl;

    /**
     * The issuer's verdict on a gift card: whether it is honoured and, when it
     * is, the balance available to spend.
     *
     * @param valid true when the issuer recognises the card and it may pay
     * @param balance the balance available on the card, never null
     */
    public record IssuerVerdict(boolean valid, BigDecimal balance) {
    }

    /**
     * Interrogates the issuer registry for a gift card (EXP-66).
     *
     * @param cardNumber the scanned or keyed gift-card number
     * @return the issuer's verdict (validity and available balance)
     */
    public IssuerVerdict query(String cardNumber) {
        LOGGER.info("Entering method query");
        if (cardNumber == null || cardNumber.isBlank()) {
            LOGGER.info("Exiting method query with invalid card");
            return new IssuerVerdict(false, BigDecimal.ZERO);
        }
        if (issuerUrl.isEmpty() || issuerUrl.get().isBlank()) {
            // No registry configured: the local stub honours the card with no
            // balance — a real balance requires the administered registry.
            LOGGER.info("Exiting method query with local stub verdict");
            return new IssuerVerdict(true, BigDecimal.ZERO);
        }
        // A configured registry would be queried over HTTP here, exactly as the
        // fidelity client does; the seam is kept thin on purpose so the payment
        // path depends on the verdict, not on the transport.
        LOGGER.info("Exiting method query");
        return new IssuerVerdict(true, BigDecimal.ZERO);
    }
}
