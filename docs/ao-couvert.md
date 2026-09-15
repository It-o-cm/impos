# Ce qui est coté couvert dans l'AO

*Extrait par script de `Questionnaire Fonctionnalites_Encaissem HD_7.xlsx`, colonne L, le 09/09/2026. Les onglets Libre-Service (634 lignes) et Digital (709) ne sont pas cotés et ne figurent donc pas ici.*

`1` = couvert intégralement. `1a` = couvert partiellement — le mécanisme existe, il lui manque quelque chose ; c'est le gisement le moins cher, puisqu'on étend au lieu de construire.

**201 lignes en `1`** et **445 en `1a`**, soit 646 sur les 1 527 des deux onglets cotés.

| Onglet | Lignes | `1` | `1a` | `3` |
|---|---:|---:|---:|---:|
| Ligne de caisses | 731 | 149 | 114 | 468 |
| Back Office | 796 | 52 | 331 | 413 |

## Ligne de caisses — 149 en `1`, 114 en `1a`

### 01 - Session Caisse — 10 en `1`, 6 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-01-01-01` | P1 | `1` | Touche ouverture caisse |
| `LC-01-01-02` | P1 | `1` | Ouverture caisse avec saisie n° opérateur + mot de passe |
| `LC-01-01-09` | P1 | `1` | Ouverture caisse avec scan code-barre unique pour chaque opérateur de caisse (badge) + mot de passe |
| `LC-01-01-10` | P1 | `1a` | Ouverture caisse avec saisie manuelle code-barre unique pour chaque opérateur de caisse (badge) + mot de passe |
| `LC-01-02-01` | P1 | `1` | Touche fermeture caisse |
| `LC-01-02-10` | P1 | `1a` | Fermeture caisse : tickets en attente |
| `LC-01-03-01` | P1 | `1a` | Touche pause |
| `LC-01-03-02` | P1 | `1` | Pause automatique |
| `LC-01-03-03` | P1 | `1` | Sortie de pause / déverrouillage de la caisse avec saisie du mot de passe |
| `LC-01-05-01` | P1 | `1a` | Autorisation superviseur par fonction/touche |
| `LC-01-05-02` | P1 | `1` | Autorisation superviseur avec saisie n° opérateur + mot de passe |
| `LC-01-05-07` | P1 | `1` | Opérateur superviseur connecté en caisse |
| `LC-01-06-01` | P1 | `1a` | (Ré)impression code-barre opérateur de caisse |
| `LC-01-06-02` | P2 | `1` | Changement de mot de passe en caisse |
| `LC-01-07-01` | P2 | `1` | Demande de service - touche |
| `LC-01-07-02` | P2 | `1a` | Demande de service - envoi requête |

### 02 - Enregistrement des articles — 36 en `1`, 33 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-02-01-01` | P1 | `1` | Article : scan |
| `LC-02-01-02` | P1 | `1` | Article : saisie code EAN |
| `LC-02-01-03` | P1 | `1` | Article : saisie code interne |
| `LC-02-02-01` | P1 | `1` | Article : saisie article en pesée |
| `LC-02-02-02` | P1 | `1a` | Article : affichage de l'article en pesée |
| `LC-02-03-01` | P1 | `1a` | Article : enregistrement article type 'quantité décimale' |
| `LC-02-03-02` | P1 | `1a` | Article : affichage article type 'quantité décimale' |
| `LC-02-03-08` | P1 | `1` | Message article "vente interdite" |
| `LC-02-04-01` | P1 | `1` | Article : affichage libellé |
| `LC-02-04-02` | P1 | `1` | Article : affichage EAN - Visu caisse et visu client |
| `LC-02-04-04` | P1 | `1` | Article : affichage EAN - Ticket de caisse |
| `LC-02-05-01` | P1 | `1a` | Article poids prix variable (PPV) : code-barre prix variable en Euros |
| `LC-02-05-03` | P1 | `1` | Article poids prix variable (PPV) : code-barre poids variable |
| `LC-02-05-04` | P1 | `1a` | Article : affichage de l'article poids prix variable (PPV) |
| `LC-02-06-01` | P1 | `1a` | Article : EAN inconnu - Erreur |
| `LC-02-06-02` | P2 | `1a` | Article : EAN inconnu - Saisie code famille |
| `LC-02-07-01` | P1 | `1` | Groupes d'articles |
| `LC-02-07-05` | P2 | `1a` | Groupes d'articles : défilement des articles |
| `LC-02-07-06` | P1 | `1` | Groupes d'articles : libellé article |
| `LC-02-07-07` | P1 | `1` | Groupes d'articles : libellé article non tronqué |
| `LC-02-07-11` | P1 | `1a` | Groupes d'articles : prix |
| `LC-02-07-12` | P3 | `1a` | Groupes d'articles : prix / kg |
| `LC-02-07-13` | P1 | `1` | Groupes d'articles : article en pesée |
| `LC-02-07-17` | P1 | `1` | Groupes d'articles : type article |
| `LC-02-09-01` | P1 | `1a` | Saisie et activation article dématérialisé |
| `LC-02-09-02` | P1 | `1a` | Annulation activation article dématérialisé |
| `LC-02-09-03` | P1 | `1a` | Saisie et activation article dématérialisé : impression ticket |
| `LC-02-09-04` | P1 | `1` | Consultation solde carte cadeau |
| `LC-02-10-03` | P1 | `1a` | Ligne article : affichage TVA sur ticket de caisse |
| `LC-02-11-01` | P1 | `1` | Annulation ligne |
| `LC-02-11-02` | P1 | `1` | Annulation ligne : dernier article |
| `LC-02-11-03` | P1 | `1a` | Annulation ligne : autre article |
| `LC-02-11-04` | P1 | `1a` | Annulation ligne : premier et unique article |
| `LC-02-11-05` | P1 | `1` | Annulation ligne : article quantité multiple |
| `LC-02-12-06` | P1 | `1a` | Annulation article : quantité multiple |
| `LC-02-12-07` | P2 | `1a` | Annulation article : affichage ticket de caisse |
| `LC-02-13-01` | P1 | `1a` | Touche "Quantité" |
| `LC-02-13-02` | P1 | `1` | Touche 'Quantité' : saisie quantité avec décimales et article de type "quantité décimale" |
| `LC-02-13-04` | P1 | `1a` | Touche 'Quantité' + scan article |
| `LC-02-13-05` | P1 | `1a` | Touche 'Quantité' + saisie code EAN : OK |
| `LC-02-13-06` | P1 | `1a` | Touche 'Quantité' + saisie code interne : OK |
| `LC-02-13-07` | P1 | `1a` | Touche 'Quantité' + groupe article : OK |
| `LC-02-13-09` | P1 | `1` | Touche 'Quantité' + article en pesée : Erreur |
| `LC-02-13-10` | P2 | `1` | Touche 'Quantité' + article PPV : Erreur |
| `LC-02-13-11` | P2 | `1` | Modification de la quantité d'un article |
| `LC-02-13-12` | P1 | `1a` | Répétition dernier article |
| `LC-02-13-13` | P1 | `1a` | Répétition dernier article non spécifique : OK |
| `LC-02-13-15` | P1 | `1a` | Répétition dernier article en pesée : Erreur |
| `LC-02-13-16` | P1 | `1a` | Répétition dernier article PPV : Erreur |
| `LC-02-13-18` | P1 | `1a` | Affichage ligne article avec quantités |
| `LC-02-13-19` | P2 | `1a` | Affichage ligne article avec quantités : regroupement sur visu caisse et visu client |
| `LC-02-13-20` | P2 | `1a` | Affichage ligne article avec quantités : regroupement sur ticket de caisse |
| `LC-02-14-02` | P1 | `1` | Vérification prix - Ajout de l'article |
| `LC-02-14-03` | P2 | `1a` | Recherche Article - Résultat |
| `LC-02-14-04` | P2 | `1` | Recherche Article - Résultat libellé |
| `LC-02-14-05` | P2 | `1` | Recherche Article - Résultat EAN |
| `LC-02-14-06` | P2 | `1` | Recherche Article - Résultat prix |
| `LC-02-14-07` | P2 | `1` | Recherche Article - Enregistrement article |
| `LC-02-14-08` | P2 | `1` | Recherche Article par libellé |
| `LC-02-14-09` | P1 | `1` | Vérification prix |
| `LC-02-14-10` | P1 | `1` | Vérification prix - Touche session caisse ouverte |
| `LC-02-14-13` | P1 | `1` | Vérification prix - Saisie manuelle EAN |
| `LC-02-14-14` | P1 | `1` | Vérification prix - Saisie manuelle code interne |
| `LC-02-14-15` | P1 | `1` | Vérification prix - Affichage informations article |
| `LC-02-15-06` | P1 | `1` | Forçage prix : article déjà enregistré |
| `LC-02-15-07` | P1 | `1` | Forçage prix : périmètre d'application |
| `LC-02-15-08` | P1 | `1` | Forçage prix : affichage prix visu caisse |
| `LC-02-15-09` | P2 | `1a` | Forçage prix : affichage prix visu client |
| `LC-02-15-10` | P2 | `1a` | Forçage prix : affichage prix ticket de caisse |

