package com.erp.backend.service;

import com.erp.backend.dto.order.OrderBulkApprovalRequest;
import com.erp.backend.dto.order.OrderBulkApprovalResponse;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Góp ý GV mục 8: Duyệt / Từ chối / Trả lại nhiều đơn cùng lúc.
 * Cố ý KHÔNG đặt @Transactional ở đây: mỗi đơn đi qua OrderApprovalService (mỗi lần gọi là 1 transaction riêng,
 * có khoá dòng, kiểm quyền, ghi lịch sử và nhật ký như duyệt từng đơn). Đơn nào lỗi chỉ đơn đó không đổi,
 * các đơn còn lại vẫn được xử lý; kết quả trả về ghi rõ từng đơn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBulkApprovalService {

    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_RETURN = "RETURN";

    private final OrderApprovalService orderApprovalService;

    public OrderBulkApprovalResponse process(OrderBulkApprovalRequest request, UserDetailsImpl actor) {
        String action = request.getAction() == null ? "" : request.getAction().trim().toUpperCase(Locale.ROOT);
        if (!Set.of(ACTION_APPROVE, ACTION_REJECT, ACTION_RETURN).contains(action)) {
            throw BusinessException.badRequest("INVALID_BULK_ACTION", "Thao tác không hợp lệ (chỉ Duyệt, Từ chối, Trả lại)");
        }
        String comment = request.getComment();
        // Từ chối / Trả lại bắt buộc có ý kiến: báo 1 lần thay vì để từng đơn lỗi giống nhau
        if (!ACTION_APPROVE.equals(action) && !StringUtils.hasText(comment)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "COMMENT_REQUIRED",
                    "Vui lòng nhập lý do khi từ chối / trả lại đơn", "comment");
        }

        // Bỏ id trùng / rỗng, giữ đúng thứ tự người dùng đã chọn
        Set<Long> ids = new LinkedHashSet<>();
        if (request.getOrderIds() != null) {
            request.getOrderIds().stream().filter(id -> id != null).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw BusinessException.badRequest("NO_ORDER_SELECTED", "Vui lòng chọn ít nhất 1 đơn hàng");
        }

        List<OrderBulkApprovalResponse.Item> results = new ArrayList<>();
        int succeeded = 0;
        for (Long id : ids) {
            try {
                OrderResponse res = switch (action) {
                    case ACTION_APPROVE -> orderApprovalService.approve(id, comment, actor);
                    case ACTION_REJECT -> orderApprovalService.reject(id, comment, actor);
                    default -> orderApprovalService.returnForEdit(id, comment, actor);
                };
                succeeded++;
                results.add(new OrderBulkApprovalResponse.Item(id, res.code(), true, res.status(), null));
            } catch (BusinessException e) {
                results.add(new OrderBulkApprovalResponse.Item(id, null, false, null, e.getMessage()));
            } catch (AccessDeniedException e) {
                results.add(new OrderBulkApprovalResponse.Item(id, null, false, null,
                        "Bạn không có quyền xử lý đơn này"));
            } catch (RuntimeException e) {
                log.warn("Xử lý hàng loạt: đơn {} lỗi không mong muốn", id, e);
                results.add(new OrderBulkApprovalResponse.Item(id, null, false, null,
                        "Không xử lý được đơn này, vui lòng thử lại"));
            }
        }
        return new OrderBulkApprovalResponse(ids.size(), succeeded, ids.size() - succeeded, results);
    }
}
