package com.erp.backend.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Chạy một việc SAU KHI transaction đã lưu thành công vào DB.
 * Dùng cho gửi email: tránh gửi mail cho dữ liệu cuối cùng bị rollback.
 * Nếu không có transaction nào đang chạy (vd: trong unit test) thì chạy ngay.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