### 03 - Générosité — 27 en `1`, 9 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-03-01-01` | P1 | `1` | Calcul des promotions à l'enregistrement article |
| `LC-03-01-02` | P1 | `1` | Calcul des promotions au total |
| `LC-03-01-03` | P1 | `1` | Calcul des promotions - mode dégradé |
| `LC-03-01-04` | P1 | `1a` | Affichage des promotions à l'article |
| `LC-03-01-05` | P1 | `1` | Affichage des promotions - recalcul |
| `LC-03-02-01` | P1 | `1` | Remise article - Saisie % |
| `LC-03-02-03` | P2 | `1` | Rabais article - Saisie montant € |
| `LC-03-02-05` | P2 | `1` | Remise globale - Saisie % |
| `LC-03-02-06` | P3 | `1` | Rabais global - Saisie montant € |
| `LC-03-02-07` | P2 | `1` | Remise article % - seuil maximum |
| `LC-03-02-08` | P1 | `1` | Autorisation superviseur |
| `LC-03-02-10` | P1 | `1a` | Remise - rabais article déjà remisé |
| `LC-03-02-11` | P1 | `1` | Remise - rabais global(e) - ventilation |
| `LC-03-02-12` | P2 | `1a` | Rabais article - seuil maximum |
| `LC-03-02-13` | P2 | `1` | Remise globale % - seuil maximum |
| `LC-03-02-14` | P2 | `1a` | Rabais global - seuil maximum |
| `LC-03-02-15` | P1 | `1a` | Remise - rabais global(e) avec articles déjà remisés |
| `LC-03-04-01` | P1 | `1` | Scan carte fidélité |
| `LC-03-04-02` | P1 | `1` | Saisie manuelle EAN carte fidélité |
| `LC-03-04-03` | P1 | `1` | Scan carte fidélité - Avant l'enregistrement des articles |
| `LC-03-04-04` | P1 | `1` | Scan carte fidélité - Pendant l'enregistrement des articles |
| `LC-03-04-05` | P1 | `1a` | Scan carte fidélité - Avant règlement partiel |
| `LC-03-04-06` | P2 | `1a` | Scan carte fidélité - Après règlement partiel |
| `LC-03-04-08` | P1 | `1a` | Scan carte fidélité - Changement de carte fidélité |
| `LC-03-04-10` | P1 | `1` | Affichage informations client visu caisse - nom |
| `LC-03-04-11` | P1 | `1` | Affichage informations client visu caisse - numéro carte |
| `LC-03-04-12` | P1 | `1` | Affichage informations client visu caisse - solde |
| `LC-03-04-14` | P1 | `1` | Recherche client fidélisé |
| `LC-03-04-15` | P1 | `1` | Recherche client fidélisé par numéro de téléphone |
| `LC-03-04-16` | P1 | `1` | Recherche client fidélisé par numéro de téléphone - format |
| `LC-03-04-19` | P1 | `1` | Recherche client fidélisé - affichage résultat |
| `LC-03-04-21` | P1 | `1` | Recherche client fidélisé - information client |
| `LC-03-04-22` | P1 | `1` | Recherche client fidélisé - Changement de carte fidélité |
| `LC-03-05-01` | P1 | `1` | Calcul avantages fidélité |
| `LC-03-05-02` | P1 | `1` | Calcul avantages fidélité - mode dégradé |
| `LC-03-05-03` | P1 | `1a` | Réception avantages fidélité |

### 04 - Gestion transactions — 13 en `1`, 10 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-04-01-01` | P1 | `1` | Mise en attente ticket |
| `LC-04-01-02` | P1 | `1a` | Mise en attente ticket : impression ticket |
| `LC-04-01-03` | P1 | `1a` | Gestion réglementaire tickets en attente |
| `LC-04-02-01` | P1 | `1a` | Reprise ticket en attente : scan |
| `LC-04-02-03` | P2 | `1a` | Reprise ticket en attente : sélection ticket |
| `LC-04-02-04` | P1 | `1` | Reprise ticket en attente même caisse |
| `LC-04-02-06` | P2 | `1` | Reprise ticket en attente : remises/rabais manuels |
| `LC-04-02-07` | P1 | `1` | Reprise ticket en attente : promotions |
| `LC-04-02-08` | P1 | `1` | Reprise ticket en attente : gestion des articles |
| `LC-04-02-09` | P1 | `1` | Reprise ticket en attente : mode dégradé |
| `LC-04-04-01` | P1 | `1a` | Abandon ticket |
| `LC-04-04-02` | P1 | `1` | Abandon ticket : phase enregistrement des articles |
| `LC-04-04-03` | P1 | `1` | Abandon ticket : gestion manuelle article dématérialisé |
| `LC-04-04-04` | P2 | `1` | Abandon ticket : gestion automatique article dématérialisé |
| `LC-04-04-05` | P1 | `1` | Abandon ticket : phase paiement |
| `LC-04-04-07` | P2 | `1a` | Abandon ticket : gestion automatique paiement partiel sans appel externe |
| `LC-04-04-09` | P2 | `1a` | Abandon ticket : gestion automatique paiement partiel avec appel externe |
| `LC-04-05-01` | P1 | `1a` | Recherche ticket |
| `LC-04-05-02` | P1 | `1a` | Recherche ticket - Affichage résultats |
| `LC-04-05-03` | P1 | `1` | Recherche ticket - Sélection ticket |
| `LC-04-05-04` | P1 | `1` | Recherche ticket - Impression duplicata |
| `LC-04-05-05` | P1 | `1a` | Recherche ticket - Affichage ticket |
| `LC-04-07-01` | P1 | `1` | Total |

