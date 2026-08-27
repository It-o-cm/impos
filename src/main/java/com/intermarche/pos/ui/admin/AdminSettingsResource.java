package com.intermarche.pos.ui.admin;

import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The store back office's PARAMETERS page ({@code /admin/settings}) —
 * imvaluation admin gabarit (light chrome, cards, POST → 303 → notice),
 * deliberately NOT the till's touch theme.
 * <p>
 * Values are stored node-locally ({@code pos_settings}) and reach the
 * registers through the referential pull (domain SETTINGS): administered on
 * the STORE node they apply store-wide at the next pull; edited on a
 * standalone register they apply locally — same screen, same table, the
 * topology decides the reach. No authentication yet: the page sits on the
 * back-office surface next to {@code /dashboard}, outside the cashier lock
 * (allowlisted in the lock filter), and a real admin login is a known gap
 * of the whole back-office surface.
 */
@Path("/")
public class AdminSettingsResource {

    /** The parameters page template. */
    @Inject
    @Location("admin-settings")
    Template adminSettings;

    /** The catalog and store of the parameters. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * One render-ready catalog entry: the definition flattened for Qute plus
     * the current effective value and the section-break marker.
     */
    public static class Entry {
        /** The storage key. */
        public String key;
        /** The section heading. */
        public String section;
        /** The operator-facing label. */
        public String label;
        /** The operator-facing explanation. */
        public String hint;
        /** The current effective value, as text. */
        public String value;
        /** True for a boolean parameter (select widget). */
        public boolean bool;
        /** True for an integer parameter (number widget). */
        public boolean integer;
        /** True when this entry opens a new section card. */
        public boolean firstOfSection;
    }

    /**
     * Shows the parameters page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the parameters page
     */
    @GET
    @Path("/admin/settings")
    public TemplateInstance settingsPage(@QueryParam("notice") String notice,
                                         @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        List<Entry> entries = new ArrayList<>();
        String lastSection = null;
        for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
            Entry entry = new Entry();
            entry.key = def.key();
            entry.section = def.section();
            entry.label = def.label();
            entry.hint = def.hint();
            entry.value = posSettingsService.value(def.key());
            entry.bool = def.type() == PosSettingsService.Type.BOOL;
            entry.integer = def.type() == PosSettingsService.Type.INT;
            entry.firstOfSection = !def.section().equals(lastSection);
            lastSection = def.section();
            entries.add(entry);
        }
        return adminSettings.data("entries", entries)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Saves every posted parameter (upsert by key), validating integers, and
     * redirects back with the outcome notice — the admin cycle.
     *
     * @param form the posted form, one field per catalog key
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/admin/settings")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response save(MultivaluedMap<String, String> form) {
        int saved = 0;
        for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
            String raw = form.getFirst(def.key());
            if (raw == null) continue;
            String value = raw.trim();
            if (def.type() == PosSettingsService.Type.INT) {
                try {
                    int parsed = Integer.parseInt(value);
                    if (parsed < 0) throw new NumberFormatException();
                    value = String.valueOf(parsed);
                } catch (NumberFormatException e) {
                    return redirect("Valeur invalide pour « " + def.label() + " » — entier positif attendu.", false);
                }
            } else if (def.type() == PosSettingsService.Type.BOOL) {
                value = String.valueOf(Boolean.parseBoolean(value));
            }
            posSettingsService.store(def.key(), value);
            saved++;
        }
        return redirect(saved + " paramètre(s) enregistré(s). Application aux caisses au prochain tirage.", true);
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/settings?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
