package com.intermarche.pos.ui.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.service.sync.EngineFeedService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EngineFeedStatusResource}.
 * <p>
 * The resource is a pure renderer over {@code EngineFeedService.feedStates()}:
 * every answer is read back with a real JSON parser, so a field name, a null
 * or an escaped character that would break the document fails here.
 */
class EngineFeedStatusResourceTest {

    /**
     * Builds a resource whose feed store answers the given states.
     *
     * @param states the states the store returns
     * @return the wired resource
     */
    private EngineFeedStatusResource newResource(List<EngineFeedService.FeedState> states) {
        EngineFeedStatusResource resource = new EngineFeedStatusResource();
        EngineFeedService service = mock(EngineFeedService.class);
        when(service.feedStates()).thenReturn(states);
        resource.engineFeedService = service;
        return resource;
    }

    /**
     * Builds one feed state.
     *
     * @param code the feed code
     * @param version the stored version
     * @param appliedVersion the version the engine acknowledged, possibly null
     * @param applied whether the two match
     * @param lastError the last delivery error, possibly null
     * @return the state
     */
    private EngineFeedService.FeedState state(String code, String version, String appliedVersion,
                                              boolean applied, String lastError) {
        return new EngineFeedService.FeedState(code, version, appliedVersion, applied, lastError,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * Reads the answer back with a real JSON parser.
     *
     * @param resource the resource to call
     * @return the parsed array
     * @throws Exception when the answer is not valid JSON
     */
    private JsonNode parsed(EngineFeedStatusResource resource) throws Exception {
        return new ObjectMapper().readTree((String) resource.status().getEntity());
    }

    /**
     * No feed stored: the payload is an empty array.
     *
     * @throws Exception when the answer is not valid JSON
     */
    @Test
    void noFeedRendersAnEmptyArray() throws Exception {
        JsonNode feeds = parsed(newResource(List.of()));
        assertTrue(feeds.isArray());
        assertEquals(0, feeds.size());
    }

    /**
     * A single acknowledged feed keeps the field names: the applied version
     * as a string, {@code applied} true and a JSON null error.
     *
     * @throws Exception when the answer is not valid JSON
     */
    @Test
    void oneAcknowledgedFeedKeepsItsFieldNames() throws Exception {
        JsonNode feed = parsed(newResource(List.of(state("PRODUCTS", "v2", "v2", true, null)))).get(0);
        assertEquals("PRODUCTS", feed.get("code").asText());
        assertEquals("v2", feed.get("version").asText());
        assertEquals("v2", feed.get("appliedVersion").asText());
        assertTrue(feed.get("applied").asBoolean());
        assertTrue(feed.get("lastError").isNull());
    }

    /**
     * A feed the engine never acknowledged: {@code appliedVersion} is the JSON
     * literal null and {@code applied} is false.
     *
     * @throws Exception when the answer is not valid JSON
     */
    @Test
    void neverAcknowledgedFeedRendersANullVersion() throws Exception {
        JsonNode feed = parsed(newResource(List.of(state("OFFERS", "v1", null, false, null)))).get(0);
        assertTrue(feed.get("appliedVersion").isNull());
        assertFalse(feed.get("applied").asBoolean());
    }

    /**
     * An error message carrying a quote, a backslash and a line break stays a
     * single well-formed JSON string, read back exactly as it was raised.
     *
     * @throws Exception when the answer is not valid JSON
     */
    @Test
    void errorMessageIsEscapedByTheLibrary() throws Exception {
        String error = "a\\b \"c\"\nd";
        JsonNode feed = parsed(newResource(List.of(state("PRICES", "v3", "v2", false, error)))).get(0);
        assertEquals(error, feed.get("lastError").asText());
    }

    /**
     * Two feeds are two elements of the array, in catalog order.
     *
     * @throws Exception when the answer is not valid JSON
     */
    @Test
    void twoFeedsAreTwoElements() throws Exception {
        JsonNode feeds = parsed(newResource(List.of(
                state("PRODUCTS", "v1", "v1", true, null),
                state("PRICES", "v1", "v1", true, null))));
        assertEquals(2, feeds.size());
        assertEquals("PRODUCTS", feeds.get(0).get("code").asText());
        assertEquals("PRICES", feeds.get(1).get("code").asText());
    }

    /**
     * The response is a 200 carrying the JSON media type's payload as a string.
     */
    @Test
    void theResponseIsAnOkCarryingTheArray() {
        EngineFeedStatusResource resource =
                newResource(List.of(state("STORES", "v1", "v1", true, null)));
        assertEquals(200, resource.status().getStatus());
    }
}
