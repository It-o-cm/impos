# Audit des partiels P1 du back-office contre le code

291 des 292 lignes cotées « 1a. Couvert partiellement » et P1 de l'onglet Back Office, confrontées au code du dépôt impos. Chaque verdict cite un `fichier:ligne` réellement lu ; le doute profite au statu quo.

| Verdict | Sens |
|---|---|
| **CABLABLE** | Le comportement existe et un point de configuration l'atteint en 2 ou 3 sites, sans changer de signature publique ni toucher au chemin fiscal. Inclut les lignes déjà entièrement administrées, qui ne coûtent aucune ligne de code. |
| **FIGE** | Le comportement existe mais n'est pas atteignable : littéral répété, objet d'état sans accès aux réglages, valeur figée dans une signature. Refactor. |
| **FISCAL** | Chemin fiscal, sécurité ou authentification. Non gatable sans instruction séparée. |
| **INEXISTANT** | Le comportement n'existe pas. Construction, pas câblage. |


## 03-06 Gestion des codes-barres (26)

Câblable 0 · Figé 10 · Fiscal 0 · Inexistant 16

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-03-06-01 | INEXISTANT | `-` | aucune notion de symbologie (code128/8/13/2D) ; ScanContext.code est une chaîne agnostique |
| BO-03-06-02 | FIGE | `ui/scanner/WeightedEanScanHandler.java:63` | format figé sur 13 chiffres, offsets article câblés en substring |
| BO-03-06-03 | FIGE | `ui/scanner/WeightedEanScanHandler.java:72-73,113` | offset/longueur/décimales du prix figés ; pas de format Franc |
| BO-03-06-04 | FIGE | `ui/scanner/WeightedEanScanHandler.java:72-73,125` | offset/longueur/échelle du poids figés dans le même parseur |
| BO-03-06-08 | FIGE | `domain/CouponType.java:18-19,46` | le rapprochement plage→moyen existe mais CouponType n'a aucun écran d'administration |
| BO-03-06-09 | FIGE | `domain/CouponType.java:46` | matchPattern est déjà une regex libre, mais sans écran BO |
| BO-03-06-10 | INEXISTANT | `-` | aucune longueur min/max générique ; chaque handler impose sa regex |
| BO-03-06-11 | FIGE | `domain/CouponType.java:56-61` | amountPattern existe, toujours sans écran |
| BO-03-06-12 | INEXISTANT | `-` | aucune détection du motif 9999 basculant en saisie manuelle |
| BO-03-06-13 | FIGE | `domain/CouponType.java:136-137` | nombre de décimales figé en dur |
| BO-03-06-33 | INEXISTANT | `-` | aucun N° de ticket encodé dans un code-barres |
| BO-03-06-34 | INEXISTANT | `-` | idem, pas de champ longueur |
| BO-03-06-36 | INEXISTANT | `-` | aucun N° de séquence ; seul scannedStickerCodes contrôle les doublons |
| BO-03-06-37 | INEXISTANT | `-` | aucun N° TPV encodé |
| BO-03-06-38 | INEXISTANT | `-` | idem, pas de longueur |
| BO-03-06-39 | FIGE | `ui/scanner/WeightedEanScanHandler.java:102-106 ; ui/payment/VoucherService.java:126-132` | doublon toujours bloquant, messages en dur, pas de flag de comptage |
| BO-03-06-42 | INEXISTANT | `-` | aucun moteur de promotion appelé depuis le scan |
| BO-03-06-45 | INEXISTANT | `-` | aucun contrôle offline au scan |
| BO-03-06-46 | INEXISTANT | `-` | aucun N° de PDV émetteur porté par les bons |
| BO-03-06-48 | FIGE | `ui/scanner/UnknownScanHandler.java:28 ; WeightedEanScanHandler.java:77 ; Gs1ScanHandler.java:281` | message dupliqué sur 3 handlers, toujours bloquant |
| BO-03-06-49 | FIGE | `ui/payment/VoucherService.java:120-132` | contrôle réel mais sévérité figée, pas de distinction local/central |
| BO-03-06-51 | INEXISTANT | `-` | aucune création de carte superviseur |
| BO-03-06-54 | INEXISTANT | `-` | aucun contrôle de clé Luhn ; la carte fidélité est une simple regex |
| BO-03-06-59 | INEXISTANT | `-` | aucun appel de fonction caisse déclenché par un code-barres |
| BO-03-06-62 | INEXISTANT | `-` | aucun registre des codes-barres imprimés |
| BO-03-06-63 | INEXISTANT | `-` | aucun registre des codes-barres scannés |

## 03-02 Gestion des modes de règlement (21)

