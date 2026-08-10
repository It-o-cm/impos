# Group O — Inventaire exhaustif : messages d'erreur & modales

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupOIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupOIT -DskipITs=false`
**Result:** `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` — 103 s, one Quarkus boot.
**Oracle:** the message catalogue (e2e-scenarios.md §O), driven on the public HTTP
surface + peripheral simulator, asserted on the rendered screen or on the injected
singleton `PosState` when a poll/redirect never surfaces the state.

## Scenarios covered (9 @Test, one per §O sub-inventory, O-A split by state family)

### O-A — ticket message zone (transient, cleared at next scan)
- `oa1_scan_zone_messages` — `AUCUNE SESSION OUVERTE - MENU CAISSE` (server-side,
  proven before the shared session opens), `PRODUIT INTERDIT À LA VENTE`,
  `CODE INCONNU: <code>` (server-side — direct field assign, no version bump),
  `PRODUIT INTROUVABLE`, `PLU INTROUVABLE`, `ARTICLE BALANCE INTROUVABLE (<plu>)`,
  `ÉTIQUETTE DÉJÀ SCANNÉE`, `POIDS INVALIDE`, `ERREUR POIDS IDENTIQUE`,
  `ERREUR PRIX SAISI`, `BON DE CONSIGNE ILLISIBLE`. Each asserted shown then
  cleared by a following valid scan (the transitory contract).
- `oa2_line_action_messages` — `AUCUNE LIGNE SÉLECTIONNÉE` (empty-cart gesture),
  `LIGNE INTROUVABLE` (stale uid), `QUANTITÉ INVALIDE (1-999)`,
  `QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE` (weighed line), `VALEUR INVALIDE`.
- `oa3_state_guard_messages` — `TERMINEZ OU ANNULEZ LE TICKET D'ABORD` (training
  over a live cart), `RÉIMPRESSION INDISPONIBLE EN FORMATION`,
  `SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE`, `PAIEMENT REFUSÉ PAR LE TPE`.

### O-B — page messages (`ob_page_messages`)
- Lock: `IDENTIFIANTS INCORRECTS`, then `COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD`
  (crosses the attempt threshold and persists even for a correct PIN during the
  lockout; the cashier lock is DB-reset afterwards).
- Session: `OUVERTURE IMPOSSIBLE (SESSION DÉJÀ OUVERTE ?)` (the rendered
  `open-failed`); the training-open block is REAL but its `INDISPONIBLE EN
  FORMATION` text lives only on the redirect query (dropped by `GET /session`),
  so it is asserted on the 303 `Location`, not on the page.
- PIN change: `Code PIN actuel incorrect`, `Le nouveau code doit comporter 4
  chiffres`, `Les nouveaux codes ne correspondent pas`, `Code PIN modifié`.

### O-C — return staging guards (`oc_return_staging_guards`)
- `RIEN À REMBOURSER` (nothing staged), the in-transaction
  `PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : <montant>)` (a free amount over
  cap, refused only AFTER the manager endorsement grant, then rolled back), and
  `RETOURS INDISPONIBLES EN FORMATION`.

### O-D — payment voucher errors (`od_payment_voucher_errors`)
- `Numéro non reconnu — vérifiez la saisie` (MANUAL Catalina, bad number) and
  `Type de bon inconnu` (a number with no type selected).

### O-E — modals & overlays
- `oe1_gesture_and_endorsement_modals` — the four gesture modals open with the
  `#priceKbArea` PosInput wired; `VALEUR INVALIDE`; ANNULER leaves no trace;
  QUANTITY VALIDER is direct (no endorsement) while REMISE routes through the
  endorsement modal; wrong manager PIN → `AUTORISATION REFUSÉE` (shared counter,
  reset after); manager badge prefill → execution; ANNULER → gesture abandoned.
- `oe2_tpe_and_completion_modals` — TPE overlay appears (amount shown), ACCEPT
  settles+completes, REFUSE withdraws with `PAIEMENT REFUSÉ PAR LE TPE`, register
  CANCEL withdraws the request; completion modal IMPRIMER prints the still-OPEN
  draft (no fiscal close), NOUVELLE VENTE is the close, an overpay shows
  `Rendu Client`.
- `oe3_drawer_interstitial_and_banners` — a guarded GET (/manual) diverts to
  `/drawer-error` and, once the drawer is shut, auto-returns to the blocked PATH;
  a guarded POST diverts and returns to the REFERER (never a replay); the
  FORMATION banner sits at z-index 50 with its warning text.

