package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.home.HomeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * The CAISSE end of the back-office → register message channel (EXP-20/89): the
 * store node (imedge, a separate repo) POSTs a message here and the register
 * shows it non-disruptively on the cashier's sale screen. This is only the
 * register end — the node producer is out of this repo, so the full round trip
 * is an {@code [N]} e2e scenario.
 *
 * <p>Under {@code /api/} on purpose: it is a machine surface the node reaches at
 * any time, so it must answer even while the register is locked (the lock
 * filter allowlists {@code /api/}), exactly like the other node-facing surfaces.
 */
@Path("/api/bo-message")
public class BackOfficeMessageResource {

    /** The class logger. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeMessageResource.class);

    /** The register state and message-channel logic. */
    @Inject HomeService homeService;

    /** The register state, read to acknowledge the stored message. */
    @Inject PosState state;

    /**
     * Receives a back-office message pushed down by the store node. A non-blank
     * {@code question} makes it a SURVEY (EXP-89) whose options are the
     * semicolon-separated {@code options}; otherwise it is a plain notice
     * (EXP-20). A blank text with no question clears the current message.
     *
     * @param text the message text, or blank to clear
     * @param question the survey question, or null/blank for a plain notice
     * @param options the semicolon-separated survey options, or null
     * @return 200 with the stored message acknowledged
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response receive(@FormParam("text") String text,
                            @FormParam("question") String question,
                            @FormParam("options") String options) {
        LOGGER.info("Entering method receive");
        PosState.BackOfficeSurvey survey = null;
        if (question != null && !question.isBlank()) {
            survey = new PosState.BackOfficeSurvey();
            survey.question = question.trim();
            if (options != null) {
                for (String raw : options.split(";")) {
                    if (!raw.isBlank()) {
                        survey.options.add(raw.trim());
                    }
                }
            }
        }
        homeService.receiveBackOfficeMessage(text, survey);
        LOGGER.info("Exiting method receive");
        return Response.ok(state.backOfficeMessage).build();
    }
}
