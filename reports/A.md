# Campagne E2E — Groupe A (Prise de poste & authentification)

**Statut : 7/7 verts.** Source de vérité : `e2e-scenarios.md` (checklist), pas JaCoCo.

## Commande de run

```
mvn -q verify -DskipUTs=true -Dit.test=GroupAIT -DskipITs=false
```

Résultat du dernier run : `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` (~41 s) → **BUILD SUCCESS**.

## Architecture

- **Une seule classe `GroupAIT`** (package `com.intermarche.pos.e2e`), un `@QuarkusTest` = **un seul boot Quarkus** pour toute la lettre.
- `@WithPlaywright(headless = true)` — Chromium headless via l'extension quarkus-playwright ; tout est joué **à travers l'écran** (navigation, taps, numpad tactile).
- `@TestProfile(E2eTestProfile.class)` : `pos.terminal.id=C04` (le défaut POS01 casserait les assertions `C04-Sxxxxx`) et `hardware-api.url` pointé sur la base de l'app elle-même (`http://localhost:8081`) → les appels hardware frappent le simulateur embarqué (`MockHardwareResource`, dans `src/mock/java`) et renvoient 200.
- `@TestMethodOrder(MethodOrderer.MethodName.class)` : les scénarios `a1…a7` s'exécutent dans l'ordre du nom.
- **Session partagée par la classe** : A1 ouvre l'unique session de caisse sur la base H2 fraîche (drop-and-create), A2–A7 opèrent dans cette même session ouverte (modèle « une session par caisse », pas par caissier). Rien dans le groupe ne clôture la session (ce serait un Z) : elle reste OPEN pour toute la classe.

## Recette de login (réutilisée dans tout le groupe)

1. `page.navigate(/lock)` **en premier** — déconnecte l'opérateur courant et vide la boîte badge one-shot.
2. Présenter le badge comme **geste hardware** : `POST /api/pos/scan` (text/plain, code 8 chiffres). Le badge numérique ne peut pas être tapé (le clavier IDENTIFIANT est alpha-only).
3. Le poll `/lock-data` (1 s) fait basculer l'overlay en saisie PIN → attente sur le texte catalogue `Entrez votre code PIN :` (jamais un sleep).
4. Taper le PIN sur les boutons `#keyboardArea` (role+name exacts), soumettre avec `#actionBtn`.
5. Le pulse d'unlock **ouvre réellement le tiroir** (simulateur embarqué) → avant tout écran `@DrawerMustBeClosed`, fermeture physique : `POST /api/hardware/drawer/close`, sinon le garde tiroir dévie sur `/drawer-error`.

## Scénarios (tous `[S]` — caisse seule + simulateur, aucun résidu `[V]`/`[N]`)

| Id | Intitulé | Ce qui est prouvé | Oracles |
|----|----------|-------------------|---------|
| **A1** | Prise de poste nominale | badge→PIN→SESSION→float 200 €→VENTE | écran (`Aucune session ouverte`, `TOTAL À PAYER`, `#opInfo` = Jean Dupont) + DB : session OPEN, `C04-S\d{5}`, float 200,00 |
| **A2** | Reverrouillage en journée | relock avec session ouverte → **direct à la vente** (pas de détour session), aucune 2ᵉ session | écran `TOTAL À PAYER` + DB : même `sessionNumber`, toujours l'unique session OPEN |
| **A3** | Verrouillage PIN | 3 PIN faux → `COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD` ; bon PIN refusé pendant le lockout ; accepté après expiration ; compteurs remis à zéro | écran + DB : `isCurrentlyLocked()`, puis `failedAttempts=0`, `lockedUntil=null` |
| **A4** | Badge sur écran de lock | le badge préremplit le login (unlock sans jamais taper le login) ; mailbox **one-shot** (un `/lock` frais ne re-déclenche pas le PIN) | écran `TOTAL À PAYER`, `#opInfo`, puis `count(Entrez votre code PIN :) == 0` sur page fraîche |
| **A5** | Badge en session ouverte, sans modale | badge scanné en étant loggé → **ignoré** : aucun switch opérateur, aucune ligne, chute jusqu'au fallback code inconnu | **serveur** via `@Inject PosState` : `operatorId` inchangé, `ticket.items` vide, `transientError == "CODE INCONNU: 12341234"` + écran (même opérateur, `EN ATTENTE D'ARTICLES...`) |
| **A6** | Changement de PIN | PIN actuel faux → `Code PIN actuel incorrect` ; confirmation ≠ → `Les nouveaux codes ne correspondent pas` ; valide → `Code PIN modifié` + reconnexion sur le nouveau PIN | écran ; **restaure le PIN seed 1234** en fin |
| **A7** | Logout avec panier en cours | logout (`/lock`) abandonne le panier mémoire mais laisse le draft **OPEN & intact** en DB (seul le recovery au redémarrage l'annule) | écran (`CAISSE FERMÉE`, puis `EN ATTENTE D'ARTICLES...`) + DB : même id, statut OPEN, 1 ligne conservée |

## Itérations (3 runs)

1. a1–a4 verts ; a5/a6/a7 en timeout — la page `/lock` résiduelle de A4 continuait à poller et **volait la boîte badge one-shot** du test suivant.
2. Ajout de `page.close()` par test → a6 vert ; a5/a7 encore rouges.
3. Correction a5 + a7 → **7/7**.

## Points durs & leçons

- **La boîte badge one-shot est un état serveur partagé.** Une page `/lock` laissée ouverte continue de poller `/lock-data` et consomme le badge destiné au scénario suivant → `page.close()` en fin de **chaque** test (les deux pages pour A4).
- **Les états client-invisibles s'assertent côté serveur.** A5 : `UnknownScanHandler` écrit son message **sans `touch()`**, donc `CODE INCONNU` n'atteint jamais le poll → assertion via `@Inject PosState` (opérateur inchangé, panier vide, `transientError` posé sur le handler inconnu).
- **Les parcours session-déjà-ouverte traversent `/drawer-error`.** L'unlock ouvre le tiroir ; avec une session ouverte la redirection frappe `/` gardé → la **fermeture physique** du tiroir (`POST /api/hardware/drawer/close`) ramène le poll de statut tiroir sur l'écran gardé (A2/A4/A5/A6/A7).
- **Les attentes d'expiration se vieillissent en base, pas au sleep.** A3 : impossible d'attendre 5 vraies minutes → `lockedUntil` reculé dans le passé via `QuarkusTransaction` (seul échafaudage autorisé hors surface HTTP).
- **`#resumeScreen` est `display:none`** jusqu'à activation → on attend le texte visible `CAISSE FERMÉE` pour confirmer le logout (A7).
- **Isolation** : `@TestMethodOrder(MethodName)` + session partagée ouverte par A1 sur la DB fraîche ; chaque scénario restaure ce qu'il altère (A6 remet le PIN 1234, A3 finit déverrouillé).

## Portée

Aucune modification de `src/main` — seul `GroupAIT.java` a été touché ; rien n'est commité.

## Observation de conception (pas un bug — non corrigée, règle de portée)

`UnknownScanHandler` posant `transientError` sans `touch()` : un `CODE INCONNU` issu d'un scan hardware nu sur un écran de vente idle ne s'affichera qu'à la prochaine action produisant un `touch()`. À garder en tête pour B7 / O-A.
