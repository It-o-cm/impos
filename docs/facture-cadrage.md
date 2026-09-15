# Facture — cadrage du chantier

*Établi le 09/09/2026 à partir de la cotation `Questionnaire
Fonctionnalites_Encaissem HD_7.xlsx`.*

Ce document est le pendant caisse de `backoffice-cadrage.md`, dont le §7.3
annonçait l'existence : « ces 81 lignes appellent leur propre plan, côté
caisse ». La facture en est le morceau le plus gros et le plus urgent, parce
qu'elle est la seule exigence non couverte de l'onglet caisse dont la
démonstration exige du **matériel** — un chemin d'impression que le produit
n'ouvre nulle part aujourd'hui, et qu'aucune revue sur papier ne peut
arbitrer.

---

## 1. Le décompte

**Bloc `LC-08-04` « Facture » : 26 exigences, 26 en `3. Non couvert non
prévu`.** Pas une exception, pas un `1a`. C'est le plus gros bloc homogène
à couverture nulle de l'onglet Ligne de caisses.

| | Lignes | dont P1 | État |
|---|---:|---:|---|
| `LC-08-04` — Facture, périmètre « All » | 19 | 14 | 19 × `3` |
| `LC-08-04` — Facture, périmètre Portugal (Fatura FT, Recibo, ATCUD) | 7 | 5 | 7 × `3` |
| `LC-08-06` — Enregistrement du NIF client (Portugal) | 11 | 6 | 11 × `3` |
| `LS-01-07` — Facture en libre-service (CLS) | 10 | 0 | non répondu |
| `LS-12-01/02` — Borne services : génération et activation | 2 | 2 | non répondu |

Les onglets **Libre-Service (634 lignes) et Digital (709)** ne sont répondus
sur aucune ligne : le chiffre de 10 + 2 ci-dessus est un périmètre, pas une
cotation.

**Côté back-office, la facture ne forme pas un lot : elle traverse sept
lots déjà constitués.** C'est le fait le plus important de ce cadrage, et il
commande la séquence.

| Bloc back-office | Lignes concernées | Lot du plan BO |
|---|---|---|
| `03-03`, `03-04`, `03-05` — modèles, assemblage et personnalisation des documents | 69 | **Lot 8 — Documents imprimés** (dernier de la séquence) |
| `02-04`, `10-04` — clients en compte, crédit client | 41 | **Lot A** (conditionnel) |
| `11-01` — flux facture vers comptabilité et SAP | 3 | **Lot C** (conditionnel) |
| `06-03`, `06-04` — liste et réimpression des factures | 3 | **Lot B** (conditionnel) |
| `04-01-53` — consultation des factures au journal | 1 | Lot 4 |
| `10-02-18/19` — règle d'arrondi, décimales HT | 2 | Lots 3 et 3C |
| `10-08-16` — archivage des factures | 1 | Lot 3B |
| `03-10-19/20` — données PDV reprises en entête | 2 | Lot 10 |
| `03-06-53` — plage de codes des clients créés en caisse | 1 | Lot 9 |

## 2. Le constat structurant

**La facture n'est pas un chantier neuf : c'est la face caisse de lots
back-office déjà planifiés, plus 26 lignes de caisse qui n'ont de plan nulle
part.** Trois conséquences.

**Première conséquence — le conflit de séquence.** Le lot 8, qui porte les
gabarits de documents, **ferme la marche du plan back-office** : onzième sur
onze, après 28 à 33 sessions. Or `LC-08-04-04` demande de choisir le modèle
de document « parmi une liste paramétrée sur le BackOffice ». Si la facture
est demandée tôt, il n'y a que deux issues : remonter le lot 8 dans la
séquence — il coûte 4 sessions et il est le plus intrusif des onze —, ou
**livrer la facture avec un gabarit figé dans le code et l'administrer plus
tard**. Ce document retient la seconde, et l'assume : c'est exactement ce
que fait déjà le ticket, dont l'entête est figée dans
`TicketPrinterService`, et le questionnaire cote le ticket `1. Couvert
intégralement` malgré cela. Un gabarit figé n'est pas une dette cachée si on
sait où il est.