### 05 - Retours — 7 en `1`, 10 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-05-01-02` | P1 | `1` | Ticket retour dédié : touche |
| `LC-05-02-01` | P1 | `1a` | Enregistrement retour article : méthode saisie |
| `LC-05-02-05` | P1 | `1` | Retour depuis ticket d'origine : saisie manuelle ticket d'origine |
| `LC-05-02-06` | P1 | `1` | Retour depuis ticket d'origine : recherche du ticket d'origine |
| `LC-05-02-07` | P1 | `1a` | Retour depuis ticket d'origine : vérification du PDV du ticket d'origine |
| `LC-05-02-10` | P1 | `1` | Retour depuis ticket d'origine : contrôle des articles retournés |
| `LC-05-02-11` | P1 | `1` | Retour depuis ticket d'origine : enregistrement des articles ticket d'origine identifié |
| `LC-05-02-12` | P1 | `1a` | Retour depuis ticket d'origine : prix article retourné ticket d'origine identifié |
| `LC-05-02-13` | P1 | `1a` | Retour depuis ticket d'origine : recalcul des promotions et de la générosité |
| `LC-05-03-01` | P1 | `1a` | Retour et désactivation article dématérialisé |
| `LC-05-06-02` | P1 | `1` | Remboursement ticket négatif : espèces |
| `LC-05-06-03` | P1 | `1a` | Remboursement ticket négatif : carte bancaire Verifone FR (France) |
| `LC-05-06-06` | P1 | `1` | Remboursement ticket négatif : avoir |
| `LC-05-06-07` | P1 | `1a` | Remboursement ticket négatif : impression ticket avoir |
| `LC-05-06-08` | P2 | `1a` | Remboursement ticket négatif : autre moyen de paiement |
| `LC-05-07-01` | P1 | `1a` | Ticket retour dédié : impression ticket retour avec mention |
| `LC-05-07-02` | P1 | `1a` | Ticket retour dédié ou ticket contenant des retours : impression ticket retour détail recalculs |

### 07 - Paiement — 17 en `1`, 16 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-07-01-01` | P1 | `1` | Retour à l'enregistrement des articles |
| `LC-07-01-02` | P1 | `1a` | Retour à l'enregistrement des articles : gestion manuelle paiement partiel sans appel externe |
| `LC-07-01-03` | P3 | `1a` | Retour à l'enregistrement des articles : gestion automatique paiement partiel sans appel externe |
| `LC-07-01-04` | P1 | `1a` | Retour à l'enregistrement des articles : gestion manuelle paiement partiel avec appel externe |
| `LC-07-01-05` | P3 | `1a` | Retour à l'enregistrement des articles : gestion automatique paiement partiel avec appel externe |
| `LC-07-02-01` | P1 | `1` | Saisie montant libre |
| `LC-07-02-03` | P1 | `1a` | Touche montant exact |
| `LC-07-02-08` | P1 | `1` | Rendu monnaie |
| `LC-07-04-01` | P1 | `1a` | Paiement Carte Bancaire : Verifone FR (France) |
| `LC-07-05-03` | P1 | `1a` | Paiement Chèque manuel : impression par l'encaissement (France) |
| `LC-07-05-04` | P1 | `1` | Paiement Chèque manuel : remplissage manuel (France) |
| `LC-07-06-01` | P3 | `1` | Touche dédiée au paiement TRD |
| `LC-07-07-01` | P1 | `1` | Abandon requête en cours |
| `LC-07-07-02` | P1 | `1a` | Abandon requête en cours - sollicitation répétée |
| `LC-07-10-01` | P1 | `1` | Paiement Décagnottage |
| `LC-07-10-02` | P1 | `1a` | Paiement Décagnottage : solde |
| `LC-07-10-03` | P1 | `1` | Paiement Décagnottage : contrôle montant utilisable |
| `LC-07-10-04` | P1 | `1` | Paiement Décagnottage : pas d'identification client |
| `LC-07-10-08` | P1 | `1a` | Paiement Décagnottage : impression de ticket |
| `LC-07-11-01` | P1 | `1a` | Paiement carte cadeau |
| `LC-07-11-02` | P1 | `1` | Paiement carte cadeau : montant utilisable |
| `LC-07-11-03` | P1 | `1a` | Paiement carte cadeau : impression ticket |
| `LC-07-11-04` | P1 | `1` | Annulation règlement partiel carte cadeau |
| `LC-07-12-01` | P1 | `1a` | Paiement sans appel externe avec touche |
| `LC-07-12-02` | P1 | `1` | Paiement sans appel externe avec scan code-barre |
| `LC-07-12-04` | P1 | `1a` | Annulation règlement partiel sans appel externe |
| `LC-07-12-05` | P1 | `1` | Règlement partiel |
| `LC-07-13-01` | P1 | `1` | Contrôle éligibilité articles |
| `LC-07-16-01` | P1 | `1a` | Ouverture tiroir caisse |
| `LC-07-17-01` | P3 | `1` | Don en caisse |
| `LC-07-17-02` | P3 | `1a` | Don en caisse - montant arrondi proposé |
| `LC-07-17-03` | P3 | `1` | Don en caisse - enregistrement article Don |
| `LC-07-17-04` | P3 | `1` | Don en caisse - article TVA exonérée |

### 08 - Tickets et factures — 7 en `1`, 10 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-08-01-01` | P1 | `1` | Ticket de caisse - génération |
| `LC-08-01-02` | P1 | `1a` | Ticket de caisse - contenu |
| `LC-08-01-03` | P1 | `1a` | Ticket de caisse - code-barre d'identification |
| `LC-08-02-01` | P1 | `1a` | Mise à disposition du ticket brut à un service externe |
| `LC-08-02-03` | P1 | `1` | Mise à disposition du ticket - mode dégradé |
| `LC-08-02-04` | P1 | `1a` | Envoi des tickets par email |
| `LC-08-02-05` | P1 | `1a` | Envoi des tickets par email - activation |
| `LC-08-02-06` | P1 | `1a` | Envoi des tickets par email - tickets concernés |
| `LC-08-02-11` | P1 | `1a` | Envoi des tickets par email - saisie adresse email |
| `LC-08-02-12` | P3 | `1` | Affichage QR-code ticket dématérialisé |
| `LC-08-03-08` | P1 | `1a` | Impression forcée de tickets techniques |
| `LC-08-03-13` | P1 | `1a` | Cumul dématérialisation et impression conditionnelle |
| `LC-08-05-01` | P1 | `1` | Duplicata dernier ticket de caisse |
| `LC-08-05-02` | P2 | `1` | Duplicata dernier ticket de caisse - gestion ticket original vs duplicata |
| `LC-08-05-03` | P1 | `1` | Duplicata ancien ticket de caisse / facture |
| `LC-08-05-04` | P1 | `1a` | Duplicata ancien ticket de caisse / facture - saisie informations |
| `LC-08-05-06` | P2 | `1` | Duplicata ancien ticket de caisse / facture - date antérieure |

### 09 - Afficheur caisse — 7 en `1`, 3 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-09-01-03` | P1 | `1a` | Interface visu caisse - Regroupement de touches |
| `LC-09-01-06` | P1 | `1` | Clavier virtuel |
| `LC-09-01-08` | P1 | `1` | Clavier virtuel - affichage automatique |
| `LC-09-01-10` | P1 | `1a` | Clavier virtuel - caractères spéciaux |
| `LC-09-01-12` | P1 | `1a` | Afficheur caisse - affichage transaction en cours : montant éligible titres restaurant |
| `LC-09-01-19` | P3 | `1` | Interface visu caisse - Mode sombre |
| `LC-09-01-20` | P1 | `1` | Interface visu caisse - gestion symbole 'décimale' |
| `LC-09-01-21` | P1 | `1` | Interface visu caisse - saisie et restitution informations |
| `LC-09-01-22` | P1 | `1` | Interface visu caisse - gestion messages |
| `LC-09-01-23` | P1 | `1` | Clavier virtuel - type adapté au contexte |

### 10 - Afficheur client — 6 en `1`, 2 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-10-01-02` | P1 | `1` | Affichage transaction en cours : liste des articles |
| `LC-10-01-03` | P1 | `1a` | Affichage transaction en cours : remises / rabais / promotions |
| `LC-10-01-15` | P1 | `1` | Affichage transaction en cours : montant total à payer |
| `LC-10-01-17` | P1 | `1a` | Affichage transaction en cours : moyens de paiement |
| `LC-10-01-18` | P1 | `1` | Affichage transaction en cours : reste à payer |
| `LC-10-01-19` | P1 | `1` | Affichage transaction en cours : rendu monnaie |
| `LC-10-01-20` | P1 | `1` | Message caisse ouverte |
| `LC-10-01-21` | P1 | `1` | Message caisse fermée |