Câblable 3 · Figé 11 · Fiscal 0 · Inexistant 7

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-03-02-01 | FIGE | `domain/ticket/TicketPayment.java:83-99 ; CashPayment.java:29-30` | un moyen = une classe JPA compilée ; en ajouter un exige du code |
| BO-03-02-02 | FIGE | `ui/journal/PaymentTypes.java:29-40` | BY_KEY codé en dur, aucune administration ni export de la liste |
| BO-03-02-03 | FIGE | `templates/pay.html:335-350` | boutons de règlement écrits en dur, aucun indicateur actif/inactif |
| BO-03-02-04 | INEXISTANT | `domain/ticket/TicketPayment.java:29` | les moyens sont identifiés par chaîne, pas d'ID numérique 2-3 digits |
| BO-03-02-10 | INEXISTANT | `domain/ticket/TicketRestoPayment.java:12-14 ; ui/payment/PaymentService.java:248` | aucun plafond par moyen ; seul plafond = reste dû |
| BO-03-02-15 | FIGE | `domain/ticket/Refund.java:53-62 ; ui/returnprocess/RefundResource.java:183-219` | enum fermé + une route par méthode |
| BO-03-02-16 | FIGE | `ui/payment/PaymentService.java:255-267,315,529,604,737` | rendu monnaie réservé aux 4 méthodes câblées en dur |
| BO-03-02-17 | FIGE | `ui/cash/CashSessionResource.java:179-186 ; service/CashSessionService.java:63-77` | seules les espèces sont comptées, les autres auto-déclarées par construction |
| BO-03-02-18 | INEXISTANT | `ui/cash/DrawerMethodService.java:91-99` | aucun prélèvement automatique, seul le geste caissier existe |
| BO-03-02-19 | FIGE | `ui/payment/PaymentService.java:319,403,609,744` | ouverture tiroir identique sur 4 sites ; ni par moyen, ni les 3 modes demandés |
| BO-03-02-20 | CABLABLE | `ui/cash/CashMovementResource.java:136 ; templates/cash-movement.html:60-66` | cash.movement-tenders — record() accepte deja paymentMethod, il suffit de le remonter du formulaire |
| BO-03-02-22 | FIGE | `service/CashSessionService.java:176-192` | openingFloat est un champ unique, implicitement especes |
| BO-03-02-23 | FIGE | `templates/pay.html:709-730` | pre-remplissage fige au nom du mode dans le JavaScript |
| BO-03-02-30 | FIGE | `ui/journal/PaymentTypes.java:30-40` | ensemble ferme de classes ; aucun indicateur cagnotte administrable |
| BO-03-02-37 | INEXISTANT | `ui/payment/VoucherBalanceResource.java:101-116` | aucune integration NEXO ni carte prepayee |
| BO-03-02-38 | CABLABLE | `service/PosSettingsService.java:282-285 ; ui/home/HomeService.java:130-147` | deja administre par tender.restricted |
| BO-03-02-41 | CABLABLE | `ui/cash/CashSessionResource.java:61-91` | cash.default-opening-float — herite automatiquement par echelon |
| BO-03-02-44 | FIGE | `ui/hardware/terminal/VerifoneTerminalClient.java:208-209 ; TerminalOutcome.java:25` | le tag D16/D46 est lu puis jete, CardPayment n'a pas de colonne pour le recevoir |
| BO-03-02-45 | INEXISTANT | `domain/CashMovement.java:56-58` | aucun catalogue de depenses, la depense est un type de mouvement |
| BO-03-02-46 | INEXISTANT | `domain/CashMovement.java:56-58` | sans catalogue, pas d'activation individuelle |
| BO-03-02-47 | INEXISTANT | `domain/CashMovement.java:56-58` | sans catalogue, pas de plafond par depense |

## 02 Referentiels — articles, nomenclature, clients, PDV (26)

Câblable 18 · Figé 2 · Fiscal 1 · Inexistant 5

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-02-01-01 | FIGE | `domain/ProductFamily.java:102-103` | nomenclature globale, aucune dimension PDV sur la relation |
| BO-02-03-03 | INEXISTANT | `domain/Product.java:53-58` | un seul ean et un seul plu ; pas de codes de vente multiples |
| BO-02-03-05 | INEXISTANT | `-` | le mode « appel prix » n'est represente nulle part |
| BO-02-03-06 | CABLABLE | `attribute/ProductAttributeCatalog.java:42-43 ; imports/ProductCsvResource.java:205-238` | exposer MEAL_VOUCHER_ELIGIBLE dans l'import CSV (3 sites) |
| BO-02-03-09 | CABLABLE | `attribute/ProductAttributeCatalog.java:30-31 ; imports/ProductCsvResource.java:205-238` | idem pour DISCOUNT_FORBIDDEN |
| BO-02-03-11 | CABLABLE | `attribute/ProductAttributeCatalog.java:36-37 ; imports/ProductCsvResource.java:205-238` | idem pour RECALL |
| BO-02-03-12 | INEXISTANT | `-` | le flag d'impression du code article sur le ticket n'existe pas |
| BO-02-03-14 | INEXISTANT | `domain/ProductFamily.java:36-48` | pas de structure typee Activite/Rayon/Famille/Sous-famille |
| BO-02-03-17 | CABLABLE | `domain/Product.java:162-163 ; templates/admin-article.html:65-68` | unitName est disabled dans la fiche : le rendre editable (2-3 sites) |
| BO-02-03-18 | CABLABLE | `domain/Product.java:221-226 ; imports/ProductCsvResource.java:205-238` | colonne generique ATTRIBUTES=code=valeur dans l'import |
| BO-02-03-21 | CABLABLE | `attribute/ProductAttributeCatalog.java:54-55` | exposer PRICE_TO_ENTER dans l'import CSV |
| BO-02-03-22 | CABLABLE | `attribute/ProductAttributeCatalog.java:57-58` | exposer QUANTITY_TO_ENTER dans l'import CSV |
| BO-02-03-23 | INEXISTANT | `attribute/ProductAttributeCatalog.java:57-58` | pas d'attribut « quantite decimale » distinct de la saisie entiere |
| BO-02-03-24 | CABLABLE | `domain/Product.java:68-69 ; templates/admin-article.html` | variableWeight alimente par CSV mais absent de la fiche (2 sites) |
| BO-02-03-25 | CABLABLE | `attribute/ProductAttributeCatalog.java:51-52` | exposer BULKY dans l'import CSV |
| BO-02-03-28 | CABLABLE | `templates/admin-article.html:24-228` | ajouter l'affichage de variableWeight |
| BO-02-03-30 | CABLABLE | `ui/admin/ArticleSearchCriteria.java:46-66` | etendre la recherche : plage EAN, famille, plage de prix, taux TVA |
| BO-02-03-31 | CABLABLE | `domain/ArticleSelection.java:29-46` | ajouter pdvCode + adapter l'unicite (2-3 sites) |
| BO-02-03-33 | CABLABLE | `ui/admin/AdminArticleResource.java:204-232 ; templates/admin-article.html:69-80` | giftCardAmount, referenceWeight, referenceVolume sont en lecture seule |
| BO-02-03-45 | CABLABLE | `ui/admin/AdminArticleResource.java:229-230,243-260` | deja entierement cable, fiche et bulk |
| BO-02-04-19 | FISCAL | `domain/ticket/DocumentType.java:20-31 ; DocumentCounter.java:11-35` | FS/FT/NC/RC toucheraient le moteur de numerotation fiscale |
| BO-02-05-01 | CABLABLE | `domain/Pdv.java:44-96 ; ui/admin/AdminEchelonResource.java:161-190` | le renommage ne migre pas les EchelonSetting rattaches (2-3 sites) |
| BO-02-05-02 | FIGE | `service/EchelonSettingService.java:62-87 ; domain/Profile.java` | profils et modeles de documents n'ont aucune dimension echelon |
| BO-02-05-03 | CABLABLE | `ui/admin/AdminEchelonResource.java:98-190` | ajouter un importeur CSV/API pour Country/Enseigne/Pdv |
| BO-02-05-04 | CABLABLE | `service/EchelonSettingService.java:47-87` | ajouter au CATALOG les cles absentes : elles heritent automatiquement |
| BO-02-05-05 | CABLABLE | `domain/Pdv.java:66-72,118-127` | adherentCode et listByAdherent existent mais ne sont appeles nulle part |

