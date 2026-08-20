package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationSchemeKeywordsTest {

	// 測試：只有使用者明確指名格式時才由程式判定，其他情況一律回問。
	@Test
	void resolvesOnlyExplicitSingleSchemeMentions() {
		assertThat(QuotationSchemeKeywords.parse("#報價 CNS 外部鷹架 2")).isEqualTo("CNS");
		assertThat(QuotationSchemeKeywords.parse("#報價 一般架，外部鷹架 2m2")).isEqualTo("GENERAL");
		assertThat(QuotationSchemeKeywords.parse("#報價 船用工程一批")).isEqualTo("MARINE");
		assertThat(QuotationSchemeKeywords.parse("#報價 空白格式")).isEqualTo("BLANK");
		assertThat(QuotationSchemeKeywords.parse("#報價 銷售報價單")).isEqualTo("SALES");
	}

	// 測試：沒提到格式、同時提到兩種格式或只描述工程內容時都不猜。
	@Test
	void refusesToGuessFromWorkDescriptionOrAmbiguousText() {
		assertThat(QuotationSchemeKeywords.parse("#報價 幫我算外牆鷹架 2 台中港工程")).isNull();
		assertThat(QuotationSchemeKeywords.parse("#報價 CNS 還是一般架？")).isNull();
		assertThat(QuotationSchemeKeywords.parse("#報價")).isNull();
		assertThat(QuotationSchemeKeywords.parse(null)).isNull();
	}

	// 測試：格式代碼與顯示名稱供按鈕與驗證共用。
	@Test
	void exposesTheFiveSupportedSchemesForButtonsAndValidation() {
		assertThat(QuotationSchemeKeywords.schemeCodes())
			.containsExactly("CNS", "GENERAL", "MARINE", "BLANK", "SALES");
		assertThat(QuotationSchemeKeywords.displayName("GENERAL")).isEqualTo("一般架");
		assertThat(QuotationSchemeKeywords.isSupported("cns")).isTrue();
		assertThat(QuotationSchemeKeywords.isSupported("LEGACY")).isFalse();
	}
}
