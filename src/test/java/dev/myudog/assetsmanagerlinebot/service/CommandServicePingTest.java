package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CommandServicePingTest {

	@Mock
	AssetService assetService;

	@Mock
	LineStorageService lineService;

	@Mock
	QuotationService quotationService;

	@Mock
	ImageArchiveService archiveService;

	CommandService commandService;

	@BeforeEach
	void setUp() {
		commandService = new CommandService(lineService);
	}

	@Test
	void repliesPongWithTheMeasuredLatencyInMilliseconds() {
		long timestamp = System.currentTimeMillis() - 150L;

		boolean handled = commandService.handleMentionPing(" PiNg ", timestamp, "R1");

		assertThat(handled).isTrue();
		ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(lineService).replyText(org.mockito.ArgumentMatchers.eq("R1"), reply.capture());
		assertThat(reply.getValue()).startsWith("🏓 pong！延遲 ").endsWith(" ms");
		long latency = Long.parseLong(reply.getValue().replaceAll("[^0-9]", ""));
		assertThat(latency).isGreaterThanOrEqualTo(150L);
	}

	@Test
	void saysLatencyIsUnavailableWhenTheEventCarriesNoTimestamp() {
		boolean handled = commandService.handleMentionPing("ping", 0L, "R1");

		assertThat(handled).isTrue();
		verify(lineService).replyText("R1", "🏓 pong！（本次事件缺少時間戳記，無法計算延遲）");
	}

	@Test
	void ignoresMentionsThatAreNotAPlainPing() {
		assertThat(commandService.handleMentionPing(null, 1L, "R1")).isFalse();
		assertThat(commandService.handleMentionPing("ping 一下", 1L, "R1")).isFalse();
		assertThat(commandService.handleMentionPing("pingpong", 1L, "R1")).isFalse();
		verifyNoInteractions(lineService, assetService, quotationService, archiveService);
	}
}
