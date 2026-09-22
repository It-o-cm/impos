# Addendum à la spécification d'intégration POS ↔ imfid
## Recherche de carte par identité du porteur (`/api/cards/lookup`)
**Version 1.1 — 28 août 2026 — remplace la v1.0. Changement additif, sans rupture.**
Aucun endpoint existant ne change : `/earn`, l'ingestion des tickets et le protocole
de réservation burn (`/api/burn/reservations`) restent identiques au caractère près.
Ce document ne décrit que l'ajout.
*Évolutions v1.1 : la recherche par nom passe de l'exact au **préfixe** (nom et
prénom) ; la réponse porte désormais le **téléphone** et l'**e-mail** du porteur.*
---
## 1. Objet
Un client se présente en caisse sans sa carte de fidélité. La caisse doit pouvoir
retrouver son numéro de carte à partir de son téléphone, de son e-mail ou de son
nom, puis dérouler le flux habituel (earn, burn, ingestion) exactement comme si la
carte avait été scannée.
imfid héberge un **annuaire local des porteurs** (nom, prénom, téléphone, e-mail)
qui joue ce rôle en repli. Cet annuaire n'est actif que lorsque **aucun accès CRM
n'est configuré** côté imfid ; quand un CRM est configuré, imfid reste pseudonyme,
l'annuaire local est vide et refuse les recherches — la résolution d'identité
incombe alors au CRM, hors du contrat imfid.
## 2. Les deux modes
| | Mode local (défaut) | Mode CRM (`imfid.crm.url` configurée) |
|---|---|---|
| Identités porteur dans imfid | Stockées (nom, prénom, tél, e-mail) | Aucune donnée nominative |
| `GET /api/cards/lookup` | Répond les correspondances | Répond **422** `{"reason":"Holder identity is managed by the CRM (§33.3)"}` |
| Conduite caisse | Utiliser cet endpoint | Interroger le CRM, puis reprendre le flux imfid avec le numéro obtenu |
La caisse n'a pas à connaître le mode à l'avance : le 422 avec cette raison est le
signal de basculer sur le CRM.
## 3. Contrat de l'endpoint
### 3.1 Requête
```
GET /api/cards/lookup?phone=...&email=...&name=...&firstName=...
```
Authentification : **HTTP Basic**, compte de rôle `pos` — le même compte machine
que pour les réservations burn. Sans authentification : 401 ; mauvais rôle : 403.
| Paramètre | Type | Matching | Rôle |
|---|---|---|---|
| `phone` | string | Exact après normalisation | Téléphone du porteur, **dans n'importe quel format saisi** |
| `email` | string | Exact après normalisation | E-mail du porteur |
| `name` | string | **Préfixe** du nom de famille | `dur` trouve Durand |
| `firstName` | string | **Préfixe** du prénom | Optionnel — restreint la recherche par nom |
**Au moins un critère est requis.** Un seul critère est utilisé par appel, par
priorité : `phone` d'abord, sinon `email`, sinon `name` (+ `firstName`).
`firstName` seul ne constitue pas un critère.
### 3.2 Normalisation et matching (côté imfid — la caisse n'a rien à préparer)
- **Téléphone** (exact) : tous les caractères non numériques sont ignorés ; un
  préfixe international français (`+33`, `0033`) est replié sur le `0` initial.
  `06 12 34 56 78`, `+33 6 12 34 56 78`, `0033612345678` et `06.12.34.56.78`
  désignent le même numéro.
- **E-mail** (exact) : espaces de bord retirés, casse ignorée.
- **Nom / prénom** (préfixe) : casse et accents ignorés (`garcia` trouve `García`,
  `lef` trouve `Lefèvre`), espaces de bord retirés. Le matching est
  « commence par » — jamais « contient ».
