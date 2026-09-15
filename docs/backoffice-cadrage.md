# Back-office — cadrage du chantier

Source : questionnaire AO Intermarché, onglet **Back Office**, 796 exigences
(dont **704 en P1**), colonne L renseignée le 31/08/2026.
Périmètre de réponse : la suite telle qu'elle existe aujourd'hui —
**impos** (caisses + nœud magasin), **imvaluation** (moteur de valorisation
et son administration), **imfid** (fidélité et son administration).

---

## 1. Doctrine de cotation

Cet onglet décrit de l'**administration** : l'exigence *est* le paramétrage.
La colonne L a donc été renseignée ainsi :

| Réponse | Critère |
|---|---|
| `1. Couvert intégralement` | le paramétrage existe dans un back-office et atteint les caisses |
| `1a. Couvert partiellement` | un mécanisme réel couvre une part : le comportement existe et n'est administrable qu'en partie, **ou** la donnée et la plomberie existent mais l'écran manque, **ou** c'est administré par une autre porte (import CSV, GraphQL) |
| `3. Non couvert non prévu` | ni le paramétrage ni le comportement |

Conséquence assumée : un comportement correct mais **figé dans le code** ne
vaut pas « couvert » sur cet onglet. C'est ce qui explique l'écart avec
l'onglet Ligne de caisses (149 / 114 / 467 sur 730), où l'exigence était le
comportement lui-même.

---

## 2. Le décompte

| Epic | Total | dont P1 | `1` | `1a` | `3` |
|---|---:|---:|---:|---:|---:|
| 01 — Connexion | 25 | 22 | 0 | 10 | 15 |
| 02 — Référentiels | 72 | 56 | 0 | 18 | 54 |
| 03 — Paramétrage | 297 | 267 | 1 | 44 | 252 |
| 04 — Utilitaires | 71 | 63 | 0 | 11 | 60 |
| 05 — Financier | 23 | 23 | 0 | 2 | 21 |
| 06 — Rapports | 31 | 19 | 0 | 3 | 28 |
| 07 — e-Commerce | 7 | 7 | 0 | 0 | 7 |
| 08 — Supervision | 46 | 39 | 0 | 14 | 32 |
| 09 — Fin de journée | 19 | 14 | 0 | 1 | 18 |
| 10 — Règles | 171 | 162 | 2 | 30 | 139 |
| 11 — Flux sortants | 34 | 32 | 0 | 10 | 24 |
| **TOTAL** | **796** | **704** | **3** | **143** | **650** |

Les trois seules couvertures intégrales :

- **BO-03-07-04** — seuil maximum des remises/rabais : `discount.line-max-percent`
  et `discount.global-max-percent` administrés dans `/admin/settings`, appliqués
  par `TicketService`, distribués par le tirage référentiel.
- **BO-10-02-27** — délai d'inactivité avant verrouillage :
  `auth.idle-lockout-seconds` administré, appliqué par `LockCheckFilter`.
- **BO-10-08-25** — les mises à jour articles/promotions en cours de journée
  n'impactent pas les ventes en cours : le tirage prend un snapshot, invariant
  déjà couvert par un scénario e2e.

Toutes trois sont nées du même chantier : le back-office de paramétrage livré
le 28/08. C'est le seul endroit de la suite où le triptyque
**catalogue typé → écran → distribution** existe réellement.

### Sous-ensembles à couverture nulle (22 blocs, 265 exigences)

`01-04 Gestion fonctions` (6) · `02-02 Référentiel TVA` (3) ·
`02-04 Référentiel clients` (22) · `03-03 Template des zones` (35) ·
`03-04 Assemblage des templates` (26) · `03-05 Personnalisation des documents` (8) ·
`03-11 Provider` (6) · `04-01 Journal électronique` (46) · `05-01 Fiscal` (6) ·
`06-01 Gestion commune des rapports` (4) · `06-03 Rapports clients` (5) ·
`06-07 États TVA` (3) · `06-08 États Offres` (2) · `07-01 e-Commerce` (7) ·
`09-01 Fin de journée automatique` (8) · `09-02 Fin de journée manuelle` (4) ·
`10-01 Omnicanalité` (36) · `10-04 Client` (19) · `10-05 Balance` (4) ·
`10-06 Fiscal` (6) · `10-09 GS1` (3) · `11-03 À la demande` (4).

---

## 3. Le constat structurant

Ce que l'AO appelle « back-office » et ce que la suite appelle « back-office »
ne sont **pas le même objet**.

L'AO décrit une **administration centrale multi-échelons** : comptes nominatifs
du Groupement, profils avec droits unitaires par fonctionnalité et par échelon,
paramètres par défaut au niveau pays et enseigne dont les PDV héritent,
déploiement *programmable* de configurations partielles ou complètes vers une
liste de PDV (BO-03-12-03/04), et vue centrale des PDV ayant personnalisé un
paramètre donné (BO-03-12-07).

