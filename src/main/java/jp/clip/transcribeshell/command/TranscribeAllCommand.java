package jp.clip.transcribeshell.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jp.clip.transcribeshell.config.TranscribeProperties;
import jp.clip.transcribeshell.service.TranscribeResult;
import jp.clip.transcribeshell.service.TranscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * フォルダ直下の未処理 MP3 をまとめて文字起こしするコマンド。
 *
 * <p>既存 {@link TranscribeService} を各ファイルに対して呼ぶだけなので、冪等性はそのまま活きる
 * （何度回しても新しい録音だけが実処理される）。1件失敗してもバッチは止めず、最後に
 * 「処理／スキップ／失敗」の件数サマリを出す。毎晩の自動実行で「昨夜、新規が何件処理されたか」を
 * 一目で分かるようにするのが狙い。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscribeAllCommand {

	private final TranscribeService transcribeService;
	private final TranscribeProperties properties;

	@Command(name = "transcribe-all", description = "フォルダ直下の未処理MP3をまとめて文字起こしする")
	public String transcribeAll(
			@Option(shortName = 'd', longName = "dir", description = "走査するフォルダ（省略時は設定 transcribe.default-dir）")
			String dir,
			@Option(shortName = 'm', longName = "model", description = "Whisperモデル(tiny/base/small/medium/large)", defaultValue = "small")
			String model,
			@Option(shortName = 'l', longName = "language", description = "言語", defaultValue = "Japanese")
			String language,
			@Option(longName = "segment-time", description = "分割秒数(既定600=10分)", defaultValue = "600")
			int segmentTime,
			@Option(longName = "force", description = "文字起こし済みpartも再実行する", defaultValue = "false")
			boolean force) {

		// --dir 未指定なら設定の既定フォルダを使う（マシン固有パスを外部化）。
		String targetDir = StringUtils.hasText(dir) ? dir : properties.getDefaultDir();
		Path root = Path.of(targetDir);
		if (!Files.isDirectory(root)) {
			return "エラー: フォルダが見つかりません: " + root;
		}

		List<Path> targets = listTargets(root);
		if (targets.isEmpty()) {
			log.info("対象なし: {} に処理対象の *.mp3 はありません", root);
			return "対象なし: " + root;
		}

		int processed = 0;
		int skipped = 0;
		int failed = 0;
		int index = 0;
		for (Path mp3 : targets) {
			index++;
			log.info("=== [{}/{}] {} ===", index, targets.size(), mp3.getFileName());
			try {
				// outputDir=null で各ファイル同階層に transcribe_<base> を生成（既存仕様）。
				TranscribeResult result = transcribeService.run(mp3.toString(), model, language, segmentTime, null, force);
				if (result.anyWorkDone()) {
					processed++;
				} else {
					skipped++;
				}
			} catch (Exception e) {
				// 1件の失敗でバッチを止めない。原因を残して次へ。
				failed++;
				log.error("失敗: {} -> {}", mp3.getFileName(), e.getMessage());
			}
		}

		String summary = String.format("処理: %d件 / スキップ: %d件 / 失敗: %d件", processed, skipped, failed);
		log.info(summary);
		return summary;
	}

	/**
	 * root 直下（非再帰）の処理対象 MP3 を名前昇順で列挙する。
	 *
	 * <p>対象は「通常ファイル・拡張子 {@code .mp3}（大小無視）・名前が {@code part_} で始まらない」もの。
	 * {@code transcribe_*} はディレクトリなので {@code isRegularFile} で自然に除外され、分割済みの
	 * {@code part_*.mp3} は接頭辞で除外する（{@code --dir} を誤って出力フォルダに向けても元録音以外を拾わない）。
	 */
	List<Path> listTargets(Path root) {
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
