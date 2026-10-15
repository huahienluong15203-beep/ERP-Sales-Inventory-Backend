package com.erp.backend.controller;

import com.erp.backend.dto.inventory.picking.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.exception.GlobalExceptionHandler;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.PickingListService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test PickingListController - S6-03 Gom đơn, gộp mặt hàng, sắp xếp vị trí kho")
class PickingListControllerTest {

    @Mock
    private PickingListService pickingListService;

    @InjectMocks
    private PickingListController pickingListController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UserDetailsImpl mockUserDetails;

    @BeforeEach
    void setUp() {
        mockUserDetails = new UserDetailsImpl(
                10L, "wh_staff", "Nhân viên kho", "staff@erp.com", "password", true, List.of()
        );

        HandlerMethodArgumentResolver authPrincipalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return mockUserDetails;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(pickingListController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("GET /api/inventory/picking-lists/available-orders - Lấy đơn sẵn sàng gom soạn -> 200 OK")
    void getAvailableOrders_returnsOk() throws Exception {
        AvailablePickingOrderResponse order = AvailablePickingOrderResponse.builder()
                .orderId(1L)
                .orderCode("SO-101")
                .customerName("Đại lý Minh Phát")
                .totalLines(3)
                .totalAmount(new BigDecimal("2500000"))
                .status("APPROVED")
                .build();

        when(pickingListService.getAvailableOrdersForPicking(eq(1L), any())).thenReturn(List.of(order));

        mockMvc.perform(get("/api/inventory/picking-lists/available-orders")
                        .param("warehouseId", "1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderCode").value("SO-101"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
    }

    @Test
    @DisplayName("POST /api/inventory/picking-lists/preview - Xem trước gộp mặt hàng và vị trí -> 200 OK")
    void previewPickingList_returnsOk() throws Exception {
        PickingListPreviewRequest req = PickingListPreviewRequest.builder()
                .warehouseId(1L)
                .orderIds(List.of(101L, 102L))
                .build();

        PickingListItemResponse item = PickingListItemResponse.builder()
                .productId(20L)
                .productSku("SP-PEPSI")
                .productName("Pepsi 330ml")
                .locationCode("BIN-A1-01")
                .routeSequence(1)
                .requestedQuantity(new BigDecimal("30"))
                .build();

        PickingListPreviewResponse preview = PickingListPreviewResponse.builder()
                .warehouseId(1L)
                .totalOrders(2)
                .totalItems(1)
                .totalQuantity(new BigDecimal("30"))
                .items(List.of(item))
                .build();

        when(pickingListService.previewPickingList(any(), any())).thenReturn(preview);

        mockMvc.perform(post("/api/inventory/picking-lists/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(2))
                .andExpect(jsonPath("$.items[0].locationCode").value("BIN-A1-01"))
                .andExpect(jsonPath("$.items[0].routeSequence").value(1));
    }

    @Test
    @DisplayName("POST /api/inventory/picking-lists - Tạo phiếu soạn hàng hợp lệ -> 201 Created")
    void createPickingList_returnsCreated() throws Exception {
        PickingListCreateRequest req = PickingListCreateRequest.builder()
                .warehouseId(1L)
                .orderIds(List.of(101L, 102L))
                .note("Đợt soạn buổi sáng")
                .build();

        PickingListResponse response = PickingListResponse.builder()
                .id(1L)
                .code("PKL-202610-0001")
                .status("ASSIGNED")
                .totalOrders(2)
                .totalItems(5)
                .totalRequestedQuantity(new BigDecimal("50"))
                .build();

        when(pickingListService.createPickingList(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/inventory/picking-lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PKL-202610-0001"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    @Test
    @DisplayName("POST /api/inventory/picking-lists - Thiếu danh sách đơn hàng -> 400 Bad Request")
    void createPickingList_missingOrders_returnsBadRequest() throws Exception {
        PickingListCreateRequest req = PickingListCreateRequest.builder()
                .warehouseId(1L)
                .orderIds(List.of()) // Rỗng
                .build();

        mockMvc.perform(post("/api/inventory/picking-lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/inventory/picking-lists - Tra cứu danh sách phiếu soạn hàng -> 200 OK")
    void searchPickingLists_returnsOk() throws Exception {
        PickingListResponse pl = PickingListResponse.builder()
                .id(1L)
                .code("PKL-202610-0001")
                .status("IN_PROGRESS")
                .build();

        PageResponse<PickingListResponse> page = new PageResponse<>(List.of(pl), 0, 20, 1, 1);
        when(pickingListService.searchPickingLists(any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/inventory/picking-lists")
                        .param("warehouseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value("PKL-202610-0001"));
    }

    @Test
    @DisplayName("GET /api/inventory/picking-lists/{id} - Xem chi tiết phiếu soạn hàng -> 200 OK")
    void getPickingListById_returnsOk() throws Exception {
        PickingListResponse response = PickingListResponse.builder()
                .id(1L)
                .code("PKL-202610-0001")
                .status("ASSIGNED")
                .build();

        when(pickingListService.getPickingListById(eq(1L), any())).thenReturn(response);

        mockMvc.perform(get("/api/inventory/picking-lists/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("PKL-202610-0001"));
    }

    @Test
    @DisplayName("PATCH /api/inventory/picking-lists/{id}/items/{itemId} - Cập nhật tiến độ dòng trên Mobile -> 200 OK")
    void updateItemProgress_returnsOk() throws Exception {
        PickingItemUpdateRequest req = PickingItemUpdateRequest.builder()
                .pickedQuantity(new BigDecimal("20"))
                .isPicked(true)
                .build();

        PickingListResponse response = PickingListResponse.builder()
                .id(1L)
                .status("IN_PROGRESS")
                .totalPickedQuantity(new BigDecimal("20"))
                .build();

        when(pickingListService.updateItemProgress(eq(1L), eq(10L), any(), any())).thenReturn(response);

        mockMvc.perform(patch("/api/inventory/picking-lists/1/items/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("POST /api/inventory/picking-lists/{id}/complete - Chốt hoàn thành phiếu -> 200 OK")
    void completePickingList_returnsOk() throws Exception {
        PickingListResponse response = PickingListResponse.builder()
                .id(1L)
                .status("COMPLETED")
                .build();

        when(pickingListService.completePickingList(eq(1L), any())).thenReturn(response);

        mockMvc.perform(post("/api/inventory/picking-lists/1/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /api/inventory/picking-lists/{id}/cancel - Hủy phiếu kèm lý do -> 200 OK")
    void cancelPickingList_returnsOk() throws Exception {
        PickingListResponse response = PickingListResponse.builder()
                .id(1L)
                .status("CANCELLED")
                .cancelReason("Khách hàng hủy đơn")
                .build();

        when(pickingListService.cancelPickingList(eq(1L), eq("Khách hàng hủy đơn"), any())).thenReturn(response);

        mockMvc.perform(post("/api/inventory/picking-lists/1/cancel")
                        .param("reason", "Khách hàng hủy đơn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
