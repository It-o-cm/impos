package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.deploy.DeployPayloads;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareClient;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries out on THIS REGISTER the remote maintenance orders of the back
 * office (BO-08-04-17): restart the payment terminal (relance TPV), reset the
 * ticket printer or the scanner, probe every peripheral again, pull the
 * referentials now.
 *
 * <p>The same bridge operations as the register's own maintenance screen,
 * reached from the store or the central node. A hardware reset is REFUSED
 * while a sale is on the screen: restarting the terminal under a customer's
 * card is the one remote gesture that must never happen. The refusal is an
 * outcome like any other, sent back up and kept in the order's trace.
 */
@ApplicationScoped
public class RemoteMaintenanceService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RemoteMaintenanceService.class);

    /** The register's state: a sale in progress refuses a hardware reset. */
    @Inject
    PosState state;

    /** The administered activation of the remote maintenance. */
    @Inject
    PosSettingsService posSettingsService;

    /** The peripheral bridge. */
    @Inject
    @RestClient
    HardwareClient hardwareClient;

    /** The peripheral probe. */
    @Inject
    HardwareService hardwareService;

    /** The referential pull. */
    @Inject
    RefPullService refPullService;

    /**
     * Carries one order out.
     *
     * @param order the order
     * @return its outcome
     */
    public DeployPayloads.ActionResult execute(DeployPayloads.Action order) {
        LOGGER.info("Entering method execute with type: " + order.type);
        if (!posSettingsService.remoteMaintenanceEnabled()) {
            return outcome(order, false, "Prise en main à distance désactivée sur cette caisse");
        }
        return switch (order.type) {
            case "TERMINAL_RESET" -> reset(order, "terminal");
            case "PRINTER_RESET" -> reset(order, "printer");
            case "SCANNER_RESET" -> reset(order, "scanner");
            case "PERIPHERAL_PROBE" -> probe(order);
            case "REFERENTIAL_PULL" -> {
                String error = refPullService.triggerPull();
                yield error == null ? outcome(order, true, "Tirage des référentiels relancé")
                        : outcome(order, false, error);
            }
            default -> outcome(order, false, "Ordre non applicable à cette caisse");
        };
    }

    /**
     * Resets one peripheral through the bridge, unless a sale is in progress.
     *
     * @param order the order
     * @param device the bridge's device role
     * @return the outcome
     */
    private DeployPayloads.ActionResult reset(DeployPayloads.Action order, String device) {
        if (!state.ticket.items.isEmpty()) {
            return outcome(order, false, "Vente en cours : ordre refusé");
        }
        try {
            hardwareClient.maintenance(device, "reset", "");
            return outcome(order, true, "Réinitialisation " + device + " effectuée");
        } catch (RuntimeException failure) {
            return outcome(order, false, "Réinitialisation " + device + " en échec : " + failure.getMessage());
        }
    }

    /**
     * Probes every peripheral again and reports what each answered.
     *
     * @param order the order
     * @return the outcome, the peripherals' states as its text
     */
    private DeployPayloads.ActionResult probe(DeployPayloads.Action order) {
        List<String> states = new ArrayList<>();
        for (HardwareService.DeviceStatus device : QuarkusTransaction.requiringNew()
                .call(hardwareService::probeDevices)) {
            states.add(device.name() + "=" + device.availability());
        }
        return outcome(order, true, String.join(", ", states));
    }

    /**
     * Builds an outcome, dated now.
     *
     * @param order the order
     * @param success whether it succeeded
     * @param text what to say
     * @return the outcome
     */
    static DeployPayloads.ActionResult outcome(DeployPayloads.Action order, boolean success, String text) {
        DeployPayloads.ActionResult result = new DeployPayloads.ActionResult();
        result.uid = order.uid;
        result.success = success;
        result.result = text;
        result.doneAt = LocalDateTime.now().toString();
        return result;
    }
}
