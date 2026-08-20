package dev.myudog.assetsmanagerlinebot.service.voice;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.service.AssetService;
import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceImageRetrievalToolTest {

	@Mock
	AssetService assetService;

	@Mock
	LineStorageService lineService;

	@Test
	void retrievesTheRequestedDepartmentAndDateAsOneLineReply() {
		VoiceImageRetrievalTool tool = new VoiceImageRetrievalTool(
			assetService,
			lineService,
			"https://assets.example.com",
			4
		);
		VoiceMcpTicketStore.ExecutionContext context =
			new VoiceMcpTicketStore.ExecutionContext("C1", "reply-token", Instant.parse("2026-08-11T08:01:00Z"));
		VoiceTaskReceipt receipt =
			new VoiceTaskReceipt("圖片取出", "ZD12345", LocalDate.of(2026, 8, 10));
		List<Asset> assets = List.of(
			asset(1L, "share-1"),
			asset(2L, "share-2")
		);
		when(assetService.searchByDepartmentAndDate("C1", "zd12345", "20260810", 4))
			.thenReturn(assets);

		VoiceImageRetrievalTool.ToolResult result = tool.execute(context, receipt);

		assertThat(result.isError()).isFalse();
		assertThat(result.imageCount()).isEqualTo(2);
		ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
		verify(lineService).reply(org.mockito.ArgumentMatchers.eq("reply-token"), messages.capture());
		assertThat(messages.getValue()).hasSize(3);
		assertThat(messages.getValue().get(0).get("text"))
			.isEqualTo("已取出「ZD12345」2026/08/10 的2張圖片。");
		assertThat(messages.getValue().get(1).get("originalContentUrl"))
			.isEqualTo("https://assets.example.com/media/share-1");
	}

	@Test
	void rejectsAnInvalidAiGeneratedDepartmentCodeBeforeQueryingData() {
		VoiceImageRetrievalTool tool = new VoiceImageRetrievalTool(
			assetService,
			lineService,
			"https://assets.example.com",
			4
		);
		VoiceMcpTicketStore.ExecutionContext context =
			new VoiceMcpTicketStore.ExecutionContext("C1", "reply-token", Instant.parse("2026-08-11T08:01:00Z"));
		VoiceTaskReceipt receipt =
			new VoiceTaskReceipt("圖片取出", "../../其他群組", LocalDate.of(2026, 8, 10));

		VoiceImageRetrievalTool.ToolResult result = tool.execute(context, receipt);

		assertThat(result.isError()).isTrue();
		assertThat(result.message()).isEqualTo("語音中的部門編號格式不正確，請重新說一次。");
		verify(lineService).replyText("reply-token", "語音中的部門編號格式不正確，請重新說一次。");
	}

	// 方法：建立語音圖片取出測試使用的資產資料。
	private Asset asset(long id, String shareToken) {
		return new Asset(
			id,
			"message-" + id,
			shareToken,
			"group",
			"C1",
			"U1",
			"ZD12345/20260810/20260810-0" + id + ".jpg",
			"image/jpeg",
			100L,
			Instant.parse("2026-08-10T01:00:00Z"),
			List.of("zd12345")
		);
	}
}
