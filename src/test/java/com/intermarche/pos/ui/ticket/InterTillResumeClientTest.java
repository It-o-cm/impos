package com.intermarche.pos.ui.ticket;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Unit tests for {@link InterTillResumeClient} (EXP-46).
 * <p>
 * The client is the seam to the store node's inter-till parked-ticket resume.
 * With no {@code pos.parking.node-url} it is a LOCAL stub that reaches nothing,
 * so every lookup resolves to null and the standalone lane keeps its offline
 * behaviour. Coverage exercises the blank-input guard (both null and blank arms)
 * and the no-URL guard (both empty and blank arms); the configured-node HTTP
 * fetch is [N] residue (needs imedge) and is not reachable in this build.
 */
class InterTillResumeClientTest {

    /**
     * Builds a client with the given node URL Optional set on its field.
     *
     * @param nodeUrl the node URL Optional to inject
     * @return the wired client
     */
    private InterTillResumeClient clientWith(Optional<String> nodeUrl) {
        InterTillResumeClient client = new InterTillResumeClient();
        client.nodeUrl = nodeUrl;
        return client;
    }

    /**
     * A null ticket number resolves to null (blank-guard null arm), whatever the
     * node configuration.
     */
    @Test
    void nullNumberResolvesToNull() {
        Assertions.assertNull(clientWith(Optional.of("http://node")).fetchParkedTicket(null));
    }

    /**
     * A blank ticket number resolves to null (blank-guard isBlank arm).
     */
    @Test
    void blankNumberResolvesToNull() {
        Assertions.assertNull(clientWith(Optional.of("http://node")).fetchParkedTicket("   "));
    }

    /**
     * With no node URL configured, a real number still resolves to null: the
     * offline stub reaches no node (Optional-empty arm).
     */
    @Test
    void noNodeUrlResolvesToNull() {
        Assertions.assertNull(clientWith(Optional.empty()).fetchParkedTicket("C04-00000001"));
    }

    /**
     * With a blank node URL, a real number still resolves to null (isBlank arm of
     * the no-URL guard).
     */
    @Test
    void blankNodeUrlResolvesToNull() {
        Assertions.assertNull(clientWith(Optional.of("   ")).fetchParkedTicket("C04-00000001"));
    }

    /**
     * A configured node is not reached in this build (the connected fetch is [N]
     * residue): a real number resolves to null rather than inventing a ticket.
     */
    @Test
    void configuredNodeIsNotReachedInThisBuild() {
        Assertions.assertNull(clientWith(Optional.of("http://node")).fetchParkedTicket("C04-00000001"));
    }
}
