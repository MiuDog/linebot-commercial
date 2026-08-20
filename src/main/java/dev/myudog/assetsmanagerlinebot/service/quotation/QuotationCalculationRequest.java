package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 報價列解析與確定性計價所需的可信程式輸入。
 *
 * @param schemeCode          報價方案代碼
 * @param standardItemIntents 使用者明確提及的標準品項數量
 * @param customItems         臨時、動態或船用內部計算品項
 * @param removedItemCodes    使用者明確刪除的標準品項代碼
 */
public record QuotationCalculationRequest(
	String schemeCode,
	List<StandardItemIntent> standardItemIntents,
	List<CustomItem> customItems,
	Set<String> removedItemCodes
) {

	/**
	 * 標準品項意圖。上游若帶入自行計算的複價，只供安全測試，服務一律忽略並重算。
	 *
	 * @param itemCode           固定品項代碼
	 * @param quantity           使用者明確提供的數量
	 * @param untrustedLineAmount 不可信的上游複價
	 */
	public record StandardItemIntent(
		String itemCode,
		BigDecimal quantity,
		BigDecimal untrustedLineAmount
	) {}

	/**
	 * 非主檔品項輸入。固定方案視為臨時品項，其餘方案視為動態或內部計算品項。
	 *
	 * @param itemName            品項名稱
	 * @param specification       規格說明
	 * @param unit                單位
	 * @param unitPrice           單價
	 * @param quantity            數量
	 * @param remark              備註
	 * @param confirmed           是否經使用者確認
	 * @param untrustedLineAmount 不可信的上游複價
	 */
	public record CustomItem(
		String itemName,
		String specification,
		String unit,
		BigDecimal unitPrice,
		BigDecimal quantity,
		String remark,
		boolean confirmed,
		BigDecimal untrustedLineAmount
	) {}
}
