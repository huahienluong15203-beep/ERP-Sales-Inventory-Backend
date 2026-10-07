package com.erp.backend.dto.customer;

import com.erp.backend.dto.user.RefItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S3-03: Hồ sơ đại lý trả về cho Frontend.
 * S3-05: Hạn mức tiền tối đa và số ngày nợ tối đa.
 * S3-07: Trạng thái khóa giao dịch và lý do.
 * region: {id, code, name} | salesRep: {id, code = tên tài khoản, name = họ tên} (null nếu chưa gán)
 */
public record CustomerResponse(
        Long id,
        String code,
        String name,
        String taxCode,
        String customerGroup,
        String customerGroupLabel,
        RefItem region,
        RefItem salesRep,
        String contactName,
        String phone,
        String email,
        String address,
        String note,
        String status,
        String statusReason,
        BigDecimal creditLimit,
        Integer maxDebtDays,
        Boolean transactionLocked,
        String transactionLockReason,
        LocalDateTime transactionLockedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        RefItem priceList,
        Integer deliveryPointCount) {

    public CustomerResponse(
            Long id,
            String code,
            String name,
            String taxCode,
            String customerGroup,
            String customerGroupLabel,
            RefItem region,
            RefItem salesRep,
            String contactName,
            String phone,
            String email,
            String address,
            String note,
            String status,
            String statusReason,
            BigDecimal creditLimit,
            Integer maxDebtDays,
            Boolean transactionLocked,
            String transactionLockReason,
            LocalDateTime transactionLockedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            RefItem priceList) {
        this(id, code, name, taxCode, customerGroup, customerGroupLabel, region, salesRep,
                contactName, phone, email, address, note, status, statusReason, creditLimit,
                maxDebtDays, transactionLocked, transactionLockReason, transactionLockedAt,
                createdAt, updatedAt, priceList, 0);
    }
}
