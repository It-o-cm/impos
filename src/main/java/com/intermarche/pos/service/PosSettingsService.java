package com.intermarche.pos.service;

import com.intermarche.pos.domain.PosSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>
 * Between the node's own overrides and the catalog default sits the ECHELON
 * inheritance (BO-02-05-04): when this node carries a {@code pos.pdv.number},
 * a key with no local override is resolved from the PDV's echelon chain
 * (PDV &rarr; enseigne &rarr; country) through {@link EchelonSettingService}.
 * A local override therefore always wins and survives a fresh pull of the
 * echelon defaults — the two live in different tables. The inherited values
 * are cached alongside the local ones and dropped together, so the echelon
 * lookup never touches the database on a hot path. On a node with no
 * {@code pos.pdv.number} the layer is inert and behaviour is unchanged, which
 * keeps a standalone register selling exactly as before.
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
        new Def("discount.enabled", Type.BOOL, "GESTES DE PRIX",
                "Remises et rabais actifs",
                "Active les gestes remise et rabais en caisse. Désactivé : le caissier ne peut appliquer ni remise ni rabais, ni à la ligne ni au ticket (BO-03-07-01).",
                "true", null),
        new Def("price.show-original-on-force", Type.BOOL, "GESTES DE PRIX",
                "Afficher le prix d'origine au forçage",
                "Le prix initial de l'article est rappelé à l'écran lors d'un forçage de prix. Désactivé : le prix d'origine est masqué (BO-10-07-12).",
                "true", null),
        new Def("customer.message-open", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse ouverte",
                "Texte affiché sur l'écran client en attente de transaction (LC-10-01-20).",
                "Bienvenue", null),
        new Def("customer.message-closed", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse fermée",
                "Texte affiché sur l'écran client quand la caisse est fermée (LC-10-01-21).",
                "Caisse fermée", null),
        new Def("customer.qr-enabled", Type.BOOL, "AFFICHEUR CLIENT",
                "QR code ticket sur l'afficheur client",
                "Le QR code de récupération du ticket dématérialisé s'affiche sur l'écran client en fin de transaction. Désactivé : aucun QR code (BO-10-07-02).",
                "true", null),
        new Def("parking.print-receipt", Type.BOOL, "TICKETS",
                "Imprimer le ticket de mise en attente",
                "Un reçu portant le numéro de reprise sort à chaque mise en attente (LC-04-01-02).",
                "true", null),
        new Def("ticket.email-format", Type.TEXT, "TICKETS",
                "Forme du ticket envoyé par email",
                "Comment le ticket voyage dans l'email : BODY (dans le corps du message, défaut), ATTACHMENT (en pièce jointe) ou BOTH (les deux). Une valeur inconnue retombe sur BODY (LC-08-02-07/08).",
                "BODY", null),
        new Def("ticket.email-editable", Type.BOOL, "TICKETS",
                "Adresse du client fidélisé modifiable",
                "L'adresse email récupérée du référentiel client peut être corrigée en caisse avant l'envoi. Désactivé : elle est envoyée telle quelle et seul un client sans adresse connue en fait saisir une (LC-08-02-10).",
                "true", null),
        new Def("ticket.line-order", Type.TEXT, "TICKETS",
                "Ordre des articles sur le ticket",
                "Ordre d'impression des articles sur le ticket de caisse : ENTRY (ordre d'enregistrement, défaut), LABEL (alphabétique par libellé) ou FAMILY (regroupés sous le libellé de leur famille). Une valeur inconnue retombe sur ENTRY (LC-08-01-07).",
                "ENTRY", null),
        new Def("print.conditional-enabled", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Choix d'impression en fin de transaction",
                "La caisse propose au caissier quels documents imprimer : tous, ticket de caisse, ticket carte bancaire, bon d'achat ou aucun. Désactivé : la caisse imprime comme aujourd'hui, sans écran de choix (LC-08-03-01).",
                "false", null),
        new Def("print.forced-documents", Type.TEXT, "IMPRESSION CONDITIONNELLE",
                "Documents toujours imprimés",
                "Documents imprimés d'office quel que soit le choix du caissier, séparés par des points-virgules : TICKET, CARTE, BON. Vide : le choix du caissier s'applique seul (LC-08-03-07).",
                "", null),
        new Def("print.force-ticket-glc", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket comportant un article sous GLC",
                "Un ticket de caisse comprenant un article soumis à la Garantie Légale de Conformité est imprimé d'office (LC-08-03-09).",
                "true", null),
        new Def("print.force-card-credit", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket carte bancaire de type crédit",
                "Le ticket carte bancaire d'un remboursement (retour article, ticket retour) est imprimé d'office (LC-08-03-10).",
                "true", null),
        new Def("print.force-card-signature", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket carte bancaire à signature",
                "Le ticket carte bancaire demandant la signature du client est imprimé d'office (LC-08-03-11).",
                "true", null),
        new Def("print.force-card-tna", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket carte bancaire TNA",
                "Le ticket carte bancaire d'une transaction non aboutie, portant la mention « abandon débit », est imprimé d'office (LC-08-03-12).",
                "true", null),
        new Def("payment.degraded-mode", Type.BOOL, "MONÉTIQUE",
                "Mode dégradé monétique",
                "Bascule manuelle en cas de coupure vers les serveurs monétique : les paiements carte sont acceptés immédiatement, sans interroger le TPE (BO-03-12-05).",
                "false", null),
        new Def("payment.degraded-forced-endorsement", Type.BOOL, "MONÉTIQUE",
                "Autorisation superviseur pour le mode dégradé forcé",
                "L'activation et la levée du mode dégradé monétique forcé exigent un aval superviseur. Désactivé : l'opérateur décide seul (LC-07-08-03/04).",
                "true", null),
        new Def("payment.degraded-forced-minutes", Type.INT, "MONÉTIQUE",
                "Durée du mode dégradé forcé (minutes)",
                "Au bout de ce délai, le mode dégradé monétique forcé se lève tout seul : l'opérateur qui l'a activé ne sera pas celui qui sera encore là trois heures plus tard (LC-07-08-04).",
                "60", null),
        new Def("ticket.header-message", Type.TEXT, "MESSAGES TICKET",
                "Message en début de ticket",
                "Texte imprimé en tête du ticket, sous l'adresse du magasin. Vide : aucun message (BO-03-08-03).",
                "", null),
        new Def("ticket.footer-message", Type.TEXT, "MESSAGES TICKET",
                "Message en fin de ticket",
                "Texte imprimé en pied du ticket, après la formule de politesse. Vide : aucun message (BO-03-08-03, BO-03-08-05).",
                "", null),
        new Def("drawer.open-on-payment", Type.BOOL, "TIROIR",
                "Ouverture du tiroir au paiement",
                "Le tiroir s'ouvre sur les règlements physiques (espèces, chèque, titres-restaurant). Désactivé : le tiroir reste fermé au paiement (BO-10-02-12).",
                "true", null),
        new Def("drawer.open-on-login", Type.BOOL, "TIROIR",
                "Ouverture du tiroir à la prise de poste",
                "Le tiroir s'ouvre au déverrouillage de la caisse pour installer le fond. Désactivé : le tiroir reste fermé à la connexion (BO-10-02-25).",
                "true", null),
        new Def("backup.manual-endorsement", Type.BOOL, "SECOURS MONETIQUE",
                "Autorisation superviseur pour la validation manuelle",
                "La validation manuelle d'un paiement 'secours monétique' — saisie du montant accepté sans lecture du QR-code de retour — exige un aval superviseur. Désactivé : l'opérateur valide seul (LC-07-07-09).",
                "true", null),
        new Def("backup.method-labels", Type.TEXT, "SECOURS MONETIQUE",
                "Table des moyens de paiement du secours monétique",
                "Correspondance entre l'identifiant renvoyé par le terminal mobile et le libellé enregistré sur le ticket, sous la forme ID=LIBELLE séparés par des virgules. Un identifiant absent de la table est enregistré en 'SECOURS MONETIQUE' (LC-07-07-08).",
                "20=CB EMV,43=AMERICAN EXPRESS,53=CB SANS CONTACT,3F=CARTE TRD CB,1010=TRD SODEXO", null),
        new Def("balance.counter-price", Type.BOOL, "TICKET COMPTOIR",
                "Prix du ticket comptoir : celui du comptoir",
                "Les lignes d'un ticket unique balance sont enregistrées au prix calculé par le comptoir. Désactivé : chaque ligne est revalorisée au prix du référentiel, le poids pesé faisant foi et le comptoir n'étant plus qu'un peseur (LC-06-01-04).",
                "true", null),
        new Def("cash.rounding-step-cents", Type.INT, "ARRONDI ESPECES",
                "Pas d'arrondi du règlement espèces (centimes)",
                "Le montant réglable en espèces est arrondi au multiple le plus proche de ce pas, et l'écart est enregistré sur le moyen de paiement ARRONDI. 0 ou 1 : aucun arrondi (France). 5 : obligation légale belge depuis le 01/12/2019 (LC-07-03-01).",
                "0", null),
        new Def("credit.allowed-in-degraded", Type.BOOL, "CREDIT CLIENT",
                "Crédit client autorisé en mode dégradé",
                "Le règlement en crédit client reste possible quand le référentiel client n'est plus à jour (coupure entre le BackOffice et la caisse). Désactivé : le crédit client est refusé tant que le référentiel n'a pas été rafraîchi, l'encours et le plafond n'étant plus fiables (LC-07-09-05).",
                "false", null),
        new Def("credit.degraded-after-minutes", Type.INT, "CREDIT CLIENT",
                "Ancienneté du référentiel client tolérée (minutes)",
                "Au-delà de ce délai sans tirage réussi du référentiel client, la caisse se considère en mode dégradé pour le crédit client (LC-07-09-05).",
                "60", null),
        new Def("scan.ean13-check-digit", Type.BOOL, "SCAN",
                "Contrôle du checkdigit EAN13",
                "Au scan d'un EAN13, la clé de contrôle est vérifiée et un code invalide est refusé. Désactivé : aucun contrôle de clé (BO-10-02-21).",
                "false", null),
        new Def("dashboard.alerts-enabled", Type.BOOL, "SUPERVISION",
                "Alertes caisses sur le back-office",
                "Les appels superviseur des caisses s'affichent sur le tableau de bord du back-office. Désactivé : aucune alerte n'est remontée à l'écran (BO-10-08-01).",
                "true", null),
        new Def("fidelity.advantages-enabled", Type.BOOL, "FIDÉLITÉ",
                "Avantages fidélité sur le ticket",
                "La section « CAGNOTTE DU JOUR » et les avantages fidélité sont imprimés sur le ticket. Désactivé : aucune section fidélité n'est imprimée (BO-10-03-15).",
                "true", null),
        new Def("fidelity.allow-multiple-scan", Type.BOOL, "FIDÉLITÉ",
                "Scan multiple de carte de fidélité",
                "Plusieurs cartes de fidélité peuvent être scannées pendant la transaction, seule la dernière est retenue. Désactivé : une carte déjà scannée bloque les suivantes (BO-10-03-02).",
                "true", null),
        new Def("cash.movement-endorsement-threshold", Type.TEXT, "MOUVEMENTS DE CAISSE",
                "Seuil d'aval manager (€)",
                "Montant au-delà duquel un mouvement de caisse (prélèvement, apport, dépense, acompte, déclaration) exige un aval manager. Un mouvement à ce montant ou en dessous passe sans aval (BO-04-01-44).",
                "100.00", null),
        new Def("cash.movement-reasons", Type.TEXT, "MOUVEMENTS DE CAISSE",
                "Motifs autorisés",
                "Motifs proposés au caissier pour un mouvement de caisse, séparés par des points-virgules. Vide : la saisie du motif reste libre (BO-04-01-44).",
                "Prélèvement coffre;Apport de fond;Achat de timbres;Dépense pharmacie;Erreur de caisse", null),
        new Def("invoice.customer-fields", Type.TEXT, "FACTURE",
                "Champs du client en compte",
                "Champs demandés à la création d'un client en caisse, séparés par des points-virgules, une étoile marquant les obligatoires : companyName, contactName, street, postalCode, city, siret, vatNumber, phone, email. Vide : tous les champs, raison sociale obligatoire (LC-08-04-10).",
                "", null),
        new Def("invoice.document-types", Type.TEXT, "FACTURE",
                "Types de document actifs",
                "Types de document proposés en caisse après la saisie du ticket, séparés par des points-virgules : FACTURE, BON_LIVRAISON. Un seul type actif : l'étape de choix n'est pas affichée. Vide : facture seule (LC-08-04-04/05).",
                "FACTURE", null),
        new Def("invoice.document-output", Type.TEXT, "FACTURE",
                "Imprimante par type de document",
                "Imprimante de chaque type de document, séparés par des points-virgules, sous la forme TYPE:IMPRIMANTE — imprimantes : TICKET (rouleau de caisse), FACTURETTE (station à insertion), A4 (imprimante réseau). Exemple : FACTURE:A4;BON_LIVRAISON:TICKET. Un type non nommé sort sur le rouleau (LC-08-04-11).",
                "", null),
        new Def("invoice.slip-lines", Type.INT, "FACTURE",
                "Lignes par facturette",
                "Nombre de lignes imprimées sur une facturette avant de demander la feuille suivante. En dessous de 1, la facturette est imprimée d'un seul tenant (LC-08-04-12/13).",
                "30", null),
        new Def("invoice.auto-print", Type.TEXT, "FACTURE",
                "Document imprimé automatiquement par moyen de règlement",
                "Document émis automatiquement en fin de transaction selon le règlement, séparés par des points-virgules, sous la forme REGLEMENT:TYPE — exemple CREDIT:FACTURE. Le document n'est émis que si le règlement désigne un client en compte. Vide : aucune émission automatique (LC-08-04-16).",
                "", null),
        new Def("drawer.withdrawal-methods", Type.TEXT, "PRÉLÈVEMENT ET TRANSFERT",
                "Moyens de règlement prélevables manuellement",
                "Moyens de règlement proposés au prélèvement manuel, séparés par des points-virgules, sous la forme CLÉ:LIBELLÉ — exemple CASH:Espèces;CHEQUE:Chèques. Un moyen prélevé automatiquement n'y figure pas et n'est pas proposé en caisse (LC-12-03-02/03).",
                "CASH:Espèces;CHEQUE:Chèques;TR:Titres-restaurant;VOUCHER:Bons", null),
        new Def("drawer.withdrawal-print", Type.BOOL, "PRÉLÈVEMENT ET TRANSFERT",
                "Impression du ticket de prélèvement",
                "Un ticket de prélèvement est imprimé après validation, détaillant les dénominations pour les espèces et les transactions pour les autres moyens (LC-12-03-07).",
                "true", null),
        new Def("drawer.transfer-methods", Type.TEXT, "PRÉLÈVEMENT ET TRANSFERT",
                "Moyens de règlement transférables",
                "Moyens de règlement proposés en source et en destination d'un transfert de règlement, même syntaxe que le prélèvement. Vide : la liste du prélèvement s'applique (LC-12-10-02).",
                "", null),
        new Def("drawer.transfer-print", Type.BOOL, "PRÉLÈVEMENT ET TRANSFERT",
                "Impression du ticket de transfert",
                "Un ticket « Transfert règlement » est imprimé après validation, indiquant les moyens source et destination et le montant transféré (LC-12-10-05).",
                "true", null),
        new Def("abandon.reasons", Type.TEXT, "ABANDON DE TICKET",
                "Motifs d'abandon",
                "Motifs proposés au caissier pour abandonner un ticket, séparés par des points-virgules. Le motif choisi est journalisé et imprimé sur le ticket d'abandon. Vide : la saisie du motif n'est pas demandée (LC-04-04-10).",
                "Erreur de saisie;Client parti sans payer;Article indisponible;Problème de règlement;Ticket de test", null),
        new Def("abandon.partial-payment", Type.TEXT, "ABANDON DE TICKET",
                "Abandon avec règlement partiel",
                "Ce que fait la caisse quand un règlement partiel est déjà enregistré : CONFIRM (l'écran annonce les règlements qui seront annulés et l'opérateur valide) ou BLOCK (l'abandon est refusé, l'opérateur doit annuler le règlement d'abord). Vide ou inconnu : CONFIRM (LC-04-04-06/07/08/09).",
                "CONFIRM", null),
        new Def("abandon.print", Type.TEXT, "ABANDON DE TICKET",
                "Impression du ticket d'abandon",
                "Quand le ticket d'abandon est imprimé : NEVER (jamais), ALWAYS (systématiquement) ou ON_DEMAND (l'opérateur décide sur l'écran d'abandon). Vide ou inconnu : NEVER (LC-04-04-12).",
                "NEVER", null),
        new Def("abandon.print-detail", Type.BOOL, "ABANDON DE TICKET",
                "Détail des articles sur le ticket d'abandon",
                "Le ticket d'abandon liste les articles du ticket abandonné. Désactivé : seuls l'entête, le motif et le total abandonné sont imprimés (LC-04-04-12).",
                "true", null),
        new Def("tender.restricted", Type.TEXT, "RÈGLEMENTS RESTREINTS",
                "Règlements restreints affichés en caisse",
                "Moyens de paiement restreints dont l'assiette éligible est affichée pendant la vente, séparés par des points-virgules, sous la forme CODE:ATTRIBUT:LIBELLÉ — attributs disponibles : MEAL_VOUCHER_ELIGIBLE, ECO_VOUCHER_ELIGIBLE, SOCIAL_CARD_ELIGIBLE. Vide : titre-restaurant, éco-chèque et carte achat sociale (LC-09-01-11 à -18).",
                "", null),
        new Def("gs1.expiry-alert", Type.TEXT, "CODES GS1",
                "Alerte date de péremption article",
                "Ce que fait la caisse quand un code GS1 porte une date de péremption (AI 17) proche, atteinte ou dépassée : NONE (rien), INFO (message au caissier, l'article est enregistré) ou BLOCK (article refusé). Vide ou inconnu : NONE (LC-11-03-13).",
                "NONE", null),
        new Def("gs1.expiry-warn-days", Type.INT, "CODES GS1",
                "Nombre de jours « date proche »",
                "Nombre de jours avant la date de péremption à partir duquel elle est considérée comme proche. Zéro : seule une date atteinte ou dépassée déclenche l'alerte (LC-11-03-13).",
                "0", null),
        new Def("gs1.coupon-expiry-alert", Type.TEXT, "CODES GS1",
                "Alerte date d'expiration coupon",
                "Ce que fait la caisse quand un coupon GS1 porte une date d'expiration (AI 17) atteinte ou dépassée : NONE, INFO ou BLOCK. Vide ou inconnu : NONE (LC-11-03-13).",
                "BLOCK", null),
        new Def("gs1.gdti-coupon-types", Type.TEXT, "CODES GS1",
                "Chèques cadeaux GS1 par émetteur",
                "Type de règlement associé à un chèque cadeau GS1 (AI 253) selon son code émetteur, séparés par des points-virgules, sous la forme PREFIXE:CODE_TYPE — exemple 9526000:CADEAU. Le préfixe le plus long l'emporte. Vide : aucun chèque cadeau GS1 accepté (LC-11-03-05).",
                "", null),
        new Def("gs1.gcn-coupon-type", Type.TEXT, "CODES GS1",
                "Type de règlement des coupons GS1",
                "Code du type de règlement associé à un coupon GS1 (AI 255). Vide : aucun coupon GS1 accepté en règlement (LC-11-03-06).",
                "", null),
        new Def("touch.groups-per-page", Type.INT, "TOUCHES CAISSE",
                "Nombre de touches groupe par page",
                "Nombre de touches de groupe d'articles affichees par page sur l'ecran de saisie directe. En dessous de 1 la valeur par defaut s'applique (BO-03-01-06).",
                "12", null),
        new Def("touch.display-order", Type.TEXT, "TOUCHES CAISSE",
                "Ordre des touches groupe",
                "Ordre d'affichage des touches groupe sur l'ecran de saisie directe : ALPHA (alphabetique, defaut), CUSTOM (ordre personnalise de la fiche groupe) ou VOLUME (volume de vente decroissant). Une valeur inconnue retombe sur ALPHA (BO-03-01-10/11/13).",
                "ALPHA", null));

    /**
     * The echelon inheritance engine, or null on a node where none is wired
     * (a plain register constructed without CDI in a unit test): the layer is
     * then skipped entirely.
     */
    @Inject
    EchelonSettingService echelonSettings;

    /**
     * This node's own point-of-vente number, absent on a standalone register
     * or the store node before it is commissioned into the organisation tree.
     * Absent means no echelon layer applies.
     */
    @Inject
    @ConfigProperty(name = "pos.pdv.number")
    Optional<String> nodePdvNumber;

    /** The cached local override rows, or null when a reload is due. */
    private volatile Map<String, String> cache = null;

    /** The cached echelon-inherited values for this node, or null when due. */
    private volatile Map<String, String> inheritedCache = null;

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
        String inherited = inherited().get(key);
        if (inherited != null) return inherited;
        if (d.configFallback() != null) {
            Optional<String> fromConfig = ConfigProvider.getConfig()
                    .getOptionalValue(d.configFallback(), String.class);
            if (fromConfig.isPresent()) return fromConfig.get();
        }
        return d.defaultValue();
    }

    /**
     * Returns the echelon-inherited values of this node, resolved once and
     * cached until the next invalidation.
     *
     * @return the inherited key-to-value map, never null
     */
    private Map<String, String> inherited() {
        Map<String, String> values = inheritedCache;
        if (values == null) {
            values = loadInherited();
            inheritedCache = values;
        }
        return values;
    }

    /**
     * Resolves this node's echelon-inherited values, or an empty map when no
     * echelon layer applies (no engine wired, or no PDV number on this node).
     *
     * @return the inherited key-to-value map, never null
     */
    private Map<String, String> loadInherited() {
        if (echelonSettings == null || nodePdvNumber == null || nodePdvNumber.isEmpty()) {
            return Map.of();
        }
        try {
            return echelonSettings.resolveForPdv(nodePdvNumber.get());
        } catch (Exception e) {
            // A failed resolution (boot ordering, missing table) falls back to
            // the local overrides and defaults; the next access retries.
            LOG.warnf("Resolution des echelons impossible (%s): valeurs locales", e.getMessage());
            inheritedCache = null;
            return Map.of();
        }
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

    /**
     * Returns the ADMINISTERED parameters with their EFFECTIVE value on this
     * node — the keys that carry a local override OR an echelon-inherited value,
     * each resolved through the precedence (local override &gt; echelon), ordered
     * by key. This is what the store node PUBLISHES down the SETTINGS pull (route
     * A, BO-02-05-04): the register receives the resolved result, never the
     * echelon chain, and a key that only has its catalog default is absent — the
     * register recomputes the default itself.
     * <p>
     * The echelon layer is resolved FRESH here (not from the long-lived cache)
     * so a value whose effect date has just come is picked up on the next
     * fingerprint recomputation without any invalidation — this is what makes a
     * scheduled echelon change apply on its own (BO-03-12-03/04). On a node with
     * no echelon engine or no PDV number the map is just the local overrides.
     *
     * @return the administered key-to-effective-value map, ordered by key
     */
    public Map<String, String> administeredValues() {
        Map<String, String> local = cache;
        if (local == null) {
            local = loadAll();
            cache = local;
        }
        Map<String, String> echelon;
        if (echelonSettings == null || nodePdvNumber == null || nodePdvNumber.isEmpty()) {
            echelon = Map.of();
        } else {
            echelon = echelonSettings.resolveForPdv(nodePdvNumber.get());
        }
        java.util.TreeMap<String, String> result = new java.util.TreeMap<>(echelon);
        result.putAll(local);
        return result;
    }

    /**
     * Drops both caches — called after every admin save and pull apply. The
     * echelon cache is dropped too so a re-pulled enseigne default is picked
     * up on the next read.
     */
    public void invalidate() {
        cache = null;
        inheritedCache = null;
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
     * Returns a decimal (monetary) parameter, guarding against a corrupt row.
     *
     * @param key the catalog key
     * @return the effective decimal value, or the catalog default when corrupt
     */
    private BigDecimal bigDecimalValue(String key) {
        try {
            return new BigDecimal(value(key).trim());
        } catch (Exception e) {
            return new BigDecimal(def(key).defaultValue());
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

    /**
     * Whether the manual monetique degraded mode is active (BO-03-12-05):
     * card payments bypass the configured terminal and are accepted
     * immediately.
     *
     * @return true when degraded mode is on
     */
    public boolean paymentDegradedMode() { return boolValue("payment.degraded-mode"); }

    /**
     * Whether the line and ticket discount/rebate gestures are offered at the
     * register (BO-03-07-01): disabled, the cashier can apply none of them.
     *
     * @return true when discounts and rebates are active
     */
    public boolean discountEnabled() { return boolValue("discount.enabled"); }

    /**
     * Whether a forced price recalls the article's original price on screen
     * (BO-10-07-12): disabled, the original price is masked.
     *
     * @return true when the original price is shown on a price forcing
     */
    public boolean priceShowOriginalOnForce() { return boolValue("price.show-original-on-force"); }

    /**
     * The message printed at the TOP of the sale ticket, under the store
     * address (BO-03-08-03); empty when no header message is administered.
     *
     * @return the ticket header message, possibly empty
     */
    public String ticketHeaderMessage() { return value("ticket.header-message"); }

    /**
     * The message printed at the BOTTOM of the sale ticket, after the closing
     * courtesy line (BO-03-08-03, BO-03-08-05); empty when none is administered.
     *
     * @return the ticket footer message, possibly empty
     */
    public String ticketFooterMessage() { return value("ticket.footer-message"); }

    /**
     * Whether the physical-tender payments open the cash drawer (BO-10-02-12):
     * disabled, the drawer stays shut on cash, cheque and meal-voucher tenders.
     *
     * @return true when a physical payment opens the drawer
     */
    public boolean drawerOpenOnPayment() { return boolValue("drawer.open-on-payment"); }

    /**
     * Whether customer credit stays available while the client referential is
     * stale ({@code LC-07-09-05}).
     *
     * @return true when the shop accepts the risk of an out-of-date balance
     */
    /**
     * The legal cash-rounding step in cents ({@code LC-07-03-01}); zero or one
     * means the shop does not round.
     *
     * @return the rounding step in cents
     */
    public int cashRoundingStepCents() { return intValue("cash.rounding-step-cents"); }

    /**
     * Whether a counter-ticket line keeps the price the scale computed, rather than
     * being re-priced from the catalog ({@code LC-06-01-04}).
     *
     * @return true when the counter's own price is booked
     */
    public boolean balanceCounterPrice() { return boolValue("balance.counter-price"); }

    /**
     * Whether a manual backup-monetics validation needs a supervisor
     * ({@code LC-07-07-09}).
     *
     * @return true when the shop requires an endorsement
     */
    public boolean backupManualEndorsement() { return boolValue("backup.manual-endorsement"); }

    /**
     * Whether forcing or releasing the monetics degraded mode needs a supervisor
     * ({@code LC-07-08-03/04}).
     *
     * @return true when the shop requires an endorsement
     */
    public boolean moneticsDegradedForcedEndorsement() {
        return boolValue("payment.degraded-forced-endorsement");
    }

    /**
     * How long a forced monetics degraded mode lasts before releasing itself
     * ({@code LC-07-08-04}).
     *
     * @return the forcing duration in minutes
     */
    public int moneticsDegradedForcedMinutes() {
        return intValue("payment.degraded-forced-minutes");
    }

    /**
     * The administered scheme table of the backup monetics ({@code LC-07-07-08}).
     *
     * @return the {@code ID=LABEL} pairs, comma separated
     */
    public String backupMethodLabels() { return value("backup.method-labels"); }

    public boolean creditAllowedInDegraded() { return boolValue("credit.allowed-in-degraded"); }

    /**
     * How long the client referential may go without a successful pull before
     * customer credit is considered degraded ({@code LC-07-09-05}).
     *
     * @return the tolerated staleness in minutes
     */
    public int creditDegradedAfterMinutes() { return intValue("credit.degraded-after-minutes"); }

    /**
     * Whether taking the post (unlock) opens the cash drawer to install the
     * float (BO-10-02-25): disabled, the drawer stays shut at login.
     *
     * @return true when the unlock pulse opens the drawer
     */
    public boolean drawerOpenOnLogin() { return boolValue("drawer.open-on-login"); }

    /**
     * Whether a scanned EAN13 has its check digit validated before lookup
     * (BO-10-02-21): disabled, no key control is performed.
     *
     * @return true when the EAN13 check digit is enforced
     */
    public boolean ean13CheckDigitEnabled() { return boolValue("scan.ean13-check-digit"); }

    /**
     * Whether the register alerts (supervisor calls) are shown on the back
     * office dashboard (BO-10-08-01): disabled, no alert reaches the screen.
     *
     * @return true when the dashboard shows the alerts
     */
    public boolean dashboardAlertsEnabled() { return boolValue("dashboard.alerts-enabled"); }

    /**
     * Whether the loyalty advantage section is printed on the sale ticket
     * (BO-10-03-15): disabled, no fidelity section is printed.
     *
     * @return true when the advantage section is printed
     */
    public boolean fidelityAdvantagesEnabled() { return boolValue("fidelity.advantages-enabled"); }

    /**
     * Whether several loyalty cards may be scanned during one transaction
     * (BO-10-03-02): enabled, the last card wins; disabled, a card already
     * attached blocks any further scan.
     *
     * @return true when multiple card scans are allowed
     */
    public boolean fidelityAllowMultipleScan() { return boolValue("fidelity.allow-multiple-scan"); }

    /**
     * Whether the digital-receipt QR code is shown on the customer display at
     * the end of a transaction (BO-10-07-02): disabled, no QR code is shown.
     *
     * @return true when the customer-display QR code is shown
     */
    public boolean customerQrEnabled() { return boolValue("customer.qr-enabled"); }

    /**
     * The amount above which a cash movement (withdrawal, deposit, expense,
     * customer down-payment, cash-count declaration) requires a manager
     * endorsement (BO-04-01-44). Administered through the SETTINGS domain, so a
     * store node can raise or lower it without redeploying a register.
     *
     * @return the endorsement threshold, in euros
     */
    public BigDecimal cashMovementEndorsementThreshold() {
        return bigDecimalValue("cash.movement-endorsement-threshold");
    }

    /**
     * The number of group touches shown per page on the SAISIE DIRECTE grid
     * (BO-03-01-06): a value below one is treated as unset and the catalog
     * default applies, so the register never renders a zero-tile page.
     *
     * @return the strictly positive page size
     */
    public int touchGroupsPerPage() {
        int configured = intValue("touch.groups-per-page");
        return configured < 1 ? Integer.parseInt(def("touch.groups-per-page").defaultValue()) : configured;
    }

    /**
     * The group-touch display order (BO-03-01-10/11/13): the administered value
     * normalized to one of {@code ALPHA}, {@code CUSTOM} or {@code VOLUME}, any
     * other value (including an unexpected one) falling back to {@code ALPHA}.
     *
     * @return the normalized order mode, never null
     */
    public String touchDisplayOrder() {
        String raw = value("touch.display-order").trim().toUpperCase();
        if (raw.equals("CUSTOM") || raw.equals("VOLUME")) {
            return raw;
        }
        return "ALPHA";
    }

    /**
     * The reasons offered to the cashier for a cash movement (BO-04-01-44),
     * parsed from the semicolon-separated administered list; blank entries are
     * dropped, and an empty or blank setting yields an empty list (free reason
     * entry). Never null.
     *
     * @return the authorized movement reasons, in catalog order
     */
    public List<String> cashMovementReasons() {
        // value() never returns null for a catalog key: an absent row falls to
        // the catalog default, which is a non-null string.
        String raw = value("cash.movement-reasons");
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                reasons.add(trimmed);
            }
        }
        return reasons;
    }

    /**
     * Whether the register offers the end-of-transaction printing choice
     * (LC-08-03-01): enabled, the cashier picks which documents are printed
     * and the forced rules below apply on top of that choice; disabled, the
     * register prints exactly as it did before the option existed.
     *
     * @return true when conditional printing is active
     */
    public boolean printConditionalEnabled() { return boolValue("print.conditional-enabled"); }

    /**
     * The documents printed WHATEVER the cashier chooses (LC-08-03-07),
     * parsed from the semicolon-separated administered list and normalized to
     * upper case; blank entries are dropped and a blank setting yields an empty
     * list (the cashier's choice then applies alone). Never null.
     *
     * @return the forced document keys, in administered order
     */
    public List<String> printForcedDocuments() {
        // value() never returns null for a catalog key: an absent row falls to
        // the catalog default, which is a non-null string.
        String raw = value("print.forced-documents");
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> documents = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                documents.add(trimmed);
            }
        }
        return documents;
    }

    /**
     * Whether a sale ticket carrying an article under the legal conformity
     * guarantee is printed whatever the cashier chose (LC-08-03-09).
     *
     * @return true when the GLC ticket is forced
     */
    public boolean printForceTicketGlc() { return boolValue("print.force-ticket-glc"); }

    /**
     * Whether the card receipt of a refund — a "credit" card transaction —
     * is printed whatever the cashier chose (LC-08-03-10).
     *
     * @return true when the credit card receipt is forced
     */
    public boolean printForceCardCredit() { return boolValue("print.force-card-credit"); }

    /**
     * Whether a card receipt asking for the customer's signature is printed
     * whatever the cashier chose (LC-08-03-11).
     *
     * @return true when the signature card receipt is forced
     */
    public boolean printForceCardSignature() { return boolValue("print.force-card-signature"); }

    /**
     * Whether the card receipt of a not-completed transaction (TNA), carrying
     * the "abandon débit" mention, is printed (LC-08-03-12).
     *
     * @return true when the TNA card receipt is forced
     */
    public boolean printForceCardTna() { return boolValue("print.force-card-tna"); }

    /**
     * The administered customer-creation mask of the invoice screen
     * (LC-08-04-10), as the raw semicolon list; the invoice screen turns it into
     * typed fields.
     *
     * @return the administered list, never null
     */
    public String invoiceCustomerFields() { return value("invoice.customer-fields"); }

    /**
     * The document kinds the back office activated for the registers
     * (LC-08-04-04), as the raw semicolon list of enum names; the invoice screen
     * turns it into the list of kinds it offers.
     *
     * @return the administered list, never null
     */
    public String invoiceDocumentTypes() { return value("invoice.document-types"); }

    /**
     * The printer each kind of document comes out of (LC-08-04-11), as the raw
     * semicolon list of {@code TYPE:IMPRIMANTE} pairs; the invoice flow turns it
     * into the target of the kind being issued.
     *
     * @return the administered list, never null
     */
    public String invoiceDocumentOutput() { return value("invoice.document-output"); }

    /**
     * How many lines are printed on one slip before the operator is asked for the
     * next sheet (LC-08-04-12/13). At or below zero the slip is printed whole.
     *
     * @return the administered line count
     */
    public int invoiceSlipLines() { return intValue("invoice.slip-lines"); }

    /**
     * The document emitted automatically at the end of a sale, per payment method
     * (LC-08-04-16), as the raw semicolon list of {@code REGLEMENT:TYPE} pairs.
     *
     * @return the administered list, never null
     */
    public String invoiceAutoPrint() { return value("invoice.auto-print"); }

    /**
     * What the register does with an expiry date carried by a GS1 code on an
     * article (LC-11-03-13).
     *
     * @return the administered level, never null
     */
    public String gs1ExpiryAlert() { return value("gs1.expiry-alert"); }

    /**
     * The restricted tenders whose eligible base is shown during the sale
     * (LC-09-01-11 to -18), as the raw semicolon list of
     * {@code CODE:ATTRIBUT:LIBELLÉ} triples.
     *
     * @return the administered list, never null
     */
    public String restrictedTenders() { return value("tender.restricted"); }

    /**
     * The abandon reasons offered to the cashier (LC-04-04-10), as the raw
     * semicolon list. Blank means the reason is not asked for.
     *
     * @return the administered list, never null
     */
    public String abandonReasons() { return value("abandon.reasons"); }

    /**
     * The tenders offered for a manual withdrawal (LC-12-03-02/03), as the raw
     * semicolon list of {@code CLÉ:LIBELLÉ} pairs.
     *
     * @return the administered list, never null
     */
    public String drawerWithdrawalMethods() { return value("drawer.withdrawal-methods"); }

    /**
     * Whether a withdrawal ticket is printed (LC-12-03-07).
     *
     * @return true when the ticket comes out
     */
    public boolean drawerWithdrawalPrint() { return boolValue("drawer.withdrawal-print"); }

    /**
     * The tenders offered as the source and the destination of a settlement
     * transfer (LC-12-10-02); blank means the withdrawal list applies.
     *
     * @return the administered list, never null
     */
    public String drawerTransferMethods() { return value("drawer.transfer-methods"); }

    /**
     * Whether a transfer ticket is printed (LC-12-10-05).
     *
     * @return true when the ticket comes out
     */
    public boolean drawerTransferPrint() { return boolValue("drawer.transfer-print"); }

    /**
     * What the register does when a partial settlement is already registered and
     * the ticket is abandoned (LC-04-04-06 to -09): {@code CONFIRM} or
     * {@code BLOCK}.
     *
     * @return the administered behaviour, never null
     */
    public String abandonPartialPayment() { return value("abandon.partial-payment"); }

    /**
     * When the abandon ticket is printed (LC-04-04-12): {@code NEVER},
     * {@code ALWAYS} or {@code ON_DEMAND}.
     *
     * @return the administered rule, never null
     */
    public String abandonPrint() { return value("abandon.print"); }

    /**
     * Whether the abandon ticket lists the articles of the abandoned sale
     * (LC-04-04-12).
     *
     * @return true when the article detail is printed
     */
    public boolean abandonPrintDetail() { return boolValue("abandon.print-detail"); }

    /**
     * How many days before an expiry date it counts as near (LC-11-03-13).
     *
     * @return the administered number of days
     */
    public int gs1ExpiryWarnDays() { return intValue("gs1.expiry-warn-days"); }

    /**
     * What the register does with an expiry date carried by a GS1 coupon
     * (LC-11-03-13).
     *
     * @return the administered level, never null
     */
    public String gs1CouponExpiryAlert() { return value("gs1.coupon-expiry-alert"); }

    /**
     * The settlement type of a GS1 gift document, by issuer prefix (LC-11-03-05),
     * as the raw semicolon list of {@code PREFIXE:CODE_TYPE} pairs.
     *
     * @return the administered list, never null
     */
    public String gs1GiftCouponTypes() { return value("gs1.gdti-coupon-types"); }

    /**
     * The settlement type of a GS1 coupon (LC-11-03-06).
     *
     * @return the administered coupon-type code, never null
     */
    public String gs1CouponType() { return value("gs1.gcn-coupon-type"); }

    /**
     * The order the articles are printed in on the sale ticket (LC-08-01-07), as
     * administered. The printing rule normalizes it — an unknown value falls back
     * to the entry order there — so this stays a plain read.
     *
     * @return the administered order, never null
     */
    public String ticketLineOrder() { return value("ticket.line-order"); }

    /**
     * How the ticket travels in the e-mail (LC-08-02-07/08), as administered. The
     * mail service normalizes it — an unknown value falls back to the message body
     * there — so this stays a plain read.
     *
     * @return the administered form, never null
     */
    public String ticketEmailFormat() { return value("ticket.email-format"); }

    /**
     * Whether the address read from the customer referential may be corrected at the
     * register before the receipt is sent (LC-08-02-10): disabled, a known address is
     * sent as it stands.
     *
     * @return true when the retrieved address is editable
     */
    public boolean ticketEmailEditable() { return boolValue("ticket.email-editable"); }
}
