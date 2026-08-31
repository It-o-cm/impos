package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.StoredValue;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.math.RoundingMode;

/**
 * Stored-value balance consultation (LC-02-09-04): gift cards and credit
 * notes are REGISTRY-backed ({@code stored_values}), so the balance answer
 * is local and authoritative — no external hub involved. Strictly
 * read-only: consulting never touches the ticket nor the instrument.
 */
@Path("/")
@DrawerMustBeClosed
public class VoucherBalanceResource {

    /** The consultation page. */
    @Inject
    @Location("voucher-balance")
    Template voucherBalance;

    /** The register's composition root. */
    @Inject
    PosState state;

    /**
     * Shows the consultation page; when a number travels in the query string
     * (put there by the PRG redirect of the POST below), the registry lookup
     * runs and the result renders — the lookup is strictly read-only, so
     * re-running it on a browser refresh is harmless.
     *
     * @param number the instrument number to consult, or null for a bare page
     * @return the consultation page, with the result when a number was given
     */
    @GET
    @Path("/voucher-balance")
    public TemplateInstance voucherBalancePage(@QueryParam("number") String number) {
        BalanceView view = (number == null || number.isBlank()) ? null : lookup(number);
        return voucherBalance.data("state", state).data("result", view);
    }

    /**
     * Redirects the typed number to the consultation page (PRG pattern, so a
     * browser reload never replays the POST); the GET runs the lookup.
     *
     * @param number the typed or scanned instrument number
     * @return a 303 redirect to the consultation page carrying the number
     */
    @POST
    @Path("/action/voucher-balance")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response consult(@FormParam("number") String number) {
        String encoded = number == null ? "" : URLEncoder.encode(number.trim(), StandardCharsets.UTF_8);
        return Response.seeOther(URI.create("/voucher-balance?number=" + encoded)).build();
    }

    /**
     * Looks a number up in the stored-value registry and builds the
     * render-ready view: the instrument's kind, status and balance — or the
     * not-found view.
     *
     * @param number the instrument number, already known non-blank
     * @return the balance view (found flag false when the number is unknown)
     */
    private BalanceView lookup(String number) {
        BalanceView view = new BalanceView();
        StoredValue instrument =
                StoredValue.<StoredValue>find("number", number.trim()).firstResult();
        if (instrument != null) {
            view.found = true;
            view.number = instrument.number;
            view.kindLabel = instrument.kind == StoredValue.Kind.GIFT_CARD
                    ? "CARTE CADEAU" : "AVOIR";
            view.statusLabel = instrument.status == StoredValue.Status.ACTIVE
                    ? "ACTIVE" : "ÉPUISÉ";
            view.balanceFormatted = String.format("%.2f",
                    instrument.balance.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
        }
        return view;
    }

    /**
     * Render-ready view of a balance consultation.
     */
    public static class BalanceView {
        /** True when the number exists in the registry. */
        public boolean found = false;
        /** The instrument number, echoed. */
        public String number;
        /** CARTE CADEAU or AVOIR. */
        public String kindLabel;
        /** ACTIVE or ÉPUISÉ. */
        public String statusLabel;
        /** The balance, French format. */
        public String balanceFormatted;
    }
}
