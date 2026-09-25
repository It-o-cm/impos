package com.intermarche.pos.service.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import com.intermarche.pos.ui.valuation.ValuationPayloads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives a campaign-consumption declaration from a finalized or cancelled
 * ticket and enqueues it for delivery to the engine.
 *
 * <p>It never blocks the sale: enqueuing is a single row insert in the caller's
 * transaction, and the delivery loop ({@link ConsumptionDeliveryService}) ships
 * it afterwards. The declaration is derived from the RETAINED valuation — the
 * verbatim {@code offerCode} and tax-included amounts the engine returned at
 * payment, which the per-line trace does not keep — so the capture reads the
 * engine JSON while it is still on the payment state.
 *
 * <p>Only engine advantages count: a LOCAL or DEGRADED valuation applied none,
 * so nothing is declared. The enqueue is idempotent on
 * {@code (storeCode, ticketNumber, direction)}, the same key the engine dedups
 * on.
 */
@ApplicationScoped
public class ConsumptionService {

    /** Logger for this service. */
    private static final Logger LOGGER = Logger.getLogger(ConsumptionService.class);

    /** Tolerant reader of the engine valuation response. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * One declared advantage: the offer that granted it and the amount granted,
     * in absolute value.
     *
     * @param offerCode the engine offer code
     * @param amount the granted amount, tax included, absolute
     */
    public record DeclaredAdvantage(String offerCode, BigDecimal amount) {
    }

    /**
     * A detached snapshot of one pending declaration, so the delivery thread
     * (which has no persistence context) can compose and POST it freely.
     *
     * @param id the row id, for the acknowledgement
     * @param ticketNumber the fiscal ticket number
     * @param storeCode the point-of-sale code
     * @param finalizedAt the ticket's closing date
     * @param direction the declaration direction
     * @param advantagesJson the advantages JSON array
     */
    public record PendingConsumption(Long id, String ticketNumber, String storeCode,
            java.time.LocalDateTime finalizedAt, ConsumptionOutbox.Direction direction,
            String advantagesJson) {
    }

    /**
     * Returns the declarations still awaiting engine acknowledgement, as
     * detached snapshots in enqueue order.
     *
     * @return the pending declarations, never null
     */
    @Transactional
    public List<PendingConsumption> pending() {
        LOGGER.info("Entering method pending");
        List<PendingConsumption> snapshots = new ArrayList<>();
        for (ConsumptionOutbox row : ConsumptionOutbox.pending()) {
            snapshots.add(new PendingConsumption(row.id, row.ticketNumber, row.storeCode,
                    row.finalizedAt, row.direction, row.advantagesJson));
        }
        LOGGER.info("Exiting method pending");
        return snapshots;
    }

    /**
     * Records the engine's acknowledgement of a declaration.
     *
     * @param id the declaration row id
     */
    @Transactional
    public void markApplied(Long id) {
        LOGGER.info("Entering method markApplied with id: " + id);
        ConsumptionOutbox row = ConsumptionOutbox.findById(id);
        if (row == null) {
            LOGGER.info("Exiting method markApplied: gone");
            return;
        }
        row.applied = true;
        row.lastError = null;
        row.persist();
        LOGGER.info("Exiting method markApplied");
    }

    /**
     * Records a failed delivery attempt on a declaration.
     *
     * @param id the declaration row id
     * @param error the failure description
     */
    @Transactional
    public void recordFailure(Long id, String error) {
        LOGGER.info("Entering method recordFailure with id: " + id);
        ConsumptionOutbox row = ConsumptionOutbox.findById(id);
        if (row == null) {
            LOGGER.info("Exiting method recordFailure: gone");
            return;
        }
        row.attempts = row.attempts + 1;
        row.lastError = error;
        row.persist();
        LOGGER.info("Exiting method recordFailure");
    }

