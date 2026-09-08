package jp.clip.transcribeshell.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jp.clip.transcribeshell.config.TranscribeProperties;
import jp.clip.transcribeshell.service.FfmOptions;
import jp.clip.transcribeshell.service.FfmTranscribeService;
import jp.clip.transcribeshell.service.Mp3FileFinder;
import jp.clip.transcribeshell.service.NativeUnavailableException;
import jp.clip.transcribeshell.service.TranscribeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * フォルダ直下の未処理 MP3 を whisper.cpp（FFM）でまとめて文字起こしするコマンド。
 *
 * <p>{@link TranscribeAllCommand} の FFM 版。{@link FfmTranscribeService} を各ファイルに対して呼ぶだけなので
 * 冪等性はそのまま活きる（出力フォルダ {@code transcribe-ffm_<base>} に {@code .txt} がそろっていれば何もしない）。
 * 1 件失敗してもバッチは止めず、最後に「処理／スキップ／失敗」の件数サマリを出す。
 * ただし {@link NativeUnavailableException}（この OS 用のネイティブが無い）だけは環境の問題で、
 * 残りを試しても全件同じ理由で失敗するので、その場で打ち切って残件数を報告する。
 *
 * <p>対象 MP3 の列挙は {@link Mp3FileFinder} を {@code transcribe-all} と共用する。
 * 走査フォルダの既定値も {@code transcribe.default-dir} を共用する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscribeFfmAllCommand {

	private final FfmTranscribeService ffmTranscribeService;
	private final Mp3FileFinder mp3FileFinder;
	private final TranscribeProperties properties;

	// alias は旧名（TranscribeFfmCommand と同じ理由）
	@Command(name = "transcribe-ffm-all", alias = "transcribe-cpp-all",
			description = "フォルダ直下の未処理MP3をwhisper.cpp(FFM)でまとめて文字起こしする")
	public String transcribeFfmAll(
			@Option(shortName = 'd', longName = "dir", description = "走査するフォルダ（省略時は設定 transcribe.default-dir）")
			String dir,
			@Option(shortName = 'm', longName = "model", description = "ggmlモデル名またはファイルパス", defaultValue = "small")
			String model,
			@Option(shortName = 'l', longName = "language", description = "言語(Japanese/ja/auto)", defaultValue = "Japanese")
			String language,
			@Option(longName = "segment-time", description = "分割秒数(既定600=10分)", defaultValue = "600")
			int segmentTime,
			@Option(longName = "force", description = "文字起こし済みpartも再実行する", defaultValue = "false")
			boolean force,
			@Option(shortName = 't', longName = "threads", description = "スレッド数(0=設定transcribe.ffm.threads)", defaultValue = "0")
			int threads,
			@Option(longName = "vad", description = "VADを使うか。省略時は設定transcribe.ffm.vad")
			Boolean vad,
			@Option(longName = "beam-search", description = "beam searchで探索するか。省略時は設定transcribe.ffm.beam-search")
			Boolean beamSearch,
			@Option(shortName = 'p', longName = "prompt", description = "初期プロンプト。省略時は設定transcribe.ffm.initial-prompt(-file)")
			String prompt) {

		String targetDir = StringUtils.hasText(dir) ? dir : properties.getDefaultDir();
		Path root = Path.of(targetDir);
		if (!Files.isDirectory(root)) {
			return "エラー: フォルダが見つかりません: " + root;
		}

		List<Path> targets = mp3FileFinder.findIn(root);
		if (targets.isEmpty()) {
			log.info("対象なし: {} に処理対象の *.mp3 はありません", root);
			return "対象なし: " + root;
		}

		// outputDir=null で各ファイル同階層に transcribe-ffm_<base> を生成する
		FfmOptions options = FfmOptions.builder()
				.model(model)
				.language(language)
				.segmentTime(segmentTime)
				.outputDir(null)
				.force(force)
				.threads(threads)
				.vad(vad)
				.beamSearch(beamSearch)
				.prompt(prompt)
				.build();

		int processed = 0;
		int skipped = 0;
		int failed = 0;
		int index = 0;
		for (Path mp3 : targets) {
			index++;
			log.info("=== [{}/{}] {} ===", index, targets.size(), mp3.getFileName());
			try {
				TranscribeResult result = ffmTranscribeService.run(mp3.toString(), options);
				if (result.anyWorkDone()) {
					processed++;
				} else {
					skipped++;
				}
			} catch (NativeUnavailableException e) {
				// 環境の問題なので、残りを試しても全件同じ理由で失敗する。ここで打ち切る。
				// catch(Exception) より前に置く必要がある（部分型なので後ろだとコンパイルエラー）
				int remaining = targets.size() - index;
				log.error("中断: {}", e.getMessage());
				return String.format("中断: ネイティブライブラリを読み込めないため残り %d 件を処理していません%n%s%n処理: %d件 / スキップ: %d件 / 失敗: %d件",
						remaining, e.getMessage(), processed, skipped, failed);
			} catch (Exception e) {
				// 1 件の失敗でバッチを止めない。原因を残して次へ
				failed++;
				log.error("失敗: {} -> {}", mp3.getFileName(), e.getMessage());
			}
		}

		String summary = String.format("処理: %d件 / スキップ: %d件 / 失敗: %d件", processed, skipped, failed);
		log.info(summary);
		return summary;
	}
}
