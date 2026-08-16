package jp.clip.transcribeshell.command;

import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jp.clip.transcribeshell.process.ProcessStartException;
import jp.clip.transcribeshell.service.TranscribeResult;
import jp.clip.transcribeshell.service.TranscribeService;
import lombok.RequiredArgsConstructor;

/**
 * MP3 を分割し Whisper で文字起こしして 1 ファイルに結合するコマンド。
 *
 * <p>入力ファイルは「先頭の位置引数」でも「{@code -f}/{@code --file} オプション」でも指定できる
 * （{@code transcribe "C:\...\x.MP3"} と {@code transcribe -f "C:\...\x.MP3"} の両対応）。
 * 位置引数はドラッグ＆ドロップ起動とも相性が良い。両方指定・未指定はそれぞれ明確なエラーにする。
 */
@Component
@RequiredArgsConstructor
public class TranscribeCommand {

	private final TranscribeService transcribeService;

	@Command(name = "transcribe", description = "MP3を分割しWhisperで文字起こしして結合する")
	public String transcribe(
			@Argument(index = 0, description = "入力MP3の絶対パス（-f の代わりに先頭に直接指定）", defaultValue = "")
			String positionalFile,
			@Option(shortName = 'f', longName = "file", description = "入力MP3の絶対パス（位置引数でも可）")
			String fileOption,
			@Option(shortName = 'm', longName = "model", description = "Whisperモデル(tiny/base/small/medium/large)", defaultValue = "small")
			String model,
			@Option(shortName = 'l', longName = "language", description = "言語", defaultValue = "Japanese")
			String language,
			@Option(longName = "segment-time", description = "分割秒数(既定600=10分)", defaultValue = "600")
			int segmentTime,
			@Option(shortName = 'o', longName = "output-dir", description = "出力フォルダ(省略時は入力と同階層のtranscribe_<base>)")
			String outputDir,
			@Option(longName = "force", description = "文字起こし済みpartも再実行する", defaultValue = "false")
			boolean force) {

		try {
			String file = resolveFile(positionalFile, fileOption);
			TranscribeResult result = transcribeService.run(file, model, language, segmentTime, outputDir, force);
			return "完了: " + result.mergedFile();
		} catch (IllegalArgumentException e) {
			// 入力不備（ファイル不在など）
			return "エラー: " + e.getMessage();
		} catch (ProcessStartException e) {
			// ffmpeg / py が起動できない
			return "エラー: 外部コマンドを起動できません -> " + e.getExecutable()
					+ "\n  設定 transcribe.ffmpeg-path / transcribe.py-path で実行ファイルのパスを指定できます。";
		} catch (IllegalStateException e) {
			// 分割・文字起こしの実行失敗（非ゼロ終了など）
			return "エラー: " + e.getMessage();
		}
	}

	/**
	 * 位置引数とオプション（-f/--file）のどちらで指定されたファイルパスを使うか決める。
	 *
	 * @return 使用するファイルパス
	 * @throws IllegalArgumentException 両方指定された場合、およびどちらも未指定の場合
	 */
	private String resolveFile(String positionalFile, String fileOption) {
		boolean hasPositional = StringUtils.hasText(positionalFile);
		boolean hasOption = StringUtils.hasText(fileOption);
		if (hasPositional && hasOption) {
			throw new IllegalArgumentException("ファイルは位置引数と -f/--file のどちらか一方で指定してください");
		}
		if (!hasPositional && !hasOption) {
			throw new IllegalArgumentException(
					"ファイルを指定してください（例: transcribe \"C:\\...\\xxx.MP3\" もしくは -f \"C:\\...\\xxx.MP3\"）");
		}
		return hasPositional ? positionalFile : fileOption;
	}
}
