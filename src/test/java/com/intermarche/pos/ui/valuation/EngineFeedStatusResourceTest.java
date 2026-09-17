package com.intermarche.pos.ui.valuation;

import com.intermarche.pos.service.sync.EngineFeedService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EngineFeedStatusResource}.
 * <p>
 * The resource is a pure renderer over {@code EngineFeedService.feedStates()},
 * so every branch it owns is exercised here: the EMPTY list (no separator, no
 * element), the SINGLE element (the {@code first} flag suppressing the comma)
 * and the PAIR (the flag flipped, one comma and only one). Both arms of
 * {@code quoted} are covered on the two fields that use it — a present
 * {@code appliedVersion} and a null one, a present {@code lastError} and a null
 * one — and both values of {@code applied}. {@code escape} is covered on its
 * null arm and on a message carrying the two characters it neutralizes.
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
     * No feed stored: the payload is an empty array, neither element nor
     * separator (the loop never runs).
     */
    @Test
    void noFeedRendersAnEmptyArray() {
        assertEquals("[]", newResource(List.of()).status().getEntity());
    }

    /**
     * A single acknowledged feed: no leading comma (the {@code first} arm), the
     * applied version quoted, {@code applied} true and a null error.
     */
    @Test
    void oneAcknowledgedFeedRendersWithoutSeparator() {
        EngineFeedStatusResource resource =
                newResource(List.of(state("PRODUCTS", "v2", "v2", true, null)));
        assertEquals("[{\"code\":\"PRODUCTS\",\"version\":\"v2\",\"appliedVersion\":\"v2\","
                + "\"applied\":true,\"lastError\":null}]", resource.status().getEntity());
    }

    /**
     * A feed the engine never acknowledged: {@code appliedVersion} renders as
     * the JSON literal null (the null arm of {@code quoted}) and
     * {@code applied} is false.
     */
    @Test
    void neverAcknowledgedFeedRendersANullVersion() {
        EngineFeedStatusResource resource =
                newResource(List.of(state("OFFERS", "v1", null, false, null)));
        assertEquals("[{\"code\":\"OFFERS\",\"version\":\"v1\",\"appliedVersion\":null,"
                + "\"applied\":false,\"lastError\":null}]", resource.status().getEntity());
    }

    /**
     * An error message carrying a quote and a backslash is neutralized: the
     * backslash is doubled and the quote becomes an apostrophe, so the payload
     * stays a single well-formed JSON string.
     */
    @Test
    void errorMessageIsNeutralized() {
        EngineFeedStatusResource resource =
                newResource(List.of(state("PRICES", "v3", "v2", false, "a\\b \"c\"")));
        assertEquals("[{\"code\":\"PRICES\",\"version\":\"v3\",\"appliedVersion\":\"v2\","
                + "\"applied\":false,\"lastError\":\"a\\\\b 'c'\"}]", resource.status().getEntity());
    }

    /**
     * Two feeds: exactly one separator between them, and none before the first
     * — the flipped arm of the {@code first} flag.
     */
    @Test
    void twoFeedsAreSeparatedOnce() {
        EngineFeedStatusResource resource = newResource(List.of(
                state("PRODUCTS", "v1", "v1", true, null),
                state("PRICES", "v1", "v1", true, null)));
        String json = (String) resource.status().getEntity();
        assertEquals(1, json.split("\\},\\{", -1).length - 1);
        assertEquals("[{\"code\":\"PRODUCTS\",\"version\":\"v1\",\"appliedVersion\":\"v1\","
                + "\"applied\":true,\"lastError\":null},"
                + "{\"code\":\"PRICES\",\"version\":\"v1\",\"appliedVersion\":\"v1\","
                + "\"applied\":true,\"lastError\":null}]", json);
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
