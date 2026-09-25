package com.intermarche.pos.ui.fidelity;

/**
 * The loyalty service answered, and its answer is not one this register can
 * use: an HTTP status the client has no reading for.
 *
 * <p>Why a type of its own rather than the {@code IllegalStateException} that
 * stood here. The two say opposite things to whoever catches them. An
 * {@code IllegalStateException} means the program was asked to do something in
 * a state where it makes no sense — a bug on this side, to be fixed. This
 * exception means the OTHER side is unreachable or unhappy — an operating
 * condition, to be degraded around, which is exactly what a register must do
 * when its loyalty service is down: sell anyway, without the loyalty display.
 * Carrying both in the same class forced every caller to catch {@code
 * Exception} and hope, which is how a programming bug ends up silently
 * downgrading a sale instead of being reported.
 *
 * <p>The HTTP status travels with the exception, not only inside the message:
 * a 404 (the card is unknown), a 409 (the account is busy) and a 503 (imfid is
 * down) call for different reactions, and a caller should not have to parse a
 * sentence to tell them apart. The statuses that carry a business meaning are
 * already read by the client itself and never reach here — this exception is
 * what remains when no reading applies.
 *
 * <p>UNCHECKED on purpose: the loyalty calls sit on the sale path, where every
 * failure funnels into the same degraded handling. A checked exception would
 * add a {@code throws} clause to that whole path without adding a decision.
 */
public class ImfidUnavailableException extends RuntimeException {

    /** Serialisation marker of the exception hierarchy. */
    private static final long serialVersionUID = 1L;

    /** The endpoint that answered, as the client names it in its traces. */
    private final String endpoint;

    /** The HTTP status the loyalty service answered with. */
    private final int httpStatus;

    /**
     * Builds the failure of one loyalty call.
     *
     * @param endpoint the endpoint that answered, as the client names it
     * @param httpStatus the HTTP status answered
     * @param body the answer body, appended when it carries something; a
     *             String body handler yields an empty string and never null
     */
    public ImfidUnavailableException(String endpoint, int httpStatus, String body) {
        super("imfid " + endpoint + " answered " + httpStatus
                + (body == null || body.isEmpty() ? "" : " " + body));
        this.endpoint = endpoint;
        this.httpStatus = httpStatus;
    }

    /**
     * Builds the failure of one loyalty call whose body carries nothing worth
     * quoting.
     *
     * @param endpoint the endpoint that answered, as the client names it
     * @param httpStatus the HTTP status answered
     */
    public ImfidUnavailableException(String endpoint, int httpStatus) {
        this(endpoint, httpStatus, null);
    }

    /**
     * Returns the endpoint that answered.
     *
     * @return the endpoint, as the client names it
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Returns the HTTP status the loyalty service answered with.
     *
     * @return the HTTP status
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
