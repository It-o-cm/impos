# Spécification — Panneau de santé caisse (moitié impos du bloc Diffusion & supervision)

**Projet** : impos (la caisse) · **Statut** : validé en séance le 26/09/2026.
**Contexte** : la console de supervision des flux du magasin vit au nœud imedge
(`spec-diffusion-supervision.md`, repo imedge) — une caisse ne supervise pas le
magasin. Ce qui revient à la caisse est un panneau de santé de SES canaux à elle.

## 1. Le panneau

Une section « Santé des échanges » sur l'écran superviseur existant de la caisse
(pas de nouvelle page) :

1. **Remontée des tickets** vers le nœud : taille de la file, plus ancien élément,
   dernière erreur.
2. **File de consommation** (`ConsumptionDeliveryService`) : en attente, en échec,
   dernière erreur, bouton « Rejouer » (idempotence moteur = rejeu sûr).
3. **Livraison des flux au moteur** (rôle tenu par la caisse en pile démo) : état
   résumé par flux (à jour / en attente / erreur) — lecture seule, le pilotage
   fin est au nœud.
4. Rafraîchissement automatique (patron `valuation-refresh`, 2 s, suspendu onglet
   masqué).

## 2. Ce que le panneau ne fait PAS

Pas de planification, pas de livraison ciblée par flux, pas de vision magasin :
tout ça est la console du nœud. Le panneau répond à une seule question : « MA
caisse échange-t-elle correctement ? »

## 3. Cas normatifs (tests)

1. Files vides → tout au vert ; élément en échec injecté → visible avec son
   erreur ; « Rejouer » → vidé, pas de double comptage.
2. Rafraîchissement sans rechargement (test JS).
3. Accès : écran superviseur uniquement.

**Règles dures habituelles** : code et Javadoc en anglais, Javadoc sur toute
méthode sans exception, portée stricte, suite complète verte.
