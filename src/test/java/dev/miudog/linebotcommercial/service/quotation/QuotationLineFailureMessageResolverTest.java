package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationLineFailureMessageResolverTest {

	// 方法：只有暫時性鎖定可以提示重試，SQL 語法錯誤不可誤報為忙碌或洩漏 SQL。
	@Test
	void distinguishesTransientLocksFromInvalidSql() {
		var locked = QuotationLineFailureMessageResolver.resolve(
			new org.springframework.dao.CannotAcquireLockException("lock unavailable"),
			QuotationLineFailureMessageResolver.Operation.POSTBACK);
		assertThat(locked.code()).isEqualTo("QUOTATION_DATABASE_BUSY");
		var invalid = QuotationLineFailureMessageResolver.resolve(
			new org.springframework.jdbc.BadSqlGrammarException("confirm", "private SQL", new java.sql.SQLException("ambiguous column")),
			QuotationLineFailureMessageResolver.Operation.POSTBACK);
		assertThat(invalid.code()).isEqualTo("QUOTATION_DATABASE_ERROR");
		assertThat(invalid.message()).contains("管理員").doesNotContain("忙碌", "稍後重試", "private SQL", "ambiguous");
	}

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
