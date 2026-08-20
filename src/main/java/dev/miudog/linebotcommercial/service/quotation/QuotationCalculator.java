package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.ai.ExtractedSpec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 【職責】把 AI 擷取出來的規格換算成報價金額。
 *
 * <p><b>本類別目前是佔位實作，公式尚未定義。</b>
 * 下方的常數與方法名稱都是假名，等實際公式確定後直接替換即可，
 * 呼叫端（{@code QuotationService}）不需要跟著改。
 *
 * <p>需要補齊的資訊：參與運算的欄位名稱、各係數的實際數值、運算順序、
 * 以及金額的進位規則。
 *
 * <p><b>預定呼叫鏈：</b>
 * {@code QuotationService.quote → calculate → QuotationAmounts
 * → QuotationPdfService.generate}。
 * 目前 {@link #calculate} 刻意拋出例外，防止未定義公式被誤當成正式價格。
 */
@Service
public class QuotationCalculator {

	// ===== 以下皆為佔位常數，待實際公式確定後替換 =====

	/** 佔位：單價係數。 */
	private static final BigDecimal FACTOR_A = BigDecimal.ZERO;

	/** 佔位：規格加成係數。 */
	private static final BigDecimal FACTOR_B = BigDecimal.ZERO;

	/** 佔位：固定成本。 */
	private static final BigDecimal BASE_COST = BigDecimal.ZERO;

	/**
	 * 依規格算出報價金額。
	 *
	 * <p>預期形式大致為 {@code BASE_COST + 主要規格值 × FACTOR_A × FACTOR_B}，
	 * 實際公式待補。
	 *
	 * @param spec AI 擷取出的規格欄位
	 * @return 計算結果
	 * @throws UnsupportedOperationException 公式尚未定義時一律拋出，
	 *         避免回傳一個看起來合理但其實是亂算的金額
	 */
	// 方法：執行 calculate 方法的處理流程。
	public QuotationAmounts calculate(ExtractedSpec spec) {
		throw new UnsupportedOperationException(
			"報價公式尚未定義：請補上參與運算的欄位、係數與進位規則後，實作 QuotationCalculator#calculate"
		);
	}

	/**
	 * 佔位：主要規格值的取得方式。
	 *
	 * @param spec 規格欄位
	 * @return 參與運算的主要數值
	 */
	// 方法：執行 primaryMetric 方法的處理流程。
	@SuppressWarnings("unused")
	private BigDecimal primaryMetric(ExtractedSpec spec) {
		throw new UnsupportedOperationException("主要規格欄位名稱待定");
	}
}
