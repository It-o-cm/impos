package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SogecCompensationService} (EXP-58), targeting 100%
 * branch coverage.
 * <p>
 * The service recognises a SOGEC bon by its administered prefixes and records a
 * compensation journal trace. {@code isSogecBon} is covered on all its legs
 * (null/blank code, null/blank administered list, matching and non-matching
 * prefix, and a blank prefix entry skipped); {@code recordCompensation} is
 * covered by verifying the {@code SOGEC_COMPENSATION} journal call. Both
 * collaborators are Mockito mocks. Each test is isolated and asserts absolute
 * values.
 */
class SogecCompensationServiceTest {

    /**
     * Builds a service with the given administered prefixes and a mock journal.
     *
     * @param prefixes the administered SOGEC bon prefixes, or null
     * @return the service under test
     */
    private SogecCompensationService service(String prefixes) {
        SogecCompensationService service = new SogecCompensationService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        when(service.posSettingsService.sogecBonPrefixes()).thenReturn(prefixes);
        return service;
    }

    /**
     * A null code is not a SOGEC bon (first guard, null leg).
     */
    @Test
    void isSogecBonFalseForNullCode() {
        Assertions.assertFalse(service("99").isSogecBon(null));
    }

    /**
     * A blank code is not a SOGEC bon (first guard, isBlank leg).
     */
    @Test
    void isSogecBonFalseForBlankCode() {
        Assertions.assertFalse(service("99").isSogecBon("  "));
    }

    /**
     * With no administered prefixes (null), no code is a SOGEC bon.
     */
    @Test
    void isSogecBonFalseWhenNoAdministeredPrefixesNull() {
        Assertions.assertFalse(service(null).isSogecBon("9912345"));
    }

    /**
     * With a blank administered list, no code is a SOGEC bon (isBlank leg).
     */
    @Test
    void isSogecBonFalseWhenAdministeredPrefixesBlank() {
        Assertions.assertFalse(service("   ").isSogecBon("9912345"));
    }

    /**
     * A code whose prefix matches an administered SOGEC prefix is recognised;
     * a blank entry in the list is skipped.
     */
    @Test
    void isSogecBonTrueForMatchingPrefix() {
        Assertions.assertTrue(service(" ;99;42").isSogecBon("9912345"));
    }

    /**
     * A code whose prefix matches none of the administered prefixes is refused.
     */
    @Test
    void isSogecBonFalseForNonMatchingPrefix() {
        Assertions.assertFalse(service("99;42").isSogecBon("7712345"));
    }

    /**
     * recordCompensation journals a SOGEC_COMPENSATION event carrying the coupon
     * code and amount, with no operator badge.
     */
    @Test
    void recordCompensationJournalsTheTrace() {
        SogecCompensationService service = service("99");
        service.recordCompensation("SG1", new BigDecimal("5.00"));
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SOGEC_COMPENSATION,
                "SG1 5.00", null);
    }
}
