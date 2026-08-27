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
import jakarta.ws.rs.core.MediaType;

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
     * Shows the consultation page, without any result yet.
     *
     * @return the consultation page
     */
    @GET
    @Path("/voucher-balance")
    public TemplateInstance voucherBalancePage() {
        return voucherBalance.data("state", state).data("result", null);
    }

    /**
     * Looks the typed number up in the registry and re-renders the page with
     * the instrument's kind, status and balance — or the unknown message.
     *
     * @param number the typed or scanned instrument number
     * @return the consultation page carrying the result
     */
    @POST
    @Path("/action/voucher-balance")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance consult(@FormParam("number") String number) {
        BalanceView view = new BalanceView();
        StoredValue instrument = (number == null || number.isBlank())
                ? null
                : StoredValue.<StoredValue>find("number", number.trim()).firstResult();
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
        return voucherBalance.data("state", state).data("result", view);
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
