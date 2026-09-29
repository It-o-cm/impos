package com.intermarche.pos.ui.resource;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the campaign-engine simulator {@link MockCampaignEngineResource}.
 * <p>
 * Plain object calls, no container: the simulator holds no state and no branch,
 * so each method is asserted on its absolute answer.
 */
class MockCampaignEngineResourceTest {

    /** The simulator under test. */
    private final MockCampaignEngineResource resource = new MockCampaignEngineResource();

    /**
     * A consumption declaration is acknowledged with a 200.
     */
    @Test
    void ingestConsumptionAcknowledges() {
        Response response = resource.ingestConsumption("{\"ticketId\":\"T1\"}");
        assertEquals(200, response.getStatus());
        assertTrue(response.getEntity().toString().contains("accepted"));
    }
}
