package dev.miudog.linebotcommercial.desktop;

import dev.miudog.linebotcommercial.desktop.cloudflare.CloudflareConnection;
import dev.miudog.linebotcommercial.desktop.cloudflare.CloudflareConnector;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlCommand;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlEndpointRepository;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlHost;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlResponse;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlServer;
import dev.miudog.linebotcommercial.desktop.control.ServiceInstanceLock;
import dev.miudog.linebotcommercial.desktop.control.ServiceInstanceResource;
import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationRepository;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationValidator;
import dev.miudog.linebotcommercial.desktop.config.DesktopSpringProperties;
import dev.miudog.linebotcommercial.desktop.config.DpapiSecretStore;
import dev.miudog.linebotcommercial.desktop.ngrok.NgrokConnection;
import dev.miudog.linebotcommercial.desktop.ngrok.NgrokConnector;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在無桌面元件的程序中接管設定、Tunnel 與 Spring 後端生命週期。
 */
public final class ServiceApplication {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(ServiceApplication.class);

	private final Supplier<Optional<AppConfiguration>> configurationLoader;
	private final UnaryOperator<AppConfiguration> tunnelStarter;
	private final BiConsumer<AppConfiguration, String[]> backendStarter;
	private final Runnable resourceStopper;
	private final Consumer<Runnable> shutdownHookRegistrar;
	private final Supplier<Runnable> emergencyShutdownArmer;
	private final ServiceControlHost controlHost;
	private final ServiceInstanceResource instanceResource;
	private String[] startupArguments;
	private boolean instanceAcquired;
	private boolean resourcesOwned;
	private boolean running;
	private boolean shutdown;

	//#endregion

	//#region 建構子

	// 方法：建立可替換設定、Tunnel、後端與程序停止鉤子的 service host。
	ServiceApplication(
		Supplier<Optional<AppConfiguration>> configurationLoader,
		UnaryOperator<AppConfiguration> tunnelStarter,
		BiConsumer<AppConfiguration, String[]> backendStarter,
		Runnable resourceStopper,
		Consumer<Runnable> shutdownHookRegistrar,
		ServiceControlHost controlHost,
		ServiceInstanceResource instanceResource
	) {
		this(
			configurationLoader,
			tunnelStarter,
			backendStarter,
			resourceStopper,
			shutdownHookRegistrar,
			() -> () -> {
			},
			controlHost,
			instanceResource
		);
	}