## 10-02 / 10-05 / 10-06 / 10-08 Regles caisse (25)

Câblable 7 · Figé 2 · Fiscal 3 · Inexistant 13

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-10-02-01 | INEXISTANT | `ui/ticket/TicketService.java:750-763` | aucun systeme generique de point de controle |
| BO-10-02-03 | INEXISTANT | `ui/returnprocess/RefundService.java:265-407` | idem sur le retour |
| BO-10-02-04 | INEXISTANT | `ui/cash/CashCountResource.java:44-49` | ouverture tiroir inconditionnelle, aucune alerte hors vente |
| BO-10-02-05 | CABLABLE | `ui/home/HomeService.java:475-493 ; service/PosSettingsService.java:82-85` | deja administre par gesture.endorsement-required |
| BO-10-02-06 | INEXISTANT | `ui/ticket/TicketService.java:202-264` | la quantite saisie n'est jamais bornee |
| BO-10-02-08 | INEXISTANT | `ui/auth/AuthService.java:161-174` | la deconnexion ne verifie aucun ticket en attente |
| BO-10-02-12 | CABLABLE | `service/PosSettingsService.java:174-177 ; ui/cash/CashSessionResource.java:151-162` | paiement deja couvert ; ajouter drawer.open-on-session-close |
| BO-10-02-14 | INEXISTANT | `ui/scanner/UnknownScanHandler.java:16-30` | aucune proposition de saisie famille au scan inconnu |
| BO-10-02-18 | FISCAL | `ui/ticket/TicketState.java:253,291,433,697 ; ui/payment/CashRounding.java:37-79` | regle d'arrondi dupliquee dans toutes les valorisations |
| BO-10-02-22 | FISCAL | `ui/LockCheckFilter.java:96-127` | desactiver la demande de mot de passe = desactiver l'authentification |
| BO-10-02-23 | FISCAL | `ui/auth/AuthService.java:201-202` | longueur de PIN = parametre de securite |
| BO-10-02-26 | CABLABLE | `ui/cash/CashSessionResource.java:151-162` | drawer.open-on-session-close |
| BO-10-02-29 | CABLABLE | `ui/scanner/AuthScanHandler.java:44-50` | auth.badge-scan-enabled |
| BO-10-02-30 | CABLABLE | `ui/scanner/AuthScanHandler.java:44-50` | meme site et meme cle que 02-29 |
| BO-10-02-31 | INEXISTANT | `ui/endorsement/EndorsementService.java:47-70` | un seul schema login+PIN, aucune cle Dallas |
| BO-10-02-33 | INEXISTANT | `ui/fidelity/FidelityState.java:9-21` | un seul solde cagnotte, pas de cagnottes multiples |
| BO-10-02-34 | INEXISTANT | `domain/Product.java:95` | internalCode n'est jamais imprime sur le ticket |
| BO-10-02-35 | INEXISTANT | `domain/Product.java:95` | internalCode n'est affiche sur aucun ecran caisse |
| BO-10-05-02 | CABLABLE | `service/PosSettingsService.java:190-193` | deja administre par balance.counter-price |
| BO-10-06-04 | CABLABLE | `ui/hardware/TicketPrinterService.java:257-260,460-462` | ticket.vat-breakdown-enabled (2 sites) |
| BO-10-06-05 | INEXISTANT | `-` | aucun modele d'eco-taxe |
| BO-10-08-22 | INEXISTANT | `domain/ProductFamily.java` | aucune notion de sous-famille |
| BO-10-08-25 | INEXISTANT | `service/sync/RefApplyService.java:123-164` | aucun moteur de promotion a proteger |
| BO-10-08-28 | FIGE | `9 sites dont ui/ticket/TicketState.java:668, TicketLine.java:277, Gs1ScanHandler.java:428` | 3 decimales cablees en dur sur 9 sites, affichage ET decodage |
| BO-10-08-29 | FIGE | `5 sites dont ui/customer/CustomerDisplayResource.java:152` | symbole kg repete sur au moins 5 sites |

## 04 Utilitaires — journal, supervision caisse, controle (27)

