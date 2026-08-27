package com.intermarche.pos.service;

import com.intermarche.pos.domain.PosSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The register's BACK-OFFICE PARAMETERS: the typed catalog of known keys
 * (label, type, default) and the cached access to their current values.
 * <p>
 * Values live in {@code pos_settings} — administered on the store node's
 * back office and distributed by the referential pull (domain SETTINGS) —
 * and an ABSENT row means the default applies. Two keys keep their
 * historical {@code application.properties} fallback ({@code
 * pos.display.show-ean}, {@code pos.auth.idle-lockout-seconds}): a value
 * set there still works on a node that has never been administered.
 * <p>
 * The whole table is cached in memory and invalidated on every admin save
 * and every pull apply — settings are read on hot paths (the lock filter,
 * the polls) and must never cost a query per request.
 */
@ApplicationScoped
public class PosSettingsService {

    private static final Logger LOG = Logger.getLogger(PosSettingsService.class);

    /** Value type of a catalog entry — drives the admin form widget. */
    public enum Type { BOOL, INT, TEXT }

    /**
     * One catalog entry: the contract of a known parameter key.
     *
     * @param key the storage key
     * @param type the value type
     * @param section the admin-page section heading
     * @param label the operator-facing label (French)
     * @param hint the operator-facing explanation (French)
     * @param defaultValue the value applying when no row overrides it
     * @param configFallback the legacy MicroProfile key consulted before the
     *        default when no row exists, or null
     */
    public record Def(String key, Type type, String section, String label,
                      String hint, String defaultValue, String configFallback) {
    }

    /**
     * The catalog of administrable parameters — the admin page renders it
     * in order, sections grouped. Adding a parameter is one entry here plus
     * its consumer.
     */
    public static final List<Def> CATALOG = List.of(
        new Def("display.show-ean", Type.BOOL, "AFFICHAGE",
                "EAN sur les écrans et le ticket",
                "L'EAN accompagne le libellé article sur la visu caisse, la visu client et le ticket imprimé (LC-02-04).",
                "false", "pos.display.show-ean"),
        new Def("auth.idle-lockout-seconds", Type.INT, "SESSION CAISSE",
                "Pause automatique (secondes)",
                "Délai d'inactivité avant verrouillage automatique de la caisse. 0 = désactivé (LC-01-03-02).",
                "0", "pos.auth.idle-lockout-seconds"),
        new Def("gesture.endorsement-required", Type.BOOL, "GESTES DE PRIX",
                "Autorisation superviseur sur les gestes",
                "Remises, discounts, forçages et remises ticket exigent un avenant manager. Désactivé : le caissier applique seul (LC-03-02-08).",
                "true", null),
        new Def("discount.line-max-percent", Type.INT, "GESTES DE PRIX",
                "Discount article : % maximum",
                "Pourcentage maximal d'un discount de ligne (LC-03-02-07).",
                "100", null),
        new Def("discount.global-max-percent", Type.INT, "GESTES DE PRIX",
                "Remise ticket : % maximum",
                "Pourcentage maximal d'une remise ticket (LC-03-02-13).",
                "100", null),
        new Def("customer.message-open", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse ouverte",
                "Texte affiché sur l'écran client en attente de transaction (LC-10-01-20).",
                "Bienvenue", null),
        new Def("customer.message-closed", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse fermée",
                "Texte affiché sur l'écran client quand la caisse est fermée (LC-10-01-21).",
                "Caisse fermée", null),
        new Def("parking.print-receipt", Type.BOOL, "TICKETS",
                "Imprimer le ticket de mise en attente",
                "Un reçu portant le numéro de reprise sort à chaque mise en attente (LC-04-01-02).",
                "true", null));

    /** The cached rows, or null when a reload is due. */
    private volatile Map<String, String> cache = null;

    /**
     * Returns the catalog entry of a key.
     *
     * @param key the catalog key
     * @return the entry, or null for an unknown key
     */
    public Def def(String key) {
        for (Def d : CATALOG) {
            if (d.key().equals(key)) return d;
        }
        return null;
    }

