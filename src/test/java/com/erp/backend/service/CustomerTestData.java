package com.erp.backend.service;

import com.erp.backend.entity.*;
import com.erp.backend.security.UserDetailsImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Dữ liệu mẫu dùng chung cho các test phần đại lý. */
final class CustomerTestData {

    private CustomerTestData() {
    }

    static UserDetailsImpl actor(long id, String... roles) {
        return new UserDetailsImpl(id, "user" + id, "Người dùng " + id, "user" + id + "@erp.com", "x", true,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    static User user(long id, String status, RoleName... roleNames) {
        Set<Role> roles = new HashSet<>();
        long roleId = 1;
        for (RoleName rn : roleNames) {
            roles.add(Role.builder().id(roleId++).name(rn).build());
        }
        return User.builder()
                .id(id)
                .username("user" + id)
                .fullName("Nhân viên " + id)
                .email("user" + id + "@erp.com")
                .password("x")
                .status(status)
                .roles(roles)
                .build();
    }

    static User salesRep(long id) {
        return user(id, "ACTIVE", RoleName.ROLE_SALES_REP);
    }

    static Region region(long id, String status) {
        return Region.builder().id(id).code("R" + id).name("Khu vực " + id).status(status).build();
    }

    static Customer customer(long id, User salesRep) {
        return Customer.builder()
                .id(id)
                .code("DL-" + id)
                .name("Đại lý " + id)
                .customerGroup(CustomerGroup.DEALER_LEVEL_1)
                .region(region(1, "ACTIVE"))
                .salesRep(salesRep)
                .status("ACTIVE")
                .build();
    }
}
