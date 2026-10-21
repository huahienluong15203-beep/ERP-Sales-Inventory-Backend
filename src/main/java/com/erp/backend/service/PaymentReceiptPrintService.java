package com.erp.backend.service;

import com.erp.backend.dto.payment.PaymentAllocationResponse;
import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.entity.PaymentReceipt;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * S7-09: Mẫu in phiếu thu (HTML khổ A5 ngang, in 2 liên): in hoặc "Lưu dưới dạng PDF" từ trình duyệt
 * (giống mẫu in hoá đơn S7-01, dự án chưa duyệt thêm thư viện PDF).
 * - Có mã phiếu thu duy nhất, thông tin đại lý, số tiền (số và chữ), hình thức, người thu, các hoá đơn được đối trừ.
 * - Phiếu chưa xác nhận / bị từ chối / đã huỷ vẫn in được để đối chiếu nhưng có dấu "KHÔNG CÓ GIÁ TRỊ".
 * - Mọi dữ liệu người nhập đều được escape HTML.
 */
@Service
@RequiredArgsConstructor
public class PaymentReceiptPrintService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final PaymentService paymentService;

    /** Kiểm quyền như xem phiếu thu (đại lý chỉ in được phiếu của mình, NV kinh doanh chỉ đại lý mình phụ trách). */
    @Transactional(readOnly = true)
    public String printHtml(Long id, boolean autoPrint, UserDetailsImpl actor) {
        return render(paymentService.getReceipt(id, actor), autoPrint);
    }

    static String render(PaymentReceiptResponse r, boolean autoPrint) {
        String invalid = invalidLabel(r.status());
        StringBuilder rows = new StringBuilder();
        List<PaymentAllocationResponse> allocations = r.allocations() != null ? r.allocations() : List.of();
        int no = 1;
        for (PaymentAllocationResponse a : allocations) {
            rows.append("<tr><td class=\"c\">").append(no++).append("</td>")
                    .append("<td>").append(esc(a.invoiceCode())).append("</td>")
                    .append("<td>").append(esc(a.orderCode())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(a.invoiceTotal())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(a.remainingBefore())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(a.allocatedAmount())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(a.remainingAfter())).append("</td></tr>");
        }
        String allocTable = allocations.isEmpty()
                ? "<p class=\"muted\">Chưa đối trừ vào hoá đơn nào.</p>"
                : "<table><thead><tr><th>STT</th><th>Hoá đơn</th><th>Đơn hàng</th><th>Tổng hoá đơn</th>"
                + "<th>Còn nợ trước</th><th>Đối trừ</th><th>Còn nợ sau</th></tr></thead><tbody>" + rows + "</tbody></table>";

        String body = "<div class=\"head\"><div><div class=\"muted\">" + esc(OrderPrintService.COMPANY) + "</div>"
                + "<h1>PHIẾU THU</h1>"
                + "<div>Số: <b>" + esc(r.code()) + "</b></div>"
                + "<div>Ngày thu: " + date(r.paymentDate()) + "</div></div>"
                + "<div class=\"muted\">Trạng thái: " + esc(statusLabel(r.status())) + "<br>Lập lúc: "
                + dateTime(r.createdAt()) + "<br>Người lập: " + esc(r.createdByUsername()) + "</div></div>"
                + "<div class=\"box\"><b>Người nộp tiền:</b> "
                + (notBlank(r.payerName()) && !r.payerName().equalsIgnoreCase(r.customerName())
                        ? "<b>" + esc(r.payerName()) + "</b> <i>(Bàn giao thay cho đại lý: " + esc(r.customerName()) + " - " + esc(r.customerCode()) + ")</i><br>"
                        : esc(r.customerName()) + " (" + esc(r.customerCode()) + ")<br>")
                + "Địa chỉ: " + esc(r.customerAddress()) + "<br>Điện thoại: " + esc(r.customerPhone()) + "</div>"
                + "<table class=\"info\">"
                + "<tr><td>Số tiền</td><td class=\"big\">" + vnd(r.amount()) + "</td></tr>"
                + "<tr><td>Bằng chữ</td><td><i>" + esc(MoneyWords.vnd(r.amount())) + "</i></td></tr>"
                + "<tr><td>Hình thức thu</td><td>" + esc(methodLabel(r.paymentMethod())) + "</td></tr>"
                + "<tr><td>Người thu</td><td>" + esc(r.collectorName())
                + (r.collectorUsername() != null ? " (" + esc(r.collectorUsername()) + ")" : "") + "</td></tr>"
                + (r.confirmedAt() != null ? "<tr><td>Kế toán xác nhận</td><td>" + esc(r.confirmedByUsername())
                + " lúc " + dateTime(r.confirmedAt()) + "</td></tr>" : "")
                + (notBlank(r.notes()) ? "<tr><td>Ghi chú</td><td>" + esc(r.notes()) + "</td></tr>" : "")
                + (notBlank(r.rejectionReason()) ? "<tr><td>Lý do từ chối</td><td>" + esc(r.rejectionReason()) + "</td></tr>" : "")
                + "</table>"
                + "<h2>Đối trừ công nợ</h2>" + allocTable
                + "<div class=\"sign\"><div>Người nộp tiền<i>(Ký, ghi rõ họ tên)</i></div>"
                + "<div>Người thu tiền<i>(Ký, ghi rõ họ tên)</i></div>"
                + "<div>Kế toán<i>(Ký, ghi rõ họ tên)</i></div></div>";

        // In 2 liên: liên 1 lưu, liên 2 giao đại lý
        String copies = "<section class=\"copy\">" + mark(invalid) + "<div class=\"lien\">Liên 1: Lưu</div>" + body + "</section>"
                + "<section class=\"copy\">" + mark(invalid) + "<div class=\"lien\">Liên 2: Giao đại lý</div>" + body + "</section>";

        return "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Phiếu thu " + esc(r.code()) + "</title><style>"
                + "@page{size:A5 landscape;margin:8mm}*{box-sizing:border-box}"
                + "body{font-family:Arial,'Segoe UI',sans-serif;font-size:13px;color:#111;margin:0;padding:12px}"
                + ".copy{position:relative;max-width:900px;margin:0 auto 24px}"
                + ".copy+.copy{page-break-before:always;break-before:page}"
                + ".lien{text-align:right;font-size:11px;color:#555}"
                + ".head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;border-bottom:2px solid #111;padding-bottom:8px}"
                + "h1{font-size:20px;margin:4px 0 2px}h2{font-size:14px;margin:14px 0 4px}.muted{color:#555}"
                + ".box{border:1px solid #bbb;border-radius:6px;padding:8px 10px;margin:10px 0}.box b{display:block;margin-bottom:4px}"
                + "table{width:100%;border-collapse:collapse}th,td{border:1px solid #999;padding:5px 6px;vertical-align:top}"
                + "th{background:#f0f0f0}.c{text-align:center}.r{text-align:right;white-space:nowrap}"
                + ".info td{border:none;padding:3px 6px}.info td:first-child{width:150px;color:#444}"
                + ".info .big{font-size:16px;font-weight:bold}"
                + ".sign{display:grid;grid-template-columns:1fr 1fr 1fr;text-align:center;margin-top:28px;gap:8px}"
                + ".sign i{display:block;color:#666;font-size:12px;margin-top:2px}"
                + ".void{position:absolute;top:38%;left:0;right:0;text-align:center;font-size:30px;font-weight:bold;"
                + "color:rgba(200,0,0,.25);transform:rotate(-12deg);pointer-events:none}"
                + "@media (max-width:600px){.head{flex-direction:column}.sign{grid-template-columns:1fr}"
                + "table{font-size:12px}.info td:first-child{width:110px}}"
                + "@media print{body{padding:0}}"
                + "</style></head><body>" + copies
                + (autoPrint ? "<script>window.addEventListener('load',function(){window.print();});</script>" : "")
                + "</body></html>";
    }

    /** null = phiếu có giá trị (đã xác nhận). */
    static String invalidLabel(String status) {
        if (status == null || PaymentReceipt.STATUS_CONFIRMED.equals(status)) {
            return null;
        }
        return switch (status) {
            case PaymentReceipt.STATUS_PENDING_SUBMISSION -> "CHỜ NỘP QUỸ – CHƯA CÓ GIÁ TRỊ";
            case PaymentReceipt.STATUS_REJECTED -> "ĐÃ TỪ CHỐI – KHÔNG CÓ GIÁ TRỊ";
            case PaymentReceipt.STATUS_VOIDED -> "ĐÃ HUỶ – KHÔNG CÓ GIÁ TRỊ";
            default -> "KHÔNG CÓ GIÁ TRỊ";
        };
    }

    static String methodLabel(String method) {
        if (method == null) {
            return "";
        }
        return switch (method) {
            case PaymentReceipt.METHOD_CASH -> "Tiền mặt";
            case PaymentReceipt.METHOD_BANK_TRANSFER -> "Chuyển khoản";
            case PaymentReceipt.METHOD_OTHER -> "Khác";
            default -> method;
        };
    }

    private static String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PaymentReceipt.STATUS_CONFIRMED -> "Đã xác nhận";
            case PaymentReceipt.STATUS_PENDING_SUBMISSION -> "Chờ nộp quỹ";
            case PaymentReceipt.STATUS_REJECTED -> "Đã từ chối";
            case PaymentReceipt.STATUS_VOIDED -> "Đã huỷ";
            default -> status;
        };
    }

    private static String mark(String invalid) {
        return invalid == null ? "" : "<div class=\"void\">" + esc(invalid) + "</div>";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    private static String vnd(BigDecimal v) {
        return v == null ? "" : CustomerCreditService.vnd(v);
    }

    private static String date(LocalDate d) {
        return d == null ? "—" : d.format(DATE);
    }

    private static String dateTime(LocalDateTime d) {
        return d == null ? "" : d.format(DATE_TIME);
    }
}