### 11 - Code-barres 1D/2D — 5 en `1`, 2 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-11-01-01` | P1 | `1a` | Interprétation EAN-8 |
| `LC-11-01-02` | P1 | `1` | Interprétation EAN-13 |
| `LC-11-01-03` | P3 | `1a` | Génération EAN-13 |
| `LC-11-01-04` | P1 | `1` | Interprétation code-128 |
| `LC-11-01-05` | P1 | `1` | Interprétation code-128 - format alphanumérique |
| `LC-11-01-06` | P1 | `1` | Interprétation code-128 - longueur |
| `LC-11-02-05` | P1 | `1` | Génération QR Code dynamique non normé GS1 |

### 12 - Gestion du tiroir — 5 en `1`, 8 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-12-02-01` | P1 | `1` | Comptage Espèces |
| `LC-12-02-02` | P1 | `1` | Comptage Espèces - saisie quantité par dénomination |
| `LC-12-02-03` | P1 | `1` | Comptage Espèces - vérification et correction |
| `LC-12-02-05` | P1 | `1a` | Comptage Espèces - contrôle du comptage superviseur |
| `LC-12-03-01` | P1 | `1a` | Prélèvement |
| `LC-12-03-04` | P1 | `1a` | Prélèvement - Espèces |
| `LC-12-03-05` | P1 | `1a` | Prélèvement - Espèces - vérification et correction |
| `LC-12-03-07` | P1 | `1a` | Prélèvement - impression ticket |
| `LC-12-04-01` | P1 | `1a` | Apport caisse |
| `LC-12-04-03` | P1 | `1` | Apport caisse - actualisation fonds de caisse |
| `LC-12-07-01` | P1 | `1a` | CA théorique |
| `LC-12-07-02` | P1 | `1a` | CA théorique - Affichage |
| `LC-12-07-03` | P1 | `1` | CA théorique - Impression ticket |

### 13 - Maintenance — 6 en `1`, 5 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-13-02-09` | P1 | `1a` | Utilisation caisse sans périphérique - imprimante non activée |
| `LC-13-02-10` | P1 | `1a` | Utilisation caisse sans périphérique - erreur imprimante |
| `LC-13-02-11` | P1 | `1` | Utilisation caisse sans périphérique - scanner non activé |
| `LC-13-02-12` | P1 | `1a` | Utilisation caisse sans périphérique - erreur scanner |
| `LC-13-02-13` | P1 | `1` | Utilisation caisse sans périphérique - balance non activée |
| `LC-13-02-14` | P1 | `1a` | Utilisation caisse sans périphérique - erreur balance |
| `LC-13-04-01` | P1 | `1` | Mise à jour données en arrière-plan |
| `LC-13-05-02` | P1 | `1` | Ouverture caisse en mode école |
| `LC-13-05-03` | P1 | `1` | Indicateur visuel mode école |
| `LC-13-05-04` | P1 | `1a` | Blocage touches/fonctionnalités |
| `LC-13-05-05` | P1 | `1` | Mention mode école tickets |

### 14 - Métrologie / Tare / Contenants — 3 en `1`, 0 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `LC-14-03-02` | P1 | `1` | Enchainement de pesées article |
| `LC-14-03-03` | P1 | `1` | Poids : nombre de décimales |
| `LC-14-03-04` | P1 | `1` | Unité de base de masse kilogramme : symbole "kg" |

## Back Office — 52 en `1`, 331 en `1a`

### 01 - Connexion — 3 en `1`, 18 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-01-01-01` | P1 | `1a` | Comptes Administrateur |
| `BO-01-01-02` | P1 | `1a` | Comptes Utilisateur CRUD |
| `BO-01-01-03` | P1 | `1a` | Comptes Utilisateur / PDV |
| `BO-01-01-04` | P1 | `1a` | Gestion de la Langue Paramétrage |
| `BO-01-01-06` | P1 | `1a` | Informations Compte Locaux Utilisateurs |
| `BO-01-02-03` | P1 | `1a` | Authentification Comptes Locaux |
| `BO-01-02-04` | P1 | `1` | Authentification Mode Standard |
| `BO-01-02-06` | P1 | `1a` | Authentification Mode allégé - Badge |
| `BO-01-02-09` | P1 | `1a` | Authentification Administration Modes |
| `BO-01-02-10` | P1 | `1a` | Changement du mot de passe Utilisateur |
| `BO-01-02-11` | P1 | `1a` | Saisie du nouveau mot de passe |
| `BO-01-03-01` | P1 | `1` | Profils CRUD |
| `BO-01-03-02` | P1 | `1` | Profil Affectation |
| `BO-01-03-03` | P1 | `1a` | Profils Affectations |
| `BO-01-03-04` | P1 | `1a` | Profils Affectation PDV |
| `BO-01-04-01` | P1 | `1a` | Accès Fonctionnalités |
| `BO-01-04-02` | P1 | `1a` | Accès Fonctionnalités Options |
| `BO-01-04-03` | P3 | `1a` | Accès Fonctionnalités Validation |
| `BO-01-04-05` | P3 | `1a` | Accès Fonctionnalités Validation Tiers |
| `BO-01-04-06` | P3 | `1a` | Accès Fonctionnalités Agregats |
| `BO-01-04-08` | P1 | `1a` | Configuration Favoris |

### 02 - Référentiels — 8 en `1`, 31 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-02-01-01` | P1 | `1a` | Gestion des nomenclatures |
| `BO-02-01-08` | P3 | `1a` | Affichage Nomenclature |
| `BO-02-02-01` | P2 | `1a` | Intégration des TVA propres à chaque pays |
| `BO-02-03-01` | P1 | `1` | Intégration article - libellé commercial |
| `BO-02-03-02` | P1 | `1` | Intégration article - libellé encaissement |
| `BO-02-03-03` | P1 | `1a` | Intégration Article : - Code EAN |
| `BO-02-03-04` | P2 | `1a` | Intégration Article : - Code Interne |
| `BO-02-03-05` | P1 | `1a` | Intégration article - appel prix |
| `BO-02-03-06` | P1 | `1a` | Intégration article - article éligible Titre Restaurant |
| `BO-02-03-07` | P1 | `1` | Intégration article - article vente interdite |
| `BO-02-03-08` | P1 | `1` | Intégration article - article contrôle d'âge |
| `BO-02-03-09` | P1 | `1a` | Intégration article - remise interdite |
| `BO-02-03-11` | P1 | `1a` | Intégration article - article retrait rappel |
| `BO-02-03-12` | P1 | `1a` | Configuration Article: code article Ticket |
| `BO-02-03-14` | P1 | `1a` | Intégration article : - nomenclature |
| `BO-02-03-17` | P1 | `1a` | Intégration article : - unité de mesure |
| `BO-02-03-18` | P1 | `1a` | Intégration article - attributs |
| `BO-02-03-21` | P1 | `1a` | Intégration article - prix à saisir |
| `BO-02-03-22` | P1 | `1a` | Intégration article - quantité à saisir |
| `BO-02-03-23` | P1 | `1a` | Intégration article - quantité décimale à saisir |
| `BO-02-03-24` | P1 | `1a` | Intégration article - article pesé |
| `BO-02-03-25` | P1 | `1a` | Gestion des articles - article encombrant |
| `BO-02-03-26` | P2 | `1a` | Intégration article - TVA exonérée |
| `BO-02-03-27` | P2 | `1a` | Intégration des articles : - TVA exonéré |
| `BO-02-03-28` | P1 | `1a` | Affichage des données basiques des articles |
| `BO-02-03-29` | P1 | `1` | Affichage des différents attributs des articles |
| `BO-02-03-30` | P1 | `1a` | Affichage des articles - Recherche article |
| `BO-02-03-31` | P1 | `1a` | Critères de sélection articles |
| `BO-02-03-32` | P1 | `1` | Administration des Attributs article |
| `BO-02-03-33` | P1 | `1a` | Administration des données Article |
| `BO-02-03-34` | P1 | `1` | Affichage des offres de l'article |
| `BO-02-03-35` | P1 | `1` | Affichage Historique Prix de vente |
| `BO-02-03-45` | P1 | `1a` | Configuration Article: Code article sur l'écran caisse |
| `BO-02-04-19` | P1 | `1a` | Administration : - Information Documents (Portugal) |
| `BO-02-05-01` | P1 | `1a` | Format et gestion du numéro de Point De Vente (PDV) |
| `BO-02-05-02` | P1 | `1a` | Changement d'enseigne d'un Point De Vente |
| `BO-02-05-03` | P1 | `1a` | Import des Points De Vente avec leur organisation via API |
| `BO-02-05-04` | P1 | `1a` | Héritage des règles/paramètres par pays et par enseigne |
| `BO-02-05-05` | P1 | `1a` | Rattachement de plusieurs PDV à un même Adhérent |

