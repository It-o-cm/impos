# Spécification — Consommer la sortie enrichie du moteur (lot jumelé caisse)

**Projet** : impos · **Origine** : lots imvaluation « divers offre », « messages » et
« exports financiers » du 26/09/2026 — le moteur émet des champs nouveaux que la
caisse doit honorer pour que les lignes RFP existent de bout en bout ·
**Prérequis** : les chantiers imvaluation correspondants livrés.

## 1. Impression des remises — `printMode` (GB-01-07-10)

Chaque `DiscountApplication` de la réponse peut porter `printMode: "LINE"|"FOOTER"`.
Le ticket imprimé (et son double numérique) place la remise en conséquence : sous la
ligne d'article concernée (`LINE`, quand la remise a une cible de ligne unique) ou en
pied de ticket (`FOOTER`, le comportement actuel = défaut quand le champ est absent).
Absent ⇒ strictement l'existant, au caractère près.

## 2. Ventilation comptable — `accountingCode` (GB-01-06-05)

Le champ est recopié tel quel dans la remontée du ticket (le flux vers le nœud), au
niveau de chaque avantage. La caisse ne l'interprète pas — c'est une clé pour l'aval
comptable. Absent ⇒ remontée inchangée bit à bit.

## 3. Offres message — bloc `messages[]` (GB-01-07-05/06)

La réponse peut porter `messages: [{code, text, printMode}]`. La caisse :

- affiche le texte à l'écran vendeur au fil du scan (les messages `BELOW_TRIGGER`
  du moteur sont l'incitation « plus que X € ») ;
- l'imprime selon `printMode` (pied de ticket par défaut) ;
- aucun impact monétaire, aucun total modifié — un message ignoré ne fausse rien.

## 4. Détail par article de la consommation — `lines[]`

La dérivation ticket→déclaration (`POST /consumptions`) alimente le champ additif
`advantages[].lines: [{ean, amount}]` à partir du détail que le ticket possède déjà
(les quotes-parts par ligne de la valorisation retenue). Rétro-compatible : le moteur
accepte avec ou sans. Ajouter la phrase correspondante au §3 de
`spec-campagnes-budgets-bo.md`.

## 5. Cas normatifs

1. Remise `printMode LINE` → imprimée sous sa ligne ; `FOOTER` et absent → pied de
   ticket ; ticket sans les nouveaux champs → impression bit à bit identique.
2. `accountingCode` présent → recopié dans la remontée ; absent → remontée
   identique.
3. Message à l'écran pendant le scan (panier ouvert relancé à chaque évolution) ;
   imprimé à l'encaissement ; réponse sans `messages[]` → rien nulle part.
4. Déclaration de consommation avec `lines[]` conformes aux quotes-parts du ticket ;
   rejeu idempotent inchangé.
5. Mode dégradé (valorisation LOCAL) : aucun des quatre points ne s'active, zéro
   régression.

**Impacts** : impression du ticket, remontée du ticket, écran de vente (affichage
messages), dérivation de consommation, spec BO campagnes (une phrase). Rien d'autre —
ni les écrans BO, ni la livraison des flux. **Règles dures habituelles** (anglais,
Javadoc partout, portée stricte, suite complète verte).
