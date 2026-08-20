package dev.myudog.assetsmanagerlinebot.config;

import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationDraftWorkflowPort;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationAiParsingService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationCalculationService;
import dev.myudog.assetsmanagerlinebot.service.quotation.SqliteQuotationDraftWorkflowPort;
import dev.myudog.assetsmanagerlinebot.service.quotation.UnconfiguredQuotationDraftWorkflowPort;
import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository;
import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class QuotationDraftWorkflowConfiguration {

	// 方法：AI 完整設定時啟用 SQLite adapter，否則使用明確拒絕操作的替代 port。
	@Bean
	@ConditionalOnMissingBean(QuotationDraftWorkflowPort.class)
	public QuotationDraftWorkflowPort quotationDraftWorkflowPort(
		JdbcTemplate jdbc,
		QuotationAiParsingService parser,
		QuotationCalculationService calculator,
		PendingImageRepository pendingImages,
		FileStorageService storage,
		PlatformTransactionManager transactionManager
	) {
		if (!parser.isConfigured()) return new UnconfiguredQuotationDraftWorkflowPort();

		return new SqliteQuotationDraftWorkflowPort(
			jdbc,
			parser,
			calculator,
			pendingImages,
			storage,
			transactionManager
		);
	}
}
