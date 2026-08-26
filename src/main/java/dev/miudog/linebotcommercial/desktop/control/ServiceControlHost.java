package dev.miudog.linebotcommercial.desktop.control;

import java.util.function.Function;

/**
 * 隔離 service 生命週期與實際 loopback 控制通道實作。
 */
public interface ServiceControlHost extends AutoCloseable {

	// 方法：啟動控制通道並將通過認證的有限命令交給 service host。
	void start(Function<ServiceControlCommand, ServiceControlResponse> commandHandler);

	// 方法：撤銷端點並停止接收新的 service 控制命令。
	@Override
	void close();
}
