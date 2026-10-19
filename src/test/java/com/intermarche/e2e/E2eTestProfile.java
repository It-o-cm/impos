package com.intermarche.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Shared @QuarkusTest profile for every E2E group (GroupAIT, GroupBIT, ...),
 * so each GroupXIT reuses it instead of redeclaring the overrides.
 * <p>
 * It pins {@code pos.terminal.id=C04} (the default POS01 would break the
 * {@code C04-Sxxxxx} assertions) and points the hardware rest-client at the
 * test application's OWN base URL (localhost on the @QuarkusTest port) so
 * peripheral calls hit the embedded simulator
 * (MockHardwareResource, wired into the test classpath from src/mock) and
 * return 200 — no ConnectException/404 stack traces in the logs.
 * <p>
 * It also points {@code pos.valuation.url} at the test application itself:
 * {@code /api/valuation} is the embedded engine simulator
 * (MockValuationResource, same src/mock classpath), so the e2e campaign is
 * AUTONOMOUS — no externally running imvaluation — and the engine verdicts
 * a scenario asserts (the meal-voucher base) come from that simulator. The
 * stack-attached Demo* profiles override this URL back to the real engine.
 * <p>
 * THE PORT IS A PARAMETER, and it has to be. Because the application reaches
 * its own simulators by URL, that URL names a port, so the application cannot
 * be given a random one: the whole campaign is nailed to a single port, and any
 * leftover test JVM — a Playwright wait, a cheque reading running out its
 * deadline — holds it and the next run dies on "Port already bound". Pass
 * {@code -Dpos.e2e.port=8082} to run beside it. The default is unchanged, so a
 * plain run behaves exactly as before.
 */
public class E2eTestProfile implements QuarkusTestProfile {

    /** System property naming the port the test application listens on. */
    private static final String PORT_PROPERTY = "pos.e2e.port";

    /** The port used when nothing says otherwise — Quarkus' own test port. */
    private static final String DEFAULT_PORT = "8081";

    /**
     * Returns the port this run listens on, and reaches itself by.
     *
     * <p>A real port number, never {@code 0}: the application is told its own
     * address, so the address has to be known before it starts, which a port
     * drawn at bind time is not.
     *
     * @return the port as configured, or 8081 when the property is absent or blank
     */
    protected static String testPort() {
        String port = System.getProperty(PORT_PROPERTY, DEFAULT_PORT).trim();
        return port.isEmpty() ? DEFAULT_PORT : port;
    }

    /**
     * Supplies the configuration overrides shared by every E2E group.
     *
     * @return the configuration overrides applied under this test profile
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        String base = "http://localhost:" + testPort();
        return Map.of(
                "pos.terminal.id", "C04",
                "quarkus.http.test-port", testPort(),
                "quarkus.rest-client.hardware-api.url", base,
                "pos.valuation.url", base + "/api",
                // The fidelity-card scan pattern the groups rely on (same regex the
                // demo fid profiles pin): a 13-digit 299-prefixed card. Without it
                // the shared profile leaves the pattern unset, so a card scan is
                // never recognized as a fidelity card and #fidIcon never lights.
                "scan.pattern.fidelity", "^299\\d{10}$");
    }
}
