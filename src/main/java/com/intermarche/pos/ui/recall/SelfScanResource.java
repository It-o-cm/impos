package com.intermarche.pos.ui.recall;

import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SELFSCANNING screen (LC-06-05-01 to -06): the basket a customer built on a
 * selfscanning device, recalled at the till by its code, its control — none,
 * partial or total, the articles the cashier re-scans counted as they come — and
 * the gestures that close it: provoke a total control, load the basket into the
 * sale, or put it aside.
 */
@Path("/")
public class SelfScanResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SelfScanResource.class);

    /** The localised operator messages. */
    @Inject
    Messages i18n;

    /** The selfscanning screen. */
    @Inject
    @Location("selfscan")
    Template selfscan;

    /** The register state, which holds the basket under control. */
    @Inject
    PosState state;

    /** The control of the basket. */
    @Inject
    SelfScanService selfScanService;

    /**
     * Shows the basket under control.
     *
     * @return the selfscanning page
     */
    @GET
    @Path("/selfscan")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page() {
        LOGGER.info("Entering method page");
        SelfScanControl control = state.selfScan;
        TemplateInstance view = selfscan.data("state", state).data("control", control)
                .data("version", state.version);
        if (control == null) {
            LOGGER.info("Exiting method page (no basket)");
            return view.data("rows", List.of()).data("vouchers", List.of()).data("modeLabel", "")
                    .data("total", "").data("progress", "");
        }
        LOGGER.info("Exiting method page");
        return view.data("rows", selfScanService.rows(control))
                .data("vouchers", vouchers(control.basket))
                .data("modeLabel", modeLabel(control))
                .data("total", euros(total(control.basket)))
                .data("progress", control.isPartial()
                        ? control.partialChecked + " / " + control.partialTarget : "");
    }

    /**
     * The version of the register state, polled by the screen so a re-scan read
     * by the hardware scanner shows at once.
     *
     * @return the version and whether a basket is under control
     */
    @GET
    @Path("/selfscan-data")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", state.version);
        data.put("active", state.selfScan != null);
        return data;
    }

    /**
     * Provokes the total control of the basket (LC-06-05-06).
     *
     * @return a redirect to the selfscanning screen
     */
    @POST
    @Path("/action/selfscan/total")
    public Response provokeTotal() {
        LOGGER.info("Entering method provokeTotal");
        selfScanService.provokeTotal(state);
        LOGGER.info("Exiting method provokeTotal");
        return Response.seeOther(URI.create("/selfscan")).build();
    }

    /**
     * Loads the controlled basket into the sale (LC-06-05-01, -02).
     *
     * @return a redirect to the sale screen once loaded, to the selfscanning
     *         screen while the control is not done
     */
    @POST
    @Path("/action/selfscan/load")
    public Response load() {
        LOGGER.info("Entering method load");
        SelfScanService.Outcome outcome = selfScanService.load(state);
        LOGGER.info("Exiting method load (" + outcome + ")");
        return Response.seeOther(URI.create(outcome == SelfScanService.Outcome.PARTIAL_PENDING
                ? "/selfscan" : "/")).build();
    }

    /**
     * Puts the basket aside.
     *
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/selfscan/abandon")
    public Response abandon() {
        LOGGER.info("Entering method abandon");
        selfScanService.abandon(state);
        LOGGER.info("Exiting method abandon");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Names the control in progress.
     *
     * @param control the control
     * @return the operator-facing name of the control
     */
    private String modeLabel(SelfScanControl control) {
        if (control.isTotal()) {
            return control.provoked ? i18n.get("selfscan.controleTotalProvoque")
                    : i18n.get("selfscan.controleTotal");
        }
        if (control.isPartial()) {
            return i18n.format("selfscan.controlePartiel", control.partialTarget)
                    + " — " + i18n.get(control.drawn ? "selfscan.tireAuSort" : "selfscan.demandeAppareil");
        }
        return i18n.get("selfscan.aucunControle");
    }

    /**
     * The vouchers the device recorded, as the screen shows them.
     *
     * @param basket the basket
     * @return one row per voucher: label and amount
     */
    private List<Map<String, String>> vouchers(RecalledDocument basket) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (basket.vouchers == null) {
            return rows;
        }
        for (RecalledDocument.Voucher voucher : basket.vouchers) {
            if (voucher == null || voucher.amount() == null) {
                continue;
            }
            rows.add(Map.of("label", voucher.label() == null ? i18n.get("selfscan.bonReduction") : voucher.label(),
                    "amount", euros(voucher.amount())));
        }
        return rows;
    }

    /**
     * The total the device declared.
     *
     * @param basket the basket
     * @return the sum of its lines
     */
    private static BigDecimal total(RecalledDocument basket) {
        BigDecimal total = BigDecimal.ZERO;
        for (RecalledDocument.Line line : basket.lines) {
            if (line != null) {
                total = total.add(line.total());
            }
        }
        return total;
    }

    /**
     * Writes an amount for the screen.
     *
     * @param amount the amount
     * @return the amount, two decimals, French comma, euro sign
     */
    private static String euros(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " €";
    }
}
