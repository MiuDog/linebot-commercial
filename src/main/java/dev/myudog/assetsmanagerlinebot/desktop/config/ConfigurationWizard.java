package dev.myudog.assetsmanagerlinebot.desktop.config;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;

/**
 * 提供繁體中文分頁式首次設定與編輯設定介面。
 */
public final class ConfigurationWizard implements AutoCloseable {

	//#region 欄位

	private static final Border NORMAL_BORDER = new JTextField().getBorder();
	private static final AtomicReference<ConfigurationWizard> ACTIVE_WIZARD = new AtomicReference<>();

	private final ConfigurationWizardModel model;
	private final Consumer<AppConfiguration> saver;
	private final JPanel content;
	private final JTabbedPane tabs;
	private final JLabel errorLabel;
	private final Map<AppConfigurationField, JTextComponent> fields;
	private final Map<AppConfigurationField.Group, Integer> tabIndexes;
	private final AtomicReference<JDialog> activeDialog;

	//#endregion

	//#region 建構子

	// 方法：建立可供首次安裝與編輯模式共用的設定精靈。
	public ConfigurationWizard(
		ConfigurationWizardModel model,
		Consumer<AppConfiguration> saver
	) {
		this.model = Objects.requireNonNull(model, "設定精靈模型不可為 null");
		this.saver = Objects.requireNonNull(saver, "設定保存操作不可為 null");
		this.fields = new EnumMap<>(AppConfigurationField.class);
		this.tabIndexes = new EnumMap<>(AppConfigurationField.Group.class);
		this.tabs = new JTabbedPane();
		this.errorLabel = new JLabel(" ");
		this.content = buildContent();
		this.activeDialog = new AtomicReference<>();
	}

	//#endregion

	//#region 方法

	// 方法：以阻塞對話框顯示設定精靈，驗證失敗時保持畫面供使用者修正。
	public ConfigurationWizardResult show(
		Component parent,
		boolean firstConfiguration
	) {
		ACTIVE_WIZARD.set(this);

		try {
			while (true) {
				int option = showDialog(parent, firstConfiguration);

				if (option != JOptionPane.OK_OPTION) return cancel();

				ConfigurationWizardResult result = save(firstConfiguration);

				if (result.saved()) return result;
			}
		}
		finally {
			ACTIVE_WIZARD.compareAndSet(this, null);
		}
	}

	// 方法：關閉目前程序正在等待的首次或編輯設定精靈，供 IPC shutdown 釋放程序。
	public static void closeActive() {
		ConfigurationWizard wizard = ACTIVE_WIZARD.get();

		if (wizard != null) wizard.close();
	}

	// 方法：在 Swing EDT 關閉此精靈目前的 modal dialog。
	@Override
	public void close() {
		JDialog dialog = activeDialog.getAndSet(null);

		if (dialog == null) return;

		if (SwingUtilities.isEventDispatchThread()) {
			dialog.dispose();
		}
		else {
			// 外部函式：透過 modal event pump 非同步關閉設定精靈，避免 IPC thread 與 EDT 互鎖。
			SwingUtilities.invokeLater(dialog::dispose);
		}
	}

	// 方法：同步畫面欄位、驗證設定並在成功時交由儲存庫保存。
	public ConfigurationWizardResult save(boolean firstConfiguration) {
		resetValidationPresentation();

		// 步驟一：將所有控制項內容完整同步至精靈模型。
		for (AppConfigurationField field : AppConfigurationField.values()) {
			model.update(field, fieldText(fields.get(field)));
		}

		// 步驟二：驗證失敗時標示第一個欄位並保持對話框開啟。
		List<AppConfigurationValidator.Violation> violations = model.violations();

		if (!violations.isEmpty()) {
			presentViolation(violations.getFirst());

			return ConfigurationWizardResult.invalid(model.configuration(), violations);
		}

		// 步驟三：保存有效設定；編輯模式要求生命週期協調器重新啟動服務。
		saver.accept(model.configuration());

		return ConfigurationWizardResult.saved(model.configuration(), !firstConfiguration);
	}

	// 方法：回傳取消結果，確保首次設定不會誤啟動服務。
	public ConfigurationWizardResult cancel() {
		return ConfigurationWizardResult.cancelled(model.configuration());
	}

	// 方法：取得設定精靈內容供桌面視窗與無頭測試使用。
	public JPanel content() {
		return content;
	}

	// 方法：取得設定群組分頁供桌面殼層與測試定位。
	public JTabbedPane tabs() {
		return tabs;
	}

	// 方法：取得指定欄位控制項供介面整合與自動化測試使用。
	public JTextComponent fieldComponent(AppConfigurationField field) {
		return fields.get(field);
	}