### 03 - Paramétrage — 8 en `1`, 121 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-03-01-01` | P1 | `1a` | Création des groupes articles |
| `BO-03-01-02` | P1 | `1` | Gestion des groupes articles - modification/suppression |
| `BO-03-01-03` | P2 | `1a` | Gestion des groupes articles - duplication |
| `BO-03-01-04` | P1 | `1a` | Gestion des groupes articles - Ajout Article |
| `BO-03-01-05` | P1 | `1a` | Gestion des groupes articles - Niveau 0 ajout Article |
| `BO-03-01-06` | P1 | `1` | Gestion de l'affichage en caisse |
| `BO-03-01-07` | P1 | `1` | Affichage permanent en caisse |
| `BO-03-01-08` | P1 | `1a` | Taille des touches |
| `BO-03-01-10` | P3 | `1a` | Ordre d'affichage en caisse: mode alphabétique |
| `BO-03-01-11` | P1 | `1a` | Ordre d'affichage en caisse: mode personnalisé |
| `BO-03-01-13` | P3 | `1a` | Ordre d'affichage en caisse: en mode volume de vente |
| `BO-03-01-15` | P1 | `1a` | Paramétrage des images associées à des articles (PLU) |
| `BO-03-01-16` | P1 | `1a` | Gestion des groupes articles - intégration en masse |
| `BO-03-01-17` | P1 | `1a` | Gestion des groupes articles - désactivation article |
| `BO-03-01-24` | P1 | `1a` | Import images AVEC le redimensionnement attendu : -taille - type de l'image -résolution |
| `BO-03-02-01` | P1 | `1a` | Mode de règlement - Ajout |
| `BO-03-02-02` | P1 | `1a` | Mode de règlement - Suppression |
| `BO-03-02-03` | P1 | `1a` | Paramétrage mode de règlement - Activation |
| `BO-03-02-04` | P1 | `1a` | Mode de règlement - Nombre de règlements |
| `BO-03-02-05` | P3 | `1a` | Mode de règlement - Impression liste des modes de règlement |
| `BO-03-02-10` | P1 | `1a` | Mode de règlement - Montant maximum dans le ticket et contrôle |
| `BO-03-02-15` | P1 | `1a` | Mode de règlement - autorisé ou non pour le remboursement |
| `BO-03-02-16` | P1 | `1a` | Mode de règlement - autorisé ou non le rendu monnaie et avec lequel |
| `BO-03-02-17` | P1 | `1a` | Mode de règlement - déclaration automatique caissière |
| `BO-03-02-18` | P1 | `1a` | Mode de règlement - prélèvement automatique |
| `BO-03-02-19` | P1 | `1a` | Mode de règlement - ouverture tiroir |
| `BO-03-02-20` | P1 | `1a` | Mode de règlement - autoriser dépense /apport |
| `BO-03-02-22` | P1 | `1a` | Mode de règlement - Autoriser le règlement d'être en fond de caisse |
| `BO-03-02-23` | P1 | `1a` | Mode de règlement - Afficher montant Total par défaut à valider |
| `BO-03-02-25` | P2 | `1a` | Mode de règlement - Affichage détaillé pour rapport de prélèvement |
| `BO-03-02-30` | P1 | `1a` | Mode de règlement - remontée paiement Fidélité |
| `BO-03-02-37` | P1 | `1a` | Mode de règlement - Type carte carte prépayée |
| `BO-03-02-38` | P1 | `1a` | Mode de règlement - Eligibilité |
| `BO-03-02-41` | P1 | `1a` | Paramétrage fonds de caisse espèces |
| `BO-03-02-42` | P3 | `1a` | Paramétrage rouleaux de monnaie |
| `BO-03-02-44` | P1 | `1a` | Type Carte bancaire |
| `BO-03-02-45` | P1 | `1a` | Dépense - Création / modification / suppression |
| `BO-03-02-46` | P1 | `1a` | Dépense - Activation |
| `BO-03-02-47` | P1 | `1a` | Dépense - Montant |
| `BO-03-02-48` | P2 | `1a` | Mode dégradé monétique manuel - Activation Point De Vente (France) |
| `BO-03-02-49` | P3 | `1a` | Activation Mode dégradé monétique manuel - Autorisation superviseur requise (France) |
| `BO-03-02-50` | P3 | `1a` | Désactivation Mode dégradé monétique manuel - Autorisation superviseur requise (France) |
| `BO-03-03-02` | P1 | `1a` | Configuration modèles des documents : ENTETE Facture /Bon de Livraison /Ticket |
| `BO-03-03-03` | P1 | `1a` | Configuration des modèles des documents : Lignes Articles |
| `BO-03-03-04` | P1 | `1a` | Configuration des modèles des documents : tableau TVA |
| `BO-03-03-05` | P1 | `1a` | Configuration des modèles des documents : Total |
| `BO-03-03-06` | P1 | `1a` | Configuration des modèles des documents : Ticket en attente |
| `BO-03-03-08` | P1 | `1a` | Configuration des modèles des documents : Avoir |
| `BO-03-03-19` | P1 | `1a` | Paramétrage TICKET - ligne Articles EAN |
| `BO-03-03-20` | P1 | `1a` | Paramétrage TICKET - Duplicata |
| `BO-03-03-22` | P1 | `1a` | Paramétrage TICKET - Remise/Rabais Article |
| `BO-03-03-23` | P1 | `1a` | Paramétrage TICKET - Promotions Articles |
| `BO-03-03-24` | P1 | `1a` | Paramétrage TICKET - Remises Immédiates |
| `BO-03-03-25` | P1 | `1a` | Paramétrage TICKET - Avantages Fidélité |
| `BO-03-04-08` | P1 | `1a` | Modèle entête/pied document |
| `BO-03-04-13` | P1 | `1a` | Configuration du nom du document en cas de réimpression |
| `BO-03-04-19` | P1 | `1a` | Numéro du document |
| `BO-03-04-25` | P1 | `1a` | Paramétrage nom de document Duplicata (Portugal) |
| `BO-03-04-28` | P1 | `1a` | Définition du code des documents par type et par caisse (Portugal) |
| `BO-03-05-01` | P1 | `1a` | Configuration entête Ticket |
| `BO-03-05-03` | P1 | `1a` | Configuration pied Ticket |
| `BO-03-05-05` | P1 | `1a` | Configuration des lignes entête / pied Ticket |
| `BO-03-06-01` | P1 | `1a` | Code-barres |
| `BO-03-06-02` | P1 | `1a` | Type de code-barres : définition article |
| `BO-03-06-03` | P1 | `1a` | Type de code-barres : définition article PPV PRIX |
| `BO-03-06-04` | P1 | `1a` | Type de code-barres : définition article PPV POIDS |
| `BO-03-06-06` | P1 | `1` | Type de code-barres : définition article Check Digit |
| `BO-03-06-08` | P1 | `1a` | Type de code-barres : Moyen de Paiement |
| `BO-03-06-09` | P1 | `1a` | Type de code-barres : alphanumérique |
| `BO-03-06-10` | P1 | `1a` | Code-barres : longueur |
| `BO-03-06-11` | P1 | `1a` | Code-barres : Montant |
| `BO-03-06-12` | P1 | `1a` | Code-barres : Montant 9999 |
| `BO-03-06-13` | P1 | `1a` | Code-barres : Prix en décimales |
| `BO-03-06-33` | P1 | `1a` | Code-barres : N° Ticket |
| `BO-03-06-34` | P1 | `1a` | Code-barres : longueur N° Ticket |
| `BO-03-06-36` | P1 | `1a` | Code-barres : longueur N° séquence |
| `BO-03-06-37` | P1 | `1a` | Code-barres : N° TPV |
| `BO-03-06-38` | P1 | `1a` | Code-barres : longueur N° TPV |
| `BO-03-06-39` | P1 | `1a` | Code-barres : Flag de Comptage / log transaction |
| `BO-03-06-42` | P1 | `1a` | Code-barres : appel Moteur de promotion |
| `BO-03-06-45` | P1 | `1a` | Contrôle Caisse Offline |
| `BO-03-06-46` | P1 | `1a` | Contrôle Caisse "Autre magasin" |
| `BO-03-06-48` | P1 | `1a` | Contrôle Caisse "code-barres non trouvé" |
| `BO-03-06-49` | P1 | `1a` | Contrôle Caisse "code-barres déjà utilisé" |
| `BO-03-06-51` | P1 | `1a` | Carte superviseur |
| `BO-03-06-54` | P1 | `1a` | Code-barres : client fidélité |
| `BO-03-06-59` | P1 | `1a` | Code-barres : Appel Fonction Caisse |
| `BO-03-06-62` | P1 | `1a` | Visualisation codes-barres imprimés en caisses |
| `BO-03-06-63` | P1 | `1a` | Visualisation codes-barres passés en caisses |
| `BO-03-07-01` | P1 | `1` | Activation Remise/Rabais |
| `BO-03-07-02` | P1 | `1a` | Création Remise Article |
| `BO-03-07-04` | P1 | `1a` | Seuil Remise / Rabais |
| `BO-03-07-05` | P1 | `1a` | Création Rabais Article |
| `BO-03-07-07` | P1 | `1a` | Création Rabais Total |
| `BO-03-07-08` | P1 | `1a` | Création Remise Total |
| `BO-03-07-09` | P1 | `1a` | Suppression |
| `BO-03-07-10` | P1 | `1a` | Mise en supervision |
| `BO-03-08-01` | P1 | `1a` | Message à l'ouverture caisse |
| `BO-03-08-02` | P1 | `1a` | Message à la fermeture caisse |
| `BO-03-08-03` | P1 | `1` | Message en début /fin de ticket |
| `BO-03-08-05` | P2 | `1` | Ticket de caisse |
| `BO-03-09-02` | P1 | `1a` | Configuration des caisses - entête/pied de page |
| `BO-03-09-03` | P1 | `1a` | Configuration des caisses - tableau TVA |
| `BO-03-09-06` | P1 | `1a` | Configuration du - Menu caisse |
| `BO-03-09-11` | P1 | `1a` | Configuration - Tiroir |
| `BO-03-09-14` | P1 | `1a` | Configuration - Balance - Activation |
| `BO-03-09-16` | P1 | `1a` | Configuration - Scanner - Activation |
| `BO-03-09-19` | P1 | `1a` | Configuration - monétique |
| `BO-03-09-21` | P1 | `1a` | Configuration - Activation imprimantes |
| `BO-03-09-23` | P1 | `1a` | Configuration - Flux Temps réel |
| `BO-03-09-28` | P1 | `1a` | Configuration du N° de terminal |
| `BO-03-10-09` | P1 | `1a` | Configuration îlots de caisse - Menu caisse |
| `BO-03-10-17` | P1 | `1a` | Menu caisse - configuration des écrans de caisse |
| `BO-03-10-18` | P1 | `1a` | Paramétrage des enseignes |
| `BO-03-10-19` | P1 | `1a` | Configuration des données Point De Vente |
| `BO-03-10-20` | P1 | `1a` | Données Point De Vente |
| `BO-03-11-04` | P1 | `1a` | Configuration des Fournisseurs de service |
| `BO-03-12-01` | P1 | `1a` | Synchronisation données Point De Vente - Caisses |
| `BO-03-12-03` | P1 | `1a` | Déploiement Partiel des données configurées - Pays - Enseigne - modèle - N° Point De Vente |
| `BO-03-12-04` | P1 | `1a` | Déploiement Complet des données configurées vers une liste de Point De Vente par : - Pays - Enseigne - modèle  |
| `BO-03-12-05` | P1 | `1` | Activation / désactivation manuel du mode dégradé monétique (France) |
| `BO-03-12-06` | P1 | `1a` | Configuration des données Point De Vente |
| `BO-03-12-07` | P1 | `1a` | Vue centrale des PDV ayant personnalisé un paramètre par pays/enseigne |
| `BO-03-13-04` | P1 | `1a` | Retour Type Ticket Retour |
| `BO-03-13-05` | P1 | `1a` | Retour Type Retour ticket Origine |
| `BO-03-13-07` | P1 | `1a` | Retour Type Retour Article |
| `BO-03-13-09` | P1 | `1a` | Contrôle Retour |
| `BO-03-13-11` | P1 | `1a` | Configuration Retour: déconsigne |
| `BO-03-13-12` | P1 | `1a` | Code-barres Type Retour article |

