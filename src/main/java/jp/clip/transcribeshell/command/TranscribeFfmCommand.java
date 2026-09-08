package jp.clip.transcribeshell.command;

import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jp.clip.transcribeshell.process.ProcessStartException;
import jp.clip.transcribeshell.service.FfmOptions;
import jp.clip.transcribeshell.service.FfmTranscribeService;
import jp.clip.transcribeshell.service.TranscribeResult;
import lombok.RequiredArgsConstructor;

/**
 * MP3 を分割し、whisper.cpp を FFM（JVM 内）で呼んで文字起こしし、1 ファイルに結合するコマンド。
 *
 * <p>設定は {@code transcribe.ffm.*}。外部バイナリ {@code whisper-cli} を使う経路は
 * {@code transcribe --engine cpp}（設定は {@code transcribe.whisper.cpp.*}）で、こちらとは別物。
 * どちらも中身は whisper.cpp なので、旧名 {@code transcribe-cpp} だと {@code --engine cpp} と
 * 区別が付かなかった。そのため {@code transcribe-ffm} に改名し、旧名は {@code alias} で残してある。
 * 使い分けは README の「どのエンジンを選ぶか」を参照。
 *
 * <p>オプションの並びと位置引数の扱いは {@code transcribe} と揃え、追加分は
 * {@code --threads}・{@code --vad}・{@code --prompt}・{@code --beam-search} のみ。
 * Python も外部バイナリも不要で、必要なのは ffmpeg と ggml モデルファイルだけ。
 */
@Component
@RequiredArgsConstructor
public class TranscribeFfmCommand {

	private final FfmTranscribeService ffmTranscribeService;

	// alias は旧名。1 週間の試用で作った transcribe-cpp_* の出力や手元のメモが残っているので、
	// しばらくはどちらでも動くようにしておく
	@Command(name = "transcribe-ffm", alias = "transcribe-cpp",
			description = "MP3を分割しwhisper.cpp(FFM)で文字起こしして結合する")
	public String transcribeFfm(
			@Argument(index = 0, description = "入力MP3の絶対パス（-f の代わりに先頭に直接指定）", defaultValue = "")
			String positionalFile,
			@Option(shortName = 'f', longName = "file", description = "入力MP3の絶対パス（位置引数でも可）")
			String fileOption,
			@Option(shortName = 'm', longName = "model", description = "ggmlモデル名(tiny/base/small/large-v3-turbo-q5_0 等)またはファイルパス", defaultValue = "small")
			String model,
			@Option(shortName = 'l', longName = "language", description = "言語(Japanese/ja/auto)", defaultValue = "Japanese")
			String language,
			@Option(longName = "segment-time", description = "分割秒数(既定600=10分)", defaultValue = "600")
			int segmentTime,
			@Option(shortName = 'o', longName = "output-dir", description = "出力フォルダ(省略時は入力と同階層のtranscribe-ffm_<base>)")
			String outputDir,
			@Option(longName = "force", description = "文字起こし済みpartも再実行する", defaultValue = "false")
			boolean force,
			@Option(shortName = 't', longName = "threads", description = "スレッド数(0=設定transcribe.ffm.threads)", defaultValue = "0")
			int threads,
			@Option(longName = "vad", description = "VAD(無音区間の除去)を使うか。--vad / --vad false。省略時は設定transcribe.ffm.vad")
			Boolean vad,
			@Option(longName = "beam-search", description = "beam searchで探索するか。省略時は設定transcribe.ffm.beam-search")
			Boolean beamSearch,
			@Option(shortName = 'p', longName = "prompt", description = "初期プロンプト(参加者名や用語のヒント。省略時は設定transcribe.ffm.initial-prompt(-file))")
			String prompt) {

		try {
			String file = resolveFile(positionalFile, fileOption);
			FfmOptions options = FfmOptions.builder()
					.model(model)
					.language(language)
					.segmentTime(segmentTime)
					.outputDir(outputDir)
					.force(force)
					.threads(threads)
					.vad(vad)
					.beamSearch(beamSearch)
					.prompt(prompt)
					.build();
			TranscribeResult result = ffmTranscribeService.run(file, options);
			return "完了: " + result.mergedFile();
		} catch (IllegalArgumentException e) {
			// 入力不備（ファイル・モデル不在など）
			return "エラー: " + e.getMessage();
		} catch (ProcessStartException e) {
			// ffmpeg が起動できない
			return "エラー: 外部コマンドを起動できません -> " + e.getExecutable()
					+ "\n  設定 transcribe.ffmpeg-path で実行ファイルのパスを指定できます。";
		} catch (IllegalStateException e) {
			// 分割・文字起こしの実行失敗。ネイティブが読み込めない場合の
			// NativeUnavailableException もこの部分型なので、案内文はそのまま表示される
			return "エラー: " + e.getMessage();
		}
	}

	/**
	 * 位置引数とオプション（-f/--file）のどちらで指定されたファイルパスを使うか決める。
	 * {@code transcribe} と同じ規則（両方指定・未指定はエラー）。
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
					"ファイルを指定してください（例: transcribe-ffm \"C:\\...\\xxx.MP3\" もしくは -f \"C:\\...\\xxx.MP3\"）");
		}
		return hasPositional ? positionalFile : fileOption;
	}
}