`/admin/settings` est l'exact inverse : un écran de **nœud**, 8 paramètres,
portée décidée par la topologie (administré sur le nœud magasin il s'applique
au magasin, édité sur une caisse isolée il s'applique localement). Sans
identité, il ne peut de toute façon porter aucune des exigences de l'AO, qui
disent toutes « un administrateur **ou un compte avec le niveau hiérarchique
nécessaire** ».

Deuxième constat, opérationnel : la suite est **riche en données et pauvre en
écrans**. Le journal électronique (46 P1, zéro couverture) est l'illustration
parfaite — tickets, lignes, paiements, remboursements et `TechnicalEvent` sont
déjà persistés, consolidés sur le nœud magasin par un outbox transactionnel, et
il ne manque qu'un écran de recherche.

---

## 4. Les socles réels sur lesquels bâtir

| Socle existant | Ce qu'il porte déjà | Ce qu'il faut en faire |
|---|---|---|
| `PosSettingsService` + domaine SETTINGS du tirage | catalogue typé de 8 paramètres, cache invalidé, distribution aux caisses | le catalogue complet, hiérarchisé par échelon, avec héritage et surcharge |
| `AppUser` / `UserUiResource` (imvaluation) | comptes en base, rôles, bootstrap, mot de passe imposé, réinitialisation | le rattachement aux échelons et les profils composables |
| `Employee` (impos) | `loginName`, PIN bcrypt, mot de passe back-office, `getRoles()`, `isEnabled()`, verrouillage PIN | *fait au lot 0* : deux providers d'identité maison lisent l'enum de rôle directement — `quarkus-security-jpa` exige une colonne texte que l'entité n'a pas |
| `StoreGroup` + workbench (imvaluation) | hiérarchie de groupes de magasins, édition directe glisser-déposer | le modèle Pays → Enseigne → PDV → îlot |
| Outbox de consolidation + `RefState` | remontée transactionnelle avec reprise, empreintes par domaine | le journal électronique et les consoles d'intégration |
| Machinerie d'import CSV (les trois applications) | fallback étagé, rapport JSON, idempotence par checksum | les référentiels de l'AO, et les consoles de contrôle d'intégration |
| Dashboard + appels superviseur | agrégats du jour, bandeaux acquittables temps réel | la supervision configurable et la validation/refus depuis le BO |

---

## 5. Plan de développement

### 5.0 État à date (31/08/2026)

Le **lot 0 est écrit** et attend sa première compilation : providers d'identité
maison, mot de passe back-office distinct du PIN, changement imposé,
`/admin/*` et `/dashboard` fermés, surfaces machine en HTTP Basic. Rien n'a
encore été compilé ni démarré. Tant que ce lot n'est pas vert, aucun autre
ne commence : ils s'appuient tous sur l'identité.

### 5.1 Principes de conduite

- **Un lot = un chantier livré, testé, commité.** Pas de lot ouvert en
  parallèle d'un autre : ils se recouvrent tous sur le paramétrage et
  l'identité.
- **Critère de fin uniforme** pour chaque lot : `mvn verify` vert, tests
  unitaires des classes nouvelles à 100 % de branches (la règle du
  CLAUDE.md, jambe par jambe), un groupe de scénarios e2e joué au
  navigateur, et le décompte des lignes AO qui basculent effectivement en
  colonne L — le questionnaire est le juge, pas l'impression de progrès.
- **Le catalogue e2e précède le code.** Comme pour la caisse : on écrit les
  scénarios du lot dans `e2e-scenarios.md` avant de l'ouvrir, sinon on
  teste ce qu'on a écrit au lieu de ce qui était demandé.
- **La taille est donnée en sessions**, pas en jours-homme : une session est
  un chantier mené du catalogue e2e au commit. C'est une mesure de cette
  méthode de travail, pas une estimation d'appel d'offres.

### 5.2 Le jalon qui commande tout : la brique centrale

L'AO décrit **trois étages** — brique centrale, point de vente, point de
contact d'encaissement — et l'essentiel de l'epic 03 en dépend :
paramètres par défaut au niveau pays et enseigne dont les PDV héritent,
déploiement programmable vers une liste de PDV, vue centrale des PDV ayant
personnalisé un paramètre.

La suite en a **deux** : caisse et nœud magasin.

**Décision rendue le 01/09/2026 : route A.** Ce qui suit garde les deux
options parce que le choix se comprend mieux avec ce qu'on a écarté, mais la
route A n'est plus une recommandation, c'est la route. Aucun lot n'a à
l'arbitrer, aucun n'a à s'en dispenser.

- **A — Un troisième rôle.** `pos.role=central` à côté de `register` et
  `store`, le même exécutable, et le tirage référentiel existant devient à
  deux niveaux : central → magasin → caisse. C'est le patron déjà éprouvé
  (le nœud magasin est déjà « la même application en rôle store »), le
  tirage par empreinte SHA-256 par domaine se chaîne naturellement, et
  `PosSettingsService` n'a qu'à apprendre l'héritage. **Route retenue.**
- **B — Deux étages assumés (écartée).** On ne construit pas de brique centrale et on
  répond partiellement à l'epic 03-12 et au 02-05. Économie réelle, mais
  elle plafonne la réponse à l'AO et rend les lots 1 à 3 bancals : sans
  échelon au-dessus du magasin, « paramètre par défaut de l'enseigne » n'a
  pas de porteur.

Le reste du plan suppose la route A, et c'est désormais sans réserve.

### 5.2.1 Spécification de la route A

Écrite le 01/09/2026, après la première session du lot 1, qui a livré le modèle
d'échelons et la résolution mais **pas** la distribution. Cette section dit ce
que la seconde session doit construire, et les cinq questions qu'elle ne doit
pas trancher au jugé.

**Le rôle.** `pos.role=central`, troisième valeur à côté de `register` et
`store`, dans le même exécutable. Ce que le rôle décide n'est pas un jeu
d'écrans : c'est la **surface exposée**. Un nœud central expose l'export
référentiel et les écrans d'échelon ; il n'a ni caisse, ni tiroir, ni session.
Le rôle est le seul discriminant — pas une liste de chemins autorisés, dont
`LockCheckFilter` a déjà montré la fragilité.

**La précédence, de la plus forte à la plus faible.** Surcharge locale du nœud
(`pos_settings`) > valeur d'échelon PDV > enseigne > pays > défaut du catalogue
typé. C'est exactement l'ordre que `PosSettingsService` applique déjà en
mémoire depuis le lot 1 ; la distribution ne doit pas l'altérer, seulement le
transporter. **Corollaire non négociable** : une valeur posée localement dans
un magasin survit à tout tirage — les deux vivent dans des tables différentes,
et c'est ce qui protège une caisse qui a une bonne raison de diverger.

**Ce que le magasin ré-expose à ses caisses : la valeur effective, pas la
chaîne.** Le nœud magasin résout l'héritage pour son propre PDV, puis exporte
dans le domaine `SETTINGS` le résultat — une clé, une valeur — comme il le fait
déjà aujourd'hui. Une caisse n'apprend jamais qu'il existe des échelons : elle
reçoit des paramètres. Deux raisons : le tirage par empreinte SHA-256 par
domaine continue de fonctionner sans changement de forme, et une caisse coupée
du magasin garde le dernier jeu appliqué, qui est déjà la valeur effective.
Le seul changement côté export est donc que `RefExportService` publie les
valeurs résolues au lieu des lignes brutes de `pos_settings`.

**La date d'effet est portée par la donnée, jamais par une poussée.** Une
valeur d'échelon porte sa date de prise d'effet ; elle est distribuée dès
qu'elle est saisie et **s'applique toute seule** le jour venu, sur chaque nœud.
On ne programme pas un envoi, on date une donnée. C'est ce qui rend le
déploiement programmable (`BO-03-12-03/04`) réalisable sans ordonnanceur
central, et ce qui le rend correct quand un magasin était éteint le jour J.

**Quand le central est injoignable, il ne se passe rien** — et c'est le
comportement attendu, pas une dégradation. Le magasin garde son dernier
snapshot appliqué, les caisses le leur, et le cycle suivant rattrape. Le
tirage est déjà bâti sur cette propriété entre magasin et caisse ; l'étage
central n'y ajoute qu'un maillon. Aucun nœud central ne devient un point de
passage obligé de la vente : c'est l'invariant du produit, et la route A n'a
le droit d'exister qu'à cette condition.

**Ce qui reste à construire pour le lot 1 (seconde session)** :

1. `pos.role=central` et la surface qu'il expose.
2. `Country`, `Enseigne`, `Pdv` et `EchelonSetting` dans les domaines du
   tirage — aujourd'hui aucun n'y figure, c'est ce qui rend les quatre lignes
   `BO-02-05-*` inatteignables.
3. `RefExportService`, côté magasin, publiant les valeurs **effectives** du
   domaine `SETTINGS`.
4. Le tirage à deux niveaux central → magasin → caisse, qui est le tirage
   existant chaîné, pas un mécanisme neuf.
5. La date d'effet sur la valeur d'échelon.

Critère de fin, inchangé et vérifiable : un paramètre posé au niveau enseigne
se retrouve sur une caisse de deux PDV différents sans avoir été touché
localement, et la surcharge d'un PDV survit au tirage suivant.

### 5.3 Les lots

Chaque lot indique ce qu'il construit, le socle qu'il réutilise, son critère
de fin et ce qu'il fait basculer dans le questionnaire.

> **Le détail ligne à ligne est dans `backoffice-lots.md`** : les 796
> exigences ventilées une par une, chacune avec sa priorité, son état actuel
> en colonne L et l'attendu du questionnaire. C'est ce fichier-là qu'on donne
> à un agent, un lot à la fois ; celui-ci porte le raisonnement.

---

**Lot 0 — Identité et accès** · *écrit, à valider* · 1 session restante

*Construit :* deux providers d'identité lisant la table `employees`, mot de
passe back-office distinct du PIN, changement imposé à la première
connexion, `/admin/*` et `/dashboard` sous `@RolesAllowed`, surfaces machine
en HTTP Basic, écrans de connexion et de mot de passe.

*Reste à faire :* la compilation, les tests unitaires des quatre classes de
`pos.security`, et un groupe e2e dédié — connexion nominale, refus du PIN à
la porte du back-office, refus d'un caissier sans mot de passe, changement
imposé, `401` sur un import anonyme, `200` avec Basic.

*Critère de fin :* les 31 `@RolesAllowed` aujourd'hui inertes prouvées
vivantes par un test qui échoue si on retire l'extension.

*Bascule :* epic 01 pour l'essentiel, et surtout le préalable de tous les
autres lots.

---

**Lot 1 — Les échelons** · 2 à 3 sessions

*Construit :* le modèle Pays → Enseigne → PDV → îlot → point de contact ;
le rôle `central` et le tirage à deux niveaux ; le rattachement des comptes
à un ou plusieurs échelons ; l'héritage des paramètres et la surcharge
locale.

*Socle :* `StoreGroup` et son atelier glisser-déposer côté imvaluation
(hiérarchie de groupes de magasins, édition directe) ; `RefState`, les
empreintes par domaine et `RefPullService` pour le chaînage.

*Critère de fin :* un paramètre posé au niveau enseigne se retrouve sur une
caisse de deux PDV différents sans avoir été touché localement, et la
surcharge d'un PDV survit au tirage suivant.

*Bascule :* `02-05` en entier, `03-12-03/04/07`, `01-01-03`, `01-03-04`.

*Risque :* c'est le lot qui touche l'invariant « un exécutable = une
caisse ». Le rôle central ne doit jamais devenir un point de passage
obligé : une caisse coupée du monde continue de vendre, c'est non
négociable.

---

**Lot 2 — Profils et droits unitaires** · 2 sessions

*Construit :* le profil comme ensemble de (fonctionnalité × option
visualisation/création/modification/suppression) × échelon, en remplacement
des quatre rôles figés ; l'écran de gestion des profils et celui
d'affectation.

*Socle :* l'écran utilisateurs d'imvaluation pour le gabarit liste/formulaire.

*Critère de fin :* un profil créé dans l'IHM, sans redéploiement, autorise
ou refuse une fonctionnalité précise sur un PDV précis.

*Bascule :* `01-03`, `01-04`.

*Risque :* le lot 0 s'appuie sur `@RolesAllowed`, qui est statique. Passer à
des droits administrés demande une couche de permissions dynamique
(`@PermissionsAllowed` ou une politique HTTP) : c'est une reprise du lot 0,
à faire d'un bloc et pas en cohabitation.

---

**Lot 3 — Paramétrage généralisé (élargissement)** · 2 à 3 sessions ·
*re-taillé le 31/08 après la première session*

*Construit :* le catalogue de `PosSettingsService` s'élargit aux
comportements que la caisse SAIT DÉJÀ FAIRE — une clé, un câblage, un test.
Le tirage est générique : une clé nouvelle ne demande aucune modification de
la plomberie de distribution.

*Correction de dimensionnement.* Ce lot annonçait 136 exigences et « le
meilleur rapport valeur/effort du plan ». C'était faux, et la première
session agent l'a démontré en n'en livrant que 4 — à raison. Elle a posé la
bonne doctrine, qu'on garde : **une clé n'est ajoutée que si elle est câblée
à un comportement existant, jamais de bouton mort.** Or sur les 136 lignes,
seules **59 remplissent cette condition**. Les autres se répartissent en
`3B` — 57 lignes dont le comportement n'existe pas du tout en caisse
(moteur d'alertes et de points de contrôle, référentiel des remises, types
de retour administrables, politique de rétention, internationalisation) — et
`3C`, 20 lignes dont l'objet est construit par un autre lot (modèles de
ticket, moyens de règlement, plages de codes-barres, référentiel articles,
clients en compte).

