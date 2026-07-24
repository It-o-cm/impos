package com.intermarche.pos.service.sync;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON payloads exchanged between a register and the store node (phase 5).
 * <p>
 * Every reference is a natural key (ticket/session/refund number, cashier
 * login, store code, EAN/PLU, line uid) — never a local database id, since
 * each register has its own database. Dates travel as ISO-8601 strings to
 * stay independent of the JSON date modules on both ends.
 * <p>
 * Evolution discipline: ADD fields, never rename or repurpose them — both
 * ends default missing fields to null (tolerant reader), so a register and
 * a store node one version apart keep synchronizing during a fleet rollout.
 */
public final class SyncPayloads {

    /**
     * Non-instantiable payload container.
     */
    private SyncPayloads() {}

    /**
     * A cash session, pushed at opening and again at closing (upsert by
     * session number).
     */
    public static class SessionDto {
        /** The session number (upsert key). */
        public String sessionNumber;
        /** The register identifier. */
        public String terminalId;
        /** The session status name. */
        public String status;
        /** The opening timestamp, ISO-8601. */
        public String openingDate;
        /** The closing timestamp, ISO-8601, or null. */
        public String closingDate;
        /** The login of the opening cashier. */
        public String openingCashierLogin;
        /** The login of the closing cashier, or null. */
        public String closingCashierLogin;
        /** The opening float. */
        public BigDecimal openingFloat;
        /** The counted amount, or null. */
        public BigDecimal countedAmount;
        /** The theoretical amount, or null. */
        public BigDecimal theoreticalAmount;
        /** The variance, or null. */
        public BigDecimal variance;
        /** The withdrawal, or null. */
        public BigDecimal withdrawnAmount;
        /** The denominations detail, or null. */
        public String countDetail;
    }

    /**
     * A closed or cancelled ticket with its lines and payments (upsert by
     * ticket number).
     */
    public static class TicketDto {
        /** The ticket number (upsert key). */
        public String ticketNumber;
        /** The register identifier. */
        public String terminalId;
        /** The ticket status name. */
        public String status;
        /** The creation timestamp, ISO-8601. */
        public String creationDate;
        /** The closing timestamp, ISO-8601, or null. */
        public String closingDate;
        /** The store code. */
        public String storeCode;
        /** The login of the cashier. */
        public String cashierLogin;
        /** The session number, or null. */
        public String sessionNumber;
        /** The fidelity card, or null. */
        public String fidelityCard;
        /** The digital receipt key, or null. */
        public String digitalKey;
        /** The customer email, or null. */
        public String customerEmail;
        /** The line count. */
        public int itemCount;
        /** The total excluding tax. */
        public BigDecimal totalExcludingTax;
        /** The total including tax. */
        public BigDecimal totalIncludingTax;
        /** The VAT total. */
        public BigDecimal totalVat;
        /** The chained signature, or null. */
        public String signature;
        /** The previous signature, or null. */
        public String previousSignature;
        /** The perpetual grand total snapshot, or null. */
        public BigDecimal grandTotal;
        /** The valuation status name. */
        public String valuationStatus;
        /** The ticket lines. */
        public List<LineDto> lines = new ArrayList<>();
        /** The registered payments. */
        public List<PaymentDto> payments = new ArrayList<>();
    }

    /**
     * A ticket line.
     */
    public static class LineDto {
        /** The 1-based line number. */
        public int lineNumber;
        /** The stable line uid, or null on legacy rows. */
        public String lineUid;
        /** The EAN snapshot, or null. */
        public String ean;
        /** The PLU snapshot, or null. */
        public String plu;
        /** The line label. */
        public String productLabel;
        /** The quantity. */
        public BigDecimal quantity;
        /** The unit price including tax. */
        public BigDecimal unitPrice;
        /** The VAT rate. */
        public BigDecimal vatRate;
        /** The price-modification label, or null. */
        public String modifierLabel;
        /** The catalog unit price before modification, or null. */
        public BigDecimal originalUnitPrice;
        /** The line total including tax. */
        public BigDecimal totalPrice;
        /** True for deposit-return lines. */
        public boolean deposit;
    }

    /**
     * A registered payment.
     */
    public static class PaymentDto {
        /** The 1-based registration order. */
        public int paymentIndex;
        /** The payment method key (discriminator value). */
        public String methodKey;
        /** The applied amount. */
        public BigDecimal amount;
        /** The tendered amount for cash payments, or null. */
        public BigDecimal tenderedAmount;
        /** The voucher label, or null. */
        public String voucherLabel;
        /** The voucher number, or null. */
        public String voucherNumber;
    }

    /**
     * A refund with its lines (upsert by refund number).
     */
    public static class RefundDto {
        /** The refund number (upsert key). */
        public String refundNumber;
        /** The register identifier. */
        public String terminalId;
        /** The refund status name. */
        public String status;
        /** The refund method name, or null. */
        public String refundMethod;
        /** The number of the refunded ticket. */
        public String originalTicketNumber;
        /** The session number, or null. */
        public String sessionNumber;
        /** The creation timestamp, ISO-8601. */
        public String creationDate;
        /** The refunded total including tax. */
        public BigDecimal totalAmount;
        /** The refunded total excluding tax, or null. */
        public BigDecimal totalExcludingTax;
        /** The refunded VAT total, or null. */
        public BigDecimal totalVat;
        /** The refunded lines. */
        public List<RefundLineDto> lines = new ArrayList<>();
    }

    /**
     * A technical journal event (upsert by uid).
     */
    public static class EventDto {
        /** The stable event uid (upsert key). */
        public String eventUid;
        /** The register identifier. */
        public String terminalId;
        /** The event type name. */
        public String type;
        /** The event detail, or null. */
        public String detail;
        /** The event timestamp, ISO-8601. */
        public String eventDate;
    }

    /**
     * A refunded line.
     */
    public static class RefundLineDto {
        /** The stable uid of the refunded original line, or null on legacy rows. */
        public String originalLineUid;
        /** The refunded label. */
        public String productLabel;
        /** The refunded quantity. */
        public BigDecimal quantity;
        /** The refunded unit price. */
        public BigDecimal price;
        /** The VAT rate, or null. */
        public BigDecimal vatRate;
    }
}
