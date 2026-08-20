package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.voice.VoiceImageRetrievalTool;
import dev.myudog.assetsmanagerlinebot.service.voice.VoiceMcpTicketStore;
import dev.myudog.assetsmanagerlinebot.service.voice.VoiceTaskReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceMcpControllerTest {

	@Mock
	VoiceMcpTicketStore ticketStore;

	@Mock
	VoiceImageRetrievalTool retrievalTool;

	ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void rejectsRequestsWithoutTheConfiguredBearerToken() throws Exception {
		VoiceMcpController controller = controller();
		var request = objectMapper.readTree("""
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}
			""");

		var response = controller.handle("Bearer wrong-token", request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		verifyNoInteractions(ticketStore, retrievalTool);
	}

	@Test
	void listsOnlyTheImageRetrievalTool() throws Exception {
		VoiceMcpController controller = controller();
		var request = objectMapper.readTree("""
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}
			""");

		var response = controller.handle("Bearer mcp-secret", request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<?, ?> result = (Map<?, ?>) response.getBody().get("result");
		java.util.List<?> tools = (java.util.List<?>) result.get("tools");
		Map<?, ?> tool = (Map<?, ?>) tools.getFirst();
		assertThat(tool.get("name")).isEqualTo("retrieve_images");
		assertThat(tools).hasSize(1);
	}

	@Test
	void consumesTheTicketAndExecutesTheReceiptAsOneToolCall() throws Exception {
		VoiceMcpController controller = controller();
		VoiceMcpTicketStore.ExecutionContext context = new VoiceMcpTicketStore.ExecutionContext(
			"C1",
			"reply-token",
			Instant.parse("2026-08-11T08:01:00Z")
		);
		VoiceTaskReceipt receipt = new VoiceTaskReceipt(
			"圖片取出",
			"ZD12345",
			LocalDate.of(2026, 8, 10)
		);
		when(ticketStore.consume("ticket-1")).thenReturn(Optional.of(context));
		when(retrievalTool.execute(context, receipt)).thenReturn(
			new VoiceImageRetrievalTool.ToolResult(
				false,
				"已取出「ZD12345」2026/08/10 的2張圖片。",
				2,
				"ZD12345",
				"2026-08-10"
			)
		);
		var request = objectMapper.readTree("""
			{
			  "jsonrpc":"2.0",
			  "id":2,
			  "method":"tools/call",
			  "params":{
			    "name":"retrieve_images",
			    "arguments":{
			      "ticket":"ticket-1",
			      "action":"圖片取出",
			      "departmentCode":"ZD12345",
			      "date":"2026-08-10"
			    }
			  }
			}
			""");

		var response = controller.handle("Bearer mcp-secret", request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(ticketStore).consume("ticket-1");
		verify(retrievalTool).execute(context, receipt);
		Map<?, ?> result = (Map<?, ?>) response.getBody().get("result");
		assertThat(result.get("isError")).isEqualTo(false);
	}

	// 方法：建立使用固定驗證權杖的 MCP 控制器。
	private VoiceMcpController controller() {
		return new VoiceMcpController(ticketStore, retrievalTool, "mcp-secret");
	}
}
