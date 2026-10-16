package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/** S8-02: Bộ đếm số chứng từ giảm trừ theo năm (GG2026-000001), khoá dòng khi cấp số. */
@Entity
@Table(name = "credit_note_sequences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditNoteSequence {

    @Id
    @Column(name = "seq_year")
    private Integer seqYear;

    @Column(name = "last_number", nullable = false)
    private Long lastNumber;
}