**Deuxième conséquence — la facture a un destinataire.** Un ticket est
anonyme, une facture ne l'est pas : `LC-08-04-06` à `10` sont cinq lignes de
client, et `BO-02-04` en décrit vingt-deux de plus. Il n'existe aucune
entité client professionnel dans impos, et **imfid n'en fournit pas** : il
gère des cartes pseudonymes de porteurs, pas des clients B2B avec SIRET,
plafond de crédit et encours. Rien n'est réutilisable. Mais le questionnaire
distingue lui-même deux populations (`LC-08-04-08`) : le client **envoyé par
la gestion commerciale** et le client **créé en caisse**. Le second ne
demande qu'une table et un écran de saisie ; le premier demande une
intégration. C'est la ligne de coupe naturelle du chantier.

**Le bon de livraison n'est pas un second document.** Il accompagne la
facture dans les treize lignes back-office qui le mentionnent — toujours
accolé, « Facture/Bon de livraison » — et il est totalement absent de
l'onglet Ligne de caisses. Ce n'est pas un oubli : `BO-03-03-02` range le
type de document dans le gabarit d'entête (« *Type de document :
facture/bon de livraison /.... (issu du nom du document)* », avec un « *N° de
séquence unique, séquentiel et sans rupture par type de document* »), et
`BO-03-04-06` fait de l'activation d'un document au back-office ce qui le
rend « automatiquement disponible en caisse ». Le bon de livraison est donc
**un type de document du même moteur de gabarits**, et le « /.... » dit que
la liste est ouverte. La caisse ne nomme aucun type parce qu'elle n'en
connaît aucun : elle affiche la liste activée — c'est exactement
`LC-08-04-04`. Le questionnaire ne demande d'ailleurs aucun mécanisme propre
au bon de livraison : ni conversion en facture, ni regroupement, ni
facturation périodique. S'ils existent, ils vivent dans la gestion
commerciale. Conséquence de plan : le bon de livraison ne coûte rien à F1 et
se réduit à une entrée de la table des types de documents au lot F4.

*Réserve, relevée hors questionnaire.* Les logiciels de caisse du métier
traitent le bon de livraison plus durement que ne le fait le questionnaire :
la pièce constate la sortie de stock et le chiffre d'affaires au jour de son
émission, elle est regroupée plus tard en une « facture de regroupement », et
**aucun règlement ne lui est associable**. Un document de vente sans
règlement serait un ticket clos avec un dû non nul, cas qu'impos n'a pas —
`handlePaymentWithChange` clôt quand le reste à payer atteint zéro. Le
questionnaire ne va pas là : `BO-10-04-11` fait du « paiement crédit client »
une fonction de caisse, et `BO-02-04-01` met la même date d'échéance
obligatoire sur la facture et sur le bon de livraison. Le ticket est donc
soldé en caisse par un moyen de paiement qui crée un encours, et le mécanisme
différé vit au lot F3, pas dans le document. Le regroupement, lui, n'est
demandé nulle part : s'il est attendu, il est dans la gestion commerciale.

**Troisième conséquence — deux moteurs de rendu, pas un.** `LC-08-04-11`
énumère trois sorties : le rouleau caisse au format ticket, la **facturette**
insérée dans l'imprimante, et l'**A4 sur imprimante réseau**. Les deux
premières sont du texte colonné et sortent de la même imprimante. La
troisième n'est ni l'un ni l'autre : c'est une mise en page paginée vers un
périphérique qu'impos ne connaît pas du tout. Traiter l'A4 comme une
variante du 42 colonnes serait l'erreur de conception de ce chantier.

## 3. Les socles réels

Contrairement au back-office, la facture n'attaque pas un terrain vide. Ce
qui existe déjà et qui sert directement :

- **`TicketCounter`** — compteur par terminal, lu sous verrou pessimiste
  dans la transaction de création, portant déjà trois séquences (tickets,
  sessions, remboursements) et les ancres de chaînage fiscal. Une séquence
  de documents s'y ajoute sans rien réinventer, et c'est le seul endroit du
  produit où une numérotation légale est correctement sérialisée.
