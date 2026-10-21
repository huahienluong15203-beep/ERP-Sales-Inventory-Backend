package com.erp.backend.dto.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** S7-01 & S7-02: Hoá đơn. id / code = null khi chỉ xem trước (chưa phát hành). */
public record InvoiceResponse(
        Long id,
        String code,
        LocalDate invoiceDate,
        String status,
        Long customerId,
        String customerCode,
        String customerName,
        String customerTaxCode,
        String customerAddress,
        LocalDate periodFrom,
        LocalDate periodTo,
        List<String> orderCodes,
        List<InvoiceLineResponse> lines,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal remainingAmount,
        String paymentStatus,
        String note,
        LocalDateTime issuedAt,
        String issuedByUsername) {

    public InvoiceResponse(
            Long id,
            String code,
            LocalDate invoiceDate,
            String status,
            Long customerId,
            String customerCode,
            String customerName,
            String customerTaxCode,
            String customerAddress,
            LocalDate periodFrom,
            LocalDate periodTo,
            List<String> orderCodes,
            List<InvoiceLineResponse> lines,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal totalAmount,
            String note,
            LocalDateTime issuedAt,
            String issuedByUsername) {
        this(id, code, invoiceDate, status, customerId, customerCode, customerName, customerTaxCode, customerAddress,
                periodFrom, periodTo, orderCodes, lines, subtotal, discountTotal, totalAmount,
                BigDecimal.ZERO, totalAmount != null ? totalAmount : BigDecimal.ZERO, "UNPAID",
                note, issuedAt, issuedByUsername);
    }
}
