package com.intermarche.pos.ui;

import com.intermarche.pos.domain.payment.PaymentTypes;
import com.intermarche.pos.ui.ticket.ManualService;
import io.quarkus.qute.Engine;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Template;
import io.quarkus.qute.Variant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The texts of the register's screens that the recotation proved wrong, held
 * where they are written: the bundles and the actual Qute templates, rendered
 * with the real French, Dutch and Portuguese bundles.
 *
 * <p>The templates are parsed from {@code src/main/resources/templates}, which
 * surefire runs from the module root, and rendered by a plain Qute engine whose
 * {@code inject:} namespace answers the register's resolver — exactly what the
 * screen does, without booting the application.
 */
class RegisterScreenTextsTest {

    /** Where the templates live, relative to the module root. */
    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates");

    /**
     * Reads one shipped bundle from the classpath.
     *
     * @param language the language tag
     * @return its keys and values
     * @throws IOException if the bundle cannot be read
     */
    private static Properties bundle(String language) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = RegisterScreenTextsTest.class.getClassLoader()
                .getResourceAsStream("messages/msg_" + language + ".properties")) {
            assertNotNull(in, language);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    /**
     * A resolver rendering the given language.
     *
     * @param language the language tag
     * @return the resolver
     */
    private static Messages messages(String language) {
        PosState state = new PosState();
        state.language = language;
        return TestMessages.french(state);
    }

    /**
     * Builds a Qute engine whose {@code inject:} namespace answers the resolver,
     * with the given includes registered from disk.
     *
     * @param messages the resolver the templates read their labels through
     * @param includes the include templates the rendered page names
     * @return the engine
     * @throws IOException if an include cannot be read
     */
    private static Engine engine(Messages messages, String... includes) throws IOException {
        Engine engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new HtmlEscaper(List.of("text/html")))
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(context -> messages).build())
                .build();
        for (String include : includes) {
            engine.putTemplate(include, engine.parse(Files.readString(TEMPLATES.resolve(include + ".html"))));
        }
        return engine;
    }

    /**
     * Parses a shipped template as HTML.
     *
     * @param engine the engine
     * @param name the template name, without its extension
     * @return the parsed template
     * @throws IOException if the template cannot be read
     */
    private static Template template(Engine engine, String name) throws IOException {
        return engine.parse(Files.readString(TEMPLATES.resolve(name + ".html")), Variant.forContentType("text/html"));
    }

    /**
     * The line percentage discount is « Remise » on a French register, and named
     * in Dutch and Portuguese on theirs — never the English « Discount ».
     *
     * @throws IOException if a bundle cannot be read
     */
    @Test
    void theLineDiscountIsNamedInEachLanguage() throws IOException {
        assertEquals("Remise -{0,number,0.00}%", bundle("fr").getProperty("ticket.rs21-discount"));
        assertEquals("Korting -{0,number,0.00}%", bundle("nl").getProperty("ticket.rs21-discount"));
        assertEquals("Desconto -{0,number,0.00}%", bundle("pt").getProperty("ticket.rs21-discount"));
        assertEquals("Remise -10,00%", messages("fr").format("ticket.rs21-discount", new java.math.BigDecimal("10")));
    }

    /**
     * Every settlement key the register records has its label in the three
     * bundles, so no screen shows a raw code such as « CAS » for the social
     * purchase card.
     *
     * @throws IOException if a bundle cannot be read
     */
    @Test
    void everySettlementKeyIsLabelledInTheThreeBundles() throws IOException {
        for (String language : List.of("fr", "nl", "pt")) {
            Properties labels = bundle(language);
            for (String key : PaymentTypes.keys()) {
                assertNotNull(labels.getProperty("ticketsearch.payment." + key), language + " " + key);
            }
        }
        assertEquals("CARTE ACHAT SOCIALE", messages("fr").code("ticketsearch.payment.", "CAS"));
        assertEquals("CARTÃO DE COMPRAS SOCIAL", messages("pt").code("ticketsearch.payment.", "CAS"));
    }

    /**
     * The hardware page titles itself after its state: « Matériel disponible »
     * when every peripheral answers, « Matériel indisponible » otherwise, and its
     * heading in the till's language on both faces.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void theHardwarePageTitleFollowsItsState() throws IOException {
        Engine engine = engine(messages("fr"), "theme");
        Template page = template(engine, "hardware-unavailable");
        String available = page.data("posTheme", "clair").data("allAvailable", true)
                .data("devices", List.of()).render();
        assertTrue(available.contains("<title>Matériel disponible</title>"), available);
        String unavailable = page.data("posTheme", "clair").data("allAvailable", false)
                .data("devices", List.of()).render();
        assertTrue(unavailable.contains("<title>Matériel indisponible</title>"), unavailable);
        String dutch = template(engine(messages("nl"), "theme"), "hardware-unavailable")
                .data("posTheme", "clair").data("allAvailable", false).data("devices", List.of()).render();
        assertTrue(dutch.contains("<h2>Apparatuur niet beschikbaar</h2>"), dutch);
        assertFalse(dutch.contains("Matériel"), dutch);
    }

    /**
     * Renders the SAISIE DIRECTE screen in a language, on a sub-level page with a
     * disabled pager on both sides, a tile showing its EAN and a family tile.
     *
     * @param language the language tag
     * @param root whether the page is the root level
     * @return the rendered page
     * @throws IOException if a template cannot be read
     */
    private static String manualPage(String language, boolean root) throws IOException {
        Template page = template(engine(messages(language), "theme", "keyboard-lib"), "manual");
        ManualService.ManualItem article = new ManualService.ManualItem("CHIPS", false, null,
                "3300000000014", "size-normal", false);
        article.priceFormatted = "1,80 €";
        ManualService.ManualItem group = new ManualService.ManualItem("CUISSON", true, "/manual/cat/CUISSON",
                null, "size-normal", false);
        return page.data("posTheme", "clair").data("posKeyboardLayout", "AZERTY").data("cancelMode", false)
                .data("items", List.of(article, group)).data("gridColumns", 6).data("gridRows", 2)
                .data("breadcrumb", "Start > CUISSON").data("isRoot", root).data("articleSort", "DEFAULT")
                .data("parentUrl", "/manual").data("page", 2).data("totalPages", 3)
                .data("prevUrl", null).data("nextUrl", null).data("showArticleEan", true)
                .data("unknownCode", "").render();
    }

    /**
     * The SAISIE DIRECTE screen of a Dutch till carries no French text: the way
     * back, the disabled pager, the page counter and the keypad titles all come
     * from the bundle. The EAN of a tile is shrunk with the tile width and wraps
     * rather than being clipped.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void theManualScreenIsTranslatedAndKeepsTheEanReadable() throws IOException {
        String dutch = manualPage("nl", false);
        assertTrue(dutch.contains("class=\"grid-btn back-btn\">&lt; TERUG</a>"), dutch);
        assertTrue(dutch.contains("<span class=\"btn btn-sm btn-primary off\">VORIGE</span>"), dutch);
        assertTrue(dutch.contains("<span class=\"btn btn-sm btn-primary off\">VOLGENDE</span>"), dutch);
        assertTrue(dutch.contains("<span class=\"pos-pager-info\">PAGINA 2 / 3</span>"), dutch);
        assertTrue(dutch.contains("title.innerText = \"PRIJS INVOEREN\";"), dutch);
        assertTrue(dutch.contains("title.innerText = \"AANTAL INVOEREN\";"), dutch);
        assertTrue(dutch.contains("<span class=\"article-code\">3300000000014</span>"), dutch);
        for (String french : List.of("&lt; RETOUR<", ">PRÉCÉDENT<", ">SUIVANT<", ">PAGE 2 / 3<", "\"SAISIE PRIX\"",
                "\"SAISIE QUANTITÉ\"")) {
            assertFalse(dutch.contains(french), french);
        }
        assertTrue(dutch.contains("font-size: min(var(--fs-btn-sm), 11cqi)"), dutch);
        assertTrue(dutch.contains("overflow-wrap: anywhere"), dutch);
        String rootPage = manualPage("pt", true);
        assertTrue(rootPage.contains("selectProduct(this, 'PRODUTO NÃO RECONHECIDO', '')"), rootPage);
    }

    /**
     * Renders one fragment of the sale screen.
     *
     * @param language the language tag
     * @param fragment the fragment id
     * @param state the register state the fragment reads
     * @return the rendered fragment
     * @throws IOException if a template cannot be read
     */
    private static String saleFragment(String language, String fragment, PosState state) throws IOException {
        Template page = template(engine(messages(language), "theme", "keyboard-lib", "ticket", "endorsement",
                "peripheral-status"), "main");
        return page.getFragment(fragment).data("state", state).render();
    }

    /**
     * The supervisor-session banner names the supervisor in the till's language.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void theSupervisorBannerIsTranslated() throws IOException {
        PosState state = new PosState();
        state.supervisorSessionBadge = "11111111";
        String portuguese = saleFragment("pt", "supervisor_banner", state);
        assertTrue(portuguese.contains("SUPERVISOR 11111111 — <a"), portuguese);
        assertTrue(portuguese.contains("FIM SUPERVISOR"), portuguese);
        assertFalse(portuguese.contains("SUPERVISEUR"), portuguese);
        assertTrue(saleFragment("fr", "supervisor_banner", state).contains("SUPERVISEUR 11111111 — <a"));
    }

    /**
     * LC-05-02-02/-15: a PRICE prompt states the current catalogue price the
     * operator confirms or changes, per article (a piece article's unit of
     * measure is not what its price is per); a quantity prompt states the price per unit
     * of measure, its unit only when the article has one; a price prompt with no
     * catalogue price states nothing.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void thePricePromptShowsTheCurrentPrice() throws IOException {
        PosState state = new PosState();
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
        state.entryPrompt.label = "HUILE D'OLIVE 1L";
        state.entryPrompt.unitPriceFormatted = "6,00";
        state.entryPrompt.unitName = "L";
        String price = saleFragment("fr", "entry_prompt", state);
        assertTrue(price.contains("PRIX ACTUEL : 6,00 €"), price);
        assertFalse(price.contains("6,00 € /"), price);
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.unitName = "m";
        String quantity = saleFragment("fr", "entry_prompt", state);
        assertTrue(quantity.contains("6,00 € / m"), quantity);
        state.entryPrompt.unitName = "";
        String whole = saleFragment("fr", "entry_prompt", state);
        assertTrue(whole.contains("6,00 €"), whole);
        assertFalse(whole.contains("6,00 € /"), whole);
        state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
        state.entryPrompt.unitPriceFormatted = "";
        assertFalse(saleFragment("fr", "entry_prompt", state).contains("entryCurrentPrice"));
    }

    /**
     * Every screen carrying the supervisor panel includes the shared keypad
     * library BEFORE it: the panel draws its keyboard with {@code PosKeyboards}
     * as soon as it loads, and a library included after it left the page with a
     * « PosKeyboards is not defined » error.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void theKeypadLibraryPrecedesTheSupervisorPanel() throws IOException {
        try (var templates = Files.list(TEMPLATES)) {
            for (Path template : templates.toList()) {
                String source = Files.readString(template);
                int panel = source.indexOf("{#include endorsement /}");
                if (panel >= 0) {
                    int library = source.indexOf("{#include keyboard-lib /}");
                    assertTrue(library >= 0 && library < panel, template.toString());
                }
            }
        }
    }
}