*Ce que ça change ailleurs que dans le plan :* ces 57 lignes ne sont pas
« du paramétrage qu'on n'a pas encore exposé », ce sont **des fonctions qui
n'existent pas**. Elles quittent le plan back-office et rejoignent les
arbitrages de périmètre du §6.

*Socle :* toute la mécanique existe déjà — catalogue typé, cache invalidé,
domaine SETTINGS du tirage, écran au gabarit.

*Critère de fin :* chaque clé ajoutée est câblée à un comportement caisse et
démontrée par un test. Le décompte du lot compte les lignes qui passent en
`1`, jamais les clés créées.

*Bascule :* **59 lignes**, dont 4 déjà livrées la nuit du 31/08.

*Le détail par nature — quelle ligne va en `3`, en `3B` ou en `3C` — est
dans `backoffice-lots.md`, qui porte les trois tables.*

---

**Lot 4 — Le journal électronique** · 2 à 3 sessions

*Construit :* un écran de recherche multicritères sur le nœud consolidé, le
détail d'un ticket, la séparation journal transactionnel / journal
fonctionnel, l'export.

*Socle :* la donnée est déjà là et déjà consolidée — tickets, lignes,
paiements, remboursements, `TechnicalEvent`, remontés par l'outbox
transactionnel. Ce lot ne produit aucune donnée, il en ouvre la lecture.

