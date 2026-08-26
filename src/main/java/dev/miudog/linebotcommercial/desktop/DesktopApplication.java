package dev.miudog.linebotcommercial.desktop;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationRepository;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationField;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationValidator;
import dev.miudog.linebotcommercial.desktop.config.ConfigurationWizard;
import dev.miudog.linebotcommercial.desktop.config.ConfigurationWizardModel;
import dev.miudog.linebotcommercial.desktop.config.ConfigurationWizardResult;
import dev.miudog.linebotcommercial.desktop.config.DpapiSecretStore;
import dev.miudog.linebotcommercial.desktop.control.PackagedServiceLauncher;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlClient;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlCommand;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlEndpointRepository;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlResponse;
import dev.miudog.linebotcommercial.desktop.control.ServiceProcessSupervisor;
import dev.miudog.linebotcommercial.desktop.diagnostic.ConnectionDiagnosticReport;
import dev.miudog.linebotcommercial.desktop.diagnostic.ConnectionDiagnosticService;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在 Windows 桌面模式下先完成安全設定，再啟動既有 Spring 後端。
 */
public final class DesktopApplication {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(DesktopApplication.class);
	private static final String DESKTOP_ARGUMENT = "--app.desktop.enabled=true";
	private static final String DESKTOP_PROPERTY = "app.desktop.enabled";
	private static final int SERVICE_READINESS_ATTEMPTS = 80;
	private static final Duration SERVICE_READINESS_INTERVAL = Duration.ofMillis(250);
	private static volatile DesktopApplication activeApplication;

	private final Function<DesktopIpcCommand, SingleInstanceResult> instanceGate;
	private final Runnable uiBootstrap;
	private final Supplier<Optional<AppConfiguration>> configurationLoader;
	private final Function<AppConfiguration, ConfigurationWizardResult> firstConfigurationEditor;
	private final AppConfiguration defaults;
	private final BiConsumer<AppConfiguration, String[]> starter;
	private final Runnable stopper;
	private final AutoCloseable instanceResource;

	//#endregion

	//#region 建構子

	// 方法：建立可替換設定來源、精靈與後端啟動器的桌面 bootstrap。
	DesktopApplication(
		Supplier<Optional<AppConfiguration>> configurationLoader,
		Function<AppConfiguration, ConfigurationWizardResult> firstConfigurationEditor,
		AppConfiguration defaults,
		BiConsumer<AppConfiguration, String[]> starter
	) {
		this(
			command -> SingleInstanceResult.PRIMARY,
			() -> {
			},
			configurationLoader,
			firstConfigurationEditor,
			defaults,
			starter,
			() -> {
			},
			() -> {
			}
		);
	}