    /**
     * Reads the engine advantages of a valuation response into the declaration
     * shape, keeping only those that name an offer and carry an amount.
     *
     * @param valuationJson the raw engine valuation JSON, possibly null
     * @return the advantages to declare, never null, empty when there are none
     */
    List<DeclaredAdvantage> parseAdvantages(String valuationJson) {
        LOGGER.info("Entering method parseAdvantages");
        List<DeclaredAdvantage> declared = new ArrayList<>();
        if (valuationJson == null || valuationJson.isBlank()) {
            LOGGER.info("Exiting method parseAdvantages: no json");
            return declared;
        }
        ValuationPayloads.ValuationResponseDto response;
        try {
            response = MAPPER.readValue(valuationJson, ValuationPayloads.ValuationResponseDto.class);
        } catch (Exception e) {
            LOGGER.warnf("Valorisation illisible pour la consommation: %s", e.getMessage());
            return declared;
        }
        if (response.advantages == null) {
            LOGGER.info("Exiting method parseAdvantages: no advantages");
            return declared;
        }
        for (ValuationPayloads.AdvantageDto advantage : response.advantages) {
            if (advantage.offerCode == null || advantage.discountAmount == null
                    || advantage.discountAmount.amountIncludingTax == null) {
                continue;
            }
            declared.add(new DeclaredAdvantage(advantage.offerCode,
                    advantage.discountAmount.amountIncludingTax.abs()));
        }
        LOGGER.info("Exiting method parseAdvantages");
        return declared;
    }

    /**
     * Serializes the declared advantages to the JSON array the engine reads.
     *
     * @param advantages the advantages to declare
     * @return the JSON array, or null when serialization fails
     */
    private String toJson(List<DeclaredAdvantage> advantages) {
        try {
            return MAPPER.writeValueAsString(advantages);
        } catch (Exception e) {
            LOGGER.warnf("Sérialisation de la consommation impossible: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Enqueues the SALE consumption of a finalized ticket, when it carries
     * engine advantages and has not already been enqueued.
     *
     * @param ticketNumber the fiscal ticket number
     * @param storeCode the point-of-sale code
     * @param finalizedAt the ticket's closing date
     * @param valuationJson the retained engine valuation JSON
     */
    @Transactional
    public void enqueueSale(String ticketNumber, String storeCode,
            java.time.LocalDateTime finalizedAt, String valuationJson) {
        LOGGER.info("Entering method enqueueSale with ticketNumber: " + ticketNumber);
        List<DeclaredAdvantage> advantages = parseAdvantages(valuationJson);
        if (advantages.isEmpty()) {
            LOGGER.info("Exiting method enqueueSale: nothing to declare");
            return;
        }
        if (ConsumptionOutbox.findByKey(storeCode, ticketNumber,
                ConsumptionOutbox.Direction.SALE) != null) {
            LOGGER.info("Exiting method enqueueSale: already enqueued");
            return;
        }
        ConsumptionOutbox row = new ConsumptionOutbox();
        row.ticketNumber = ticketNumber;
        row.storeCode = storeCode;
        row.finalizedAt = finalizedAt;
        row.direction = ConsumptionOutbox.Direction.SALE;
        row.advantagesJson = toJson(advantages);
        row.persist();
        LOGGER.info("Exiting method enqueueSale: enqueued");
    }

    /**
     * Enqueues the symmetric CANCELLATION of a ticket, but only when a SALE was
     * previously declared for it — an abandoned draft that never finalized has
     * no engine consumption to reverse. Idempotent on the cancellation key.
     *
     * @param ticketNumber the fiscal ticket number
     * @param storeCode the point-of-sale code
     * @param cancelledAt when the cancellation happened
     */
    @Transactional
    public void enqueueCancellation(String ticketNumber, String storeCode,
            java.time.LocalDateTime cancelledAt) {
        LOGGER.info("Entering method enqueueCancellation with ticketNumber: " + ticketNumber);
        ConsumptionOutbox sale = ConsumptionOutbox.findByKey(storeCode, ticketNumber,
                ConsumptionOutbox.Direction.SALE);
        if (sale == null) {
            LOGGER.info("Exiting method enqueueCancellation: no sale to reverse");
            return;
        }
        if (ConsumptionOutbox.findByKey(storeCode, ticketNumber,
                ConsumptionOutbox.Direction.CANCELLATION) != null) {
            LOGGER.info("Exiting method enqueueCancellation: already enqueued");
            return;
        }
        ConsumptionOutbox row = new ConsumptionOutbox();
        row.ticketNumber = ticketNumber;
        row.storeCode = storeCode;
        row.finalizedAt = cancelledAt;
        row.direction = ConsumptionOutbox.Direction.CANCELLATION;
        row.advantagesJson = sale.advantagesJson;
        row.persist();
        LOGGER.info("Exiting method enqueueCancellation: enqueued");
    }
}
