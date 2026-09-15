# Campagne caisse « la caisse témoin » — prompt de session

Un prompt, une session, **un lot**. Le numéro du lot est le seul paramètre.

> **LOT = _<C1, C2, C3, C4, C5a, C5b, C5c>_**

---

## 1. Ce que tu fais

Le back-office ne peut chercher que ce que la caisse a écrit. Vingt-quatre des
quarante-six critères du journal électronique (`BO-04-01`) sont restés en `3`
non par manque d'écran, mais par manque de **gisement** : la donnée n'existe
nulle part. Cette campagne crée ces gisements en caisse, et livre dans le même
lot le critère de recherche qui les exploite.

Tu construis le lot indiqué et ses tests unitaires. Rien d'autre : ni le lot
suivant, ni le précédent, ni les améliorations repérées en chemin.

**Une exigence n'est servie que si les deux moitiés sont livrées** : la caisse
écrit la donnée *et* le journal sait la chercher. Un gisement sans critère
laisse la ligne du questionnaire en `3`.

## 2. Sources normatives, par ordre d'autorité

1. **Le §7 de ce fichier** — la fiche de ton lot est le contrat.
2. **`docs/backoffice-lots.md`, table du lot 4** — le texte exact de chaque
   exigence `BO-04-01-xx` citée par ta fiche. C'est la formulation de l'AO qui
   fait foi sur *ce qui est demandé*, pas ta reformulation.
3. **`docs/backoffice-cadrage.md` §7.2** — pourquoi ces données n'existent pas,
   et les trois causes distinctes. À lire avant d'écrire.
4. **`CLAUDE.md`** — conventions de code et de test, **sauf** sa clause « ne
   jamais modifier `src/main` », que le §4 ci-dessous remplace.
5. **Les Javadoc du code existant** — elles portent les contrats sémantiques du
   produit (l'entonnoir `syncDraft`, le moment fiscal de `validateTicket`, la
   chaîne de scan, `PosState` racine de composition, l'outbox). Les relire
   prime sur toute supposition.

## 3. Le mécanisme unique — à trouver avant d'écrire une ligne

Le produit a **un seul émetteur d'événements** :
`service/TechnicalEventService.log(EventType, String detail)`. Il crée
l'événement, lui donne son `eventUid`, et l'enfile dans l'outbox **dans la
transaction de l'appelant**. Le transport est déjà écrit de bout en bout :
`SyncOutboxService.prepare` sérialise, `SyncPushService` poste vers
`/api/sync/<type>`, `SyncIngestResource` reçoit sur le nœud magasin,
`SyncIngestService` fait un *upsert* par `uid` — donc rejouable sans doublon.

**Tu n'écris jamais un second chemin.** Un nouvel événement = une valeur de
plus dans `TechnicalEvent.EventType` et un appel à `log(...)`. Un nouvel objet
persisté = une valeur de plus dans `SyncOutbox.EntityType`, un DTO dans
`SyncPayloads`, un `case` dans `prepare` et `toDto`, une route dans
`SyncIngestResource`, un *upsert* dans `SyncIngestService`. Rien de plus.

## 4. Règle de portée — elle remplace celle du CLAUDE.md

Cette campagne **construit**, et elle construit sur la surface de vente. La
frontière se déplace, elle ne disparaît pas.

- Tu ne traites **que** les exigences de ton lot.
- Tu ne renommes pas, ne réorganises pas, ne refactores pas ce que tu croises.
  Une amélioration hors périmètre se **signale en une ligne** dans le rapport,
  elle ne s'applique pas.
- **Tu ne tranches aucun arbitrage produit.** Si une exigence suppose une
  décision non écrite (une règle métier, une frontière entre applications, un
  choix de modèle), tu ne l'implémentes pas : tu la listes en arbitrage et tu
  passes à la suivante.
- Tu ne modifies jamais le questionnaire `.xlsx`.
- Tu ne touches pas au back-office au-delà du critère de journal de ton lot.

## 5. Invariants du produit — ils priment sur toute exigence de l'AO

Si une exigence semble en demander la violation, tu t'arrêtes et tu le
signales.

- **Les totaux d'un ticket ne changent pas.** Aucun lot de cette campagne ne
  déplace un centime. Tout lot qui touche à la ligne de ticket doit le
  **prouver par un test** : mêmes totaux HT, TTC et TVA avant et après.
- **Le moment fiscal est `validateTicket`**, et l'ordre de signature est un
  contrat. Avant de toucher à la collection de lignes, **lis ce que la
  signature couvre réellement** et écris-le dans ton rapport.
- **Une caisse coupée du réseau continue de vendre.** Tout nouvel objet part
  par l'outbox, jamais par un appel synchrone dans le fil de la vente.
- **Toute ligne de ticket porte un EAN.**
- **Un exécutable = une caisse, une base par caisse.**
- **État local, jamais transporté par le tirage** : `failedAttempts`,
  `lockedUntil`, `bo_password`, `must_change_password`. Si ton lot ajoute un
  champ, décide explicitement de son côté de la frontière et écris-le dans la
  Javadoc de l'entité.
- **Le tirage référentiel prend un snapshot** : une mise à jour en cours de
  journée n'impacte pas une vente ouverte.

## 6. Ordre de travail d'une session

0. **Si le travail du lot est déjà présent dans l'arbre** (session précédente
   interrompue, ou correction appliquée à la main), tu ne le reconstruis pas et
   tu ne le remanies pas : tu le vérifies ligne à ligne contre la fiche, tu
   complètes ce qui manque, et tu laisses le reste tel quel.
