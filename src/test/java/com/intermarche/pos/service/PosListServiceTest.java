package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.PosList;
import com.intermarche.pos.domain.setting.PosListItem;
import com.intermarche.pos.domain.setting.ReturnTypeDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PosListService}, the resolver of the administered
 * lists ({@code BO-04-02-07}, {@code BO-04-03-12}).
 *
 * <p>The only collaborators are a mocked {@link PosSettingsService} — the
 * fallback road — and the static finder of {@link PosList}, intercepted with
 * {@code mockStatic}: an unstubbed kind answers null, which is exactly the
 * "no list distributed" case the fallback is written for.
 *
 * <p>Branch enumeration (both arms): each of the four resolvers by the
 * referential and by the fallback; the function filter matching, not matching
 * and absent; the four option markers of a return type plus an unknown one
 * and a null option string; an inactive entry; a container with no weight and
 * one with a negative weight; {@code returnType} on null, blank, a matching
 * label and an unknown one; and the EXPENSE / non-EXPENSE arms of the
 * justification fallback.
 */
class PosListServiceTest {

    /**
     * Builds a service whose settings source is a fresh mock.
     *
     * @return the service under test
     */
    private PosListService service() {
        PosListService service = new PosListService();
        service.posSettingsService = mock(PosSettingsService.class);
        return service;
    }

    /**
     * Builds a list carrying the given entries.
     *
     * @param kind what the list drives
     * @param functions the functions it serves, or null for all
     * @param items the entries
     * @return the list
     */
    private PosList list(PosList.Kind kind, String functions, PosListItem... items) {
        PosList posList = new PosList();
        posList.code = "L";
        posList.label = "Liste";
        posList.kind = kind;
        posList.functions = functions;
        posList.active = true;
        for (PosListItem item : items) {
            item.posList = posList;
            posList.items.add(item);
        }
        return posList;
    }

    /**
     * Builds an active entry carrying a code and a label.
     *
     * @param code the entry code
     * @param label the entry label
     * @return the entry
     */
    private PosListItem item(String code, String label) {
        PosListItem item = new PosListItem();
        item.code = code;
        item.label = label;
        item.active = true;
        return item;
    }