- **`VatBreakdown`** — la ventilation TVA par taux avec HT, TVA et TTC par
  seau, déjà calculée et déjà imprimée sur le ticket. C'est le tableau TVA
  que `BO-03-03-04` veut administrer, et il exige explicitement qu'il soit
  « conforme à celui imprimé en ticket de caisse ». Il l'est par
  construction si la facture le réutilise.
- **`Store`** — porte déjà `vatNumber`, `siret`, `phone`,
  `bankAccountNumber` et une `Address` embarquée : la totalité des données
  vendeur de l'entête de facture. `Address` est réutilisable telle quelle
  pour le client.
- **`TicketPrinterService`** — neuf documents rendus en 42 colonnes, avec
  ses zones entête / lignes / TVA / total / pied. Les zones que le lot 8
  veut administrer existent, elles sont simplement en dur.
- **`ReprintService`** — retrouve un ticket clos et le réimprime avec la
  mention duplicata. Le socle de `LC-08-04-02` (saisie du ticket d'origine)
  et de `-17` (duplicata automatique), à ceci près que le questionnaire
  veut un **masque de saisie** (date, n° ticket, n° caisse) là où impos
  offre une liste paginée. La donnée est là, l'ergonomie diffère.
- **La station chèque de la TM-H6000V**, ouverte le 08/09 pour
  l'endossement (`jpos.POSPrinter`, station `PTR_S_SLIP`, via le service
  JavaPOS Epson piloté par réflexion depuis impos-hw). Voir §4.

Ce qui n'existe nulle part : le client professionnel, l'entité facture, la
numérotation par type de document, le gabarit administrable, la sortie A4,
et le crédit client comme moyen de paiement.

## 4. Les incidences matérielles

C'est la raison d'être de ce cadrage, et le seul volet qu'on ne peut pas
préparer sur le papier.

**La facturette sort de la station document.** Sur la TM-H6000V, le feuillet
inséré à la main passe devant la tête MICR puis sous la tête d'impression du
chemin document — le même chemin physique que le chèque.

*Le même chemin physique, pas le même pilote.* Ce projet parle aux
périphériques directement — ESC/POS sur `/dev/usb/lp0`, hidraw, série,
`java.lang.foreign` pour le TPE — et JavaPOS y est une exception étroite,
consentie pour le seul lecteur de chèques parce que les commandes MICR ne
sont pas publiques et que le service du constructeur sait déjà attendre le
document et décoder le CMC7. L'endossement l'a suivi parce qu'il s'imprime
pendant que le service MICR tient le chèque. **La facturette n'a pas cette
contrainte** : sélection de station, attente d'insertion, capteurs de début
et de fin de feuillet et éjection sont des commandes ESC/POS, et c'est en
ESC/POS qu'elle doit être écrite — sans quoi une fonction de vente entière
tomberait dans la dépendance que NEMO cherche justement à défaire.

Trois contraintes en découlent, et aucune n'est un détail d'implémentation :

1. **L'insertion est un geste humain.** Le service attend le document
   (`beginInsertion` / `endInsertion`) exactement comme pour le chèque. La
   commande d'impression n'est donc pas synchrone du point de vue de la
   caisse : elle est une opération suivie, comme le paiement et la lecture
   de chèque le sont déjà sur le pont matériel. `LC-08-04-12` le dit
   explicitement — « la caisse demande d'insérer le papier ».
2. **La pagination est physique.** Un feuillet a une hauteur, et
   `LC-08-04-13` demande d'annoncer le nombre de pages **avant** de
   commencer, donc de paginer le document au moment du rendu et non au fil
   de l'écriture. C'est une contrainte de conception du moteur de rendu, pas
   une option d'impression.
