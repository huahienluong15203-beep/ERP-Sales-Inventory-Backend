package com.erp.backend.service;

import com.erp.backend.dto.supplier.ChangeSupplierStatusRequest;
import com.erp.backend.dto.supplier.CreateSupplierRequest;
import com.erp.backend.dto.supplier.SupplierProfileRequest;
import com.erp.backend.dto.supplier.SupplierResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Supplier;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SupplierRepository;
import com.erp.backend.repository.SupplierSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * S2-09: Quản lý danh mục nhà cung cấp.
 * - Mã và mã số thuế không trùng. Mã không đổi sau khi tạo.
 * - Nhà cung cấp đã có phiếu nhập thì không xoá được, chỉ ngừng giao dịch.
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";

    private final SupplierRepository supplierRepository;

    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> search(String keyword, String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Page<Supplier> result = supplierRepository.findAll(
                SupplierSpecifications.search(keyword, status),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id").descending())));
        return PageResponse.of(result.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public SupplierResponse getById(Long id) {
        return toResponse(findSupplier(id));
    }

    @Transactional
    public SupplierResponse create(CreateSupplierRequest req) {
        String code = req.getCode().trim().toUpperCase();
        String taxCode = req.getTaxCode().trim();

        if (supplierRepository.existsByCodeIgnoreCase(code)) {
            throw BusinessException.conflict("SUPPLIER_CODE_EXISTS", "Mã nhà cung cấp '" + code + "' đã tồn tại", "code");
        }
        if (supplierRepository.existsByTaxCode(taxCode)) {
            throw BusinessException.conflict("TAX_CODE_EXISTS",
                    "Mã số thuế '" + taxCode + "' đã được dùng cho nhà cung cấp khác", "taxCode");
        }

        Supplier supplier = Supplier.builder().code(code).status(ACTIVE).build();
        applyProfile(supplier, req, taxCode);
        return toResponse(supplierRepository.save(supplier));
    }

    /** Sửa thông tin. Không đổi được mã nhà cung cấp. */
    @Transactional
    public SupplierResponse update(Long id, SupplierProfileRequest req) {
        Supplier supplier = findSupplier(id);
        String taxCode = req.getTaxCode().trim();

        if (supplierRepository.existsByTaxCodeAndIdNot(taxCode, id)) {
            throw BusinessException.conflict("TAX_CODE_EXISTS",
                    "Mã số thuế '" + taxCode + "' đã được dùng cho nhà cung cấp khác", "taxCode");
        }

        applyProfile(supplier, req, taxCode);
        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierResponse changeStatus(Long id, ChangeSupplierStatusRequest req) {
        Supplier supplier = findSupplier(id);
        String newStatus = req.getStatus().trim().toUpperCase();
        String reason = blankToNull(req.getReason());

        if (INACTIVE.equals(newStatus) && reason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Vui lòng nhập lý do ngừng giao dịch", "reason");
        }
        if (newStatus.equals(supplier.getStatus())) {
            throw BusinessException.badRequest("STATUS_UNCHANGED",
                    INACTIVE.equals(newStatus) ? "Nhà cung cấp đã ở trạng thái ngừng giao dịch" : "Nhà cung cấp đang giao dịch");
        }

        supplier.setStatus(newStatus);
        supplier.setStatusReason(INACTIVE.equals(newStatus) ? reason : null);
        return toResponse(supplierRepository.save(supplier));
    }

    /**
     * Xoá nhà cung cấp chưa phát sinh giao dịch.
     * Phiếu nhập kho chưa có (làm ở Sprint 5). Khi có, phải chặn xoá nếu nhà cung cấp đã có phiếu nhập
     * (trả 409 SUPPLIER_IN_USE, gợi ý chuyển sang ngừng giao dịch).
     */
    @Transactional
    public void delete(Long id) {
        Supplier supplier = findSupplier(id);
        supplierRepository.delete(supplier);
    }

    // ======================= HÀM PHỤ =======================

    private Supplier findSupplier(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy nhà cung cấp"));
    }

    private void applyProfile(Supplier supplier, SupplierProfileRequest req, String taxCode) {
        supplier.setName(req.getName().trim());
        supplier.setTaxCode(taxCode);
        supplier.setContactName(blankToNull(req.getContactName()));
        supplier.setPhone(blankToNull(req.getPhone()));
        supplier.setEmail(blankToNull(req.getEmail()));
        supplier.setAddress(blankToNull(req.getAddress()));
        supplier.setPaymentTerms(blankToNull(req.getPaymentTerms()));
        supplier.setNote(blankToNull(req.getNote()));
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    SupplierResponse toResponse(Supplier s) {
        return new SupplierResponse(s.getId(), s.getCode(), s.getName(), s.getTaxCode(), s.getContactName(),
                s.getPhone(), s.getEmail(), s.getAddress(), s.getPaymentTerms(), s.getNote(), s.getStatus(),
                s.getStatusReason(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
