package jp.clip.transcribeshell.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

/**
 * フォルダ直下の「まだ分割されていない元 MP3」を列挙する。
 *
 * <p>{@code transcribe-all} と {@code transcribe-ffm-all} が同じ規則で対象を選ぶ必要があるため、
 * どちらのコマンドからも呼べるようにここへ切り出してある（以前は両コマンドに同じ実装が写っていた）。
 */
@Service
public class Mp3FileFinder {

	/**
	 * root 直下（非再帰）の処理対象 MP3 を名前昇順で列挙する。
	 *
	 * <p>対象は「通常ファイル・拡張子 {@code .mp3}（大小無視）・名前が {@code part_} で始まらない」もの。
	 * 出力フォルダ（{@code transcribe_*} / {@code transcribe-ffm_*}）はディレクトリなので
	 * {@link Files#isRegularFile} で自然に除外され、分割済みの {@code part_*.mp3} は接頭辞で除外する
	 * （{@code --dir} を誤って出力フォルダに向けても元録音以外を拾わない）。
	 *
	 * @param root 走査するフォルダ
	 * @return 対象ファイルの一覧（名前昇順）
	 * @throws UncheckedIOException 走査に失敗した場合
	 */
	public List<Path> findIn(Path root) {
		try (Stream<Path> stream = Files.list(root)) {
			return stream
					.filter(Files::isRegularFile)
					.filter(p -> {
						String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
						return name.endsWith(".mp3") && !name.startsWith("part_");
					})
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("フォルダの走査に失敗しました: " + root, e);
		}
	}
}
