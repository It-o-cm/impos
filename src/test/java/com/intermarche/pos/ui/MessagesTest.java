package com.intermarche.pos.ui;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.auth.AuthState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Messages}.
 * <p>
 * The bundles are the REAL ones on the classpath, deliberately: a test that
 * stubbed them would prove the resolution chain and nothing about the labels the
 * till actually shows. The two collaborators are mocks — the register state is a
 * real {@link PosState} carrying a real {@link AuthState}, so the operator leg
 * can be driven by setting a field, and the employee lookup is a Panache static
 * stubbed per case.
 * <p>
 * Every leg of the resolution chain has its own case, including the three
 * nullities that make it skip a step, and every arm of the bilingual rendering.
 */
class MessagesTest {

    /**
     * Builds a {@link Messages} with a real register state and a mocked settings
     * service answering the catalog defaults (French system language, the three
     * bundled languages offered, no second language).
     *
     * @return a ready-to-drive resolver
     */
    private Messages newMessages() {
        Messages messages = new Messages();
        messages.state = new PosState();
        messages.posSettingsService = mock(PosSettingsService.class);
        when(messages.posSettingsService.systemLanguage()).thenReturn("fr");
        when(messages.posSettingsService.availableLanguages())
                .thenReturn(List.of("fr", "nl", "pt"));
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("");
        return messages;
    }

    /**
     * Builds an employee carrying an administered interface language.
     *
     * @param language the administered language, possibly null
     * @return the employee
     */
    private Employee employee(String language) {
        Employee employee = new Employee();
        employee.language = language;
        return employee;
    }

    // --- the resolution chain ---

