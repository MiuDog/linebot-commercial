package dev.myudog.assetsmanagerlinebot.observability;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ChineseCommentCoverageTest {

	private static final Pattern CHINESE_CHARACTER = Pattern.compile("\\p{IsHan}");
	private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\s*\\(");

	@Test
	void everyProductionMethodAndLogCallHasAChineseCommentImmediatelyAbove() throws IOException {
		Path sourceRoot = Path.of("src", "main", "java");
		List<Path> sourceFiles;
		try (var files = Files.walk(sourceRoot)) {
			sourceFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
		}

		List<String> violations = new ArrayList<>();
		inspectMethodComments(sourceFiles, violations);
		inspectLogComments(sourceFiles, violations);

		assertThat(violations).as("每個方法與 log 呼叫上方都必須有中文註解").isEmpty();
	}

	private static void inspectMethodComments(List<Path> sourceFiles, List<String> violations) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		try (
			StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)
		) {
			JavacTask task = (JavacTask) compiler.getTask(
				null,
				fileManager,
				null,
				List.of("-proc:none"),
				null,
				fileManager.getJavaFileObjectsFromPaths(sourceFiles)
			);
			Trees trees = Trees.instance(task);
			for (CompilationUnitTree unit : task.parse()) {
				List<String> lines = Files.readAllLines(Path.of(unit.getSourceFile().toUri()), StandardCharsets.UTF_8);
				new TreeScanner<Void, Void>() {
					@Override
					public Void visitMethod(MethodTree method, Void unused) {
						long start = trees.getSourcePositions().getStartPosition(unit, method);
						int lineNumber = Math.toIntExact(unit.getLineMap().getLineNumber(start));
						if (!hasChineseMarkerAbove(lines, lineNumber, "// 方法：")) {
							violations.add(unit.getSourceFile().getName() + ":" + lineNumber + " 缺少中文方法註解");
						}
						return super.visitMethod(method, unused);
					}
				}.scan(unit, null);
			}
		}
	}

	private static void inspectLogComments(List<Path> sourceFiles, List<String> violations) throws IOException {
		for (Path sourceFile : sourceFiles) {
			List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
			for (int index = 0; index < lines.size(); index++) {
				if (LOG_CALL.matcher(lines.get(index)).find() && !hasChineseMarkerAbove(lines, index + 1, "// 日誌：")) {
					violations.add(sourceFile + ":" + (index + 1) + " 缺少中文日誌註解");
				}
			}
		}
	}

	private static boolean hasChineseMarkerAbove(List<String> lines, int declarationLineNumber, String marker) {
		for (int index = declarationLineNumber - 2; index >= 0; index--) {
			String candidate = lines.get(index).trim();
			if (candidate.isEmpty()) continue;

			return candidate.startsWith(marker) && CHINESE_CHARACTER.matcher(candidate).find();
		}
		return false;
	}
}
