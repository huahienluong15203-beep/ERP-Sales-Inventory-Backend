package com.erp.backend.service;

import com.erp.backend.dto.order.OrderLineResponse;
import com.erp.backend.dto.order.OrderResponse;
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
 * S4-08: Mẫu in / xuất PDF đơn hàng.
 * Trang HTML khổ A4 gồm thông tin đại lý, điểm giao, chi tiết dòng hàng, chiết khấu, tổng tiền,
 * mã đơn và mã vạch Code 128 để kho quét tra cứu nhanh. Người dùng bấm In hoặc "Lưu dưới dạng PDF" của trình duyệt
 * (dự án chưa duyệt thêm thư viện PDF). Không in giá sàn, giá vốn. Mọi dữ liệu người nhập đều được escape HTML.
 */
@Service
@RequiredArgsConstructor
public class OrderPrintService {

    static final String COMPANY = "NHÀ PHÂN PHỐI ERP";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final OrderDraftService orderDraftService;

    /** Kiểm quyền như xem đơn (NV kinh doanh chỉ in đơn của đại lý mình phụ trách, khác -> 404). */
    @Transactional(readOnly = true)
    public String printHtml(Long orderId, boolean autoPrint, UserDetailsImpl actor) {
        return render(orderDraftService.getById(orderId, actor), autoPrint);
    }

    static String render(OrderResponse o, boolean autoPrint) {
        StringBuilder rows = new StringBuilder();
        List<OrderLineResponse> lines = o.lines() != null ? o.lines() : List.of();
        int stt = 1;
        for (OrderLineResponse l : lines) {
            rows.append("<tr><td class=\"c\">").append(stt++).append("</td>")
                    .append("<td>").append(esc(l.productSku())).append("</td>")
                    .append("<td>").append(esc(l.productName())).append("</td>")
                    .append("<td class=\"c\">").append(esc(l.unitName())).append("</td>")
                    .append("<td class=\"r\">").append(qty(l.quantity())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(l.pricePerUnit())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(l.discountAmount())).append("</td>")
                    .append("<td class=\"r\">").append(vnd(l.netAmount())).append("</td></tr>");
        }
        if (lines.isEmpty()) {
            rows.append("<tr><td colspan=\"8\" class=\"c\">Đơn chưa có dòng hàng</td></tr>");
        }

        OrderResponse.DeliveryAddressInfo a = o.deliveryAddress();
        String barcode;
        try {
            barcode = o.code() != null ? Code128Svg.svg(o.code(), 2, 56) : "";
        } catch (IllegalArgumentException e) {
            barcode = "";
        }
        String status = o.status() == null ? "" : PortalService.STATUS_LABELS.getOrDefault(o.status(), o.status());

        return "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Đơn hàng " + esc(o.code()) + "</title><style>"
                + "@page{size:A4;margin:12mm}*{box-sizing:border-box}"
                + "body{font-family:Arial,'Segoe UI',sans-serif;font-size:13px;color:#111;margin:0;padding:16px}"
                + ".head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;border-bottom:2px solid #111;padding-bottom:10px}"
                + "h1{font-size:20px;margin:6px 0 2px}.muted{color:#555}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:14px 0}"
                + ".box{border:1px solid #bbb;border-radius:6px;padding:8px 10px}.box b{display:block;margin-bottom:4px}"
                + "table{width:100%;border-collapse:collapse;margin-top:6px}th,td{border:1px solid #999;padding:5px 6px;vertical-align:top}"
                + "th{background:#f0f0f0}.c{text-align:center}.r{text-align:right;white-space:nowrap}"
                + ".tot{width:320px;margin-left:auto;margin-top:10px}.tot td{border:none;padding:3px 6px}.tot .big td{font-size:15px;font-weight:bold;border-top:1px solid #111}"
                + ".sign{display:grid;grid-template-columns:repeat(3,1fr);text-align:center;margin-top:36px}.sign i{display:block;color:#666;font-size:12px;margin-top:2px}"
                + ".barcode{max-width:100%;height:auto}@media print{body{padding:0}}"
                + "</style></head><body>"
                + "<div class=\"head\"><div><div class=\"muted\">" + esc(COMPANY) + "</div>"
                + "<h1>ĐƠN ĐẶT HÀNG</h1>"
                + "<div>Mã đơn: <b>" + esc(o.code()) + "</b></div>"
                + "<div>Ngày tạo: " + dateTime(o.createdAt()) + " · Trạng thái: " + esc(status) + "</div>"
                + "<div>Người lập: " + esc(o.createdByUsername()) + "</div></div>"
                + "<div>" + barcode + "</div></div>"
                + "<div class=\"grid\"><div class=\"box\"><b>Đại lý</b>"
                + esc(o.customerName()) + " (" + esc(o.customerCode()) + ")<br>"
                + "Nhóm: " + esc(o.customerGroupLabel()) + "</div>"
                + "<div class=\"box\"><b>Điểm giao hàng</b>"
                + (a == null ? "Chưa chọn điểm giao"
                : esc(a.label()) + "<br>" + esc(a.address()) + "<br>Người nhận: " + esc(a.receiverName())
                + " · " + esc(a.receiverPhone()))
                + "<br>Ngày giao mong muốn: " + date(o.desiredDeliveryDate()) + "</div></div>"
                + "<table><thead><tr><th>STT</th><th>Mã SKU</th><th>Tên hàng</th><th>ĐVT</th><th>Số lượng</th>"
                + "<th>Đơn giá</th><th>Chiết khấu</th><th>Thành tiền</th></tr></thead><tbody>" + rows + "</tbody></table>"
                + "<table class=\"tot\"><tr><td>Tổng tiền hàng</td><td class=\"r\">" + vnd(o.subtotal()) + "</td></tr>"
                + "<tr><td>Chiết khấu</td><td class=\"r\">- " + vnd(o.discountTotal()) + "</td></tr>"
                + "<tr class=\"big\"><td>Tổng phải thu</td><td class=\"r\">" + vnd(o.totalAmount()) + "</td></tr></table>"
                + (o.note() != null && !o.note().isBlank() ? "<p><b>Ghi chú:</b> " + esc(o.note()) + "</p>" : "")
                + "<div class=\"sign\"><div>Người lập đơn<i>(Ký, ghi rõ họ tên)</i></div>"
                + "<div>Thủ kho<i>(Ký, ghi rõ họ tên)</i></div><div>Đại lý xác nhận<i>(Ký, ghi rõ họ tên)</i></div></div>"
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
