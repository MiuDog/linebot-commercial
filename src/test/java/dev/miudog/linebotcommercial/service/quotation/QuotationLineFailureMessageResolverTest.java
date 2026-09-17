package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationLineFailureMessageResolverTest {

	// 方法：確認與對話領域錯誤保留可行動代碼，不再被誤報為內部錯誤。
	@Test
	void preservesExpectedConfirmationAndConversationErrors() {
		var confirmation = QuotationLineFailureMessageResolver.resolve(
			new QuotationConfirmationException("QUOTATION_ASSETS_NOT_READY", "尚未啟用公司資產"),
			QuotationLineFailureMessageResolver.Operation.POSTBACK);
		assertThat(confirmation.code()).isEqualTo("QUOTATION_ASSETS_NOT_READY");
		assertThat(confirmation.message()).contains("尚未啟用公司資產").doesNotContain("INTERNAL_ERROR");
		var conversation = QuotationLineFailureMessageResolver.resolve(
			new QuotationConversationException("STALE_DRAFT", "請重新預覽"),
			QuotationLineFailureMessageResolver.Operation.POSTBACK);
		assertThat(conversation.code()).isEqualTo("STALE_DRAFT");
	}
}
