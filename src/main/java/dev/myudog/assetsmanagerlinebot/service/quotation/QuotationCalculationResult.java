package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.math.BigDecimal;
import java.util.List;

/**
 * 五格式共用的確定性報價列與合計模型。
 *
 * @param schemeCode           報價方案代碼
 * @param internalLines        內部完整計價列
 * @param customerLines        對客可見明細列
 * @param subtotal             未稅小計
 * @param tax                  5% 稅額
 * @param total                含稅總額
 * @param customerPresentation 對客明細呈現方式
 */
public record QuotationCalculationResult(
	String schemeCode,
	List<QuotationLine> internalLines,
	List<QuotationLine> customerLines,
	BigDecimal subtotal,
	BigDecimal tax,
	BigDecimal total,
	CustomerPresentation customerPresentation
) {

	/** 報價列來源。 */
	public enum LineOrigin {
		STANDARD,
		TEMPORARY,
		DYNAMIC
	}

	/** 對客版的明細呈現規則。 */
	public enum CustomerPresentation {
		DETAIL,
		SUMMARY_ONLY
	}

	/**
	 * 報價列的固定快照與程式計算結果。
	 *
	 * @param itemCode          固定主檔代碼；非主檔品項為 null
	 * @param itemName          品項名稱
	 * @param specification     規格說明
	 * @param unit              單位
	 * @param unitPrice         單價
	 * @param quantity          數量；未提及固定品項為 null
	 * @param lineAmount        複價；數量空白時為 null
	 * @param remark            備註
	 * @param displayOrder      顯示順序
	 * @param calculationMode   計價模式
	 * @param origin            品項來源
	 * @param customerVisible   是否可出現在對客明細
	 */
	public record QuotationLine(
		String itemCode,
		String itemName,
		String specification,
		String unit,
		BigDecimal unitPrice,
		BigDecimal quantity,
		BigDecimal lineAmount,
		String remark,
		int displayOrder,
		String calculationMode,
		LineOrigin origin,
		boolean customerVisible
	) {}
}
