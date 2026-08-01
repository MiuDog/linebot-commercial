package dev.myudog.assetsmanagerlinebot.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoConsoleOutputTest {

	@Test
	void productionCodeUsesLoggerInsteadOfConsoleOutput() throws IOException {
		Path sourceRoot = Path.of("src", "main", "java");

		List<String> violations;
		try (var files = Files.walk(sourceRoot)) {
			violations =
			files.filter(path -> path.toString().endsWith(".java")).flatMap(path -> findViolations(path).stream()).toList();
		}

		assertThat(violations).as("Production code must not use System.out, System.err, or printStackTrace").isEmpty();
	}

	private static List<String> findViolations(Path path) {
		try {
			List<String> lines = Files.readAllLines(path);
			return java.util.stream.IntStream.range(0, lines.size())
				.filter(index -> containsConsoleOutput(lines.get(index)))
				.mapToObj(index -> path + ":" + (index + 1))
				.toList();
		}
		catch (IOException e) {
			throw new IllegalStateException("Unable to inspect " + path, e);
		}
	}

	private static boolean containsConsoleOutput(String line) {
		return line.contains("System.out") || line.contains("System.err") || line.contains(".printStackTrace(");
	}
}
