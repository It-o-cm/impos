package com.intermarche.pos.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Shared @QuarkusTest profile for every E2E group (GroupAIT, GroupBIT, ...),
 * so each GroupXIT reuses it instead of redeclaring the overrides.
 * <p>
 * It pins {@code pos.terminal.id=C04} (the default POS01 would break the
 * {@code C04-Sxxxxx} assertions) and points the hardware rest-client at the
 * test application's OWN base URL ({@code http://localhost:8081}, the
 * @QuarkusTest port) so peripheral calls hit the embedded simulator
 * (MockHardwareResource, wired into the test classpath from src/mock) and
 * return 200 — no ConnectException/404 stack traces in the logs.
 */
public class E2eTestProfile implements QuarkusTestProfile {

    /**
     * Supplies the fixed configuration overrides shared by every E2E group.
     *
     * @return the configuration overrides applied under this test profile
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "pos.terminal.id", "C04",
                "quarkus.rest-client.hardware-api.url", "http://localhost:8081");
    }
}
