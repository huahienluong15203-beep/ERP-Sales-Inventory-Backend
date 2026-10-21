package com.erp.backend.service;

import com.erp.backend.dto.credit.CreditFlagRunResponse;
import com.erp.backend.dto.credit.FlaggedCustomerResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * S7-04: Quét cờ công nợ cho mọi đại lý đang hoạt động. Mỗi đại lý đánh giá trong transaction riêng
 * (CreditFlagService.reevaluate): 1 đại lý lỗi không làm hỏng cả lần quét. Dùng chung cho tác vụ hằng ngày và nút chạy tay.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditFlagRunner {

    private final CustomerRepository customerRepository;
    private final CreditFlagService creditFlagService;

    Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    public CreditFlagRunResponse runAll(String trigger) {
        LocalDateTime start = LocalDateTime.now(clock);
        List<Long> ids = customerRepository.findIdsByStatus("ACTIVE");
        int flagged = 0;
        int newly = 0;
        int unflagged = 0;
        int errors = 0;
        for (Long id : ids) {
            try {
                CreditFlagService.Result r = creditFlagService.reevaluate(id, trigger);
                if (r.flagged()) {
                    flagged++;
                }
                if (r.changed()) {
                    if (r.flagged()) {
                        newly++;
                    } else {
                        unflagged++;
                    }
                }
            } catch (RuntimeException e) {
                errors++;
                log.warn("S7-04: Không đánh giá được cờ công nợ đại lý id={}: {}", id, e.getMessage());
            }
        }
        log.info("S7-04: Quét cờ công nợ ({}): {} đại lý, đang bị cờ {}, gắn mới {}, gỡ {}, lỗi {}",
                trigger, ids.size(), flagged, newly, unflagged, errors);
        return new CreditFlagRunResponse(start, ids.size(), flagged, newly, unflagged, errors);
    }

    /** Danh sách đại lý đang bị gắn cờ (cũ nhất lên đầu). */
    @Transactional(readOnly = true)
    public List<FlaggedCustomerResponse> flagged() {
        return customerRepository.findCreditFlagged().stream()
                .map(CreditFlagRunner::toResponse)
                .toList();
    }

    private static FlaggedCustomerResponse toResponse(Customer c) {
        return new FlaggedCustomerResponse(c.getId(), c.getCode(), c.getName(),
                c.getSalesRep() != null ? c.getSalesRep().getFullName() : null, c.getCreditFlagReason(),
                c.getCreditFlaggedAt(), c.getCreditCheckedAt());
    }
}