Câblable 14 · Figé 6 · Fiscal 3 · Inexistant 4

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-04-01-05 | CABLABLE | `ui/journal/JournalService.java:106-113 ; domain/ticket/Ticket.java:208` | second couple de bornes hors remise sur totalIncludingTax + globalDiscountApplied |
| BO-04-01-09 | CABLABLE | `ui/journal/JournalService.java:127-134` | ajouter hourFrom/hourTo (extract hour) pour une plage horaire repetee |
| BO-04-01-10 | CABLABLE | `ui/journal/JournalService.java:156-174` | OR-er l.product.plu : les articles au PLU catalogue vendus hors pesee |
| BO-04-01-11 | CABLABLE | `ui/journal/JournalService.java:187-204 ; templates/journal.html:81-87` | multi-select familles + critere IN |
| BO-04-01-14 | FIGE | `domain/ticket/TechnicalEvent.java:35-108 ; ui/journal/JournalCriteria.java:38-73` | 5 valeurs sur 15 ; deduction, forcage, article emporte n'ont aucun porteur |
| BO-04-01-16 | CABLABLE | `ui/journal/JournalService.java:292-317` | meme correctif que 04-01-10 |
| BO-04-01-19 | FIGE | `service/TicketPersistenceService.java:349-358` | PAYMENTS_CLEARED ne porte ni montant ni mode |
| BO-04-01-23 | CABLABLE | `ui/journal/JournalService.java:221-248` | meme correctif que 04-01-10 |
| BO-04-01-26 | CABLABLE | `ui/journal/JournalService.java:257-275` | restreindre a REMISE/DISCOUNT pour exclure le forcage prix |
| BO-04-01-33 | CABLABLE | `ui/journal/JournalService.java:666-697` | ajouter movementAmountMin/Max sur m.amount |
| BO-04-01-34 | CABLABLE | `ui/journal/JournalCriteria.java:49-50 ; JournalService.java:372-375` | plage de PLU jumelee au flag UNKNOWN_ITEM |
| BO-04-01-39 | CABLABLE | `ui/endorsement/EndorsementService.java:63-67 ; service/TechnicalEventService.java:52-61` | appeler log() a 3 arguments : le filtre par N° caissiere fonctionnera |
| BO-04-01-46 | CABLABLE | `ui/journal/JournalService.java:383-386 ; domain/ticket/BackupPayment.java:28-30` | OR-er BackupPayment dans le total monetique |
| BO-04-01-50 | FIGE | `ui/journal/JournalRow.java:10-14 ; JournalService.java:446-448` | mode ecole ne cree aucun Ticket, canal autonome non modelise |
| BO-04-02-01 | FIGE | `service/PosSettingsService.java:82-84` | un seul booleen global ; pas de configuration par fonction ni par profil |
| BO-04-02-02 | FISCAL | `ui/dashboard/DashboardResource.java:118-130` | il manque un canal d'autorisation temps reel du BO vers la caisse |
| BO-04-02-06 | INEXISTANT | `-` | aucune remontee temps reel des scans inconnus vers le BO |
| BO-04-02-09 | FISCAL | `domain/Employee.java:59-75 ; security/EmployeeBootstrap.java` | creation d'employe uniquement par import ; chemin d'authentification |
| BO-04-02-11 | FISCAL | `ui/hardware/TicketPrinterService.java` | la carte superviseur n'est pas un objet du domaine |
| BO-04-02-12 | CABLABLE | `templates/dashboard.html:3,134 ; templates/admin-layout.html` | deplacer le bandeau d'alerte et son poll dans le gabarit partage |
| BO-04-03-01 | FIGE | `ui/dashboard/DashboardService.java:47-137` | comparatif N-1 et CA par famille sont des dimensions inexistantes |
| BO-04-03-02 | FIGE | `templates/admin-layout.html:26` | aucun breakpoint nulle part ; refonte CSS transverse |
| BO-04-03-03 | INEXISTANT | `ui/dashboard/DashboardService.java:47-137` | aucun moteur de parametrage de tableau de bord |
| BO-04-03-07 | CABLABLE | `templates/dashboard.html:3,134 ; ui/dashboard/DashboardService.java:47-137` | ajouter une requete activite recente au poll existant |
| BO-04-03-08 | INEXISTANT | `-` | aucun modele generique de regle d'alerte |
| BO-04-03-09 | INEXISTANT | `-` | aucun type de controle configurable ni compteur par transaction |
| BO-04-03-10 | CABLABLE | `service/PosSettingsService.java:226-229 ; ui/cash/CashMovementResource.java:88-90` | decliner cash.movement-reasons par MovementType |

## 03-03 / 03-04 / 03-05 / 03-08 Documents imprimes (22)

Câblable 3 · Figé 6 · Fiscal 6 · Inexistant 7

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-03-03-02 | FIGE | `ui/invoice/InvoiceRenderer.java:45-69 ; ui/hardware/TicketPrinterService.java:138-163` | sequence figee de out.add, pas de moteur de template |
| BO-03-03-03 | FIGE | `ui/hardware/TicketPrinterService.java:188-217` | une seule String.format, zones non reordonnables |
| BO-03-03-04 | FISCAL | `ui/hardware/TicketPrinterService.java:248-260` | ventilation TVA = mention fiscale |
| BO-03-03-05 | FISCAL | `ui/hardware/TicketPrinterService.java:221-225` | totaux, congruence exigee avec le total TTC |
| BO-03-03-06 | FIGE | `ui/hardware/TicketPrinterService.java:650-667` | zones code-barres et date absentes du ticket en attente |
| BO-03-03-08 | FIGE | `ui/hardware/TicketPrinterService.java:513-526` | composition figee du bon de remboursement |
| BO-03-03-19 | CABLABLE | `ui/hardware/TicketPrinterService.java:202-205` | deja cable — display.show-ean |
| BO-03-03-20 | FISCAL | `ui/hardware/TicketPrinterService.java:28-31,103-117,154-157` | marquage duplicata = integrite anti-fraude |
| BO-03-03-22 | FIGE | `ui/hardware/TicketPrinterService.java:209-217,221-225` | pas de zone dediee remise, position et libelle figes |
| BO-03-03-23 | FIGE | `ui/hardware/TicketPrinterService.java:209-217` | promotion non distinguee d'un avantage |
| BO-03-03-24 | INEXISTANT | `domain/ticket/Ticket.java:200-208` | pas de champ « remise immediate » distinct |
| BO-03-03-25 | INEXISTANT | `ui/fidelity/FidelityState.java:47-68` | EarnLine n'a pas de champ type, aucun regroupement |
| BO-03-04-08 | INEXISTANT | `service/PosSettingsService.java:166-173` | une seule chaine d'entete et de pied, pas de modeles multiples |
| BO-03-04-13 | FISCAL | `ui/hardware/TicketPrinterService.java:154-157 ; ui/invoice/NetworkDocumentPrinter.java:137-138` | le nom du document en reimpression fait partie du marquage |
| BO-03-04-19 | FISCAL | `service/TicketNumberService.java:12-33` | sequence sans rupture = exigence fiscale |
| BO-03-04-25 | INEXISTANT | `domain/ticket/DocumentType.java:24-31` | pas de types FS/FT/NC/RC |
| BO-03-04-28 | FISCAL | `service/TicketNumberService.java:12-33` | unicite par annee non implementee, chainage fiscal |
| BO-03-05-01 | INEXISTANT | `service/PosSettingsService.java:166-169 ; ui/hardware/TicketPrinterService.java:150-153` | pas de multi-lignes, pas de centrage/justification/gras |
| BO-03-05-03 | INEXISTANT | `service/PosSettingsService.java:170-173` | meme constat pour le pied |
| BO-03-05-05 | INEXISTANT | `ui/hardware/TicketPrinterService.java:150-153,311-316` | aucun modele de ligne |
| BO-03-08-01 | CABLABLE | `service/PosSettingsService.java:102-105` | deja cable — customer.message-open |
| BO-03-08-02 | CABLABLE | `service/PosSettingsService.java:106-109` | deja cable — customer.message-closed |

