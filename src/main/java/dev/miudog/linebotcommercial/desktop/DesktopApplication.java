package dev.miudog.linebotcommercial.desktop;

import dev.miudog.linebotcommercial.desktop.cloudflare.CloudflareConnection;
import dev.miudog.linebotcommercial.desktop.cloudflare.CloudflareConnector;
import dev.miudog.linebotcommercial.desktop.cloudflare.CloudflareConnectorException;
import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationRepository;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationField;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationValidator;
import dev.miudog.linebotcommercial.desktop.config.ConfigurationWizard;
import dev.miudog.linebotcommercial.desktop.config.ConfigurationWizardModel;
import dev.miudog.linebotcommercial.desktop.config.ConfigurationWizardResult;
import dev.miudog.linebotcommercial.desktop.config.DesktopSpringProperties;
import dev.miudog.linebotcommercial.desktop.config.DpapiSecretStore;
import dev.miudog.linebotcommercial.desktop.ngrok.NgrokConnection;
import dev.miudog.linebotcommercial.desktop.ngrok.NgrokConnector;
import dev.miudog.linebotcommercial.desktop.ngrok.NgrokConnectorException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
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

	// 方法：建立使用 Local AppData、DPAPI 與 Spring 後端的正式桌面應用程式。
	public static DesktopApplication createDefault() {
		Path localAppData = localAppData();
		AppConfiguration defaults = AppConfiguration.defaults(localAppData);
		AppConfigurationRepository repository = new AppConfigurationRepository(
			AppConfiguration.configurationRoot(localAppData),
			defaults,
			new DpapiSecretStore(),
			new AppConfigurationValidator()
		);
		DesktopLifecycleCoordinator coordinator = new DesktopLifecycleCoordinator(
			new SpringDesktopBackend(),
			new DesktopSpringProperties()::from
		);
		DesktopWindowModel windowModel = new DesktopWindowModel(
			Integer.parseInt(defaults.value(AppConfigurationField.SERVER_PORT))
		);
		DesktopUi desktopUi = new DesktopUi(windowModel);
		NgrokConnector ngrokConnector = new NgrokConnector();
		CloudflareConnector cloudflareConnector = new CloudflareConnector();
		AtomicReference<AppConfiguration> activeConfiguration = new AtomicReference<>();
		AtomicReference<String[]> activeArguments = new AtomicReference<>(new String[0]);
		AtomicReference<DesktopApplication> applicationReference = new AtomicReference<>();
		AtomicReference<DesktopActions> actionsReference = new AtomicReference<>();
		coordinator.addStatusListener(status -> onEdt(() -> windowModel.updateStatus(status)));
		SingleInstanceCoordinator instanceCoordinator = new SingleInstanceCoordinator(
			AppConfiguration.configurationRoot(localAppData)
		);
		DesktopActions actions = new DesktopActions(
			desktopUi::show,
			() -> runInBackground(
				"desktop-settings",
				() -> editConfiguration(
					repository,
					coordinator,
					desktopUi,
					windowModel,
					ngrokConnector,
					cloudflareConnector,
					activeConfiguration,
					activeArguments
				)
			),
			() -> runInBackground(
				"desktop-restart",
				() -> restartServices(
					repository,
					coordinator,
					desktopUi,
					windowModel,
					ngrokConnector,
					cloudflareConnector,
					activeConfiguration,
					activeArguments
				)
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
				activeArguments.set(arguments.clone());
				onEdt(() -> windowModel.updatePort(Integer.parseInt(configuration.value(AppConfigurationField.SERVER_PORT))));
				desktopUi.start(actionsReference.get(), logDirectory(configuration));
				onEdt(() -> windowModel.updateStatus(DesktopStatus.STARTING));
				AppConfiguration preparedConfiguration = prepareTunnels(
					configuration,
					repository,
					ngrokConnector,
					cloudflareConnector
				);

				activeConfiguration.set(preparedConfiguration);
				updateWindowConfiguration(windowModel, preparedConfiguration);
				coordinator.start(preparedConfiguration, arguments);

				if (requestedCommand(arguments) == DesktopIpcCommand.OPEN_SETTINGS) {
					actionsReference.get().settings().run();
				}
			},
			() -> {
				ConfigurationWizard.closeActive();
				coordinator.stop();
				ngrokConnector.stop();
				cloudflareConnector.stop();
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

	// 方法：顯示編輯設定精靈，成功保存後以新設定重新啟動 Spring 後端。
	private static void editConfiguration(
		AppConfigurationRepository repository,
		DesktopLifecycleCoordinator coordinator,
		DesktopUi desktopUi,
		DesktopWindowModel windowModel,
		NgrokConnector ngrokConnector,
		CloudflareConnector cloudflareConnector,
		AtomicReference<AppConfiguration> activeConfiguration,
		AtomicReference<String[]> activeArguments
	) {
		AppConfiguration current = repository.load().orElse(activeConfiguration.get());
		ConfigurationWizardResult result = showConfiguration(current, repository, false);

		if (!result.saved()) return;

		activeConfiguration.set(result.configuration());
		restartServices(
			repository,
			coordinator,
			desktopUi,
			windowModel,
			ngrokConnector,
			cloudflareConnector,
			activeConfiguration,
			activeArguments
		);
	}

	// 方法：依受控順序停止 Spring、ngrok 與 Cloudflare，再以最新設定重新建立兩者。
	private static void restartServices(
		AppConfigurationRepository repository,
		DesktopLifecycleCoordinator coordinator,
		DesktopUi desktopUi,
		DesktopWindowModel windowModel,
		NgrokConnector ngrokConnector,
		CloudflareConnector cloudflareConnector,
		AtomicReference<AppConfiguration> activeConfiguration,
		AtomicReference<String[]> activeArguments
	) {
		coordinator.stop();
		ngrokConnector.stop();
		cloudflareConnector.stop();
		AppConfiguration configured = repository.load().orElse(activeConfiguration.get());
		desktopUi.followLogDirectory(logDirectory(configured));
		onEdt(() -> windowModel.updatePort(Integer.parseInt(configured.value(AppConfigurationField.SERVER_PORT))));
		onEdt(() -> windowModel.updateStatus(DesktopStatus.STARTING));
		AppConfiguration prepared = prepareTunnels(configured, repository, ngrokConnector, cloudflareConnector);

		activeConfiguration.set(prepared);
		updateWindowConfiguration(windowModel, prepared);
		coordinator.start(prepared, activeArguments.get());
	}

	// 方法：在 Spring 前準備 ngrok 或 Cloudflare Tunnel。
	private static AppConfiguration prepareTunnels(
		AppConfiguration configuration,
		AppConfigurationRepository repository,
		NgrokConnector ngrokConnector,
		CloudflareConnector cloudflareConnector
	) {
		AppConfiguration afterNgrok = prepareNgrok(configuration, repository, ngrokConnector);
		return prepareCloudflare(afterNgrok, repository, cloudflareConnector);
	}

	// 方法：在 Spring 前取得 ngrok URL，失敗時提供重試、設定或本機模式選項。
	private static AppConfiguration prepareNgrok(
		AppConfiguration configuration,
		AppConfigurationRepository repository,
		NgrokConnector ngrokConnector
	) {
		AppConfiguration candidate = configuration;

		while (true) {
			try {
				NgrokConnection connection = ngrokConnector.start(candidate, java.time.Duration.ofSeconds(15));

				return connection.configuration();
			}
			catch (NgrokConnectorException exception) {
				int decision = showNgrokFailureDecision();

				if (decision == 0) continue;

				if (decision == 1) {
					ConfigurationWizardResult result = showConfiguration(candidate, repository, false);

					if (result.saved()) candidate = result.configuration();

					continue;
				}

				if (decision == 2) {
					return candidate
						.withValue(AppConfigurationField.NGROK_ENABLED, "false")
						.withValue(AppConfigurationField.PUBLIC_BASE_URL, "");
				}

				throw exception;
			}
		}
	}

	// 方法：在 Spring 前啟動 Cloudflare Tunnel，失敗時提供重試、設定或關閉選項。
	private static AppConfiguration prepareCloudflare(
		AppConfiguration configuration,
		AppConfigurationRepository repository,
		CloudflareConnector cloudflareConnector
	) {
		AppConfiguration candidate = configuration;

		while (true) {
			try {
				CloudflareConnection connection = cloudflareConnector.start(candidate, java.time.Duration.ofSeconds(10));

				return connection.configuration();
			}
			catch (CloudflareConnectorException exception) {
				int decision = showCloudflareFailureDecision(exception.getMessage());

				if (decision == 0) continue;

				if (decision == 1) {
					ConfigurationWizardResult result = showConfiguration(candidate, repository, false);

					if (result.saved()) candidate = result.configuration();

					continue;
				}

				if (decision == 2) {
					return candidate
						.withValue(AppConfigurationField.CLOUDFLARE_ENABLED, "false");
				}

				throw exception;
			}
		}
	}

	// 方法：在 Swing EDT 顯示 Cloudflare 失敗處理選項。
	private static int showCloudflareFailureDecision(String errorMessage) {
		AtomicReference<Integer> decision = new AtomicReference<>(JOptionPane.CLOSED_OPTION);
		String message = (errorMessage != null && !errorMessage.isBlank())
			? "Cloudflare Tunnel 啟動失敗: " + errorMessage + "\n可重試、修改設定，或關閉 Tunnel 啟動。"
			: "Cloudflare Tunnel 無法啟動。可重試、修改設定，或關閉 Tunnel 啟動。";

		Runnable dialog = () -> decision.set(JOptionPane.showOptionDialog(
			null,
			message,
			"Cloudflare Tunnel 啟動失敗",
			JOptionPane.DEFAULT_OPTION,
			JOptionPane.WARNING_MESSAGE,
			null,
			new String[] {"重試", "設定", "關閉 Tunnel"},
			"重試"
		));

		try {
			if (SwingUtilities.isEventDispatchThread()) {
				dialog.run();
			}
			else {
				// 外部函式：所有 Cloudflare 錯誤選項都在 Swing EDT 顯示與操作。
				SwingUtilities.invokeAndWait(dialog);
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			return JOptionPane.CLOSED_OPTION;
		}
		catch (InvocationTargetException exception) {
			return JOptionPane.CLOSED_OPTION;
		}

		return decision.get();
	}

	// 方法：在 Swing EDT 顯示不含 Token 的 ngrok 失敗處理選項。
	private static int showNgrokFailureDecision() {
		AtomicReference<Integer> decision = new AtomicReference<>(JOptionPane.CLOSED_OPTION);
		Runnable dialog = () -> decision.set(JOptionPane.showOptionDialog(
			null,
			"ngrok 無法建立公開連線。可重試、修改設定，或只以本機模式啟動。",
			"ngrok 啟動失敗",
			JOptionPane.DEFAULT_OPTION,
			JOptionPane.WARNING_MESSAGE,
			null,
			new String[] {"重試", "設定", "本機模式"},
			"重試"
		));

		try {
			if (SwingUtilities.isEventDispatchThread()) {
				dialog.run();
			}
			else {
				// 外部函式：所有 ngrok 錯誤選項都在 Swing EDT 顯示與操作。
				SwingUtilities.invokeAndWait(dialog);
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			return JOptionPane.CLOSED_OPTION;
		}
		catch (InvocationTargetException exception) {
			return JOptionPane.CLOSED_OPTION;
		}

		return decision.get();
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
