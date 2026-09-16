package dev.miudog.linebotcommercial.service.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 提供不綁定特定業務的 OpenAI 相容 JSON 文字完成邊界。
 */
public interface AiJsonCompletionClient {

	// 方法：確認模型端點、金鑰與模型名稱已設定。
	boolean isConfigured();

	// 方法：以隔離的系統／使用者提示與有識別碼的圖片取得模型 JSON 文字。
	String completeJson(String systemPrompt, String userPrompt, List<AiImageInput> images);

	// 方法：使用 API 層的嚴格結構化契約取得模型 patch。
	String completeJson(String systemPrompt, String userPrompt, List<AiImageInput> images, JsonNode responseSchema);
}
