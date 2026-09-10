package com.intermarche.pos.ui.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two QR payloads exchanged with a backup-monetics terminal, and the signature
 * that binds them ({@code LC-07-07-07} and {@code -08}).
 *
 * <p>Two machines that have never spoken meet on a printed square. The register
 * emits a REQUEST — what to charge, on which sale, at which till — and reads back a
 * RESPONSE the terminal produced. Nothing about that exchange is secured by the
 * channel: a QR code on a screen can be photographed, replayed at another till, or
 * simply mistaken for another one still on the counter. The signature is what makes
 * the register able to refuse all three.
 *
 * <p>PIPE-SEPARATED FIELDS, in a fixed order, uppercase ASCII where possible: a QR
 * code holds more when it stays alphanumeric, and a handheld scanner reads it faster
 * for the same reason. The signature is an HMAC-SHA256 of the fields before it,
 * truncated to sixteen hex characters — long enough that guessing one is hopeless,
 * short enough that the code stays small on a 15-inch till screen.
 *
 * <p>Pure functions with no state and no injection: the same class builds a request
 * and verifies a response, so the two can never drift apart the way two mirrored
 * implementations would.
 */
public final class BackupPaymentTicket {

    /** The field separator, chosen for being absent from every field. */
    static final String SEPARATOR = "|";

    /** How many hex characters of the HMAC ride in the code. */
    static final int SIGNATURE_LENGTH = 16;

    /** The timestamp format carried in both payloads. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Not instantiable.
     */
    private BackupPaymentTicket() {
    }

    /**
     * What the register asks the mobile terminal to take ({@code LC-07-07-07}).
     *
     * @param kind D for a debit, C for a credit
     * @param totalCents the amount to charge, in cents
     * @param mealEligibleCents the part of it eligible for meal vouchers, in cents
     * @param transactionNumber the sale this settles
     * @param pdv the point-of-sale number
     * @param terminal the register
     * @param stamp when the request was emitted
     */
    public record Request(String kind, long totalCents, long mealEligibleCents,
            String transactionNumber, String pdv, String terminal, LocalDateTime stamp) {

        /**
         * Renders the signable body of the request, signature excluded.
         *
         * @return the fields joined in their fixed order
         */
        public String body() {
            return String.join(SEPARATOR, "R1", kind, String.valueOf(totalCents),
                    String.valueOf(mealEligibleCents), transactionNumber, pdv, terminal,
                    STAMP.format(stamp));
        }

        /**
         * Renders the full payload the QR code carries.
         *
         * @param secret the shared secret, possibly blank
         * @return the body followed by its signature
         */
        public String encode(String secret) {
            return body() + SEPARATOR + sign(body(), secret);
        }
    }

    /**
     * What the mobile terminal answers ({@code LC-07-07-08}).
     *
     * @param acceptedCents the amount actually accepted, in cents
     * @param methodId the scheme identifier, resolved against the administered table
     * @param transactionNumber the sale it claims to settle
     * @param pdv the point-of-sale number it was given
     * @param terminal the register it was given
     * @param receiptBase64 the card slip to print, base64, possibly empty
     */
    public record Response(long acceptedCents, String methodId, String transactionNumber,
            String pdv, String terminal, String receiptBase64) {
    }

    /**
     * Parses and verifies a response payload scanned off the mobile terminal.
     *
     * <p>Returns null on ANYTHING that does not add up — a payload of the wrong
     * shape, an unreadable amount, a bad signature. The caller cannot act on the
     * difference and must not: a response the register cannot vouch for is a
     * response it refuses, whichever way it is malformed.
     *
     * @param payload the scanned text
     * @param secret the shared secret, possibly blank
     * @return the verified response, or null when it cannot be trusted
     */
    public static Response decodeResponse(String payload, String secret) {
        if (payload == null) {
            return null;
        }
        String[] parts = payload.trim().split("\\" + SEPARATOR, -1);
        if (parts.length != 8 || !"A1".equals(parts[0])) {
            return null;
        }
        String body = String.join(SEPARATOR, java.util.Arrays.copyOfRange(parts, 0, 7));
        if (!sign(body, secret).equalsIgnoreCase(parts[7])) {
            return null;
        }
        long accepted;
        try {
            accepted = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (accepted <= 0) {
            return null;
        }
        return new Response(accepted, parts[2], parts[3], parts[4], parts[5], parts[6]);
    }

    /**
     * Tells whether a response answers the request that was emitted
     * ({@code LC-07-07-08}: the transaction, the point of sale and the register must
     * all match).
     *
     * @param request the request the register emitted, or null when none is pending
     * @param response the verified response
     * @return true when the response belongs to this request
     */
    public static boolean matches(Request request, Response response) {
        if (request == null || response == null) {
            return false;
        }
        return request.transactionNumber().equals(response.transactionNumber())
                && request.pdv().equals(response.pdv())
                && request.terminal().equals(response.terminal());
    }

    /**
     * Signs a payload body.
     *
     * <p>A blank secret produces a constant signature rather than none: the format
     * then stays identical between a shop that configured a secret and one that has
     * not, so a misconfiguration shows up as a refused code and never as a payload
     * the parser cannot even read.
     *
     * @param body the fields to sign
     * @param secret the shared secret, possibly blank
     * @return the truncated hex signature
     */
    static String sign(String body, String secret) {
        String key = secret == null || secret.isBlank() ? "impos-secours" : secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02X", b));
            }
            return hex.substring(0, SIGNATURE_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("Signature du secours monétique impossible", e);
        }
    }

    /**
     * Converts an amount in euros into the cents the payloads carry.
     *
     * @param amount the amount, possibly null
     * @return the amount in cents, zero when absent
     */
    public static long toCents(BigDecimal amount) {
        return amount == null ? 0L
                : amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /**
     * Converts the cents a payload carries back into euros.
     *
     * @param cents the amount in cents
     * @return the amount in euros, two decimals
     */
    public static BigDecimal toEuro(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
