package com.intermarche.pos.ui.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BackupPaymentTicket}.
 * <p>
 * The two payloads and the signature that binds them, checked the way an attacker
 * would probe them: a tampered amount, a tampered scheme, a signature from another
 * secret, a payload of the wrong shape, a payload of the right shape with the wrong
 * marker, an amount that is not a number and an amount that is zero or negative.
 * Every one of those is a null answer, deliberately — the caller cannot act on the
 * difference. The matching rule is then checked on each of its three fields
 * separately, because a response that agrees on two out of three is exactly the
 * shape a replay from the next till takes.
 */
class BackupPaymentTicketTest {

    /** The shared secret used throughout. */
    private static final String SECRET = "secret-magasin";

    /** A fixed emission instant, so the payload is reproducible. */
    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 9, 10, 14, 30, 0);

    /**
     * Builds the request the register emits in every test.
     *
     * @return the request
     */
    private BackupPaymentTicket.Request request() {
        return new BackupPaymentTicket.Request("D", 2462L, 1200L, "T-0042", "0001",
                "CAISSE-01", STAMP);
    }

    /**
     * Builds a signed response payload with the given fields.
     *
     * @param accepted the accepted amount in cents, as text
     * @param methodId the scheme identifier
     * @param transaction the transaction number
     * @param pdv the point-of-sale number
     * @param terminal the register
     * @param receipt the base64 card slip
     * @param secret the secret to sign with
     * @return the full payload
     */
    private String response(String accepted, String methodId, String transaction, String pdv,
            String terminal, String receipt, String secret) {
        String body = String.join("|", "A1", accepted, methodId, transaction, pdv, terminal,
                receipt);
        return body + "|" + BackupPaymentTicket.sign(body, secret);
    }

    /**
     * Builds the nominal signed response to {@link #request()}.
     *
     * @return the payload
     */
    private String nominalResponse() {
        return response("2462", "20", "T-0042", "0001", "CAISSE-01", "", SECRET);
    }

    // --------------------------------------------------
    // The request
    // --------------------------------------------------

    /**
     * The request carries its fields in a fixed order, followed by its signature.
     */
    @Test
    void theRequestCarriesItsFieldsAndSignature() {
        String encoded = request().encode(SECRET);
        String[] parts = encoded.split("\\|");
        assertEquals(9, parts.length);
        assertEquals("R1", parts[0]);
        assertEquals("D", parts[1]);
        assertEquals("2462", parts[2]);
        assertEquals("1200", parts[3]);
        assertEquals("T-0042", parts[4]);
        assertEquals("0001", parts[5]);
        assertEquals("CAISSE-01", parts[6]);
        assertEquals("20260910143000", parts[7]);
        assertEquals(BackupPaymentTicket.SIGNATURE_LENGTH, parts[8].length());
    }

    /**
     * Two shops with different secrets sign the same request differently — which is
     * the whole reason the secret exists.
     */
    @Test
    void differentSecretsSignDifferently() {
        assertNotEquals(request().encode(SECRET), request().encode("autre-secret"));
    }

    /**
     * A blank secret still produces a signature, so a misconfigured shop shows up as
     * a refused code and never as an unreadable payload.
     */
    @Test
    void aBlankSecretStillSigns() {
        String encoded = request().encode("");
        assertEquals(BackupPaymentTicket.SIGNATURE_LENGTH,
                encoded.split("\\|")[8].length());
    }

    /**
     * A null secret takes the same leg as a blank one.
     */
    @Test
    void aNullSecretStillSigns() {
        assertNotNull(request().encode(null));
    }

    // --------------------------------------------------
    // Decoding the response
    // --------------------------------------------------

    /**
     * A well-formed, correctly signed response decodes into its fields.
     */
    @Test
    void aValidResponseDecodes() {
        BackupPaymentTicket.Response decoded =
                BackupPaymentTicket.decodeResponse(nominalResponse(), SECRET);
        assertNotNull(decoded);
        assertEquals(2462L, decoded.acceptedCents());
        assertEquals("20", decoded.methodId());
        assertEquals("T-0042", decoded.transactionNumber());
        assertEquals("0001", decoded.pdv());
        assertEquals("CAISSE-01", decoded.terminal());
    }

    /**
     * A null payload decodes to nothing.
     */
    @Test
    void aNullPayloadIsRefused() {
        assertNull(BackupPaymentTicket.decodeResponse(null, SECRET));
    }

    /**
     * A payload with the wrong number of fields is refused.
     */
    @Test
    void aTruncatedPayloadIsRefused() {
        assertNull(BackupPaymentTicket.decodeResponse("A1|2462|20", SECRET));
    }

    /**
     * A payload of the right shape but the wrong marker is refused: it is not an
     * answer, it is something else entirely — the request, for instance.
     */
    @Test
    void aPayloadWithTheWrongMarkerIsRefused() {
        String bad = nominalResponse().replaceFirst("A1", "R1");
        assertNull(BackupPaymentTicket.decodeResponse(bad, SECRET));
    }

    /**
     * An amount raised after signing is refused: the signature no longer holds.
     */
    @Test
    void aTamperedAmountIsRefused() {
        String tampered = nominalResponse().replaceFirst("2462", "9999");
        assertNull(BackupPaymentTicket.decodeResponse(tampered, SECRET));
    }

    /**
     * A scheme changed after signing is refused for the same reason.
     */
    @Test
    void aTamperedMethodIsRefused() {
        String tampered = nominalResponse().replaceFirst("\\|20\\|", "|43|");
        assertNull(BackupPaymentTicket.decodeResponse(tampered, SECRET));
    }

    /**
     * A payload signed with another secret is refused — the neighbouring shop's
     * handheld cannot settle a sale here.
     */
    @Test
    void aPayloadSignedWithAnotherSecretIsRefused() {
        String foreign = response("2462", "20", "T-0042", "0001", "CAISSE-01", "", "autre");
        assertNull(BackupPaymentTicket.decodeResponse(foreign, SECRET));
    }

    /**
     * An amount that is not a number is refused rather than throwing.
     */
    @Test
    void anUnreadableAmountIsRefused() {
        assertNull(BackupPaymentTicket.decodeResponse(
                response("beaucoup", "20", "T-0042", "0001", "CAISSE-01", "", SECRET), SECRET));
    }

    /**
     * A zero amount is refused: a terminal that accepted nothing settles nothing.
     */
    @Test
    void aZeroAmountIsRefused() {
        assertNull(BackupPaymentTicket.decodeResponse(
                response("0", "20", "T-0042", "0001", "CAISSE-01", "", SECRET), SECRET));
    }

    /**
     * A negative amount is refused — the other side of the same guard.
     */
    @Test
    void aNegativeAmountIsRefused() {
        assertNull(BackupPaymentTicket.decodeResponse(
                response("-100", "20", "T-0042", "0001", "CAISSE-01", "", SECRET), SECRET));
    }

    /**
     * A card slip rides through untouched, base64 and all.
     */
    @Test
    void theCardSlipRidesThrough() {
        String slip = java.util.Base64.getEncoder()
                .encodeToString("TICKET CB".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BackupPaymentTicket.Response decoded = BackupPaymentTicket.decodeResponse(
                response("2462", "20", "T-0042", "0001", "CAISSE-01", slip, SECRET), SECRET);
        assertNotNull(decoded);
        assertEquals(slip, decoded.receiptBase64());
    }

    // --------------------------------------------------
    // Matching the response to the request
    // --------------------------------------------------

    /**
     * A response naming the same sale, shop and register matches.
     */
    @Test
    void theRightAnswerMatches() {
        BackupPaymentTicket.Response decoded =
                BackupPaymentTicket.decodeResponse(nominalResponse(), SECRET);
        assertTrue(BackupPaymentTicket.matches(request(), decoded));
    }

    /**
     * A response for another sale does not match — a code still up from the previous
     * customer.
     */
    @Test
    void anotherTransactionDoesNotMatch() {
        BackupPaymentTicket.Response decoded = BackupPaymentTicket.decodeResponse(
                response("2462", "20", "T-0099", "0001", "CAISSE-01", "", SECRET), SECRET);
        assertFalse(BackupPaymentTicket.matches(request(), decoded));
    }

    /**
     * A response from another shop does not match.
     */
    @Test
    void anotherPdvDoesNotMatch() {
        BackupPaymentTicket.Response decoded = BackupPaymentTicket.decodeResponse(
                response("2462", "20", "T-0042", "0009", "CAISSE-01", "", SECRET), SECRET);
        assertFalse(BackupPaymentTicket.matches(request(), decoded));
    }

    /**
     * A response for the till next door does not match — the replay this rule exists
     * for.
     */
    @Test
    void anotherTerminalDoesNotMatch() {
        BackupPaymentTicket.Response decoded = BackupPaymentTicket.decodeResponse(
                response("2462", "20", "T-0042", "0001", "CAISSE-02", "", SECRET), SECRET);
        assertFalse(BackupPaymentTicket.matches(request(), decoded));
    }

    /**
     * With no request pending nothing matches.
     */
    @Test
    void nothingMatchesWithoutARequest() {
        BackupPaymentTicket.Response decoded =
                BackupPaymentTicket.decodeResponse(nominalResponse(), SECRET);
        assertFalse(BackupPaymentTicket.matches(null, decoded));
    }

    /**
     * A null response matches nothing — the second leg of the same guard.
     */
    @Test
    void aNullResponseMatchesNothing() {
        assertFalse(BackupPaymentTicket.matches(request(), null));
    }

    // --------------------------------------------------
    // Amount conversion
    // --------------------------------------------------

    /**
     * Euros become the cents the payloads carry.
     */
    @Test
    void amountsBecomeCents() {
        assertEquals(2462L, BackupPaymentTicket.toCents(new BigDecimal("24.62")));
    }

    /**
     * A null amount becomes zero cents rather than throwing.
     */
    @Test
    void aNullAmountBecomesZeroCents() {
        assertEquals(0L, BackupPaymentTicket.toCents(null));
    }

    /**
     * Cents come back as euros with two decimals.
     */
    @Test
    void centsBecomeEuros() {
        assertEquals(0, new BigDecimal("24.62").compareTo(BackupPaymentTicket.toEuro(2462L)));
    }
}