## 03-09 / 03-10 / 03-11 / 03-12 Points de contact (21)

Câblable 3 · Figé 4 · Fiscal 1 · Inexistant 13

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-03-09-02 | INEXISTANT | `service/PosSettingsService.java:166-169` | reglages portes par magasin, pas par caisse |
| BO-03-09-03 | INEXISTANT | `ui/hardware/TicketPrinterService.java:248` | ligne Dont TVA unique, aucun choix |
| BO-03-09-06 | INEXISTANT | `domain/Menu.java:14-23` | pas de concept de menu caisse selectionnable |
| BO-03-09-11 | INEXISTANT | `ui/hardware/HardwareClient.java:47-58` | aucun toggle d'activation du tiroir |
| BO-03-09-14 | INEXISTANT | `ui/hardware/HardwareClient.java:29-31` | aucun toggle d'activation de la balance |
| BO-03-09-16 | INEXISTANT | `ui/hardware/scanner/ScannerReaderService.java:42-43,91-92` | pos.scanner.enabled est une propriete de deploiement, non administrable |
| BO-03-09-19 | INEXISTANT | `ui/hardware/terminal/TerminalClientProducer.java:42,104-119` | pos.tpe.mode resolu au boot ; seul Verifone implemente |
| BO-03-09-21 | INEXISTANT | `ui/hardware/TicketPrinterService.java:86-87` | WIDTH=42 code en dur, aucun parametre d'imprimante |
| BO-03-09-23 | INEXISTANT | `service/sync/EngineFeedDeliveryService.java:47-56` | URL cible unique et fixe |
| BO-03-09-28 | FISCAL | `service/TicketNumberService.java:21-32,38,50-55` | pos.terminal.id porte le chainage fiscal |
| BO-03-10-09 | INEXISTANT | `domain/EchelonLevel.java:4-8` | l'ilot est explicitement hors des echelons porteurs de reglages |
| BO-03-10-17 | INEXISTANT | `domain/EchelonLevel.java:4-8` | pas de modele d'ecran par ilot |
| BO-03-10-18 | INEXISTANT | `domain/Country.java:44-49 ; domain/Enseigne.java:60-64` | une seule langue par defaut, tres en-deca de l'exigence |
| BO-03-10-19 | CABLABLE | `ui/admin/AdminStoreResource.java:83-101 ; domain/Store.java:56,71,85-107` | deja cable — tous les champs sont editables |
| BO-03-10-20 | INEXISTANT | `ui/hardware/TicketPrinterService.java:146,150-152` | aucun moteur de substitution %Nom Magasin% |
| BO-03-11-04 | FIGE | `service/sync/EngineFeedDeliveryService.java:47-56 ; RefPullService.java:66-70` | IP/URL/identifiants en ConfigProperty figees au deploiement |
| BO-03-12-01 | FIGE | `service/sync/RefPullService.java:58,120` | pull periodique de 300s, ni immediat ni administrable |
| BO-03-12-03 | FIGE | `ui/admin/AdminEchelonResource.java:192-224 ; domain/EchelonLevel.java:16-26` | programmation limitee au CATALOG, pas d'axe modele |
| BO-03-12-04 | FIGE | `ui/admin/AdminEchelonResource.java:147-190` | meme limite, pas de diffusion vers une liste de PDV |
| BO-03-12-06 | CABLABLE | `ui/admin/AdminStoreResource.java:29-36,96` | deja cable |
| BO-03-12-07 | CABLABLE | `ui/admin/AdminEchelonResource.java:69-90 ; service/EchelonSettingService.java:178-203` | deja cable — vue des PDV personnalises |

## 08 / 09 / 11 Supervision, fin de journee, flux sortants (40)

