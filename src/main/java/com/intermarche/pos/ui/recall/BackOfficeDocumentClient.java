package com.intermarche.pos.ui.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * The seam to the store node's DOCUMENT store (EXP-90/91/92): quotes, orders,
 * sale vouchers and click-and-collect baskets are emitted and kept by imedge
 * (a separate node, out of this repo), and recalled at the till by the code the
 * back office gave the customer.
 *
 * <p>The real store lives behind {@code pos.recall.document-url}, reached over
 * HTTP like {@link com.intermarche.pos.ui.fidelity.ImfidClient}: when a URL is
 * configured the register issues {@code GET <url>/api/orders/{code}} carrying the
 * shared {@code X-Sync-Token}, and parses the answer into a {@link RecalledDocument}.
 * When no URL is configured this client answers a LOCAL stub verdict — it knows no
 * document, so every code resolves to {@code null} (not found) — which keeps a
 * standalone lane deterministic: the register cannot invent a document imedge never
 * sent. The call is TOLERANT by construction: a node down, unreachable, a non-2xx
 * answer or an empty body all resolve to {@code null} rather than an exception, so a
 * network hiccup never disturbs a sale. The endpoint is guarded node-side by
 * {@code pos.role=store} + the token; the connected fetch is [N] e2e residue (it
 * needs imedge running).
 */
@ApplicationScoped
public class BackOfficeDocumentClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeDocumentClient.class);

    /** The document-store base URL; absent = the local stub (no document known). */
    @ConfigProperty(name = "pos.recall.document-url")
    Optional<String> documentUrl;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The JSON reader for the node's answer. */
    @jakarta.inject.Inject
    ObjectMapper objectMapper;

    /** Shared HTTP client toward the node (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Fetches the document a recall code names (EXP-90/91/92).
     *
     * @param code the recall code the customer presented
     * @return the recalled document, or null when the code is blank, no store is
     *         configured, the store knows no such document, or the node could not
     *         be reached
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
        try {
            String encoded = URLEncoder.encode(code.trim(), StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(documentUrl.get().trim() + "/api/orders/" + encoded))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method fetch (node answered no document)");
                return null;
            }
            RecalledDocument document =
                    objectMapper.readValue(response.body(), RecalledDocument.class);
            LOGGER.info("Exiting method fetch (document recalled from node)");
            return document;
        } catch (Exception e) {
            // Tolerant: a node down or a malformed answer is "no document",
            // never a thrown error that could disturb the lane.
            LOGGER.warnf("Nœud magasin injoignable pour le document %s : %s", code, e.getMessage());
            LOGGER.info("Exiting method fetch (node unreachable, no document)");
            return null;
        }
    }
}
