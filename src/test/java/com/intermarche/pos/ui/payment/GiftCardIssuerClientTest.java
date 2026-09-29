package com.intermarche.pos.ui.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Unit tests for {@link GiftCardIssuerClient} (EXP-66), targeting 100% branch
 * coverage.
 * <p>
 * The client is a thin seam over an optional issuer-registry URL: {@code query}
 * refuses a null or blank card number, answers the local stub verdict (valid,
 * nil balance) when no registry URL is configured, and — with a URL present —
 * takes the configured-registry arm (which, until a transport is wired, returns
 * the same honoured verdict). The URL is a package-private {@code Optional}
 * field set directly, no CDI. Each test is isolated and asserts absolute values.
 */
class GiftCardIssuerClientTest {

    /**
     * Builds a client with the given issuer-URL Optional wired on its field.
     *
     * @param url the issuer-registry URL Optional
     * @return the client under test
     */
    private GiftCardIssuerClient client(Optional<String> url) {
        GiftCardIssuerClient client = new GiftCardIssuerClient();
        client.issuerUrl = url;
        return client;
    }

    /**
     * A null card number is refused with a nil balance (first guard, null leg).
     */
    @Test
    void queryRefusesNullCard() {
        GiftCardIssuerClient.IssuerVerdict verdict = client(Optional.empty()).query(null);
        Assertions.assertFalse(verdict.valid());
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(verdict.balance()));
    }

    /**
     * A blank card number is refused (first guard, isBlank leg).
     */
    @Test
    void queryRefusesBlankCard() {
        Assertions.assertFalse(client(Optional.empty()).query("   ").valid());
    }

    /**
     * With no registry URL configured (empty Optional), the local stub honours a
     * non-blank card with a nil balance.
     */
    @Test
    void queryHonoursLocallyWhenNoUrl() {
        GiftCardIssuerClient.IssuerVerdict verdict = client(Optional.empty()).query("GC1");
        Assertions.assertTrue(verdict.valid());
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(verdict.balance()));
    }

    /**
     * With a blank URL string present, the stub arm still applies (isBlank leg of
     * the URL guard).
     */
    @Test
    void queryHonoursLocallyWhenUrlBlank() {
        Assertions.assertTrue(client(Optional.of("   ")).query("GC1").valid());
    }

    /**
     * With a non-blank registry URL configured, the configured-registry arm is
     * taken (both URL-guard legs false); until a transport is wired it returns
     * the honoured verdict.
     */
    @Test
    void queryTakesConfiguredRegistryArm() {
        GiftCardIssuerClient.IssuerVerdict verdict = client(Optional.of("http://issuer")).query("GC1");
        Assertions.assertTrue(verdict.valid());
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(verdict.balance()));
    }
}
