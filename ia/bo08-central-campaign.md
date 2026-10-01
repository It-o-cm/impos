# Campagne BO-08 — la brique centrale dans la pile, et la relance côté caisse

But : rendre démontrables les quatorze lignes de BO-08 qui énumèrent trois
échelons — brique centrale, point de vente, point de contact — et fermer les
quatre qui attendent en plus une relance manuelle côté caisse.

Lignes visées : BO-08-01-01/02/03, BO-08-04-01/02, BO-08-04-03/04/06/07/08,
BO-08-04-09/10/12/13.

`CLAUDE.md` est le contrat de forme. `ia/bo08-recotation.md` porte la
re-cotation dont ce lot est la suite.

## Ce qui est déjà vrai dans le code — vérifié, ne pas le redécouvrir

- `pos.role` prend trois valeurs : `register`, `store`, `central`. Lu par
  `RegisterCommandResource`, `RefExportResource`, `RefPullService`,
  `SyncIngestResource`, `DashboardResource`.
- `RefExportResource.versions()` sert `RefPayloads.ECHELON_DOMAINS`
  (`COUNTRIES`, `ENSEIGNES`, `PDVS`, `ECHELON_SETTINGS`) quand le rôle est
  `central`, et `RefPayloads.DOMAINS` sinon.
- `RefPullService` lit `pos.sync.central-url` : l'URL du nœud central dont un
  nœud MAGASIN tire ses échelons.
- `Country`, `Enseigne` et `EchelonSetting` portent « Held on the CENTRAL
  node » ; `AdminEchelonResource` édite ce registre.
- `SyncSupervisionResource` n'est gardé que par `@RolesAllowed("ADMIN")`, PAS
  par `pos.role` : une instance centrale sert le même `/admin/sync`.
- Les importeurs CSV ne portent aucune garde de rôle : la porte
  `POST /feeds/import/{CODE}` fonctionne aux trois échelons.
- Le `DataInitializer` d'imedge ne crée AUCUN échelon — ni pays, ni enseigne,
  ni PDV. Un central fraîchement démarré sert des domaines vides.
- `SyncIngestResource` est réservé au rôle `store` et répond 403 hors rôle.
  C'est correct : aucune caisse ne pousse vers le central.

## État du dépôt : une première passe existe et n'a JAMAIS été compilée

Une passe a été écrite sans compilateur disponible. Elle est peut-être juste,
elle n'est pas vérifiée. **Premier geste : compiler et tester, avant de
toucher à quoi que ce soit.**

```
cd ~/Documents/Workspaces
for p in impos imedge imvaluation imfid; do (cd "$p" && mvn -q test > ../build-$p.log 2>&1; echo "$p -> $?"); done
```

Ce que cette passe a posé, à vérifier ligne par ligne :

- `impos/demo-stack.sh` : un service `central-node` sur le 8050, `pos.role=central`,
  base H2 `./data/central-node`, synchronisation `rsync` des sources vers
  `../imedge-central`, `lsof -ti :8050` ajouté au bloc de libération des ports,
  et `-Dpos.sync.central-url=http://localhost:8050` posé sur le nœud magasin.
- `impos/.../ui/supervision/ExchangeHealthResource.java` : `@POST
  /supervisor/health/feeds/replay` appelant `RefPullService.triggerPull()`.
- `impos/src/main/resources/templates/supervisor.html` : bouton « Relancer le
  tirage » sur la carte des flux, qui perd son « (lecture seule) ».
- `impos` et `imedge`, `.../service/sync/RefPullService.java` : une
  `ConcurrentHashMap` d'erreurs par domaine, un try/catch PAR DOMAINE dans
  `pullOnce()` de sorte qu'un domaine fautif n'interrompe plus le cycle, et
  `getLastError(domain)` / `getLastErrors()`.
- `imedge/.../ui/supervision/SyncSupervisionService.java` : `PullRow.lastError`,
  rempli depuis le service, plus une ligne synthétique pour un domaine en
  échec qui n'a jamais rien appliqué. **L'import `java.util.Map` a été ajouté
  après coup — le vérifier en premier.**
- `imedge/src/main/resources/templates/admin-sync.html` : colonne « Dernière
  erreur » sur la table des tirages, `colspan` de la ligne vide passé à 4.

Si une de ces pièces est fausse, la corriger. Si elle est inutile, la retirer
et le dire. Ne pas la réécrire pour le plaisir de la réécrire.

## Travail à faire

### 1. Faire passer le build

Les quatre dépôts verts sous `mvn test`. Le plus exposé est
`impos/src/test/java/com/intermarche/pos/imports/ProductFamilyCsvResourceTest.java`,
345 lignes entièrement neuves, jamais compilées. Après le premier vert,
**contrôler que le nombre de tests exécutés a augmenté** : un total inchangé
signifie que la classe n'a pas tourné, pas qu'elle passe.

### 2. Faire démarrer la pile à cinq services

`./demo-stack.sh` doit lever imvaluation 8090, imfid 8060, **central 8050**,
magasin 8070, caisse 8080, et arriver au bout de son pre-flight.

