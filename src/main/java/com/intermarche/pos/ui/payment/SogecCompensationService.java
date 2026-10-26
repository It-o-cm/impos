package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.barcode.gs1.Gs1ApplicationIdentifier;
import com.intermarche.pos.domain.barcode.gs1.Gs1Message;
import com.intermarche.pos.domain.barcode.gs1.Gs1Parser;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * The SOGEC manufacturer-coupon compensation circuit (EXP-58): a SOGEC bon is
 * NOT a generic GS1 coupon. It is recognised on its own administered prefixes
 * and routed here, which records the supplier-reimbursement trace so the coupon
 * can be sent back to SOGEC for compensation — distinct from the generic GS1
 * coupon path, which grants an immediate discount and expects no reimbursement.
 *
 * <p>The recognised prefixes are administered ({@code sogec.bon-prefixes}); the
 * compensation itself is a journal trace (the store node reconciles it with the
 * SOGEC statements), so no synchronous supplier call sits on the sale path.
 */
@ApplicationScoped
public class SogecCompensationService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SogecCompensationService.class);

    /** The administered SOGEC bon prefixes and the journal. */
    @Inject
    PosSettingsService posSettingsService;

    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Whether a scanned code belongs to the SOGEC circuit — its prefix is one of
     * the administered SOGEC bon prefixes (EXP-58).
     *
     * @param code the scanned code, possibly null
     * @return true when the code is a SOGEC bon
     */
    public boolean isSogecBon(String code) {
        LOGGER.info("Entering method isSogecBon");
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method isSogecBon with false");
            return false;
        }
        String administered = posSettingsService.sogecBonPrefixes();
        if (administered == null || administered.isBlank()) {
            LOGGER.info("Exiting method isSogecBon with false");
            return false;
        }
        for (String prefix : administered.split(";")) {
            String trimmed = prefix.trim();
            if (!trimmed.isEmpty() && code.startsWith(trimmed)) {
                LOGGER.info("Exiting method isSogecBon with true");
                return true;
            }
        }
        LOGGER.info("Exiting method isSogecBon with false");
        return false;
    }

    /**
     * The FACE VALUE a SOGEC bon carries in its own barcode, when it carries one
     * ({@code LC-11-03-21}): the GS1 amount-payable identifier {@code (390n)}, the
     * same element the generic coupon path reads.
     *
     * <p>A bon is worth what is printed on it, not what is left to pay. The register
     * used to settle a SOGEC bon with the whole remaining due, so a 20 € bon paid a
     * 56,50 € ticket in full; the value now comes from the paper — decoded here when
     * the bon is a GS1 payload, keyed by the cashier otherwise.
     *
     * <p>A code that is not a GS1 payload — most SOGEC bons, which carry a plain
     * administered prefix — decodes to an EMPTY message rather than to nonsense
     * (the decoder claims a payload only when the payload says it is GS1), so this
     * returns null and the keyed value is used.
     *
     * @param code the bon code, scanned or keyed, possibly null
     * @return the encoded face value in euros, or null when the code carries none
     */
    public BigDecimal faceValueOf(String code) {
        LOGGER.info("Entering method faceValueOf");
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method faceValueOf with null");
            return null;
        }
        Gs1Message message = Gs1Parser.parse(code.trim());
        BigDecimal encoded = message.decimal(Gs1ApplicationIdentifier.AMOUNT_PAYABLE);
        if (encoded == null || encoded.signum() <= 0) {
            LOGGER.info("Exiting method faceValueOf with null");
            return null;
        }
        LOGGER.info("Exiting method faceValueOf with " + encoded);
        return encoded;
    }

    /**
     * Records a SOGEC compensation trace for a bon settled on the ticket
     * (EXP-58): the coupon code and its amount, journaled for the supplier
     * reimbursement.
     *
     * @param couponCode the SOGEC bon code
     * @param amount the amount the bon settled
     */
    public void recordCompensation(String couponCode, BigDecimal amount) {
        LOGGER.info("Entering method recordCompensation");
        technicalEventService.log(TechnicalEvent.EventType.SOGEC_COMPENSATION,
                couponCode + " " + amount, null);
        LOGGER.info("Exiting method recordCompensation");
    }
}
