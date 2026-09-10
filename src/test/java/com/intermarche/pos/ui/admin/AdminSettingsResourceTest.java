package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
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
        // Expected BY KEY, not by position. Three parallel boolean arrays indexed
        // by catalog rank meant that adding one parameter — and the register adds
        // them by the handful — shifted every expectation after it and the test
        // failed on entries it was not about. A new parameter is one line here.
        String[][] expected = {
            //  key                                   section                      bool   integer
            {"display.show-ean",                    "AFFICHAGE",                  "b", ""},
            {"auth.idle-lockout-seconds",           "SESSION CAISSE",             "",  "i"},
            {"gesture.endorsement-required",        "GESTES DE PRIX",             "b", ""},
            {"discount.line-max-percent",           "GESTES DE PRIX",             "",  "i"},
            {"discount.global-max-percent",         "GESTES DE PRIX",             "",  "i"},
            {"discount.enabled",                    "GESTES DE PRIX",             "b", ""},
            {"price.show-original-on-force",        "GESTES DE PRIX",             "b", ""},
            {"customer.message-open",               "AFFICHEUR CLIENT",           "",  ""},
            {"customer.message-closed",             "AFFICHEUR CLIENT",           "",  ""},
            {"customer.qr-enabled",                 "AFFICHEUR CLIENT",           "b", ""},
            {"parking.print-receipt",               "TICKETS",                    "b", ""},
            {"ticket.email-format",                 "TICKETS",                    "",  ""},
            {"ticket.email-editable",               "TICKETS",                    "b", ""},
            {"ticket.line-order",                   "TICKETS",                    "",  ""},
            {"print.conditional-enabled",           "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.forced-documents",              "IMPRESSION CONDITIONNELLE",  "",  ""},
            {"print.force-ticket-glc",              "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-credit",             "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-signature",          "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-tna",                "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"payment.degraded-mode",               "MONÉTIQUE",                  "b", ""},
            {"payment.degraded-forced-endorsement", "MONÉTIQUE",                  "b", ""},
            {"payment.degraded-forced-minutes",     "MONÉTIQUE",                  "",  "i"},
            {"ticket.header-message",               "MESSAGES TICKET",            "",  ""},
            {"ticket.footer-message",               "MESSAGES TICKET",            "",  ""},
            {"drawer.open-on-payment",              "TIROIR",                     "b", ""},
            {"drawer.open-on-login",                "TIROIR",                     "b", ""},
            {"backup.manual-endorsement",           "SECOURS MONETIQUE",          "b", ""},
            {"backup.method-labels",                "SECOURS MONETIQUE",          "",  ""},
            {"balance.counter-price",               "TICKET COMPTOIR",            "b", ""},
            {"cash.rounding-step-cents",            "ARRONDI ESPECES",            "",  "i"},
            {"credit.allowed-in-degraded",          "CREDIT CLIENT",              "b", ""},
            {"credit.degraded-after-minutes",       "CREDIT CLIENT",              "",  "i"},
            {"scan.ean13-check-digit",              "SCAN",                       "b", ""},
            {"dashboard.alerts-enabled",            "SUPERVISION",                "b", ""},
            {"fidelity.advantages-enabled",         "FIDÉLITÉ",                   "b", ""},
            {"fidelity.allow-multiple-scan",        "FIDÉLITÉ",                   "b", ""},
            {"cash.movement-endorsement-threshold", "MOUVEMENTS DE CAISSE",       "",  ""},
            {"cash.movement-reasons",               "MOUVEMENTS DE CAISSE",       "",  ""},
            {"invoice.customer-fields",             "FACTURE",                    "",  ""},
            {"touch.groups-per-page",               "TOUCHES CAISSE",             "",  "i"},
            {"touch.display-order",                 "TOUCHES CAISSE",             "",  ""},
        };
        assertEquals(expected.length, entries.size());
        String previousSection = null;
        for (int i = 0; i < expected.length; i++) {
            AdminSettingsResource.Entry entry = entries.get(i);
            assertEquals(expected[i][0], entry.key, "key#" + i);
            assertEquals(expected[i][1], entry.section, "section#" + i);
            assertEquals("b".equals(expected[i][2]), entry.bool, "bool#" + entry.key);
            assertEquals("i".equals(expected[i][3]), entry.integer, "integer#" + entry.key);
            // A card opens on every section change and only there.
            assertEquals(!expected[i][1].equals(previousSection), entry.firstOfSection,
                    "firstOfSection#" + entry.key);
            previousSection = expected[i][1];
            assertEquals("V", entry.value);
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

    /**
     * {@code adminRoot} routes a signed-in administrator to the parameters:
     * the non-null arm of the identity guard, the true arm of {@code hasRole}
     * and the true arm of the routing ternary.
     */
    @Test
    void adminRootRoutesAdminToSettings() {
        AdminSettingsResource resource = newResource();
        resource.identity = mock(SecurityIdentity.class);
        when(resource.identity.hasRole(Employee.EmployeeRole.ADMIN.name())).thenReturn(true);
        Response response = resource.adminRoot();
        assertEquals(303, response.getStatus());
        assertEquals("/admin/settings", response.getLocation().toString());
    }

    /**
     * {@code adminRoot} routes a signed-in non-administrator to the
     * supervision: the non-null arm of the identity guard, the false arm of
     * {@code hasRole} and the false arm of the routing ternary.
     */
    @Test
    void adminRootRoutesNonAdminToDashboard() {
        AdminSettingsResource resource = newResource();
        resource.identity = mock(SecurityIdentity.class);
        when(resource.identity.hasRole(Employee.EmployeeRole.ADMIN.name())).thenReturn(false);
        Response response = resource.adminRoot();
        assertEquals(303, response.getStatus());
        assertEquals("/dashboard", response.getLocation().toString());
    }

    /**
     * {@code adminRoot} routes to the supervision when there is no identity:
     * the null arm of the identity guard short-circuits before {@code hasRole}
     * and lands on the false arm of the routing ternary.
     */
    @Test
    void adminRootRoutesNullIdentityToDashboard() {
        AdminSettingsResource resource = newResource();
        resource.identity = null;
        Response response = resource.adminRoot();
        assertEquals(303, response.getStatus());
        assertEquals("/dashboard", response.getLocation().toString());
    }
}
