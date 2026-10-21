package com.erp.backend.dto.inventory.transfer;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockTransferResponse {

    private String id;
    private String code;
    private String sourceWarehouseCode;
    private String sourceWarehouseName;
    private String destWarehouseCode;
    private String destWarehouseName;
    private String transferDate;
    private String expectedReceiveDate;
    private String status;
    private List<StockTransferLineResponse> lines;
    private String vehiclePlate;
    private String transporterName;
    private String note;
    private String discrepancyGeneralReason;
    private String createdBy;
    private String dispatchedAt;
    private String receivedAt;
    private String createdAt;
    private String updatedAt;
}