L'endpoint est une recherche, pas un parcours d'annuaire : un préfixe trop court
qui matche trop de porteurs rend une liste tronquée (voir 3.3), pas un annuaire.
### 3.3 Réponse 200 — la liste des correspondances
```json
[
  { "card": "2990000000019", "status": "ACTIVE", "lastName": "Durand",
    "firstName": "Marie", "phone": "06 12 34 56 78", "email": "marie.durand@example.fr" },
  { "card": "2990000000057", "status": "ACTIVE", "lastName": "Durand",
    "firstName": "Jacques", "phone": "06 12 34 56 99", "email": "jacques.durand@example.fr" }
]
```
| Champ | Contenu |
|---|---|
| `card` | Le numéro de carte (13 chiffres), utilisable tel quel dans tout le flux existant |
| `status` | `ACTIVE` \| `PENDING_ACTIVATION` \| `RESILIATED` — à contrôler avant usage (voir 4) |
| `lastName`, `firstName` | L'identité, pour la confirmation verbale par l'opérateur |
| `phone` | Le téléphone tel que saisi à l'enregistrement, ou `null` |
| `email` | L'e-mail (minuscules), ou `null` |
- Liste **possiblement vide** (aucune correspondance → `[]`, toujours 200).
- Tri par nom puis prénom pour la recherche par nom.
- **Au plus 20 résultats.** Au-delà, la liste est tronquée à 20 : demander un
  critère plus discriminant (le téléphone plutôt qu'un préfixe de nom court).
### 3.4 Réponse 422 — refus
```json
{ "reason": "A phone, an e-mail or a name is required (§33.3)" }
```
| `reason` (texte exact) | Signification | Conduite caisse |
|---|---|---|
| `A phone, an e-mail or a name is required (§33.3)` | Aucun critère fourni | Corriger l'appel |
| `Holder identity is managed by the CRM (§33.3)` | Mode CRM actif | Basculer la recherche sur le CRM |
## 4. Flux caisse recommandé
1. L'opérateur demande **le téléphone** du client (critère le plus discriminant ;
   le nom est le repli du repli).
2. La caisse appelle `/api/cards/lookup`.
3. Selon le résultat :
   - **0 correspondance** : proposer un autre critère, ou continuer sans carte.
   - **1 correspondance** : l'opérateur **confirme verbalement** l'identité
     (« M. Durand, Jacques ? ») avant d'utiliser la carte. Ne jamais afficher au
     client des données qu'il n'a pas fournies ; le téléphone et l'e-mail de la
     réponse servent à discriminer côté opérateur, pas à être lus à voix haute.
   - **Plusieurs correspondances** : affiner avec `firstName`, ou discriminer par
     les derniers chiffres du téléphone que le client énonce lui-même.
4. Contrôle du statut avant usage :
   - `ACTIVE` : flux normal.
   - `PENDING_ACTIVATION` : la carte cagnotte mais **ne peut pas burner** (la
     réservation répondrait 422 `ACCOUNT_STATUS`) — l'annoncer au client.
   - `RESILIATED` : ne pas utiliser ; inviter le client à passer à l'accueil.
5. Le numéro de carte obtenu s'utilise ensuite **exactement comme un scan** :
   `/earn`, réservation burn, ingestion — aucun autre changement.
## 5. Ce qui ne change pas
- Tous les endpoints existants, leurs statuts, leurs corps et leur idempotence.
- Le numéro de carte reste la seule clé du flux ticket : l'identité ne circule
  jamais dans `/earn`, l'ingestion ni les réservations.
