package com.intermarche.pos.ui;

import io.quarkus.arc.Unremovable;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the localised labels of the register's screens, the customer display
 * and the printed documents (LC-13-06).
 *
 * <p>SAME MECHANISM AS THE BACK OFFICE. The valuation back office localises its
 * screens through a {@code @Named("i18n")} request-scoped bean reached from the
 * templates as {@code {inject:i18n.get('key')}}, with one {@code
 * messages/msg_<lang>.properties} bundle per language and the French bundle as
 * the exhaustive reference. The till does the same, deliberately: two bricks of
 * one suite that localise two different ways cost twice to maintain and drift
 * apart on the first label added in a hurry.
 *
 * <p>WHAT DIFFERS, AND WHY. The back office negotiates {@code Accept-Language},
 * which is the right answer for a browser someone chose. A till's browser says
 * nothing about who is standing at it: the same machine serves a French-speaking
 * cashier at eight and a Dutch-speaking one at two. So the chain here is the
 * OPERATOR's, most specific first:
 * <ol>
 *   <li>the language chosen on this register since the last sign-in
 *       ({@link PosState#language}) — the operator's own choice, before signing
 *       in (LC-13-06-03) or at any moment (LC-13-06-04);</li>
 *   <li>the language administered for the signed-in operator
 *       ({@code Employee.language}, referential data) — LC-13-06-02;</li>
 *   <li>the store's system language (back-office parameter
 *       {@code language.system}) — LC-13-06-01;</li>
 *   <li>French.</li>
 * </ol>
 *
 * <p>A SECOND LANGUAGE, for the bilingual customer display and ticket of a
 * bilingual region (LC-13-06-06 / -11): {@code language.customer-second} names
 * it, and {@link #both(String)} renders the two sides of one label. Empty, the
 * customer display and the ticket stay monolingual, which is every store but the
 * Belgian bilingual ones.
 *
 * <p>A MISSING KEY IS NEVER FATAL: the value falls back to the French bundle,
 * then to the key itself. A till must show a wrong label rather than a broken
 * screen, and the exhaustiveness test keeps the bundles aligned so the fallback
 * stays theoretical.
 *
 * <p>Package placement: consumed exclusively by the screens and the documents —
 * ui root, like {@link ThemeService}, the other cross-screen concern.
 */
@Named("i18n")
@RequestScoped
@TemplateData
@Unremovable
public class Messages {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(Messages.class);

    /** The default language: the exhaustive reference bundle. */
    public static final String DEFAULT_LANGUAGE = "fr";

    /** The languages the register ships a bundle for, in display order. */
    public static final List<String> BUNDLED_LANGUAGES = List.of("fr", "nl", "pt");

    /** Loaded bundles, keyed by language; a bundle is read from the classpath once. */
    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();

    /** The register state: carries the language the operator chose. */
    @Inject
    PosState state;

    /** Administered settings: the store's system language and the second one. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The language resolved for this request, computed once. */
    private String resolvedLanguage;

    /**
     * Returns the localised label of a key, in the language resolved for this
     * request.
     *
     * @param key the message key, or null
     * @return the localised label, the French value as a fallback, or the key
     *         itself when it is defined nowhere
     */
    public String get(String key) {
        if (key == null) {
            return "";
        }
        String language = language();
        String value = bundle(language).get(key);
        if (value == null && !DEFAULT_LANGUAGE.equals(language)) {
            value = bundle(DEFAULT_LANGUAGE).get(key);
        }
        return value != null ? value : key;
    }

    /**
     * Returns the localised label of a key with its {@code {0}}, {@code {1}}…
     * placeholders substituted.
     *
     * @param key the message key
     * @param args the arguments substituted into the pattern, possibly empty
     * @return the formatted localised label
     */
    public String format(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, Locale.forLanguageTag(language())).format(args);
    }

    /**
     * Returns a label in BOTH languages of a bilingual store, second language
     * first and separated by a slash — the shape the requirement spells out,
     * {@code "Terug te geven / A rendre"} (LC-13-06-06 / -11).
     *
     * <p>Falls back to the single resolved label when no second language is
     * administered, when it is the resolved one, or when the two renderings are
     * identical: a label printed twice reads as a defect, not as a courtesy.
     *
     * @param key the message key
     * @return the bilingual label, or the single one when there is no second
     *         language to show
     */
    public String both(String key) {
        String second = secondLanguage();
        String primary = get(key);
        if (second == null) {
            return primary;
        }
        String other = bundle(second).get(key);
        if (other == null || other.equals(primary)) {
            return primary;
        }
        return other + " / " + primary;
    }

    /**
     * Returns the NAME of a language in the language being rendered — "Dutch" in
     * French, "Nederlands" in Dutch — for the selection screen.
     *
     * <p>It exists because Qute has no concatenation operator: a template cannot
     * build the key {@code language.nl} from the tag it is looping over, so the
     * key is built here.
     *
     * @param tag the language tag, possibly null
     * @return the language's name, or the tag itself when it has no label
     */
    public String languageName(String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return "";
        }
        return get("language." + normalized);
    }

    /**
     * Returns the language actually rendered, for the {@code <html lang>}
     * attribute and for the screens that highlight the current choice.
     *
     * @return the rendered language tag
     */
    public String getLang() {
        return language();
    }

    /**
     * Returns the administered second language of the customer display and the
     * ticket, or null when the store is monolingual (LC-13-06-06 / -11).
     *
     * @return the second language tag, or null
     */
    public String getSecondLang() {
        return secondLanguage();
    }

    /**
     * Returns the languages the operator may choose from: the administered list
     * narrowed to the ones a bundle ships, never empty (the default language is
     * always offered).
     *
     * @return the selectable language tags, in administered order
     */
    public List<String> getAvailableLanguages() {
        List<String> available = new ArrayList<>();
        for (String entry : posSettingsService.availableLanguages()) {
            String tag = normalize(entry);
            if (BUNDLED_LANGUAGES.contains(tag) && !available.contains(tag)) {
                available.add(tag);
            }
        }
        if (available.isEmpty()) {
            available.add(DEFAULT_LANGUAGE);
        }
        return available;
    }

    /**
     * Resolves, once per request, the language to render: the operator's own
     * choice, then the signed-in operator's administered language, then the
     * store's system language, then French. A language no bundle ships is
     * ignored at every step rather than shown as raw keys.
     *
     * @return the language tag to render
     */
    private String language() {
        if (resolvedLanguage == null) {
            String chosen = normalize(state.language);
            String operator = normalize(operatorLanguage());
            String system = normalize(posSettingsService.systemLanguage());
            if (BUNDLED_LANGUAGES.contains(chosen)) {
                resolvedLanguage = chosen;
            } else if (BUNDLED_LANGUAGES.contains(operator)) {
                resolvedLanguage = operator;
            } else if (BUNDLED_LANGUAGES.contains(system)) {
                resolvedLanguage = system;
            } else {
                resolvedLanguage = DEFAULT_LANGUAGE;
            }
        }
        return resolvedLanguage;
    }

    /**
     * Returns the language administered for the signed-in operator
     * (LC-13-06-02), or null when nobody is signed in, the employee row is gone
     * or no language was administered for them.
     *
     * @return the operator's administered language, or null
     */
    private String operatorLanguage() {
        if (state.auth == null || state.auth.operatorId == null) {
            return null;
        }
        com.intermarche.pos.domain.people.Employee employee =
                com.intermarche.pos.domain.people.Employee.findById(state.auth.operatorId);
        return employee == null ? null : employee.language;
    }

    /**
     * Returns the administered second language, normalized and checked against
     * the shipped bundles, or null when there is none to show.
     *
     * @return the second language tag, or null
     */
    private String secondLanguage() {
        String second = normalize(posSettingsService.customerSecondLanguage());
        if (!BUNDLED_LANGUAGES.contains(second) || second.equals(language())) {
            return null;
        }
        return second;
    }

    /**
     * Reduces a language tag to the lower-case primary subtag this register
     * reasons about ({@code fr-BE} → {@code fr}).
     *
     * @param tag the language tag, possibly null or blank
     * @return the primary subtag, or an empty string
     */
    private static String normalize(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String trimmed = tag.trim().toLowerCase(Locale.ROOT);
        int dash = trimmed.indexOf('-');
        int underscore = trimmed.indexOf('_');
        int separator;
        if (dash < 0) {
            separator = underscore;
        } else if (underscore < 0) {
            separator = dash;
        } else {
            separator = Math.min(dash, underscore);
        }
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }

    /**
     * Returns a language's bundle, reading it from the classpath on first use.
     *
     * @param language the language tag
     * @return the key/value pairs of that bundle, empty when the file is absent
     */
    private static Map<String, String> bundle(String language) {
        return BUNDLES.computeIfAbsent(language, Messages::load);
    }

    /**
     * Loads a bundle from the classpath, UTF-8 encoded.
     *
     * @param language the bundle language
     * @return the loaded key/value pairs; empty when the bundle file is absent
     */
    private static Map<String, String> load(String language) {
        Map<String, String> map = new HashMap<>();
        String path = "messages/msg_" + language + ".properties";
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.warnf("Bundle de messages absent : %s", path);
                return map;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String name : properties.stringPropertyNames()) {
                map.put(name, properties.getProperty(name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Bundle de messages illisible : " + path, e);
        }
        return map;
    }
}
