package com.intermarche.pos.domain.ticket;

import com.intermarche.pos.domain.BaseEntity;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "tickets",
        indexes = {
                @Index(name = "idx_ticket_number", columnList = "ticket_number", unique = true),
                @Index(name = "idx_ticket_date", columnList = "creation_date"),
                @Index(name = "idx_ticket_cashier", columnList = "cashier_id")
        }
)
public class Ticket extends BaseEntity {

    // --------------------------------------------------
    // Statut du Ticket
    // --------------------------------------------------

    public enum TicketStatus {
        OPEN,   // En cours de paiement (draft)
        CLOSED  // Terminé et validé
    }

    @Column(name = "status", nullable = false)
    @NotNull
    public TicketStatus status = TicketStatus.OPEN; // Par défaut OPEN

    // --------------------------------------------------
    // Identité & Date
    // --------------------------------------------------

    @Column(name = "ticket_number", unique = true, nullable = false, length = 30)
    @NotBlank
    public String ticketNumber;

    @Column(name = "creation_date", nullable = false)
    @NotNull
    public LocalDateTime creationDate;

    // --------------------------------------------------
    // Relations (Unidirectionnelles)
    // --------------------------------------------------

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    @NotNull
    public Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id", nullable = false)
    @NotNull
    public Employee cashier;

    @Column(name = "fidelity_card", length = 50)
    public String fidelityCard;

    // Collections mappées avec @JoinColumn (FK dans la table enfant)
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ticket_id", nullable = false)
    public List<TicketLine> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ticket_id", nullable = false)
    public List<TicketPayment> payments = new ArrayList<>();

    // --------------------------------------------------
    // Totaux Financiers
    // --------------------------------------------------

    @Column(name = "item_count", nullable = false)
    public int itemCount;

    @Column(name = "total_ht", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalExcludingTax;

    @Column(name = "total_ttc", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalIncludingTax;

    @Column(name = "total_vat", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalVat;

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    public void addLine(TicketLine line) {
        this.lines.add(line);
    }

    public void addPayment(TicketPayment payment) {
        this.payments.add(payment);
    }

    /**
     * Retourne le total TTC formaté pour l'affichage (arrondi à 2 décimales, virgule).
     * Sécurité : Le formatage est fait côté serveur.
     */
    public String getTotalFormatted() {
        if (this.totalIncludingTax == null) return "0,00";
        // Arrondi mathématique à 2 décimales (HALF_UP)
        BigDecimal rounded = this.totalIncludingTax.setScale(2, RoundingMode.HALF_UP);
        // Formatage avec virgule française
        return String.format("%.2f", rounded).replace(".", ",");
    }

    @Override
    public int getChecksum() {
        return Objects.hash(ticketNumber, creationDate, status, totalIncludingTax);
    }
}