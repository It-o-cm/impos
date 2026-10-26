package com.intermarche.pos.ui.home;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The key of the register screen that calls a PROGRAM INSTALLED ON THE TILL
 * (LC-09-01-04) — the cash management of the cash changer in Portugal, for
 * instance — whose path the back office administers.
 *
 * <p>The program is started as a process of its own and the register goes on:
 * nothing waits for it, nothing reads what it prints. Only the administered path
 * is ever started, with the administered arguments split on spaces and handed
 * over as they are — no shell reads them, so nothing typed at the till or in the
 * back office can become a command. No path, no key; a path that names no
 * executable file says so instead of failing in silence.
 */
@ApplicationScoped
public class ExternalProgramService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ExternalProgramService.class);

    /** The administered path, label and arguments. */
    @Inject
    PosSettingsService posSettingsService;

    /** The localised operator messages. */
    @Inject
    Messages i18n;

    /** Starts the program; replaced in the tests. */
    Function<List<String>, Process> launcher = ExternalProgramService::start;

    /**
     * Whether the back office administered a program.
     *
     * @return true when a path is set
     */
    public boolean isConfigured() {
        return !posSettingsService.externalProgramPath().isEmpty();
    }

    /**
     * The label of the key.
     *
     * @return the administered label, or the default one when blank
     */
    public String label() {
        String label = posSettingsService.externalProgramLabel();
        return label == null || label.isBlank() ? i18n.get("main.programmeExterne") : label.trim();
    }

    /**
     * Starts the administered program (LC-09-01-04).
     *
     * @return null when it was started, the operator's message otherwise
     */
    public String launch() {
        LOGGER.info("Entering method launch");
        String path = posSettingsService.externalProgramPath();
        if (path.isEmpty()) {
            LOGGER.info("Exiting method launch (not configured)");
            return i18n.get("op.programmeExterneNonConfigure");
        }
        File program = new File(path);
        if (!program.isFile() || !program.canExecute()) {
            LOGGER.info("Exiting method launch (not executable)");
            return i18n.format("op.programmeExterneIntrouvable", path);
        }
        List<String> command = new ArrayList<>();
        command.add(path);
        for (String argument : posSettingsService.externalProgramArguments().split(" ")) {
            if (!argument.isEmpty()) {
                command.add(argument);
            }
        }
        try {
            launcher.apply(command);
        } catch (RuntimeException e) {
            LOGGER.warnf("Programme externe %s non lance : %s", path, e.getMessage());
            LOGGER.info("Exiting method launch (failed)");
            return i18n.format("op.programmeExterneEchec", path);
        }
        LOGGER.infof("Programme externe lance : %s", command);
        LOGGER.info("Exiting method launch");
        return null;
    }

    /**
     * Starts a process detached from the register's output.
     *
     * @param command the program and its arguments
     * @return the process started
     */
    static Process start(List<String> command) {
        try {
            return new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
