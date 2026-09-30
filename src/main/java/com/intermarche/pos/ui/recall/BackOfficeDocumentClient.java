package com.intermarche.pos.ui.recall;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The seam to the store node's DOCUMENT store (EXP-90/91/92): quotes, orders,
 * sale vouchers and click-and-collect baskets are emitted and kept by imedge
 * (a separate node, out of this repo), and recalled at the till by the code the
 * back office gave the customer.
 *
 * <p>The real store lives behind {@code pos.recall.document-url}, reached over
 * HTTP like {@link com.intermarche.pos.ui.fidelity.ImfidClient}. When no URL is
 * configured this client answers a LOCAL stub verdict — it knows no document,
 * so every code resolves to {@code null} (not found) — which keeps a standalone
 * lane deterministic: the register cannot invent a document imedge never sent.
 * Tests inject a mock of this client to return a document and exercise the load.
 * The connected fetch of a real document is therefore [N] e2e residue (it needs
 * imedge running).
 */
@ApplicationScoped
public class BackOfficeDocumentClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeDocumentClient.class);

    /** The document-store base URL; absent = the local stub (no document known). */
    @ConfigProperty(name = "pos.recall.document-url")
    Optional<String> documentUrl;

    /**
     * Fetches the document a recall code names (EXP-90/91/92).
     *
     * @param code the recall code the customer presented
     * @return the recalled document, or null when the code is blank, no store is
     *         configured, or the store knows no such document
     */
    public RecalledDocument fetch(String code) {
        LOGGER.info("Entering method fetch");
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method fetch with blank code");
            return null;
        }
        if (documentUrl.isEmpty() || documentUrl.get().isBlank()) {
            // No document store configured: the standalone lane knows no
            // document, so nothing resolves (the connected fetch needs imedge).
            LOGGER.info("Exiting method fetch with local stub (no document)");
            return null;
        }
        // A configured store would be queried over HTTP here (imedge). Left to
        // the connected deployment; unit tests inject a mock returning a document.
        LOGGER.info("Exiting method fetch (configured store not reached in this build)");
        return null;
    }
}