Câblable 20 · Figé 5 · Fiscal 0 · Inexistant 15

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-08-01-01 | INEXISTANT | `domain/EngineFeed.java:26-67` | aucune brique centrale multi-PDV |
| BO-08-01-02 | INEXISTANT | `service/sync/EngineFeedService.java:47-54` | idem, integration articles |
| BO-08-01-03 | INEXISTANT | `service/sync/EngineFeedService.java:47-54` | idem, nomenclature |
| BO-08-01-07 | CABLABLE | `imports/EngineFeedRelayResource.java:144-168` | ecran admin sur l'endpoint de statut existant, code PRODUCTS |
| BO-08-01-08 | CABLABLE | `imports/EngineFeedRelayResource.java:144-168` | meme ecran, code FAMILIES |
| BO-08-01-12 | CABLABLE | `imports/EngineFeedRelayResource.java:144-168` | meme ecran consulte depuis une caisse |
| BO-08-01-13 | CABLABLE | `imports/EngineFeedRelayResource.java:144-168` | meme ecran, FAMILIES sur caisse |
| BO-08-01-16 | CABLABLE | `service/sync/EngineFeedService.java:47-54` | meme ecran, code OFFERS |
| BO-08-01-17 | INEXISTANT | `ui/dashboard/DashboardService.java:47-137` | console centrale multi-PDV avec graphiques et export : absente |
| BO-08-01-18 | INEXISTANT | `service/sync/EngineFeedService.java:47-54` | aucun flux d'attributs dans le catalogue |
| BO-08-02-01 | INEXISTANT | `service/sync/EngineFeedService.java:47-54` | la donnee d'integration d'attributs n'existe pas |
| BO-08-03-08 | CABLABLE | `service/sync/SyncPushService.java:47,81 ; RefPullService.java:58,120,200` | ajouter les cadences au CATALOG et afficher le dernier sync |
| BO-08-04-03 | CABLABLE | `service/sync/SyncPushService.java:134-141 ; domain/SyncOutbox.java:70-76` | ecran exposant attempts et lastError par code |
| BO-08-04-04 | CABLABLE | `service/sync/RefPullService.java:184-191` | meme ecran, code FAMILIES |
| BO-08-04-07 | INEXISTANT | `service/sync/EngineFeedService.java:47-54` | rien a relancer, le referentiel d'attributs n'existe pas |
| BO-08-04-08 | CABLABLE | `service/sync/EngineFeedDeliveryService.java:137-141` | exposer EngineFeed.lastError du code OFFERS |
| BO-08-04-09 | CABLABLE | `service/sync/RefPullService.java:179` | endpoint admin appelant pullOnce, domaine PRODUCTS |
| BO-08-04-10 | CABLABLE | `service/sync/RefPullService.java:179` | meme endpoint, FAMILIES |
| BO-08-04-13 | CABLABLE | `service/sync/EngineFeedDeliveryService.java:137-141` | endpoint admin appelant deliverPending |
| BO-08-04-15 | INEXISTANT | `ui/dashboard/DashboardService.java:47-137` | console centrale avec vision peripherique : absente |
| BO-08-04-16 | CABLABLE | `ui/dashboard/DashboardService.java:66-82,124-134` | colonne anomalie basee sur le backlog outbox par terminal |
| BO-08-04-18 | INEXISTANT | `-` | aucun heartbeat de supervision |
| BO-09-01-05 | FIGE | `service/CashSessionService.java:361-372` | declenchement manuel seul, aucun ordonnanceur dans le code |
| BO-09-01-08 | FIGE | `service/CashSessionService.java:361-372` | idem : alerte, liste et compte a rebours absents |
| BO-09-01-09 | CABLABLE | `service/CashSessionService.java:349-388` | bouton forcage fermeture appelant closeSession(null, ...) |
| BO-09-03-05 | INEXISTANT | `service/PosSettingsService.java:74-313` | aucun controle d'ecart caisse vs serveur |
| BO-11-01-01 | FIGE | `service/sync/RefPullService.java:212-239` | articles/nomenclature/client connectes ; TVA et attributs exigent un domaine complet |
| BO-11-01-02 | FIGE | `service/CashSessionService.java:100-106 ; ui/journal/JournalResource.java:143-153` | aucun connecteur d'export planifie |
| BO-11-01-14 | FIGE | `ui/journal/JournalResource.java:143-153` | aucun declenchement quotidien, aucun @Scheduled dans le code |
| BO-11-01-21 | INEXISTANT | `-` | aucun KPI de productivite caissiere |
| BO-11-02-01 | INEXISTANT | `service/sync/SyncPayloads.java:19-352` | aucune integration temps reel vers une gestion commerciale externe |
| BO-11-02-02 | INEXISTANT | `domain/SyncOutbox.java:33-56` | la synchro ne sert que le noeud magasin interne |
| BO-11-02-05 | CABLABLE | `domain/SyncOutbox.java:70-76 ; service/sync/SyncPushService.java:99-144` | ecran healthcheck sync sur le tableau de bord |
| BO-11-02-06 | INEXISTANT | `-` | aucun protocole d'exposition de prix pour une borne |
| BO-11-03-01 | CABLABLE | `ui/journal/JournalResource.java:143-153` | reutiliser l'export CSV a la demande |
| BO-11-03-04 | INEXISTANT | `-` | aucun protocole NEXO Retailer |
| BO-11-04-04 | CABLABLE | `ui/fidelity/ImfidClient.java:39-48,115-134` | fidelity.url/user/password au CATALOG |
| BO-11-04-05 | CABLABLE | `ui/fidelity/FidelityService.java:254-310` | meme cle de configuration |
| BO-11-04-06 | CABLABLE | `service/sync/FidEventOutboxService.java:129-161` | meme cle de configuration |
| BO-11-04-07 | CABLABLE | `ui/fidelity/FidelityService.java:373-391` | meme cle de configuration |

## 03-01 / 03-07 / 03-13 Groupes, remises, retours (22)

