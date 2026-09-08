package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Mp3FileFinder} の抽出規則を検証する。
 *
 * <p>{@code transcribe-all} と {@code transcribe-cpp-all} が共用する部分なので、
 * 規則をここ 1 か所で固定する。
 */
class Mp3FileFinderTest {

	private final Mp3FileFinder finder = new Mp3FileFinder();

	@Test
	void 直下のmp3だけを名前昇順で返す(@TempDir Path dir) throws IOException {
		Files.createFile(dir.resolve("b.MP3"));          // 大文字拡張子も対象
		Files.createFile(dir.resolve("a.mp3"));
		Files.createFile(dir.resolve("part_000.mp3"));   // 分割済み part は対象外
		Files.createFile(dir.resolve("memo.txt"));       // mp3 以外
		Files.createDirectory(dir.resolve("transcribe_a"));
		Files.createFile(dir.resolve("transcribe_a").resolve("c.mp3")); // 非再帰なので対象外

		assertThat(finder.findIn(dir))
				.extracting(p -> p.getFileName().toString())
				.containsExactly("a.mp3", "b.MP3");
	}

	@Test
	void 対象が無ければ空を返す(@TempDir Path dir) {
		assertThat(finder.findIn(dir)).isEmpty();
	}
}
