# Group J — Mode formation (E2E campaign report)

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupJIT.java`
**Run:** `mvn -q verify -DskipUTs=true -Dit.test=GroupJIT -DskipITs=false`
**Result:** `tests=3 errors=0 skipped=0 failures=0` — BUILD SUCCESS (one Quarkus boot, ~30 s wall).

## Scenarios covered (all `[S]`, all implemented)

| Id | Scenario | Oracles exercised |
|----|----------|-------------------|
| **J1** | Entrée/sortie sous avenant — bannières orange sur tous les écrans (caisse ET client), état propre à la sortie | Endorsed toggle flips `PosState.trainingMode`; sale screen raises `.overlay-training` (`MODE FORMATION`); customer display `#trainingBanner` becomes visible via its `/customer-data` poll; second endorsed toggle drops the cashier banner from the DOM and re-hides the customer banner; cart left empty. |
| **J2** | Neutralisation fiscale — vente sans écriture : aucun draft, paiements simulés, tiroir jamais ouvert, reçu mémoire NON VALABLE | Two scans accepted onto the cart (12,00 € in memory); pay overlay raises its own `.overlay-training` banner; cash tender completes (`transactionComplete`, `payment.payments` non-empty) yet `ticketDbId` stays null and the C04 `Ticket` row count is unchanged; drawer status stays `CLOSED` across the cash payment; the `IMPRIMER` receipt is a `MODE FORMATION` / `TICKET NON VALABLE` / `FORMATION - SANS VALEUR` slip carrying no `C04-\d{8}` number; the open session is left OPEN, same number, no closing date. |
| **J3** | Actions bloquées — réimpression, création de retour, mutations de session, sur les deux chemins | Duplicata reprint refused on the on-screen `IMPRIMER` link AND the direct `/reprint/print/{id}` URL (`RÉIMPRESSION INDISPONIBLE EN FORMATION` on `ticket.transientError`, printer carries no ticket number); refund request refused (`RETOURS INDISPONIBLES EN FORMATION`, no endorsement opened, `Refund` count for the ticket unchanged); every session mutation blocked — `close-start` on both the `CLÔTURE DE CAISSE (Z)` button and its direct GET URL (divert to `/session` with `INDISPONIBLE`, never reaching `cash-count`), and the POST-only `open`/`close` routes on the request bus (both divert with `INDISPONIBLE`); the open session left untouched. |

## Files read

- `e2e-scenarios.md` (group J spec).
- Reference test classes: `GroupIIT.java` (training-Z block I4, login recipe, DB oracles), `GroupHIT.java` (training refund block, closed-ticket build, refund-screen gestures), `GroupGIT.java` (customer-display banner assertion), `E2eTestProfile.java`.
- Production: `PosState.java`, `HomeService.java` / `HomeResource.java` (endorsed training toggle), `EndorsementResource.java`, `AuthResource.java` (training skips the session detour), `PaymentService.java` / `PaymentResource.java` (drawer-shut-in-training, memory receipt path), `TicketPersistenceService.java` (`syncDraft` returns null in training), `TicketPrinterService.java` (`printTrainingReceipt` — NON VALABLE), `ReprintService.java` / `ReprintResource.java`, `RefundService.java` / `RefundResource.java`, `CashSessionResource.java` (session-mutation training block + drawer annotations), `CustomerDisplayResource.java`.
- Templates: `main.html`, `pay.html`, `customer.html`, `session.html`, `reprint-ticket-detail.html`, `return-detail.html`.
- Seed EANs: `DataInitializer.java` (Huile d'Olive `3300000000006`, Miel d'Acacia `3300000000024`, both 6,00 € TTC).

## Iterations

1. **v1** — first run: J1, J2 green; J3 failed. `assertSessionMutationBlockedByUrl` timed out waiting `Session en cours` because (a) the drawer was left out from the login pulse and the session-mutation routes are drawer-guarded (`DrawerCheckFilter` diverted to `/drawer-error`), and (b) `/action/session/open` and `/action/session/close` are `@POST` routes that a `page.navigate` (GET) cannot reach (405).
2. **v2** — added a `closeDrawer()` before the session block, kept `close-start` (a real `@GET`) on both button and URL, and probed the POST-only `open`/`close` mutations on the request bus via a new `assertPostSessionRouteBlocked` helper. All three green.

## Hard points

- **`syncDraft` is the single training guard.** Fiscal neutralization (J2) is proven three ways at once — `ticketDbId == null`, unchanged C04 `Ticket.count`, and the drawer never pulsing — because `syncDraft` short-circuits to `null` in training and every drawer opener is `if (!trainingMode)`-gated.
- **The refund method links are not a second path.** `ESPÈCES` and its siblings on the return detail run `onclick="return submitAction(this)"`, which submits the staging form and *cancels* the href — so a plain click never issues the refund; the request only ever fires via the `GET /return/pay/{method}` route the link targets. J3 therefore exercises that route on a fully-staged line (matching the `GroupHIT` return doctrine) rather than a misleading click. See residue.
- **Session-mutation routes split by HTTP verb.** Only `close-start` is a `@GET` with a real on-screen button (the `Z` link), so it carries the genuine button-vs-URL pair; `open`/`close` are `@POST` with no button while a session is open, so they are probed on the request bus.
- **`?error=INDISPONIBLE` never renders as text.** `sessionPage` only maps `open-failed`/`no-session` to a visible message, so the training block is asserted on the URL flag (as in I4), not on rendered text.

## Justified residue

- **J2 literal "vente SANS session" (routing bypass).** The catalog frames J2 as a sale with *no open session* (login routed straight to the sale, skipping the session screen — `AuthResource` line 109: training skips the session detour). This cannot be bootstrapped purely through the screen: the training toggle lives only on the sale screen, reachable only with an open session, and that session cannot then be closed in training (the Z is blocked — I4/J3). The **fiscal essence** of "sans session" — the training sale consumes/mutates no session, writes no draft, and leaves the open session pristine — is fully proven on the database in J2; only the pure UI routing bypass is left as residue.
- **J3 refund "button" path.** As above, the method links funnel through `submitAction` to the same `GET /return/pay/{method}` route, so button and URL are not two distinct server paths for a refund; the single request route is exercised on a staged line. (Reprint and session-`close-start` retain genuine button-vs-URL pairs.)
- No `[V]`/`[N]` scenarios exist in group J — every J id is `[S]` and implemented.