3. **Le service JavaPOS revendique l'imprimante en exclusivité.** Le démon
   imprime les tickets en écrivant directement sur `/dev/usb/lp0` ; le
   lecteur de chèques, lui, passe par le service du constructeur. Les deux
   ne peuvent pas tenir l'imprimante en même temps — d'où la règle déjà en
   vigueur : le lecteur est ouvert par lecture et relâché aussitôt. Une
   facturette allonge cette fenêtre d'exclusivité de quelques secondes à la
   durée d'un geste client. **Tant qu'une facturette est en cours, aucun
   ticket ne peut sortir sur cette caisse.** C'est acceptable, mais ça doit
   être décidé et non subi : la contention de l'imprimante devient
   structurelle avec ce chantier.

**L'A4 réseau n'existe pas du tout.** Ni impos ni impos-hw ne connaissent
d'imprimante réseau. Il faut choisir qui la porte — la caisse (CUPS/IPP
local) ou le nœud magasin —, et produire un PDF paginé plutôt que du texte
colonné. C'est un second moteur de rendu, une dépendance externe et un
nouveau mode dégradé (imprimante réseau injoignable pendant une vente). Ce
cadrage le sort du premier temps.

**Ce qui reste à prouver sur la machine.** D'abord le jeu de commandes
lui-même : les séquences ESC/POS de la station document ne s'inventent pas,
elles se lisent — dans le manuel du constructeur s'il est sur la caisse,
sinon dans les jars du service Epson, qui pilote cette station et écrit donc
ces octets. C'est la méthode qui a donné le protocole de la balance et celui
du tiroir. Ensuite, que la station accepte
plusieurs pages consécutives sans réinsertion manuelle intermédiaire ; quelle
est la largeur utile en caractères du chemin document, qui n'est pas celle du
rouleau ; et si le service annonce correctement la fin d'éjection pour
enchaîner. Trois questions qui se règlent en une session sur la vraie
imprimante, et qui doivent l'être **avant** d'écrire le moteur de rendu.

## 5. Le plan

Six lots pour le périmètre France, deux renvois vers des lots back-office et
un volet portugais. Chaque exigence `LC-08-04` appartient à un lot et à un
seul ; les six lots, les deux renvois et le volet portugais totalisent
exactement les 26 lignes du bloc, et ça doit le rester à chaque révision.

---

**Lot F0 — L'épreuve de la station document** · 1-2 sessions · **en
parallèle, pas en tête**

*Construit :* rien de fonctionnel. D'abord l'extraction du jeu de commandes
ESC/POS de la station document — dans le manuel du constructeur s'il est sur
la caisse, sinon dans les jars du service Epson. Ensuite une commande
`slip-test` dans impos-hw qui insère, imprime un canevas de calibrage (règle
de largeur, hauteur de page, deux pages consécutives), éjecte, et journalise
la réponse des capteurs à chaque étape.

*Pourquoi en parallèle et non en premier :* les mesures du §4 dimensionnent
le moteur de rendu de **F2**, pas celui de F1 — qui imprime en 42 colonnes
sur le rouleau, un format déjà éprouvé. Rien dans le document, le client, la
numérotation ou la dérivation TVA ne dépend de la largeur d'un feuillet. En
revanche F0 porte le seul risque non borné du chantier — l'extraction d'un
protocole a pris une semaine sur le TPE, une session sur la balance — et il
dépend de fichiers qui sont sur la caisse. Un risque non borné se lève tôt ;
il ne se met pas en travers du chemin critique.

*Critère de fin :* les séquences de sélection de station, d'attente
d'insertion, de lecture des capteurs et d'éjection établies et vérifiées sur
la machine ; la largeur utile en caractères, la hauteur de page en lignes et
le comportement multi-pages consignés dans le `HANDOFF.md` d'impos-hw.

*Bascule :* aucune ligne. C'est une mesure, pas une fonctionnalité.

---

**Lot F1 — Le document facture** · 3-4 sessions

