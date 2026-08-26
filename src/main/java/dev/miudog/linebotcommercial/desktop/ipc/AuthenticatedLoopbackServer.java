package dev.miudog.linebotcommercial.desktop.ipc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提供只綁定 loopback、以每次啟動 nonce 認證的有限列舉命令通道。
 *
 * @param <C> 白名單命令列舉
 * @param <R> 固定回應列舉
 */
public final class AuthenticatedLoopbackServer<C extends Enum<C>, R extends Enum<R>> implements AutoCloseable {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(AuthenticatedLoopbackServer.class);
	private static final int MAX_PAYLOAD_LENGTH = 4096;
	private static final int SOCKET_TIMEOUT_MILLISECONDS = 2000;

	private final String component;
	private final String nonce;
	private final Class<C> commandType;
	private final R rejectedResponse;
	private final Function<C, R> commandHandler;
	private ServerSocket serverSocket;
	private Thread serverThread;
	private volatile boolean running;

	//#endregion

	//#region 建構子

	// 方法：建立只接受指定 nonce、白名單命令與固定回應的 loopback 伺服器。
	public AuthenticatedLoopbackServer(
		String component,
		String nonce,
		Class<C> commandType,
		R rejectedResponse,
		Function<C, R> commandHandler
	) {
		this.component = requireText(component, "IPC 元件名稱不可為空白");
		this.nonce = requireText(nonce, "IPC nonce 不可為空白");
		this.commandType = Objects.requireNonNull(commandType, "IPC 命令類型不可為 null");
		this.rejectedResponse = Objects.requireNonNull(rejectedResponse, "IPC 拒絕回應不可為 null");
		this.commandHandler = Objects.requireNonNull(commandHandler, "IPC 命令處理器不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：綁定 loopback 隨機 Port 並啟動 daemon 接收執行緒。
	public synchronized void start() {
		if (running) throw new IllegalStateException("Loopback IPC 已經啟動");

		try {
			serverSocket = new ServerSocket();

			// 外部網路函式：明確綁定 loopback，避免控制通道暴露至區域網路介面。
			serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			running = true;
			ServerSocket activeServer = serverSocket;

			// 外部 JVM：建立 daemon 執行緒，避免控制通道阻止已完成清理的程序結束。
			serverThread = Thread.ofPlatform()
				.daemon()
				.name(component + "-ipc")
				.start(() -> acceptLoop(activeServer));

			// 日誌：記錄控制通道元件與 loopback Port，不輸出認證 nonce。
			log.info("event=loopback_ipc_started component={} port={}", component, port());
		}
		catch (IOException exception) {
			close();

			throw new IllegalStateException("無法啟動 loopback IPC", exception);
		}
	}

	// 方法：取得已啟動 IPC 的 loopback Port。
	public synchronized int port() {
		if (serverSocket == null) throw new IllegalStateException("Loopback IPC 尚未啟動");

		return serverSocket.getLocalPort();
	}

	// 方法：以認證格式傳送列舉命令並解析固定列舉回應。
	public static <C extends Enum<C>, R extends Enum<R>> R request(
		int port,
		String nonce,
		C command,
		Class<R> responseType,
		Duration timeout
	) {
		Objects.requireNonNull(command, "IPC 命令不可為 null");

		return requestRaw(
			port,
			requireText(nonce, "IPC nonce 不可為空白") + "\t" + command.name(),
			responseType,
			timeout
		);
	}

	// 方法：傳送原始資料供協調器與 malformed payload 安全測試使用。
	public static <R extends Enum<R>> R requestRaw(
		int port,
		String payload,
		Class<R> responseType,
		Duration timeout
	) {
		Objects.requireNonNull(payload, "IPC payload 不可為 null");
		Objects.requireNonNull(responseType, "IPC 回應類型不可為 null");
		Objects.requireNonNull(timeout, "IPC timeout 不可為 null");
		int timeoutMilliseconds = Math.toIntExact(timeout.toMillis());

		try (Socket socket = new Socket()) {
			// 外部網路函式：只連線至本機 loopback 並限制連線及讀取等待時間。
			socket.connect(
				new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
				timeoutMilliseconds
			);
			socket.setSoTimeout(timeoutMilliseconds);

			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
				socket.getOutputStream(),
				StandardCharsets.UTF_8
			)); BufferedReader reader = new BufferedReader(new InputStreamReader(
				socket.getInputStream(),
				StandardCharsets.UTF_8
			))) {
				writer.write(payload);
				writer.newLine();
				writer.flush();
				String response = readBoundedLine(reader);

				if (response == null) return null;

				return Enum.valueOf(responseType, response);
			}
		}
		catch (IOException | ArithmeticException | IllegalArgumentException exception) {
			return null;
		}
	}

	// 方法：停止接收、關閉 socket 並釋放 loopback Port。
	@Override
	public synchronized void close() {
		running = false;

		if (serverSocket == null) return;

		try {
			serverSocket.close();
		}
		catch (IOException exception) {
			// 日誌：關閉 IPC socket 失敗時記錄元件與錯誤類型，不包含 nonce 或 payload。
			log.warn("event=loopback_ipc_close_failed component={} errorType={}",
				component,
				exception.getClass().getSimpleName()
			);
		}
		finally {
			serverSocket = null;
			serverThread = null;
		}
	}

	// 方法：持續接收單次命令連線，關閉後自然離開迴圈。
	private void acceptLoop(ServerSocket activeServer) {
		while (running) {
			try {
				Socket acceptedSocket = activeServer.accept();

				handle(acceptedSocket);
			}
			catch (IOException exception) {
				if (running) {
					// 日誌：記錄 IPC 接收錯誤類型，不包含客戶端傳入內容。
					log.warn("event=loopback_ipc_accept_failed component={} errorType={}",
						component,
						exception.getClass().getSimpleName()
					);
				}
			}
		}
	}

	// 方法：限制讀取量、驗證來源與 nonce，通過後才執行白名單命令。
	private void handle(Socket socket) {
		try (socket; BufferedReader reader = new BufferedReader(new InputStreamReader(
			socket.getInputStream(),
			StandardCharsets.UTF_8
		)); BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
			socket.getOutputStream(),
			StandardCharsets.UTF_8
		))) {
			socket.setSoTimeout(SOCKET_TIMEOUT_MILLISECONDS);
			String payload = readBoundedLine(reader);
			C command = authenticate(socket, payload);

			if (command == null) {
				writeResponse(writer, rejectedResponse);

				return;
			}

			R response = Objects.requireNonNull(commandHandler.apply(command), "IPC 命令回應不可為 null");
			writeResponse(writer, response);

			// 日誌：記錄通過驗證的有限命令與固定回應，不輸出 nonce 或原始 payload。
			log.info("event=loopback_ipc_command_accepted component={} command={} response={}",
				component,
				command,
				response
			);
		}
		catch (IOException | RuntimeException exception) {
			// 日誌：只記錄元件與處理錯誤類型，避免惡意 payload 進入 Log。
			log.warn("event=loopback_ipc_command_failed component={} errorType={}",
				component,
				exception.getClass().getSimpleName()
			);
		}
	}

	// 方法：以固定時間比較 nonce，並只解析命令列舉中的白名單值。
	private C authenticate(
		Socket socket,
		String payload
	) {
		if (!socket.getInetAddress().isLoopbackAddress()) return null;

		if (payload == null) return null;

		String[] parts = payload.split("\t", -1);

		if (parts.length != 2 || !sameNonce(parts[0])) return null;

		try {
			return Enum.valueOf(commandType, parts[1]);
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	// 方法：以固定時間比較 UTF-8 nonce，降低認證值的時間差資訊。
	private boolean sameNonce(String suppliedNonce) {
		return MessageDigest.isEqual(
			nonce.getBytes(StandardCharsets.UTF_8),
			suppliedNonce.getBytes(StandardCharsets.UTF_8)
		);
	}

	// 方法：最多讀取允許長度與換行，避免惡意連線造成無界記憶體配置。
	private static String readBoundedLine(BufferedReader reader) throws IOException {
		StringBuilder result = new StringBuilder();

		for (int index = 0; index <= MAX_PAYLOAD_LENGTH; index++) {
			int value = reader.read();

			if (value == -1 || value == '\n') return result.toString();

			if (value != '\r') result.append((char) value);
		}

		return null;
	}

	// 方法：寫入不含內部錯誤細節的固定列舉 IPC 回應。
	private void writeResponse(
		BufferedWriter writer,
		R response
	) throws IOException {
		writer.write(response.name());
		writer.newLine();
		writer.flush();
	}

	// 方法：拒絕空白固定文字，避免建立未認證或無法辨識的通道。
	private static String requireText(
		String value,
		String message
	) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(message);

		return value;
	}

	//#endregion
}
