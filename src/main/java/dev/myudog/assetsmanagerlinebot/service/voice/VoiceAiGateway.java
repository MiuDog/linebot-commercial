package dev.myudog.assetsmanagerlinebot.service.voice;

import java.time.LocalDate;

/**
 * 語音指令使用的 AI 邊界，負責轉錄及要求模型決定是否呼叫 MCP 工具。
 */
public interface VoiceAiGateway {

	// 方法：確認語音 AI 是否已完成設定。
	boolean isConfigured();

	// 方法：將音訊轉為逐字稿。
	String transcribe(byte[] audio, String contentType) throws VoiceAiException;

	// 方法：分析逐字稿並在收據完整時直接執行 MCP。
	TaskDecision analyzeAndExecute(
		String transcript,
		String executionTicket,
		LocalDate currentDate
	) throws VoiceAiException;

	/**
	 * @param toolCalled 是否已由模型透過 MCP 完成工具呼叫
	 * @param userMessage 未執行工具時，要回覆給使用者的補充說明
	 */
	record TaskDecision(boolean toolCalled, String userMessage) {}
}
