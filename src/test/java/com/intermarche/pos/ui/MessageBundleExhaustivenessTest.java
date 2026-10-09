package com.intermarche.pos.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test keeping the register's message bundles exhaustive and aligned
 * (LC-13-06), the same guard the back office puts on its own bundles.
 * <p>
 * French is the reference; Dutch and Portuguese must be complete. The test loads
 * the three bundles from the classpath and asserts their key sets are identical,
 * so a label added to one screen and forgotten in a language fails the build
 * rather than surfacing a raw key on a till. No value may be blank, and no
 * language may be missing its file.
 */
class MessageBundleExhaustivenessTest {

    /**
     * Loads a bundle's key/value pairs from the classpath.
     *
     * @param language the bundle language
     * @return the loaded properties
     * @throws IOException if the bundle cannot be read
     */
    private Properties load(String language) throws IOException {
        String path = "messages/msg_" + language + ".properties";
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(in, "le bundle doit exister sur le classpath : " + path);
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }

    /**
     * Tests that every shipped bundle declares exactly the keys the French
     * reference declares.
     *
     * @throws IOException if a bundle cannot be read
     */
    @Test
    void testBundlesShareTheSameKeys() throws IOException {
        Set<String> reference = new TreeSet<>(load(Messages.DEFAULT_LANGUAGE).stringPropertyNames());
        for (String language : Messages.bundledLanguages()) {
            if (Messages.DEFAULT_LANGUAGE.equals(language)) {
                continue;
            }
            Set<String> other = new TreeSet<>(load(language).stringPropertyNames());
            Set<String> missing = new TreeSet<>(reference);
            missing.removeAll(other);
            Set<String> extra = new TreeSet<>(other);
            extra.removeAll(reference);
            assertEquals(Set.of(), missing,
                    "clés présentes en fr et absentes en " + language);
            assertEquals(Set.of(), extra,
                    "clés présentes en " + language + " et absentes en fr");
        }
    }

    /**
     * Tests that no bundle carries a blank value: a label resolved to an empty
     * string is a button with no text on it.
     *
     * @throws IOException if a bundle cannot be read
     */
    @Test
    void testNoBlankValues() throws IOException {
        for (String language : Messages.bundledLanguages()) {
            Properties properties = load(language);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                assertFalse(value == null || value.isBlank(),
                        "valeur vide pour " + key + " en " + language);
            }
        }
    }

    /**
     * Tests that the languages offered are DISCOVERED from the bundles present,
     * French first, and that each one names itself.
     *
     * <p>No expected list is written here, deliberately: a test that pinned
     * {@code fr, nl, pt} would restore in the test suite exactly the hard-coding
     * the production code just lost, and the next language added would show up as
     * a red test rather than as a language. What IS pinned is the contract — the
     * reference bundle exists, every discovered bundle is readable and carries its
     * own name — so a file dropped half-written still fails loudly.
     *
     * @throws IOException if a bundle cannot be read
     */
    @Test
    void testTheOfferedLanguagesAreTheBundlesPresent() throws IOException {
        java.util.List<String> discovered = Messages.bundledLanguages();
        assertFalse(discovered.isEmpty(), "au moins le français doit être découvert");
        assertEquals(Messages.DEFAULT_LANGUAGE, discovered.get(0),
                "le français ouvre la liste : c'est le bundle de référence");
        assertFalse(load(Messages.DEFAULT_LANGUAGE).isEmpty(),
                "le bundle de référence ne peut pas être vide");
        for (String language : discovered) {
            Properties properties = load(language);
            assertFalse(properties.isEmpty(), "bundle vide ou absent : " + language);
            String own = properties.getProperty("language.self");
            assertFalse(own == null || own.isBlank(),
                    "le bundle " + language + " doit se nommer lui-même (language.self)");
        }
    }

    /**
     * Tests that the three bundles shipped TODAY are among those discovered.
     *
     * <p>Not the same assertion as above and not a duplicate of it: that one says
     * the mechanism works, this one says the delivery is complete. A bundle
     * deleted by accident would otherwise pass every test in this class.
     *
     * @throws IOException if a bundle cannot be read
     */
    @Test
    void testTheThreeShippedBundlesAreDiscovered() throws IOException {
        assertTrue(Messages.bundledLanguages().containsAll(Set.of("fr", "nl", "pt")),
                "les trois bundles livrés doivent être découverts");
    }
}
