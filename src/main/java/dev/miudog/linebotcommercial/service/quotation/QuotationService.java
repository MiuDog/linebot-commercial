package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.ai.AiExtractionException;
import dev.miudog.linebotcommercial.service.ai.AiExtractionService;
import dev.miudog.linebotcommercial.service.ai.ExtractedSpec;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 【職責】報價流程的串接者：資料提取 → 計算 → 產出 PDF。
 *
 * <p>流程本身已經接好，三段之中只有第一段（AI 提取）是完成的，
 * 後兩段會拋出 {@link UnsupportedOperationException} 直到公式與模板補齊。
 * 這樣安排的用意是：AI 提取現在就能單獨測試，補完公式與模板後不必再改串接邏輯。
 *
 * <p><b>事件呼叫鏈：</b>
 * {@code #報價 → CommandService.replyQuotation → quote
 * → AiExtractionService.extract → QuotationCalculator.calculate
 * → QuotationPdfService.generate → CommandService 回覆結果}。
 *
 * <p>目前第一段已接通；計算器會回報「報價公式尚未定義」，
 * 所以事件尚未到達 PDF 產生器。新版 Excel schema、模板定義與輸出目錄服務
 * 也尚未接入本類別，完整邊界見 {@code docs/06-event-call-chains.md}。
 */
@Service
public class QuotationService {

	private final AiExtractionService aiExtractionService;
	private final QuotationCalculator calculator;
	private final QuotationPdfService pdfService;

	/**
	 * @param aiExtractionService 規格圖資料提取
	 * @param calculator          報價公式（佔位）
	 * @param pdfService          報價單產生（佔位）
	 */
	// 方法：初始化 QuotationService。
	public QuotationService(
		AiExtractionService aiExtractionService,
		QuotationCalculator calculator,
		QuotationPdfService pdfService
	) {
		this.aiExtractionService = aiExtractionService;
		this.calculator = calculator;
		this.pdfService = pdfService;
	}

	/**
	 * 流程執行結果。
	 *
	 * <p>刻意把「提取成功但後段未完成」也表達成一種結果而非例外，
	 * 讓使用者至少能看到 AI 讀出了什麼。
	 *
	 * @param spec        提取出的規格；提取失敗時為 null
	 * @param amounts     計算出的金額；公式未定義時為 null
	 * @param pdfPath     產出的報價單路徑；模板未提供時為 null
	 * @param blockedStep 卡在哪一步，全部完成時為 null
	 */
	public record QuotationResult(ExtractedSpec spec, QuotationAmounts amounts, Path pdfPath, String blockedStep) {

		/**
		 * 是否完整跑完三個步驟。
		 *
		 * @return 沒有卡關時為 true
		 */
		// 方法：執行 isComplete 方法的處理流程。
		public boolean isComplete() {
			return blockedStep == null;
		}
	}

	/**
	 * 執行完整報價流程。
	 *
	 * @param infoImage   規格圖／資訊圖位元組
	 * @param contentType 圖片 MIME 型態
	 * @return 流程結果，含卡關資訊
	 * @throws AiExtractionException 第一步就失敗時（未設定、呼叫失敗、必要欄位缺漏）
	 */
	// 方法：執行 quote 方法的處理流程。
	public QuotationResult quote(byte[] infoImage, String contentType) {
		ExtractedSpec spec = aiExtractionService.extract(infoImage, contentType);

		QuotationAmounts amounts;
		try {
			amounts = calculator.calculate(spec);
		}
		catch (UnsupportedOperationException e) {
			return new QuotationResult(spec, null, null, "報價公式尚未定義");
		}

		try {
			Path pdfPath = pdfService.generate(spec, amounts, infoImage);
			return new QuotationResult(spec, amounts, pdfPath, null);
		}
		catch (UnsupportedOperationException e) {
			return new QuotationResult(spec, amounts, null, "報價單模板尚未提供");
		}
	}

	/**
	 * AI 服務是否已設定，供指令入口先行檢查。
	 *
	 * @return 已設定時為 true
	 */
	// 方法：執行 isAiConfigured 方法的處理流程。
	public boolean isAiConfigured() {
		return aiExtractionService.isConfigured();
	}
}
