package com.erp.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * S7-09: Đọc số tiền VND thành chữ cho phiếu thu, vd 1.050.000 -> "Một triệu không trăm năm mươi nghìn đồng".
 * Tiền VND không có số lẻ nên làm tròn tới đồng (HALF_UP).
 */
final class MoneyWords {

    private static final String[] DIGITS = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};
    private static final String[] SCALES = {"", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ", "tỷ tỷ"};

    private MoneyWords() {
    }

    static String vnd(BigDecimal amount) {
        BigDecimal v = (amount == null ? BigDecimal.ZERO : amount).setScale(0, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        String digits = v.abs().toPlainString();
        if (digits.length() > SCALES.length * 3) {
            throw new IllegalArgumentException("Số tiền quá lớn để đọc thành chữ");
        }
        String words = read(digits);
        String text = (negative ? "âm " : "") + words + " đồng";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String read(String digits) {
        List<Integer> groups = new ArrayList<>(); // nhóm 3 chữ số, từ hàng đơn vị lên
        for (int end = digits.length(); end > 0; end -= 3) {
            groups.add(Integer.parseInt(digits.substring(Math.max(0, end - 3), end)));
        }
        int top = groups.size() - 1;
        while (top > 0 && groups.get(top) == 0) {
            top--;
        }
        if (top == 0 && groups.get(0) == 0) {
            return DIGITS[0];
        }
        List<String> parts = new ArrayList<>();
        for (int i = top; i >= 0; i--) {
            int g = groups.get(i);
            if (g == 0) {
                continue;
            }
            // Nhóm đứng đầu đọc gọn (5 -> "năm"); nhóm sau đọc đủ 3 chữ số (5 -> "không trăm linh năm")
            parts.add(group(g, i != top));
            if (!SCALES[i].isEmpty()) {
                parts.add(SCALES[i]);
            }
        }
        return String.join(" ", parts);
    }

    private static String group(int g, boolean full) {
        int h = g / 100;
        int t = (g / 10) % 10;
        int u = g % 10;
        List<String> w = new ArrayList<>();
        if (full || h > 0) {
            w.add(DIGITS[h]);
            w.add("trăm");
        }
        if (t == 0) {
            if (u > 0) {
                if (!w.isEmpty()) {
                    w.add("linh");
                }
                w.add(DIGITS[u]);
            }
        } else if (t == 1) {
            w.add("mười");
            if (u == 5) {
                w.add("lăm");
            } else if (u > 0) {
                w.add(DIGITS[u]);
            }
        } else {
            w.add(DIGITS[t]);
            w.add("mươi");
            if (u == 1) {
                w.add("mốt");
            } else if (u == 5) {
                w.add("lăm");
            } else if (u > 0) {
                w.add(DIGITS[u]);
            }
        }
        return String.join(" ", w);
    }
}
