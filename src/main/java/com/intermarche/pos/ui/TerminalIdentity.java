package com.intermarche.pos.ui;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The identity of this register as its screens name it in their header
 * ({@code CAISSE <id> | Opérateur : <name>}), reached from the templates as
 * {@code {inject:terminal.id}} like the labels are reached through
 * {@code inject:i18n}: the screens used to print a hard-coded "04" whatever
 * register they ran on.
 */
@Named("terminal")
@ApplicationScoped
@Unremovable
public class TerminalIdentity {

    /** The identifier of this register ({@code pos.terminal.id}). */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Returns the identifier of this register.
     *
     * @return the configured terminal identifier
     */
    public String getId() {
        return terminalId;
    }
}
