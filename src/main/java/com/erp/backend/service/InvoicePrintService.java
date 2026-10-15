package com.erp.backend.service;

import com.erp.backend.dto.invoice.InvoiceLineResponse;
import com.erp.backend.dto.invoice.InvoiceResponse;
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
 * S7-01: Mẫu in hoá đơn (HTML khổ A4): in hoặc "Lưu dưới dạng PDF" từ trình duyệt (như mẫu in đơn hàng S4-08,
 * dự án chưa duyệt thêm thư viện PDF). Mọi dữ liệu người nhập đều được escape HTML. Không in giá vốn.
 */
@Service
@RequiredArgsConstructor
public class InvoicePrintService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final InvoiceService invoiceService;

    /** Kiểm quyền như xem hoá đơn. */
    @Transactional(readOnly = true)
    public String printHtml(Long id, boolean autoPrint, UserDetailsImpl actor) {
        return render(invoiceService.get(id, actor), autoPrint);
    }

    static String render(InvoiceResponse inv, boolean autoPrint) {
        StringBuilder rows = new StringBuilder();
        List<InvoiceLineResponse> lines = inv.lines() != null ? inv.lines() : List.of();
        for (InvoiceLineResponse l : lines) {
            rows.append("<tr><td class=\"c\">").append(l.lineNo()).append("</td>")
                    .append("<td>").append(esc(l.orderCode())).append("</td>")
                    .append("<td>").append(esc(l.productSku())).append("</td>")
                    .append("<td>").append(esc(l.productName())).append("</td>")
                    .append("<td class=\"c\">").append(esc(l.unitName())).append("</td>")
                    .append("<td class=\"r\">").append(qty(l.quantity())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(l.pricePerUnit())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(l.grossAmount())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(l.discountAmount())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(l.netAmount())).append("</td></tr>");
        }
        if (lines.isEmpty()) {
            rows.append("<tr><td colspan=\"10\" class=\"c\">Hoá đơn không có dòng hàng</td></tr>");
        }
        String orders = inv.orderCodes() == null ? "" : String.join(", ", inv.orderCodes());
        return "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Hoá đơn " + esc(inv.code()) + "</title><style>"
                + "@page{size:A4;margin:12mm}*{box-sizing:border-box}"
                + "body{font-family:Arial,'Segoe UI',sans-serif;font-size:13px;color:#111;margin:0;padding:16px}"
                + ".head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;border-bottom:2px solid #111;padding-bottom:10px}"
                + "h1{font-size:20px;margin:6px 0 2px}.muted{color:#555}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:14px 0}"
                + ".box{border:1px solid #bbb;border-radius:6px;padding:8px 10px}.box b{display:block;margin-bottom:4px}"
                + "table{width:100%;border-collapse:collapse;margin-top:6px}th,td{border:1px solid #999;padding:5px 6px;vertical-align:top}"
                + "th{background:#f0f0f0}.c{text-align:center}.r{text-align:right;white-space:nowrap}"
                + ".tot{width:340px;margin-left:auto;margin-top:10px}.tot td{border:none;padding:3px 6px}"
                + ".tot .big td{font-size:15px;font-weight:bold;border-top:1px solid #111}"
                + ".sign{display:grid;grid-template-columns:1fr 1fr;text-align:center;margin-top:36px}"
                + ".sign i{display:block;color:#666;font-size:12px;margin-top:2px}@media print{body{padding:0}}"
                + "</style></head><body>"
                + "<div class=\"head\"><div><div class=\"muted\">" + esc(OrderPrintService.COMPANY) + "</div>"
                + "<h1>HOÁ ĐƠN BÁN HÀNG</h1>"
                + "<div>Số: <b>" + esc(inv.code()) + "</b></div>"
                + "<div>Ngày hoá đơn: " + date(inv.invoiceDate()) + "</div></div>"
                + "<div class=\"muted\">Phát hành: " + dateTime(inv.issuedAt()) + "<br>Người lập: "
                + esc(inv.issuedByUsername()) + "</div></div>"
                + "<div class=\"grid\"><div class=\"box\"><b>Người mua (đại lý)</b>"
                + esc(inv.customerName()) + " (" + esc(inv.customerCode()) + ")<br>"
                + "Mã số thuế: " + esc(inv.customerTaxCode()) + "<br>Địa chỉ: " + esc(inv.customerAddress()) + "</div>"
                + "<div class=\"box\"><b>Kỳ giao hàng</b>" + date(inv.periodFrom()) + " – " + date(inv.periodTo())
                + "<br>Đơn hàng: " + esc(orders) + "</div></div>"
                + "<table><thead><tr><th>STT</th><th>Đơn hàng</th><th>Mã SKU</th><th>Tên hàng</th><th>ĐVT</th>"
                + "<th>SL thực giao</th><th>Đơn giá</th><th>Tiền hàng</th><th>Chiết khấu</th><th>Thành tiền</th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table>"
                + "<table class=\"tot\"><tr><td>Tổng tiền hàng</td><td class=\"r\">" + vnd(inv.subtotal()) + "</td></tr>"
                + "<tr><td>Chiết khấu</td><td class=\"r\">- " + vnd(inv.discountTotal()) + "</td></tr>"
                + "<tr class=\"big\"><td>Tổng thanh toán</td><td class=\"r\">" + vnd(inv.totalAmount()) + "</td></tr></table>"
                + (inv.note() != null && !inv.note().isBlank() ? "<p><b>Ghi chú:</b> " + esc(inv.note()) + "</p>" : "")
                + "<div class=\"sign\"><div>Người mua hàng<i>(Ký, ghi rõ họ tên)</i></div>"
                + "<div>Người bán hàng<i>(Ký, ghi rõ họ tên)</i></div></div>"
                + (autoPrint ? "<script>window.addEventListener('load',function(){window.print();});</script>" : "")
                + "</body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    private static String vnd(BigDecimal v) {
        return v == null ? "" : CustomerCreditService.vnd(v);
    }

    private static String qty(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString().replace('.', ',');
    }

    private static String date(LocalDate d) {
        return d == null ? "—" : d.format(DATE);
    }

    private static String dateTime(LocalDateTime d) {
        return d == null ? "" : d.format(DATE_TIME);
    }
}
