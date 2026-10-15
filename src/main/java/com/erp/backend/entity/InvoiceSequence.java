package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * S7-01: Bộ đếm số hoá đơn theo năm. Khoá dòng (SELECT ... FOR UPDATE) khi cấp số, cấp trong cùng transaction
 * với việc lưu hoá đơn: lỗi giữa chừng thì số cũng được hoàn lại -> không trùng, không nhảy số.
 */
@Entity
@Table(name = "invoice_sequences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceSequence {

    @Id
    @Column(name = "seq_year")
    private Integer seqYear;

    @Column(name = "last_number", nullable = false)
    private Long lastNumber;
}
