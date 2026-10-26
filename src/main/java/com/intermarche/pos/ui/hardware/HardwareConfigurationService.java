package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.service.CheckoutPointService;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The DRIVER CONFIGURATION of this register, as the back office administers
 * it, handed to the hardware module (BO-03-09-11, -14, -15, -16, -17, -18,
 * -19, -21, -22, -29).
 *
 * <p>The drivers of the drawer, the scale, the printers and the payment
 * terminal live in the hardware module, behind the HTTP boundary of
 * {@link HardwareClient}; until here their activation and their addressing
 * were arguments of the module's launch, the same on every till. They are now
 * parameters of the register — posed on its checkout point, its island or an
 * echelon — and this service gathers them, with the peripherals the checkout
 * point declares, into ONE block the module receives before the lane opens.
 * The block is pushed only when it changed, so the unlock gate does not
 * repeat it at every prise de poste.
 */
@ApplicationScoped
public class HardwareConfigurationService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HardwareConfigurationService.class);

    /** The hardware module. */
    @Inject
    @RestClient
    HardwareClient hardwareClient;

    /** The administered parameters, resolved for this register. */
    @Inject
    PosSettingsService settings;

    /** This register's own checkout point. */
    @Inject
    CheckoutPointService checkoutPointService;

    /** The last block the module acknowledged, null before the first push. */
    private volatile String lastPushed;

    /**
     * Gathers the driver configuration of this register.
     *
     * @return the configuration, in a stable order
     */
    public Map<String, String> configuration() {
        LOGGER.info("Entering method configuration");
        CheckoutPoint point = checkoutPointService.own();
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("drawer.enabled", String.valueOf(settings.hardwareEnabled("drawer")));
        configuration.put("drawer.address", settings.drawerAddress());
        configuration.put("scale.enabled", String.valueOf(settings.hardwareEnabled("scale")));
        configuration.put("scale.protocol", point == null || point.scaleProtocol == null
                ? "NONE" : point.scaleProtocol.name());
        configuration.put("scale.address", point == null || point.scaleAddress == null ? "" : point.scaleAddress);
        configuration.put("scale.port", point == null || point.scalePort == null ? "" : point.scalePort.toString());
        configuration.put("scanner.enabled", String.valueOf(settings.hardwareEnabled("scanner")));
        configuration.put("scanner.driver", settings.scannerDriver());
        configuration.put("scanner.name-patterns", settings.scannerNamePatterns());
        configuration.put("scanner.opos-device", settings.scannerOposDevice());
        configuration.put("scanner2.enabled", String.valueOf(settings.secondScannerEnabled()));
        configuration.put("scanner2.name-patterns", settings.secondScannerNamePatterns());
        configuration.put("printer.enabled", String.valueOf(settings.hardwareEnabled("printer")));
        configuration.put("printer.type", settings.printerType());
        configuration.put("printer.speed", String.valueOf(settings.printerSpeed()));
        configuration.put("printer.code-page", String.valueOf(settings.printerCodePage()));
        configuration.put("printer.paper-width", String.valueOf(settings.printerPaperWidth()));
        configuration.put("printer.cheque-flip", String.valueOf(settings.chequeFlip()));
        configuration.put("printer2.enabled", String.valueOf(settings.secondPrinterEnabled()));
        configuration.put("printer2.format", settings.secondPrinterFormat());
        configuration.put("printer2.connection", settings.secondPrinterConnection());
        configuration.put("printer2.address", settings.secondPrinterAddress());
        configuration.put("payment.type", settings.paymentTerminalType());
        configuration.put("payment.address", settings.paymentTerminalAddress());
        configuration.put("payment.island-label", settings.islandPaymentLabel());
        configuration.put("coin-machine.protocol", point == null || point.coinMachineProtocol == null
                ? "NONE" : point.coinMachineProtocol.name());
        configuration.put("coin-machine.address", point == null || point.coinMachineAddress == null
                ? "" : point.coinMachineAddress);
        configuration.put("coin-machine.port", point == null || point.coinMachinePort == null
                ? "" : point.coinMachinePort.toString());
        LOGGER.info("Exiting method configuration");
        return configuration;
    }

    /**
     * Writes the configuration as the block the module reads.
     *
     * @return one {@code key=value} per line
     */
    public String text() {
        StringBuilder block = new StringBuilder();
        for (Map.Entry<String, String> entry : configuration().entrySet()) {
            block.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return block.toString();
    }

    /**
     * Pushes the configuration to the module when it changed since the last
     * acknowledged push. A module that does not answer is not an error the lane
     * must wait on: it keeps its launch configuration and the next prise de
     * poste tries again.
     *
     * @return true when a new block was acknowledged
     */
    public boolean push() {
        LOGGER.info("Entering method push");
        String block = text();
        if (block.equals(lastPushed)) {
            LOGGER.info("Exiting method push");
            return false;
        }
        try {
            hardwareClient.configure(block);
            lastPushed = block;
            LOGGER.info("Exiting method push");
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Configuration des pilotes non transmise au module matériel : %s", e.getMessage());
            LOGGER.info("Exiting method push");
            return false;
        }
    }
}
