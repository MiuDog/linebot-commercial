package dev.myudog.assetsmanagerlinebot.config;

import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository;
import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationAiParsingService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationCalculationService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationDraftWorkflowPort;
import dev.myudog.assetsmanagerlinebot.service.quotation.SqliteQuotationDraftWorkflowPort;
import dev.myudog.assetsmanagerlinebot.service.quotation.UnconfiguredQuotationDraftWorkflowPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotationDraftWorkflowConfigurationTest {

	@Test
	void usesSqliteRuntimePortWhenAiIsConfigured() {
		QuotationAiParsingService parser = mock(QuotationAiParsingService.class);
		when(parser.isConfigured()).thenReturn(true);

		QuotationDraftWorkflowPort port = new QuotationDraftWorkflowConfiguration()
			.quotationDraftWorkflowPort(
				mock(JdbcTemplate.class),
				parser,
				mock(QuotationCalculationService.class),
				mock(PendingImageRepository.class),
				mock(FileStorageService.class),
				mock(PlatformTransactionManager.class)
			);

		assertThat(port).isInstanceOf(SqliteQuotationDraftWorkflowPort.class);
	}

	@Test
	void usesExplicitUnavailableFallbackOnlyWhenAiIsNotConfigured() {
		QuotationAiParsingService parser = mock(QuotationAiParsingService.class);
		when(parser.isConfigured()).thenReturn(false);

		QuotationDraftWorkflowPort port = new QuotationDraftWorkflowConfiguration()
			.quotationDraftWorkflowPort(
				mock(JdbcTemplate.class),
				parser,
				mock(QuotationCalculationService.class),
				mock(PendingImageRepository.class),
				mock(FileStorageService.class),
				mock(PlatformTransactionManager.class)
			);

		assertThat(port).isInstanceOf(UnconfiguredQuotationDraftWorkflowPort.class);
	}
}
