package dev.miudog.linebotcommercial.desktop.control;

import dev.miudog.linebotcommercial.desktop.ipc.AuthenticatedLoopbackServer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建立 service 專用認證通道並安全發布目前端點。
 */
public final class ServiceControlServer implements ServiceControlHost {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(ServiceControlServer.class);

	private final ServiceControlEndpointRepository endpointRepository;
	private AuthenticatedLoopbackServer<ServiceControlCommand, ServiceControlResponse> server;

	//#endregion

	//#region 建構子

	// 方法：建立使用指定受保護端點儲存庫的 service 控制伺服器。
	public ServiceControlServer(ServiceControlEndpointRepository endpointRepository) {
		this.endpointRepository = Objects.requireNonNull(endpointRepository, "Service 控制端點儲存庫不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：產生每次啟動 nonce、綁定 loopback 並發布受保護端點。
	@Override
	public synchronized void start(Function<ServiceControlCommand, ServiceControlResponse> commandHandler) {
		if (server != null) throw new IllegalStateException("Service 控制通道已啟動");

		Objects.requireNonNull(commandHandler, "Service 控制命令處理器不可為 null");
		byte[] nonceBytes = new byte[32];

		try {
			// 外部密碼學函式：使用安全亂數建立每次 service 啟動都不同的控制 nonce。
			new SecureRandom().nextBytes(nonceBytes);
			String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
			server = new AuthenticatedLoopbackServer<>(
				"service-control",
				nonce,
				ServiceControlCommand.class,
				ServiceControlResponse.REJECTED,
				commandHandler
			);
			server.start();
			endpointRepository.publish(new ServiceControlEndpoint(server.port(), nonce));

			// 日誌：記錄控制通道已可用與 Port，不輸出 nonce 或端點檔內容。
			log.info("event=service_control_started port={}", server.port());
		}
		catch (RuntimeException exception) {
			close();

			throw exception;
		}
		finally {
			Arrays.fill(nonceBytes, (byte) 0);
		}
	}

	// 方法：先撤銷端點再關閉 loopback 通道，避免新 client 取得即將失效的連線資料。
	@Override
	public synchronized void close() {
		try {
			endpointRepository.clear();
		}
		catch (RuntimeException exception) {
			// 日誌：記錄端點撤銷失敗類型，不輸出端點或認證內容。
			log.warn("event=service_control_endpoint_clear_failed errorType={}",
				exception.getClass().getSimpleName()
			);
		}

		if (server == null) return;

		server.close();
		server = null;

		// 日誌：確認 service 控制通道已停止。
		log.info("event=service_control_stopped");
	}

	//#endregion
}
