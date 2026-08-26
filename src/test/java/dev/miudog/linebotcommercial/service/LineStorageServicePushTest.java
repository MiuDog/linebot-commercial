package dev.miudog.linebotcommercial.service;

import dev.miudog.linebotcommercial.observability.NetworkObservationLogger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineStorageServicePushTest {

	// 方法：驗證所有 LINE 發送請求套用客戶設定的單次逾時。
	@Test
	void appliesConfiguredTimeoutToLineRequests() throws Exception {
		NetworkObservationLogger observations = mock(NetworkObservationLogger.class);
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(observations.started("LINE", "reply_message")).thenReturn(30L);
		when(response.statusCode()).thenReturn(200);
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		LineStorageService service = new LineStorageService(
			observations,
			http,
			new ObjectMapper(),
			"channel-token-for-test",
			Duration.ofSeconds(7)
		);

		service.replyText("reply-token", "回覆");

		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
		assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(7));
	}

	@Test
	void exposesAStableExceptionWhenLineRejectsAReply() throws Exception {
		NetworkObservationLogger observations = mock(NetworkObservationLogger.class);
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(observations.started("LINE", "reply_message")).thenReturn(20L);
		when(response.statusCode()).thenReturn(500);
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		LineStorageService service = new LineStorageService(
			observations,
			http,
			new ObjectMapper(),
			"channel-token-for-test"
		);

		assertThatThrownBy(() -> service.replyText("reply-token", "回覆"))
			.isInstanceOf(LineStorageService.LineMessagingException.class)
			.hasMessage("LINE reply rejected");
		verify(observations).completed("LINE", "reply_message", 20L, 500);
	}

	@Test
	void sendsFlexThroughTheLinePushEndpointAndReadsTheProviderMessageId() throws Exception {
		NetworkObservationLogger observations = mock(NetworkObservationLogger.class);
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(observations.started("LINE", "push_message")).thenReturn(10L);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn("{\"sentMessages\":[{\"id\":\"provider-message-1\"}]}");
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		LineStorageService service = new LineStorageService(
			observations,
			http,
			new ObjectMapper(),
			"channel-token-for-test"
		);

		LineStorageService.LinePushReceipt receipt = service.push(
			"U-line-user",
			List.of(Map.of("type", "flex", "altText", "報價單已完成")),
			UUID.fromString("2ee96a20-63ea-3f67-b28b-0a2d22ed1730")
		);

		assertThat(receipt.providerMessageId()).isEqualTo("provider-message-1");
		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
		assertThat(request.getValue().uri().toString())
			.isEqualTo("https://api.line.me/v2/bot/message/push");
		assertThat(request.getValue().headers().firstValue("Authorization"))
			.contains("Bearer channel-token-for-test");
		assertThat(request.getValue().headers().firstValue("X-Line-Retry-Key"))
			.contains("2ee96a20-63ea-3f67-b28b-0a2d22ed1730");
		verify(observations).completed("LINE", "push_message", 10L, 200);
	}
}
