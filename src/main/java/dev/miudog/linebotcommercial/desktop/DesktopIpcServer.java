package dev.miudog.linebotcommercial.desktop;

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
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在 loopback 隨機 Port 接收具有啟動 nonce 的有限桌面命令。
 */
public final class DesktopIpcServer implements AutoCloseable {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(DesktopIpcServer.class);
	private static final int MAX_PAYLOAD_LENGTH = 4096;
	private static final String ACCEPTED_RESPONSE = "OK";
	private static final String REJECTED_RESPONSE = "REJECTED";

	private final String nonce;
	private final Consumer<DesktopIpcCommand> commandHandler;
	private ServerSocket serverSocket;
	private Thread serverThread;
	private volatile boolean running;

	//#endregion

	//#region 建構子

	// 方法：建立只接受指定 nonce 的桌面 IPC 伺服器。
	public DesktopIpcServer(
		String nonce,
		Consumer<DesktopIpcCommand> commandHandler
	) {
		this.nonce = requireNonce(nonce);
		this.commandHandler = Objects.requireNonNull(commandHandler, "IPC 命令處理器不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：綁定 loopback 隨機 Port 並啟動背景接收執行緒。
	public synchronized void start() {
		if (running) throw new IllegalStateException("桌面 IPC 已經啟動");

		try {
			serverSocket = new ServerSocket();

			// 外部函式：明確綁定 loopback，避免 IPC 暴露至區域網路介面。
			serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			running = true;
			ServerSocket activeServer = serverSocket;

			// 外部函式：建立 daemon 執行緒，讓 IPC 不會阻止已完成清理的程序結束。
			serverThread = Thread.ofPlatform()
				.daemon()
				.name("desktop-ipc")
				.start(() -> acceptLoop(activeServer));

			// 日誌：記錄 IPC 已啟動與 loopback Port，不記錄驗證 nonce。
			log.info("event=desktop_ipc_started port={}", port());
		}
		catch (IOException exception) {
			close();

			throw new IllegalStateException("無法啟動桌面 IPC", exception);
		}
	}

	// 方法：取得已啟動 IPC 的 loopback Port。
	public synchronized int port() {
		if (serverSocket == null) throw new IllegalStateException("桌面 IPC 尚未啟動");

		return serverSocket.getLocalPort();
	}

	// 方法：以認證格式將有限命令送至第一個執行個體。
	public static boolean send(
		int port,
		String nonce,
		DesktopIpcCommand command,
		Duration timeout
	) {
		Objects.requireNonNull(command, "IPC 命令不可為 null");

		return sendRaw(port, requireNonce(nonce) + "\t" + command.name(), timeout);
	}

	// 方法：傳送原始資料供協調器與 malformed payload 安全測試使用。
	static boolean sendRaw(
		int port,
		String payload,
		Duration timeout
	) {
		Objects.requireNonNull(payload, "IPC payload 不可為 null");
		Objects.requireNonNull(timeout, "IPC timeout 不可為 null");
		int timeoutMilliseconds = Math.toIntExact(timeout.toMillis());

		try (Socket socket = new Socket()) {
			// 外部函式：只連線至本機 loopback 並限制連線及讀取等待時間。
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

				return ACCEPTED_RESPONSE.equals(reader.readLine());
			}
		}
		catch (IOException | ArithmeticException exception) {
			return false;
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
			// 日誌：關閉 IPC socket 失敗時記錄錯誤類型，不包含 nonce 或 payload。
			log.warn("event=desktop_ipc_close_failed errorType={}", exception.getClass().getSimpleName());
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
					log.warn("event=desktop_ipc_accept_failed errorType={}",
						exception.getClass().getSimpleName()
					);
				}
			}
		}
	}

	// 方法：驗證來源、長度、nonce 與命令後才交給桌面事件處理器。
	private void handle(Socket socket) {
		try (socket; BufferedReader reader = new BufferedReader(new InputStreamReader(
			socket.getInputStream(),
			StandardCharsets.UTF_8
		)); BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
			socket.getOutputStream(),
			StandardCharsets.UTF_8
		))) {
			socket.setSoTimeout(2000);
			String payload = reader.readLine();
			DesktopIpcCommand command = authenticate(socket, payload);

			if (command == null) {
				writeResponse(writer, REJECTED_RESPONSE);
				return;
			}

			commandHandler.accept(command);
			writeResponse(writer, ACCEPTED_RESPONSE);

			// 日誌：記錄通過驗證的有限命令，不輸出 nonce 或原始 payload。
			log.info("event=desktop_ipc_command_accepted command={}", command);
		}
		catch (IOException | RuntimeException exception) {
			// 日誌：只記錄處理錯誤類型，避免惡意 payload 進入 Log。
			log.warn("event=desktop_ipc_command_failed errorType={}", exception.getClass().getSimpleName());
		}
	}

	// 方法：以固定時間比較 nonce，並只解析白名單中的列舉命令。
	private DesktopIpcCommand authenticate(
		Socket socket,
		String payload
	) {
		if (!socket.getInetAddress().isLoopbackAddress()) return null;

		if (payload == null || payload.length() > MAX_PAYLOAD_LENGTH) return null;

		String[] parts = payload.split("\t", -1);

		if (parts.length != 2 || !sameNonce(parts[0])) return null;

		try {
			return DesktopIpcCommand.valueOf(parts[1]);
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

	// 方法：寫入不含內部錯誤細節的固定 IPC 回應。
	private void writeResponse(
		BufferedWriter writer,
		String response
	) throws IOException {
		writer.write(response);
		writer.newLine();
		writer.flush();
	}

	// 方法：拒絕空白 nonce，避免產生未認證的本機控制通道。
	private static String requireNonce(String nonce) {
		if (nonce == null || nonce.isBlank()) throw new IllegalArgumentException("IPC nonce 不可為空白");

		return nonce;
	}

	//#endregion
}
