package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Selects the active {@link PaymentTerminalClient} implementation from
 * {@code pos.tpe.mode}:
 * <ul>
 *   <li>{@code virtual} (default) — the simulator's terminal
 *       ({@link VirtualTerminalClient});</li>
 *   <li>{@code auto} — no terminal, immediate acceptance, the legacy
 *       {@code pos.tpe.virtual=false} behavior
 *       ({@link AutoAcceptTerminalClient});</li>
 *   <li>{@code verifone} — the integrated monetique skeleton
 *       ({@link VerifoneTerminalClient}), configured by
 *       {@code pos.tpe.verifone.host/port/timeout-ms};</li>
 *   <li>{@code bridge} — the real terminal, reached through the hardware
 *       daemon like every other peripheral
 *       ({@link HardwareBridgeTerminalClient}), configured by
 *       {@code pos.tpe.bridge.poll-ms} and {@code pos.tpe.bridge.deadline-ms}.</li>
 * </ul>
 * An unknown mode falls back to {@code virtual} with a warning, so a typo
 * in configuration can never leave the register without a terminal path.
 * <p>
 * The resolved implementation is always wrapped in a
 * {@link DegradedModePaymentTerminalClient} (BO-03-12-05): the manual
 * degraded-mode toggle needs a live per-transaction check, which only a
 * wrapper checking {@link PosSettingsService} on every call can give —
 * the CDI producer itself runs once at bean creation.
 */
@ApplicationScoped
public class TerminalClientProducer {

    private static final Logger LOGGER = Logger.getLogger(TerminalClientProducer.class);

    /** The configured terminal mode. */
    @ConfigProperty(name = "pos.tpe.mode", defaultValue = "virtual")
    String mode;

    /** Host of the local Verifone monetique client. */
    @ConfigProperty(name = "pos.tpe.verifone.host", defaultValue = "127.0.0.1")
    String verifoneHost;

    /** Port of the local Verifone monetique client. */
    @ConfigProperty(name = "pos.tpe.verifone.port", defaultValue = "8200")
    int verifonePort;

    /** Connect/read timeout of the Verifone transport, in milliseconds. */
    @ConfigProperty(name = "pos.tpe.verifone.timeout-ms", defaultValue = "60000")
    int verifoneTimeoutMs;

    /** How long the bridge client waits between two readings of the payment state. */
    @ConfigProperty(name = "pos.tpe.bridge.poll-ms", defaultValue = "500")
    long bridgePollMs;

    /**
     * How long the bridge client follows one payment before giving up.
     * <p>
     * Generous on purpose: it bounds a cardholder, not a machine.
     */
    @ConfigProperty(name = "pos.tpe.bridge.deadline-ms", defaultValue = "300000")
    long bridgeDeadlineMs;

    /** The hardware bridge, the same boundary the scale and the drawer go through. */
    @Inject
    @org.eclipse.microprofile.rest.client.inject.RestClient
    com.intermarche.pos.ui.hardware.HardwareClient hardwareClient;

    /** The simulator implementation, always available as a CDI bean. */
    @Inject
    VirtualTerminalClient virtualTerminalClient;

    /** The catalog holding the live value of the degraded-mode toggle. */
    @Inject
    PosSettingsService posSettingsService;

    /** The localised operator messages handed to the clients this producer builds. */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The register state carrying the operator's own forcing (LC-07-08-02). */
    @Inject
    com.intermarche.pos.ui.PosState state;

    /**
     * Produces the terminal client matching the configured mode, wrapped
     * with the degraded-mode gate.
     *
     * @return the active payment-terminal implementation
     */
    @Produces
    @ApplicationScoped
    public PaymentTerminalClient paymentTerminalClient() {
        return new DegradedModePaymentTerminalClient(
                this::currentConfigured, new AutoAcceptTerminalClient(VerifoneTags.DEGRADED_SERVER),
                posSettingsService, state);
    }

    /** The mode, host and port the current client was built for (BO-03-09-19). */
    private String builtFor;

    /** The client built for {@link #builtFor}. */
    private PaymentTerminalClient built;

    /**
     * The terminal client for what is administered NOW (BO-03-09-19): the
     * family and the address reach the register with its referential, after
     * it started, so the client is rebuilt when either changes and reused
     * otherwise.
     *
     * @return the configured (non-degraded) payment-terminal implementation
     */
    synchronized PaymentTerminalClient currentConfigured() {
        String key = effectiveMode() + "|" + host() + ":" + port();
        if (built == null || !key.equals(builtFor)) {
            built = resolveConfigured();
            builtFor = key;
        }
        return built;
    }

    /**
     * The terminal family administered for this register wins over the
     * deployment's mode (BO-03-09-19): Verifone is the centralised monetique
     * this register speaks itself; Ingenico (SIBS, Portugal) and Worldline
     * (Belgium) terminals are driven by the hardware module, which receives the
     * family in its driver configuration. Nothing administered: the
     * deployment's {@code pos.tpe.mode}.
     *
     * @return the mode the client is built for
     */
    String effectiveMode() {
        String administered = posSettingsService.paymentTerminalType();
        if ("VERIFONE".equals(administered)) {
            return "verifone";
        }
        return administered.isEmpty() ? mode : "bridge";
    }

    /**
     * The host of the monetique client: the administered address's, else the
     * deployment's (BO-03-09-19).
     *
     * @return the host
     */
    String host() {
        String address = posSettingsService.paymentTerminalAddress();
        int colon = address.lastIndexOf(':');
        if (address.isEmpty()) {
            return verifoneHost;
        }
        return colon > 0 ? address.substring(0, colon) : address;
    }

    /**
     * The port of the monetique client: the administered address's, else the
     * deployment's (BO-03-09-19).
     *
     * @return the port
     */
    int port() {
        String address = posSettingsService.paymentTerminalAddress();
        int colon = address.lastIndexOf(':');
        if (colon <= 0) {
            return verifonePort;
        }
        try {
            return Integer.parseInt(address.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return verifonePort;
        }
    }

    /**
     * Resolves the terminal implementation matching the configured mode.
     *
     * @return the configured (non-degraded) payment-terminal implementation
     */
    private PaymentTerminalClient resolveConfigured() {
        switch (effectiveMode()) {
            case "auto":
                return new AutoAcceptTerminalClient();
            case "verifone":
                // BO-03-09-30/-31: the administered monetique parameters are
                // HANDED OVER, like the message resolver beside them — the
                // client is built here and not by the container.
                return new VerifoneTerminalClient(
                        new VerifoneTransport(host(), port(), verifoneTimeoutMs),
                        i18n, posSettingsService);
            case "bridge":
                return new HardwareBridgeTerminalClient(hardwareClient, bridgePollMs,
                        bridgeDeadlineMs, i18n);
            case "virtual":
                return virtualTerminalClient;
            default:
                LOGGER.warnf("Unknown pos.tpe.mode '%s' - falling back to the virtual terminal", mode);
                return virtualTerminalClient;
        }
    }
}
