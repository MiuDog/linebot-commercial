package dev.myudog.assetsmanagerlinebot.service.voice;

import java.time.LocalDate;

/** AI 從語音任務整理出的結構化執行收據。 */
public record VoiceTaskReceipt(
	String action,
	String departmentCode,
	LocalDate date
) {}