	// 方法：建立包含單一執行個體與完整停止資源的正式桌面 bootstrap。
	DesktopApplication(
		Function<DesktopIpcCommand, SingleInstanceResult> instanceGate,
		Runnable uiBootstrap,
		Supplier<Optional<AppConfiguration>> configurationLoader,
		Function<AppConfiguration, ConfigurationWizardResult> firstConfigurationEditor,
		AppConfiguration defaults,
		BiConsumer<AppConfiguration, String[]> starter,
		Runnable stopper,
		AutoCloseable instanceResource
	) {
		this.instanceGate = Objects.requireNonNull(instanceGate, "單一執行個體閘門不可為 null");
		this.uiBootstrap = Objects.requireNonNull(uiBootstrap, "UI 前置啟動器不可為 null");
		this.configurationLoader = Objects.requireNonNull(configurationLoader, "設定載入器不可為 null");
		this.firstConfigurationEditor = Objects.requireNonNull(
			firstConfigurationEditor,
			"首次設定精靈不可為 null"
		);
		this.defaults = Objects.requireNonNull(defaults, "預設設定不可為 null");
		this.starter = Objects.requireNonNull(starter, "後端啟動器不可為 null");
		this.stopper = Objects.requireNonNull(stopper, "後端停止器不可為 null");
		this.instanceResource = Objects.requireNonNull(instanceResource, "執行個體資源不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：建立使用 Local AppData、DPAPI 與獨立背景 service 的桌面控制器。
	public static DesktopApplication createDefault() {
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
		ServiceControlClient controlClient = new ServiceControlClient(
			new ServiceControlEndpointRepository(configurationRoot, secretStore)
		);
		ServiceProcessSupervisor serviceSupervisor = new ServiceProcessSupervisor(
			controlClient,
			PackagedServiceLauncher.fromCurrentProcess("LinebotCommercialService.exe")
		);
		DesktopWindowModel windowModel = new DesktopWindowModel(
			Integer.parseInt(defaults.value(AppConfigurationField.SERVER_PORT))
		);
		DesktopUi desktopUi = new DesktopUi(windowModel);
		ConnectionDiagnosticService connectionDiagnosticService = new ConnectionDiagnosticService();
		AtomicReference<AppConfiguration> activeConfiguration = new AtomicReference<>();
		AtomicReference<DesktopApplication> applicationReference = new AtomicReference<>();
		AtomicReference<DesktopActions> actionsReference = new AtomicReference<>();
		SingleInstanceCoordinator instanceCoordinator = new SingleInstanceCoordinator(configurationRoot);
		DesktopActions actions = new DesktopActions(
			desktopUi::show,
			() -> runInBackground(
				"desktop-settings",
				() -> editServiceConfiguration(
					repository,
					controlClient,
					desktopUi,
					windowModel,
					activeConfiguration
				)
			),
			() -> runInBackground(
				"desktop-restart",
				() -> restartService(
					repository,
					controlClient,
					desktopUi,
					windowModel,
					activeConfiguration
				)
			),
			(target, completion) -> runInBackground(
				"desktop-connection-diagnostic",
				() -> {
					AppConfiguration configuration = Objects.requireNonNullElse(
						activeConfiguration.get(),
						defaults
					);
					ConnectionDiagnosticReport report = connectionDiagnosticService.run(configuration, target);

					onEdt(() -> completion.accept(report));
				}
			),
			() -> runInBackground("desktop-exit", () -> applicationReference.get().shutdown())
		);
		actionsReference.set(actions);
		DesktopApplication application = new DesktopApplication(
			command -> instanceCoordinator.acquireOrNotify(
				command,
				receivedCommand -> handleIpcCommand(receivedCommand, actionsReference.get())
			),
			() -> {
				// 以預設資料根目錄先開視窗與系統匣；設定載入後再由 followLogDirectory 換成實際路徑。
				desktopUi.start(actionsReference.get(), logDirectory(defaults));
				onEdt(() -> windowModel.updateStatus(DesktopStatus.STARTING));
			},
			repository::load,
			configuration -> showFirstConfiguration(configuration, repository),
			defaults,
			(configuration, arguments) -> {
				activeConfiguration.set(configuration);
				onEdt(() -> windowModel.updatePort(Integer.parseInt(configuration.value(AppConfigurationField.SERVER_PORT))));
				desktopUi.start(actionsReference.get(), logDirectory(configuration));
				updateWindowConfiguration(windowModel, configuration);
				onEdt(() -> windowModel.updateStatus(DesktopStatus.STARTING));

				// 背景程序：沿用已運行 service，或從安裝目錄啟動一次並有限等待就緒。
				boolean serviceReady = serviceSupervisor.ensureRunning(
					SERVICE_READINESS_ATTEMPTS,
					SERVICE_READINESS_INTERVAL
				);
				applyServiceResponse(
					windowModel,
					serviceReady ? ServiceControlResponse.RUNNING : ServiceControlResponse.UNAVAILABLE
				);

				if (requestedCommand(arguments) == DesktopIpcCommand.OPEN_SETTINGS) {
					actionsReference.get().settings().run();
				}
			},
			() -> {
				ConfigurationWizard.closeActive();
				onEdt(() -> windowModel.updateStatus(DesktopStatus.STOPPING));

				// 本機控制通道：明確結束 App 時要求背景 service 釋放 Tunnel 與 Spring。
				controlClient.request(ServiceControlCommand.SHUTDOWN);
				onEdt(() -> windowModel.updateStatus(DesktopStatus.STOPPED));
				desktopUi.close();
			},
			instanceCoordinator
		);
		applicationReference.set(application);

		return application;
	}

	// 方法：判斷命令列是否明確要求 Windows 桌面模式。
	public static boolean desktopModeRequested(String[] arguments) {
		return desktopModeRequested(arguments, System.getProperty(DESKTOP_PROPERTY));
	}

	// 方法：同時判斷固定 JVM property 與相容命令列，避免額外參數取代 launcher 預設值。
	static boolean desktopModeRequested(
		String[] arguments,
		String desktopProperty
	) {
		if (Boolean.parseBoolean(desktopProperty)) return true;

		for (String argument : arguments) {
			if (DESKTOP_ARGUMENT.equalsIgnoreCase(argument)) return true;
		}

		return false;
	}

	// 方法：載入既有設定或執行首次精靈，成功後才啟動 Spring 後端。
	public boolean start(String[] arguments) {
		DesktopIpcCommand requestedCommand = requestedCommand(arguments);
		SingleInstanceResult instanceResult = instanceGate.apply(requestedCommand);

		if (instanceResult == SingleInstanceResult.NOTIFIED) {
			closeInstanceResource();

			return false;
		}

		if (instanceResult == SingleInstanceResult.FAILED) {
			throw new IllegalStateException("無法通知已執行的桌面 App");
		}

		if (requestedCommand == DesktopIpcCommand.SHUTDOWN) {
			shutdown();

			return false;
		}

		// 主視窗與系統匣必須在設定之前就建立：首次設定精靈是 JDialog，本身不會有工作列
		// 按鈕，若此時 App 在系統上毫無存在感，使用者找不到它也無法重新開啟設定。
		uiBootstrap.run();

		Optional<AppConfiguration> loaded = configurationLoader.get();
		AppConfiguration configuration;

		if (loaded.isPresent()) {
			configuration = loaded.orElseThrow();
		}
		else {
			ConfigurationWizardResult result = firstConfigurationEditor.apply(defaults);

			if (!result.saved()) {
				// 日誌：記錄首次設定取消，確認 Spring 後端未啟動。
				log.info("event=desktop_first_configuration_cancelled");

				// 取消時 UI 已經建立，必須走完整停止流程，否則系統匣與 EDT 會留住程序。
				shutdown();

				return false;
			}

			configuration = result.configuration();
		}

		activeApplication = this;
		starter.accept(configuration, arguments.clone());

		return true;
	}

	// 方法：依序停止 Spring 後端與單一執行個體資源，供明確結束操作使用。
	public synchronized void shutdown() {
		try {
			stopper.run();
		}
		finally {
			closeInstanceResource();
			activeApplication = null;
		}
	}

	// 方法：釋放單一執行個體資源，錯誤只記錄安全的例外類型。
	private void closeInstanceResource() {
		try {
			instanceResource.close();
		}
		catch (Exception exception) {
			// 日誌：記錄桌面執行個體資源釋放失敗，不包含連線認證資料。
			log.warn("event=desktop_instance_resource_close_failed errorType={}",
				exception.getClass().getSimpleName()
			);
		}
	}

	// 方法：把已驗證的第二次開啟命令轉交主視窗或設定操作。
	private static void handleIpcCommand(
		DesktopIpcCommand command,
		DesktopActions actions
	) {
		// 日誌：記錄已驗證的桌面控制命令，供後續視窗事件整合追蹤。
		log.info("event=desktop_ipc_command_received command={}", command);

		switch (command) {
			case SHOW_WINDOW -> actions.show().run();
			case OPEN_SETTINGS -> actions.settings().run();
			case SHUTDOWN -> actions.exit().run();
		}
	}

	// 方法：依安裝器或第二次開啟參數選擇有限 IPC 命令。
	private static DesktopIpcCommand requestedCommand(String[] arguments) {
		for (String argument : arguments) {
			if ("--shutdown".equalsIgnoreCase(argument)) return DesktopIpcCommand.SHUTDOWN;

			// 安裝完成後由安裝器帶入；首次啟動本來就會進入設定精靈，因此等同一般開啟。
			if ("--configure-first-run".equalsIgnoreCase(argument)) return DesktopIpcCommand.SHOW_WINDOW;

			if ("--configure".equalsIgnoreCase(argument)) return DesktopIpcCommand.OPEN_SETTINGS;
		}

		return DesktopIpcCommand.SHOW_WINDOW;
	}

	// 方法：顯示設定精靈，保存後要求背景 service 重新載入最新設定。
	private static void editServiceConfiguration(
		AppConfigurationRepository repository,
		ServiceControlClient controlClient,
		DesktopUi desktopUi,
		DesktopWindowModel windowModel,
		AtomicReference<AppConfiguration> activeConfiguration
	) {
		AppConfiguration current = repository.load().orElse(activeConfiguration.get());
		ConfigurationWizardResult result = showConfiguration(current, repository, false);

		if (!result.saved()) return;

		activeConfiguration.set(result.configuration());
		desktopUi.followLogDirectory(logDirectory(result.configuration()));
		onEdt(() -> windowModel.updatePort(Integer.parseInt(
			result.configuration().value(AppConfigurationField.SERVER_PORT)
		)));
		updateWindowConfiguration(windowModel, result.configuration());
		onEdt(() -> windowModel.updateStatus(DesktopStatus.STARTING));

		// 本機控制通道：設定已由 repository 安全保存後，通知 service 停止舊資源並重新載入。
		ServiceControlResponse response = controlClient.request(ServiceControlCommand.RESTART);
		applyServiceResponse(windowModel, response);
	}

	// 方法：要求背景 service 依 repository 最新設定重新啟動，不在桌面程序建立 Spring 或 Tunnel。
	private static void restartService(
		AppConfigurationRepository repository,
		ServiceControlClient controlClient,
		DesktopUi desktopUi,
		DesktopWindowModel windowModel,
		AtomicReference<AppConfiguration> activeConfiguration
	) {
		AppConfiguration configuration = repository.load().orElse(activeConfiguration.get());
		activeConfiguration.set(configuration);
		desktopUi.followLogDirectory(logDirectory(configuration));
		onEdt(() -> windowModel.updatePort(Integer.parseInt(
			configuration.value(AppConfigurationField.SERVER_PORT)
		)));
		updateWindowConfiguration(windowModel, configuration);
		onEdt(() -> windowModel.updateStatus(DesktopStatus.STARTING));

		// 本機控制通道：只傳送固定列舉命令，service 自行重新讀取 DPAPI 設定。
		ServiceControlResponse response = controlClient.request(ServiceControlCommand.RESTART);
		applyServiceResponse(windowModel, response);
	}

	// 方法：將固定 service 控制結果映射為非技術使用者可理解的桌面狀態。
	static void applyServiceResponse(
		DesktopWindowModel windowModel,
		ServiceControlResponse response
	) {
		Objects.requireNonNull(windowModel, "桌面視窗模型不可為 null");
		DesktopStatus status = switch (Objects.requireNonNull(response, "Service 控制結果不可為 null")) {
			case RUNNING, RESTARTED -> DesktopStatus.RUNNING;
			case SHUTTING_DOWN -> DesktopStatus.STOPPING;
			case STOPPED -> DesktopStatus.STOPPED;
			case REJECTED, FAILED, UNAVAILABLE -> DesktopStatus.FAILED;
		};

		onEdt(() -> windowModel.updateStatus(status));
	}

	// 方法：同步公開網址至主視窗；未啟用時顯示本機模式。
	private static void updateWindowConfiguration(
		DesktopWindowModel windowModel,
		AppConfiguration configuration
	) {
		onEdt(() -> windowModel.updatePublicUrl(configuration.value(AppConfigurationField.PUBLIC_BASE_URL)));
	}

	// 方法：依目前資料根目錄取得既有 Logback 使用的固定 log 子目錄，並確保目錄已建立與 System property 已設定。
	private static Path logDirectory(AppConfiguration configuration) {
		String systemRoot = configuration.value(AppConfigurationField.SYSTEM_ROOT_PATH);
		if (systemRoot != null && !systemRoot.isBlank()) {
			System.setProperty("SYSTEM_ROOT_PATH", systemRoot);
		}
		Path logDir = Path.of(systemRoot).resolve("log");
		try {
			java.nio.file.Files.createDirectories(logDir);
		}
		catch (Exception exception) {
			// 日誌：記錄日誌目錄建立失敗，不中斷桌面啟動流程。
			log.warn("event=desktop_log_directory_create_failed errorType={}", exception.getClass().getSimpleName());
		}
		return logDir;
	}

	// 狀態來源包含 main、Spring lifecycle 與背景執行緒，而系統匣與視窗只能在 EDT 操作。
	// 方法：把視窗模型更新統一轉交 EDT，呼叫端不需判斷自己目前在哪個執行緒。
	private static void onEdt(Runnable operation) {
		if (SwingUtilities.isEventDispatchThread()) {
			operation.run();

			return;
		}

		// 外部函式：非同步排入 EDT，避免與正在等待的 Swing 操作互鎖。
		SwingUtilities.invokeLater(operation);
	}

	// 方法：建立具名背景執行緒，避免設定與 Spring 啟停阻塞 Swing EDT。
	private static void runInBackground(
		String threadName,
		Runnable operation
	) {
		// 外部函式：建立非 daemon 背景工作，確保受控啟停流程不會被程序提早中斷。
		Thread.ofPlatform().name(threadName).start(() -> {
			try {
				operation.run();
			}
			catch (RuntimeException exception) {
				// 日誌：記錄桌面操作失敗類型，詳細流程由各子系統事件 Log 追蹤。
				log.error("event=desktop_action_failed action={} errorType={}",
					threadName,
					exception.getClass().getSimpleName()
				);
			}
		});
	}

	// 方法：在 Swing EDT 顯示首次設定並同步等待使用者完成或取消。
	private static ConfigurationWizardResult showFirstConfiguration(
		AppConfiguration configuration,
		AppConfigurationRepository repository
	) {
		return showConfiguration(configuration, repository, true);
	}

	// 方法：在 Swing EDT 顯示首次或編輯設定精靈並同步取得結果。
	private static ConfigurationWizardResult showConfiguration(
		AppConfiguration configuration,
		AppConfigurationRepository repository,
		boolean firstConfiguration
	) {
		ConfigurationWizard wizard = new ConfigurationWizard(
			new ConfigurationWizardModel(configuration),
			repository::save
		);
		AtomicReference<ConfigurationWizardResult> result = new AtomicReference<>();

		try {
			// 外部函式：首次設定畫面必須在 Swing EDT 建立與操作。
			SwingUtilities.invokeAndWait(() -> result.set(wizard.show(null, firstConfiguration)));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException("首次設定被中斷", exception);
		}
		catch (InvocationTargetException exception) {
			throw new IllegalStateException("無法顯示首次設定", exception.getCause());
		}

		return result.get();
	}

	// 方法：取得目前使用者的 Local AppData，缺少環境變數時使用 Windows 標準後備路徑。
	private static Path localAppData() {
		String configuredPath = System.getenv("LOCALAPPDATA");

		if (configuredPath != null && !configuredPath.isBlank()) return Path.of(configuredPath);

		return Path.of(System.getProperty("user.home"), "AppData", "Local");
	}

	//#endregion
}