## Files read
- Catalogue: `e2e-scenarios.md` (§O).
- Production (read-only, none modified): `TicketService`, `HomeService`,
  `HomeResource`, `UnknownScanHandler`, `EanScanHandler`,
  `WeightedEanScanHandler`, `DepositVoucherScanHandler`, `PaymentService`,
  `PaymentResource`, `PosHardwareResource`, `AuthResource`, `AuthService`,
  `PinChangeResource`, `CashSessionResource`, `RefundResource`, `RefundService`,
  `EndorsementResource`, `PosState`, `TicketState`, `CouponType`,
  `DataInitializer`, `MockHardwareResource`, and templates `ticket.html`,
  `main.html`, `lock.html`, `session.html`, `pin-change.html`, `return.html`,
  `return-detail.html`, `pay.html`, `endorsement.html`.
- Existing ITs harvested for proven gestures: `GroupMIT`, `GroupCIT`, `GroupHIT`,
  `E2eTestProfile`.

## Iterations
1. Wrote the full class (9 @Test) → compiled clean → first run: 5/9 green
   (oa1, ob, oc, od, oe1), 4 failing.
2. Diagnosed & fixed: `getTargetItem` falls back to the last line (AUCUNE LIGNE
   needs an empty cart); the `/api/hardware/tpe/*` routes are shadowed by the
   simulator mounted at the same `/api/hardware` root, so accept/refuse are
   played through the injected `PaymentService` bean (as GroupFIT/KIT do); oe3
   drawer waits made content-based. Second run: 7/9.
3. Fixed the last two: `lastClosedTicketId` is global (polluted by earlier
   closed tickets) — baselined it and asserted the OPEN-draft invariant instead;
   `GET /pay` is `@DrawerMayBeOpen` — switched the GET-block to the guarded
   `/manual`. Third run: **9/9 green.**

## Hard points
- **Two visibility classes of a ticket message.** `setError` bumps the poll
  version (rendered on the sale `.error-line`); `CODE INCONNU` and the off-sale
  `AUCUNE SESSION OUVERTE` assign the field directly (no bump) and are asserted
  server-side on `PosState`.
- **Non-rendered session block.** `GET /session` maps only `open-failed`/
  `no-session`; the training `INDISPONIBLE EN FORMATION` code is carried on the
  redirect query and never rendered — asserted on the raw 303 `Location`.
- **TPE routing shadow.** The register's `/api/hardware/tpe/{accept,refuse}` are
  eclipsed on the test classpath by `MockHardwareResource` (`@Path /api/hardware`);
  the simulator's TPE buttons are the injected `PaymentService` calls.
- **Cart is a server singleton.** A new browser page still sees the shared cart,
  so each scenario empties it through the endorsed cancel gesture (the only cart
  reset on the HTTP surface) rather than any in-memory scaffolding.
- **`getTargetItem` last-line fallback.** AUCUNE LIGNE SÉLECTIONNÉE only fires on
  a truly empty cart.
- **Global counters.** `lastClosedTicketId` and fiscal ids leak across tests;
  assertions use deltas/invariants, never absolutes (session numbers match
  `C04-S\d{5}`).

## Justified residue (unreachable on a caisse-seule simulator)
- Supervisor outcomes `SUPERVISEUR PRÉVENU`, `APPEL SUPERVISEUR REFUSÉ (<code>)`,
  `APPEL SUPERVISEUR IMPOSSIBLE`, `APPEL SUPERVISEUR INTERROMPU` — require a
  reachable/failing store node (biface `[N]`) or a thread interruption; under the
  caisse-seule profile `pos.sync.store-url` is unset, so only `SUPERVISION NON
  CONFIGURÉE` is reachable and asserted.
- `QUANTITÉ DÉJÀ REMBOURSÉE (<détail>)` — needs an interleaved second refund to
  open a staging≠execution gap; a single-operator register (one `PosState`)
  cannot host two concurrent stagings. The free-amount `PLAFOND` re-check
  exercises the same in-transaction guard.
- `ACTION DE REMBOURSEMENT INCONNUE` — defensive dispatch branch reached only by
  a malformed `REFUND_<method>`; no HTTP route produces one (`/return/pay/*` only
  park valid enum methods).
- `Aucun opérateur connecté` — belongs to `AuthService.changePin`, not the
  endorsement modal (catalogue mis-attribution); its POST is guarded by
  `state.isLocked()`, which diverts a logged-out caller to the lock page before
  `changePin` runs, so the null-operator arm is unreachable on the screen.
- Valuation banners VALORISATION INDISPONIBLE (z-49), AVANTAGES (z-48),
  SUGGESTION (z-47) — the dedicated valuation module (`[V]`, seeded mirror
  engine) explicitly left non-approfondi by §O; only FORMATION (z-50) is proven.

No `[V]`/`[N]` scenarios were implemented; §O carries no `[V]`/`[N]`-tagged ids of
its own (the whole inventory is `[S]`), and the above residue lists the specific
rows that require the biface/valuation contexts.
