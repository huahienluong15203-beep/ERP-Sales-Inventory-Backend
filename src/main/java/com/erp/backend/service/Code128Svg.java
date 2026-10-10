package com.erp.backend.service;

import java.util.ArrayList;
import java.util.List;

/**
 * S4-08: Sinh mã vạch Code 128 (bộ ký tự B) dạng SVG, không cần thư viện ngoài.
 * Cấu trúc: Start B + dữ liệu + checksum (mod 103) + Stop. Mỗi ký hiệu là 6 độ rộng vạch/khoảng trắng (Stop có 7).
 */
public final class Code128Svg {

    static final int START_B = 104;
    static final int STOP = 106;

    /** Bảng độ rộng vạch chuẩn Code 128 cho các giá trị 0..106 (vạch, trắng, vạch, trắng...). */
    static final String[] PATTERNS = {
            "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
            "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
            "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
            "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
            "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
            "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
            "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
            "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
            "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
            "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
            "114131", "311141", "411131", "211412", "211214", "211232", "2331112"
    };

    private Code128Svg() {
    }

    /** Các giá trị ký hiệu: Start B, dữ liệu, checksum, Stop. Chỉ nhận ký tự ASCII in được (32..126). */
    static List<Integer> symbols(String data) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Không có dữ liệu để tạo mã vạch");
        }
        List<Integer> values = new ArrayList<>();
        values.add(START_B);
        int checksum = START_B;
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch < 32 || ch > 126) {
                throw new IllegalArgumentException("Mã vạch Code 128B chỉ nhận ký tự ASCII: '" + ch + "'");
            }
            int v = ch - 32;
            values.add(v);
            checksum += v * (i + 1);
        }
        values.add(checksum % 103);
        values.add(STOP);
        return values;
    }

    /**
     * SVG mã vạch, có khoảng trắng 10 mô-đun hai bên và chữ bên dưới.
     *
     * @param moduleWidth độ rộng 1 mô-đun (px)
     * @param barHeight   chiều cao vạch (px)
     */
    public static String svg(String data, int moduleWidth, int barHeight) {
        List<Integer> values = symbols(data);
        int quiet = 10 * moduleWidth;
        StringBuilder bars = new StringBuilder();
        int x = quiet;
        for (int value : values) {
            String pattern = PATTERNS[value];
            for (int i = 0; i < pattern.length(); i++) {
                int w = (pattern.charAt(i) - '0') * moduleWidth;
                if (i % 2 == 0) {
                    bars.append("<rect x=\"").append(x).append("\" y=\"0\" width=\"").append(w)
                            .append("\" height=\"").append(barHeight).append("\"/>");
                }
                x += w;
            }
        }
        int width = x + quiet;
        int height = barHeight + 18;
        String text = data.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"barcode\" width=\"" + width + "\" height=\"" + height
                + "\" viewBox=\"0 0 " + width + " " + height + "\" role=\"img\" aria-label=\"Mã vạch " + text + "\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><g fill=\"#000\">" + bars + "</g>"
                + "<text x=\"" + (width / 2) + "\" y=\"" + (barHeight + 15) + "\" font-family=\"monospace\" font-size=\"13\""
                + " text-anchor=\"middle\">" + text + "</text></svg>";
    }
}
