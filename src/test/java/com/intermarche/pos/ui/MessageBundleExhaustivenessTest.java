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
        for (String language : Messages.BUNDLED_LANGUAGES) {
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
        for (String language : Messages.BUNDLED_LANGUAGES) {
            Properties properties = load(language);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                assertFalse(value == null || value.isBlank(),
                        "valeur vide pour " + key + " en " + language);
            }
        }
    }

    /**
     * Tests that the three languages the register claims to ship are the three it
     * actually ships, and that French is one of them: the whole fallback chain
     * rests on the reference bundle existing.
     *
     * @throws IOException if a bundle cannot be read
     */
    @Test
    void testTheShippedLanguagesAreTheDeclaredOnes() throws IOException {
        assertEquals(Set.of("fr", "nl", "pt"), Set.copyOf(Messages.BUNDLED_LANGUAGES));
        assertFalse(load(Messages.DEFAULT_LANGUAGE).isEmpty(),
                "le bundle de référence ne peut pas être vide");
    }
}