### 04 - Utilitaires — 25 en `1`, 33 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-04-01-01` | P1 | `1` | Consultation - Texte libre |
| `BO-04-01-02` | P1 | `1` | Consultation - N° caissière |
| `BO-04-01-03` | P1 | `1` | Consultation - N° TPV |
| `BO-04-01-04` | P1 | `1` | Consultation - N° transaction |
| `BO-04-01-05` | P1 | `1a` | Consultation - Montant de la transaction |
| `BO-04-01-06` | P1 | `1` | Consultation - Mode de règlement |
| `BO-04-01-07` | P1 | `1` | Consultation - Mode de règlement + plage de montants |
| `BO-04-01-08` | P1 | `1` | Consultation - N° d'autorisation |
| `BO-04-01-09` | P1 | `1a` | Consultation - Heure de la transaction |
| `BO-04-01-10` | P1 | `1a` | Consultation - Articles |
| `BO-04-01-11` | P1 | `1a` | Consultation - Plage de familles |
| `BO-04-01-12` | P1 | `1` | Consultation - Acompte |
| `BO-04-01-13` | P1 | `1` | Consultation - Choix multiple |
| `BO-04-01-14` | P1 | `1a` | Consultation - Evénement |
| `BO-04-01-16` | P1 | `1a` | Consultation- Annulation article |
| `BO-04-01-19` | P1 | `1a` | Consultation - Annulation de règlement |
| `BO-04-01-23` | P1 | `1a` | Consultation - Remboursement article |
| `BO-04-01-25` | P1 | `1` | Consultation - Article à prix zéro |
| `BO-04-01-26` | P1 | `1a` | Consultation - Réduction manuelle |
| `BO-04-01-27` | P1 | `1` | Consultation - Départ en pause |
| `BO-04-01-28` | P1 | `1` | Consultation - Retour de pause |
| `BO-04-01-29` | P1 | `1` | Consultation - Modification mot de passe |
| `BO-04-01-30` | P1 | `1` | Consultation - Mot de passe incorrect |
| `BO-04-01-31` | P1 | `1` | Consultation - Ticket avec retour |
| `BO-04-01-32` | P1 | `1` | Consultation - Ticket avec bons de réduction |
| `BO-04-01-33` | P1 | `1a` | Consultation - Seuil d'espèces dans le tiroir |
| `BO-04-01-34` | P1 | `1a` | Consultation- Présence d'articles inconnus |
| `BO-04-01-35` | P1 | `1` | Consultation - Ticket avec dépense |
| `BO-04-01-36` | P1 | `1` | Consultation - Ticket de prélèvement |
| `BO-04-01-37` | P1 | `1` | Consultation - Ticket d'apport |
| `BO-04-01-39` | P1 | `1a` | Consultation - Forçage superviseur |
| `BO-04-01-40` | P1 | `1` | Consultation - Prélèvement espèces |
| `BO-04-01-44` | P1 | `1` | Consultation - Déclaration caissière |
| `BO-04-01-46` | P1 | `1a` | Consultation - Total monétique |
| `BO-04-01-47` | P1 | `1` | Consultation - Transactions monétiques en mode dégradé |
| `BO-04-01-49` | P1 | `1` | Consultation - Transactions monétiques mode dégradé manuel |
| `BO-04-01-50` | P1 | `1a` | Consultation - Affichage de la liste des tickets |
| `BO-04-01-51` | P1 | `1` | Consultation - Possibilité de trier le résultat obtenu |
| `BO-04-01-52` | P1 | `1` | IHM séparées |
| `BO-04-01-53` | P2 | `1a` | Consultation - Factures/Bon de livraison imprimés en caisse |
| `BO-04-01-55` | P2 | `1a` | Consultation - ticket Vente taux de TVA |
| `BO-04-02-01` | P1 | `1a` | Configuration de la supervision sur toutes les fonctions de caisse |
| `BO-04-02-02` | P1 | `1a` | Gestion des demandes émises par les caisses : validation/acquittement Backoffice |
| `BO-04-02-06` | P1 | `1a` | Configuration des demandes caisse "Article Inconnu" |
| `BO-04-02-09` | P1 | `1a` | Création des codes superviseurs |
| `BO-04-02-11` | P1 | `1a` | Impression des codes superviseurs |
| `BO-04-02-12` | P1 | `1a` | Gestion des demandes émises par les caisses : affichage alerte BackOffice |
| `BO-04-03-01` | P1 | `1a` | IHM |
| `BO-04-03-02` | P1 | `1a` | IHM Responsive |
| `BO-04-03-03` | P1 | `1a` | Tableau de Bord |
| `BO-04-03-04` | P2 | `1a` | Visualisation des accès utilisateurs |
| `BO-04-03-07` | P1 | `1a` | Surveillance caissier |
| `BO-04-03-08` | P1 | `1a` | Gestion des alertes de caisse Périmêtre |
| `BO-04-03-09` | P1 | `1a` | Gestion des alertes en caisse Type |
| `BO-04-03-10` | P1 | `1a` | Gestion des justifications en caisse Fonctions |
| `BO-04-03-11` | P2 | `1a` | Gestion des justifications en caisse Justification |
| `BO-04-03-14` | P2 | `1a` | Traçabilité |
| `BO-04-03-15` | P2 | `1a` | Tableau de bord |

