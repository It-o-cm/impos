# Instructions pour imedge — contrepartie « nœud magasin » de la campagne expert-caisse

La campagne a livré **la moitié caisse** (impos = rôle caisse uniquement) de l'étape 7
« Nœud magasin » et des intégrations de paiement de l'étape 6. Chaque point ci-dessous
est un **seam** déjà codé côté caisse (client injectable, endpoint, ou champ remonté) qui
attend son implémentation dans **imedge**. Tant que l'URL/endpoint n'est pas configuré, la
caisse reste en mode autonome (le stub ne résout rien) — c'est le câblage imedge qui
**active le mode connecté**. Les scénarios e2e correspondants sont tagués `[N]`.

## A. Endpoints qu'imedge doit EXPOSER (appelés par la caisse)

| Fonction | Propriété de conf caisse | Contrat attendu |
|---|---|---|
| **Rappel de document (EXP-90/91/92)** | `pos.recall.document-url` | `fetch(code)` → JSON `RecalledDocument` : `{ kind: DEVIS\|COMMANDE\|BON_VENTE\|CLICK_COLLECT, code, lines: [ {ean, label, quantity, unitPrice, vatRate} ], acompteAlreadyPaid }`. imedge **émet les codes** remis au client et **stocke** devis, commandes, bons de vente et paniers click&collect. `null`/inconnu → la caisse affiche « DOCUMENT INTROUVABLE ». Depuis LC-06-03, la réponse porte aussi `depositVatDetail` (ventilation TVA par taux de l'acompte perçu en caisse, forme compacte `rate:amount;...`, null sinon) et `totalAmount`, que le ticket final restitue (LC-06-03-08). |
| **Reprise inter-caisses (EXP-46)** | `pos.parking.node-url` | lookup d'un ticket en attente **par code, toutes caisses confondues** → renvoie le ticket `PARKED` (avec ses lignes) posé sur une autre caisse. Suppose qu'imedge **consolide les tickets PARKED** remontés par l'outbox des caisses. Hors ligne, la caisse ne reprend que ses propres tickets (règle assumée). |
| **Carte cadeau fournisseur (EXP-66)** | `pos.giftcard.issuer-url` | `query(cardNumber)` → `IssuerVerdict { valid: bool, balance: BigDecimal }` (interrogation du registre de l'émetteur : validité + solde). La caisse accepte le paiement à hauteur du solde. |
| **Resto Flash (EXP-67)** | `pos.restoflash.url` | `confirm(token, amount)` → booléen accepté/refusé (paiement titre-restaurant mobile). |
| **Passerelle SMS (EXP-48)** | `pos.sms.gateway-url` | `send(ticket, phone)` → envoie le ticket dématérialisé par SMS. (Le mail fonctionne déjà côté caisse.) |
| **Création de commande en caisse (LC-06-03-01)** | `pos.recall.document-url` | `pushOrder(order)` → `POST <url>/api/orders` avec le corps `RecalledDocument` (`kind=COMMANDE`, `code`, `lines`, `totalAmount`, `depositVatDetail`). imedge **stocke** la commande créée à la caisse : le back-office la liste (`/admin/orders`) et toute caisse du magasin la rappelle par son code (LC-06-03-07). La poussée est idempotente par code (même code = même ligne rafraîchie). L'acompte lui-même ne voyage PAS ici : c'est le mouvement `CUSTOMER_DEPOSIT` référencé (C) qui le porte. |
| **Règlement de la commande (LC-06-03-07)** | `pos.recall.document-url` | `settleOrder(code)` → `POST <url>/api/orders/{code}/settled`. À la clôture de la vente qui solde la commande, la caisse prévient le nœud, qui marque la commande payée puis retirée — le code ne peut plus être rappelé et payé deux fois. Tolérant : un échec de poussée n'entrave jamais la vente. |

## B. Message descendant EXP-20/89 — désormais en PULL (résout ta remarque topologie)

Topologie corrigée côté caisse : **la caisse SONDE le nœud**, le nœud n'a jamais besoin de
connaître l'adresse d'une caisse. imedge doit donc **EXPOSER** (au lieu de pousser) :

- **`GET <pos.message.node-url>?terminal=<C0x>` (EXP-20/89)** → JSON
  `{ text, question, options }` : `text` = message non perturbant (bandeau écran caissier),
  `question` (+ `options` séparées par `;`) = sondage. Réponse vide/pas de message → `text`
  vide ou corps vide (la caisse garde/efface le bandeau en conséquence). La caisse poll toutes
  les `pos.message.poll-seconds` (défaut 20 s), tolérant : nœud injoignable = pas de changement.
  *(Côté caisse : `BackOfficeMessageClient` + `BackOfficeMessagePoller`. Un endpoint push
  `POST /api/bo-message` reste disponible sur la caisse si un jour le nœud sait l'adresser,
  mais le PULL est le chemin nominal.)*
- **Réponse au sondage & message libre & demande de service caisse → BO (EXP-19/84/89)** :
  montent par le canal **existant** `POST /api/supervisor/call` — imedge accepte, en plus du
  motif, un **message libre**, la **réponse de sondage**, et un **motif de la liste administrée**
  `supervisor.service-reasons`.

## C. Données qu'imedge doit CONSOMMER (nouveaux champs remontés par l'outbox)

Ces champs voyagent sur les upserts déjà synchronisés (CashMovement, CardPayment, TechnicalEvent) :

- **`CashMovement.safeDeposit` (bool) — EXP-87** : prélèvement « vers le coffre ». **Le coffre
  est une entité du nœud** : imedge en tient le solde et impute ces dépôts. La caisse ne fait
  que produire les mouvements qui l'alimentent.
- **`CashMovement.orderReference` (String) — EXP-85** : acompte (`CUSTOMER_DEPOSIT`) rattaché à
  une commande. imedge impute l'acompte à la commande et le renvoie dans `acompteAlreadyPaid`
  du `RecalledDocument` (A) au rappel de la commande.
- **`CardPayment.card_entry_mode` (SANS_CONTACT / CODE) — EXP-54** : mode CB réel tracé sur le
  ticket → à exposer dans les états/reporting back-office.
- **`PaymentDto.depositVatDetail` + `PaymentDto.depositOrderCode` (LC-06-03-07/-08)** : le
  règlement « REPRISE ACOMPTE » d'un ticket qui solde une commande porte la ventilation TVA
  par taux de l'acompte perçu (calculée au moment de la perception, jamais recalculée) et le
  code de la commande — les comptes d'attente par taux que le back-office remonte en fin de
  période (LC-06-03-08) se lisent sur la ligne elle-même.
- **Événement `SOGEC_COMPENSATION` + `SogecPayment` — EXP-58** : à router vers la **compensation
  fournisseur SOGEC** (circuit distinct du coupon GS1 générique).
- **`SessionDto` — nouveaux champs de clôture opposable (EXP-82/83/95)** : `varianceEndorsedBy`
  (superviseur ayant validé un écart hors seuil — trace nominative du Z), `paperDeclaration`
  (déclaration des moyens papier comptés, JSON) et `floatingNoCount` (soir de fond flottant
  sans comptage complet). Désormais sérialisés sur le domaine SESSION → à ingérer/afficher
  dans le Z consolidé du nœud.

## D. Administration back-office : nouvelles clés `pos_settings`

La caisse comprend et reçoit ces clés **résolues** par le pull `SETTINGS`. L'écran d'admin
d'imedge doit permettre de les administrer (par échelon), sinon elles restent au défaut catalogue :

```
session.welcome-message          sale.waiting-label            cash.drawer-cap
payment.trd-card-cap             cash.variance-threshold       cash.float-mode
supervisor.service-reasons       gesture.endorsement-scope
gesture.endorsement-cancel-line  gesture.endorsement-cancel-last
gesture.endorsement-abandon      gesture.endorsement-return    gesture.endorsement-refund
gesture.endorsement-discount     gesture.endorsement-price-force
```

(Le libellé, le type et le défaut de chacune sont dans `PosSettingsService.CATALOG` côté impos —
à recopier tels quels dans le catalogue d'administration d'imedge.)

## E. Rappel de contexte

- **impos = caisse uniquement** : `pos.role` et tout l'appareillage échelon/central ont été
  retirés d'impos (entités Country/Enseigne/Pdv/EchelonSetting supprimées). imedge reste le
  détenteur des échelons (Pays → Enseigne → PDV) et sert les **SETTINGS déjà résolus** aux caisses.
- L'entité `Store` (identité magasin locale de la caisse) a été **conservée** dans impos.