1. Lire la fiche du lot au §7, puis le texte des exigences dans
   `docs/backoffice-lots.md`.
2. Lire le code existant cité par la fiche — c'est la partie que les campagnes
   précédentes ont le plus bâclée.
3. **Énumérer les embranchements avant d'écrire**, exigence par exigence.
4. Construire : gisement, transport, puis critère de journal.
5. Écrire les tests unitaires au fil de l'eau, pas à la fin.
6. `mvn -Dtest=<Classes> test` jusqu'au vert, puis `mvn verify`, puis lire le
   rapport JaCoCo des classes du lot et combler les branches manquantes.
7. Rapport.

## 7. Les lots

### Lot C1 — Événements de sécurité opérateur

**Exigences** : `BO-04-01-27`, `28`, `29`, `30` (4 lignes).
**Coût** : le plus faible des cinq. Aucune entité, aucun transport à écrire.

Existant à lire d'abord : `ui/LockCheckFilter` (la pause automatique existe
déjà, pilotée par `pos.auth.idle-lockout-seconds`), `ui/auth/AuthService`
(échec de PIN et verrouillage — `AUTH_LOCKED` est déjà émis),
`ui/admin/AdminAuthResource` (changement du mot de passe back-office).

Travail :
1. Ajouter à `TechnicalEvent.EventType` les valeurs manquantes —
   verrouillage/pause, changement de mot de passe, échec de mot de passe.
   Javadoc sur chaque constante, dans le style des vingt existantes.
2. Émettre par `TechnicalEventService.log` aux points ci-dessus. Le `detail`
   porte ce qui rend l'événement exploitable (l'identifiant de l'opérateur,
   jamais un mot de passe ni un hash, même partiel).
3. Vérifier que `ui/journal/EventTypes` expose les nouvelles valeurs sans
   modification — si ce n'est pas le cas, c'est là que tu corriges.
4. Critère de journal : la recherche par plage de N° caissière sur l'onglet
   fonctionnel. Regarde si `JournalCriteria` la porte déjà pour l'onglet
   transactionnel et réutilise, ne duplique pas. **Le badge a sa propre
   colonne** `TechnicalEvent.operatorBadgeId`, portée par le transport : le
   critère compare des badges à des badges. Comparer une plage à la colonne
   `detail`, qui est du texte libre pour les seize autres types d'événement,
   rendrait des lignes fausses sans le dire — c'est un défaut, pas un
   raccourci.

Piège : n'écris jamais un mot de passe, même erroné, même tronqué, dans le
`detail` d'un événement. Le journal est consultable par tout MANAGER.

### Lot C2 — Traces monétique

**Exigences** : `BO-04-01-08`, `47`, `48`, `49` (4 lignes).

Existant à lire d'abord : `domain/ticket/CardPayment` (il ne porte aujourd'hui
que le montant), le paquet `ui/hardware/terminal` en entier —
`PaymentTerminalClient`, `DegradedModePaymentTerminalClient` et
`TerminalClientProducer`, qui décide lequel est injecté —, `SyncPayloads.PaymentDto`,
et le simulateur `docs/simulateur.html` pour la partie TPE virtuel.

