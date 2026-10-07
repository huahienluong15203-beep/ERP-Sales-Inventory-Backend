package com.erp.backend.service;

import com.erp.backend.dto.customer.DeliveryAddressRequest;
import com.erp.backend.dto.customer.DeliveryAddressResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.CustomerDeliveryAddress;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerDeliveryAddressRepository;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * S3-04: Nhiều điểm giao hàng cho một đại lý.
 * - Mỗi điểm có địa chỉ, người nhận, số điện thoại, ghi chú đường đi.
 * - Luôn có đúng 1 điểm mặc định (điểm đầu tiên tự thành mặc định).
 * - Không xoá cứng: ngừng sử dụng (INACTIVE). Nếu ngừng điểm mặc định thì điểm cũ nhất còn lại thành mặc định.
 * Đơn hàng (S3-09) lấy danh sách điểm giao của đúng đại lý qua API list ở đây.
 */
@Service
@RequiredArgsConstructor
public class CustomerDeliveryAddressService {

    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";

    private final CustomerRepository customerRepository;
    private final CustomerDeliveryAddressRepository addressRepository;

    @Transactional
    public List<DeliveryAddressResponse> list(Long customerId, UserDetailsImpl actor) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer);
        List<CustomerDeliveryAddress> list = activeAddresses(customerId);
        if (list.isEmpty() && StringUtils.hasText(customer.getAddress())) {
            CustomerDeliveryAddress autoDefault = CustomerDeliveryAddress.builder()
                    .customer(customer)
                    .label("Địa chỉ trụ sở / Kho chính")
                    .address(customer.getAddress().trim())
                    .receiverName(StringUtils.hasText(customer.getContactName()) ? customer.getContactName().trim() : customer.getName())
                    .receiverPhone(StringUtils.hasText(customer.getPhone()) ? customer.getPhone().trim() : "0000000000")
                    .defaultAddress(true)
                    .status(ACTIVE)
                    .build();
            addressRepository.save(autoDefault);
            list = List.of(autoDefault);
        }
        return list.stream().map(this::toResponse).toList();
    }

    @Transactional
    public DeliveryAddressResponse create(Long customerId, DeliveryAddressRequest req, UserDetailsImpl actor) {
        Customer customer = lockCustomer(customerId, actor);
        if (INACTIVE.equals(customer.getStatus())) {
            throw BusinessException.badRequest("CUSTOMER_INACTIVE",
                    "Đại lý đang ngừng giao dịch, không thêm được điểm giao hàng");
        }

        List<CustomerDeliveryAddress> existing = activeAddresses(customerId);
        boolean makeDefault = existing.isEmpty() || Boolean.TRUE.equals(req.getIsDefault());

        CustomerDeliveryAddress address = CustomerDeliveryAddress.builder()
                .customer(customer)
                .status(ACTIVE)
                .defaultAddress(false)
                .build();
        applyFields(address, req);

        if (makeDefault) {
            clearDefault(existing, null);
            address.setDefaultAddress(true);
        }
        return toResponse(addressRepository.save(address));
    }

    @Transactional
    public DeliveryAddressResponse update(Long customerId, Long addressId, DeliveryAddressRequest req, UserDetailsImpl actor) {
        lockCustomer(customerId, actor);
        CustomerDeliveryAddress address = findActiveAddress(customerId, addressId);
        applyFields(address, req);

        // Chỉ xử lý khi muốn đặt làm mặc định. Bỏ mặc định thì phải chọn điểm khác làm mặc định.
        if (Boolean.TRUE.equals(req.getIsDefault()) && !address.isDefaultAddress()) {
            clearDefault(activeAddresses(customerId), addressId);
            address.setDefaultAddress(true);
        }
        return toResponse(addressRepository.save(address));
    }

    @Transactional
    public DeliveryAddressResponse setDefault(Long customerId, Long addressId, UserDetailsImpl actor) {
        lockCustomer(customerId, actor);
        CustomerDeliveryAddress address = findActiveAddress(customerId, addressId);
        if (!address.isDefaultAddress()) {
            clearDefault(activeAddresses(customerId), addressId);
            address.setDefaultAddress(true);
            address = addressRepository.save(address);
        }
        return toResponse(address);
    }

    @Transactional
    public void deactivate(Long customerId, Long addressId, UserDetailsImpl actor) {
        lockCustomer(customerId, actor);
        CustomerDeliveryAddress address = findActiveAddress(customerId, addressId);
        boolean wasDefault = address.isDefaultAddress();

        address.setStatus(INACTIVE);
        address.setDefaultAddress(false);
        addressRepository.save(address);

        if (wasDefault) {
            // Điểm còn lại cũ nhất thành mặc định
            activeAddresses(customerId).stream()
                    .filter(a -> !a.getId().equals(addressId))
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefaultAddress(true);
                        addressRepository.save(next);
                    });
        }
    }

    // ======================= HÀM PHỤ =======================

    /** Khoá dòng đại lý để 2 người cùng đổi điểm mặc định không tạo ra 2 điểm mặc định. */
    private Customer lockCustomer(Long customerId, UserDetailsImpl actor) {
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer);
        return customer;
    }

    private CustomerDeliveryAddress findActiveAddress(Long customerId, Long addressId) {
        return addressRepository.findByIdAndCustomer_Id(addressId, customerId)
                .filter(a -> ACTIVE.equals(a.getStatus()))
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy điểm giao hàng của đại lý này"));
    }

    private List<CustomerDeliveryAddress> activeAddresses(Long customerId) {
        return addressRepository.findByCustomer_IdAndStatusOrderByDefaultAddressDescIdAsc(customerId, ACTIVE);
    }

    private void clearDefault(List<CustomerDeliveryAddress> addresses, Long exceptId) {
        for (CustomerDeliveryAddress a : addresses) {
            if (a.isDefaultAddress() && (exceptId == null || !exceptId.equals(a.getId()))) {
                a.setDefaultAddress(false);
                addressRepository.save(a);
            }
        }
    }

    private void applyFields(CustomerDeliveryAddress address, DeliveryAddressRequest req) {
        address.setLabel(blankToNull(req.getLabel()));
        address.setAddress(req.getAddress().trim());
        address.setReceiverName(req.getReceiverName().trim());
        address.setReceiverPhone(req.getReceiverPhone().trim());
        address.setNote(blankToNull(req.getNote()));
    }

    private DeliveryAddressResponse toResponse(CustomerDeliveryAddress a) {
        return new DeliveryAddressResponse(
                a.getId(),
                a.getCustomer() == null ? null : a.getCustomer().getId(),
                a.getLabel(),
                a.getAddress(),
                a.getReceiverName(),
                a.getReceiverPhone(),
                a.getNote(),
                a.isDefaultAddress(),
                a.getStatus(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
