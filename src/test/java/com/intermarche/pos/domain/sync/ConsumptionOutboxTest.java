package com.intermarche.pos.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the finders of {@link ConsumptionOutbox}.
 * <p>
 * The Panache static access is intercepted with {@code
 * mockStatic(PanacheEntityBase.class)} since the entity is not enhanced under
 * plain {@code mvn test}.
 */
class ConsumptionOutboxTest {

    /**
     * {@code findByKey} queries on the three-part key and returns the first row.
     */
    @Test
    void findByKeyReturnsTheKeyedRow() {
        ConsumptionOutbox expected = new ConsumptionOutbox();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ConsumptionOutbox> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> ConsumptionOutbox.find(
                            "storeCode = ?1 and ticketNumber = ?2 and direction = ?3",
                            "C04", "T1", ConsumptionOutbox.Direction.SALE))
                    .thenReturn(query);
            assertSame(expected, ConsumptionOutbox.findByKey("C04", "T1",
                    ConsumptionOutbox.Direction.SALE));
        }
    }

    /**
     * {@code findByKey} returns null when no row carries the key.
     */
    @Test
    void findByKeyReturnsNullWhenAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ConsumptionOutbox> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> ConsumptionOutbox.find(
                            "storeCode = ?1 and ticketNumber = ?2 and direction = ?3",
                            "C04", "T1", ConsumptionOutbox.Direction.CANCELLATION))
                    .thenReturn(query);
            assertNull(ConsumptionOutbox.findByKey("C04", "T1",
                    ConsumptionOutbox.Direction.CANCELLATION));
        }
    }

    /**
     * {@code pending} lists the unacknowledged rows, oldest first.
     */
    @Test
    void pendingListsUnacknowledgedRows() {
        List<ConsumptionOutbox> expected = List.of(new ConsumptionOutbox());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ConsumptionOutbox> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(expected);
            panache.when(() -> ConsumptionOutbox.find("applied = false order by id")).thenReturn(query);
            assertEquals(expected, ConsumptionOutbox.pending());
        }
    }
}
