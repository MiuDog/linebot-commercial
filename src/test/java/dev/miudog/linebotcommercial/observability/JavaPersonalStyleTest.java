package dev.miudog.linebotcommercial.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 驗證專案 Java 原始碼遵循個人化排版規則。
 */
class JavaPersonalStyleTest {

	private static final List<Path> SOURCE_ROOTS = List.of(
		Path.of("src/main/java"),
		Path.of("src/test/java")
	);

	// 方法：驗證一般 Java 程式碼一律使用 tab 縮排。
	@Test
	void shouldUseTabsForJavaIndentation() throws IOException {
		List<String> violations = new ArrayList<>();

		for (Path file : javaFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			boolean insideTextBlock = false;

			for (int index = 0; index < lines.size(); index++) {
				String line = lines.get(index);

				boolean javadocAlignment = line.matches("^ +\\*.*");

				if (!insideTextBlock && !javadocAlignment && line.matches("^ +\\S.*")) {
					violations.add(file + ":" + (index + 1));
				}

				long delimiterCount = countOccurrences(line, "\"\"\"");
				if (delimiterCount % 2 == 1) insideTextBlock = !insideTextBlock;
			}
		}

		assertThat(violations)
			.as("Java 程式碼不可用空白字元作為行首縮排")
			.isEmpty();
	}

	// 方法：驗證控制流程接續關鍵字必須換行。
	@Test
	void shouldPlaceContinuationKeywordsOnNewLines() throws IOException {
		List<String> violations = new ArrayList<>();

		for (Path file : javaFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			boolean insideTextBlock = false;

			for (int index = 0; index < lines.size(); index++) {
				String line = lines.get(index);

				if (!insideTextBlock && line.matches(".*}\\s+(else|catch|finally|while)\\b.*")) {
					violations.add(file + ":" + (index + 1));
				}

				long delimiterCount = countOccurrences(line, "\"\"\"");
				if (delimiterCount % 2 == 1) insideTextBlock = !insideTextBlock;
			}
		}

		assertThat(violations)
			.as("else、catch、finally 與 do-while 的 while 必須另起一行")
			.isEmpty();
	}

	// 方法：驗證只有 return、continue 或 break 的 if 不使用大括號。
	@Test
	void shouldKeepDirectControlTransfersOnOneLine() throws IOException {
		List<String> violations = new ArrayList<>();

		for (Path file : javaFiles()) {
			String source = Files.readString(file, StandardCharsets.UTF_8);
			String sourceWithoutTextBlocks = source.replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"");
			var matcher = java.util.regex.Pattern.compile(
				"(?m)^(\\t*)if \\([^\\n]+\\) \\{\\n\\1\\t(return(?: [^;\\n]+)?;|continue;|break;)\\n\\1}"
			).matcher(sourceWithoutTextBlocks);

			while (matcher.find()) {
				long lineNumber = sourceWithoutTextBlocks.substring(0, matcher.start()).lines().count() + 1;
				violations.add(file + ":" + lineNumber);
			}
		}

		assertThat(violations)
			.as("if 後若只有 return、continue 或 break，必須寫成無大括號的單行")
			.isEmpty();
	}

	// 方法：驗證控制轉移後的下一段敘述必須以空行分隔。
	@Test
	void shouldSeparateStatementsAfterControlTransfers() throws IOException {
		List<String> violations = new ArrayList<>();

		for (Path file : javaFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			boolean insideTextBlock = false;

			for (int index = 0; index < lines.size() - 1; index++) {
				String line = lines.get(index);
				String nextLine = lines.get(index + 1).trim();

				boolean transfersControl = !insideTextBlock
					&& line.matches(".*\\b(return|continue|break|throw)\\b.*;\\s*");

				boolean nextLineContinuesBlock = nextLine.isEmpty()
					|| nextLine.equals("}")
					|| nextLine.matches("^(else|catch|finally|while)\\b.*");

				if (transfersControl && !nextLineContinuesBlock) {
					violations.add(file + ":" + (index + 1));
				}

				long delimiterCount = countOccurrences(line, "\"\"\"");
				if (delimiterCount % 2 == 1) insideTextBlock = !insideTextBlock;
			}
		}

		assertThat(violations)
			.as("return、continue、break 或 throw 後的下一段敘述前必須空一行")
			.isEmpty();
	}

	// 方法：取得主程式與測試程式內的所有 Java 檔案。
	private List<Path> javaFiles() throws IOException {
		List<Path> files = new ArrayList<>();

		for (Path sourceRoot : SOURCE_ROOTS) {
			try (Stream<Path> paths = Files.walk(sourceRoot)) {
				paths.filter(path -> path.toString().endsWith(".java"))
					.sorted()
					.forEach(files::add);
			}
		}

		return files;
	}

	// 方法：計算指定片段在一行文字內出現的次數。
	private long countOccurrences(String source, String target) {
		long count = 0;
		int index = 0;

		while ((index = source.indexOf(target, index)) >= 0) {
			count++;
			index += target.length();
		}

		return count;
	}
}