- Aucune donnée nominative n'est ajoutée aux réponses existantes.
## 6. Données de test (environnement dev imfid, jeu de démonstration)
L'annuaire de démo est rechargé à chaque démarrage d'imfid (profil dev) :
| Carte | Statut | Nom | Prénom | Téléphone | E-mail |
|---|---|---|---|---|---|
| 2990000000019 | ACTIVE | Durand | Marie | 06 12 34 56 78 | marie.durand@example.fr |
| 2990000000026 | ACTIVE | Nguyen | Linh | +33 6 98 76 54 32 | linh.nguyen@example.fr |
| 2990000000033 | ACTIVE | Lefèvre | Théo | 07 55 44 33 22 | theo.lefevre@example.fr |
| 2990000000040 | PENDING_ACTIVATION | Martin | Paul | 06 00 00 00 01 | — |
| 2990000000057 | ACTIVE | Durand | Jacques | 06 12 34 56 99 | jacques.durand@example.fr |
| 2990000000071 | ACTIVE | Bernard | Sophie | 06 77 88 99 00 | sophie.bernard@example.fr |
| 2990000000088 | ACTIVE | García | Ana | 0033 6 11 22 33 44 | ana.garcia@example.fr |
Les cartes 2990000000064 (perdue, transférée) et 2990000000095 (annulée CGU)
n'ont volontairement pas de porteur.
Appels d'essai :
```bash
# Préfixe de nom -> les deux Durand, triés
curl -u pos:*** "http://localhost:8060/api/cards/lookup?name=dur"
# Restreint par préfixe de prénom
curl -u pos:*** "http://localhost:8060/api/cards/lookup?name=durand&firstName=j"
# Préfixe court multi-noms : "le" ne trouve que Lefèvre dans le jeu de démo
curl -u pos:*** "http://localhost:8060/api/cards/lookup?name=le"
# Téléphone, trois formats équivalents -> Marie Durand
curl -u pos:*** "http://localhost:8060/api/cards/lookup?phone=0612345678"
curl -u pos:*** "http://localhost:8060/api/cards/lookup?phone=%2B33612345678"
curl -u pos:*** "http://localhost:8060/api/cards/lookup?phone=06.12.34.56.78"
# Nom saisi sans accent -> García (0033 replié au stockage)
curl -u pos:*** "http://localhost:8060/api/cards/lookup?name=garcia"
# E-mail
curl -u pos:*** "http://localhost:8060/api/cards/lookup?email=Theo.Lefevre@Example.fr"
# Inconnu -> 200 []
curl -u pos:*** "http://localhost:8060/api/cards/lookup?phone=0699999999"
# Aucun critère -> 422
curl -u pos:*** "http://localhost:8060/api/cards/lookup"
```
## 7. Scénarios de recette conseillés côté impos
| # | Scénario | Attendu |
|---|---|---|
| L01 | Recherche par téléphone, format avec espaces | 200, 1 résultat, carte 2990000000019, avec phone et email |
| L02 | Même numéro en `+33` puis en `0033` | Mêmes résultats que L01 |
| L03 | Recherche par e-mail, casse mélangée | 200, 1 résultat |
| L04 | `name=dur` (préfixe) | 200, 2 résultats (Marie puis Jacques, tri nom/prénom) |
| L05 | `name=durand&firstName=j` | 200, 1 résultat, carte 2990000000057 |
| L06 | `name=garcia` (sans accent) | 200, 1 résultat (García) |
| L07 | `name=lef` (préfixe accentué) | 200, 1 résultat (Lefèvre) |
| L08 | Carte `PENDING_ACTIVATION` trouvée (Martin) puis tentative de burn | Lookup 200 ; réservation refusée 422 `ACCOUNT_STATUS` |
| L09 | Téléphone inconnu | 200, liste vide |
| L10 | Aucun paramètre | 422, raison « A phone, an e-mail or a name is required (§33.3) » |
| L11 | `firstName` seul | 422, même raison que L10 |
| L12 | imfid démarré avec `imfid.crm.url` configurée | 422, raison « Holder identity is managed by the CRM (§33.3) » |
| L13 | Appel sans authentification | 401 |
| L14 | Flux complet : lookup → earn → réservation → confirmation avec la carte trouvée | Identique au flux carte scannée |
## 8. Note sécurité et données personnelles
L'annuaire local n'est interrogeable que par critère normalisé (exact pour
téléphone et e-mail, préfixe pour le nom), sous authentification machine, avec
une liste bornée à 20 : il ne permet ni énumération ni export. Les identités ne
sont stockées et servies qu'en mode local (sans CRM) ; en mode CRM, imfid ne
détient et ne renvoie aucune donnée nominative. À l'écran caisse, l'identité,
le téléphone et l'e-mail renvoyés ne servent qu'à la discrimination et à la
confirmation par l'opérateur (voir 4) — jamais à être affichés ou lus au client.