Câblable 14 · Figé 4 · Fiscal 1 · Inexistant 3

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-03-01-01 | CABLABLE | `ui/admin/AdminProductFamilyResource.java:116-218` | deja couvert integralement, CRUD complet |
| BO-03-01-04 | FIGE | `ui/ticket/ManualService.java:133-166` | le groupe NIVEAU-0 n'est jamais eclate en touches directes |
| BO-03-01-05 | FIGE | `ui/admin/AdminProductFamilyResource.java:250-264` | meme lacune de rendu |
| BO-03-01-08 | CABLABLE | `ui/admin/AdminTouchResource.java:117-139` | deja couvert — tailles SMALL/NORMAL/LARGE |
| BO-03-01-11 | CABLABLE | `ui/ticket/ManualService.java:369-380` | deja administre par touch.display-order |
| BO-03-01-15 | CABLABLE | `ui/admin/AdminTouchResource.java:177-235` | deja couvert — image par article |
| BO-03-01-16 | FIGE | `templates/admin-article-groups.html:61-62` | import fichier non branche sur l'ecran groupes |
| BO-03-01-17 | CABLABLE | `ui/admin/AdminProductFamilyResource.java:301-316` | deja couvert — bascule actif/inactif |
| BO-03-01-24 | CABLABLE | `ui/admin/ImageResizeService.java:31,57-83` | deja couvert ; taille max figee, cle optionnelle touch.image-max-dimension |
| BO-03-07-02 | CABLABLE | `ui/ticket/TicketService.java:502-519` | deja administre par discount.enabled et discount.line-max-percent |
| BO-03-07-04 | CABLABLE | `ui/ticket/TicketService.java:475-494,132-148` | ajouter discount.line-max-amount et discount.global-max-amount |
| BO-03-07-05 | CABLABLE | `ui/ticket/TicketService.java:475-494` | deja administre par discount.enabled |
| BO-03-07-07 | CABLABLE | `ui/ticket/TicketService.java:132-148` | deja administre par discount.enabled |
| BO-03-07-08 | CABLABLE | `ui/ticket/TicketService.java:132-148` | deja administre par discount.enabled et global-max-percent |
| BO-03-07-09 | INEXISTANT | `ui/ticket/TicketService.java:475-546` | aucune fonction ne retire une remise en conservant la ligne |
| BO-03-07-10 | CABLABLE | `ui/home/HomeService.java:479-493` | deja administre par gesture.endorsement-required |
| BO-03-13-04 | INEXISTANT | `ui/returnprocess/RefundService.java:92-114` | aucun scan de code-barres ticket, aucun type de retour configurable |
| BO-03-13-05 | CABLABLE | `ui/returnprocess/RefundService.java:169-232` | deja couvert par la saisie du numero |
| BO-03-13-07 | CABLABLE | `ui/returnprocess/RefundService.java:204-232` | deja couvert integralement |
| BO-03-13-09 | FISCAL | `ui/returnprocess/RefundService.java:265-281` | controle superviseur systematique sur une transaction financiere |
| BO-03-13-11 | FIGE | `domain/CouponType.java:18-19,69-79` | le mecanisme est honore mais CouponType n'a aucun ecran |
| BO-03-13-12 | INEXISTANT | `ui/scanner/DepositVoucherScanHandler.java:36-62` | aucun handler ne decode un code-barres retour article |

## 07 / 10-01 / 10-03 / 10-07 Omnicanalite, fidelite, presentation (27)

Câblable 9 · Figé 3 · Fiscal 1 · Inexistant 14

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-07-01-01 | INEXISTANT | `imports/` | aucun module d'integration de ventes tierces |
| BO-07-01-02 | INEXISTANT | `ui/valuation/ValuationPayloads.java:56` | deliveryMode=IN_STORE fige, aucune transaction e-commerce |
| BO-07-01-04 | INEXISTANT | `service/CashMovementService.java` | aucun connecteur e-commerce vers le livre de caisse |
| BO-10-01-03 | INEXISTANT | `imports/` | aucun retour vers un systeme e-commerce |
| BO-10-01-06 | INEXISTANT | `ui/ticket/TicketService.java:707-720` | l'EAN generique sert la saisie manuelle, pas une commande externe |
| BO-10-01-21 | INEXISTANT | `ui/journal/JournalRow.java:12-13` | le self-scanning n'est pas un canal modelise |
| BO-10-01-22 | INEXISTANT | `ui/valuation/ValuationPayloads.java:47-58` | aucun canal transmis au moteur de promotion |
| BO-10-01-27 | INEXISTANT | `domain/CashMovement.java:59-60` | l'acompte client est un mouvement sans lien avec une commande |
| BO-10-01-28 | INEXISTANT | `ui/valuation/ValuationPayloads.java:47-58` | aucune notion de gestion commerciale |
| BO-10-01-31 | INEXISTANT | `ui/valuation/ValuationPayloads.java:47-58` | aucune notion de Qbusting |
| BO-10-01-34 | INEXISTANT | `ui/valuation/ValuationPayloads.java:47-58` | aucune notion de mobilite |
| BO-10-03-01 | CABLABLE | `ui/admin/AdminEchelonResource.java:135-143` | deja administre par Enseigne.code, non encore transmis a imfid |
| BO-10-03-04 | CABLABLE | `templates/main.html:163 ; ui/home/HomeResource.java:115-117` | fidelity.show-holder-name (2-3 sites) |
| BO-10-03-06 | FIGE | `domain/ticket/FidelityPayment.java:20 ; ui/payment/PaymentService.java:772` | FIDELITY est un discriminant JPA fige |
| BO-10-03-07 | CABLABLE | `ui/fidelity/ImfidClient.java:64-66` | fidelity.external-enabled dans isConfigured (2 sites) |
| BO-10-03-08 | FISCAL | `ui/fidelity/ImfidClient.java:39-48` | URL et identifiants Basic-Auth du compte machine |
| BO-10-03-10 | FIGE | `ui/fidelity/ImfidClient.java:52,90,121,147,189,240,274,289,311,391` | delais figes sur une dizaine de sites |
| BO-10-03-15 | CABLABLE | `service/PosSettingsService.java:214-217` | deja administre par fidelity.advantages-enabled |
| BO-10-03-22 | CABLABLE | `ui/valuation/ValuationService.java:358` | fidelity.jv-promotion-enabled (2 sites) |
| BO-10-07-01 | CABLABLE | `service/PosSettingsService.java:102-109` | deja administre pour ouverte/fermee ; l'etat veille s'ajoute sur le meme schema |
| BO-10-07-08 | CABLABLE | `ui/ThemeService.java:52-64` | point de resolution unique : ajouter un theme ecole (2-3 sites) |
| BO-10-07-09 | INEXISTANT | `ui/cash/CashSessionResource.java:85-100` | aucune impression d'ouverture de session |
| BO-10-07-12 | CABLABLE | `service/PosSettingsService.java:98-101` | deja administre par price.show-original-on-force |
| BO-10-07-18 | INEXISTANT | `domain/Country.java:43-49` | aucun mecanisme d'internationalisation, francais code en dur |
| BO-10-07-19 | INEXISTANT | `templates/theme.html:76-92` | aucune banniere de mode deconnecte |
| BO-10-07-20 | FIGE | `5 sites dont ui/scanner/EanScanHandler.java:65` | message vente interdite en dur sur 5 sites, pas de message par article |
| BO-10-07-21 | CABLABLE | `service/PosSettingsService.java:130-141` | deja administre par print.conditional-enabled et print.forced-documents |

