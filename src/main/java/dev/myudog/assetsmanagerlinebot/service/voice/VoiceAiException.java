package dev.myudog.assetsmanagerlinebot.service.voice;

/** 語音 AI 外部服務失敗時使用的受檢例外。 */
public class VoiceAiException extends Exception {

	// 方法：建立不含底層原因的語音 AI 例外。
	public VoiceAiException(String message) {
		super(message);
	}

	// 方法：建立保留底層原因的語音 AI 例外。
	public VoiceAiException(String message, Throwable cause) {
		super(message, cause);
	}
}