*Construit :* l'entité `Invoice` — dérivée d'un ticket clos, jamais
recalculée : elle fige le ticket d'origine, la ventilation TVA, le total HT
et TTC au moment de l'émission. Une entité `Customer` minimale (raison
sociale, nom, `Address`, SIRET, TVA intracommunautaire), créée en caisse et
elle seule. Une **numérotation par type de document** portée par
`TicketCounter`, sous le même verrou que les trois séquences existantes —
générique dès le départ, avec préfixe et compteur par type, ce qui pré-résout
le besoin FS/FT/NC/RC du Portugal au lieu de le coder deux fois. L'écran de
demande : saisie du ticket d'origine, pré-rempli par le dernier ticket de la
caisse, puis saisie ou reprise du client. Le duplicata automatique si une
facture existe déjà pour ce ticket.

*Un moteur, deux destinations.* Le rendu produit **des lignes de texte** en
42 colonnes, gabarit figé, réutilisant les zones de `TicketPrinterService` ;
l'écran et l'imprimante consomment les mêmes lignes. La caisse **visualise
donc la facture avant de l'imprimer**, et cette visualisation est fidèle par
construction : ce que la caissière voit est ce qui sortira, colonne pour
colonne. C'est là que se place l'abandon de `LC-08-04-14` — sans écran
intermédiaire, la fenêtre « tant que l'impression n'a pas démarré » est
théorique ; avec lui, l'opérateur voit le document et dit non.

*Le piège à ne pas commettre :* l'aperçu ne doit PAS devenir un gabarit HTML
autonome. L'écran affiche le bloc monospace que le moteur a produit, rien de
plus. Le jour où F4 apporte les gabarits administrés et F6 la mise en page
A4, c'est le moteur qui change et l'écran suit sans être touché. Deux mises
en page pour un même document, ce sont deux vérités et une divergence à la
troisième correction.

*Réutilise :* `TicketCounter`, `VatBreakdown`, `Store`, `Address`,
`ReprintService` pour la recherche du ticket d'origine, le chemin
d'impression rouleau existant.

*Critère de fin :* une facture émise sur un ticket clos, numérotée dans sa
propre séquence, portant un client saisi en caisse, **visualisée à l'écran**
puis imprimée sur le rouleau, et sa seconde demande sortant marquée
duplicata — le tout couvert par des tests sans base de données pour la
dérivation et le rendu.

*Ce lot se démontre sans matériel.* La facture rendue à l'écran ne demande ni
caisse, ni imprimante, ni F0 : elle se montre dans un navigateur. C'est la
première chose visible du chantier, et la plus tôt.

*Bascule ANNONCÉE :* `LC-08-04-01`, `-02`, `-03`, `-06`, `-07`, `-09`, `-10`,
`-14`, `-17` — 9 lignes, dont 8 P1.

