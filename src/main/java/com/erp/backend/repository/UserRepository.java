package com.erp.backend.repository;

import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    Boolean existsByUsername(String username);

    Boolean existsByEmail(String email);

    // S1-08: Kiểm tra trùng khi tạo / sửa tài khoản
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    // S3-06: Danh sách nhân viên kinh doanh đang hoạt động để chọn người phụ trách đại lý
    List<User> findDistinctByRoles_NameAndStatusOrderByFullNameAsc(RoleName roleName, String status);

    List<User> findDistinctByRoles_NameInAndStatusOrderByFullNameAsc(java.util.Collection<RoleName> roleNames, String status);
}
