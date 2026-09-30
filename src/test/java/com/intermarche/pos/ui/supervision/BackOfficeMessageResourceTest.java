package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.home.HomeService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link BackOfficeMessageResource}, the caisse end of the
 * back-office &rarr; register message channel (EXP-20/89).
 * <p>
 * The resource is a thin JAX-RS facade: it turns the posted form into a plain
 * notice or a {@link PosState.BackOfficeSurvey} and hands it to
 * {@link HomeService#receiveBackOfficeMessage}. Both collaborators are Mockito
 * mocks; the survey it builds is captured to assert the question trim and the
 * option split (blank options dropped). Tests cover the survey/plain branch
 * (question present vs null vs blank) and the null-options arm.
 */
class BackOfficeMessageResourceTest {

    /**
     * Builds a resource with its two collaborators mocked.
     *
     * @return the wired resource
     */
    private BackOfficeMessageResource newResource() {
        BackOfficeMessageResource resource = new BackOfficeMessageResource();
        resource.homeService = mock(HomeService.class);
        resource.state = mock(PosState.class);
        return resource;
    }

    /**
     * A message with no question is a PLAIN notice: the survey handed to the
     * service is null (question-blank false arm not taken — question is null).
     */
    @Test
    void receivePlainNoticeBuildsNoSurvey() {
        BackOfficeMessageResource resource = newResource();
        resource.state.backOfficeMessage = "Réunion à 18h";
        Response response = resource.receive("Réunion à 18h", null, null);
        assertEquals(200, response.getStatus());
        assertEquals("Réunion à 18h", response.getEntity());
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        verify(resource.homeService).receiveBackOfficeMessage(org.mockito.ArgumentMatchers.eq("Réunion à 18h"),
                captor.capture());
        assertNull(captor.getValue());
    }

    /**
     * A blank question is also a plain notice: the {@code isBlank} arm is true,
     * so no survey is built.
     */
    @Test
    void receiveBlankQuestionBuildsNoSurvey() {
        BackOfficeMessageResource resource = newResource();
        Response response = resource.receive("texte", "   ", "a;b");
        assertEquals(200, response.getStatus());
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        verify(resource.homeService).receiveBackOfficeMessage(org.mockito.ArgumentMatchers.eq("texte"),
                captor.capture());
        assertNull(captor.getValue());
    }

    /**
     * A non-blank question makes a SURVEY (EXP-89): the question is trimmed and
     * the options are split on semicolons with blank entries dropped.
     */
    @Test
    void receiveSurveyBuildsQuestionAndSplitsOptions() {
        BackOfficeMessageResource resource = newResource();
        Response response = resource.receive("Sondage", "  Satisfait ?  ", "Oui; Non; ;Sans avis");
        assertEquals(200, response.getStatus());
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        verify(resource.homeService).receiveBackOfficeMessage(org.mockito.ArgumentMatchers.eq("Sondage"),
                captor.capture());
        PosState.BackOfficeSurvey survey = captor.getValue();
        assertEquals("Satisfait ?", survey.question);
        assertEquals(java.util.List.of("Oui", "Non", "Sans avis"), survey.options);
    }

    /**
     * A survey with NULL options builds a question with an empty option list (the
     * {@code options != null} false arm), never a null-pointer.
     */
    @Test
    void receiveSurveyWithNullOptionsLeavesEmptyList() {
        BackOfficeMessageResource resource = newResource();
        resource.receive("Sondage", "Question", null);
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        verify(resource.homeService).receiveBackOfficeMessage(org.mockito.ArgumentMatchers.eq("Sondage"),
                captor.capture());
        assertEquals(java.util.List.of(), captor.getValue().options);
    }
}
