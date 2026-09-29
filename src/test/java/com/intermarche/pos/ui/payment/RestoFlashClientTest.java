package com.intermarche.pos.ui.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Unit tests for {@link RestoFlashClient} (EXP-67), targeting 100% branch
 * coverage.
 * <p>
 * The client is a thin seam over an optional Resto Flash URL: {@code confirm}
 * refuses a null/blank token or a non-positive amount, confirms via the local
 * stub when no URL is configured, and takes the configured-service arm when a
 * URL is present. The URL is a package-private {@code Optional} field set
 * directly, no CDI. Each test is isolated and asserts absolute values.
 */
class RestoFlashClientTest {

    /**
     * Builds a client with the given service-URL Optional wired on its field.
     *
     * @param url the Resto Flash service URL Optional
     * @return the client under test
     */
    private RestoFlashClient client(Optional<String> url) {
        RestoFlashClient client = new RestoFlashClient();
        client.serviceUrl = url;
        return client;
    }

    /**
     * A null token is refused (first guard, token-null leg).
     */
    @Test
    void confirmRefusesNullToken() {
        Assertions.assertFalse(client(Optional.empty()).confirm(null, new BigDecimal("5")));
    }

    /**
     * A blank token is refused (token-isBlank leg).
     */
    @Test
    void confirmRefusesBlankToken() {
        Assertions.assertFalse(client(Optional.empty()).confirm("  ", new BigDecimal("5")));
    }

    /**
     * A null amount is refused (amount-null leg).
     */
    @Test
    void confirmRefusesNullAmount() {
        Assertions.assertFalse(client(Optional.empty()).confirm("TOK", null));
    }

    /**
     * A non-positive amount is refused (amount-signum leg).
     */
    @Test
    void confirmRefusesNonPositiveAmount() {
        Assertions.assertFalse(client(Optional.empty()).confirm("TOK", BigDecimal.ZERO));
    }

    /**
     * With no service URL configured, a valid token confirms via the local stub.
     */
    @Test
    void confirmConfirmsLocallyWhenNoUrl() {
        Assertions.assertTrue(client(Optional.empty()).confirm("TOK", new BigDecimal("5")));
    }

    /**
     * With a blank URL string present, the stub arm still confirms (URL-isBlank leg).
     */
    @Test
    void confirmConfirmsLocallyWhenUrlBlank() {
        Assertions.assertTrue(client(Optional.of("   ")).confirm("TOK", new BigDecimal("5")));
    }

    /**
     * With a non-blank service URL, the configured-service arm is taken (both
     * URL-guard legs false); until a transport is wired it confirms.
     */
    @Test
    void confirmTakesConfiguredServiceArm() {
        Assertions.assertTrue(client(Optional.of("http://restoflash")).confirm("TOK", new BigDecimal("5")));
    }
}
