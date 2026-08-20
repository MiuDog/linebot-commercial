package dev.myudog.assetsmanagerlinebot.service.voice;

import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceCommandServiceTest {

	@Mock
	LineStorageService lineService;

	@Mock
	VoiceAiGateway aiGateway;

	@Mock
	VoiceMcpTicketStore ticketStore;

	VoiceCommandService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(
			Instant.parse("2026-08-11T08:00:00Z"),
			ZoneId.of("Asia/Taipei")
		);
		service = new VoiceCommandService(lineService, aiGateway, ticketStore, clock, true);
	}

	@Test
	void ignoresTranscribedAudioWithoutTheWakeWord() throws Exception {
		byte[] audio = "audio".getBytes(StandardCharsets.UTF_8);
		when(lineService.downloadContent("A1"))
			.thenReturn(new LineStorageService.LineContent(new ByteArrayInputStream(audio), "audio/mp4"));
		when(aiGateway.isConfigured()).thenReturn(true);
		when(aiGateway.transcribe(audio, "audio/mp4")).thenReturn("請幫我取出圖片");

		service.handleGroupAudio("A1", "C1", "reply-token");

		verifyNoInteractions(ticketStore);
		verify(aiGateway, never()).analyzeAndExecute(
			"請幫我取出圖片",
			"ticket",
			java.time.LocalDate.of(2026, 8, 11)
		);
		verify(lineService, never()).replyText("reply-token", "請幫我取出圖片");
	}

	@Test
	void executesMcpAfterACompleteWakeWordTaskIsRecognized() throws Exception {
		byte[] audio = "audio".getBytes(StandardCharsets.UTF_8);
		String transcript = "小定，圖片取出 ZD12345 八月十日的圖片";
		when(lineService.downloadContent("A1"))
			.thenReturn(new LineStorageService.LineContent(new ByteArrayInputStream(audio), "audio/mp4"));
		when(aiGateway.isConfigured()).thenReturn(true);
		when(aiGateway.transcribe(audio, "audio/mp4")).thenReturn(transcript);
		when(ticketStore.issue("C1", "reply-token")).thenReturn("ticket-1");
		when(aiGateway.analyzeAndExecute(transcript, "ticket-1", java.time.LocalDate.of(2026, 8, 11)))
			.thenReturn(new VoiceAiGateway.TaskDecision(true, null));

		service.handleGroupAudio("A1", "C1", "reply-token");

		verify(aiGateway).analyzeAndExecute(
			transcript,
			"ticket-1",
			java.time.LocalDate.of(2026, 8, 11)
		);
		verify(lineService, never()).replyText("reply-token", transcript);
	}

	@Test
	void repliesWithTheAiClarificationWhenTheReceiptIsIncomplete() throws Exception {
		byte[] audio = "audio".getBytes(StandardCharsets.UTF_8);
		String transcript = "小定，幫我取圖片";
		when(lineService.downloadContent("A1"))
			.thenReturn(new LineStorageService.LineContent(new ByteArrayInputStream(audio), "audio/mp4"));
		when(aiGateway.isConfigured()).thenReturn(true);
		when(aiGateway.transcribe(audio, "audio/mp4")).thenReturn(transcript);
		when(ticketStore.issue("C1", "reply-token")).thenReturn("ticket-2");
		when(aiGateway.analyzeAndExecute(transcript, "ticket-2", java.time.LocalDate.of(2026, 8, 11)))
			.thenReturn(new VoiceAiGateway.TaskDecision(false, "請補充部門編號與圖片日期。"));

		service.handleGroupAudio("A1", "C1", "reply-token");

		verify(ticketStore).discard("ticket-2");
		verify(lineService).replyText("reply-token", "請補充部門編號與圖片日期。");
	}
}
