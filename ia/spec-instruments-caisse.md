# Spécification — Instruments à la caisse (vérification, burn, remontée des attributions)

**Projet** : impos · **Statut** : arbitré le 28/09/2026 — chantier transverse, voir
`spec-registre-instruments.md` (imfid) et `spec-plafonds-attribution.md`
(imvaluation). **Prérequis** : le registre imfid livré.

## 1. Le rôle de la caisse

La caisse ne tient aucun état d'instrument : elle **vérifie** au scan (auprès du
registre imfid), **transmet** au panier ce qui est valide (le moteur valorise,
sans état, comme aujourd'hui via `couponCodes`), **déclare** l'usage à
l'encaissement, et **remonte** les attributions calculées. Même patron que les
promotions carte : en ligne au moment du scan, résilient au moment de déclarer.

## 2. Au scan d'un BA/coupon

1. `GET /api/instruments/{code}` chez imfid.
2. Refus PROPRE à l'écran (motif affiché) si : inconnu, `USED`, expiré, hors
   canal du poste, montant du ticket sous `minPurchaseAmount` (revérifié à
   l'encaissement — le ticket bouge encore).
3. Valide → le code entre dans le panier (`couponCodes`, existant) et la
   valorisation suit son cours ; un VOUCHER porteur de valeur suit le circuit de
   paiement de la caisse (hors valorisation — réserve documentée du RFP).
4. **Mode dégradé** (imfid injoignable) : paramètre d'enseigne
   `instruments.offline-policy` — `REJECT` (défaut : un droit nominatif ne se
   vérifie pas en confiance) ou `ACCEPT_LOG` (accepté, tracé, régularisé au
   retour). Le choix est une politique, pas un comportement câblé.

## 3. À l'encaissement et à l'annulation

- Ticket encaissé portant des instruments → `POST /api/instruments/{code}/use`
  (ticketId, storeCode, canal) — **asynchrone et rejouable** (file persistée,
  patron de la dérivation de consommation) ; le 409 « déjà utilisé ailleurs »
  remonte en supervision comme une anomalie à traiter, jamais en blocage de
  l'encaissement déjà fait.
- Annulation de ticket → `release` symétrique, même file.
- La remontée du ticket vers imfid transporte les attributions calculées par le
  moteur (grants de `advantages[]` + `grantTotals`) — imfid crédite, la caisse
  ne calcule rien.

## 4. Cas normatifs

1. Scan d'un BA `ISSUED` → accepté, valorisé, `use` déclaré à l'encaissement.
2. Scan d'un BA `USED` → refus affiché, panier intact.
3. Ticket annulé → `release`, le BA redevient utilisable.
4. imfid coupé au scan : `REJECT` → refus motivé ; `ACCEPT_LOG` → accepté et
   tracé, `use` part au retour d'imfid par la file.
5. `use` en échec réseau → rejoué (idempotence imfid), visible en supervision
   entre-temps ; 409 « autre ticket » → anomalie en supervision, encaissement
   non bloqué.
6. Ticket avec attributions → remontée complète, crédit vérifié de bout en bout
   sur la pile démo.

**Impacts** : client instruments (scan/use/release + file), politique offline,
écran de vente (refus motivés), remontée du ticket (attributions), supervision
(file instruments à côté des files existantes), jeu de démonstration (un BA et
un coupon d'exemple au registre). **Règles dures habituelles.**
