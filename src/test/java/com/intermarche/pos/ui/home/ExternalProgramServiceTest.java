package com.intermarche.pos.ui.home;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExternalProgramService} (LC-09-01-04), targeting 100%
 * branch coverage.
 *
 * <p>The settings are a mock and the launcher is replaced, so no program is
 * started except in the one test of the real launcher, which starts the Java
 * runtime running the tests. Branch enumeration: a path administered or not; a
 * label absent, blank or given; a path naming nothing, a file that cannot be
 * executed and an executable one; arguments absent and separated by several
 * spaces; a launcher that fails; the real launcher starting a program and
 * failing on a missing one.
 */
class ExternalProgramServiceTest {

    /** A folder of the test's own. */
    @TempDir
    Path folder;

    /**
     * A service over mocked settings, its launcher recording the commands.
     *
     * @param path the administered path
     * @param arguments the administered arguments
     * @param launched where the launched commands are recorded
     * @return the service
     */
    private ExternalProgramService service(String path, String arguments, List<List<String>> launched) {
        ExternalProgramService service = new ExternalProgramService();
        service.i18n = TestMessages.french();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.externalProgramPath()).thenReturn(path);
        when(service.posSettingsService.externalProgramArguments()).thenReturn(arguments);
        service.launcher = command -> {
            launched.add(command);
            return null;
        };
        return service;
    }

    /**
     * An executable file in the test's folder.
     *
     * @param executable whether it may be executed
     * @return its path
     * @throws Exception when it cannot be written
     */
    private String program(boolean executable) throws Exception {
        Path file = Files.writeString(folder.resolve(executable ? "cash.sh" : "notes.txt"), "#!/bin/sh\n");
        assertTrue(file.toFile().setExecutable(executable));
        return file.toString();
    }

    /**
     * The key exists only when a path is administered; its label is the
     * administered one, trimmed, or the default one.
     */
    @Test
    void theKeyAndItsLabel() {
        List<List<String>> launched = new ArrayList<>();
        ExternalProgramService none = service("", "", launched);
        assertFalse(none.isConfigured());
        ExternalProgramService some = service("/opt/cash", "", launched);
        assertTrue(some.isConfigured());
        when(some.posSettingsService.externalProgramLabel()).thenReturn(null);
        assertEquals("PROGRAMME EXTERNE", some.label());
        when(some.posSettingsService.externalProgramLabel()).thenReturn("  ");
        assertEquals("PROGRAMME EXTERNE", some.label());
        when(some.posSettingsService.externalProgramLabel()).thenReturn(" CASH MANAGEMENT ");
        assertEquals("CASH MANAGEMENT", some.label());
    }

    /**
     * Without a path, a path naming nothing and a file that cannot be executed,
     * nothing is started and the operator is told why.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    void nothingIsStartedWithoutAnExecutable() throws Exception {
        List<List<String>> launched = new ArrayList<>();
        assertEquals("AUCUN PROGRAMME EXTERNE ADMINISTRÉ", service("", "", launched).launch());
        String missing = folder.resolve("missing").toString();
        assertEquals("PROGRAMME EXTERNE INTROUVABLE : " + missing, service(missing, "", launched).launch());
        String notes = program(false);
        assertEquals("PROGRAMME EXTERNE INTROUVABLE : " + notes, service(notes, "", launched).launch());
        assertEquals("PROGRAMME EXTERNE INTROUVABLE : " + folder, service(folder.toString(), "", launched).launch());
        assertTrue(launched.isEmpty());
    }

    /**
     * The program is started with its arguments split on spaces, empty ones
     * dropped; without arguments it is started alone.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    void theProgramIsStartedWithItsArguments() throws Exception {
        String cash = program(true);
        List<List<String>> launched = new ArrayList<>();
        assertNull(service(cash, "--till  POS01 ", launched).launch());
        assertNull(service(cash, "", launched).launch());
        assertEquals(List.of(List.of(cash, "--till", "POS01"), List.of(cash)), launched);
    }

    /**
     * A launcher that fails is reported, not thrown.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    void aFailedStartIsReported() throws Exception {
        String cash = program(true);
        ExternalProgramService service = service(cash, "", new ArrayList<>());
        service.launcher = command -> {
            throw new IllegalStateException("denied");
        };
        assertEquals("LE PROGRAMME EXTERNE N'A PAS PU DÉMARRER : " + cash, service.launch());
    }

    /**
     * The real launcher starts a program — the Java runtime running the tests,
     * asked for its version — and fails on a missing one.
     *
     * @throws Exception when the started program cannot be waited for
     */
    @Test
    void theRealLauncherStartsAProcess() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = ExternalProgramService.start(List.of(java, "-version"));
        assertEquals(0, process.waitFor());
        assertThrows(UncheckedIOException.class, () -> ExternalProgramService.start(
                List.of(folder.resolve("missing").toString())));
        assertTrue(new File(java).exists() || "java".equals(java));
    }
}
