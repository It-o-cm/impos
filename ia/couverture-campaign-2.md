# Campagne couverture 2 — les classes des chantiers retours et langue

But : porter la couverture de branches des classes que les chantiers **retours**
(lots R1 à R6, LC-05) et **langue** (lot L1, LC-13-06) ont touchées ou créées au
maximum utile. Toutes ont déjà une classe de test : les branches qui manquent
sont donc DANS des classes testées, et `sh/run-tests-campaign.sh`, qui saute
toute classe possédant un `<Classe>Test.java`, ne les verra jamais.

Cette campagne est la suite de `ia/couverture-campaign.md`. **Lire ce document
d'abord** : la méthode, la liste de nullité, les interdits et le format de
rapport y sont, ils ne sont pas répétés ici. Ce fichier ajoute le périmètre, les
pièges que les deux derniers jours ont mesurés, et **une règle qui remplace celle
de la campagne 1** : le code inatteignable ne se rapporte plus, il se supprime.
Là où la campagne 1 écrit « ne rien modifier sous `src/main` », lire : rien
d'autre que la suppression du code mort, aux conditions posées plus bas.

`CLAUDE.md` est le contrat de forme. Le lire, l'appliquer, ne pas le paraphraser
dans le rapport.

## Étape 0 — mesurer, comme dans la campagne 1

```
mvn -q verify
mvn -q jacoco:report
```

Lire `target/site/jacoco/jacoco.csv`, trier par `BRANCH_MISSED` décroissant, et
**restreindre à la liste de classes ci-dessous**. Écrire la liste retenue avec
`BRANCH_MISSED/BRANCH_COVERED` par classe avant d'écrire une ligne de test.

Si `mvn -q verify` est ROUGE à l'étape 0, ne rien écrire : réparer d'abord, et
lire plus bas « Les tests qui figent un nombre », qui explique la famille
d'échecs la plus probable.

## Périmètre — ces classes et elles seules

Retours (LC-05) :

```
ui/returnprocess/ReturnValuationService      (classe neuve, 325 lignes)
ui/returnprocess/RefundService
ui/returnprocess/RefundState
ui/returnprocess/RefundResource
ui/ticket/TicketService                      (interception du scan retour)
ui/hardware/TicketPrinterService             (bloc RECALCUL DES AVANTAGES)
domain/sale/TicketLine                       (getEffectiveUnitPrice)
domain/sale/Refund, domain/sale/RefundLine
ui/home/HomeService                          (MODE RETOUR, types de retours)
ui/endorsement/EndorsementResource           (action RETURN_MODE_TOGGLE)
ui/auth/AuthService                          (caisse dediee aux retours)
```

Langue (LC-13-06) :

```
ui/Messages                                  (classe neuve)
ui/LanguageResource                          (classe neuve)
ui/SessionCheckFilter                        (deux prefixes ouverts)
ui/PosState                                  (champ language)
domain/people/Employee                       (colonne language)
service/PosSettingsService                   (dix Def ajoutes, leurs accesseurs)
```

Ne pas élargir. Les autres classes de l'onglet relèvent de la campagne 1, qui a
son propre fichier.

## Les tests qui figent un nombre — la famille d'échecs de ce dépôt

Trois tests affirment un COMPTE, et non un comportement. Ils cassent dès qu'une
constante ou un paramètre est ajouté, et c'est **voulu** : ce sont des gardes
contre l'ajout silencieux.

- `TechnicalEventTest` fige le nombre de constantes de `EventType` ET le tableau
  exact des constantes dans l'ordre. Une constante ajoutée réclame les deux
  mises à jour, et le nom de la méthode de test porte le nombre en toutes
  lettres.
- `MessageBundleExhaustivenessTest` exige que `msg_fr`, `msg_nl` et `msg_pt`
  déclarent exactement les mêmes clés et aucune valeur vide. Une clé ajoutée
  dans une seule langue casse la construction.
- `MessagesTest` lit les **vrais** bundles du classpath : un libellé modifié
  dans `src/main/resources/messages/` casse les assertions qui le citent.

**Mettre ces tests à jour, jamais les affaiblir.** Remplacer un
`assertArrayEquals` par une comparaison d'ensembles, ou un compte exact par un
`>=`, détruit la garde : c'est un échec de campagne, pas une correction.

## Pièges mesurés sur ces classes

