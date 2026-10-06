package com.intermarche.pos.ui;

import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LanguageResource}.
 * <p>
 * The register state is a real {@link PosState} so the recorded choice can be
 * read back; the template, the label resolver and the settings are mocks. Both
 * arms of the permission guard are driven (locked register, administered
 * parameter), and both landings of the redirect.
 */
class LanguageResourceTest {

    /**
     * Builds the resource with a real state and mocked collaborators.
     *
     * @return a ready-to-drive resource
     */
    private LanguageResource newResource() {
        LanguageResource resource = new LanguageResource();
        resource.state = new PosState();
        resource.languageSelect = mock(Template.class);
        resource.messages = mock(Messages.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.messages.getAvailableLanguages()).thenReturn(List.of("fr", "nl"));
        when(resource.messages.getLang()).thenReturn("fr");
        return resource;
    }

    /**
     * Stubs the selection template's four-step render and returns the final view.
     *
     * @param resource the resource whose template is stubbed
     * @return the view the render returns
     */
    private TemplateInstance stubPage(LanguageResource resource) {
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withLanguages = mock(TemplateInstance.class);
        TemplateInstance withCurrent = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.languageSelect.data("state", resource.state)).thenReturn(withState);
        when(withState.data(eq("languages"), any())).thenReturn(withLanguages);
        when(withLanguages.data(eq("current"), any())).thenReturn(withCurrent);
        when(withCurrent.data(eq("allowed"), any())).thenReturn(view);
        return view;
    }

    /**
     * The selection screen hands the template the state, the selectable
     * languages, the current one and whether the choice is accepted.
     */
    @Test
    void theSelectionScreenHandsTheTemplateWhatItNeeds() {
        LanguageResource resource = newResource();
        when(resource.posSettingsService.languageChangeAllowed()).thenReturn(true);
        TemplateInstance view = stubPage(resource);
        assertSame(view, resource.languageSelectPage());
    }

    /**
     * On a LOCKED register the choice is always accepted (LC-13-06-03): an
     * operator who cannot read the screen could not sign in to change it.
     */
    @Test
    void theChoiceIsAcceptedOnALockedRegister() {
        LanguageResource resource = newResource();
        resource.state.auth.isLocked = true;
        when(resource.posSettingsService.languageChangeAllowed()).thenReturn(false);
        try (Response response = resource.chooseLanguage("nl")) {
            assertEquals(303, response.getStatus());
            assertEquals("/lock", response.getLocation().toString());
        }
        assertEquals("nl", resource.state.language);
    }

    /**
     * During a session the choice is accepted when the back office allows it
     * (LC-13-06-04), and the operator lands back on the sale screen.
     */
    @Test
    void theChoiceIsAcceptedDuringTheSessionWhenAdministered() {
        LanguageResource resource = newResource();
        resource.state.auth.isLocked = false;
        when(resource.posSettingsService.languageChangeAllowed()).thenReturn(true);
        try (Response response = resource.chooseLanguage("pt")) {
            assertEquals(303, response.getStatus());
            assertEquals("/", response.getLocation().toString());
        }
        assertEquals("pt", resource.state.language);
    }

    /**
     * During a session where the back office forbids the change, nothing is
     * recorded — and the operator is still sent back rather than left on a dead
     * screen.
     */
    @Test
    void theChoiceIsRefusedDuringTheSessionWhenForbidden() {
        LanguageResource resource = newResource();
        resource.state.auth.isLocked = false;
        resource.state.language = "nl";
        when(resource.posSettingsService.languageChangeAllowed()).thenReturn(false);
        try (Response response = resource.chooseLanguage("pt")) {
            assertEquals(303, response.getStatus());
            assertEquals("/", response.getLocation().toString());
        }
        assertEquals("nl", resource.state.language);
    }

    /**
     * A blank choice hands the decision back to the chain (the "store default"
     * key), and a null one does the same (both legs of the blank guard).
     */
    @Test
    void aBlankChoiceClearsTheOperatorsLanguage() {
        LanguageResource resource = newResource();
        when(resource.posSettingsService.languageChangeAllowed()).thenReturn(true);
        resource.state.language = "nl";
        try (Response ignored = resource.chooseLanguage("   ")) {
            assertNull(resource.state.language);
        }
        resource.state.language = "pt";
        try (Response ignored = resource.chooseLanguage(null)) {
            assertNull(resource.state.language);
        }
    }

    /**
     * A chosen language is trimmed: the form posts what the screen holds, and the
     * screen is not asked to be tidy.
     */
    @Test
    void theChosenLanguageIsTrimmed() {
        LanguageResource resource = newResource();
        when(resource.posSettingsService.languageChangeAllowed()).thenReturn(true);
        try (Response ignored = resource.chooseLanguage("  nl  ")) {
            assertEquals("nl", resource.state.language);
        }
    }
}
