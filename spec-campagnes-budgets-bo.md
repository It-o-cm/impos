# Spécification — Campagnes & budgets, moitié Back Office (impos)

**Projet** : impos · **Chantier** : administration des campagnes et budgets de générosité
· **Statut** : arbitré en séance le 25/09/2026, à valider avant implémentation.
**Moitié jumelle** : `spec-campagnes-budgets.md` dans le repo imvaluation — le moteur est
exécutant (compteurs, contrôle à l'arbitrage, ingestion de la consommation) ; impos est
le **BO maître** : c'est ici qu'on crée, associe, duplique, budgète et suit.

## 1. Partage des rôles et principe de livraison

impos administre les **campagnes** ; le moteur ne connaît que leur projection budgétaire,
le **groupe budgétaire** (code, période, plafonds, `perStore`) — libellé, notes,
association, duplication et suivi ne descendent jamais. Le principe unique de tout le
chantier : **le moteur est livré par sa chaîne de flux, jamais directement par un
émetteur métier** — les budgets comme la consommation empruntent le canal des
`ENGINE_FEEDS`, avec ses propriétés existantes (file persistée, rejeu, supervision).
Le suivi de consommation se LIT depuis le moteur (GraphQL `budgetStatus` /
`groupBudgetStatus`) — impos ne tient aucun compteur en propre.

## 2. L'entité campagne côté BO

Champs : code, libellé, période (`validFrom` inclus / `validTo` exclu, bornes nulles
ouvertes), notes libres, budget `maxAmount` (€) et/ou `maxApplications` (nombre),
`perStore`.

Écrans :

1. **Liste des campagnes** : code, libellé, période, budget, nombre d'offres rattachées,
   état (à venir / en cours / terminée), consommation (lue du moteur, voir §5).
2. **Fiche campagne** : édition des champs, association/dissociation des offres
   (une offre appartient à au plus une campagne), validation : période cohérente,
   au moins un des deux plafonds si un budget est saisi.
3. **Duplication** (GB-01-06-15) : depuis une campagne, créer une copie — nouveau code
   obligatoire, tous les champs modifiables avant enregistrement, option « dupliquer
   aussi les offres rattachées » (copie des offres avec nouveaux codes suffixés,
   mêmes spécifications).
4. **Budget d'offre** : le formulaire d'offres expose le bloc `budget` de la
   spécification — il suit tout seul si le formulaire est piloté par les schémas ;
   sinon, ajouter la section (mêmes trois champs).

RBAC : lecture pour tous les rôles BO, écriture selon le rôle qui écrit déjà les offres.

## 3. La consommation — dérivée du ticket, PAS un nouveau canal

**Aucun nouveau canal caisse→moteur.** La caisse continue de faire exactement ce
qu'elle fait : encaisser, et pousser ses tickets finalisés au nœud (file persistée,
rejeu — l'existant). La déclaration de consommation est **dérivée du ticket encaissé**
par le composant de livraison des flux — celui qui livre déjà les `ENGINE_FEEDS` au
moteur, où qu'il tourne (le nœud en production ; dans la pile démo, la caisse joue ce
rôle, l'URL moteur du nœud y étant volontairement vide).

- Dérivation : pour chaque ticket finalisé portant des avantages moteur, produire
  `POST /consumptions` : `ticketId`, `storeCode`, `finalizedAt`,
  `direction: "SALE"`, `advantages: [{offerCode, amount}]` — montants en valeur
  absolue, extraits du `advantages[]` de la valorisation retenue au paiement.
- **Annulation** de ticket ⇒ même déclaration, `direction: "CANCELLATION"`.
- L'endpoint moteur est idempotent sur `(storeCode, ticketId, direction)` : le rejeu
  est sûr, par n'importe quel maillon.
- **Jamais bloquant** : la dérivation suit le flux du ticket, l'encaissement ne
  l'attend pas ; les échecs de livraison se voient à la supervision des flux,
  comme les autres.
- Mode dégradé (valorisation LOCAL, moteur injoignable au moment de la vente) :
  rien à déclarer — aucun avantage moteur n'a été appliqué.
- Budgets globaux : les déclarations des autres PDV redescendent par le relais
  inter-nœuds de la même chaîne (fenêtre d'approximation documentée, arbitrage n° 6
  de la spec moteur) — aucun mécanisme dédié.

## 4. Les flux

- Nouveau flux `BUDGET_GROUPS` dans le catalogue `ENGINE_FEEDS` : la projection
  budgétaire des campagnes, header
  `CODE|VALID_FROM|VALID_TO|MAX_AMOUNT|MAX_APPLICATIONS|PER_STORE`.
  Livré AVANT `OFFERS` (une offre référence son groupe).
- Le flux `OFFERS` gagne la colonne optionnelle `BUDGET_GROUP_CODE` (= le code de la
  campagne).
- Le jeu de démonstration gagne une campagne exemple avec budget et deux offres
  rattachées (pour la pile démo de bout en bout).

## 5. Le suivi de consommation

Sur la liste et la fiche campagne (et sur la fiche offre budgétée) : consommé / plafond
/ restant, en € et en nombre, global et par PDV (`byStore`), lus du moteur via
`budgetStatus` / `groupBudgetStatus`. Lecture à l'affichage, pas de cache local — et
l'écran affiche l'heure de la lecture (le budget global est approximatif par
construction).

## 6. Cas normatifs (tests)

1. Ticket encaissé avec deux avantages → une déclaration SALE dérivée, montants exacts
   du ticket.
2. Annulation → CANCELLATION symétrique.
3. Moteur injoignable au moment de la dérivation → ticket encaissé normalement,
   déclaration en file, rejouée avec succès au retour, visible en supervision
   entre-temps.
4. Rejeu de file après redémarrage → pas de double comptage (idempotence moteur,
   vérifiée de bout en bout).
5. Duplication de campagne avec offres → nouveaux codes, spécifications identiques,
   budget copié modifiable.
6. Association d'une offre déjà rattachée à une autre campagne → refus explicite.
7. Flux BUDGET_GROUPS livré avant OFFERS ; offre avec BUDGET_GROUP_CODE inconnu
   rejetée ligne à ligne par le moteur (vérifié sur la pile démo).

**Règles dures habituelles** : code et Javadoc en anglais, Javadoc sur toute méthode
sans exception, portée stricte, suite complète verte.
