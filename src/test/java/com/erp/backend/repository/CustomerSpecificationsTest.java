package com.erp.backend.repository;

import com.erp.backend.entity.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3-07: bộ lọc "Bị khoá giao dịch" phải lọc trong câu truy vấn DB (toàn bộ đại lý), không lọc trên trang đang xem.
 */
@SuppressWarnings("unchecked")
class CustomerSpecificationsTest {

    private Root<Customer> root;
    private CriteriaQuery<Object> query;
    private CriteriaBuilder cb;
    private Path<Boolean> lockedPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        query = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        lockedPath = mock(Path.class);
        when(root.<Boolean>get("transactionLocked")).thenReturn(lockedPath);
    }

    @Test
    @DisplayName("S3-07: transactionLocked=true -> chỉ lấy đại lý đang bị khoá")
    void lockedTrue_filtersLockedOnly() {
        when(cb.isTrue(lockedPath)).thenReturn(mock(Predicate.class));

        CustomerSpecifications.search(null, null, null, null, null, true).toPredicate(root, query, cb);

        verify(cb).isTrue(lockedPath);
        verify(cb, never()).isFalse(any());
    }

    @Test
    @DisplayName("S3-07: transactionLocked=false -> đại lý đang mở (kể cả dữ liệu cũ để null)")
    void lockedFalse_includesNullAsUnlocked() {
        when(cb.isNull(lockedPath)).thenReturn(mock(Predicate.class));
        when(cb.isFalse(lockedPath)).thenReturn(mock(Predicate.class));

        CustomerSpecifications.search(null, null, null, null, null, false).toPredicate(root, query, cb);

        verify(cb).isNull(lockedPath);
        verify(cb).isFalse(lockedPath);
        verify(cb, never()).isTrue(any());
    }

    @Test
    @DisplayName("S3-07: không truyền bộ lọc khoá -> không thêm điều kiện khoá giao dịch")
    void lockedNull_noLockCondition() {
        CustomerSpecifications.search(null, null, null, null, null, null).toPredicate(root, query, cb);

        verify(root, never()).get("transactionLocked");
        verify(cb, never()).isTrue(any());
        verify(cb, never()).isFalse(any());
    }
}
