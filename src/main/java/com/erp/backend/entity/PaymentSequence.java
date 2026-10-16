package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * S7-02: Bộ đếm số phiếu thu theo năm (PT2026-000001).
 * Khoá dòng (SELECT ... FOR UPDATE) trong transaction khi cấp số để đảm bảo không nhảy số, không trùng lặp.
 */
@Entity
@Table(name = "payment_sequences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSequence {

    @Id
    @Column(name = "seq_year")
    private Integer seqYear;

    @Column(name = "last_number", nullable = false)
    private Long lastNumber;
}
