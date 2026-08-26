package dev.miudog.linebotcommercial.desktop.cloudflare;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationField;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 協調可選 cloudflared child process，在 Spring 啟動時建立 Cloudflare Tunnel。
 */
public final class CloudflareConnector {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(CloudflareConnector.class);
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);

	private final CloudflareProcessControl process;
	private final CloudflareTunnelLease lease;
	private CloudflareAgentIdentity currentIdentity;

	//#endregion

	//#region 建構子

	// 方法：建立使用正式 cloudflared process 的 connector。
	public CloudflareConnector() {
		this(new CloudflareProcess(), CloudflareTunnelLease.platformDefault());
	}

	// 方法：建立可替換程序的 connector 供測試使用。
	CloudflareConnector(CloudflareProcessControl process) {
		this(process, CloudflareTunnelLease.disabled());
	}

	// 方法：建立可替換程序與同機租約的 connector 供整合測試使用。
	CloudflareConnector(
		CloudflareProcessControl process,
		CloudflareTunnelLease lease
	) {
		this.process = Objects.requireNonNull(process, "cloudflared process 不可為 null");
		this.lease = Objects.requireNonNull(lease, "Cloudflare Tunnel 租約不可為 null");
		this.currentIdentity = CloudflareAgentIdentity.empty();
	}

	//#endregion

	//#region 方法

	// 方法：未啟用時保持原設定；啟用時啟動 cloudflared 並回傳連線狀態。
	public synchronized CloudflareConnection start(
		AppConfiguration configuration,
		Duration startupTimeout
	) {
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
		Objects.requireNonNull(startupTimeout, "cloudflared 啟動 timeout 不可為 null");
		boolean enabled = Boolean.parseBoolean(configuration.value(AppConfigurationField.CLOUDFLARE_ENABLED));

		if (!enabled) {
			currentIdentity = CloudflareAgentIdentity.empty();

			return new CloudflareConnection(
				false,
				configuration.value(AppConfigurationField.PUBLIC_BASE_URL),
				configuration,
				CloudflareAgentIdentity.empty()
			);
		}

		if (startupTimeout.isNegative() || startupTimeout.isZero()) {
			throw new IllegalArgumentException("cloudflared 啟動 timeout 必須大於零");
		}

		String token = configuration.value(AppConfigurationField.CLOUDFLARE_TUNNEL_TOKEN);
		if (token.isBlank()) {
			throw new CloudflareConnectorException("Cloudflare Tunnel Token 不可為空白", null);
		}

		UUID expectedTunnelId;
		UUID tokenTunnelId;
		String expectedTunnelSource = configuration.value(AppConfigurationField.CLOUDFLARE_TUNNEL_ID);
		if (expectedTunnelSource.isBlank()) throw new CloudflareConnectorException("Cloudflare Tunnel ID 不可為空白", null);

		try {
			expectedTunnelId = UUID.fromString(expectedTunnelSource);
			tokenTunnelId = CloudflareTunnelToken.tunnelId(token);
		}
		catch (IllegalArgumentException exception) {
			throw new CloudflareConnectorException("Cloudflare Tunnel ID 或 Token 格式無效", exception);
		}

		if (!expectedTunnelId.equals(tokenTunnelId)) {
			throw new CloudflareConnectorException("Cloudflare Tunnel ID 與 Token 不符，請勿共用其他機器人的 Token", null);
		}

		Path agent;
		try {
			agent = CloudflareProcess.resolveAgent(configuration.value(AppConfigurationField.CLOUDFLARE_AGENT_PATH));
		}
		catch (IllegalArgumentException exception) {
			throw new CloudflareConnectorException(exception.getMessage(), exception);
		}

		if (!lease.acquire(expectedTunnelId, "LinebotCommercial")) {
			throw new CloudflareConnectorException("此電腦已有其他 App 使用相同 Cloudflare Tunnel，Commercial 與 Document 必須使用不同 Tunnel", null);
		}

		try {
			CloudflareProtocol protocol = CloudflareProtocol.parse(
				configuration.value(AppConfigurationField.CLOUDFLARE_PROTOCOL)
			);
			process.start(agent, token, protocol);

			// 外部函式：等待 cloudflared 官方 readiness endpoint，避免只以程序存活誤判連線成功。
			if (!process.awaitReady(startupTimeout)) {
				String diagnostic = process.diagnostic();
				String detail = diagnostic.isBlank() ? "未建立 Cloudflare Edge 連線" : diagnostic;

				throw new CloudflareConnectorException("cloudflared 尚未就緒：" + detail, null);
			}

			String publicUrl = configuration.value(AppConfigurationField.PUBLIC_BASE_URL);

			// 日誌：記錄 Cloudflare tunnel 已啟動。
			log.info("event=cloudflare_tunnel_ready publicUrl={}", publicUrl);

			CloudflareAgentIdentity identity = process.identity();
			currentIdentity = identity;

			// 日誌：記錄非機密 connector 身分，協助辨識是否在錯誤電腦形成 replica。
			log.info(
				"event=cloudflare_connector_identified tunnelId={} connectorId={} computerName={}",
				identity.tunnelId(),
				identity.connectorId(),
				identity.computerName()
			);

			return new CloudflareConnection(true, publicUrl, configuration, identity);
		}
		catch (RuntimeException exception) {
			process.stop(STOP_TIMEOUT);
			lease.release();

			if (exception instanceof CloudflareConnectorException safeException) throw safeException;

			throw new CloudflareConnectorException("cloudflared 啟動失敗，請檢查 agent 與 Token 設定", exception);
		}
	}

	// 方法：停止且只停止本 connector 所管理的 cloudflared child process。
	public synchronized void stop() {
		process.stop(STOP_TIMEOUT);
		lease.release();
		currentIdentity = CloudflareAgentIdentity.empty();
	}

	// 方法：取得目前 App 所管理且不包含 Token 的 Cloudflare connector 身分。
	public synchronized CloudflareAgentIdentity identity() {
		return currentIdentity;
	}

	//#endregion
}