    /**
     * BO-04-02-07: a distributed service-request list answers, severity
     * included, and an inactive entry is left out.
     */
    @Test
    void serviceRequestTypesReadsTheReferential() {
        PosListService service = service();
        PosListItem kept = item("ANNUL", "Annulation");
        kept.severity = 3;
        PosListItem silent = item("VIEUX", "Motif retiré");
        silent.active = false;
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(List.of(list(PosList.Kind.SERVICE_REQUEST, null, kept, silent)));
            List<PosListService.ServiceRequestType> types = service.serviceRequestTypes();
            assertEquals(1, types.size());
            assertEquals("ANNUL", types.get(0).code());
            assertEquals("Annulation", types.get(0).label());
            assertEquals(3, types.get(0).severity());
        }
    }

    /**
     * BO-04-02-07: with nothing distributed, the semicolon setting answers,
     * each reason being its own code and carrying no severity; blank entries
     * are dropped.
     */
    @Test
    void serviceRequestTypesFallsBackOnTheSetting() {
        PosListService service = service();
        when(service.posSettingsService.supervisorServiceReasons())
                .thenReturn("Annulation; ;Prix / étiquette;");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(List.of());
            List<PosListService.ServiceRequestType> types = service.serviceRequestTypes();
            assertEquals(2, types.size());
            assertEquals("Annulation", types.get(0).code());
            assertEquals(0, types.get(0).severity());
            assertEquals("Prix / étiquette", types.get(1).label());
        }
    }

    /**
     * BO-04-03-12: a justification list bound to EXPENSE answers that
     * function, and a list bound to another one is not mixed in.
     */
    @Test
    void justificationsFilterOnTheFunction() {
        PosListService service = service();
        PosList expense = list(PosList.Kind.JUSTIFICATION, "EXPENSE;DEPOSIT",
                item("TIMBRES", "Achat de timbres"));
        PosList cancel = list(PosList.Kind.JUSTIFICATION, "TICKET_CANCEL",
                item("ERREUR", "Erreur de saisie"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(expense, cancel));
            assertEquals(List.of("Achat de timbres"),
                    service.justifications(PosListService.FUNCTION_EXPENSE));
            assertEquals(List.of("Erreur de saisie"), service.justifications("TICKET_CANCEL"));
        }
    }

    /**
     * BO-04-03-12: lists exist but none serves the function asked about, so
     * nothing is offered — and the EXPENSE setting is NOT answered to another
     * gesture.
     */
    @Test
    void justificationsEmptyWhenNoListServesTheFunction() {
        PosListService service = service();
        PosList expense = list(PosList.Kind.JUSTIFICATION, "EXPENSE",
                item("TIMBRES", "Achat de timbres"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(expense));
            assertTrue(service.justifications("WITHDRAWAL").isEmpty());
        }
    }

    /**
     * BO-06-06-04: the code-carrying resolver returns the CODE beside the
     * label — what a back-office state groups by.
     */
    @Test
    void justificationsOfCarryTheirCode() {
        PosListService service = service();
        PosList cancel = list(PosList.Kind.JUSTIFICATION, PosListService.FUNCTION_CANCEL_LINE,
                item("ERREUR", "Erreur de saisie"), item("CLIENT", "Refus client"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(cancel));
            List<PosListService.ListChoice> offered =
                    service.justificationsOf(PosListService.FUNCTION_CANCEL_LINE);
            assertEquals(2, offered.size());
            assertEquals("ERREUR", offered.get(0).code());
            assertEquals("Erreur de saisie", offered.get(0).label());
            assertEquals("CLIENT", offered.get(1).code());
        }
    }

    /**
     * BO-06-06-04: a function with no list offers nothing, and a fallen-back
     * EXPENSE reason is its own code — there is nothing else to use.
     */
    @Test
    void justificationsOfFallBackAndAnswerNothing() {
        PosListService service = service();
        when(service.posSettingsService.cashMovementReasons())
                .thenReturn(List.of("Achat de timbres"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of());
            List<PosListService.ListChoice> expense =
                    service.justificationsOf(PosListService.FUNCTION_EXPENSE);
            assertEquals(1, expense.size());
            assertEquals("Achat de timbres", expense.get(0).code());
            assertEquals("Achat de timbres", expense.get(0).label());
            assertTrue(service.justificationsOf(PosListService.FUNCTION_CANCEL_LINE).isEmpty());
            assertTrue(service.justificationsOf(null).isEmpty());
        }
    }

    /**
     * BO-06-06-04: {@code justificationOf} resolves a code whatever its case,
     * and answers null for a null one, a blank one and one the list does not
     * name — the four arms of its lookup.
     */
    @Test
    void justificationOfResolvesACodeOrAnswersNull() {
        PosListService service = service();
        PosList cancel = list(PosList.Kind.JUSTIFICATION, PosListService.FUNCTION_CANCEL_LINE,
                item("ERREUR", "Erreur de saisie"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(cancel));
            assertEquals("Erreur de saisie", service.justificationOf(
                    PosListService.FUNCTION_CANCEL_LINE, " erreur ").label());
            assertNull(service.justificationOf(PosListService.FUNCTION_CANCEL_LINE, null));
            assertNull(service.justificationOf(PosListService.FUNCTION_CANCEL_LINE, "   "));
            assertNull(service.justificationOf(PosListService.FUNCTION_CANCEL_LINE, "INCONNU"));
        }
    }

    /**
     * BO-06-06-05: the return reasons come from the referential with their
     * codes, and the label resolves to the entry carrying it whatever its
     * case; a null, a blank and an unknown label resolve to nothing.
     */
    @Test
    void returnReasonsComeFromTheReferential() {
        PosListService service = service();
        PosList reasons = list(PosList.Kind.RETURN_REASON, null,
                item("PERIME", "Article périmé"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_REASON))
                    .thenReturn(List.of(reasons));
            assertEquals(1, service.returnReasons().size());
            assertEquals("PERIME", service.returnReasons().get(0).code());
            assertEquals("PERIME", service.returnReason(" article périmé ").code());
            assertNull(service.returnReason(null));
            assertNull(service.returnReason("   "));
            assertNull(service.returnReason("Inconnu"));
        }
    }

    /**
     * BO-06-06-05: with nothing distributed the reasons fall back on
     * {@code return.reasons}, each reason being its own code.
     */
    @Test
    void returnReasonsFallBackOnTheSetting() {
        PosListService service = service();
        when(service.posSettingsService.returnReasons())
                .thenReturn(List.of("Erreur de caisse"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_REASON))
                    .thenReturn(List.of());
            assertEquals("Erreur de caisse", service.returnReasons().get(0).code());
            assertEquals("Erreur de caisse", service.returnReason("erreur de caisse").label());
        }
    }

    /**
     * BO-06-06-05: the return TYPE resolves to its administered code, to the
     * label itself when no coded list is distributed, and to nothing for a
     * null or blank label.
     */
    @Test
    void returnTypeCodeResolvesOrFallsBackOnTheLabel() {
        PosListService service = service();
        PosList types = list(PosList.Kind.RETURN_TYPE, null, item("ECH", "Échange"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of(types));
            assertEquals("ECH", service.returnTypeCode(" échange "));
            assertEquals("Inconnu", service.returnTypeCode("Inconnu"));
            assertNull(service.returnTypeCode(null));
            assertNull(service.returnTypeCode("   "));
        }
    }

    /**
     * BO-06-06-05: with no return-type list distributed, the label is its own
     * code — there is nothing else to use.
     */
    @Test
    void returnTypeCodeIsTheLabelWhenNothingIsDistributed() {
        PosListService service = service();
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of());
            assertEquals("Échange", service.returnTypeCode("Échange"));
        }
    }

    /**
     * BO-04-03-12: with nothing distributed, EXPENSE falls back on
     * {@code cash.movement-reasons} and any other function offers nothing.
     */
    @Test
    void justificationsFallBackOnTheExpenseSettingOnly() {
        PosListService service = service();
        when(service.posSettingsService.cashMovementReasons())
                .thenReturn(List.of("Achat de timbres"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of());
            assertEquals(List.of("Achat de timbres"),
                    service.justifications(PosListService.FUNCTION_EXPENSE));
            assertTrue(service.justifications("WITHDRAWAL").isEmpty());
            assertTrue(service.justifications(null).isEmpty());
        }
    }

    /**
     * LC-05-04-01: a distributed return-type list reads the four option
     * markers, ignores an unknown one and accepts an entry carrying none.
     */
    @Test
    void returnTypesReadTheOptionMarkers() {
        PosListService service = service();
        PosListItem full = item("SANS", "Sans ticket");
        full.options = "TICKET,NOENDORSE,NOORIGIN,PRICE,INCONNU";
        PosListItem bare = item("ECHANGE", "Échange");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of(list(PosList.Kind.RETURN_TYPE, null, full, bare)));
            List<PosSettingsService.ReturnType> types = service.returnTypes();
            assertEquals(2, types.size());
            assertTrue(types.get(0).dedicatedTicket());
            assertTrue(types.get(0).endorsementExempt());
            assertTrue(types.get(0).originOptional());
            assertTrue(types.get(0).priceConfirmed());
            assertEquals("Échange", types.get(1).label());
            assertTrue(!types.get(1).dedicatedTicket() && !types.get(1).endorsementExempt()
                    && !types.get(1).originOptional() && !types.get(1).priceConfirmed());
        }
    }

    /**
     * LC-05-04-01: with nothing distributed, the administered string answers.
     */
    @Test
    void returnTypesFallBackOnTheSetting() {
        PosListService service = service();
        List<PosSettingsService.ReturnType> administered =
                List.of(new PosSettingsService.ReturnType("Échange", false));
        when(service.posSettingsService.returnTypes()).thenReturn(administered);
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of());
            assertEquals(administered, service.returnTypes());
        }
    }

    /**
     * BO-03-13-06/-08/-10: a type is resolved by its label, case-insensitively;
     * a null, a blank and an unknown label resolve to nothing.
     */
    @Test
    void returnTypeResolvesByLabel() {
        PosListService service = service();
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of(list(PosList.Kind.RETURN_TYPE, null,
                            item("ECHANGE", "Échange"))));
            assertEquals("Échange", service.returnType("  échange ").label());
            assertNull(service.returnType("Inconnu"));
            assertNull(service.returnType(null));
            assertNull(service.returnType("   "));
        }
    }

    /**
     * LC-14-01-04: a distributed container list answers its weights, an entry
     * carrying none or a negative one being dropped.
     */
    @Test
    void tareContainersReadTheReferential() {
        PosListService service = service();
        PosListItem kept = item("BQS", "Barquette S");
        kept.numericValue = new BigDecimal("0.035");
        PosListItem weightless = item("RIEN", "Sans poids");
        PosListItem negative = item("NEG", "Poids négatif");
        negative.numericValue = new BigDecimal("-0.010");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.TARE_CONTAINER))
                    .thenReturn(List.of(list(PosList.Kind.TARE_CONTAINER, null,
                            kept, weightless, negative)));
            List<PosSettingsService.TareContainer> containers = service.tareContainers();
            assertEquals(1, containers.size());
            assertEquals("BQS", containers.get(0).code());
            assertEquals(new BigDecimal("0.035"), containers.get(0).tareKg());
        }
    }

    /**
     * LC-14-01-04: with nothing distributed, the administered string answers.
     */
    @Test
    void tareContainersFallBackOnTheSetting() {
        PosListService service = service();
        List<PosSettingsService.TareContainer> administered = List.of(
                new PosSettingsService.TareContainer("SACPAP", "Sac kraft", new BigDecimal("0.010")));
        when(service.posSettingsService.tareContainers()).thenReturn(administered);
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.TARE_CONTAINER))
                    .thenReturn(List.of());
            assertEquals(administered, service.tareContainers());
        }
    }

    /**
     * A finder answering null — the kind was never asked of the database —
     * takes the same road as an empty one: the fallback.
     */
    @Test
    void aNullFinderResultFallsBack() {
        PosListService service = service();
        when(service.posSettingsService.supervisorServiceReasons()).thenReturn("Autre");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(null);
            assertEquals(1, service.serviceRequestTypes().size());
        }
    }
    /** BO-06-06-05/BO-04-03-12: malformed list entries without a lookup label or code cannot match a cashier choice. */
    @Test
    void malformedListEntriesCannotResolveAChoice() {
        var service = service();
        var reason = list(PosList.Kind.RETURN_REASON, null, item("R", null));
        var justification = list(PosList.Kind.JUSTIFICATION, null, item(null, "LABEL"));
        var type = list(PosList.Kind.RETURN_TYPE, null, item("T", null));
        try (var lists = mockStatic(PosList.class);
                var definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_REASON)).thenReturn(List.of(reason));
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION)).thenReturn(List.of(justification));
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE)).thenReturn(List.of(type));
            assertNull(service.returnReason("LABEL"));
            assertNull(service.justificationOf("FUNCTION", "CODE"));
            assertEquals("LABEL", service.returnTypeCode("LABEL"));
        }
    }


    /**
     * Builds an entry carrying option markers and a value.
     *
     * @param code the entry code
     * @param label the entry label
     * @param options the option markers, or null
     * @param value the value, or null
     * @return the entry
     */
    private PosListItem item(String code, String label, String options, String value) {
        PosListItem item = item(code, label);
        item.options = options;
        item.numericValue = value == null ? null : new BigDecimal(value);
        return item;
    }

    /**
     * BO-03-02-45/-47: the expenses are the EXPENSE justifications, each with
     * its tender (cash meaning none), its maximum and its control; without a
     * list, the movement reasons answer as unbounded cash expenses.
     */
    @Test
    void expensesReadTheReferentialThenTheSetting() {
        PosListService service = service();
        PosListItem stamps = item("TIMBRES", "Timbres", "TENDER:cheque, CONTROL:BLOCKING", "50");
        PosListItem coffee = item("CAFE", "Café", "TENDER:CASH", null);
        PosListItem bare = item("DIVERS", "Divers", null, null);
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(list(PosList.Kind.JUSTIFICATION, "EXPENSE", stamps, coffee, bare)));
            List<PosListService.Expense> expenses = service.expenses();
            assertEquals(3, expenses.size());
            assertEquals("CHEQUE", expenses.get(0).tenderCode());
            assertEquals(new BigDecimal("50"), expenses.get(0).maxAmount());
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                    expenses.get(0).control());
            assertNull(expenses.get(1).tenderCode());
            assertNull(expenses.get(2).tenderCode());
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                    expenses.get(2).control());
        }
        when(service.posSettingsService.cashMovementReasons()).thenReturn(List.of("Timbres"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION)).thenReturn(List.of());
            List<PosListService.Expense> expenses = service.expenses();
            assertEquals(1, expenses.size());
            assertEquals("Timbres", expenses.get(0).code());
            assertNull(expenses.get(0).maxAmount());
        }
    }

    /**
     * BO-03-02-46/-47: an expense is found by label or code; a blank or unknown
     * reason finds none; above its maximum the expense answers its control.
     */
    @Test
    void anExpenseIsFoundAndControlled() {
        PosListService service = service();
        PosListItem stamps = item("TIMBRES", "Timbres poste", "CONTROL:WARNING", "50");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(list(PosList.Kind.JUSTIFICATION, null, stamps)));
            assertNull(service.expense(null));
            assertNull(service.expense("  "));
            assertNull(service.expense("Essence"));
            assertEquals("TIMBRES", service.expense(" timbres poste ").code());
            PosListService.Expense expense = service.expense("TIMBRES");
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.WARNING,
                    expense.levelFor(new BigDecimal("50.01")));
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                    expense.levelFor(new BigDecimal("50")));
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                    expense.levelFor(null));
        }
        PosListService.Expense unbounded = new PosListService.Expense("X", "X", null, null,
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING);
        assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                unbounded.levelFor(BigDecimal.TEN));
    }

    /**
     * The option and control readers: an absent string, a marker with no
     * name, another name and an empty value read nothing; an unknown level
     * reads none.
     */
    @Test
    void optionsAndLevelsAreReadTolerantly() {
        assertNull(PosListService.option(null, "TENDER"));
        assertNull(PosListService.option(":CHEQUE,CONTROL:INFO", "TENDER"));
        assertNull(PosListService.option("TENDER:", "TENDER"));
        assertEquals("INFO", PosListService.option("TENDER,CONTROL: INFO", "CONTROL"));
        assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                PosListService.controlLevel(null));
        assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                PosListService.controlLevel("BLOK"));
        assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                PosListService.controlLevel(" supervisor "));
        assertFalse(PosListService.marked(null, "CUMUL"));
        assertNull(PosListService.decimal(null));
        assertNull(PosListService.decimal("abc"));
        assertNull(PosListService.decimal("-3"));
        assertEquals(new BigDecimal("2.5"), PosListService.decimal("2,5"));
    }

    /**
     * BO-03-07-03/-04/-06/-09: the discount keys are the DISCOUNT entries of a
     * known nature — a key naming both natures, none, or a non-positive figure
     * is dropped — with their scope, cumulation and ceiling; without a list,
     * the parameter's keys answer as line keys.
     */
    @Test
    void discountKeysReadTheReferentialThenTheSetting() {
        PosListService service = service();
        PosListItem twenty = item("M20", "-20%", "PERCENT", "20");
        PosListItem open = item("LIBRE", "Libre", "AMOUNT,TICKET,CUMUL,MAX:30", null);
        PosListItem both = item("B", "Both", "PERCENT,AMOUNT", "5");
        PosListItem none = item("N", "None", "CUMUL", "5");
        PosListItem zero = item("Z", "Zero", "PERCENT", "0");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.DISCOUNT))
                    .thenReturn(List.of(list(PosList.Kind.DISCOUNT, null, twenty, open, both, none, zero)));
            List<PosListService.DiscountKey> keys = service.discountKeys();
            assertEquals(2, keys.size());
            assertTrue(keys.get(0).percent());
            assertFalse(keys.get(0).open());
            assertFalse(keys.get(0).ticketLevel());
            assertNull(keys.get(0).maximum());
            assertFalse(keys.get(1).percent());
            assertTrue(keys.get(1).open());
            assertTrue(keys.get(1).ticketLevel());
            assertTrue(keys.get(1).cumulative());
            assertEquals(new BigDecimal("30"), keys.get(1).maximum());
            assertNull(service.discountKey(null));
            assertNull(service.discountKey(" "));
            assertNull(service.discountKey("-50%"));
            assertEquals("Libre", service.discountKey(" libre ").label());
        }
        when(service.posSettingsService.discountPresets()).thenReturn(List.of(
                new PosSettingsService.DiscountPreset("-10%", true, BigDecimal.TEN, true)));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.DISCOUNT)).thenReturn(List.of());
            List<PosListService.DiscountKey> keys = service.discountKeys();
            assertEquals(List.of(new PosListService.DiscountKey("-10%", true, BigDecimal.TEN, true,
                    false, null)), keys);
        }
    }

    /**
     * BO-03-13-12: the return type a barcode range is attached to is the entry
     * whose RANGE option names it, its markers read; no range, no list, or no
     * attachment answers none.
     */
    @Test
    void theReturnTypeOfARangeIsTheAttachedEntry() {
        PosListService service = service();
        PosListItem machine = item("TRI", "Retour machine de tri", "RANGE:RET29,NOORIGIN,NOENDORSE", null);
        PosListItem other = item("CONT", "Contenant", null, null);
        assertNull(service.returnTypeOfRange(null));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE)).thenReturn(List.of());
            assertNull(service.returnTypeOfRange("RET29"));
        }
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of(list(PosList.Kind.RETURN_TYPE, null, other, machine)));
            PosSettingsService.ReturnType type = service.returnTypeOfRange("ret29");
            assertEquals("Retour machine de tri", type.label());
            assertTrue(type.originOptional());
            assertTrue(type.endorsementExempt());
            assertFalse(type.dedicatedTicket());
            assertFalse(type.priceConfirmed());
            assertNull(service.returnTypeOfRange("RET30"));
        }
    }

    /**
     * Builds a return type of the dedicated referential.
     *
     * @param code the type code
     * @param label the type label
     * @param active whether the register offers it
     * @return the type
     */
    private ReturnTypeDefinition definition(String code, String label, boolean active) {
        ReturnTypeDefinition type = new ReturnTypeDefinition();
        type.code = code;
        type.label = label;
        type.active = active;
        return type;
    }

    /**
     * BO-03-13-01/-03: once the dedicated referential holds a type, it wins
     * over the list — its ACTIVE types are offered, options read off their
     * columns, and an inactive type is kept out.
     */
    @Test
    void returnTypesReadTheDedicatedReferential() {
        PosListService service = service();
        ReturnTypeDefinition ticket = definition("TCK", "Retour ticket", true);
        ticket.ticketMode = true;
        ticket.endorsementExempt = true;
        ticket.originOptional = true;
        ticket.priceConfirmed = true;
        ReturnTypeDefinition off = definition("OFF", "Désactivé", false);
        try (MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            definitions.when(ReturnTypeDefinition::findAllOrdered).thenReturn(List.of(ticket, off));
            List<PosSettingsService.ReturnType> types = service.returnTypes();
            assertEquals(1, types.size());
            assertEquals("Retour ticket", types.get(0).label());
            assertTrue(types.get(0).dedicatedTicket() && types.get(0).endorsementExempt()
                    && types.get(0).originOptional() && types.get(0).priceConfirmed());
        }
    }

    /**
     * BO-03-13-01: an EMPTY referential, like an absent one, is no
     * referential — the list, then the setting, still answer.
     */
    @Test
    void anEmptyReferentialFallsBackOnTheList() {
        PosListService service = service();
        when(service.posSettingsService.returnTypes())
                .thenReturn(List.of(new PosSettingsService.ReturnType("Échange", false)));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
                MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            definitions.when(ReturnTypeDefinition::findAllOrdered).thenReturn(List.of(), (List<ReturnTypeDefinition>) null);
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE)).thenReturn(List.of());
            assertEquals("Échange", service.returnTypes().get(0).label());
            assertEquals("Échange", service.returnTypes().get(0).label());
        }
    }

    /**
     * BO-03-13-12: with the referential, the type of a range is the ACTIVE
     * type attached to it; an inactive one and one attached elsewhere are
     * passed over, and an unattached range names none.
     */
    @Test
    void theReturnTypeOfARangeReadsTheReferential() {
        PosListService service = service();
        ReturnTypeDefinition inactive = definition("OLD", "Ancien", false);
        inactive.rangeCode = "TRI";
        ReturnTypeDefinition other = definition("AUT", "Autre", true);
        other.rangeCode = "AUTRE";
        ReturnTypeDefinition attached = definition("MAC", "Machine de tri", true);
        attached.rangeCode = "tri";
        attached.originOptional = true;
        try (MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            definitions.when(ReturnTypeDefinition::findAllOrdered).thenReturn(List.of(inactive, other, attached));
            PosSettingsService.ReturnType type = service.returnTypeOfRange("TRI");
            assertEquals("Machine de tri", type.label());
            assertTrue(type.originOptional());
            assertNull(service.returnTypeOfRange("NONE"));
            assertNull(service.returnTypeOfRange(null));
        }
    }

    /**
     * BO-03-13-12: with the referential, the code of a label is the
     * referential's, a type without a label never matching, and an unknown
     * label is its own code.
     */
    @Test
    void returnTypeCodeReadsTheReferential() {
        PosListService service = service();
        ReturnTypeDefinition unnamed = definition("NONAME", null, true);
        ReturnTypeDefinition known = definition("ECH", "Échange", false);
        try (MockedStatic<ReturnTypeDefinition> definitions = mockStatic(ReturnTypeDefinition.class)) {
            definitions.when(ReturnTypeDefinition::findAllOrdered).thenReturn(List.of(unnamed, known));
            assertEquals("ECH", service.returnTypeCode(" échange "));
            assertEquals("Inconnu", service.returnTypeCode("Inconnu"));
        }
    }
}
