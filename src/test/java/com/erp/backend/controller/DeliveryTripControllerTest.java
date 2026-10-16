package com.erp.backend.controller;

import com.erp.backend.dto.trip.*;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.DeliveryTripService;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test DeliveryTripController - S6-06 Phân tuyến giao hàng và gán tài xế")
class DeliveryTripControllerTest {

    @Mock
    private DeliveryTripService deliveryTripService;

    @InjectMocks
    private DeliveryTripController deliveryTripController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UserDetailsImpl mockUserDetails;

    @BeforeEach
    void setUp() {
        mockUserDetails = new UserDetailsImpl(
                10L, "wh_mgr", "Quản lý kho", "mgr@erp.com", "password", true, List.of()
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

        mockMvc = MockMvcBuilders.standaloneSetup(deliveryTripController)
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("GET /api/delivery-trips/candidate-orders -> trả về danh sách phiếu xuất gom chuyến theo khu vực")
    void getCandidateOrders_returnsList() throws Exception {
        DeliveryTripCandidateOrderResponse item = new DeliveryTripCandidateOrderResponse(
                101L, "DH101", 10L, "KH001", "Đại lý Hà Nội",
                1L, "Khu vực Cầu Giấy", "Quận Cầu Giấy", "Hà Nội",
                "Số 10 Duy Tân, Cầu Giấy, Hà Nội", "Anh Hoàng", "0912345678",
                new BigDecimal("5000000.00"), "DISPATCHED",
                1L, "WH-MB01", "Kho Tổng Miền Bắc",
                null, LocalDateTime.now(), "staff1"
        );

        when(deliveryTripService.getCandidateOrders(eq(1L), eq(1L), eq("Cầu Giấy"), any()))
                .thenReturn(List.of(item));

        mockMvc.perform(get("/api/delivery-trips/candidate-orders")
                        .param("warehouseId", "1")
                        .param("regionId", "1")
                        .param("search", "Cầu Giấy")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(101))
                .andExpect(jsonPath("$[0].orderCode").value("DH101"))
                .andExpect(jsonPath("$[0].customerName").value("Đại lý Hà Nội"))
                .andExpect(jsonPath("$[0].district").value("Quận Cầu Giấy"))
                .andExpect(jsonPath("$[0].receiverPhone").value("0912345678"));
    }

    @Test
    @DisplayName("GET /api/delivery-trips/{id}/manifest -> trả về bảng kê chi tiết điểm giao và SĐT người nhận")
    void getManifest_returnsManifest() throws Exception {
        DeliveryTripStopResponse stop = new DeliveryTripStopResponse(
                101L, "DH101", 10L, "KH001", "Đại lý Hà Nội",
                "Số 10 Duy Tân, Cầu Giấy, Hà Nội", "Anh Hoàng", "0912345678",
                "Quận Cầu Giấy", "Hà Nội", 1,
                "DISPATCHED", new BigDecimal("5000000.00"), new BigDecimal("5000000.00"),
                null, null, false
        );

        DeliveryTripManifestResponse manifest = new DeliveryTripManifestResponse(
                500L, "CG261016-0001",
                1L, "WH-MB01", "Kho Tổng Miền Bắc",
                "29B-88888", "Nguyễn Văn Tuấn", "0987654321",
                "IN_PROGRESS", LocalDateTime.now(), LocalDateTime.now().plusHours(4),
                1, new BigDecimal("5000000.00"), "Chuyến giao buổi sáng",
                List.of(stop), LocalDateTime.now(), "mgr"
        );

        when(deliveryTripService.getManifest(eq(500L), any())).thenReturn(manifest);

        mockMvc.perform(get("/api/delivery-trips/500/manifest")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(500))
                .andExpect(jsonPath("$.tripCode").value("CG261016-0001"))
                .andExpect(jsonPath("$.vehiclePlate").value("29B-88888"))
                .andExpect(jsonPath("$.driverName").value("Nguyễn Văn Tuấn"))
                .andExpect(jsonPath("$.driverPhone").value("0987654321"))
                .andExpect(jsonPath("$.stops[0].receiverPhone").value("0912345678"))
                .andExpect(jsonPath("$.stops[0].deliveryAddress").value("Số 10 Duy Tân, Cầu Giấy, Hà Nội"));
    }

    @Test
    @DisplayName("POST /api/delivery-trips -> tạo chuyến giao hàng mới")
    void createTrip_success() throws Exception {
        DeliveryTripCreateRequest req = DeliveryTripCreateRequest.builder()
                .orderIds(List.of(101L, 102L))
                .vehiclePlate("29H-12345")
                .driverName("Anh Tuấn")
                .expectedReturnAt(LocalDateTime.now().plusHours(4))
                .build();

        DeliveryTripSummaryResponse summary = new DeliveryTripSummaryResponse(
                500L, "CG261016-0001", 1L, "WH-MB01", "Kho Tổng Miền Bắc",
                "29H-12345", "Anh Tuấn", "0912345678", "IN_PROGRESS",
                LocalDateTime.now(), LocalDateTime.now().plusHours(4), null,
                2, 0, 0, 2, new BigDecimal("10000000.00"), new BigDecimal("10000000.00"), false, 0, null
        );
        DeliveryTripDetailResponse detail = new DeliveryTripDetailResponse(summary, List.of());

        when(deliveryTripService.create(any(), any())).thenReturn(detail);

        mockMvc.perform(post("/api/delivery-trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trip.code").value("CG261016-0001"))
                .andExpect(jsonPath("$.trip.driverName").value("Anh Tuấn"));
    }
}