Obstacle connu : `start_app` lance `mvn quarkus:dev` depuis le répertoire du
projet. Deux instances d'imedge depuis le MÊME répertoire se disputent
`target/` et le verrou du mode dev — ça ne démarre pas. La première passe
contourne par une copie `rsync` vers `../imedge-central`. Si une solution plus
propre existe (un répertoire de build distinct, un jar packagé pour le
central), la préférer et l'expliquer ; sinon garder la copie et vérifier
qu'elle ne réintroduit pas `target/` ni `data/`.

Le port de debug dérivé est `port - 3000` : le central prend 5050, distinct
des autres. Vérifier qu'aucune JVM ne meurt sur « JDWP bind failed ».

### 3. Donner des échelons au central

Sans pays ni enseigne, le tirage d'échelon transporte du vide et les quatorze
lignes se démontrent sur une table vide. Créer au démarrage de la démo, par
l'IHM ou par un flux, au moins un pays, une enseigne et un PDV, puis vérifier
sur `/admin/sync` du nœud magasin que les quatre domaines d'échelon portent une
empreinte et une date d'application.

Décider et dire où ça vit : dans `demo-stack.sh`, ou dans le seed d'imedge
sous garde de rôle. Ne pas seeder des échelons sur un nœud magasin.

### 4. Fermer les quatre relances manuelles côté caisse

Le bouton posé par la première passe appelle `triggerPull()` et redirige sans
rien montrer. Or `triggerPull()` RETOURNE le message d'échec ou null. Deux
manques :

- l'issue de la relance n'est pas affichée — le nœud magasin le fait par un
  paramètre `notice`, la caisse ne le fait pas ;
- l'écran superviseur de la caisse n'affiche AUCUN état de tirage : ni
  empreinte, ni date, ni erreur par domaine. Les erreurs sont désormais
  disponibles par `RefPullService.getLastErrors()` et rien ne les rend.

Ajouter la table des tirages sur `/supervisor`, sur le modèle de celle de
`/admin/sync`, et afficher l'issue de la relance.

## Critères d'acceptation, tous vérifiables

1. `mvn test` vert sur les quatre dépôts, et le nombre de tests exécutés
   d'impos supérieur à celui d'avant la campagne.
2. `./demo-stack.sh` monte cinq services et finit son pre-flight.
3. `/admin/sync` du nœud magasin (8070) montre les quatre domaines d'échelon
   avec une empreinte non vide.
4. `/admin/sync` du central (8050) répond et montre ses propres flux.
5. `/supervisor` de la caisse montre une table de tirage par domaine avec une
   colonne d'erreur, et le bouton de relance affiche son issue.
6. Un domaine rendu volontairement fautif n'empêche pas les autres d'être
   tirés, et son erreur apparaît aux deux échelons.
7. Tests unitaires à deux bras sur tout ce qui est ajouté, cas de nullité
   compris : `getLastError` sur un domaine sain et sur un domaine fautif,
   `pullOnce` avec un domaine qui lève et un qui passe, la relance avec et
   sans échec.

## Pièges de ce dépôt

- **Javadoc orpheline.** Insérer un membre juste au-dessus d'une déclaration
  qui porte déjà une javadoc détache celle-ci. C'est arrivé quatre fois dans la
  passe précédente. Contrôler après chaque insertion :
  `grep -Pzo '\*/\n\s*/\*\*' <fichier>` doit ne rien rendre.
- **Import manquant.** Vérifier que chaque symbole utilisé est importé — le
  `Map.Entry` de `SyncSupervisionService` est passé à travers.
- `ReceiptLogo.directive()` met un bitmap de ~4 600 caractères en tête de tout
  document imprimé : aucune assertion de largeur de ligne ne doit le mesurer.
- **Ne jamais lancer `-Dgolden.record=true`.** Un document qui change est un
  échec à rapporter.
- Les entités Panache ne sont pas enhanced sous `mvn test` : `mockStatic(PanacheEntityBase.class)`.
- `pos.role` par défaut est `register`, pas `store` : un `-D` oublié fait
  démarrer un imedge en caisse et les endpoints répondent 403 hors rôle.

## Interdits

- Ne pas toucher à la cotation du classeur : ce lot rend démontrable, il ne
  cote pas. La cotation se fait après, sur la pile qui tourne.
- Ne pas ajouter `ACCOUNT_CUSTOMERS` à `EngineFeedService.CATALOG` : ce
  catalogue est la liste des flux livrés au MOTEUR, qui n'a rien à faire des
  clients en compte. Le domaine existe déjà sous le nom `CUSTOMERS` dans
  `RefPayloads.DOMAINS` et dans la table de délégation d'`EngineFeedRelayResource`.
- Jamais de `git push`.

## Rapport

```
build : impos <n tests> imedge <n> imvaluation <n> imfid <n>
pile  : 5 services montes o/n, pre-flight o/n
echelons : COUNTRIES/ENSEIGNES/PDVS/ECHELON_SETTINGS empreintes o/n
caisse : table de tirage o/n, issue de relance affichee o/n
premiere passe : pieces gardees / corrigees / retirees
tests ajoutes : <n> dont cas de nullite <n>
lignes BO-08 desormais demontrables : <liste>
```
