package com.intermarche.pos.ui.hardware.terminal;

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

    /**
     * Produces the terminal client matching the configured mode.
     *
     * @return the active payment-terminal implementation
     */
    @Produces
    @ApplicationScoped
    public PaymentTerminalClient paymentTerminalClient() {
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