    /**
     * Returns the CURRENT value of a key: the stored row when present, else
     * the legacy configuration fallback when declared, else the default.
     *
     * @param key the catalog key
     * @return the effective value, as text
     */
    public String value(String key) {
        Map<String, String> values = cache;
        if (values == null) {
            values = loadAll();
            cache = values;
        }
        String stored = values.get(key);
        if (stored != null) return stored;
        Def d = def(key);
        if (d == null) return null;
        if (d.configFallback() != null) {
            java.util.Optional<String> fromConfig = ConfigProvider.getConfig()
                    .getOptionalValue(d.configFallback(), String.class);
            if (fromConfig.isPresent()) return fromConfig.get();
        }
        return d.defaultValue();
    }

    /**
     * Reads every stored row — one query, called only on a cold cache.
     *
     * @return the key-to-value map of stored overrides
     */
    private Map<String, String> loadAll() {
        Map<String, String> values = new HashMap<>();
        try {
            for (PosSetting row : PosSetting.<PosSetting>listAll()) {
                values.put(row.settingKey, row.settingValue);
            }
        } catch (Exception e) {
            // A failed read (boot ordering, missing table) falls back to the
            // defaults; the next access retries.
            LOG.warnf("Lecture des paramètres impossible (%s): valeurs par défaut", e.getMessage());
            cache = null;
        }
        return values;
    }

    /** Drops the cache — called after every admin save and pull apply. */
    public void invalidate() {
        cache = null;
    }

    /**
     * Stores one value (upsert by key) — the admin page's save path.
     *
     * @param key the catalog key
     * @param value the new value, already validated by the caller
     */
    @Transactional
    public void store(String key, String value) {
        PosSetting row = PosSetting.findByKey(key);
        if (row == null) {
            row = new PosSetting();
            row.settingKey = key;
        }
        row.settingValue = value;
        row.persist();
        invalidate();
    }

    /**
     * Returns a boolean parameter.
     *
     * @param key the catalog key
     * @return the effective boolean value
     */
    private boolean boolValue(String key) {
        return Boolean.parseBoolean(value(key));
    }

    /**
     * Returns an integer parameter, guarding against a corrupt row.
     *
     * @param key the catalog key
     * @return the effective integer value, or the catalog default when corrupt
     */
    private int intValue(String key) {
        try {
            return Integer.parseInt(value(key).trim());
        } catch (Exception e) {
            return Integer.parseInt(def(key).defaultValue());
        }
    }

    /**
     * Whether the article EAN accompanies the label on screen and paper.
     *
     * @return true when the EAN is displayed
     */
    public boolean showEan() { return boolValue("display.show-ean"); }

    /**
     * The idle-lockout delay in seconds; 0 disables the automatic pause.
     *
     * @return the delay in seconds
     */
    public long idleLockoutSeconds() { return intValue("auth.idle-lockout-seconds"); }

    /**
     * Whether price gestures require a manager endorsement.
     *
     * @return true when the endorsement ceremony applies
     */
    public boolean gestureEndorsementRequired() { return boolValue("gesture.endorsement-required"); }

    /**
     * The maximal percentage of a line discount.
     *
     * @return the cap, in percent
     */
    public int lineMaxDiscountPercent() { return intValue("discount.line-max-percent"); }

    /**
     * The maximal percentage of a whole-ticket discount.
     *
     * @return the cap, in percent
     */
    public int globalMaxDiscountPercent() { return intValue("discount.global-max-percent"); }

    /**
     * The customer-display message while the register is open and idle.
     *
     * @return the display text
     */
    public String customerOpenMessage() { return value("customer.message-open"); }

    /**
     * The customer-display message while the register is locked.
     *
     * @return the display text
     */
    public String customerClosedMessage() { return value("customer.message-closed"); }

    /**
     * Whether parking a ticket prints the resume receipt.
     *
     * @return true when the parked receipt is printed
     */
    public boolean parkingPrintReceipt() { return boolValue("parking.print-receipt"); }
}
