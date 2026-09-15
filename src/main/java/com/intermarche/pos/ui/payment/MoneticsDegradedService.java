package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * Lets the shop force the monetics into degraded mode, and lets it out again
 * ({@code LC-07-08-02} to {@code -05}).
 *
 * <p>The monetics is supposed to fall back on its own when its servers stop
 * answering. It does not always: a link that is very slow but not cut, or a server
 * that answers ping and nothing else, leaves every card payment hanging while the
 * queue grows. This is the shop deciding, under its own responsibility, to stop
 * waiting.
 *
 * <p>UNDER ITS OWN RESPONSIBILITY is the whole point, and it is why three things
 * hold here. Turning it on can require a supervisor. It ENDS BY ITSELF after an
 * administered delay, because the operator who forced it will not be the one still
 * standing there in three hours. And it is journalled both ways, so the day a
 * chargeback arrives the shop can say who decided to stop asking for authorization,
 * and when.
 */
@ApplicationScoped
public class MoneticsDegradedService {

    private static final Logger LOGGER = Logger.getLogger(MoneticsDegradedService.class);

    /** The action code journalled when a supervisor allows the forcing. */
    static final String ACTIVATE_ACTION = "MONETICS_DEGRADED_ON";

    /** The action code journalled when a supervisor allows the release. */
    static final String DEACTIVATE_ACTION = "MONETICS_DEGRADED_OFF";

    /** The administered endorsement rule and expiry delay. */
    @Inject
    PosSettingsService posSettingsService;

    /** Checks the supervisor credential and journals the decision. */
    @Inject
    EndorsementService endorsementService;

    /** Records who forced the monetics aside, and when. */
    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Forces the monetics into degraded mode until the administered delay runs out
     * ({@code LC-07-08-03}).
     *
     * @param state the current POS state
     * @param login the supervisor's login, ignored when no endorsement is required
     * @param password the supervisor's password
     * @return true when the forcing was turned on
     */
    public boolean activate(PosState state, String login, String password) {
        LOGGER.info("Entering method activate with state: " + state + ", login: " + login + ", password: ***");
        if (!granted(state, login, password, ACTIVATE_ACTION)) {
            state.ticket.setError("AUTORISATION REFUSEE");
            LOGGER.info("Exiting method activate");
            return false;
        }
        int minutes = Math.max(1, posSettingsService.moneticsDegradedForcedMinutes());
        state.moneticsDegradedUntil = LocalDateTime.now().plusMinutes(minutes);
        technicalEventService.log(TechnicalEvent.EventType.ENDORSEMENT_GRANTED,
                "Mode dégradé monétique forcé pour " + minutes + " min par "
                        + state.auth.operatorName);
        LOGGER.infof("Mode dégradé monétique forcé jusqu'à %s", state.moneticsDegradedUntil);
        state.ticket.setNotice("MODE DÉGRADÉ MONÉTIQUE ACTIVÉ (" + minutes + " MIN)");
        LOGGER.info("Exiting method activate");
        return true;
    }

    /**
     * Releases the forcing before its delay runs out ({@code LC-07-08-04}).
     *
     * @param state the current POS state
     * @param login the supervisor's login, ignored when no endorsement is required
     * @param password the supervisor's password
     * @return true when the forcing was turned off
     */
    public boolean deactivate(PosState state, String login, String password) {
        LOGGER.info("Entering method deactivate with state: " + state + ", login: " + login + ", password: ***");
        if (!state.isMoneticsDegradedForced()) {
            // Already off — expired on its own, or never on. Saying so beats an
            // authorization prompt for a state that does not exist.
            state.ticket.setNotice("MODE DÉGRADÉ MONÉTIQUE DÉJÀ INACTIF");
            LOGGER.info("Exiting method deactivate");
            return false;
        }
        if (!granted(state, login, password, DEACTIVATE_ACTION)) {
            state.ticket.setError("AUTORISATION REFUSEE");
            LOGGER.info("Exiting method deactivate");
            return false;
        }
        state.moneticsDegradedUntil = null;
        technicalEventService.log(TechnicalEvent.EventType.ENDORSEMENT_GRANTED,
                "Mode dégradé monétique levé par " + state.auth.operatorName);
        state.ticket.setNotice("MODE DÉGRADÉ MONÉTIQUE DÉSACTIVÉ");
        LOGGER.info("Exiting method deactivate");
        return true;
    }

    /**
     * Tells whether the gesture is allowed: either the shop asks for no endorsement,
     * or the logged operator is already a supervisor, or a supervisor's credential
     * was given.
     *
     * @param state the current POS state
     * @param login the supervisor's login
     * @param password the supervisor's password
     * @param actionCode the action code journalled with the decision
     * @return true when the gesture may proceed
     */
    private boolean granted(PosState state, String login, String password, String actionCode) {
        if (!posSettingsService.moneticsDegradedForcedEndorsement()) {
            return true;
        }
        if (endorsementService.operatorIsSupervisor(state)) {
            return true;
        }
        return endorsementService.authorize(login, password, actionCode);
    }
}
