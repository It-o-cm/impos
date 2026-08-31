package com.intermarche.pos.ui.admin;

import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminSettingsResource}.
 * <p>
 * The resource is a Qute-backed back-office page over a mocked
 * {@link PosSettingsService}. The template is a Mockito mock whose chained
 * {@code data(...)} returns a mocked instance; the rendered entry list is
 * captured to assert its projection. No database and no Quarkus context is
 * booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@code settingsPage} covers
 * the {@code firstOfSection} decision (open / continue section arms) and the
 * {@code bool} / {@code integer} type ternaries over the whole catalog;
 * {@code save} covers the missing-field arm (continue), a valid integer
 * (parse-ok / non-negative arm) stored normalized, a non-numeric integer and a
 * negative integer (both the invalid arm, early red redirect), a boolean
 * normalized, a text stored verbatim, and the success green redirect.
 */
class AdminSettingsResourceTest {

    /**
     * Builds a resource over a mocked template and settings service.
     *
     * @return the wired resource
     */
    private AdminSettingsResource newResource() {
        AdminSettingsResource resource = new AdminSettingsResource();
        resource.adminSettings = mock(Template.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminSettingsResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminSettings.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * {@code settingsPage} projects the whole catalog into render entries,
     * opening a section card on each section change (firstOfSection both arms)
     * and flagging the boolean and integer widgets from the type.
     */
    @Test
    @SuppressWarnings("unchecked")
    void settingsPageProjectsCatalog() {
        AdminSettingsResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        when(resource.posSettingsService.value(anyString())).thenReturn("V");
        assertEquals(instance, resource.settingsPage("hi", false));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(resource.adminSettings).data(eq("entries"), captor.capture());
        List<AdminSettingsResource.Entry> entries =
                (List<AdminSettingsResource.Entry>) captor.getValue();
        assertEquals(20, entries.size());
        boolean[] firstOfSection = {true, true, true, false, false, false, false, true, false, false,
                true, true, true, false, true, false, true, true, true, false};
        boolean[] bool = {true, false, true, false, false, true, true, false, false, true,
                true, true, false, false, true, true, true, true, true, true};
        boolean[] integer = {false, true, false, true, true, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false};
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(firstOfSection[i], entries.get(i).firstOfSection, "firstOfSection#" + i);
            assertEquals(bool[i], entries.get(i).bool, "bool#" + i);
            assertEquals(integer[i], entries.get(i).integer, "integer#" + i);
            assertEquals("V", entries.get(i).value);
        }
        verify(instance).data("notice", "hi");
        verify(instance).data("noticeOk", false);
    }

    /**
     * {@code save} normalizes and stores every posted field — a valid integer
     * (parse-ok / non-negative arm), a boolean (normalized) and a text
     * (verbatim) — then redirects green; a missing field is skipped (continue
     * arm).
     */
    @Test
    void saveStoresValidFields() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("display.show-ean", "TRUE");
        form.putSingle("auth.idle-lockout-seconds", "30");
        form.putSingle("customer.message-open", "  Bonjour  ");
        // discount.* and the rest are missing -> continue arm.
        Response response = resource.save(form);
        verify(resource.posSettingsService).store("display.show-ean", "true");
        verify(resource.posSettingsService).store("auth.idle-lockout-seconds", "30");
        verify(resource.posSettingsService).store("customer.message-open", "Bonjour");
        verify(resource.posSettingsService, never()).store(eq("discount.line-max-percent"), anyString());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        assertTrue(response.getLocation().toString().contains("3+param"));
    }

    /**
     * {@code save} rejects a non-numeric integer with a red redirect (invalid
     * arm) and stores nothing past it (early return).
     */
    @Test
    void saveRejectsNonNumericInteger() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("auth.idle-lockout-seconds", "abc");
        Response response = resource.save(form);
        verify(resource.posSettingsService, never()).store(anyString(), anyString());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} rejects a negative integer with a red redirect (negative
     * arm of the invalid guard).
     */
    @Test
    void saveRejectsNegativeInteger() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("auth.idle-lockout-seconds", "-5");
        Response response = resource.save(form);
        verify(resource.posSettingsService, never()).store(anyString(), anyString());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} normalizes a boolean field to its canonical text form.
     */
    @Test
    void saveNormalizesBoolean() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("gesture.endorsement-required", "garbage");
        Response response = resource.save(form);
        verify(resource.posSettingsService).store("gesture.endorsement-required", "false");
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        assertFalse(response.getLocation().toString().contains("noticeOk=false"));
    }
}
