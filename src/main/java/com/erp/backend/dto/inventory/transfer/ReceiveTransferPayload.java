package com.erp.backend.dto.inventory.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiveTransferPayload {

    private String transferId;

    @NotEmpty(message = "Danh sách dòng hàng nhận không được để trống")
    @Valid
    private List<ReceiveTransferLineItem> receivedLines;

    private String generalReason;
}