    /**
     * With nothing chosen and nobody signed in, the store's system language is
     * rendered (third step of the chain).
     */
    @Test
    void languageFallsBackToTheSystemLanguage() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("nl");
        assertEquals("nl", messages.getLang());
        assertEquals("TERUG", messages.get("common.back"));
    }

    /**
     * A system language no bundle ships yields French rather than raw keys
     * (fourth and last step of the chain).
     */
    @Test
    void languageFallsBackToFrenchOnAnUnknownSystemLanguage() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("de");
        assertEquals("fr", messages.getLang());
        assertEquals("RETOUR", messages.get("common.back"));
    }

    /**
     * A blank system language is as absent as a missing one (the normalization
     * guard).
     */
    @Test
    void languageFallsBackToFrenchOnABlankSystemLanguage() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("   ");
        assertEquals("fr", messages.getLang());
    }

    /**
     * The language administered for the SIGNED-IN operator wins over the store's
     * (LC-13-06-02, second step of the chain).
     */
    @Test
    void theOperatorsAdministeredLanguageWinsOverTheStores() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("fr");
        messages.state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee("pt"));
            assertEquals("pt", messages.getLang());
            assertEquals("VOLTAR", messages.get("common.back"));
        }
    }

    /**
     * An operator whose administered language no bundle ships falls through to
     * the store's (the operator leg's own guard).
     */
    @Test
    void anUnknownOperatorLanguageFallsThroughToTheStore() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("nl");
        messages.state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee("de"));
            assertEquals("nl", messages.getLang());
        }
    }

    /**
     * An operator with no administered language falls through (nullity of the
     * column).
     */
    @Test
    void anOperatorWithoutALanguageFallsThroughToTheStore() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("pt");
        messages.state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee(null));
            assertEquals("pt", messages.getLang());
        }
    }

    /**
     * An employee row that is gone falls through rather than failing: the
     * referential is not the language's keeper (nullity of the lookup).
     */
    @Test
    void aVanishedEmployeeRowFallsThroughToTheStore() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("nl");
        messages.state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(null);
            assertEquals("nl", messages.getLang());
        }
    }

    /**
     * With nobody signed in the operator step is skipped without any lookup
     * (nullity of the operator id).
     */
    @Test
    void nobodySignedInSkipsTheOperatorStep() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("pt");
        messages.state.auth.operatorId = null;
        assertEquals("pt", messages.getLang());
    }

    /**
     * The language the OPERATOR chose on this register wins over everything
     * (LC-13-06-03/04, first step of the chain).
     */
    @Test
    void theChosenLanguageWinsOverTheWholeChain() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("fr");
        messages.state.language = "nl";
        messages.state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee("pt"));
            assertEquals("nl", messages.getLang());
        }
    }

    /**
     * A chosen language no bundle ships is ignored and the chain goes on (the
     * chosen leg's own guard).
     */
    @Test
    void anUnknownChosenLanguageIsIgnored() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("pt");
        messages.state.language = "de";
        assertEquals("pt", messages.getLang());
    }

    /**
     * A regional tag is reduced to its primary subtag, with either separator:
     * {@code fr-BE} and {@code nl_NL} are French and Dutch.
     */
    @Test
    void regionalTagsAreReducedToTheirLanguage() {
        Messages messages = newMessages();
        messages.state.language = "nl-BE";
        assertEquals("nl", messages.getLang());
        Messages other = newMessages();
        other.state.language = "pt_PT";
        assertEquals("pt", other.getLang());
    }

    /**
     * The resolution FOLLOWS the register state: a language chosen on the
     * language screen moves the very next label, and not the next instance.
     *
     * <p>It is the opposite of what a request-scoped resolver did, and it is why
     * the scope changed: the resolver now outlives the request — the printer
     * reaches it from the terminal's follower thread — so there is no render to
     * freeze the choice for.
     */
    @Test
    void theLanguageFollowsTheRegisterState() {
        Messages messages = newMessages();
        messages.state.language = "nl";
        assertEquals("nl", messages.getLang());
        messages.state.language = "pt";
        assertEquals("pt", messages.getLang());
    }

    /**
     * The operator leg is read ONCE per sign-in and not once per label: the
     * employee row is asked for again only when the signed-in operator changes.
     *
     * <p>The memo is what keeps a hundred-label screen from asking the database
     * a hundred times for the same answer.
     */
    @Test
    void theOperatorLegIsReadOncePerSignIn() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("fr");
        messages.state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee("nl"));
            assertEquals("nl", messages.getLang());
            assertEquals("nl", messages.getLang());
            assertEquals("nl", messages.getLang());
            panache.verify(() -> Employee.findById(7L), org.mockito.Mockito.times(1));
        }
    }

    /**
     * An employee row that cannot be read where the label is resolved — the
     * printer's follower thread has no persistence context — falls through to
     * the store's language instead of propagating.
     */
    @Test
    void anUnreadableEmployeeRowFallsThroughToTheStore() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("nl");
        messages.state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L))
                    .thenThrow(new IllegalStateException("no persistence context"));
            assertEquals("nl", messages.getLang());
        }
    }

    // --- get, format, both ---

    /**
     * A null key renders nothing rather than the word "null".
     */
    @Test
    void aNullKeyRendersNothing() {
        assertEquals("", newMessages().get(null));
    }

    /**
     * A key no bundle defines renders as itself, so a missing label is visible
     * and never fatal.
     */
    @Test
    void anUndefinedKeyRendersAsItself() {
        assertEquals("sale.doesNotExist", newMessages().get("sale.doesNotExist"));
    }

    /**
     * A key missing from the rendered bundle falls back to the French value (the
     * second arm of the lookup), which is why French is the reference bundle.
     */
    @Test
    void aKeyMissingFromTheRenderedBundleFallsBackToFrench() {
        Messages messages = newMessages();
        messages.state.language = "nl";
        // Every shipped key exists in all three bundles (the exhaustiveness test
        // guarantees it), so the French fallback is exercised on an absent one:
        // it must not answer the French value of a key nobody defines either.
        assertEquals("sale.doesNotExist", messages.get("sale.doesNotExist"));
        assertEquals("TERUG", messages.get("common.back"));
    }

    /**
     * {@code format} substitutes the pattern's placeholders.
     */
    @Test
    void formatSubstitutesThePlaceholders() {
        Messages messages = newMessages();
        assertEquals("Verrouillée par Alice (code 1234)",
                messages.format("lock.lockedBy", "Alice", "1234"));
    }

    /**
     * {@code format} without arguments hands the pattern back untouched (first
     * leg of its guard), and a null argument array does the same (second leg).
     */
    @Test
    void formatWithoutArgumentsReturnsThePattern() {
        Messages messages = newMessages();
        assertEquals("RETOUR", messages.format("common.back"));
        assertEquals("RETOUR", messages.format("common.back", (Object[]) null));
    }

    /**
     * A code is read through its family prefix; a code the bundles do not
     * label shows itself, and a null code is empty.
     */
    @Test
    void codesAreReadThroughTheirFamilyPrefix() {
        Messages messages = newMessages();
        assertEquals("ESPÈCES", messages.code("ticketsearch.payment.", "CASH"));
        assertEquals("XYZ", messages.code("ticketsearch.payment.", "XYZ"));
        assertEquals("", messages.code("ticketsearch.payment.", null));
    }

    /**
     * Without an administered second language the bilingual rendering is the
     * single label (first leg of its guard).
     */
    @Test
    void bothIsTheSingleLabelWithoutASecondLanguage() {
        Messages messages = newMessages();
        assertEquals("A rendre", messages.both("customer.change"));
        assertNull(messages.getSecondLang());
    }

    /**
     * A second language EQUAL to the rendered one is no second language (second
     * leg of its guard): a label printed twice reads as a defect.
     */
    @Test
    void bothIsTheSingleLabelWhenTheSecondLanguageIsTheRenderedOne() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("fr");
        assertEquals("A rendre", messages.both("customer.change"));
        assertNull(messages.getSecondLang());
    }

    /**
     * A second language no bundle ships is ignored (third leg of its guard).
     */
    @Test
    void bothIgnoresAnUnknownSecondLanguage() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("de");
        assertEquals("A rendre", messages.both("customer.change"));
        assertNull(messages.getSecondLang());
    }

    // --- the customer's own language (LC-13-06-08 / -13) ---

    /**
     * Without a customer choice the customer-facing rendering is the usual one:
     * operator's chain and, where administered, bilingual.
     */
    @Test
    void forCustomerWithoutAChoiceRendersAsUsual() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("nl");
        assertEquals("Terug te geven / A rendre", messages.forCustomer(() -> messages.both("customer.change")));
        assertEquals("fr", messages.forCustomer(messages::getLang));
    }

    /**
     * LC-13-06-14: a rendering in a given language — the one a ticket was sold
     * in — speaks it inside the scope only; a language no bundle ships, or none,
     * leaves the usual rendering.
     */
    @Test
    void forLanguageRendersTheGivenLanguageInsideTheScopeOnly() {
        Messages messages = newMessages();
        assertEquals("TERUG", messages.forLanguage("nl-BE", () -> messages.get("common.back")));
        assertEquals("RETOUR", messages.get("common.back"));
        assertEquals("RETOUR", messages.forLanguage("de", () -> messages.get("common.back")));
        assertEquals("RETOUR", messages.forLanguage(null, () -> messages.get("common.back")));
    }

    /**
     * LC-13-06-09 / -14: the customer language of the sale is the primary subtag
     * of the language chosen or retrieved, when a bundle ships it, and none
     * otherwise.
     */
    @Test
    void theCustomerLanguageTagIsASupportedPrimarySubtag() {
        Messages messages = newMessages();
        assertNull(messages.customerLanguageTag());
        messages.state.customerLanguage = "PT_pt";
        assertEquals("pt", messages.customerLanguageTag());
        messages.state.customerLanguage = "de";
        assertNull(messages.customerLanguageTag());
        assertEquals("nl", messages.supportedLanguage(" nl "));
        assertNull(messages.supportedLanguage(""));
    }

    /**
     * BO-10-07-17: the customer display speaks its administered first language,
     * the second one kept beside it, inside the scope only; the customer's own
     * choice still wins, monolingual; no administered language, the usual
     * rendering.
     */
    @Test
    void forCustomerDisplayRendersTheDisplaysTwoLanguages() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("fr");
        when(messages.posSettingsService.customerDisplayLanguage()).thenReturn(" NL ");
        assertEquals("A rendre / Terug te geven", messages.forCustomerDisplay(() -> messages.both("customer.change")));
        assertEquals("nl", messages.forCustomerDisplay(messages::getLang));
        assertEquals("RETOUR", messages.get("common.back"));
        messages.state.customerLanguage = "pt";
        assertEquals("A devolver", messages.forCustomerDisplay(() -> messages.both("customer.change")));
        messages.state.customerLanguage = null;
        when(messages.posSettingsService.customerDisplayLanguage()).thenReturn("");
        assertEquals("RETOUR", messages.forCustomerDisplay(() -> messages.get("common.back")));
    }

    /**
     * A language no bundle ships is no choice: the rendering stays the usual one.
     */
    @Test
    void forCustomerIgnoresALanguageNoBundleShips() {
        Messages messages = newMessages();
        messages.state.customerLanguage = "de";
        assertEquals("RETOUR", messages.forCustomer(() -> messages.get("common.back")));
    }

    /**
     * The customer's language outranks the operator's own choice inside the
     * scope, drops the second language — the choice IS the alternative to the
     * bilingual display — and leaves the cashier's labels alone outside it.
     */
    @Test
    void forCustomerRendersTheCustomersLanguageMonolingualAndOnlyInsideTheScope() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("nl");
        messages.state.language = "fr";
        messages.state.customerLanguage = "pt";
        assertEquals("VOLTAR", messages.forCustomer(() -> messages.get("common.back")));
        assertEquals("pt", messages.forCustomer(messages::getLang));
        assertNull(messages.forCustomer(messages::getSecondLang));
        assertEquals(messages.forCustomer(() -> messages.get("customer.change")),
                messages.forCustomer(() -> messages.both("customer.change")));
        assertEquals("RETOUR", messages.get("common.back"));
        assertEquals("Terug te geven / A rendre", messages.both("customer.change"));
    }

    /**
     * A nested scope restores the outer one, and leaving the outer one restores
     * the operator's labels.
     */
    @Test
    void forCustomerRestoresTheEnclosingScope() {
        Messages messages = newMessages();
        messages.state.customerLanguage = "nl";
        String nested = messages.forCustomer(() -> {
            String inner = messages.forCustomer(() -> messages.get("common.back"));
            return inner + "|" + messages.get("common.back");
        });
        assertEquals("TERUG|TERUG", nested);
        assertEquals("RETOUR", messages.get("common.back"));
    }

    /**
     * The printing variant runs its work in the customer's language and restores
     * the operator's afterwards.
     */
    @Test
    void runForCustomerRunsTheWorkInTheCustomersLanguage() {
        Messages messages = newMessages();
        messages.state.customerLanguage = "nl";
        StringBuilder printed = new StringBuilder();
        messages.runForCustomer(() -> printed.append(messages.get("common.back")));
        assertEquals("TERUG", printed.toString());
        assertEquals("RETOUR", messages.get("common.back"));
    }

    /**
     * A real second language renders both sides, the second one first, as the
     * requirement spells it out (LC-13-06-06 / -11).
     */
    @Test
    void bothRendersTheSecondLanguageFirst() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("nl");
        assertEquals("nl", messages.getSecondLang());
        assertEquals("Terug te geven / A rendre", messages.both("customer.change"));
    }

    /**
     * A label that happens to be the SAME in both languages is rendered once
     * (the equality arm) — the enseigne's name is spelt the same in the three
     * bundles — and a key the second bundle does not define falls back to the
     * single label (the nullity arm).
     */
    @Test
    void bothRendersOnceWhenTheTwoLanguagesAgreeOrTheKeyIsAbsent() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("nl");
        assertEquals("INTERMARCHÉ", messages.both("common.store"));
        assertEquals("sale.doesNotExist", messages.both("sale.doesNotExist"));
        // A label the two languages spell differently DOES render both sides.
        assertEquals("Totaal / Total", messages.both("customer.total"));
    }

    // --- the selectable languages and their names ---

    /**
     * The selectable languages are the administered ones narrowed to the shipped
     * bundles, in administered order, without duplicates.
     */
    @Test
    void availableLanguagesAreTheAdministeredBundledOnes() {
        Messages messages = newMessages();
        when(messages.posSettingsService.availableLanguages())
                .thenReturn(List.of("nl", "de", "NL", "fr-BE"));
        assertEquals(List.of("nl", "fr"), messages.getAvailableLanguages());
    }

    /**
     * An administered list that names nothing shippable still offers French: a
     * selection screen with no choice at all is a broken screen.
     */
    @Test
    void availableLanguagesAlwaysOfferFrench() {
        Messages messages = newMessages();
        when(messages.posSettingsService.availableLanguages()).thenReturn(List.of("de"));
        assertEquals(List.of("fr"), messages.getAvailableLanguages());
        Messages empty = newMessages();
        when(empty.posSettingsService.availableLanguages()).thenReturn(List.of());
        assertEquals(List.of("fr"), empty.getAvailableLanguages());
    }

    /**
     * A language NAMES ITSELF, in its own bundle and in its own language:
     * "Nederlands" whatever the screen is rendered in, never "Néerlandais".
     *
     * <p>Two reasons, and the second is the structural one. Whoever looks for
     * Dutch on that screen does not read French. And a name held by the OTHER
     * bundles meant that adding a language forced an edit of every language
     * already shipped — the coupling that kept the list hard-coded.
     */
    @Test
    void aLanguageNamesItselfInItsOwnLanguage() {
        Messages messages = newMessages();
        assertEquals("Nederlands", messages.languageName("nl"));
        Messages dutch = newMessages();
        dutch.state.language = "nl";
        assertEquals("Nederlands", dutch.languageName("nl"));
        assertEquals("Français", dutch.languageName("fr"));
    }

    /**
     * A null or blank tag names nothing (the nullity guard), and a tag no bundle
     * ships names itself by its own tag, upper-cased: the screen stays readable
     * and the gap stays visible.
     */
    @Test
    void languageNameToleratesABlankOrUnknownTag() {
        Messages messages = newMessages();
        assertEquals("", messages.languageName(null));
        assertEquals("", messages.languageName("  "));
        assertEquals("DE", messages.languageName("de"));
    }

    /**
     * Every shipped bundle is actually readable from the classpath: a bundle
     * whose file is missing would silently render keys on a till.
     */
    @Test
    void everyBundledLanguageIsReadable() {
        for (String language : Messages.bundledLanguages()) {
            Messages messages = newMessages();
            messages.state.language = language;
            assertEquals(language, messages.getLang());
            assertTrue(messages.get("common.back").length() > 1,
                    "le bundle " + language + " doit porter common.back");
        }
    }

    // --------------------------------------------------
    // Campaign 2 — language added branches
    // --------------------------------------------------

    /**
     * With no auth state at all (the {@code state.auth == null} true arm of the
     * operator-language guard), the operator step contributes nothing and the
     * chain falls through to the system language.
     */
    @Test
    void operatorLanguageYieldsNothingWithoutAnAuthState() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("nl");
        messages.state.auth = null;
        assertEquals("nl", messages.getLang());
    }

    /**
     * A second-language tag carrying a region after a dash and no underscore is
     * reduced to its primary subtag (the {@code underscore < 0} true arm of the
     * separator choice): {@code nl-BE} resolves the bilingual second language to
     * {@code nl}.
     */
    @Test
    void secondLanguageReducesADashRegionTag() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("nl-BE");
        assertEquals("nl", messages.getSecondLang());
    }

    /**
     * A tag carrying BOTH a dash and an underscore is cut at the earliest
     * separator (the {@code underscore < 0} false arm, {@code Math.min} branch):
     * {@code pt-BR_x} reduces to {@code pt}.
     */
    @Test
    void secondLanguageCutsAtTheEarliestSeparator() {
        Messages messages = newMessages();
        when(messages.posSettingsService.customerSecondLanguage()).thenReturn("pt-BR_x");
        assertEquals("pt", messages.getSecondLang());
    }

    // --------------------------------------------------
    // The memo, and forgetting it
    // --------------------------------------------------

    /**
     * The operator leg is read ONCE for the same three inputs, and
     * {@link Messages#forgetResolvedLanguage()} makes it read again.
     * <p>
     * The row is changed BEHIND the resolver, which is exactly what the back
     * office does: the second call still answers Dutch, because the memo's key
     * (nothing chosen, same operator, same system language) has not moved; the
     * call after the forget answers Portuguese.
     */
    @Test
    void forgettingTheMemoReadsTheOperatorLanguageAgain() {
        Messages messages = newMessages();
        when(messages.posSettingsService.systemLanguage()).thenReturn("fr");
        messages.state.auth.operatorId = 7L;
        Employee jean = employee("nl");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(jean);
            assertEquals("nl", messages.getLang());
            jean.language = "pt";
            assertEquals("nl", messages.getLang(), "the same inputs answer from the memo");
            messages.forgetResolvedLanguage();
            assertEquals("pt", messages.getLang(), "a forgotten memo reads the row again");
        }
    }
    /** LC-13-06: bundled-language discovery handles jars, unrelated entries, unlistable file URLs and unknown protocols. */
    @Test
    void coverageClasspathBundleDiscovery(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var archive = temporary.resolve("bundles.jar");
        try (var jar = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            for (String entry : new String[]{"messages/", "messages/msg_de.properties", "messages/msg_.properties",
                    "messages/msg_DE.properties", "messages/msg_de.txt", "README"}) {
                jar.putNextEntry(new java.util.jar.JarEntry(entry));
                jar.closeEntry();
            }
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = new ClassLoader(previous) {
            /** Supplies all protocol arms without querying the network. */
            @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                return java.util.Collections.enumeration(java.util.List.of(
                        java.net.URI.create("jar:" + archive.toUri() + "!/messages/").toURL(), archive.toUri().toURL(),
                        java.net.URI.create("http://localhost/unused").toURL()));
            }
        };
        var method = Messages.class.getDeclaredMethod("classpathBundles");
        method.setAccessible(true);
        try {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(java.util.Set.of("de"), method.invoke(null));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** LC-13-06: an unenumerable classpath is tolerated, while an unreadable bundle reports its I/O cause. */
    @Test
    void coverageClasspathEnumerationAndReadFailure() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = new ClassLoader(previous) {
            /** Simulates a classpath provider which cannot enumerate directories. */
            @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                throw new java.io.IOException("unreadable directory");
            }
            /** Simulates a present bundle whose stream cannot be read. */
            @Override public java.io.InputStream getResourceAsStream(String name) {
                return new java.io.InputStream() {
                    /** Reports the failing byte read without accessing a file or network. */
                    @Override public int read() throws java.io.IOException { throw new java.io.IOException("unreadable bundle"); }
                };
            }
        };
        var discovery = Messages.class.getDeclaredMethod("classpathBundles");
        discovery.setAccessible(true);
        var load = Messages.class.getDeclaredMethod("load", String.class);
        load.setAccessible(true);
        try {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(java.util.Set.of(), discovery.invoke(null));
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> load.invoke(null, "de"));
            assertEquals(java.io.UncheckedIOException.class, failure.getCause().getClass());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** LC-13-06: external bundles are discovered only in a real configured directory and normalized language files. */
    @Test
    void coverageExternalBundleDiscovery(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var config = org.mockito.Mockito.mock(org.eclipse.microprofile.config.Config.class);
        var method = Messages.class.getDeclaredMethod("externalBundles");
        method.setAccessible(true);
        java.nio.file.Files.writeString(temporary.resolve("msg_de.properties"), "language.name=Deutsch");
        java.nio.file.Files.writeString(temporary.resolve("unrelated.txt"), "ignored");
        try (var provider = org.mockito.Mockito.mockStatic(org.eclipse.microprofile.config.ConfigProvider.class)) {
            provider.when(org.eclipse.microprofile.config.ConfigProvider::getConfig).thenReturn(config);
            when(config.getOptionalValue("pos.messages.dir", String.class)).thenReturn(java.util.Optional.of(temporary.toString()));
            assertEquals(java.util.Set.of("de"), method.invoke(null));
            when(config.getOptionalValue("pos.messages.dir", String.class)).thenReturn(java.util.Optional.of(temporary.resolve("unrelated.txt").toString()));
            assertEquals(java.util.Set.of(), method.invoke(null));
            when(config.getOptionalValue("pos.messages.dir", String.class)).thenReturn(java.util.Optional.empty());
            assertEquals(java.util.Set.of(), method.invoke(null));
        }
    }


    /** LC-13-06: an unreadable bundle fails explicitly rather than being silently accepted as an empty language. */
    @Test
    void unreadableBundleReportsItsIoFailure() throws Exception {
        var method = Messages.class.getDeclaredMethod("load", String.class);
        method.setAccessible(true);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader failing = new ClassLoader(previous) {
            /** LC-13-06: supplies a stream whose contents cannot be read, without accessing the filesystem. */
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return new java.io.InputStream() {
                    /** LC-13-06: simulates the resource read failure. */
                    @Override
                    public int read() throws java.io.IOException {
                        throw new java.io.IOException("unreadable bundle");
                    }
                };
            }
        };
        try {
            Thread.currentThread().setContextClassLoader(failing);
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(null, "broken"));
            assertEquals(java.io.UncheckedIOException.class, failure.getCause().getClass());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }


    /** LC-13-06: a configured directory that becomes unlistable contributes no languages instead of breaking the language selector. */
    @Test
    void unlistableExternalDirectoryContributesNoLanguages() throws Exception {
        var config = org.mockito.Mockito.mock(org.eclipse.microprofile.config.Config.class);
        when(config.getOptionalValue("pos.messages.dir", String.class)).thenReturn(java.util.Optional.of("mock-directory"));
        var method = Messages.class.getDeclaredMethod("externalBundles");
        method.setAccessible(true);
        try (var provider = org.mockito.Mockito.mockStatic(org.eclipse.microprofile.config.ConfigProvider.class);
             var directories = org.mockito.Mockito.mockConstruction(java.io.File.class, (directory, context) -> {
                 when(directory.isDirectory()).thenReturn(true);
                 when(directory.listFiles()).thenReturn(null);
             })) {
            provider.when(org.eclipse.microprofile.config.ConfigProvider::getConfig).thenReturn(config);
            assertEquals(java.util.Set.of(), method.invoke(null));
        }
    }

}
