package com.erp.backend.service;

import com.erp.backend.dto.order.OrderBulkApprovalRequest;
import com.erp.backend.dto.order.OrderBulkApprovalResponse;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.util.Arrays;
import java.util.List;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderBulkApprovalServiceTest {

    @Mock private OrderApprovalService orderApprovalService;
    @InjectMocks private OrderBulkApprovalService service;

    private final UserDetailsImpl manager = actor(3, "ROLE_SALES_MANAGER");

    private static OrderResponse order(Long id, String code, String status) {
        return new OrderResponse(id, code, status, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static OrderBulkApprovalRequest request(String action, String comment, Long... ids) {
        OrderBulkApprovalRequest r = new OrderBulkApprovalRequest();
        r.setAction(action);
        r.setComment(comment);
        r.setOrderIds(Arrays.asList(ids));
        return r;
    }

    @Test
    @DisplayName("Duyệt hàng loạt: đơn lỗi không làm hỏng đơn khác, trả rõ kết quả từng đơn")
    void approve_mixedResults() {
        when(orderApprovalService.approve(1L, null, manager)).thenReturn(order(1L, "DH-1", "APPROVED"));
        when(orderApprovalService.approve(2L, null, manager))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "ORDER_NOT_PENDING", "Đơn không còn chờ duyệt"));
        when(orderApprovalService.approve(3L, null, manager)).thenReturn(order(3L, "DH-3", "APPROVED"));

        OrderBulkApprovalResponse res = service.process(request("approve", null, 1L, 2L, 3L), manager);

        assertThat(res.total()).isEqualTo(3);
        assertThat(res.succeeded()).isEqualTo(2);
        assertThat(res.failed()).isEqualTo(1);
        assertThat(res.results()).extracting(OrderBulkApprovalResponse.Item::success).containsExactly(true, false, true);
        assertThat(res.results().get(1).message()).isEqualTo("Đơn không còn chờ duyệt");
        assertThat(res.results().get(0).orderCode()).isEqualTo("DH-1");
    }

    @Test
    @DisplayName("Bỏ id trùng / rỗng, mỗi đơn chỉ xử lý 1 lần")
    void duplicateIds_processedOnce() {
        when(orderApprovalService.reject(anyLong(), any(), any())).thenReturn(order(5L, "DH-5", "REJECTED"));

        OrderBulkApprovalResponse res = service.process(request("REJECT", "Sai giá", 5L, 5L, null), manager);

        assertThat(res.total()).isEqualTo(1);
        verify(orderApprovalService, times(1)).reject(5L, "Sai giá", manager);
    }

    @Test
    @DisplayName("Từ chối / Trả lại không có lý do -> báo lỗi 1 lần, không gọi đơn nào")
    void rejectWithoutComment_fails() {
        assertThatThrownBy(() -> service.process(request("RETURN", "  ", 1L, 2L), manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("COMMENT_REQUIRED");
        verifyNoInteractions(orderApprovalService);
    }

    @Test
    @DisplayName("Thao tác lạ hoặc danh sách rỗng -> 400")
    void invalidActionOrEmpty_fails() {
        assertThatThrownBy(() -> service.process(request("DELETE", null, 1L), manager))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("INVALID_BULK_ACTION");
        OrderBulkApprovalRequest empty = request("APPROVE", null);
        empty.setOrderIds(List.of());
        assertThatThrownBy(() -> service.process(empty, manager))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("NO_ORDER_SELECTED");
    }

    @Test
    @DisplayName("Không có quyền với 1 đơn -> đơn đó báo không có quyền, đơn khác vẫn chạy")
    void accessDenied_isReportedPerOrder() {
        when(orderApprovalService.returnForEdit(1L, "Sửa lại", manager)).thenThrow(new AccessDeniedException("x"));
        when(orderApprovalService.returnForEdit(2L, "Sửa lại", manager)).thenReturn(order(2L, "DH-2", "DRAFT"));

        OrderBulkApprovalResponse res = service.process(request("RETURN", "Sửa lại", 1L, 2L), manager);

        assertThat(res.succeeded()).isEqualTo(1);
        assertThat(res.results().get(0).message()).isEqualTo("Bạn không có quyền xử lý đơn này");
    }
}
