package dev.myudog.assetsmanagerlinebot.service.voice;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存短效、單次使用的 MCP 執行票券，避免模型直接持有 LINE 群組及回覆權杖。
 */
@Service
public class VoiceMcpTicketStore {

	private static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

	private final ConcurrentHashMap<String, ExecutionContext> tickets = new ConcurrentHashMap<>();
	private final Clock clock;
	private final Duration ttl;

	// 方法：以正式時鐘及兩分鐘有效期初始化票券庫。
	public VoiceMcpTicketStore() {
		this(Clock.systemUTC(), DEFAULT_TTL);
	}

	// 方法：以指定時鐘及有效期初始化測試用票券庫。
	VoiceMcpTicketStore(Clock clock, Duration ttl) {
		this.clock = clock;
		this.ttl = ttl;
	}

	// 方法：簽發僅能使用一次的 MCP 執行票券。
	public String issue(String sourceId, String replyToken) {
		String ticket = UUID.randomUUID().toString().replace("-", "");
		tickets.put(
			ticket,
			new ExecutionContext(sourceId, replyToken, clock.instant().plus(ttl))
		);
		return ticket;
	}

	// 方法：原子地取出並刪除票券，過期票券不予執行。
	public Optional<ExecutionContext> consume(String ticket) {
		if (ticket == null || ticket.isBlank()) return Optional.empty();

		ExecutionContext context = tickets.remove(ticket);
		if (context == null || !context.expiresAt().isAfter(clock.instant())) return Optional.empty();

		return Optional.of(context);
	}

	// 方法：AI 未執行 MCP 時主動撤銷票券。
	public void discard(String ticket) {
		if (ticket != null) tickets.remove(ticket);
	}

	public record ExecutionContext(
		String sourceId,
		String replyToken,
		Instant expiresAt
	) {}
}
