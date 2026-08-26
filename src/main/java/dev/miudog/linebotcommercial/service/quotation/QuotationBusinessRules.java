package dev.miudog.linebotcommercial.service.quotation;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 集中保存可由客戶設定的報價稅率與有效天數。
 */
@Component
public class QuotationBusinessRules {

	//#region 欄位

	private final BigDecimal taxRate;
	private final int validityDays;

	//#endregion

	//#region 建構子

	// 方法：建立經過範圍驗證的報價商業規則。
	public QuotationBusinessRules(
		@Value("${app.quotation.tax-rate:0.05}") BigDecimal taxRate,
		@Value("${app.quotation.validity-days:15}") int validityDays
	) {
		if (taxRate == null || taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("報價稅率必須介於 0 到 1");
		}

		if (validityDays < 1 || validityDays > 3650) {
			throw new IllegalArgumentException("報價有效天數必須介於 1 到 3650");
		}

		this.taxRate = taxRate;
		this.validityDays = validityDays;
	}

	//#endregion

	//#region 方法

	// 方法：取得報價計算與快照共用的稅率。
	public BigDecimal taxRate() {
		return taxRate;
	}

	// 方法：取得報價日起算的有效天數。
	public int validityDays() {
		return validityDays;
	}

	//#endregion
}
