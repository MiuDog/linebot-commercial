package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.voice.VoiceImageRetrievalTool;
import dev.myudog.assetsmanagerlinebot.service.voice.VoiceMcpTicketStore;
import dev.myudog.assetsmanagerlinebot.service.voice.VoiceTaskReceipt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 對 OpenAI Responses API 提供的最小唯讀 MCP 工具伺服器。 */
@RestController
@RequestMapping("/mcp")
public class VoiceMcpController {

	private static final String PROTOCOL_VERSION = "2025-03-26";
	private static final String TOOL_NAME = "retrieve_images";

	private final VoiceMcpTicketStore ticketStore;
	private final VoiceImageRetrievalTool retrievalTool;
	private final String authToken;

	// 方法：初始化 MCP 控制器及其驗證權杖。
	public VoiceMcpController(
		VoiceMcpTicketStore ticketStore,
		VoiceImageRetrievalTool retrievalTool,
		@Value("${app.voice.mcp-auth-token:}") String authToken
	) {
		this.ticketStore = ticketStore;
		this.retrievalTool = retrievalTool;
		this.authToken = authToken;
	}

	// 方法：處理 MCP JSON-RPC 初始化、工具列舉及工具呼叫。
	@PostMapping
	public ResponseEntity<Map<String, Object>> handle(
		@RequestHeader(value = "Authorization", required = false) String authorization,
		@RequestBody JsonNode request
	) {
		if (!isAuthorized(authorization)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(Map.of("error", "Unauthorized"));
		}
		if (request == null || !request.isObject()) return ResponseEntity.ok(
			error(null, -32600, "Invalid Request")
		);


		JsonNode id = request.get("id");
		String method = textOf(request, "method");
		if (id == null && method != null && method.startsWith("notifications/")) return ResponseEntity
			.accepted()
			.build();


		return switch (method == null ? "" : method) {
			case "initialize" -> ResponseEntity.ok(success(id, initializeResult(request)));
			case "ping" -> ResponseEntity.ok(success(id, Map.of()));
			case "tools/list" -> ResponseEntity.ok(success(id, Map.of("tools", List.of(toolDefinition()))));
			case "tools/call" -> ResponseEntity.ok(handleToolCall(id, request.get("params")));
			default -> ResponseEntity.ok(error(id, -32601, "Method not found"));
		};
	}

	// 方法：回傳 MCP 協議交握資訊。
	private Map<String, Object> initializeResult(JsonNode request) {
		JsonNode params = request.get("params");
		String requestedVersion = textOf(params, "protocolVersion");
		return Map.of(
			"protocolVersion",
			requestedVersion == null ? PROTOCOL_VERSION : requestedVersion,
			"capabilities",
			Map.of("tools", Map.of("listChanged", false)),
			"serverInfo",
			Map.of("name", "linebot-commercial", "version", "0.1.0")
		);
	}

	// 方法：宣告唯一允許的圖片取出工具及嚴格輸入結構。
	private Map<String, Object> toolDefinition() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("ticket", Map.of("type", "string", "minLength", 16, "maxLength", 128));
		properties.put("action", Map.of("type", "string", "const", "圖片取出"));
		properties.put("departmentCode", Map.of(
			"type", "string",
			"pattern", "^(?:ZD\\d{5}[A-Z]?|ZD-JY\\d{5}|YJ\\d{6})$"
		));
		properties.put("date", Map.of(
			"type", "string",
			"format", "date",
			"description", "圖片日期，格式為 YYYY-MM-DD"
		));

		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", List.of("ticket", "action", "departmentCode", "date"));
		schema.put("additionalProperties", false);

		return Map.of(
			"name", TOOL_NAME,
			"description", "依目前 LINE 群組、部門編號及日期取出圖片，並直接回覆到該群組。",
			"inputSchema", schema
		);
	}

	// 方法：解析不受信任的模型參數，消耗單次票券後完整執行圖片資料組。
	private Map<String, Object> handleToolCall(JsonNode id, JsonNode params) {
		if (!TOOL_NAME.equals(textOf(params, "name"))) return error(
			id,
			-32602,
			"Invalid tool name"
		);


		JsonNode arguments = params == null ? null : params.get("arguments");
		String ticket = textOf(arguments, "ticket");
		String action = textOf(arguments, "action");
		String departmentCode = textOf(arguments, "departmentCode");
		String dateText = textOf(arguments, "date");
		if (ticket == null || action == null || departmentCode == null || dateText == null) return error(
			id,
			-32602,
			"Missing required tool arguments"
		);


		LocalDate date;
		try {
			date = LocalDate.parse(dateText);
		}
		catch (DateTimeParseException exception) {
			return error(id, -32602, "Invalid date");
		}

		Optional<VoiceMcpTicketStore.ExecutionContext> context = ticketStore.consume(ticket);
		if (context.isEmpty()) {
			return toolResult(
				id,
				new VoiceImageRetrievalTool.ToolResult(
					true,
					"語音任務已失效，請重新傳送一次。",
					0,
					null,
					null
				)
			);
		}

		VoiceTaskReceipt receipt = new VoiceTaskReceipt(action, departmentCode, date);
		return toolResult(id, retrievalTool.execute(context.get(), receipt));
	}

	// 方法：將圖片工具結果轉成 MCP content 與 structuredContent。
	private Map<String, Object> toolResult(
		JsonNode id,
		VoiceImageRetrievalTool.ToolResult toolResult
	) {
		Map<String, Object> structured = new LinkedHashMap<>();
		structured.put("message", toolResult.message());
		structured.put("imageCount", toolResult.imageCount());
		structured.put("departmentCode", toolResult.departmentCode());
		structured.put("date", toolResult.date());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", List.of(Map.of("type", "text", "text", toolResult.message())));
		result.put("structuredContent", structured);
		result.put("isError", toolResult.isError());
		return success(id, result);
	}

	// 方法：以固定時間比較 MCP Bearer 權杖。
	private boolean isAuthorized(String authorization) {
		if (authToken == null || authToken.isBlank() || authorization == null) return false;

		byte[] expected = ("Bearer " + authToken).getBytes(StandardCharsets.UTF_8);
		byte[] actual = authorization.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}

	// 方法：安全讀取 JSON 字串欄位。
	private String textOf(JsonNode node, String field) {
		if (node == null) return null;

		JsonNode value = node.get(field);
		return value != null && value.isString() ? value.stringValue() : null;
	}

	// 方法：建立 JSON-RPC 成功回應。
	private Map<String, Object> success(JsonNode id, Object result) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("result", result);
		return response;
	}

	// 方法：建立不暴露內部資訊的 JSON-RPC 錯誤回應。
	private Map<String, Object> error(JsonNode id, int code, String message) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("error", Map.of("code", code, "message", message));
		return response;
	}
}
