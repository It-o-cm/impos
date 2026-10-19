package com.intermarche.pos.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the localisation of the register's message band (LC-13-06).
 *
 * <p>WHY A TEST THAT READS THE SOURCE. The ninety-odd sentences the till shows
 * its operator used to be French literals inside the services. They were moved
 * to bundle keys one by one, and nothing but a habit keeps the next one from
 * being typed as a literal again — a habit the whole suite would then have to
 * notice at a Dutch till. So the rule is asserted where it can be: the
 * {@code setError} and {@code setNotice} call sites of the production sources
 * carry no string literal, and every key they do name exists in the reference
 * bundle.
 *
 * <p>It reads {@code src/main/java}, which surefire runs from the module root,
 * and the reference bundle from the classpath.
 */
class OperatorMessagesAreKeyedTest {

    /** Where the production sources live, relative to the module root. */
    private static final Path SOURCES = Path.of("src", "main", "java");

    /** The reference bundle, the one every key must be defined in. */
    private static final String REFERENCE = "messages/msg_fr.properties";

    /** An operator-message call, up to its opening parenthesis. */
    private static final Pattern CALL =
            Pattern.compile("\\.(?:setError|setNotice)\\s*\\(");

    /** An operator message written onto a state field. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "([\\w.]*\\.(?:errorMessage|error|notice|message|transientError"
            + "|backupError|creditError|currencyError))\\s*=\\s*");

    /** A key named in a resolver call. */
    private static final Pattern KEY =
            Pattern.compile("i18n\\.(?:get|format)\\(\\s*(?:[^()\"]*\\?\\s*)?\"([^\"]+)\"");

    /**
     * Reads the reference bundle from the classpath.
     *
     * @return the French keys and values
     * @throws IOException if the bundle cannot be read
     */
    private Properties reference() throws IOException {
        Properties properties = new Properties();
        try (var in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(REFERENCE)) {
            assertTrue(in != null, "le bundle de référence doit être sur le classpath");
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    /**
     * Every production source file.
     *
     * @return the java files under {@code src/main/java}
     * @throws IOException if the tree cannot be walked
     */
    private List<Path> javaFiles() throws IOException {
        try (Stream<Path> tree = Files.walk(SOURCES)) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Returns the argument of a call starting at the given position, parentheses
     * and string literals balanced.
     *
     * @param source the file's text
     * @param from the index just after the call's opening parenthesis
     * @return the argument expression
     */
    private String argumentAt(String source, int from) {
        int depth = 1;
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                i++;
                while (i < source.length() && source.charAt(i) != '"') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return source.substring(from, i);
            }
            i++;
        }
        return source.substring(from);
    }

    /**
     * No operator message is a literal: every {@code setError} and
     * {@code setNotice} argument goes through the resolver.
     *
     * @throws IOException if a source file cannot be read
     */
    @Test
    void testNoOperatorMessageIsAFrenchLiteral() throws IOException {
        List<String> literals = new ArrayList<>();
        for (Path path : javaFiles()) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            Matcher call = CALL.matcher(source);
            while (call.find()) {
                String argument = argumentAt(source, call.end()).strip();
                // A quote is legitimate inside a resolver call — the key itself,
                // and the empty string a caller may pass as a parameter. It is
                // the quote OUTSIDE a resolver call that is a French sentence.
                if (argument.contains("\"") && !argument.startsWith("i18n.")) {
                    literals.add(path + " : " + argument);
                }
            }
        }
        assertEquals(List.of(), literals,
                "un message opérateur doit nommer une clé, pas une phrase");
    }

    /**
     * Every key the sources name is defined in the reference bundle — including
     * the two branches of a key chosen by a ternary.
     *
     * @throws IOException if a source file or the bundle cannot be read
     */
    @Test
    void testEveryKeyNamedInTheSourcesExists() throws IOException {
        Properties french = reference();
        List<String> unknown = new ArrayList<>();
        int named = 0;
        for (Path path : javaFiles()) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            Matcher key = KEY.matcher(source);
            while (key.find()) {
                named++;
                if (french.getProperty(key.group(1)) == null) {
                    unknown.add(path + " : " + key.group(1));
                }
            }
            Matcher ternary = Pattern.compile(
                    "i18n\\.get\\([^\"]*\\?\\s*\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(source);
            while (ternary.find()) {
                named++;
                if (french.getProperty(ternary.group(2)) == null) {
                    unknown.add(path + " : " + ternary.group(2));
                }
            }
        }
        assertEquals(List.of(), unknown, "clé nommée mais absente du bundle français");
        assertTrue(named > 100, "le balayage doit voir les appels : " + named);
    }

    /**
     * An operator message written straight onto a state field — the refund
     * screen's {@code errorMessage}, the endorsement's {@code error}, the
     * loyalty view's {@code message} — is keyed too.
     *
     * <p>SAME RULE, SECOND SHAPE. Half the sentences the till shows never go
     * through {@code setError}: the screens that own a sub-state write into it
     * directly. A guard that only watched the calls would have let the other
     * half drift back into French.
     *
     * @throws IOException if a source file cannot be read
     */
    @Test
    void testNoStateMessageIsAFrenchLiteral() throws IOException {
        List<String> literals = new ArrayList<>();
        for (Path path : javaFiles()) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            Matcher assignment = ASSIGNMENT.matcher(source);
            while (assignment.find()) {
                String value = statementAt(source, assignment.end()).strip();
                // An emptying is not a message, and a resolver call is the rule
                // being followed rather than broken.
                if (value.contains("\"") && !value.equals("\"\"")
                        && !value.startsWith("i18n.")) {
                    literals.add(path + " : " + assignment.group(1) + " = " + value);
                }
            }
        }
        assertEquals(List.of(), literals,
                "un message opérateur doit nommer une clé, pas une phrase");
    }

    /**
     * Returns the value assigned at the given position, up to the statement's
     * semicolon, parentheses and string literals balanced.
     *
     * @param source the file's text
     * @param from the index just after the assignment operator
     * @return the assigned expression, without its semicolon
     */
    private String statementAt(String source, int from) {
        int depth = 0;
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                i++;
                while (i < source.length() && source.charAt(i) != '"') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ';' && depth == 0) {
                return source.substring(from, i);
            }
            i++;
        }
        return source.substring(from);
    }
}