*Bascule CONSTATÉE au terme du lot, 09/09 :* **4 lignes en `1`** — `-01`
(touche), `-03` (dernier ticket pré-rempli), `-07` (recherche par nom avec
l'adresse en discriminant), `-14` (abandon) — **4 en `1a`** et **1 en `3`` :

| Ligne | État | Écart |
|---|---|---|
| `-02` | `1a` | Le masque ne saisit que le numéro de ticket ; la date et le n° de caisse manquent |
| `-06` | `3` | Aucune recherche par numéro de client : la recherche porte sur la raison sociale seule |
| `-09` | `1a` | Le client est créé en caisse mais n'est **envoyé nulle part** — aucune remontée back-office |
| `-17` | `1a` | Le duplicata est produit et marqué, mais reconnu à l'ÉMISSION et non « lors de la saisie du ticket », et il n'est pas automatique |

**La leçon du §7.1 de `backoffice-cadrage.md` se répète, dans l'autre sens.**
Elle disait qu'on ne dimensionne pas un lot depuis le texte de l'exigence
mais depuis le code ; ici le lot a été dimensionné depuis le texte AVANT
d'écrire le code, et le code en rend quatre sur neuf. Les quatre écarts sont
petits et nommables — deux d'entre eux, `-02` et `-06`, sont une passe
courte — mais ils n'étaient pas visibles depuis l'énoncé.

---

**Lot F2 — La facturette** · 1-2 sessions

*Construit :* l'impression sur la station document. Côté impos-hw, une
opération suivie comme le paiement et le chèque — `POST /printer/slip` pour
démarrer, `GET` pour suivre — parce qu'elle attend un geste humain. Côté
impos, l'écran d'attente d'insertion, l'annonce du nombre de pages avant
impression, le choix de la sortie entre rouleau et facturette, et le mode
dégradé quand l'imprimante est prise.

*Dépend de :* F0 pour le protocole et les mesures, F1 pour le document. C'est
la jonction des deux, et la seule raison pour laquelle F0 doit avoir abouti
avant que ce lot commence.

*Critère de fin :* une facturette réelle sortie de la TM-H6000V, sur deux
pages, avec l'annonce préalable du nombre de pages.

*Bascule :* `LC-08-04-11` (les deux sorties de cette imprimante ; l'A4 reste
en `1a` jusqu'au lot F6), `-12`, `-13` — 3 lignes.

---

**Lot F3 — Les clients en compte** · 4-5 sessions

*Construit :* le référentiel professionnel complet et son intégration depuis
la gestion commerciale (full et delta), la recherche par nom avec l'adresse
en discriminant, le blocage, le plafond de crédit, la date d'échéance,
l'encours, la plage de codes des clients créés en caisse, et le **crédit
client comme moyen de paiement**.

*C'est le lot A du plan back-office, vu de la caisse.* Les 41 lignes
back-office et la ligne caisse `LC-08-04-08` se livrent ensemble ou pas du
tout : un référentiel administré sans consommation en caisse ne se démontre
pas, et une recherche client en caisse sans référentiel n'a rien à chercher.

*Bascule :* `LC-08-04-08`, plus les 41 lignes du lot A.

---

**Lot F4 — Les gabarits de documents** · 4 sessions

*Construit :* le moteur de gabarits — zones entête, titre, lignes articles,
tableau TVA, total, pied ; activation, ordre, cadrage, gras ; modèles par
format et par type de document ; visualisation du rendu. Côté caisse : le
choix du modèle quand plusieurs sont éligibles, et l'étape escamotée quand
il n'y en a qu'un.

*C'est le lot 8 du plan back-office.* Il libère aussi le gabarit figé du lot
F1, l'entête du ticket, et une grande partie des mentions légales
portugaises — `backoffice-cadrage.md` §6 note déjà qu'« un moteur de
gabarits les absorbe au lieu de les coder une à une ».

*Bascule :* `LC-08-04-04`, `-05`, plus les 69 lignes du lot 8.

---

**Lot F5 — La facture d'acompte** · 1-2 sessions

*Construit :* `LC-08-04-15` — un document portant articles, montants, total
et TVA **alors que la vente n'est pas terminée et que les articles ne sont
pas vendus**.

*Pourquoi seul :* c'est la seule ligne du bloc qui touche la chaîne fiscale
autrement qu'en la lisant. Un document numéroté et remis au client alors
qu'aucun ticket n'existe pose la question de sa place dans le chaînage, dans
le Z et dans le grand total perpétuel. Ce n'est pas une variante de la
facture, c'est un objet fiscal distinct, et il se tranche avec le fiscaliste
avant d'être écrit.

*Bascule :* `LC-08-04-15`.

---

**Lot F6 — La sortie A4 et les flux** · 2-3 sessions

*Construit :* le rendu paginé PDF, l'imprimante réseau et son mode dégradé,
puis les exports — `clifact.dat` / `clilfact.dat` / `cliregl.dat` /
`clitva.dat` vers la comptabilité, l'exposition vers SAP Équipement Maison,
l'activation par pays / enseigne / point de vente.

*C'est la part facture du lot C.* Dépend de F3 : sans clients en compte, il
n'y a pas de facturation à exporter.

*Bascule :* le solde de `LC-08-04-11`, plus `BO-11-01-06`, `-12`, `-22`.

---

**Hors des six lots — deux renvois.**

`LC-08-04-16` (impression automatique d'un document en fin de transaction
selon le paramétrage du moyen de paiement) bascule avec le **lot 7 du plan
back-office**, qui construit les modes de règlement : la règle est un
attribut du moyen de paiement, pas de la facture.

`LC-08-04-25` et `-26` (arrondis HT, prix unitaire et total HT à 2 ou 3
décimales) basculent avec `BO-10-02-18` et `-19`, lots 3 et 3C : ce sont des
paramètres de calcul, pas des fonctions de facture.

**Le Portugal reste dehors** : `LC-08-04-18` à `-24` et les 11 lignes de
`LC-08-06` (NIF) forment le volet portugais, qui appartient au **lot G** du
plan back-office et se fait après le moteur de gabarits.

**Le libre-service reste dehors** : les 10 lignes de `LS-01-07` et les 2 de
`LS-12` ne sont pas cotées, et le CLS n'a pas d'écran dans impos. La facture
en libre-service demande en plus l'authentification du client fidèle par PIN
sur le TPE ou sur l'écran — un chantier qui n'a rien à voir avec celui-ci.

## 6. Séquence et cumul

| | Lot | Sessions | Lignes `LC-08-04` | dont P1 | Cumul caisse |
|---|---|---:|---:|---:|---:|
| 1 | F1 — Le document facture | 3-4 | 9 | 8 | 9 |
| — | F0 — Épreuve de la station document *(en parallèle)* | 1-2 | 0 | 0 | 9 |
| 2 | F2 — La facturette | 1-2 | 3 | 2 | 12 |
| 3 | F3 — Les clients en compte | 4-5 | 1 | 1 | 13 |
| 4 | F4 — Les gabarits de documents | 4 | 2 | 1 | 15 |
| 5 | F5 — La facture d'acompte | 1-2 | 1 | 1 | 16 |
| 6 | F6 — Sortie A4 et flux | 2-3 | 0 | 0 | 16 |

**16 à 21 sessions** pour les 16 lignes « All » que ce plan porte
directement — les 3 autres du périmètre France partant avec les lots
back-office 7, 3 et 3C. En contrepartie, F3, F4 et F6 font basculer **113
lignes de back-office** (lots A, 8 et la part facture de C) qui sont déjà
comptées dans le plan back-office et ne sont donc pas un coût nouveau : ce
chantier les **avance**, il ne les ajoute pas.

**Le chemin le plus court vers une démonstration crédible est F1 + F2, F0
courant à côté : 5 à 8 sessions pour une vraie facture, numérotée, adressée à
un client, qui sort en facturette de la vraie imprimante. Mais la première
démonstration arrive bien avant, au milieu de F1 : la facture visualisée à
l'écran, sans aucun matériel.** C'est ce qu'on
demandera de montrer. Tout le reste peut suivre. Et si F0 s'enlise, F1 sort
quand même une facture imprimée — sur le rouleau — ce qui est déjà une
démonstration.

## 7. Décisions en attente

1. **Facture avant référentiel, ou après.** F1 émet des factures à des
   clients créés en caisse et jamais synchronisés. C'est ce que le
   questionnaire autorise (`LC-08-04-08` distingue les deux populations),
   mais il faut l'assumer : pendant l'intervalle F1 → F3, des clients
   existent en caisse et nulle part ailleurs.
2. **Le lot 8 remonte-t-il ?** Si la réponse est oui, F1 attend le moteur de
   gabarits et le chantier commence par 4 sessions de back-office. Si elle
   est non, F1 livre un gabarit figé qu'il faudra démonter au lot F4. Ce
   cadrage retient non, mais c'est un arbitrage et pas une évidence.
3. **La facture d'acompte dans la chaîne fiscale** (§5, lot F5) — à trancher
   avant d'écrire, pas pendant.
4. **Qui porte l'imprimante A4** : la caisse ou le nœud magasin.
5. **Le Portugal est-il dans la réponse** — 18 lignes (`LC-08-04-18/24` et
   `LC-08-06`) qui ne coûtent presque rien après le moteur de gabarits, et
   très cher avant.

---

*Document de cadrage et plan — établi le 09/09/2026. Pendant caisse de
`backoffice-cadrage.md`, annoncé par son §7.3. À relire à chaque évolution
du questionnaire et à chaque lot livré.*