	// 方法：建立可注入緊急停止保護的 service host，供正式程序與停止逾時測試使用。
	ServiceApplication(
		Supplier<Optional<AppConfiguration>> configurationLoader,
		UnaryOperator<AppConfiguration> tunnelStarter,
		BiConsumer<AppConfiguration, String[]> backendStarter,
		Runnable resourceStopper,
		Consumer<Runnable> shutdownHookRegistrar,
		Supplier<Runnable> emergencyShutdownArmer,
		ServiceControlHost controlHost,
		ServiceInstanceResource instanceResource
	) {
		this.configurationLoader = Objects.requireNonNull(configurationLoader, "設定載入器不可為 null");
		this.tunnelStarter = Objects.requireNonNull(tunnelStarter, "Tunnel 啟動器不可為 null");
		this.backendStarter = Objects.requireNonNull(backendStarter, "後端啟動器不可為 null");
		this.resourceStopper = Objects.requireNonNull(resourceStopper, "資源停止器不可為 null");
		this.shutdownHookRegistrar = Objects.requireNonNull(shutdownHookRegistrar, "停止鉤子註冊器不可為 null");
		this.emergencyShutdownArmer = Objects.requireNonNull(emergencyShutdownArmer, "緊急停止保護不可為 null");
		this.controlHost = Objects.requireNonNull(controlHost, "Service 控制通道不可為 null");
		this.instanceResource = Objects.requireNonNull(instanceResource, "Service 執行個體資源不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：建立使用目前使用者 DPAPI 設定與正式 Tunnel、Spring 後端的 service host。
	public static ServiceApplication createDefault() {
		Path localAppData = localAppData();
		Path configurationRoot = AppConfiguration.configurationRoot(localAppData);
		AppConfiguration defaults = AppConfiguration.defaults(localAppData);
		DpapiSecretStore secretStore = new DpapiSecretStore();
		AppConfigurationRepository repository = new AppConfigurationRepository(
			configurationRoot,
			defaults,
			secretStore,
			new AppConfigurationValidator()
		);
		NgrokConnector ngrokConnector = new NgrokConnector();
		CloudflareConnector cloudflareConnector = new CloudflareConnector();
		SpringDesktopBackend backend = new SpringDesktopBackend();
		DesktopSpringProperties properties = new DesktopSpringProperties();
		ServiceControlServer controlServer = new ServiceControlServer(
			new ServiceControlEndpointRepository(configurationRoot, secretStore)
		);

		return new ServiceApplication(
			repository::load,
			configuration -> prepareTunnels(configuration, ngrokConnector, cloudflareConnector),
			(configuration, arguments) -> backend.start(properties.from(configuration), arguments),
			() -> stopResources(cloudflareConnector::stop, ngrokConnector::stop, backend::stop),
			shutdownAction -> {
				Thread shutdownHook = Thread.ofPlatform()
					.name("linebot-commercial-service-shutdown")
					.unstarted(shutdownAction);

				// 外部 JVM：註冊程序停止鉤子，確保 SCM、關機與一般終止都釋放 child process。
				Runtime.getRuntime().addShutdownHook(shutdownHook);
			},
			ServiceApplication::armEmergencyShutdown,
			controlServer,
			new ServiceInstanceLock(configurationRoot)
		);
	}

	// 方法：載入已保存設定並依序啟動 Tunnel 與 Spring 後端。
	public synchronized void start(String[] arguments) {
		if (running || shutdown) throw new IllegalStateException("Service host 已啟動或停止");

		Objects.requireNonNull(arguments, "Service 啟動參數不可為 null");

		// 外部檔案鎖：任何 Tunnel 或 Spring 建立前先取得跨程序單一執行個體資格。
		if (!instanceResource.acquire()) {
			shutdown = true;

			// 日誌：已有背景 service 時讓重複 launcher 安全結束，不建立第二組 Tunnel。
			log.info("event=service_host_duplicate_ignored");

			return;
		}

		instanceAcquired = true;

		// 外部檔案與 DPAPI：只載入目前使用者已保存的設定，不從命令列接收祕密。
		AppConfiguration configuration;

		try {
			configuration = configurationLoader.get()
				.orElseThrow(() -> new IllegalStateException("尚未完成 App 設定，Service 不可啟動"));
		}
		catch (RuntimeException exception) {
			releaseInstanceResource();

			throw exception;
		}
		startupArguments = arguments.clone();

		// 外部 JVM：在建立 child process 前先註冊完整停止路徑。
		shutdownHookRegistrar.accept(this::shutdown);

		// 日誌：標記 headless service 進入啟動流程，不輸出設定值或 Token。
		log.info("event=service_host_starting");

		try {
			startResources(configuration);

			// 本機控制通道：資源就緒後才發布受保護端點，避免 client 讀到尚未完成的狀態。
			controlHost.start(this::handleControlCommand);

			// 日誌：Spring 已完成啟動，service host 可開始接受請求。
			log.info("event=service_host_ready");
		}
		catch (RuntimeException exception) {
			// 日誌：記錄啟動失敗類型，避免 Tunnel 或 Token 細節進入 Log。
			log.error("event=service_host_start_failed errorType={}", exception.getClass().getSimpleName());
			shutdown = true;
			stopControlHost();
			stopOwnedResources();
			releaseInstanceResource();

			throw exception;
		}
	}

	// 方法：停止舊資源、重新讀取最新設定並再次啟動 Tunnel 與 Spring。
	public synchronized void restart() {
		if (shutdown || startupArguments == null) throw new IllegalStateException("Service host 尚未啟動或已停止");

		running = false;

		// 日誌：標記 service host 開始以最新保存設定重新啟動。
		log.info("event=service_host_restarting");
		stopOwnedResources();
		releaseInstanceResource();

		// 外部檔案與 DPAPI：重新讀取桌面 App 最新保存的設定，不沿用舊記憶體值。
		AppConfiguration configuration = configurationLoader.get()
			.orElseThrow(() -> new IllegalStateException("找不到已保存 App 設定，Service 無法重新啟動"));

		try {
			startResources(configuration);

			// 日誌：確認 Tunnel 與 Spring 已依最新設定恢復服務。
			log.info("event=service_host_restarted");
		}
		catch (RuntimeException exception) {
			stopOwnedResources();

			// 日誌：記錄重啟失敗類型，保留後續再次重啟能力且不輸出設定內容。
			log.error("event=service_host_restart_failed errorType={}", exception.getClass().getSimpleName());

			throw exception;
		}
	}

	// 方法：停止 ingress、child process 與 Spring，且重複呼叫時不重複釋放。
	public synchronized void shutdown() {
		if (shutdown) return;

		shutdown = true;
		running = false;
		Runnable cancelEmergencyShutdown = emergencyShutdownArmer.get();

		try {
			// 日誌：標記 service host 開始停止受管理資源。
			log.info("event=service_host_stopping");
			stopControlHost();

			stopOwnedResources();
			releaseInstanceResource();

			// 日誌：確認 service host 已完成資源釋放。
			log.info("event=service_host_stopped");
		}
		finally {
			cancelEmergencyShutdown.run();
		}
	}

	// 方法：安排只作用於目前 service JVM 的停止逾時保護，正常清理完成時可取消。
	private static Runnable armEmergencyShutdown() {
		AtomicBoolean cancelled = new AtomicBoolean();

		// 外部 JVM：以 daemon watchdog 限制關閉流程，避免 Spring 或 Tunnel 卡住而永久占用安裝檔。
		Thread watchdog = Thread.ofPlatform()
			.daemon()
			.name("linebot-commercial-service-stop-watchdog")
			.start(() -> {
				try {
					Thread.sleep(Duration.ofSeconds(15));
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();

					return;
				}

				if (cancelled.get()) return;

				// 日誌：正常資源清理逾時，將只終止目前 service JVM 以解除安裝檔占用。
				log.error("event=service_host_stop_timeout action=halt_current_service");

				// 外部 JVM：只終止目前 service 程序，不依名稱影響其他程序或產品。
				Runtime.getRuntime().halt(0);
			});

		return () -> {
			cancelled.set(true);
			watchdog.interrupt();
		};
	}

	// 方法：回傳 service host 是否已完成所有啟動階段。
	public synchronized boolean running() {
		return running;
	}

	// 方法：執行固定 service 控制命令，失敗時只回傳通用結果且不洩漏設定內容。
	private synchronized ServiceControlResponse handleControlCommand(ServiceControlCommand command) {
		try {
			return switch (command) {
				case STATUS -> running ? ServiceControlResponse.RUNNING : ServiceControlResponse.STOPPED;
				case RESTART -> {
					restart();

					yield ServiceControlResponse.RESTARTED;
				}
				case SHUTDOWN -> {
					shutdown();

					yield ServiceControlResponse.SHUTTING_DOWN;
				}
			};
		}
		catch (RuntimeException exception) {
			// 日誌：記錄控制命令與失敗類型，不回傳內部例外訊息或設定內容。
			log.error("event=service_control_command_failed command={} errorType={}",
				command,
				exception.getClass().getSimpleName()
			);

			return ServiceControlResponse.FAILED;
		}
	}

	// 方法：依序建立 Tunnel 與 Spring，並標記由 service host 負責後續清理。
	private void startResources(AppConfiguration configuration) {
		resourcesOwned = true;

		// 步驟一：先建立對外入口，取得必要的 runtime public URL。
		AppConfiguration preparedConfiguration = tunnelStarter.apply(configuration);

		// 步驟二：以準備完成的設定啟動 Spring、資料庫與 Webhook。
		backendStarter.accept(preparedConfiguration, startupArguments.clone());
		running = true;
	}

	// 方法：只釋放目前仍由 host 擁有的資源，失敗後仍阻止重複停止同一批資源。
	private void stopOwnedResources() {
		if (!resourcesOwned) return;

		resourcesOwned = false;

		try {
			resourceStopper.run();
		}
		catch (RuntimeException exception) {
			// 日誌：停止失敗只記錄類型，避免程序終止或重啟時洩漏設定內容。
			log.error("event=service_host_stop_failed errorType={}", exception.getClass().getSimpleName());
		}
	}

	// 方法：撤銷 service 控制端點並停止接收命令，關閉失敗不阻止其餘資源釋放。
	private void stopControlHost() {
		try {
			controlHost.close();
		}
		catch (RuntimeException exception) {
			// 日誌：記錄控制通道停止失敗類型，不包含 Port、nonce 或端點內容。
			log.error("event=service_control_stop_failed errorType={}", exception.getClass().getSimpleName());
		}
	}

	// 方法：只釋放已取得的背景 service 單一執行個體資格。
	private void releaseInstanceResource() {
		if (!instanceAcquired) return;

		instanceAcquired = false;
		instanceResource.close();
	}

	// 方法：依序啟動可選 ngrok 與 Cloudflare，將 runtime URL 傳給 Spring。
	private static AppConfiguration prepareTunnels(
		AppConfiguration configuration,
		NgrokConnector ngrokConnector,
		CloudflareConnector cloudflareConnector
	) {
		// 外部網路程序：先建立 ngrok，再以更新後設定建立 Cloudflare Tunnel。
		NgrokConnection ngrokConnection = ngrokConnector.start(configuration, Duration.ofSeconds(15));
		CloudflareConnection cloudflareConnection = cloudflareConnector.start(
			ngrokConnection.configuration(),
			Duration.ofSeconds(10)
		);

		return cloudflareConnection.configuration();
	}

	// 方法：先停止公開入口，再關閉 Spring 與本機資料資源。
	static void stopResources(
		Runnable cloudflareStopper,
		Runnable ngrokStopper,
		Runnable backendStopper
	) {
		stopResource("cloudflare", cloudflareStopper);
		stopResource("ngrok", ngrokStopper);
		stopResource("spring", backendStopper);
	}

	// 方法：隔離單一停止錯誤，確保後續受管理資源仍能釋放。
	private static void stopResource(
		String resource,
		Runnable stopper
	) {
		try {
			stopper.run();
		}
		catch (RuntimeException exception) {
			// 日誌：記錄固定資源類型與錯誤類型，不輸出設定或 child process 參數。
			log.error("event=service_resource_stop_failed resource={} errorType={}",
				resource,
				exception.getClass().getSimpleName()
			);
		}
	}

	// 方法：取得目前使用者 Local AppData，缺少環境變數時使用 Windows 標準後備路徑。
	private static Path localAppData() {
		String configuredPath = System.getenv("LOCALAPPDATA");
		if (configuredPath != null && !configuredPath.isBlank()) return Path.of(configuredPath);

		return Path.of(System.getProperty("user.home"), "AppData", "Local");
	}

	//#endregion
}
