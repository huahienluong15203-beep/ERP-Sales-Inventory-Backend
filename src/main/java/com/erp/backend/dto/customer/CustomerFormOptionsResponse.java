package com.erp.backend.dto.customer;

import com.erp.backend.dto.user.RefItem;

import java.util.List;

/** Dữ liệu cho các ô chọn trên form / bộ lọc đại lý. */
public record CustomerFormOptionsResponse(
        List<OptionItem> customerGroups,
        List<OptionItem> statuses,
        List<RefItem> regions,
        List<RefItem> salesReps,
        List<PriceListOptionResponse> priceLists,
        List<RefItem> warehouses) {

    public CustomerFormOptionsResponse(
            List<OptionItem> customerGroups,
            List<OptionItem> statuses,
            List<RefItem> regions,
            List<RefItem> salesReps,
            List<PriceListOptionResponse> priceLists) {
        this(customerGroups, statuses, regions, salesReps, priceLists, List.of());
    }
}
