package com.erp.backend.config;

import com.erp.backend.entity.Supplier;
import com.erp.backend.repository.SupplierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierDataInitializerTest {

    @Mock private SupplierRepository supplierRepository;
    @InjectMocks private SupplierDataInitializer initializer;

    @Test
    @DisplayName("S2-09: DB chưa có nhà cung cấp -> nạp sẵn dữ liệu mẫu hợp lệ, không trùng mã / MST")
    @SuppressWarnings("unchecked")
    void seedsWhenEmpty() {
        when(supplierRepository.count()).thenReturn(0L);
        ArgumentCaptor<List<Supplier>> captor = ArgumentCaptor.forClass(List.class);

        initializer.run();

        verify(supplierRepository).saveAll(captor.capture());
        List<Supplier> saved = captor.getValue();
        assertThat(saved).hasSizeGreaterThanOrEqualTo(5);
        assertThat(new HashSet<>(saved.stream().map(Supplier::getCode).toList())).hasSize(saved.size());
        assertThat(new HashSet<>(saved.stream().map(Supplier::getTaxCode).toList())).hasSize(saved.size());
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.getCode()).matches("^[A-Za-z0-9][A-Za-z0-9_-]{1,29}$");
            assertThat(s.getTaxCode()).matches("^\\d{10}(-\\d{3})?$");
            assertThat(s.getPhone()).matches("^(0|\\+84)(3|5|7|8|9)\\d{8}$");
            assertThat(s.getStatus()).isEqualTo("ACTIVE");
        });
    }

    @Test
    @DisplayName("S2-09: Đã có nhà cung cấp -> không nạp thêm, không ghi đè")
    void skipsWhenNotEmpty() {
        when(supplierRepository.count()).thenReturn(3L);

        initializer.run();

        verify(supplierRepository, never()).saveAll(anyList());
    }
}
