package com.intermarche.pos.imports;

import com.intermarche.pos.domain.Employee;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmployeeCsvResource}.
 * <p>
 * Plain JUnit + Mockito: the entity manager is reached through a mocked
 * {@link Panache} static, and the update paths work on REAL {@link Employee}
 * instances carrying real BCrypt hashes — the idempotence contract of this
 * importer is "verify the secret against the hash", so the tests exercise the
 * verification itself, never a mocked shortcut of it.
 */
class EmployeeCsvResourceTest {

    /** The canonical header of the full feed, ACTIVE included. */
    private static final String[] FULL_HEADER = {"BADGE_ID", "LOGIN", "PIN", "FIRST_NAME",
            "LAST_NAME", "EMAIL", "ROLE", "BACK_OFFICE_PASSWORD", "ACTIVE"};

    /** The header without the optional columns. */
    private static final String[] BARE_HEADER = {"BADGE_ID", "LOGIN", "PIN", "FIRST_NAME",
            "LAST_NAME", "EMAIL", "ROLE"};

    /**
     * Builds a header-bound row over the given header.
     *
     * @param header the header names, in cell order
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData line(String[] header, String[] cells) {
        java.util.Map<String, Integer> index = new java.util.LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) index.put(header[i], i);
        return new ImporterCsvResource.LineData(1, index, cells, header[0]);
    }

    /**
     * Builds a stored employee with real hashes for the update tests.
     *
     * @param pin the clear-text PIN the stored hash verifies
     * @param backOffice the clear-text back-office password, or null
     * @return the stored employee
     */
    private static Employee stored(String pin, String backOffice) {
        Employee employee = new Employee();
        employee.id = 42L;
        employee.badgeId = "11111111";
        employee.loginName = "mcurie";
        employee.firstName = "Marie";
        employee.lastName = "Curie";
        employee.email = "marie.curie@test.com";
        employee.role = Employee.EmployeeRole.MANAGER;
        employee.active = true;
        employee.password = Employee.hashPassword(pin);
        employee.setBackOfficePassword(backOffice);
        employee.mustChangePassword = false;
        return employee;
    }

    /**
     * The canonical row matching {@link #stored(String, String)} exactly.
     *
     * @param pin the clear-text PIN cell
     * @param backOffice the back-office password cell (empty = leave alone)
     * @return the cells of the row
     */
    private static String[] matchingCells(String pin, String backOffice) {
        return new String[]{"11111111", "mcurie", pin, "Marie", "Curie",
                "marie.curie@test.com", "MANAGER", backOffice, "true"};
    }

    /**
     * Wraps a string as a UTF-8 input stream.
     *
     * @param content the CSV content
     * @return the input stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code importEmployees} delegates to the base importer with the badge
     * key; a header-only body produces a zero-count JSON with a 200 status.
     */
    @Test
    void importEmployeesDelegatesToBaseImporter() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Response response = resource.importEmployees(stream("BADGE_ID|LOGIN|PIN|FIRST_NAME|LAST_NAME|EMAIL|ROLE\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * {@code feedCode} stays null: employees are never captured for the
     * engine — secrets do not leave the POS chain.
     */
    @Test
    void feedCodeCapturesNothing() {
        assertNull(new EmployeeCsvResource().feedCode());
    }

    /**
     * A new badge creates the employee ({@code employee == null} true arm):
     * identity fields fed, PIN hashed (BCrypt), back-office password set with
     * the forced change, ACTIVE read from its declared column, persisted,
     * created counter incremented.
     */
    @Test
    void processLineLogicCreatesEmployeeWithSecrets() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("1111", "secret-bo")),
                    new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
            verify(em).persist(captor.capture());
            Employee created = captor.getValue();
            assertEquals("11111111", created.badgeId);
            assertEquals("mcurie", created.loginName);
            assertEquals("Marie", created.firstName);
            assertEquals("Curie", created.lastName);
            assertEquals("marie.curie@test.com", created.email);
            assertEquals(Employee.EmployeeRole.MANAGER, created.role);
            assertTrue(created.active);
            assertTrue(created.verifyPassword("1111"));
            assertTrue(created.verifyBackOfficePassword("secret-bo"));
            assertTrue(created.mustChangePassword);
        }
    }

    /**
     * A creation without the optional columns keeps the entity defaults
     * (active true, no back-office password, no forced change) — the
     * back-office arm and the ACTIVE arm both absent.
     */
    @Test
    void processLineLogicCreatesEmployeeWithoutOptionalColumns() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line(BARE_HEADER, new String[]{"22222222", "aeinstein",
                    "2222", "Albert", "Einstein", "albert@test.com", "PICKER"}), new HashMap<>(), counters);
            ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
            verify(em).persist(captor.capture());
            Employee created = captor.getValue();
            assertTrue(created.active);
            assertNull(created.backOfficePassword);
            assertFalse(created.mustChangePassword);
            assertTrue(created.verifyPassword("2222"));
        }
    }

    /**
     * A creation with an empty PIN cell is that line's definitive error
     * ({@code pin == null} true arm): the register cannot authenticate a
     * PIN-less employee, and an empty cell must never hash "".
     */
    @Test
    void processLineLogicRefusesCreationWithoutPin() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(
                line(FULL_HEADER, matchingCells("", "")), new HashMap<>(), new int[]{0, 0}));
    }

    /**
     * Re-importing the exact same row is a strict no-op (identity equal,
     * PIN verifies, back-office cell empty): no counter moves, the forced
     * change is NOT re-armed — the idempotence contract.
     */
    @Test
    void processLineLogicIsIdempotentOnUnchangedRow() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("1111", "")), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertFalse(employee.mustChangePassword);
        }
    }

    /**
     * A different PIN on a known badge re-hashes the PIN and counts one
     * update ({@code pinChanged} true arm), without touching the back-office
     * secret.
     */
    @Test
    void processLineLogicUpdatesChangedPin() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("9999", "")), map, counters);
            assertEquals(1, counters[1]);
            assertTrue(employee.verifyPassword("9999"));
            assertTrue(employee.verifyBackOfficePassword("secret-bo"));
            assertFalse(employee.mustChangePassword);
        }
    }

    /**
     * A different back-office password re-hashes it and re-arms the forced
     * change ({@code backOfficeChanged} true arm).
     */
    @Test
    void processLineLogicUpdatesChangedBackOfficePassword() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("1111", "new-bo")), map, counters);
            assertEquals(1, counters[1]);
            assertTrue(employee.verifyBackOfficePassword("new-bo"));
            assertTrue(employee.mustChangePassword);
        }
    }

    /**
     * A changed identity field (the role here) re-feeds the identity and
     * counts one update ({@code identityDiffers} true arm) while both
     * verified secrets stay untouched.
     */
    @Test
    void processLineLogicUpdatesChangedIdentity() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[6] = "CASHIER";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertEquals(Employee.EmployeeRole.CASHIER, employee.role);
            assertTrue(employee.verifyPassword("1111"));
            assertFalse(employee.mustChangePassword);
        }
    }

    /**
     * A declared ACTIVE column that differs from the stored flag is an
     * identity change (the ACTIVE leg of {@code identityDiffers}).
     */
    @Test
    void processLineLogicUpdatesChangedActiveFlag() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[8] = "false";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertFalse(employee.active);
        }
    }
}