### 05 - Financier — 0 en `1`, 9 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-05-01-06` | P1 | `1a` | Export Transactions (Portugal) |
| `BO-05-02-01` | P1 | `1a` | Gestion des mouvements hors encaissement - les dépenses |
| `BO-05-02-02` | P1 | `1a` | Gestion des mouvements hors encaissement - les recettes - saisie manuelle |
| `BO-05-02-08` | P1 | `1a` | Gestion des mouvements de caisse - l'historique des journées |
| `BO-05-02-09` | P1 | `1a` | Gestion des mouvements de caisse - validation des mouvements de chaque caissière |
| `BO-05-02-10` | P1 | `1a` | Gestion des mouvements de caisse - la remise au coffre |
| `BO-05-02-13` | P1 | `1a` | Gestion des mouvements de caisse - reporting |
| `BO-05-02-14` | P1 | `1a` | Gestion des profils donnant accès au coffre |
| `BO-05-02-15` | P1 | `1a` | Gestion des salariés |

### 06 - Rapports — 0 en `1`, 13 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-06-01-02` | P1 | `1a` | Rapport csv |
| `BO-06-04-02` | P2 | `1a` | Etat Ventes par emplacement |
| `BO-06-04-03` | P2 | `1a` | Liste des Factures imprimées en caisse - Réimpression des Factures à partir du backoffice encaissement (avec a |
| `BO-06-04-05` | P1 | `1a` | Etat Ventes Magasin |
| `BO-06-04-06` | P2 | `1a` | Etat Productivité horaire |
| `BO-06-06-01` | P1 | `1a` | Etat des tickets en attente |
| `BO-06-06-03` | P1 | `1a` | Etat Caissière |
| `BO-06-06-04` | P1 | `1a` | Etat Annulation |
| `BO-06-06-05` | P1 | `1a` | Etat Remboursement |
| `BO-06-06-06` | P1 | `1a` | Etat Acomptes / Avoirs / Coupons / Bons |
| `BO-06-06-07` | P1 | `1a` | Etat des codes raison |
| `BO-06-06-09` | P1 | `1a` | Etat des règlements |
| `BO-06-06-10` | P1 | `1a` | Etat des forçages prix |

### 07 - e-Commerce — 0 en `1`, 3 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-07-01-01` | P1 | `1a` | Configuration de l'activité d'intégration des ventes issues d'un système tiers |
| `BO-07-01-02` | P1 | `1a` | Mise à jour du CA E-Commerce |
| `BO-07-01-04` | P1 | `1a` | Intégration des paiements E-Commerce |

### 08 - Supervision — 0 en `1`, 26 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-08-01-01` | P1 | `1a` | Référentiel PDV |
| `BO-08-01-02` | P1 | `1a` | Référentiel articles |
| `BO-08-01-03` | P1 | `1a` | Référentiel nomenclature |
| `BO-08-01-07` | P1 | `1a` | Référentiel articles PDV |
| `BO-08-01-08` | P1 | `1a` | Référentiel nomenclature PDV |
| `BO-08-01-10` | P3 | `1a` | Référentiel TVA PDV |
| `BO-08-01-12` | P1 | `1a` | Référentiel articles Caisse |
| `BO-08-01-13` | P1 | `1a` | Référentiel nomenclature Caisse |
| `BO-08-01-16` | P1 | `1a` | Référentiel Offres Commerciales Caisse |
| `BO-08-01-17` | P1 | `1a` | Intégration - Console |
| `BO-08-01-18` | P1 | `1a` | Intégration attributs Articles |
| `BO-08-02-01` | P1 | `1a` | Intégration attributs Articles |
| `BO-08-02-02` | P2 | `1a` | Contrôle des fonctions utilisées en caisse |
| `BO-08-03-08` | P1 | `1a` | Synchronisation données Point De Vente - Caisses |
| `BO-08-04-03` | P1 | `1a` | Relance automatique sur erreur Intégration Articles |
| `BO-08-04-04` | P1 | `1a` | Relance Automatique sur erreur Intégration Nomenclature |
| `BO-08-04-06` | P2 | `1a` | Relance Automatique sur erreur Intégration TVA |
| `BO-08-04-07` | P1 | `1a` | Relance Automatique sur erreur Intégration attributs Articles |
| `BO-08-04-08` | P1 | `1a` | Relance automatique sur erreur Intégration Offres Commerciales |
| `BO-08-04-09` | P1 | `1a` | Relance manuelle sur erreur Intégration Articles |
| `BO-08-04-10` | P1 | `1a` | Relance manuelle sur erreur Intégration Nomenclature |
| `BO-08-04-12` | P2 | `1a` | Relance manuelle sur erreur Intégration TVA |
| `BO-08-04-13` | P1 | `1a` | Relance manuelle sur erreur Intégration Offres Commerciales |
| `BO-08-04-15` | P1 | `1a` | Activité - Console |
| `BO-08-04-16` | P1 | `1a` | Supervision Caisse |
| `BO-08-04-18` | P1 | `1a` | Ticket |

### 09 - Fin de journée — 0 en `1`, 7 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-09-01-05` | P1 | `1a` | Fin de journée Automatique : Ticket en attente |
| `BO-09-01-06` | P2 | `1a` | Fin de journée Automatique - caisse ouverte |
| `BO-09-01-08` | P1 | `1a` | Contrôle Tickets en Attente et RAZ |
| `BO-09-01-09` | P1 | `1a` | Forçage fermeture |
| `BO-09-02-01` | P2 | `1a` | Fin de journée manuelle - Alerte ticket en attente |
| `BO-09-02-03` | P2 | `1a` | Fin de journée manuelle - Alerte caisse ouverte |
| `BO-09-03-05` | P1 | `1a` | Fin de période - Vérification caisse |

### 10 - Règles — 8 en `1`, 56 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-10-01-03` | P1 | `1a` | eCommerce - clôture panier |
| `BO-10-01-06` | P1 | `1a` | eCommerce - Article inconnu |
| `BO-10-01-13` | P1 | `1a` | eCommerce - définition du canal |
| `BO-10-01-21` | P1 | `1a` | Self scanning - Article inconnu |
| `BO-10-01-22` | P1 | `1a` | Self scanning - définition du canal |
| `BO-10-01-27` | P1 | `1a` | Gestion Commerciale - Gestion acompte |
| `BO-10-01-28` | P1 | `1a` | Gestion Commerciale - définition du canal |
| `BO-10-01-31` | P1 | `1a` | Qbusting - définition du canal |
| `BO-10-01-34` | P1 | `1a` | Mobilité - définition du canal |
| `BO-10-02-01` | P1 | `1a` | Points de contrôle - Vente annulation article |
| `BO-10-02-03` | P1 | `1a` | Points de contrôle - Retour |
| `BO-10-02-04` | P1 | `1a` | Points de contrôle - Vente tiroir |
| `BO-10-02-05` | P1 | `1a` | Points de contrôle - Vente Forçage Prix |
| `BO-10-02-06` | P1 | `1a` | Points de contrôle - Vente quantité |
| `BO-10-02-08` | P1 | `1a` | Points de contrôle - log off ticket en attente |
| `BO-10-02-12` | P1 | `1a` | Tiroir - règle d'ouverture tiroir |
| `BO-10-02-14` | P1 | `1a` | Scan - Article inconnu |
| `BO-10-02-18` | P1 | `1a` | Gestion règle d'arrondi |
| `BO-10-02-21` | P1 | `1` | CheckDigit Saisie Article |
| `BO-10-02-22` | P1 | `1a` | Connexion - Sécurité |
| `BO-10-02-23` | P1 | `1a` | Connexion - Sécurité |
| `BO-10-02-25` | P1 | `1` | Connexion - tiroir |
| `BO-10-02-26` | P1 | `1a` | Déconnexion - tiroir |
| `BO-10-02-27` | P1 | `1` | Verrouillage/fermeture - Sécurité 1/2 |
| `BO-10-02-29` | P1 | `1a` | Connexion - Sécurité |
| `BO-10-02-30` | P1 | `1a` | Déverrouillage - Sécurité |
| `BO-10-02-31` | P1 | `1a` | Superviseur - Sécurité |
| `BO-10-02-33` | P1 | `1a` | Fidélité - cagnottes (PT) |
| `BO-10-02-34` | P1 | `1a` | Code article Ticket |
| `BO-10-02-35` | P1 | `1a` | Code article écrans |
| `BO-10-03-01` | P1 | `1a` | Fidélité - Code enseigne |
| `BO-10-03-02` | P1 | `1` | Fidélité - scan multiple de carte de fidélité |
| `BO-10-03-04` | P1 | `1a` | Fidélité - affichage nom client FID sur le viseur hôte(sse) de caisse |
| `BO-10-03-06` | P1 | `1a` | Fidélité - Moyen de Paiement |
| `BO-10-03-07` | P1 | `1a` | Fidélité - Activation d'une Fidélité externe |
| `BO-10-03-08` | P1 | `1a` | Fidélité - configuration |
| `BO-10-03-10` | P1 | `1a` | Fidélité - délai de réponse |
| `BO-10-03-15` | P1 | `1a` | Fidélité - Avantage |
| `BO-10-03-16` | P1 | `1` | Fidélité - validation transaction |
| `BO-10-03-22` | P1 | `1a` | Fidélité - moteur de promotion Activation JV (activation de la promotion perso) |
| `BO-10-05-02` | P1 | `1a` | Ticket Balance - référence prix article |
| `BO-10-06-04` | P1 | `1a` | TVA |
| `BO-10-06-05` | P1 | `1a` | Eco-Taxe |
| `BO-10-07-01` | P1 | `1a` | Affichage Ecran clients 10'' |
| `BO-10-07-02` | P1 | `1` | Affichage QRCODE Ecran Client 10'' |
| `BO-10-07-07` | P1 | `1` | Ticket - mode Ecole |
| `BO-10-07-08` | P1 | `1a` | Ecran - mode Ecole |
| `BO-10-07-09` | P1 | `1a` | Ticket - impression ticket ouverture/fermeture |
| `BO-10-07-12` | P1 | `1a` | Ticket - impression prix d'origine forçage prix |
| `BO-10-07-18` | P1 | `1a` | Langue |
| `BO-10-07-19` | P1 | `1a` | Ecran CAISSE mode Déconnecté (offline) |
| `BO-10-07-20` | P1 | `1a` | Message Article vente interdit |
| `BO-10-07-21` | P1 | `1a` | Configuration - Impression Conditionnelle |
| `BO-10-07-22` | P2 | `1a` | Configuration - envoi de mail |
| `BO-10-07-23` | P2 | `1a` | Configuration - Modification de mail |
| `BO-10-08-01` | P1 | `1` | Alerte - Activation |
| `BO-10-08-02` | P2 | `1a` | Alerte - Activation |
| `BO-10-08-22` | P1 | `1a` | Article - sous famille |
| `BO-10-08-25` | P1 | `1a` | Référentiel - Mise à jour |
| `BO-10-08-28` | P1 | `1a` | Unité et mesure caisse - nombre de décimales |
| `BO-10-08-29` | P1 | `1a` | Unité et mesure caisse - Poids |
| `BO-10-08-30` | P2 | `1a` | Affichage ticket Annulation article / annulation Ligne |
| `BO-10-08-31` | P2 | `1a` | Optimisation des lignes article sur le ticket |
| `BO-10-08-32` | P3 | `1a` | Optimisation des lignes article écrans caisse |

### 11 - Flux Sortants — 0 en `1`, 14 en `1a`

| Code | Prio | État | Sous-fonctionnalité |
|---|---|---|---|
| `BO-11-01-01` | P1 | `1a` | Flux d'intégration - Émetteur |
| `BO-11-01-02` | P1 | `1a` | Flux d'exportation - Destinataire |
| `BO-11-01-14` | P1 | `1a` | Export quotidien des transactions |
| `BO-11-01-21` | P1 | `1a` | Export : rapport de productivité de la caisse. (France) |
| `BO-11-02-01` | P1 | `1a` | Transmission en temps réel des données de vente unitaire (DS ventes) |
| `BO-11-02-02` | P1 | `1a` | Mise à disposition des TICKETS |
| `BO-11-02-05` | P1 | `1a` | Tickets Healthcheck |
| `BO-11-02-06` | P1 | `1a` | Borne de prix |
| `BO-11-03-01` | P1 | `1a` | Exportation des données à la demande |
| `BO-11-03-04` | P1 | `1a` | Produits dématérialisés |
| `BO-11-04-04` | P1 | `1a` | Webservice Fidélité - AR |
| `BO-11-04-05` | P1 | `1a` | Webservice Fidélité - VT |
| `BO-11-04-06` | P1 | `1a` | Webservice Fidélité - GT |
| `BO-11-04-07` | P1 | `1a` | Webservice Fidélité - VT2 |
