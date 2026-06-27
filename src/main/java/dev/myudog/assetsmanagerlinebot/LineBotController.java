package dev.myudog.assetsmanagerlinebot;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.web.argument.annotation.LineBotMessages;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LineBotController {

	private final MessagingApiClient messagingApiClient;

	LineBotController(MessagingApiClient messagingApiClient) {
		this.messagingApiClient = messagingApiClient;
	}

	/**
	 * LINE Webhook 接收端點
	 * 使用 @LineBotMessages 自動將 LINE 事件注入，並驗證簽名
	 */
	@PostMapping("/callback")
	public void callback(@LineBotMessages List<Event> events) {
		for (Event event : events) {
			// 負責處理文字訊息的事件
			if (event instanceof MessageEvent messageEvent &&
				messageEvent.message() instanceof TextMessageContent textMessage) {

				// 取得使用者傳過來的文字
				String userMessage = textMessage.text();
				// 取得回覆用的 replyToken
				String replyToken = messageEvent.replyToken();

				// 建立一個準備回傳的文字訊息（鸚鵡學舌：你傳什麼，我就回什麼）
				TextMessage responseMessage = new TextMessage("你說了：" + userMessage);

				// 透過 LINE 官方 Client 端將訊息送回給 LINE 伺服器
				messagingApiClient.replyMessage(new ReplyMessageRequest(
						replyToken,
						List.of(responseMessage),
						false
				));
			}
			else {
				// 處理其他未預期事件（例如：被加入群組、加入好友等），防止程式報錯
				System.out.println("收到未處理事件: " + event);
			}
		}
	}
}