Travail :
1. Ajouter à `CardPayment` le numéro d'autorisation et l'indicateur de mode
   dégradé. Décide et documente : ces champs sont-ils portés par le
   transport ? (oui — ils décrivent la vente, pas l'état de la caisse).
2. Les remplir depuis la réponse du terminal. Le mode dégradé est **déjà connu
   du code** : `DegradedModePaymentTerminalClient` sait qu'il l'est. C'est
   `BO-04-01-49` servi sans matériel.
3. Faire rendre un numéro d'autorisation au simulateur.
4. Porter les deux champs dans `PaymentDto` et l'ingestion.
5. Critères de journal : recherche par numéro d'autorisation, et les deux
   drapeaux de mode dégradé.

Piège : un vrai terminal n'est pas branché. Tu sers ce que la caisse **sait
aujourd'hui** ; ce qui dépend réellement d'un serveur monétique secondaire
reste en `3` et se dit dans le rapport, sans habillage.

### Lot C3 — Nomenclature snapshotée sur la ligne

**Exigences** : `BO-04-01-11`, `23` (2 lignes).

Existant à lire d'abord : `domain/ticket/TicketLine` (elle recopie déjà
libellé, EAN, PLU et prix au moment de la vente), la création de ligne dans
`ui/ticket/TicketService`, `SyncPayloads.LineDto`.

Travail :
1. Ajouter à `TicketLine` le code et le libellé de nomenclature.
2. Les remplir **à la création de la ligne**, depuis le produit, exactement
   comme le prix l'est déjà.
3. Les porter dans `LineDto` et l'ingestion.
4. Critère de journal : recherche par nomenclature.

Piège, et c'est la raison d'être du lot : le référentiel change. La ligne doit
garder ce qui était vrai **au moment de la vente**, jamais rejouer le
référentiel d'aujourd'hui. Un test doit le démontrer : la famille du produit
modifiée après la vente ne change pas la ligne consolidée.

### Lot C4 — Lignes annulées conservées

**Exigences** : `BO-04-01-16`, `19` (2 lignes).
**C'est le lot le plus risqué de la campagne malgré sa taille.**

Existant à lire d'abord : la suppression de ligne dans `ui/ticket/TicketService`,
le calcul des totaux, l'impression du ticket, l'écran client, et **la
signature fiscale**.

Travail :
1. **D'abord** : établir ce que couvre la signature fiscale. Si elle porte sur
   la collection de lignes, tu t'arrêtes et tu remontes l'arbitrage — on ne
   change pas une chaîne de signature dans une session de campagne.
2. Ajouter à `TicketLine` le marquage d'annulation (drapeau, horodatage,
   auteur).
3. Marquer au lieu de retirer. Exclure les lignes marquées des totaux, du
   ticket imprimé, de l'écran client et de tout ce qui compte des articles.
4. Porter le marquage dans `LineDto` et l'ingestion.
5. Critère de journal : recherche des tickets portant une annulation d'article,
   avec plage de PLU et plage de montants (lis le texte de `BO-04-01-16`, il
   est précis sur les deux plages).

Piège : chaque endroit qui itère `ticket.lines` est un endroit à examiner. Fais
la liste avant de modifier, mets-la dans le rapport.

### Lot C5 — Mouvements de caisse, découpé en C5a / C5b / C5c

**Exigences** : `BO-04-01-12`, `33`, `35`, `36`, `37`, `40`, `44` (7 lignes),
**servies par le seul C5c** — les deux premiers lots construisent ce qu'il lira.
C'est le plus gros chantier de la campagne, et le seul qui ouvre aussi le lot
conditionnel D (coffre).

Le découpage suit la nature du travail, pas le nombre d'exigences : une donnée
qui n'existe pas, un geste qui ne se fait pas, une lecture qui n'a rien à lire.
Chacun tient dans une session et se commite seul.

**Le décompte ne se répartit pas.** C5a et C5b servent **zéro** exigence et le
disent : leur rapport porte `1 : 0 / 1a : 0 / 3 : 7`, sans détour. Annoncer une
demi-exigence parce que la moitié du chemin est faite est exactement la faute
que le §1 interdit. Les sept lignes basculent d'un coup en C5c, ou elles ne
basculent pas.

Existant à lire d'abord, pour les trois : `domain/CashSession` — elle porte
cinq **montants** (fond initial, compté, théorique, écart, prélevé) et **aucun
mouvement** ; `service/CashSessionService` et son calcul du théorique ;
`ui/endorsement/EndorsementService` pour l'aval manager ;
`service/PosSettingsService` et son catalogue typé.

#### Lot C5a — L'objet et son transport

Travail :
1. Créer l'entité de mouvement dans `domain/` : uid, TPV, session, caissière,
   type (prélèvement, apport, dépense, acompte, déclaration), montant, motif,
   horodatage, aval éventuel.
