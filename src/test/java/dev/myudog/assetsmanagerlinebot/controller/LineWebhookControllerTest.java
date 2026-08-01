package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.CommandService;
import dev.myudog.assetsmanagerlinebot.service.ImageArchiveService;
import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineWebhookControllerTest {

	private static final String CHANNEL_SECRET = "test-channel-secret";

	@Mock
	CommandService commandService;

	@Mock
	ImageArchiveService archiveService;

	@Mock
	LineStorageService lineService;

	LineWebhookController controller;

	@BeforeEach
	void setUp() {
		controller = new LineWebhookController(commandService, archiveService, lineService);
		ReflectionTestUtils.setField(controller, "channelSecret", CHANNEL_SECRET);
	}

	@Test
	void forwardsLineImageSetMetadataToTheArchiveService() throws Exception {
		String payload = """
                {"events":[{
                  "type":"message",
                  "replyToken":"reply-token",
                  "source":{"type":"group","groupId":"C1","userId":"U1"},
                  "message":{
                    "id":"M2",
                    "type":"image",
                    "imageSet":{"id":"SET1","index":2,"total":3}
                  }
                }]}
                """;
		when(lineService.downloadContent("M2"))
			.thenReturn(new LineStorageService
			.LineContent(new ByteArrayInputStream("image".getBytes(StandardCharsets.UTF_8)), "image/jpeg"));

		var response = controller.handleWebhook(signature(payload), payload);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(archiveService)
			.stage(
			eq("M2"),
			eq("SET1"),
			eq(2),
			eq(3),
			eq("group"),
			eq("C1"),
			eq("U1"),
			any(InputStream.class),
			eq("image/jpeg")
		);
	}

	@Test
	void recordsTheImagePositionWhenLineContentDownloadFails() throws Exception {
		String payload = """
				{"events":[{
				  "type":"message",
				  "replyToken":"reply-token",
				  "source":{"type":"group","groupId":"C1","userId":"U1"},
				  "message":{
				    "id":"M1",
				    "type":"image",
				    "imageSet":{"id":"SET1","index":1,"total":3}
				  }
				}]}
				""";
		when(lineService.downloadContent("M1")).thenReturn(null);

		var response = controller.handleWebhook(signature(payload), payload);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(archiveService).recordFetchFailure("M1", "SET1", 1, 3, "C1");
	}

	// 方法：建立符合 LINE webhook 規格的 HMAC-SHA256 簽章。
	private static String signature(String payload) throws Exception {
		// 外部 API：使用 Java 密碼 API 建立測試用 HMAC。
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
	}
}