## 01 / 05 / 06 Connexion, financier, rapports (34)

Câblable 6 · Figé 15 · Fiscal 3 · Inexistant 10

| Code | Verdict | Preuve | Clé proposée ou raison |
|---|---|---|---|
| BO-01-01-01 | FISCAL | `security/EmployeeIdentityProvider.java:42-47` | le code documente lui-meme le manque ; chemin securite |
| BO-01-01-02 | FIGE | `ui/admin/AdminUserProfileResource.java:50-193` | aucun ecran CRUD employe, aucun modele de hierarchie |
| BO-01-01-03 | FIGE | `domain/EmployeeProfile.java:19-30,50-55` | portee limitee a GLOBAL ou un seul PDV |
| BO-01-01-04 | INEXISTANT | `templates/admin-login.html:10` | aucune infrastructure i18n |
| BO-01-01-06 | FIGE | `domain/Employee.java:85-101` | ni telephone ni date de debut ; creation par CSV seulement |
| BO-01-02-03 | FIGE | `imports/EmployeeCsvResource.java:55-77` | pas d'ecran de gestion des comptes locaux |
| BO-01-02-06 | FISCAL | `domain/Employee.java:104-116,205-219` | mecanique d'authentification |
| BO-01-02-09 | FIGE | `domain/EmployeeProfile.java:19-30` | echelon pays/enseigne non modelise, arbitrage ouvert |
| BO-01-02-10 | CABLABLE | `domain/Employee.java:280,218-219` | action POST /admin/users/reset-password reutilisant les methodes existantes |
| BO-01-02-11 | FIGE | `security/PasswordChangeFilter.java` | mustChangePassword ne couvre que le MDP BO, pas le PIN caisse |
| BO-01-03-03 | FIGE | `domain/Profile.java ; ui/admin/AdminUserProfileResource.java:143-168` | modele de hierarchie des profils a creer |
| BO-01-03-04 | CABLABLE | `domain/EmployeeProfile.java:50-74 ; ui/admin/AdminUserProfileResource.java:138-168` | selection multiple de PDV rebouclant sur assign() |
| BO-01-04-01 | CABLABLE | `domain/Feature.java:22-35 ; ui/journal/JournalResource.java:65` | ajouter une Feature et remplacer @RolesAllowed par PermissionService.guard() |
| BO-01-04-02 | CABLABLE | `domain/CrudOption.java:17-27` | s'etend automatiquement a toute nouvelle Feature |
| BO-01-04-08 | CABLABLE | `ui/admin/AdminFavoriteResource.java:34-50` | ecran favoris existant, s'etend avec chaque Feature |
| BO-05-01-06 | FISCAL | `ui/journal/JournalResource.java:143-153` | mentions fiscales et export reglementaire |
| BO-05-02-01 | INEXISTANT | `domain/CashMovement.java:56-60` | pas de coffre distinct de la caisse, pas de TVA sur les mouvements |
| BO-05-02-02 | INEXISTANT | `domain/CashMovement.java:59-60` | idem |
| BO-05-02-08 | FIGE | `service/CashSessionService.java:63-155` | SessionReport ne couvre qu'une session |
| BO-05-02-09 | FIGE | `ui/cash/DrawerOperationsResource.java` | aucun statut valide par un manager |
| BO-05-02-10 | INEXISTANT | `domain/CashMovement.java:53-54` | aucune notion de pochette ni de remise au coffre |
| BO-05-02-13 | INEXISTANT | `-` | le coffre n'existe pas |
| BO-05-02-14 | INEXISTANT | `domain/Feature.java:22-35` | aucune cle SAFE |
| BO-05-02-15 | INEXISTANT | `domain/Employee.java:59-76` | aucun suivi d'acompte salarie |
| BO-06-01-02 | FIGE | `ui/journal/JournalResource.java:143-153` | CSV seulement sur l'onglet transactionnel |
| BO-06-04-05 | FIGE | `ui/dashboard/DashboardService.java:47-137` | TVA, mode ecole, annulations, ratios absents |
| BO-06-06-01 | FIGE | `domain/ticket/TechnicalEvent.java:62-65` | detail en texte libre, pas de montant structure |
| BO-06-06-03 | INEXISTANT | `domain/ticket/Ticket.java:124,128` | aucune donnee de duree |
| BO-06-06-04 | INEXISTANT | `-` | aucun catalogue de codes raison |
| BO-06-06-05 | FIGE | `domain/ticket/Refund.java:46-90` | pas de champ raison ni code superviseur |
| BO-06-06-06 | FIGE | `domain/ticket/VoucherPayment.java:24-37` | aucun lien structure emission vers utilisation |
| BO-06-06-07 | INEXISTANT | `service/PosSettingsService.java` | abandon.reasons est du texte libre, pas un catalogue de codes |
| BO-06-06-09 | CABLABLE | `service/CashSessionService.java:71,100-106` | nouvel ecran reutilisant totalsByMethod deja calcule |
| BO-06-06-10 | FIGE | `ui/valuation/ValuationPayloads.java:84` | prix d'origine et pourcentage de demarque non persistes |


## Total

| Verdict | Lignes |
|---|---|
| CABLABLE | 97 |
| FIGE | 68 |
| FISCAL | 19 |
| INEXISTANT | 107 |
| **Total** | **291** |
