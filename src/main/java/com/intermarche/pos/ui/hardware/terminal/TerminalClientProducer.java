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
 *       {@code pos.tpe.verifone.host/port/timeout-ms}.</li>
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

    private static final Logger LOG = Logger.getLogger(TerminalClientProducer.class);

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

    /** The simulator implementation, always available as a CDI bean. */
    @Inject
    VirtualTerminalClient virtualTerminalClient;

    /** The catalog holding the live value of the degraded-mode toggle. */
    @Inject
    PosSettingsService posSettingsService;

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
                resolveConfigured(), new AutoAcceptTerminalClient(), posSettingsService);
    }

    /**
     * Resolves the terminal implementation matching the configured mode.
     *
     * @return the configured (non-degraded) payment-terminal implementation
     */
    private PaymentTerminalClient resolveConfigured() {
        switch (mode) {
            case "auto":
                return new AutoAcceptTerminalClient();
            case "verifone":
                return new VerifoneTerminalClient(
                        new VerifoneTransport(verifoneHost, verifonePort, verifoneTimeoutMs));
            case "virtual":
                return virtualTerminalClient;
            default:
                LOG.warnf("Unknown pos.tpe.mode '%s' - falling back to the virtual terminal", mode);
                return virtualTerminalClient;
        }
    }
}
