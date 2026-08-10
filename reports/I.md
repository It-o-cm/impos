# Group I — Session & clôture Z — E2E campaign report

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupIIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupIIT -DskipITs=false`
**Result:** `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS (28.0 s, one Quarkus boot for the group).

## Scenarios covered (all [S])

- **I1 — Rapport X** : from the session screen the `RAPPORT X (LECTURE)` link
  prints a `RAPPORT X - LECTURE` snapshot (session number, float, theoretical
  cash) with **no** closing block (`RAPPORT Z`/`ECART` asserted absent). Proven
  read-only: the session stays OPEN, same number, no closing date, and a second
  X prints identically — "imprimable à tout moment, ne mute rien".
- **I2 — Comptage Z** : the whole close walked through the drawer-count page.
  The client-side calculator really runs — tapping `4×Billet 50 €` + `1×Billet
  10 €` sums live to `210,00 €` read back off `#totalDisplay`. The counted total
  travels as a String with its per-denomination JSON, a `50,00 €` withdrawal is
  entered, and the DB oracle confirms `CLOSED`, counted `210,00`, theoretical
  `200,00` (float, no sales), variance `+10,00`, withdrawal `50,00`, detail
  `{b50,b10}`. Paper is `RAPPORT Z - CLOTURE` with the `ECART`/`Especes
  comptees`/`Prelevement` lines. A fresh session then opens at the next
  sequential number — "nouvelle session possible".
- **I3 — Effets du Z** : a ticket parked before the Z is `PARKED` → `CANCELLED`
  by the closing; the next login takes the **A1 morning flow** (lands straight
  on `Aucune session ouverte`); and a sale scan with no session is refused with
  `AUCUNE SESSION OUVERTE - MENU CAISSE`, cart untouched.
- **I4 — Z en formation → bloqué** : in training mode (entered via manager
  endorsement) `close-start` diverts back to the session screen with the
  `INDISPONIBLE EN FORMATION` flag, never reaching `cash-count`; the open
  session is left OPEN with the same number and no closing date.

## Files read

- `e2e-scenarios.md` (group I block, lines 154–162).
- Test pattern: `GroupFIT.java`, `GroupCIT.java` (endorsement gesture),
  `E2eTestProfile.java`.
- Production: `CashSessionResource`, `CashCountResource`, `CashCountService`,
  `CashSessionService`, `TicketPrinterService#printSessionReport`,
  `TicketService#requireOpenSession`/`processScan`, `TicketParkingService`,
  `EndorsementResource`, `HomeService#performTrainingToggle`,
  `HomeResource`/`AuthResource` (routing), domains `CashSession`, `Ticket`.
- Templates: `session.html`, `cash-count.html`, `main.html`, `ticket.html`,
  `endorsement.html`.

## Iterations

One. The class compiled and all four scenarios passed on the first campaign run
(re-run once to confirm stability — green both times).

## Hard points

- **The MENU-CAISSE block is invisible to the poll.** `processScan` refuses a
  no-session sale scan and `return`s *without* `touch()`, so the message never
  reaches `/ticket-fragment`. Asserted on the injected `PosState.ticket
  .transientError` (the group-A unknown-code lesson), not on the rendered screen.
- **The `INDISPONIBLE EN FORMATION` text is never rendered.** `CashSessionResource
  .sessionPage` only maps the `open-failed`/`no-session` error codes to a visible
  message; the training-block code falls through to a null message (no error
  box). The block is therefore asserted by the **redirect target** (URL carries
  the `INDISPONIBLE` flag, `cash-count` never reached) and by the untouched open
  session, not by an on-screen string.
- **The real drawer.** Both the unlock pulse and `close-start` physically open
  the drawer; every guarded sale landing is reached only after
  `POST /api/hardware/drawer/close`. `/lock`, `/session` and `/cash-count`
  (`@DrawerMayBeOpen`) stay reachable drawer-out.
- **Shared session across closing scenarios.** I2 and I3 both close the register;
  each ensures an OPEN session at entry and re-opens one before returning, so
  ordering under `MethodOrderer.MethodName` (i1…i4) stays isolated. Session
  numbers asserted by format/delta (`sequenceOfSession`), never absolute.
- **Deterministic theoretical cash.** Sessions open with a fixed `200,00 €`
  float and carry no sales, so the theoretical cash (and thus the écart) is a
  fixed round figure — the count `210,00` yields a clean `+10,00` variance.

## Justified residue

None. Every group-I scenario is `[S]`; I1–I4 are all implemented. No `[V]`/`[N]`
ids exist in group I.