	// 方法：建立可由 lifecycle 持有並關閉的 modal dialog，再轉換使用者選項。
	private int showDialog(
		Component parent,
		boolean firstConfiguration
	) {
		JOptionPane optionPane = new JOptionPane(
			content,
			JOptionPane.PLAIN_MESSAGE,
			JOptionPane.OK_CANCEL_OPTION
		);

		// 外部函式：明確持有 Swing dialog，讓 installer shutdown 可安全關閉首次設定。
		JDialog dialog = optionPane.createDialog(parent, firstConfiguration ? "首次設定" : "編輯設定");
		activeDialog.set(dialog);

		// 安裝程式以 Exec 啟動 App 時拿不到前景權，且 JDialog 本身不會產生工作列按鈕；
		// 不主動置頂就會被其他視窗蓋住，使用者會以為設定精靈根本沒出現。
		dialog.setAlwaysOnTop(true);
		dialog.setAutoRequestFocus(true);

		try {
			dialog.setVisible(true);
		}
		finally {
			activeDialog.compareAndSet(dialog, null);
			dialog.dispose();
		}

		Object selectedValue = optionPane.getValue();

		return selectedValue instanceof Integer selectedOption
			? selectedOption
			: JOptionPane.CLOSED_OPTION;
	}

	// 方法：建立包含說明、群組分頁及驗證訊息的主內容。
	private JPanel buildContent() {
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		JLabel description = new JLabel("請完成必要設定；密碼欄留空會保留既有值。");

		// 外部函式：建立一致的 Swing 邊界與偏好尺寸，讓安裝與編輯畫面可直接使用。
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		panel.setPreferredSize(new Dimension(720, 540));
		errorLabel.setForeground(java.awt.Color.RED.darker());

		for (AppConfigurationField.Group group : AppConfigurationField.Group.values()) {
			addGroupTab(group);
		}

		panel.add(description, BorderLayout.NORTH);
		panel.add(tabs, BorderLayout.CENTER);
		panel.add(errorLabel, BorderLayout.SOUTH);

		return panel;
	}

	// 方法：建立單一設定群組的表單分頁與所有所屬欄位。
	private void addGroupTab(AppConfigurationField.Group group) {
		JPanel form = new JPanel(new GridBagLayout());
		int row = 0;

		// 單一演算法：依中繼資料順序加入此群組的標籤與輸入控制項。
		for (AppConfigurationField field : AppConfigurationField.values()) {
			if (field.group() != group) continue;

			addField(form, field, row);
			row++;
		}

		GridBagConstraints spacer = constraints(0, row);
		spacer.weighty = 1;
		spacer.gridwidth = 2;
		form.add(new JPanel(), spacer);
		tabIndexes.put(group, tabs.getTabCount());

		// 外部函式：以可捲動分頁容納不同螢幕尺寸下的完整設定表單。
		tabs.addTab(groupLabel(group), new JScrollPane(form));
	}

	// 方法：依欄位機密屬性建立文字或密碼控制項並加入表單。
	private void addField(
		JPanel form,
		AppConfigurationField field,
		int row
	) {
		String requiredMarker = field.required() ? " *" : "";
		JLabel label = new JLabel(field.label() + requiredMarker);
		JTextComponent input = field.secret() ? new JPasswordField() : new JTextField();

		input.setText(model.displayValue(field));
		input.setName(field.environmentKey());
		input.setToolTipText(field.environmentKey());
		label.setLabelFor(input);
		fields.put(field, input);
		form.add(label, constraints(0, row));

		GridBagConstraints inputConstraints = constraints(1, row);
		inputConstraints.weightx = 1;
		inputConstraints.fill = GridBagConstraints.HORIZONTAL;
		form.add(input, inputConstraints);
	}

	// 方法：建立表單欄位共用的格線配置。
	private GridBagConstraints constraints(
		int column,
		int row
	) {
		GridBagConstraints constraints = new GridBagConstraints();

		constraints.gridx = column;
		constraints.gridy = row;
		constraints.anchor = GridBagConstraints.NORTHWEST;
		constraints.insets = new Insets(4, 6, 4, 6);

		return constraints;
	}

	// 方法：安全讀取一般與密碼控制項內容，並立即清除密碼字元陣列。
	private String fieldText(JTextComponent component) {
		if (!(component instanceof JPasswordField passwordField)) return component.getText();

		char[] password = passwordField.getPassword();

		try {
			return new String(password);
		}
		finally {
			java.util.Arrays.fill(password, '\0');
		}
	}

	// 方法：清除上次驗證的紅框與錯誤文字。
	private void resetValidationPresentation() {
		errorLabel.setText(" ");

		for (JTextComponent field : fields.values()) {
			field.setBorder(NORMAL_BORDER);
		}
	}

	// 方法：切換至錯誤欄位所在分頁並顯示可理解的中文訊息。
	private void presentViolation(AppConfigurationValidator.Violation violation) {
		JTextComponent field = fields.get(violation.field());
		Integer tabIndex = tabIndexes.get(violation.field().group());

		errorLabel.setText(violation.message());
		field.setBorder(BorderFactory.createLineBorder(java.awt.Color.RED));
		tabs.setSelectedIndex(tabIndex);

		// 外部函式：等待 Swing 完成分頁切換後，把鍵盤焦點移至錯誤欄位。
		SwingUtilities.invokeLater(field::requestFocusInWindow);
	}

	// 方法：取得適合非技術使用者辨識的繁體中文分頁名稱。
	private String groupLabel(AppConfigurationField.Group group) {
		return switch (group) {
			case SYSTEM -> "系統";
			case LINE -> "LINE";
			case AI -> "AI";
			case VOICE -> "語音";
			case QUOTATION -> "報價";
			case LOG -> "記錄";
			case NGROK -> "ngrok";
		};
	}

	//#endregion
}
