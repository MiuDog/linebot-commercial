package dev.myudog.assetsmanagerlinebot.desktop;

import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfiguration;
import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfigurationRepository;
import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfigurationField;
import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfigurationValidator;
import dev.myudog.assetsmanagerlinebot.desktop.config.ConfigurationWizard;
import dev.myudog.assetsmanagerlinebot.desktop.config.ConfigurationWizardModel;
import dev.myudog.assetsmanagerlinebot.desktop.config.ConfigurationWizardResult;
import dev.myudog.assetsmanagerlinebot.desktop.config.DesktopSpringProperties;
import dev.myudog.assetsmanagerlinebot.desktop.config.DpapiSecretStore;
import dev.myudog.assetsmanagerlinebot.desktop.ngrok.NgrokConnection;
import dev.myudog.assetsmanagerlinebot.desktop.ngrok.NgrokConnector;
import dev.myudog.assetsmanagerlinebot.desktop.ngrok.NgrokConnectorException;
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
		Supplier<Optional<AppConfiguration>> configurationLoader,
		Function<AppConfiguration, ConfigurationWizardResult> firstConfigurationEditor,
		AppConfiguration defaults,
		BiConsumer<AppConfiguration, String[]> starter,
		Runnable stopper,
		AutoCloseable instanceResource
	) {
		this.instanceGate = Objects.requireNonNull(instanceGate, "單一執行個體閘門不可為 null");
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
		AtomicReference<AppConfiguration> activeConfiguration = new AtomicReference<>();
		AtomicReference<String[]> activeArguments = new AtomicReference<>(new String[0]);
		AtomicReference<DesktopApplication> applicationReference = new AtomicReference<>();
		AtomicReference<DesktopActions> actionsReference = new AtomicReference<>();
		coordinator.addStatusListener(windowModel::updateStatus);
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
			repository::load,
			configuration -> showFirstConfiguration(configuration, repository),
			defaults,
			(configuration, arguments) -> {
				activeConfiguration.set(configuration);
				activeArguments.set(arguments.clone());
				windowModel.updatePort(Integer.parseInt(configuration.value(AppConfigurationField.SERVER_PORT)));
				desktopUi.start(actionsReference.get(), logDirectory(configuration));
				windowModel.updateStatus(DesktopStatus.STARTING);
				AppConfiguration preparedConfiguration = prepareNgrok(
					configuration,
					repository,
					ngrokConnector
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
				closeInstanceResource();

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
			activeConfiguration,
			activeArguments
		);
	}

	// 方法：依受控順序停止 Spring 與 ngrok，再以最新設定重新建立兩者。
	private static void restartServices(
		AppConfigurationRepository repository,
		DesktopLifecycleCoordinator coordinator,
		DesktopUi desktopUi,
		DesktopWindowModel windowModel,
		NgrokConnector ngrokConnector,
		AtomicReference<AppConfiguration> activeConfiguration,
		AtomicReference<String[]> activeArguments
	) {
		coordinator.stop();
		ngrokConnector.stop();
		AppConfiguration configured = repository.load().orElse(activeConfiguration.get());
		desktopUi.followLogDirectory(logDirectory(configured));
		windowModel.updatePort(Integer.parseInt(configured.value(AppConfigurationField.SERVER_PORT)));
		windowModel.updateStatus(DesktopStatus.STARTING);
		AppConfiguration prepared = prepareNgrok(configured, repository, ngrokConnector);

		activeConfiguration.set(prepared);
		updateWindowConfiguration(windowModel, prepared);
		coordinator.start(prepared, activeArguments.get());
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
		windowModel.updatePublicUrl(configuration.value(AppConfigurationField.PUBLIC_BASE_URL));
	}

	// 方法：依目前資料根目錄取得既有 Logback 使用的固定 log 子目錄。
	private static Path logDirectory(AppConfiguration configuration) {
		return Path.of(configuration.value(AppConfigurationField.SYSTEM_ROOT_PATH)).resolve("log");
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
