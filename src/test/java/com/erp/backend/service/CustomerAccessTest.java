package com.erp.backend.service;

import com.erp.backend.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerAccessTest {

    @Test
    @DisplayName("S3-06: Admin, QL kinh doanh, Kế toán thấy toàn bộ đại lý")
    void fullAccessRoles_notRestricted() {
        assertThat(CustomerAccess.restrictedSalesRepId(actor(1, "ROLE_ADMIN"))).isNull();
        assertThat(CustomerAccess.restrictedSalesRepId(actor(2, "ROLE_SALES_MANAGER"))).isNull();
        assertThat(CustomerAccess.restrictedSalesRepId(actor(3, "ROLE_ACCOUNTANT"))).isNull();
    }

    @Test
    @DisplayName("S3-06: Nhân viên kinh doanh chỉ thấy đại lý của chính mình")
    void salesRep_restrictedToSelf() {
        assertThat(CustomerAccess.restrictedSalesRepId(actor(7, "ROLE_SALES_REP"))).isEqualTo(7L);
    }

    @Test
    @DisplayName("Người vừa là NV kinh doanh vừa là Kế toán -> thấy tất cả")
    void multipleRoles_highestWins() {
        assertThat(CustomerAccess.restrictedSalesRepId(actor(7, "ROLE_SALES_REP", "ROLE_ACCOUNTANT"))).isNull();
    }

    @Test
    @DisplayName("Mặc định từ chối: Nhân viên kho không được xem đại lý")
    void otherRoles_denied() {
        assertThatThrownBy(() -> CustomerAccess.restrictedSalesRepId(actor(9, "ROLE_WAREHOUSE")))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> CustomerAccess.restrictedSalesRepId(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("NV kinh doanh mở đại lý của người khác hoặc chưa gán -> 404 (không lộ dữ liệu)")
    void salesRep_cannotAccessOthersCustomer() {
        var me = actor(7, "ROLE_SALES_REP");

        assertThatCode(() -> CustomerAccess.checkCanAccess(me, customer(1, salesRep(7)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> CustomerAccess.checkCanAccess(me, customer(2, salesRep(8))))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> CustomerAccess.checkCanAccess(me, customer(3, null)))
                .isInstanceOf(BusinessException.class);
    }
}
