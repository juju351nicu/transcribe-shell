package jp.clip.transcribeshell.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

/**
 * {@code part_*.txt} を名前昇順に結合し、{@code <base>_all.txt} を UTF-8(BOMなし) で書き出す。
 */
@Service
public class TranscriptMergeService {

	/**
	 * outputDir 内の part_*.txt を結合して <base>_all.txt を書き出す。
	 *
	 * @param outputDir part_*.txt が置かれたディレクトリ
	 * @param base      拡張子を除いた元ファイル名（例: sample_001）
	 * @return 書き出した結合ファイルのパス
	 */
	public Path merge(Path outputDir, String base) {
		List<Path> txts = listTxts(outputDir);
		if (txts.isEmpty()) {
			throw new IllegalStateException("結合対象の part_*.txt が見つかりません: " + outputDir);
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < txts.size(); i++) {
			String content = readUtf8(txts.get(i));
			// 末尾の改行を正規化し、part 間には空行を1つ入れる。
			sb.append(content.stripTrailing());
			if (i < txts.size() - 1) {
				sb.append("\n\n");
			}
		}
		sb.append("\n");

		Path out = outputDir.resolve(base + "_all.txt");
		try {
			// BOM なし UTF-8 で書き出す。
			Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("結合ファイルの書き出しに失敗しました: " + out, e);
		}
		return out;
	}

	/** outputDir 内の part_*.txt を名前昇順で列挙する。 */
	List<Path> listTxts(Path outputDir) {
		try (Stream<Path> stream = Files.list(outputDir)) {
			return stream
					.filter(p -> {
						String name = p.getFileName().toString();
						return name.startsWith("part_") && name.endsWith(".txt");
					})
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("part_*.txt の列挙に失敗しました: " + outputDir, e);
		}
	}

	private String readUtf8(Path txt) {
		try {
			return Files.readString(txt, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("part テキストの読み込みに失敗しました: " + txt, e);
		}
	}
}
