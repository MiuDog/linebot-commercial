package dev.myudog.assetsmanagerlinebot.service.voice;

import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;

/** 接收 LINE 群組語音，並在「小定」喚醒詞成立時交由 AI 處理。 */
@Service
public class VoiceCommandService {

	private static final Logger log = LoggerFactory.getLogger(VoiceCommandService.class);
	private static final int MAX_AUDIO_BYTES = 25 * 1024 * 1024;
	private static final String WAKE_WORD = "小定";

	private final LineStorageService lineService;
	private final VoiceAiGateway aiGateway;
	private final VoiceMcpTicketStore ticketStore;
	private final Clock clock;
	private final boolean enabled;

	// 方法：依環境設定初始化正式語音指令服務。
	@Autowired
	public VoiceCommandService(
		LineStorageService lineService,
		VoiceAiGateway aiGateway,
		VoiceMcpTicketStore ticketStore,
		@Value("${app.voice.enabled:false}") boolean enabled
	) {
		this(lineService, aiGateway, ticketStore, Clock.systemDefaultZone(), enabled);
	}

	// 方法：以固定時鐘初始化測試用語音指令服務。
	VoiceCommandService(
		LineStorageService lineService,
		VoiceAiGateway aiGateway,
		VoiceMcpTicketStore ticketStore,
		Clock clock,
		boolean enabled
	) {
		this.lineService = lineService;
		this.aiGateway = aiGateway;
		this.ticketStore = ticketStore;
		this.clock = clock;
		this.enabled = enabled;
	}

	// 方法：下載、轉錄及處理一則群組語音訊息。
	public void handleGroupAudio(String messageId, String sourceId, String replyToken) {
		if (!enabled) return;

		if (!aiGateway.isConfigured()) {
			lineService.replyText(replyToken, "語音功能尚未完成設定，請聯絡管理員。");
			return;
		}

		LineStorageService.LineContent content = lineService.downloadContent(messageId);
		if (content == null) {
			lineService.replyText(replyToken, "語音訊息下載失敗，請稍後重新傳送。");
			return;
		}

		try (InputStream stream = content.stream()) {
			byte[] audio = readBounded(stream);
			String transcript = aiGateway.transcribe(audio, content.contentType()).strip();
			if (!transcript.stripLeading().startsWith(WAKE_WORD)) return;

			// 日誌：依需求記錄通過喚醒詞檢查的實際語音內容。
			log.info("接收到語音訊息：{}", safeLogText(transcript));
			handleAwakenedCommand(transcript, sourceId, replyToken);
		}
		catch (AudioTooLargeException exception) {
			lineService.replyText(replyToken, "語音訊息超過25 MB，請縮短後重新傳送。");
		}
		catch (IOException | VoiceAiException exception) {
			// 日誌：只記錄語音失敗類型，不寫入金鑰或外部回應本文。
			log.warn("event=voice_command_failed errorType={}", exception.getClass().getSimpleName());
			lineService.replyText(replyToken, "語音內容處理失敗，請稍後重新傳送。");
		}
	}

	// 方法：簽發票券並讓 AI 將完整任務直接交由 MCP 執行。
	private void handleAwakenedCommand(
		String transcript,
		String sourceId,
		String replyToken
	) throws VoiceAiException {
		String ticket = ticketStore.issue(sourceId, replyToken);
		VoiceAiGateway.TaskDecision decision;
		try {
			decision = aiGateway.analyzeAndExecute(transcript, ticket, LocalDate.now(clock));
		}
		catch (VoiceAiException exception) {
			ticketStore.discard(ticket);
			throw exception;
		}

		if (decision.toolCalled()) return;


		ticketStore.discard(ticket);
		String message = decision.userMessage();
		lineService.replyText(
			replyToken,
			message == null || message.isBlank()
				? "目前支援圖片取出，請說明部門編號與圖片日期。"
				: message
		);
	}

	// 方法：限制語音大小，避免不受信任的 LINE 內容耗盡記憶體。
	private byte[] readBounded(InputStream stream) throws IOException, AudioTooLargeException {
		byte[] audio = stream.readNBytes(MAX_AUDIO_BYTES + 1);
		if (audio.length > MAX_AUDIO_BYTES) throw new AudioTooLargeException();

		return audio;
	}

	// 方法：保留實際轉錄內容，同時避免換行偽造 logger 記錄。
	private String safeLogText(String transcript) {
		return transcript.replace('\r', ' ').replace('\n', ' ');
	}

	private static class AudioTooLargeException extends Exception {}
}
