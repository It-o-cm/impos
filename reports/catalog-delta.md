# Delta du catalogue e2e — surface ajoutée depuis le gel

**Objet.** Comparaison de `e2e-scenarios.md` (62 scénarios + inventaire O) à la surface
réelle du code au 2026-08-22. Relevé PAR LE CODE des écrans, actions, endpoints et
messages qui EXISTENT mais qu'aucun scénario ne couvre — les fonctionnalités ajoutées
après le gel du catalogue. Périmètre annoncé : intégration fidélité (imfid), « mot de
passe oublié », onglet imports.

**Contrat de ce document.** Les scénarios ci-dessous sont rédigés dans le style exact du
catalogue (parcours → attendus vérifiables, textes littéraux, tags d'infrastructure) et
présentés **en AJOUT** : aucune renumérotation des ids existants (ils sont référencés par
les tests). On étend les groupes (`D4…`, `A8…`, extensions `O`) ou on crée des lettres
neuves (`P`). **Aucun test n'est modifié ; `e2e-scenarios.md` n'est pas touché** — ce
fichier est le delta soumis à arbitrage avant fusion.

---

## Synthèse d'arbitrage — les trois périmètres annoncés

| Périmètre annoncé | Verdict du code | Ce qu'on ajoute |
|---|---|---|
| **Intégration fidélité (imfid)** | ✅ Réelle et large. Le groupe D actuel (D1–D3) ne couvre QUE l'attache locale de carte. Toute la couche imfid (consultation, projection cagnotte/earn, tender CAGNOTTE, bail lease/burn, outbox fiscal, remboursement en cagnotte, mode dégradé) est postérieure au gel et non couverte. | **D4–D11** + nouveau tag d'infra **[FID]** + lignes d'inventaire O. |
| **« Mot de passe oublié »** | ⚠️ **N'existe pas** dans le code. Aucun flux reset/récupération/PIN provisoire nulle part (grep exhaustif). Seul vestige : un commentaire Javadoc périmé et faux `Employee.java:325` (« password reset fields ») sans champ correspondant — **red herring à signaler**. En revanche la surface d'auth réellement ajoutée et non couverte est le **login par IDENTIFIANT + mot de passe** (mode manuel deux étapes) et l'écran d'attente **CAISSE FERMÉE**. | **A8–A9** + 2 lignes O-B. Voir la note « Mot de passe oublié » ci-dessous. |
| **Onglet imports** | ⚠️ **Pas d'onglet UI.** Aucun écran HTML, aucun `<input type=file>`, aucun lien dans le menu. La fonctionnalité est **REST pure** : 4 endpoints CSV `@RolesAllowed("ADMIN")`. Le groupe L couvre la consolidation store (push/pull), pas ces imports opérateur. | **Nouveau groupe P** (surface HTTP admin), avec la mention explicite « pas d'écran ». |

### Note « Mot de passe oublié » (pour arbitrage)
Le code ne contient **aucun** flux de mot de passe oublié / réinitialisation / code
provisoire / question de sécurité / déverrouillage par un responsable. La récupération
après lockout est **purement temporelle** (`lockedUntil` expire), déjà couverte par A3.
Un PIN ne peut changer que par A6 (PIN actuel requis) ou hors-bande via le pull
référentiel (back-office store, hors POS). Le seul artefact évoquant un reset est le
commentaire mensonger `Employee.java:325` — à traiter comme dette documentaire, pas comme
une fonctionnalité à tester. On propose donc d'interpréter le périmètre « mot de passe »
comme le **chemin de login par identifiant + mot de passe** (réellement présent,
réellement non couvert) et de documenter l'absence du reset.

### Nouveau tag d'infrastructure proposé
Le catalogue définit **[S]** (caisse seule + simulateur), **[V]** (+ valorisateur :8090)
et **[N]** (+ nœud store). L'intégration fidélité introduit une quatrième dépendance :
le service **imfid**. On propose :

> **[FID]** = caisse + service fidélité **imfid** (seedé), joignable sur `pos.fid.url`.
> Variante **[FID↯]** = imfid volontairement coupé/absent pour les scénarios de mode
> dégradé et de disjoncteur. Sans `pos.fid.url`, earn/burn/outbox sont silencieusement
> désactivés (log `Service fidélité imfid NON CONFIGURÉ`).

Config : `pos.fid.url`, `pos.fid.user`, `pos.fid.password` (Basic auth) ;
reconnaissance carte `scan.pattern.fidelity` (les ITs démo injectent `^299\d{10}$`).
imfid est appelé en `java.net.http` brut (pas de rest-client) : connect 1500 ms,
requête 2500 ms ; « un affichage fidélité ne doit jamais ralentir une vente ».

---

## D. Fidélité — extension imfid (D4–D11)

> D1–D3 (attache locale, saisie manuelle, inertie sur lock) restent inchangés. Ci-dessous
> la couche d'intégration imfid. Textes littéraux vérifiés dans `FidelityService`,
> `fidelity.html`, `pay.html`, `TicketPrinterService`, `RefundService`.

- **D4 Consultation en caisse** [FID] : carte attachée → ouvrir `/fidelity` → bloc de
  consultation imfid affiché : `STATUT` (une des trois traductions `ACTIF` /
  `EN ATTENTE D'ACTIVATION` / `RÉSILIÉ`), `SOLDE`, `DISPONIBLE`, puis la table des
  mouvements (`DATE` / `OPÉRATION` / `MONTANT`) avec les sept libellés d'opération
  traduits (`CAGNOTTE` [+ `(ruleCode)` si présent], `UTILISATION EN CAISSE`,
  `REPRISE SUR RETOUR`, `REMBOURSEMENT EN CAGNOTTE`, `AJUSTEMENT`, `PÉREMPTION`,
  `ANNULATION D'ACTIVATION`), montants signés, date de repli (fiscalDate → createdAt).
  Sans carte attachée → `AUCUNE CARTE ATTACHÉE — SCANNEZ OU SAISISSEZ LA CARTE` ;
  carte 404 → `CARTE FIDÉLITÉ INCONNUE`.

- **D5 Modes de saisie manuelle** [S] : sur `/fidelity`, les trois bascules
  `N° CARTE` / `TÉLÉPHONE` / `NOM / PRÉNOM` changent le clavier (numpad ↔ alpha) ;
  `VALIDER` attache, `PASSER` renvoie sans attacher. Titre `PROGRAMME DE FIDÉLITÉ`.
  (Purement local — aucun appel imfid requis pour la bascule elle-même.)

- **D6 Projection de cagnotte (earn)** [FID] : attacher une carte en cours de panier →
  **revalorisation immédiate** pour que le couple voie la carte (pré-vol earn) ; le
  couple `/valuation` verbatim est capté et posté à `/api/earn`. À la clôture, le
  ticket imprimé porte la section `CAGNOTTE DU JOUR` (`+… E`) et une ligne par règle
  (label + montant). Un **DUPLICATA ne réimprime PAS** la section earn (croiser G3).
  Note technique : le poll `/ticket-fragment` calcule `fidelityEarn`
  (`AVANTAGE CARTE %.2f €`) mais le JS de `main.html` ne le rend pas — sortie morte à
  signaler, pas à tester à l'écran.

- **D7 Paiement CAGNOTTE (tender)** [FID] : sur l'écran pay, bouton `CAGNOTTE` **désactivé
  sans carte active**, activé avec ; champ `Montant à utiliser :` + `UTILISER` → réserve
  un bail plafonné à `min(demandé, restant dû, burnableBase, availableBalance)` (montant
  0/vide ⇒ maximum). Succès → paiement `FIDELITY` enregistré, **tiroir NON ouvert**,
  afficheur pôle `FIDELITE … E`, complétion possible. Branches de refus (zone message
  ticket), textes exacts :
  `CARTE FIDÉLITÉ REQUISE` (pas de carte) · `SERVICE FIDÉLITÉ INDISPONIBLE`
  (non configuré / disjoncteur ouvert) · `CARTE FIDÉLITÉ INCONNUE` (404) ·
  `CAGNOTTE: AUCUN MONTANT UTILISABLE` (plafond nul) ·
  `CAGNOTTE RÉSERVÉE SUR UNE AUTRE CAISSE` (409) ·
  `SOLDE CAGNOTTE INSUFFISANT` / `CAGNOTTE DÉJÀ UTILISÉE AUJOURD'HUI` /
  `COMPTE FIDÉLITÉ INACTIF` / `PAIEMENT FIDÉLITÉ REFUSÉ` (422, selon `reason`).

- **D8 Cycle de bail (lease)** [FID] : réserve à la demande CAGNOTTE →
  renouvellement à mi-vie sur rafraîchissement de l'écran pay (`maybeRenewLease`) →
  **confirmation au moment fiscal, le 410 étant toléré** (« l'ingestion tranchera » ;
  le reservationId voyage quand même dans l'événement ticket-closed) → **release au
  retour panier** (annulation des paiements, échec avalé, la TTL est le filet). Les
  champs `fidReservationId` / `fidLeaseExpiresAt` / `fidLeaseSeconds` de `PaymentState`
  sont posés puis remis à zéro.

- **D9 Outbox d'événements fiscaux** [FID] : à la clôture, un événement `ticket-closed`
  est mis en file (`FidEvent`) avec le couple verbatim + earn affiché + reservationId ;
  le démon `fid-events` draine toutes les **10 s** (`Outbox fidélité active vers … (toutes
  les 10s)`) : `202` ⇒ `sentAt` posé ; tout autre code / panne transport ⇒ ligne laissée
  pending, cycle stoppé (pas de matraquage), rejeu idempotent par référence ticket, ordre
  indifférent. Sans `pos.fid.url` : outbox no-op au boot.

- **D10 Remboursement en cagnotte** [FID] : dans le flux retour, `return-detail.html`
  → bouton `CAGNOTTE` → `GET /return/pay/loyalty` → **modale d'avenant manager** →
  crédit fidélité créé + événement `ticket-return` mis en file (lignes d'origine +
  `refundToCard` optionnel). Garde : ticket d'origine sans carte →
  `AUCUNE CARTE FIDÉLITÉ SUR LE TICKET D'ORIGINE`, rien de persisté. Slip imprimé :
  `REMBOURSEMENT EN CAGNOTTE` / `CREDIT CARTE` / `(visible sur la carte sous quelques
  minutes)` ; afficheur pôle `CREDIT FIDELITE … E`. Coin subtil : le bouton `CAGNOTTE`
  de `return.html` est **inerte** (`href="#"`), seul celui de `return-detail.html` est
  vivant.

- **D11 Mode dégradé & disjoncteur** [FID↯] : imfid coupé en plein panier → la vente
  **n'est jamais bloquée** ; disjoncteur `FidelityService` ouvert **10 s**
  (`RETRY_SECONDS`) après toute panne transport earn/account/burn → aucune nouvelle
  requête dans la fenêtre, projection earn masquée, tender refusé
  `SERVICE FIDÉLITÉ INDISPONIBLE`, consultation `SERVICE FIDÉLITÉ INDISPONIBLE` ;
  `pos.fid.url` absent → consultation `SERVICE FIDÉLITÉ NON CONFIGURÉ` et boot
  `Service fidélité imfid NON CONFIGURÉ`. Reprise automatique au retour d'imfid
  (outbox rejoué).

---

## A. Prise de poste & authentification — extension (A8–A9)

> A1–A7 inchangés. Ces deux scénarios couvrent le chemin de login par identifiant/mot de
> passe (le « mot de passe » réellement présent) et l'écran d'attente. Anchors vérifiés
> dans `lock.html` et `AuthService`.

- **A8 Connexion par IDENTIFIANT (identifiant + mot de passe)** [S] : sur l'overlay
  `AUTHENTIFICATION`, bascule `IDENTIFIANT` (vs `CODE PIN RAPIDE`) → parcours **deux
  étapes** : `Étape 1/2 : Identifiant` (clavier alpha, mis en MAJUSCULES côté client,
  garde `< 2` caractères qui bloque `SUIVANT`) puis `Étape 2/2 : Mot de passe`
  (`Entrez votre code PIN :`, numpad) → `CONNEXION`. Le serveur normalise l'identifiant
  en minuscules (`Employee.findActiveLogin(login.toLowerCase())`) : `jdupont` + PIN
  `1234` → déverrouillage (routage session/vente selon session ouverte, comme A1/A2).
  Identifiants faux → `IDENTIFIANTS INCORRECTS`, et l'écran **force le mode IDENTIFIANT**
  au rechargement (l'overlay se rouvre en z-60). Chemin distinct du badge (A4) et du PIN
  rapide (A1) : le badge, geste matériel numérique, préremplit directement l'étape 2.

- **A9 Écran CAISSE FERMÉE (attente)** [S] : caisse verrouillée au repos → l'écran de lock
  montre le panneau plein `CAISSE FERMÉE` / `Cette caisse est actuellement inutilisable`
  / bouton `S'IDENTIFIER` ; le tap sur `S'IDENTIFIER` révèle l'overlay `AUTHENTIFICATION`
  (mode PIN si un opérateur était déjà connu, sinon IDENTIFIANT). Route `@DrawerMayBeOpen`
  (le lock reste joignable tiroir ouvert). Purement client, asservi à l'état verrouillé.

---

## O. Inventaire — extensions

### O-A bis. Zone message du ticket — fidélité (transitoire) [FID]
Mêmes règles que O-A (s'affiche puis s'efface au scan/action suivant). Déclencheurs
détaillés en D7/D11.

| Déclencheur | Message exact |
|---|---|
| Tender/burn sans carte attachée | `CARTE FIDÉLITÉ REQUISE` |
| imfid non configuré ou disjoncteur ouvert | `SERVICE FIDÉLITÉ INDISPONIBLE` |
| Carte inconnue d'imfid (404) | `CARTE FIDÉLITÉ INCONNUE` |
| Plafond utilisable nul | `CAGNOTTE: AUCUN MONTANT UTILISABLE` |
| Bail déjà pris ailleurs (409) | `CAGNOTTE RÉSERVÉE SUR UNE AUTRE CAISSE` |
| Refus 422 — solde | `SOLDE CAGNOTTE INSUFFISANT` |
| Refus 422 — règle du jour | `CAGNOTTE DÉJÀ UTILISÉE AUJOURD'HUI` |
| Refus 422 — statut compte | `COMPTE FIDÉLITÉ INACTIF` |
| Refus 422 — générique | `PAIEMENT FIDÉLITÉ REFUSÉ` |

### O-B. Messages de page — deux ajouts (changement de PIN)
Le tableau O-B existant liste `Code PIN actuel incorrect`, `Le nouveau code doit comporter
4 chiffres`, `Les nouveaux codes ne correspondent pas`, `Code PIN modifié`. Deux gardes
supplémentaires de `AuthService.changePin` ne sont pas répertoriées :

- **Aucun opérateur connecté** (`POST /action/pin-change` sans opérateur en session) →
  `Aucun opérateur connecté`.
- **Opérateur introuvable** (opérateur en session mais absent en base) →
  `Opérateur introuvable`.

(Effectivement peu atteignables via l'UI — la ressource redirige vers lock quand
`isLocked()` — mais présentes et non testées par GroupOIT. À vérifier via POST direct.)

### O-C bis. Consultation fidélité (`/fidelity`) — états dégradés [FID]

- Aucune carte attachée → `AUCUNE CARTE ATTACHÉE — SCANNEZ OU SAISISSEZ LA CARTE`.
- `pos.fid.url` absent → `SERVICE FIDÉLITÉ NON CONFIGURÉ`.
- Carte inconnue (404) → `CARTE FIDÉLITÉ INCONNUE`.
- imfid injoignable → `SERVICE FIDÉLITÉ INDISPONIBLE`.

---

## P. Imports référentiel CSV [S] — NOUVEAU GROUPE

> **Pas d'onglet UI.** Aucun écran HTML, aucun `<input type=file>`, aucun lien de menu :
> la fonctionnalité est **REST pure**, jouée par HTTP (RestAssured) et non « à travers
> l'écran ». Infra caisse seule ([S]) suffit — pas de valorisateur ni de store node.
> Les 4 endpoints sont `@RolesAllowed("ADMIN")`. Le groupe L couvre la consolidation
> store (push/pull) ; ce groupe couvre l'import opérateur/back-office. Contrat commun
> (`ImporterCsvResource`) : colonnes **séparées par `|`**, **1re ligne = en-tête ignorée**,
> **colonne 0 = clé naturelle**, exécution `@RunOnVirtualThread`, réponse JSON
> `{"createdCount":n, "updatedCount":n, "errors":[…]}` (clé `errors` seulement si non
> vide). Seed d'auth : admin `00000000` / `0000` (rôle ADMIN).

- **P1 Import produits** [S] : `POST /products/import` (`Consumes` text/plain |
  octet-stream, `Produces` JSON), 9 colonnes
  `EAN|Name|Description|Brand|ReferenceWeight|ReferenceVolume|ProductType|UnitName|Active`
  → produits créés/mis à jour, `createdCount`/`updatedCount` justes. `ProductType` parsé
  insensible à la casse (valeur inconnue → null) ; `forbiddenToSale` **jamais effacé** par
  l'import (le CSV ne porte que `active`).

- **P2 Import prix** [S] : `POST /prices/import`, 7 colonnes
  `EAN|PriceExcludingTax|PriceIncludingTax|VatRate|Priority|StartDateTime|EndDateTime`
  (dates ISO `YYYY-MM-DDTHH:MM:SS`). Clé composite EAN+StartDateTime+Priority. Produit
  absent → **erreur de ligne** (`Product with EAN <x> not found.`), les autres lignes
  passent.

- **P3 Import familles (graphe autoritatif)** [S] : `POST /product-families/import`,
  5 colonnes `code|description|flags|product_eans|family_codes` (deux dernières = listes
  séparées par virgule). Le CSV **fait autorité sur le graphe de liens** : un lien omis
  est un lien retiré. L'ordre importe (produits avant familles, enfants avant parents).
  Références manquantes → erreurs de ligne (`Product EAN '<x>' not found.` /
  `SubFamily code '<x>' not found.`) ; auto-référence rejetée
  (`Family '<x>' cannot contain itself.`).

- **P4 Import magasins** [S] : `POST /stores/import`, colonnes
  `Code|Name|StreetLine1|StreetLine2|PostalCode|City|Country|Latitude|Longitude`
  (lat/long optionnelles) → Store + Address embarquée. Hors pull référentiel (une ligne
  statique par nœud).

- **P5 Garde ADMIN** [S] : appel non authentifié / rôle non-ADMIN sur n'importe lequel des
  quatre endpoints → refus conteneur standard **401/403** (pas de corps métier). Aucun
  couplage tiroir/lock (pas de `@DrawerMustBeClosed`) : l'import répond quel que soit
  l'état caisse.

- **P6 Isolation ligne empoisonnée (fallback en cascade)** [S] : un lot contenant une
  ligne fautive → l'algorithme retombe **1000 → 100 → 10 → 1** et isole la ligne : une
  seule entrée d'erreur `Line <n> (<code>): <cause>`, les bonnes lignes du lot sont bien
  committées. Preuve du contrat « une ligne empoisonnée ne coûte que sa propre erreur ».

- **P7 Ré-import idempotent (checksum)** [S] : réimporter le **même** fichier → no-op
  (`updatedCount` ne compte que les changements réels : created=0, updated=0). La
  comparaison de checksum par ligne évite les écritures fantômes.

- **P8 Ligne trop courte** [S] : ligne à moins de colonnes que le minimum → **ignorée en
  douceur** (pas fatale) avec l'entrée `Line <n> ignored (not enough columns): <ligne>` ;
  le reste de l'import se poursuit.

> **Résidu documenté (P) :** les messages d'erreur/réponse des imports sont en **anglais**
> (dont la coquille `Unexcepted error: …`) et non asservis à un écran — ils sont assertés
> sur le corps JSON HTTP, jamais « à l'écran ». Les endpoints store `/api/referential/*`
> (pull, gardé par `pos.role==store` + `X-Sync-Token`) et `/api/sync/*` relèvent du
> périmètre [N]/groupe L, hors de ce groupe P d'imports opérateur.

---

## Récapitulatif du delta

- **Fidélité imfid** : **8 scénarios** (D4–D11) + tag **[FID]/[FID↯]** + inventaire
  O-A bis (9 messages), O-C bis (4 états).
- **Auth** : **2 scénarios** (A8–A9) + O-B (2 messages). *Le « mot de passe oublié »
  n'existe pas — voir la note ; on documente l'absence et le commentaire périmé
  `Employee.java:325`.*
- **Imports** : **nouveau groupe P, 8 scénarios** (P1–P8), REST-only, **pas d'onglet UI**.

**Total proposé : 18 nouveaux scénarios + 15 lignes d'inventaire**, sans aucune
renumérotation des ids existants. Ce document est soumis à arbitrage avant fusion dans
`e2e-scenarios.md`.

### Fichiers lus (relevé par le code, aucun modifié)
`e2e-scenarios.md` ; `lock.html`, `fidelity.html`, `pay.html`, `main.html`,
`return.html`, `return-detail.html` ; `AuthService.java`, `AuthResource.java`,
`PinChangeResource.java`, `Employee.java` ; `FidelityService.java`, `FidelityResource.java`,
`FidelityState.java`, `ImfidClient.java`, `FidEventOutboxService.java`, `FidEvent.java`,
`FidelityPayment.java`, `FidelityScanHandler.java`, `TicketPrinterService.java`,
`RefundService.java`, `PaymentService.java` ; `ImporterCsvResource.java`,
`ProductCsvResource.java`, `PriceCsvResource.java`, `ProductFamilyCsvResource.java`,
`StoreCsvResource.java`, `RefExportResource.java`, `SyncIngestResource.java` ;
`HomeResource.java`, `main.html` (menu — confirme l'absence d'onglet imports).
