# Campagne — remettre au vert les tests e2e existants (impos)

## Contexte mesuré (ne pas re-diagnostiquer, vérifier)

Dernier run : `./demo-stack.sh` → pre-flight `Demo*` (rapports dans `target/failsafe-reports/`).
Résultat : 20 tests, 15 failures, 2 errors.

| Classe | Rouge | Symptôme |
|---|---|---|
| DemoIT | 10/11 | `the hardware scan bus should accept 12341234` (demo06 : conséquence, demo05 n'a pas clos le ticket) |
| DemoFidIT | 4/6 | même assertion ; fid02 : timeout Playwright sur `DISPONIBLE` |
| DemoDegradedIT | 1/1 | `the hardware scan bus should accept the badge` |
| DemoFidDegradedIT | 1/1 | `the hardware scan bus should accept 12341234` |
| DemoStoreIT | 1/1 | timeout Playwright sur `Entrez votre code PIN :` (le scan du badge l.132 n'est pas asserté) |

Les 12 `Group*IT` n'ont PAS été exécutés depuis ; ils utilisent le même `POST /api/pos/scan` et sont présumés rouges pour la même raison.

### Cause racine (identifiée, à confirmer par un test rouge→vert)

`POST /api/pos/scan` n'atteint jamais `PosHardwareResource.handleScan` (aucune ligne « Entering method handleScan » dans les logs) : il répond 404.

Le commit `2349ebd` (phase G) a créé `src/mock/java/com/intermarche/pos/ui/resource/MockCampaignEngineResource.java` avec **`@Path("/api")` au niveau classe**. JAX-RS (RESTEasy classic) choisit d'abord la CLASSE dont le chemin littéral est le plus spécifique : `/api` (4 caractères) bat `PosHardwareResource` en `@Path("/")`. Tout `/api/**` non couvert par une classe plus précise (`/api/pos/scan`, `/api/pos/...`) est donc résolu contre ce mock, qui n'a que `/consumptions` → 404.

C'est exactement le piège déjà documenté dans la javadoc de `MockHardwareResource` (champ `state`, « WHY THESE TWO ENDPOINTS LIVE HERE »).

Aggravant : `src/mock/java` est en `add-source` (classpath PRINCIPAL, cf. commentaire du pom), et ce mock n'a pas de `@IfBuildProfile`. Le 404 touche donc aussi `mvn quarkus:dev` (simulateur, lecteur de scan de la pile de démo), pas seulement les tests.

## Lot 1 — la correction (obligatoire)

1. **Test d'abord.** Écrire un test qui prouve la régression, dans le harnais existant des e2e (`@QuarkusTest` + `E2eTestProfile`, package `com.intermarche.e2e`, pas de nouvelle classe si un `Group*IT` existant a sa place naturelle ; sinon une classe `RoutingIT` minimale) :
   - `POST /api/pos/scan` avec un code non vide → 200 ;
   - `POST /api/pos/scan` avec un corps vide → 400 (preuve qu'on atteint bien `handleScan`, pas un 404) ;
   - `POST /api/consumptions` (JSON) → 200 et corps contenant `accepted` (le mock reste joignable).
   Le lancer AVANT la correction et constater le rouge (404).
2. **Correction.** Dans `MockCampaignEngineResource` uniquement :
   - classe : `@Path("/api/consumptions")` ;
   - méthode `ingestConsumption` : retirer son `@Path("/consumptions")`.
   L'URL effective (`{pos.valuation.url}/consumptions` = `http://localhost:8081/api/consumptions` sous `E2eTestProfile`) est inchangée. Ne pas toucher `ConsumptionDeliveryService`.
   Mettre à jour la javadoc de la classe pour dire pourquoi le chemin est porté par la classe (renvoi au piège de `MockHardwareResource`). Rien d'autre dans ce fichier.
3. `MockCampaignEngineResourceTest` (unitaire) doit rester vert tel quel ; ne le modifier que s'il casse.
4. **Garde anti-récidive** (unitaire, JUnit 5 pur, pas de `@QuarkusTest`) : un test qui parcourt les fichiers `.java` de `src/mock/java`, charge chaque classe par son nom qualifié (`Class.forName`), lit son `@Path` de classe et échoue si l'une d'elles a un chemin de classe égal à `/api` ou `/` (message : nommer la classe et le piège). Javadoc sur chaque méthode.

## Lot 2 — rejouer et trier

Dans cet ordre, en lisant `target/failsafe-reports/*.txt` après chaque run :

```bash
mvn verify -DskipITs=false -DskipUTs=true -Dit.test='Group*IT'
```

puis la pile de démo complète (pre-flight `Demo*` inclus) :

```bash
./demo-stack.sh
```

Pour chaque test encore rouge, classer AVANT de toucher quoi que ce soit :

- **(a) conséquence du 404** → doit être vert ; sinon c'est un nouveau diagnostic ;
- **(b) assertion périmée** par un changement VOULU du code (logo en tête de ticket via `ReceiptLogo.directive()` = ligne `[[IMAGE 384 72 …]]` d'environ 4 600 caractères ; bannière texte remplacée par le logo sur avoir et cagnotte ; suppression de la fonctionnalité Campaign côté caisse, déplacée dans imedge) → adapter l'assertion au comportement voulu, en citant dans le rapport le changement qui la justifie ;
- **(c) vrai bug applicatif** → NE PAS le corriger dans ce lot : le décrire (test, attendu, obtenu, cause probable, fichier:ligne) et s'arrêter sur ce point ;
- **(d) environnement** (service externe absent, base persistée du nœud) → le signaler, ne pas coder de contournement.

### Point connu à classer : collision PLU dans DemoStoreIT

Pendant `DemoStoreIT`, le pull des référentiels depuis le nœud magasin (:8070) échoue en boucle sur tous les paliers (1000→100→10→1) :

```
Unique index … ON PUBLIC.PRODUCTS(PLU) VALUES ('4020')   (puis 501, 210, 220, 4062, 4796)
at ProductCsvResource.importProducts ← RefApplyService.applyFromFile
```

`ProductCsvResource` apparie par EAN (`Product.list("ean IN ?1")`) : un INSERT sur un PLU déjà présent veut dire que la ligne tirée porte un PLU du seed sous un EAN que la caisse n'a pas. Diagnostiquer (sans corriger) laquelle de ces hypothèses tient, preuves à l'appui :

1. la base persistée du nœud (`imedge/data/store-node.mv.db`) contient des produits de même PLU et d'EAN différent (import CSV antérieur) → classe (d) ;
2. course au démarrage : le pull pré-charge les produits avant que `DataInitializer` n'ait commité son seed → classe (c) ;
3. autre, à décrire.

Ce point n'est pas bloquant pour `DemoStoreIT` si le scan est réparé ; le vérifier.

## Règles

- Portée stricte : uniquement ce qui est décrit ici. Toute amélioration repérée ailleurs → une ligne dans le rapport, pas de code.
- Code et commentaires en anglais ; **Javadoc sur toutes les méthodes**, sans exception (tests compris, méthodes privées, constructeurs).
- Ne jamais lancer `-Dgolden.record=true`. Ne pas toucher `src/test/resources/golden/`.
- Ne pas désactiver, `@Disabled`, ni affaiblir un test pour le faire passer (pas de `assumeTrue` ajouté, pas de timeout rallongé sans cause démontrée).
- Le répertoire de travail contient du travail non commité de l'utilisateur (suppression Campaign côté caisse, imports, TicketPrinterService, golden) : ne rien annuler, ne rien reformater.
- Pas de git (ni commit, ni stash, ni checkout, ni push).

## Critères de fin

1. Le test du lot 1.1 est rouge avant, vert après.
2. Garde anti-récidive verte.
3. `mvn test` vert (hors rouges préexistants listés avec leur cause).
4. `Group*IT` : tous verts, ou chaque rouge restant classé (b)/(c)/(d) avec preuve.
5. `Demo*` sous `./demo-stack.sh` : idem.
6. Diagnostic de la collision PLU rendu.

## Rapport attendu

Tableau par classe IT : avant / après / classement des rouges restants, la liste des fichiers modifiés avec une ligne de justification chacun, et le diagnostic PLU.