*Critère de fin :* les 46 critères de recherche de `04-01` servis, et un
export qui reproduit à l'identique ce que l'écran affiche.

*Bascule :* `04-01` (46 P1), plus une partie de `06`.

*Risque :* aucun sur la donnée ; le vrai sujet est la performance des
recherches libres sur un an de tickets — index à poser dès l'écriture.

---

**Lot 5 — Les référentiels administrés** · 3 sessions

*Construit :* la fiche article complète (données de base, attributs, offres
en cours, historique des prix), la recherche multicritères, l'administration
des attributs, les groupes articles et les touches caisse, plus le
référentiel de TVA et ses motifs légaux.

*Socle :* les écrans liste/formulaire d'imvaluation, sa machinerie d'import
CSV, le GraphQL existant des deux côtés.

*Critère de fin :* une fiche article modifiée dans le back-office change le
comportement de la caisse au tirage suivant, y compris sur un attribut
nouvellement déclaré.

*Bascule :* `02-01`, `02-02`, `02-03`, `03-01` — 66 lignes.

---

**Lot 6 — Supervision et déploiement** · 3 sessions

*Construit :* la configuration de la supervision fonction par fonction, la
validation et le refus des demandes depuis le back-office avec effet temps
réel en caisse, la console d'intégration, le tableau de bord de santé des
caisses.

