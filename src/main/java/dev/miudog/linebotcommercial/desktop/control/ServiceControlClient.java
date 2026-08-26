package dev.miudog.linebotcommercial.desktop.control;

import dev.miudog.linebotcommercial.desktop.ipc.AuthenticatedLoopbackServer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 讓桌面 App 經受保護端點向本機 service 傳送有限控制命令。
 */
public final class ServiceControlClient {

	//#region 欄位

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

	private final ServiceControlEndpointRepository endpointRepository;

	//#endregion

	//#region 建構子

	// 方法：建立從指定端點儲存庫尋找目前 service 的控制 client。
	public ServiceControlClient(ServiceControlEndpointRepository endpointRepository) {
		this.endpointRepository = Objects.requireNonNull(endpointRepository, "Service 控制端點儲存庫不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：讀取目前受保護端點並傳送命令，任何失效狀態都回傳不可使用。
	public ServiceControlResponse request(ServiceControlCommand command) {
		Objects.requireNonNull(command, "Service 控制命令不可為 null");
		Optional<ServiceControlEndpoint> loadedEndpoint = endpointRepository.load();

		if (loadedEndpoint.isEmpty()) return ServiceControlResponse.UNAVAILABLE;

		ServiceControlEndpoint endpoint = loadedEndpoint.orElseThrow();
		ServiceControlResponse response = AuthenticatedLoopbackServer.request(
			endpoint.port(),
			endpoint.nonce(),
			command,
			ServiceControlResponse.class,
			REQUEST_TIMEOUT
		);

		return response == null ? ServiceControlResponse.UNAVAILABLE : response;
	}

	//#endregion
}
