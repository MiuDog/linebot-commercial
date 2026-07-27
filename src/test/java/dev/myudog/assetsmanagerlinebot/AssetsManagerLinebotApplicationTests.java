package dev.myudog.assetsmanagerlinebot;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"line.bot.channel-token=test-channel-token",
		"line.bot.channel-secret=test-channel-secret"
})
class AssetsManagerLinebotApplicationTests {

	@MockitoBean
	private MessagingApiClient messagingApiClient;

	@Test
	void contextLoads() {
	}

}