*Socle :* le dashboard et les appels superviseur acquittables existent ;
`RefState` et les versions par domaine donnent la matière de la console.

*Critère de fin :* un refus prononcé sur le back-office fait apparaître le
message de refus sur la caisse qui attendait, sans rechargement manuel.

*Bascule :* `04-02`, `04-03`, epic `08` — 71 lignes.

---

**Lot 7 — Les modes de règlement** · 4 à 5 sessions

*Construit :* un référentiel administrable de moyens de paiement — création,
plafonds et contrôles associés, autorisations de remboursement et de rendu,
ouverture du tiroir, modèle d'impression — en remplacement des paiements
figés dans le code.

*Critère de fin :* un moyen de paiement créé dans le back-office est
utilisable en caisse au tirage suivant, sans déploiement.

*Bascule :* `03-02` — 47 lignes.

*Risque :* le lot le plus intrusif du plan. Il touche `PaymentService`, la
séquence fiscale et l'impression. À ne pas ouvrir avant que les lots 0 à 3
soient stables.

---

**Lot 8 — Les documents imprimés** · 4 sessions

*Construit :* un moteur de gabarits d'impression — zones, assemblage,
entêtes et pieds, modèles par type de document et par moyen de paiement.

*Critère de fin :* le ticket actuel reproduit à l'identique par un gabarit
administré, prouvé par comparaison du contenu imprimé avant/après.

*Bascule :* `03-03`, `03-04`, `03-05` — 69 lignes.

---

**Lot 9 — Les codes-barres** · 3 sessions

*Construit :* la généralisation de `CouponType` en référentiel de plages —
positions, longueurs, dates, contrôles et actions configurables — les deux
écrans de visualisation des codes émis et scannés, et le ticket balance avec
sa tare (`10-05`), plus les règles GS1 (`10-09`) : ce ne sont que des plages
de code de plus, et les trois lignes GS1 de `03-06` sont déjà dans ce lot.

*Critère de fin :* une plage créée dans le back-office est reconnue au scan
en caisse au tirage suivant, sans redéploiement.

