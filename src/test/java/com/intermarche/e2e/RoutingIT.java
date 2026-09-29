package com.intermarche.e2e;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * JAX-RS ROUTING guard for the e2e/demo stack: proves the hardware scan bus
 * ({@code POST /api/pos/scan} on {@link com.intermarche.pos.ui.home.PosHardwareResource})
 * is actually reached, and is not shadowed by an embedded {@code src/mock}
 * simulator claiming a broad {@code /api} class path.
 * <p>
 * RESTEasy classic selects the resource CLASS by the most specific LITERAL path
 * first: a mock at {@code @Path("/api")} beats {@code PosHardwareResource} at
 * {@code @Path("/")}, so every uncovered {@code /api/**} URI (among them
 * {@code /api/pos/scan}) resolves against that mock and answers 404 — the exact
 * trap already spelled out in {@link com.intermarche.pos.ui.resource.MockHardwareResource}.
 * When that happens EVERY screen scenario that presents a badge on the bus fails
 * with "the hardware scan bus should accept …"; this test isolates the routing
 * cause so a regression is caught here rather than in twenty scenario failures.
 * <p>
 * No browser: it boots one @QuarkusTest instance under {@link E2eTestProfile}
 * (the same profile the groups use, so the {@code src/mock} simulators are on
 * the classpath) and drives the HTTP surface directly with the JDK client.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
class RoutingIT {

    /** The base URL of the booted test application (its @QuarkusTest port). */
    @TestHTTPResource("/")
    URL base;

    /**
     * Sends one HTTP request to a path of the test application.
     *
     * @param method the HTTP method
     * @param path the path, relative to the application base (leading slash)
     * @param contentType the request Content-Type
     * @param body the request body
     * @return the response
     * @throws Exception if the exchange fails
     */
    private HttpResponse<String> send(String method, String path, String contentType, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base.toString().replaceAll("/$", "") + path))
                .header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The scan bus accepts a non-empty code: {@code POST /api/pos/scan} reaches
     * {@code handleScan} and answers 200 — proving the mock does not shadow it.
     * A 404 here is the regression this campaign fixes.
     *
     * @throws Exception if the exchange fails
     */
    @Test
    void scanBusAcceptsNonEmptyCode() throws Exception {
        HttpResponse<String> response = send("POST", "/api/pos/scan", "text/plain", "12341234");
        Assertions.assertEquals(200, response.statusCode(),
                "POST /api/pos/scan should reach handleScan and answer 200, not 404");
    }

    /**
     * The scan bus rejects an empty code with 400: this is {@code handleScan}'s
     * own answer, so a 400 (not a 404) proves the request reaches the real
     * handler rather than the shadowing mock.
     *
     * @throws Exception if the exchange fails
     */
    @Test
    void scanBusRejectsEmptyCodeWithBadRequest() throws Exception {
        HttpResponse<String> response = send("POST", "/api/pos/scan", "text/plain", "");
        Assertions.assertEquals(400, response.statusCode(),
                "an empty scan code must yield handleScan's 400, proving it is reached (not a 404)");
    }

    /**
     * The consumption simulator stays reachable at its unchanged effective URL
     * {@code /api/consumptions}: {@code POST} answers 200 and acknowledges with
     * {@code accepted}, so moving its path onto the class does not break the
     * delivery loop.
     *
     * @throws Exception if the exchange fails
     */
    @Test
    void consumptionSimulatorStillReachable() throws Exception {
        HttpResponse<String> response = send("POST", "/api/consumptions", "application/json", "{}");
        Assertions.assertEquals(200, response.statusCode(),
                "POST /api/consumptions must still answer 200");
        Assertions.assertTrue(response.body().contains("accepted"),
                "the consumption simulator must acknowledge with an 'accepted' body");
    }
}