- **`ISO_LOCAL_DATE_TIME` et la minute ronde.** Le formateur n'écrit les
  secondes que lorsqu'elles sont présentes. Un `LocalDateTime.of(a, m, j, 11,
  30)` rend donc une chaîne différente d'un `…, 11, 30, 45)`, et un attendu
  écrit à la main dépend alors de cette règle plutôt que du contrat. Donner des
  secondes non nulles aux dates de fixture dès qu'une chaîne ISO est comparée.
- **`MessageFormat` et l'apostrophe.** Un libellé passé à `Messages.format()`
  doit DOUBLER ses apostrophes, `MessageFormat` s'en servant pour échapper. Un
  libellé qui n'est lu que par `get()` n'a pas cette contrainte. Un test qui
  ajoute un libellé à paramètres vérifie les deux formes.
- **Collaborateurs neufs dans des montages anciens.** Quatre champs ont été
  ajoutés à des services déjà testés ; un montage qui les laisse à `null`
  tombe en NullPointerException au premier appel, et le test échoue pour une
  raison qui n'est pas la sienne. Les fixtures les portent déjà, ne pas les
  retirer : `RefundService.returnValuationService` (rendre un
  `Recalculation.none(NOT_APPLICABLE)` par défaut),
  `TicketService.refundService`, `AuthService.posSettingsService`, et dans
  `HomeServiceTest` les trois paramètres de retour
  (`returnInSaleAllowed`, `returnWithoutOriginAllowed`,
  `returnDedicatedRegister`) plus `gestureAuthorized(state, "return")`.
- **`ReturnValuationService` ne doit JAMAIS lever.** C'est son contrat : le
  client est au comptoir. Les deux familles d'échec du client moqué se pilotent
  séparément — `InterruptedException` (le drapeau d'interruption doit être
  relevé, et le test doit le consommer avec `Thread.interrupted()` pour ne pas
  polluer les suivants) et toute autre exception. Un test qui attend une
  exception de cette classe teste l'inverse du contrat.
- **`ValuationClient` se moque**, y compris son `record ValuationResult`, qui se
  construit directement. Le panier envoyé se relève avec un `ArgumentCaptor` :
  c'est la seule façon d'éprouver le constructeur de requête, qui est privé.
- **`Messages` résout sa langue UNE fois par requête** (champ
  `resolvedLanguage`). Un test qui change `state.language` après une première
  lecture n'obtiendra pas la nouvelle langue : c'est le comportement voulu, pas
  un défaut à contourner en instanciant deux fois dans la même assertion.
- **`PosState.auth.isLocked` vaut `true` à la construction.** Un montage qui
  veut une caisse ouverte doit le dire ; plusieurs gardes de la langue et du
  retour en dépendent.
- **`jakarta.ws.rs.core.Response` s'utilise en try-with-resources** dans les
  tests de ressource de ce dépôt. Garder l'idiome.
- Les pièges de la campagne 1 valent toujours, en particulier
  `ReceiptLogo.directive()` en tête de chaque document imprimé,
  `TicketLayoutGoldenMasterTest` qu'on ne réenregistre jamais, et les finders
  Panache qui se moquent par `mockStatic(PanacheEntityBase.class)`.

## Le code inatteignable se SUPPRIME

C'est la différence de fond avec la campagne 1, qui interdisait de toucher
`src/main` et demandait de rapporter les branches impossibles. **Cette campagne
les supprime.** Une alternative qu'aucune exécution ne peut atteindre n'est pas
une précaution : c'est du code mort qui se fait lire, relire et maintenir, et qui
fait mentir le rapport de couverture — on ne sait plus si les 3 % manquants sont
une garde oubliée ou un cadavre.

Quand une branche de la liste de l'étape 0 résiste, la question n'est donc plus
« comment l'atteindre » mais « est-elle atteignable ». Deux réponses, deux
traitements, et il faut trancher par preuve et non par impression.

### À supprimer : inatteignable PAR CONSTRUCTION

Le code que rien ne peut exécuter, quel que soit l'environnement :

- une garde qu'une garde précédente rend impossible — un `if (x == null)` après
  un retour anticipé sur `x == null`, un `signum() < 0` sur une valeur déjà
  bornée à zéro au-dessus ;
- un `default` ou un dernier bras de `switch` sur une énumération dont tous les
  cas précédents sont écrits ;
- une méthode, une surcharge ou un champ que personne n'appelle — chercher les
  appelants dans `src/main` ET dans `src/test` avant de conclure, et se méfier
  des appels par réflexion, par Qute (`{inject:…}`, `@TemplateData`,
  `@TemplateGlobal`) et par JAX-RS, qui ne ressemblent pas à des appels ;
- une condition contredite par le type ou par une contrainte de colonne — un
  test de nullité sur un primitif, un `isEmpty()` sur une collection que le
  constructeur remplit toujours ;
- un paramètre que tous les appelants passent constant, et le bras que l'autre
  valeur servait.

Un exemple mesuré sur ce dépôt : `isSogecBon` n'est appelée de nulle part. Elle
se supprime, et le test qui la couvrait avec elle.

Pour chacune : supprimer le code, supprimer les tests qui n'existaient que pour
lui, relancer `mvn -q verify`, et rapporter la suppression en une ligne avec la
raison de l'inatteignabilité. **Un test vert n'est pas une preuve
d'inatteignabilité** : c'est la lecture du code qui l'établit, le test ne fait
que confirmer qu'on n'a rien cassé.

### À garder : atteignable mais non reproductible en test

À l'opposé, le code qu'un montage unitaire ne sait pas déclencher mais que
l'exploitation déclenchera. Celui-là se garde, se commente s'il ne l'est pas, et
se rapporte comme non couvert avec sa raison :

- une entrée-sortie qui peut échouer — `Messages.load()` et son
  `UncheckedIOException` sur un bundle présent et illisible, une impression, une
  pesée, un appel au concentrateur monétique ;
- l'indisponibilité d'une brique d'infrastructure — les `catch (Exception)` des
  globaux Qute de `ThemeService.Globals`, qui existent pour qu'un rendu d'écran
  ne casse jamais si Arc ne répond pas ;
- un échec réseau ou un délai dépassé vers le moteur, la fidélité ou le nœud
  magasin ;
- une collision de concurrence que le test ne met pas en scène.

La frontière, en une phrase : **si la branche peut s'exécuter un jour sur une
caisse, elle reste ; si aucune exécution ne peut l'atteindre, elle part.** En cas
de doute réel, elle reste, et le doute se rapporte — supprimer une garde encore
atteignable est le seul défaut de cette campagne qui coûte de l'argent au
magasin.

### Deux cas déjà tranchés sur ces classes

- Le `default` du `switch` sur `PriceModType` dans
  `ReturnValuationService.applyGesture` est ATTEIGNABLE : une ligne peut porter
  un geste de niveau ticket (`GLOBAL_REMISE`, `GLOBAL_DISCOUNT`), et le test qui
  l'exerce existe. Ne pas le supprimer.
- `LanguageResource.redirectBack()` n'a que deux atterrissages, tous deux
  atteints. Rien à supprimer, rien à inventer.

### Ce que la suppression change dans la conduite de la campagne

- `src/main` se modifie, mais **par suppression seulement**. Aucun ajout, aucune
  réécriture, aucun renommage, aucune garde nouvelle : ce n'est pas une campagne
  de refactoring. Un obstacle à la testabilité qui demanderait d'AJOUTER du code
  se rapporte en une ligne, comme avant.
- Chaque suppression est son propre commit, séparé des commits de test, avec le
  motif dans le message. Une suppression mêlée à dix tests ne se relit pas.
- `mvn -q verify` doit être vert après chaque suppression, les golden masters
  compris : un document imprimé qui change est un échec, pas une référence à
  réécrire.
- Le rapport de classe gagne une ligne : `code supprime : <element> (raison)`,
  ou `aucun`.

## À faire en plus, une fois les branches traitées

Deux vérifications que seul un œil extérieur fait bien, à rapporter en une
phrase chacune :

1. **Les six lignes de retour laissées partielles** portent-elles un test qui
   nomme ce qui manque ? Prix modifiable hors ticket d'origine (LC-05-02-02
   et -15), article dématérialisé Nexo (LC-05-03-01), remboursement carte
   Verifone (LC-05-06-03), autre moyen (LC-05-06-08), détail des recalculs
   (LC-05-07-02). Un « reste à faire » coté dans le questionnaire sans test qui
   l'atteste est une affirmation sans preuve.
2. **Le bundle néerlandais et le portugais** sont une première passe non
   relue. Aucun test ne peut juger une traduction, mais un test peut juger
   qu'une clé existe partout et qu'aucune valeur n'est restée en français dans
   les trois fichiers par copier-coller. Si ce test n'existe pas, le proposer en
   une phrase — ne pas l'écrire sans accord, une heuristique de langue qui se
   trompe bloque la construction pour rien.

## Rapport et fin

Format de rapport par classe et condition de fin : ceux de
`ia/couverture-campaign.md`, avec la ligne de suppression en plus :

```
<Classe> : branches n/n (x %) -> m/m (y %)
tests ajoutes : k  dont cas de nullite : j
tests executes : avant -> apres
code supprime : <element> (raison de l'inatteignabilite)   ou   aucun
branches laissees : ligne L (raison), ligne L (raison)
obstacle src/main : ... (ou aucun)
```

Un commit par classe pour les tests, un commit par suppression, jamais de
`git push`.

Le rapport final consolide trois listes : la couverture de branches globale
avant et après, **tout le code supprimé avec sa raison**, et les branches
laissées non couvertes parce qu'atteignables en exploitation et non en test.
