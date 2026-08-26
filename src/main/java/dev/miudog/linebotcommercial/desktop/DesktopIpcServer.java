package dev.miudog.linebotcommercial.desktop;

import dev.miudog.linebotcommercial.desktop.ipc.AuthenticatedLoopbackServer;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 將共用認證 loopback 通道轉接為桌面單一執行個體命令。
 */
public final class DesktopIpcServer implements AutoCloseable {

	//#region 欄位

	private final AuthenticatedLoopbackServer<DesktopIpcCommand, DesktopIpcResponse> server;

	//#endregion

	//#region 建構子

	// 方法：建立只接受指定 nonce 的桌面 IPC 伺服器。
	public DesktopIpcServer(
		String nonce,
		Consumer<DesktopIpcCommand> commandHandler
	) {
		Objects.requireNonNull(commandHandler, "IPC 命令處理器不可為 null");
		this.server = new AuthenticatedLoopbackServer<>(
			"desktop",
			nonce,
			DesktopIpcCommand.class,
			DesktopIpcResponse.REJECTED,
			command -> {
				commandHandler.accept(command);

				return DesktopIpcResponse.ACCEPTED;

			}
		);
	}

	//#endregion

	//#region 方法

	// 方法：綁定 loopback 隨機 Port 並啟動背景接收執行緒。
	public void start() {
		server.start();
	}

	// 方法：取得已啟動 IPC 的 loopback Port。
	public int port() {
		return server.port();
	}

	// 方法：以認證格式將有限命令送至第一個執行個體。
	public static boolean send(
		int port,
		String nonce,
		DesktopIpcCommand command,
		Duration timeout
	) {
		DesktopIpcResponse response = AuthenticatedLoopbackServer.request(
			port,
			nonce,
			command,
			DesktopIpcResponse.class,
			timeout
		);

		return response == DesktopIpcResponse.ACCEPTED;
	}

	// 方法：傳送原始資料供協調器與 malformed payload 安全測試使用。
	static boolean sendRaw(
		int port,
		String payload,
		Duration timeout
	) {
		DesktopIpcResponse response = AuthenticatedLoopbackServer.requestRaw(
			port,
			payload,
			DesktopIpcResponse.class,
			timeout
		);

		return response == DesktopIpcResponse.ACCEPTED;
	}

	// 方法：停止接收、關閉 socket 並釋放 loopback Port。
	@Override
	public void close() {
		server.close();
	}

	//#endregion

	/**
	 * 定義桌面 IPC 對外可見的固定結果。
	 */
	private enum DesktopIpcResponse {
		ACCEPTED,
		REJECTED
	}
}
