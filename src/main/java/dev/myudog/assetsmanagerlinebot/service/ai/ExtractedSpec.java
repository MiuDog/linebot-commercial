package dev.myudog.assetsmanagerlinebot.service.ai;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 【職責】AI 從規格圖／資訊圖讀出來的結構化結果。
 *
 * <p>欄位名稱刻意不寫死成 record component，而是放在 {@code fields} 這個 Map 裡：
 * 實際要提取哪些欄位由設定檔 {@code app.ai.required-fields} 與提示詞決定，
 * 規格調整時不需要動 Java 程式碼。
 *
 * <p>{@code rawResponse} 保留模型的原始輸出，出問題時可以直接看它回了什麼，
 * 不必重跑一次呼叫。
 *
 * @param fields      欄位名稱到值的對應，值可能是字串、數字或巢狀結構
 * @param rawResponse 模型回應原文，供除錯用
 */
public record ExtractedSpec(Map<String, Object> fields, String rawResponse) {

    /**
     * 以字串取出某個欄位。
     *
     * @param key 欄位名稱
     * @return 欄位值的字串形式；欄位不存在時為 null
     */
    public String text(String key) {
        Object value = fields.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 以數值取出某個欄位，供計算公式使用。
     *
     * <p>模型有時把數字放在字串裡（例如 "1,200 mm"），所以這裡會先濾掉
     * 數字與小數點以外的字元再轉換。
     *
     * @param key 欄位名稱
     * @return 欄位的數值；欄位不存在或無法解析為數字時為 null
     */
    public BigDecimal number(String key) {
        String raw = text(key);
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9.\\-]", "");
        if (digits.isBlank() || "-".equals(digits) || ".".equals(digits)) {
            return null;
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