2. Créer son service dans `pos.service` : enregistrement, et exigence d'aval
   au-delà d'un seuil (le seuil arrive en C5b ; ici, une valeur par défaut
   documentée suffit, et le service ne lit aucun écran).
3. Transport complet : valeur dans `SyncOutbox.EntityType`, DTO dans
   `SyncPayloads`, `case` dans `prepare` et `toDto`, route dans
   `SyncIngestResource`, *upsert* par uid dans `SyncIngestService`.

Piège : l'ordre de poussée de l'outbox est significatif (la session part avant
les tickets, l'événement en dernier). Place le mouvement en connaissance de
cause — il référence une session — et justifie la place choisie dans le
rapport.

Critère de fin propre au lot : aucun écran, aucun critère de journal, aucune
exigence servie. Un mouvement créé par le service part au nœud magasin et y
est ingéré deux fois sans doublon — c'est ce que tes tests doivent démontrer.

#### Lot C5b — Le geste et son paramétrage

Travail :
1. Ajouter la fonction de caisse et sa route dans `ui/cash`, avec son template,
   et l'aval manager au-delà du seuil.
2. Ajouter au catalogue de `PosSettingsService` le seuil d'aval et les motifs
   autorisés — **par le catalogue typé et le domaine `SETTINGS` du tirage**,
   jamais par une clé de configuration en dur. C'est ce qui servira
   `BO-04-01-44` en C5c.
3. **Corriger le montant théorique de la session** pour intégrer les
   mouvements.

Piège, et c'est le vrai risque du chantier : le point 3 change l'écart de
clôture. Sans lui, l'écart devient faux le jour où la fonction existe ; avec
lui, mal fait, il devient faux tout de suite. Un test doit démontrer que le
théorique d'une session **sans aucun mouvement** est strictement inchangé, et
un autre qu'un prélèvement puis un apport de même montant se compensent
exactement.

#### Lot C5c — La lecture

Travail :
1. Champs de critères dans `JournalCriteria`, clauses dans `JournalService` :
   un critère par type de mouvement, plus la déclaration de comptage.
2. L'affichage correspondant dans l'onglet du journal.

Piège : les mouvements ne sont pas des tickets. Décide où ils se cherchent —
un troisième onglet, ou des lignes dans l'onglet fonctionnel — et justifie le
choix dans le rapport ; ne les greffe pas sur la recherche transactionnelle,
dont chaque clause suppose un `Ticket`.

C'est ce lot qui fait basculer les sept exigences. S'il en laisse une en `3`,
il le dit et il dit laquelle.

## 8. Ce qui n'est PAS dans cette campagne

- **Self-scanning** (`BO-04-01-41/42/43`) : le canal n'existe pas. Ces lignes
  arriveront avec son intégration, jamais avant.
- **Vérification de prix** (`BO-04-01-38`) : la fonction n'existe pas en
  caisse. C'est un écran de vente à concevoir, pas un gisement à ouvrir.
- **Entité facture** (`BO-04-01-53`) : territoire du lot conditionnel A.
- Les **scénarios e2e** : campagne séparée.
- La campagne back-office (`backoffice-campaign.md`) et ses lots 0 à 10.

## 9. Conventions de code

- **Anglais** pour tout le code et tous les commentaires.
- **Javadoc sur chaque méthode, sans exception** : getters, setters,
  constructeurs, méthodes privées, callbacks de cycle de vie, redéfinitions.
  Javadoc sur chaque champ d'entité et chaque constante d'énumération.
- **Placement des paquets** : dans `pos.service`, uniquement les services qui
  servent d'autres services ; ce qui ne sert que la couche IHM va dans
  `ui.<sous-paquet>`, jamais directement sous `ui`. **Jamais de paquet à
  classe unique.**
- **Écrans de vente** : le style existant fait foi, on n'invente pas une aire.
  **Back-office** : tout écran passe par `{#include admin-layout}`,
  `admin/base.css` reste intouchée, le spécifique va dans la feuille de l'aire,
  aucun bloc `<style>` dans un template, jetons `--im-*` uniquement.
- **`application.properties` est en ISO-8859-1** : aucun caractère non-ASCII
  dans les nouvelles clés ni dans leurs commentaires.
- **Un paramètre administré passe par le catalogue typé de
  `PosSettingsService` et le domaine `SETTINGS` du tirage.**
- `BigDecimal` pour toute somme, échelle 2. Logger JBoss, jamais `System.out`.
  Jamais de collection nulle : `List.of()`.

## 10. Conventions de test

- **JUnit 5 + Mockito, tests unitaires purs.** Jamais `@QuarkusTest` pour un
  test de classe, jamais H2, jamais de démarrage d'application.
- Les entités Panache ne sont pas bytecode-enhanced sous `mvn test` : les
  finders statiques se mockent par `mockStatic(PanacheEntityBase.class)` et
  `persist()` se neutralise par `mockConstruction(<Entity>.class)`.
- `QuarkusTransaction` se mocke par
  `mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)` en
  exécutant le `Runnable`/`Callable` reçu — **patron exact dans
  `FidEventOutboxServiceTest`**, à copier plutôt qu'à réinventer.
- Tous les mocks statiques en try-with-resources.
- **Assertions `org.junit.jupiter.api.Assertions` uniquement**, jamais AssertJ.
- Valeurs absolues attendues, aucune dépendance d'ordre, aucun état partagé.
- **Chaque jambe de chaque expression booléenne composée** a son cas de test
  (`a || b || c` = trois cas), et **les deux bras** de chaque ternaire et de
  chaque garde de nullité. C'est le point sur lequel les campagnes précédentes
  ont le plus dérapé : passe-les mentalement une par une avant de livrer.
- Pas de ligne vide dans un corps de méthode. Javadoc sur chaque test et
  chaque helper.
- **100 % de branches JaCoCo** sur les classes créées par le lot. Tout résidu
  se justifie ligne à ligne dans le rapport.
- La couverture est désormais **mesurée par le build** : `mvn verify` attache
  l'agent JaCoCo aux tests unitaires, écrit `target/site/jacoco/`, et échoue
  sous 0,95 de branches / 0,96 de lignes sur l'ensemble. Tu lis ce rapport, tu
  ne l'estimes pas ; et tu ne touches jamais aux seuils du `pom.xml` pour faire
  passer un build.

### Tests propres à cette campagne — ils s'ajoutent, ils ne remplacent rien

- **Invariance des totaux** : pour tout lot touchant la ligne de ticket (C3,
  C4), un test qui pose un ticket, applique la modification du lot, et vérifie
  l'égalité stricte des totaux HT, TTC et TVA.
- **Le snapshot est un snapshot** (C3) : modifier le référentiel après la vente
  ne change pas la ligne consolidée.
- **Émission par le mécanisme unique** : chaque nouvel événement se teste en
  vérifiant l'appel à `TechnicalEventService.log` avec le type et le détail
  attendus — pas en inspectant la base.
- **Transport** (C2, C5) : un test de sérialisation du DTO et un test de
  l'*upsert* d'ingestion, y compris le second passage du même uid, qui ne doit
  créer aucune ligne.
- **Idempotence et hors ligne** : aucun nouvel appel réseau synchrone dans le
  fil de la vente. Si ton lot en ajoute un, il est faux.

## 11. Critère de fin

- `mvn verify` vert.
- 100 % de branches sur les classes nouvelles, ou un résidu justifié.
- **Le décompte** : pour chaque exigence du lot, dire si elle passe en `1`,
  reste en `1a` ou reste en `3`, avec la raison. Une exigence dont le gisement
  est écrit mais dont le critère de journal manque **reste en `3`**.
- Tu n'annonces jamais couverte une exigence que rien ne démontre.

## 12. Rapport de fin de session — format imposé

```
Lot <C n> — <titre>
Exigences du lot : <total>  →  1 : <n>   1a : <n>   3 : <n>
Fichiers créés   : ...
Fichiers modifiés: ...
Couverture       : <Classe> <branches couvertes>/<branches totales>  (une ligne par classe nouvelle)
Itérations mvn   : <n>
Totaux           : invariance vérifiée par <test>  |  sans objet
Signature        : ce que couvre la signature fiscale, constaté dans le code
Résidus          : une ligne par résidu, avec sa raison
Arbitrages       : une ligne par décision à rendre
Hors périmètre   : une ligne par amélioration repérée et NON appliquée
```

## 13. Git

- **Un commit par lot**, message **exactement** `feat(caisse): lot C<n>` —
  sans titre ni suffixe : le script de campagne le compare caractère pour
  caractère, c'est lui qui décide si le lot est passé.
- **Jamais de push. Jamais de branche.**
- Le commit est le seul juge : pas de commit, pas de lot. Le code de retour de
  l'agent ne décide de rien.

---

*Campagne établie le 01/09/2026. Elle consomme les gisements du §7.2 de
`docs/backoffice-cadrage.md` ; le texte des exigences est la table du lot 4 de
`docs/backoffice-lots.md`.*