*Bascule :* `03-06`, `10-05`, `10-09` — 71 lignes.

---

**Lot 10 — Parc des caisses et îlots** · 2 à 3 sessions

*Construit :* la configuration des points de contact d'encaissement —
activation d'une caisse, type de terminal, périphériques et leurs pilotes
(tiroir, balance, scanner, afficheurs, imprimantes, monnayeurs, monétique),
entête et logo par caisse — et la configuration groupée par îlot, avec la
règle de priorité caisse sur îlot.

*Socle :* ces réglages existent aujourd'hui en clés de configuration par
nœud (`pos.terminal.id`, `pos.tpe.mode`, l'URL du bus matériel) ; le lot les
fait passer du fichier de propriétés au référentiel administré, par le même
chemin que les paramètres du lot 3.

*Critère de fin :* une caisse déclarée et configurée depuis le back-office
démarre avec ses périphériques sans qu'on touche à son fichier de
propriétés.

*Bascule :* `03-09`, `03-10` — 50 lignes.

*Risque :* la frontière avec la mise en service physique. Certains réglages
(le numéro de terminal, l'URL du bus matériel) doivent rester locaux, sinon
une caisse neuve ne peut pas se présenter au référentiel pour recevoir sa
configuration. Cette frontière est à écrire avant d'ouvrir le lot.

### 5.4 Séquence et cumul

L'affectation ci-dessous est une **partition** de l'onglet : chaque exigence
appartient à un lot et à un seul, et les onze lots plus les sept lots
conditionnels du §6 totalisent exactement les 796 lignes. C'est vérifiable,
et ça doit le rester à chaque révision.

| | Lot | Sessions | Lignes | dont P1 | Cumul |
|---|---|---:|---:|---:|---:|
| 1 | 0 — Identité et accès | 1 | 14 | 14 | 14 |
| 2 | 1 — Échelons | 2-3 | 13 | 13 | 27 |
| 3 | 3 — Paramétrage généralisé (élargissement) | 2-3 | 59 | 52 | 86 |
| 4 | 4 — Journal électronique | 2-3 | 46 | 44 | 132 |
| 5 | 2 — Profils et droits | 2 | 9 | 6 | 141 |
| 6 | 5 — Référentiels administrés | 3 | 66 | 47 | 207 |
| 7 | 6 — Supervision et déploiement | 3 | 71 | 58 | 278 |
| 8 | 10 — Parc des caisses et îlots | 2-3 | 50 | 44 | 328 |
| 9 | 9 — Codes-barres | 3 | 71 | 68 | 399 |
| 10 | 7 — Modes de règlement | 4-5 | 47 | 39 | 446 |
| 11 | 8 — Documents imprimés | 4 | 69 | 67 | 515 |

Les lots 3 et 4 sont remontés juste après les échelons : ils apportent 105
lignes sur des mécaniques déjà éprouvées, et donnent un back-office qui
ressemble à un back-office avant qu'on n'ouvre les chantiers lourds. Le lot
2 (profils) peut glisser tant que les quatre rôles suffisent, mais pas
au-delà du lot 5, sinon les écrans se multiplient sans droits pour les
encadrer. Les lots 7 et 8, les plus intrusifs, ferment la marche.

**28 à 33 sessions pour 515 lignes sur 796**.

Les 20 lignes du `3C` s'ajoutent sans session propre : elles basculent avec
le lot qui construit leur objet. Les 57 du `3B` n'y sont pas et n'y seront
pas — elles sont passées au §6.

## 6. Les lots conditionnels

Les **261 lignes** restantes ne sont pas hors plan : elles sont **hors
décision**. Ce sont les 204 des sept lots ci-dessous, plus les **57 du `3B`**
découvertes en re-taillant le lot 3 — elles ressemblaient à du paramétrage,
ce sont des fonctions de caisse à écrire. Chacune appartient à un
comportement qui n'existe nulle part dans la suite, et qu'on ne commence pas parce qu'il manque un
écran mais parce qu'il faut d'abord dire si on le construit. Elles sont donc
loties comme les autres, avec leur contenu et leur taille, mais leur place
dans la séquence dépend d'un arbitrage.

| Lot | Lignes | dont P1 | Sessions | Blocs | Dépend de |
|---|---:|---:|---:|---|---|
| A — Clients en compte et crédit client | 41 | 38 | 4-5 | `02-04`, `10-04` | — |
| B — Rapports et états | 31 | 19 | 3 | epic `06` | lot 4 |
| C — Flux, connecteurs et providers | 40 | 38 | 4 | epic `11`, `03-11` | lot 1 |
| D — Gestion du coffre | 17 | 17 | 3 | `05-02` | — |
| E — Omnicanalité et e-commerce | 43 | 43 | 5-6 | `10-01`, `07-01` | lot 7 |
| F — Fin de journée pilotée | 20 | 15 | 2-3 | epic `09`, `03-12-02` | lots 1 et 6 |
| G — Fiscal Portugal | 12 | 12 | 3-4 | `05-01`, `10-06` | lot 8 |

**Lot A — Clients en compte et crédit client.** Le référentiel des clients
professionnels (numéro, civilité, coordonnées, SIRET, TVA
intracommunautaire, blocage, plafond de crédit, échéance, encours), le
paiement par crédit client en caisse, la facture et le bon de livraison.
imfid gère des cartes pseudonymes, pas des clients B2B : rien n'est
réutilisable. Touche la caisse autant que le back-office.

**Lot B — Rapports et états.** Les états de ventes, caissières, TVA, offres
et clients, avec l'export Excel/CSV/PDF et la restitution graphique. À
construire après le lot 4, qui aura déjà ouvert la donnée consolidée et posé
ses index : sans lui, chaque état est une requête inventée deux fois.

**Lot C — Flux, connecteurs et providers.** Les flux sortants — chiffre
d'affaires, livre de caisse, transactions, panels, vignettes, factures vers
l'ERP — au fil de l'eau ou programmés, et la déclaration des fournisseurs de
services. L'outbox transactionnel et sa reprise sur erreur sont le socle ;
ce qui manque, c'est la déclaration administrée des destinataires et les
formats attendus par chacun.

**Lot D — Gestion du coffre.** Dépenses, recettes, demandes et réceptions de
monnaie, validation des mouvements, remise en banque, remise au coffre,
inventaire, clôture, salariés, monnayeurs. Le comptage de caisse existe, le
coffre n'existe pas du tout.

**Lot E — Omnicanalité et e-commerce.** La reprise en caisse des paniers
venus d'ailleurs : e-commerce, self-scanning, qbusting, mobilité, commandes
de gestion commerciale, bons de vente. Chacun est un mode de reprise avec sa
règle de prix, son canal transmis au moteur de promotion et son traitement
des articles inconnus ou interdits. Le plus gros des sept, et celui qui
touche le plus la caisse.

**Lot F — Fin de journée pilotée.** L'ordonnancement de la fin de période,
les alertes et comptes à rebours sur caisses ouvertes ou hors ligne, la
fermeture forcée, l'impression automatique des rapports, l'archivage. La
clôture Z existe par caisse, mais elle est manuelle et locale : ce lot la
pilote depuis le back-office, donc après les échelons et la supervision.

**Lot G — Fiscal Portugal.** SAF-T manuel et au fil de l'eau, ATCUD et
QR-code réglementaires, numéro de certification, préfixes et compteurs de
documents FS/FT/NC/RC, éco-taxes. À faire après le lot 8 : ces obligations
sont pour l'essentiel des mentions imprimées, et un moteur de gabarits les
absorbe au lieu de les coder une à une.

Ces sept lots pèsent **24 à 28 sessions**. Menés tous, le questionnaire est
couvert de bout en bout pour **55 à 63 sessions**.

## 7. Les travaux de caisse induits

**81 exigences du questionnaire back-office ne se règlent pas dans le
back-office.** Elles ont l'apparence du paramétrage, mais ce qu'elles
demandent d'administrer n'existe pas encore dans la caisse — soit la
fonction, soit la donnée. Deux campagnes agent l'ont découvert
empiriquement, chacune à son étage, et c'est le résultat le plus utile de
la nuit du 31/08.

Cette section les rassemble parce qu'elles forment un chantier cohérent, à
instruire et à chiffrer du côté caisse, pas du côté administration.

### 7.1 Fonctions absentes — 57 exigences (le lot 3B)

| Chantier | Exigences | Codes |
|---|---:|---|
| Politique de rétention et de purge | 15 | `10-08-02/03/07→18/24` |
| Moteur d'alertes et points de contrôle | 10 | `10-02-01→08`, `10`, `11` |
| Types de retour administrables | 9 | `03-13-01→08`, `10` |
| Référentiel des remises et rabais | 7 | `03-07-02/03/05/06/07/08/09` |
| Règles de saisie et de sécurité caisse | 7 | `10-02-13/15/16/17/24/26/32` |
| Divers (diffusion de messages, fidélité, impressions) | 7 | `03-08-04/08`, `10-03-09/14/17`, `10-07-09/20` |
| Internationalisation | 2 | `10-07-17/18` |

Les trois premiers sont de vrais chantiers, pas des ajouts. **Le moteur
d'alertes et de points de contrôle** est le plus structurant : l'AO veut
qu'on associe à n'importe quelle fonction de caisse une alerte de type
information, avertissement, blocage, ou blocage levable par un superviseur.
La caisse a aujourd'hui l'avenant superviseur pour les gestes de prix, et
rien d'autre — c'est un mécanisme à généraliser, et il conditionne aussi
une partie du lot 6.

### 7.2 Données absentes — 24 exigences (le plafond du journal)

Le lot 4 a servi 20 des 46 critères de recherche du journal électronique.
Les 24 restants ne manquent pas d'écran : **ils manquent de gisement**. Le
plafond du journal est fixé par ce que la caisse persiste, pas par ce que
le back-office sait lire.

**Pourquoi ces données n'existent pas.** Aucune n'est un oubli. Trois causes
distinctes, et elles n'ont pas le même remède :

1. *La caisse modélise des états, pas des événements.* Le fond de caisse
   compté à la clôture, oui ; la suite des mouvements qui l'ont produit,
   non. Le panier tel qu'il est, oui ; les lignes annulées en chemin, non.
   C'est un choix juste pour vendre, et c'est un mur dès qu'on veut
   contrôler plutôt que vendre.
2. *Le journal a été bâti pour le fiscal, pas pour le contrôle.* La chaîne
   de signature et les `TechnicalEvent` de la phase 1 répondent à « ce
   ticket est-il inaltéré ». L'AO pose une autre question : « qu'a fait
   cette caissière à 15:42 ». Elle le dit elle-même en demandant DEUX
   onglets (`BO-04-01-52`), transactionnel et fonctionnel. Nous avons
   construit le premier.
3. *Le monde extérieur n'est pas branché.* Pas de monétique réelle — le TPE
   est un simulateur, un vrai terminal rend un numéro d'autorisation et un
   indicateur de mode dégradé —, pas de self-scanning, pas de clients en
   compte, pas de coffre. Ces données arriveront avec leur intégration,
   jamais avant.

Autrement dit, le back-office demande à la caisse d'être aussi un **témoin**
— de garder trace de ce qui s'est passé, pas seulement de ce qui est. Cette
exigence n'a jamais figuré dans les phases du produit.

| Gisement à créer | Exigences | Codes |
|---|---:|---|
| Mouvements de caisse (prélèvement, apport, dépense, acompte, déclaration, seuil tiroir) | 7 | `04-01-12/33/35/36/37/40/44` |
| Événements de sécurité opérateur (pause, changement et échec de mot de passe) | 4 | `04-01-27/28/29/30` |
| Traces monétique (n° d'autorisation, drapeaux de mode dégradé) | 4 | `04-01-08/47/48/49` |
| Canal self-scanning | 3 | `04-01-41/42/43` |
| Lignes annulées conservées | 2 | `04-01-16/19` |
| Nomenclature et PLU snapshotés sur la ligne | 2 | `04-01-11/23` |
| Vérification de prix | 1 | `04-01-38` |
| Entité facture | 1 | `04-01-53` |

*La facture a depuis reçu son propre cadrage : `facture-cadrage.md`.*

Le plus rentable est le premier : une entité de mouvement de caisse de
premier rang débloque sept critères d'un coup, et elle est aussi le socle
du lot conditionnel D (gestion du coffre), qui en compte dix-sept.

### 7.3 Ce que ça implique

Ces 81 lignes **ne sont pas dans les 515 du plan back-office** et n'y
entreront pas. Elles appellent leur propre plan, côté caisse, avec deux
propriétés qui les distinguent des lots d'administration :

- elles touchent la surface de vente, donc la séquence fiscale, donc le
  périmètre où une régression ne se voit pas dans un `mvn verify` vert ;
- elles se paient deux fois — une fois pour écrire la fonction, une fois
  pour l'administrer, cette seconde partie revenant alors dans le lot
  back-office correspondant.

Rien n'oblige à les traiter toutes. Mais tant qu'elles ne le sont pas, les
lignes du questionnaire qui en dépendent restent en `3`, quel que soit le
soin mis au back-office.

## 8. Décisions en attente

*Rendue le 01/09/2026 : la brique centrale se fait en **route A**, un
troisième rôle `pos.role=central` dans le même exécutable (§5.2). Elle ne
figure plus ci-dessous.*

1. **Lesquels des sept lots conditionnels sont retenus** (§6) — 204 lignes
   et 24 à 28 sessions en dépendent, ainsi que la réponse commerciale à
   l'AO. La question n'est pas « quand », elle est « si ».
2. **Profils administrés ou rôles figés** — si les quatre rôles actuels
   suffisent en v1, le lot 2 sort du chemin critique.

---

*Document de cadrage et plan — établi le 31/08/2026 à partir de la cotation
complète de l'onglet Back Office. À relire à chaque évolution du
questionnaire et à chaque lot livré.*
