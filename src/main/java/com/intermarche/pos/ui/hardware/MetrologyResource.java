package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.Messages;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * The metrology information of the register's weighing system, behind the
 * MÉTROLOGIE key of the maintenance screen (LC-14-03-01).
 *
 * <p>WHAT AN INSPECTOR ASKS FOR: the body that certified the weighing system,
 * the certificate's number, the CRC of the scale's legal software, and the
 * name and version of the till software. The first three are written on the
 * certificate, so the back office administers them — the CRC per register,
 * the scale model being able to differ from one till to the next; the last two
 * are the software's own, read from the running build and never typed by
 * anyone.
 *
 * <p>Under {@code /maintenance}, so it is reachable on a locked register, like
 * the rest of the diagnostics: an inspection does not wait for a cashier to
 * sign in. It reads, and changes nothing.
 */
@Path("/maintenance/metrology")
public class MetrologyResource {

    /** The administered certificate information. */
    @Inject PosSettingsService settings;

    /** The labels of the screen. */
    @Inject Messages i18n;

    /** The metrology screen. */
    @Inject @Location("metrology") Template metrology;

    /** The name of the running till software, as the build states it. */
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "impos")
    String softwareName;

    /** The version of the running till software, as the build states it. */
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "")
    String softwareVersion;

    /**
     * Shows the metrology information.
     *
     * @return the metrology screen, one row per item
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance show() {
        List<Row> rows = new ArrayList<>();
        rows.add(row("metrology.body", settings.metrologyBody()));
        rows.add(row("metrology.certificate", settings.metrologyCertificate()));
        rows.add(row("metrology.scaleCrc", settings.metrologyScaleCrc()));
        rows.add(row("metrology.software", softwareName));
        rows.add(row("metrology.version", softwareVersion));
        return metrology.data("rows", rows);
    }

    /**
     * Builds one row, an empty value reading "not set" rather than a blank.
     *
     * @param labelKey the message key of the item
     * @param value the value, possibly null or blank
     * @return the row
     */
    private Row row(String labelKey, String value) {
        boolean set = value != null && !value.isBlank();
        return new Row(i18n.get(labelKey), set ? value.trim() : i18n.get("metrology.notSet"), set);
    }

    /**
     * One item of the metrology screen.
     *
     * @param label the item's label
     * @param value the item's value, or the "not set" mention
     * @param set whether the value is administered or known
     */
    public record Row(String label, String value, boolean set) {
    }
}
