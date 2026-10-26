package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.barcode.ScannedBarcode;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.domain.util.DateTimeProvider;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Journals every payment barcode read at this till, known or not, and pushes
 * the reading to the store node (BO-03-06-63): the back office lists the
 * codes passed at the tills with the register, the sale, the amount, the end
 * of validity, the date of use and whether the system knew the code.
 */
@ApplicationScoped
public class ScannedBarcodeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ScannedBarcodeService.class);

    /** The longest code the journal keeps. */
    static final int MAX_CODE_LENGTH = 64;

    /** This register's identifier. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** The push to the store node. */
    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Records one reading.
     *
     * @param code the code read, a blank one recording nothing
     * @param type the range that recognised it, or null
     * @param amount what it was worth, or null when unknown
     * @param validUntil its end of validity, or null
     * @param found whether the system knew the code
     * @param ticketId the sale it was presented to, or null
     * @param gs1 whether the code was read as a GS1 payload (BO-11-01-18)
     */
    @Transactional
    public void record(String code, CouponType type, BigDecimal amount, LocalDateTime validUntil,
                       boolean found, Long ticketId, boolean gs1) {
        LOGGER.info("Entering method record with code: " + code + ", found: " + found);
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method record");
            return;
        }
        ScannedBarcode reading = new ScannedBarcode();
        reading.terminalId = terminalId;
        String trimmed = code.trim();
        reading.code = trimmed.length() > MAX_CODE_LENGTH ? trimmed.substring(0, MAX_CODE_LENGTH) : trimmed;
        reading.typeCode = type == null ? null : type.code;
        reading.typeLabel = type == null ? null : type.label;
        Ticket ticket = ticketId == null ? null : Ticket.findById(ticketId);
        reading.ticketNumber = ticket == null ? null : ticket.ticketNumber;
        reading.amount = amount;
        reading.validUntil = validUntil;
        reading.scannedAt = DateTimeProvider.now();
        reading.found = found;
        reading.gs1 = gs1;
        reading.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.SCANNED_BARCODE, reading.id);
        LOGGER.info("Exiting method record");
    }
}
