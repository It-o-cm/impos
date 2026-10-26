package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetrologyResource}, the metrology information of the
 * weighing system (LC-14-03-01): the administered certificate items, the
 * software's own name and version, and the "not set" mention for every item
 * nobody filled — null, blank or empty.
 */
class MetrologyResourceTest {

    /**
     * Builds the resource over mocked settings and template.
     *
     * @return the resource
     */
    private MetrologyResource resource() {
        MetrologyResource resource = new MetrologyResource();
        resource.settings = mock(PosSettingsService.class);
        resource.i18n = TestMessages.french();
        resource.metrology = mock(Template.class);
        resource.softwareName = "impos";
        resource.softwareVersion = "1.0-SNAPSHOT";
        return resource;
    }

    /**
     * Renders the screen and returns its rows.
     *
     * @param resource the resource
     * @return the rows handed to the template
     */
    @SuppressWarnings("unchecked")
    private List<MetrologyResource.Row> rows(MetrologyResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        when(resource.metrology.data(eq("rows"), captor.capture())).thenReturn(instance);
        assertSame(instance, resource.show());
        return (List<MetrologyResource.Row>) captor.getValue();
    }

    /** Every item administered and known reads its value, in the inspector's order. */
    @Test
    void showsTheCertificateAndTheSoftware() {
        MetrologyResource resource = resource();
        when(resource.settings.metrologyBody()).thenReturn("LNE");
        when(resource.settings.metrologyCertificate()).thenReturn("LNE-12345");
        when(resource.settings.metrologyScaleCrc()).thenReturn(" 8F3A ");
        assertEquals(List.of(
                new MetrologyResource.Row("Organisme de certification", "LNE", true),
                new MetrologyResource.Row("N° de certificat", "LNE-12345", true),
                new MetrologyResource.Row("CRC de la balance", "8F3A", true),
                new MetrologyResource.Row("Logiciel d'encaissement", "impos", true),
                new MetrologyResource.Row("Version du logiciel", "1.0-SNAPSHOT", true)), rows(resource));
    }

    /** An item nobody filled reads "not set", whether null, blank or empty. */
    @Test
    void anUnsetItemSaysSo() {
        MetrologyResource resource = resource();
        when(resource.settings.metrologyBody()).thenReturn(null);
        when(resource.settings.metrologyCertificate()).thenReturn("  ");
        when(resource.settings.metrologyScaleCrc()).thenReturn("");
        resource.softwareVersion = "";
        List<MetrologyResource.Row> rows = rows(resource);
        assertEquals(new MetrologyResource.Row("Organisme de certification", "non renseigné", false), rows.get(0));
        assertEquals(new MetrologyResource.Row("N° de certificat", "non renseigné", false), rows.get(1));
        assertEquals(new MetrologyResource.Row("CRC de la balance", "non renseigné", false), rows.get(2));
        assertEquals(new MetrologyResource.Row("Version du logiciel", "non renseigné", false), rows.get(4));
        verify(resource.metrology).data(eq("rows"), org.mockito.ArgumentMatchers.any());
    }
}
