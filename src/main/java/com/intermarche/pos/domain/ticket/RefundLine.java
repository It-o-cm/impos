package com.intermarche.pos.domain.ticket;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "refund_lines")
public class RefundLine extends BaseEntity {

    @Column(name = "original_line_id", nullable = false)
    public Long originalLineId;

    @Column(name = "product_label", nullable = false)
    public String productLabel;

    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    public BigDecimal quantity;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    public BigDecimal price;

    @Override
    public int getChecksum() {
        return 0;
    }
}