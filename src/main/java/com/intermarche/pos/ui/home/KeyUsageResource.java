package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Records each dynamically identified operator key through the existing outbox (LC-13-05-01). */
@Path("/api/pos/key-use")
public class KeyUsageResource {

    /** Operator attribution without reading a password or a keypad value. */
    @Inject PosState state;
    /** Existing journal and store synchronization. */
    @Inject TechnicalEventService events;

    /**
     * Records a key identifier, not the values entered with that key.
     * @param key the configured DOM identifier or action identity
     * @return 204 when recorded, 400 for invalid identifiers (LC-13-05-01)
     */
    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    public Response record(String key) {
        if (key == null) {
            return Response.status(400).build();
        }
        if (key.isBlank()) {
            return Response.status(400).build();
        }
        if (key.length() > 200) {
            return Response.status(400).build();
        }
        events.log(TechnicalEvent.EventType.TOUCH_USED, key, state.auth.operatorBadgeId);
        return Response.noContent().build();
    }
}
