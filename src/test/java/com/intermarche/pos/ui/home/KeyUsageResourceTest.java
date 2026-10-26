package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.domain.session.TechnicalEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dynamic key identity, attribution and input guards (LC-13-05-01). */
class KeyUsageResourceTest {

    /**
     * Creates a resource with an attributed operator and journal mock.
     * @return the test resource (LC-13-05-01)
     */
    private KeyUsageResource resource() {
        KeyUsageResource resource = new KeyUsageResource();
        resource.state = new PosState();
        resource.state.auth.operatorBadgeId = "42";
        resource.events = mock(TechnicalEventService.class);
        return resource;
    }

    /** LC-13-05-01: a configured key is recorded without a fixed business-event list. */
    @Test
    void dynamicKeyIsAttributed() {
        KeyUsageResource resource = resource();
        assertEquals(204, resource.record("configured-key-17").getStatus());
        verify(resource.events).log(TechnicalEvent.EventType.TOUCH_USED, "configured-key-17", "42");
    }

    /** LC-13-05-01: a pre-login key can be counted with no operator badge. */
    @Test
    void lockedOperatorAttribution() {
        KeyUsageResource resource = resource(); resource.state.auth.operatorBadgeId = null;
        resource.record("/lock:submit");
        verify(resource.events).log(TechnicalEvent.EventType.TOUCH_USED, "/lock:submit", null);
    }

    /** LC-13-05-01: a null identifier does not create an empty counter. */
    @Test
    void nullKey() {
        KeyUsageResource resource = resource();
        assertEquals(400, resource.record(null).getStatus());
        verifyNoInteractions(resource.events);
    }

    /** LC-13-05-01: a blank identifier is also refused. */
    @Test
    void blankKey() {
        KeyUsageResource resource = resource();
        assertEquals(400, resource.record(" ").getStatus());
        verifyNoInteractions(resource.events);
    }

    /** LC-13-05-01: identifiers exceeding the journal contract are refused. */
    @Test
    void oversizedKey() {
        KeyUsageResource resource = resource();
        assertEquals(400, resource.record("x".repeat(201)).getStatus());
        verifyNoInteractions(resource.events);
    }

    /** LC-13-05-01: the maximum supported identifier is preserved exactly. */
    @Test
    void maximumKey() {
        KeyUsageResource resource = resource();
        assertEquals(204, resource.record("x".repeat(200)).getStatus());
        verify(resource.events).log(TechnicalEvent.EventType.TOUCH_USED, "x".repeat(200), "42");
    }
}
