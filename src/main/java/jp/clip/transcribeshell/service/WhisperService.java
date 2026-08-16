package jp.clip.transcribeshell.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jp.clip.transcribeshell.config.PartFormat;
import jp.clip.transcribeshell.config.TranscribeProperties;
import jp.clip.transcribeshell.config.WhisperEngine;
import jp.clip.transcribeshell.process.ProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 設定されたエンジン（faster / openai / cpp）で part ごとに文字起こしする。
 *
 * <p>冪等性: part に対応する {@code .txt} が既に存在すればスキップする
 * （{@code force=true} のときは既存でも再実行）。
 * part の列挙はシェルのワイルドカードに頼らず {@link Files#list} で行う。
 *
 * <p>part の拡張子はエンジンによって変わりうるため {@link PartFormat} から取得する
 * （分割側の {@link FfmpegService} と同じ判定を通すことで、両者の食い違いを防ぐ）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhisperService {

	/**
	 * whisper 子プロセスに渡す環境変数。
	 *
	 * <p>{@code PYTHONUTF8=1} で Python の標準出力を UTF-8 に固定し、{@link ProcessRunner} の UTF-8 読み取りと
	 * 一致させて進捗表示の文字化けを防ぐ。whisper の出力ファイルは元々 UTF-8 で書かれるため、ファイル内容には影響しない。
	 * cpp エンジン（Python を使わない）では無意味だが、渡しても害はないためエンジンで分けない。
	 */
	private static final Map<String, String> WHISPER_ENV = Map.of("PYTHONUTF8", "1");

	/** ggml モデルの公式配布元。モデル未検出時の案内に使う。 */
	private static final String GGML_MODEL_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

	private final ProcessRunner processRunner;
	private final TranscribeProperties properties;

	/**
	 * outputDir 内の part を名前昇順に文字起こしする。
	 *
	 * <p>エンジン比較（faster / openai / cpp のどれが速いか）を実測できるよう、part ごとと全体の
	 * 経過時間をログに出す。
	 *
	 * @param outputDir part が置かれた作業ディレクトリ
	 * @param model     Whisper モデル
	 * @param language  言語
	 * @param force     true なら .txt が既存でも再実行する
	 * @return 今回新規に文字起こしした part 数（既存 .txt でスキップしたものは数えない）。
	 *         一括処理の「処理/スキップ」判定に使う
	 */
	public int transcribeAll(Path outputDir, String model, String language, boolean force) {
		PartFormat format = PartFormat.resolve(properties, outputDir);
		WhisperEngine engine = WhisperEngine.from(properties.getWhisper().getEngine());
		List<Path> parts = listParts(outputDir, format);
		if (parts.isEmpty()) {
			throw new IllegalStateException(
					"分割された part_*" + format.getExtension() + " が見つかりません: " + outputDir);
		}

		log.info("[4/4] 文字起こし: {}件のpartを処理します (engine={}, model={})", parts.size(), engine.settingName(), model);
		long minPartBytes = properties.getWhisper().getMinPartBytes();
		long batchStart = System.nanoTime();
		int transcribed = 0;
		for (Path part : parts) {
			Path txt = txtFor(part, format);
			if (!force && Files.exists(txt)) {
				log.info("      {} ... skip (txtあり)", part.getFileName());
				continue;
			}
			if (isRemnantPart(part, minPartBytes)) {
				continue;
			}
			log.info("      {} ... 実行中", part.getFileName());
			long partStart = System.nanoTime();
			int exit = processRunner.run(buildCommand(part, outputDir, model, language, engine, format), outputDir,
					WHISPER_ENV);
			// FP16 警告はエラーではないため、成否は終了コードのみで判定する。
			if (exit != 0) {
				throw new IllegalStateException(
						"whisper が異常終了しました (exit=" + exit + ", part=" + part.getFileName() + ")");
			}
			verifyTxtCreated(engine, part, txt);
			transcribed++;
			log.info("      {} ... done ({})", part.getFileName(), elapsedSince(partStart));
		}
		if (transcribed > 0) {
			log.info("[4/4] 文字起こし完了: {}件 / 合計 {} (engine={}, model={})",
					transcribed, elapsedSince(batchStart), engine.settingName(), model);
		}
		return transcribed;
	}

	/**
	 * part に対する文字起こし実行コマンドを、設定されたエンジンに応じて組み立てる。
	 *
	 * <p>faster（whisper-ctranslate2）と openai（py -m whisper）は引数名が共通なので、差分は実行ファイルと
	 * faster 固有の {@code --output_format} / {@code --compute_type} のみ。cpp（whisper-cli）は引数体系が
	 * まったく別（{@code -m} が .bin のパス、{@code -of} が拡張子なしの出力ベース名）なので独立に組み立てる。
	 */
	List<String> buildCommand(Path part, Path outputDir, String model, String language, WhisperEngine engine,
			PartFormat format) {
		TranscribeProperties.Whisper whisper = properties.getWhisper();
		return switch (engine) {
			case CPP -> buildCppCommand(part, model, language, whisper.getCpp(), format);
			case OPENAI -> buildOpenaiCommand(part, outputDir, model, language);
			case FASTER -> buildFasterCommand(part, outputDir, model, language, whisper);
		};
	}

	/** faster エンジン（whisper-ctranslate2）のコマンド配列。 */
	private List<String> buildFasterCommand(Path part, Path outputDir, String model, String language,
			TranscribeProperties.Whisper whisper) {
		List<String> command = new ArrayList<>();
		command.add(whisper.getCommand());
		command.add(part.toString());
		command.add("--language");
		command.add(language);
		command.add("--model");
		command.add(model);
		command.add("--output_dir");
		command.add(outputDir.toString());
		command.add("--output_format");
		command.add(whisper.getOutputFormat());
		command.add("--compute_type");
		command.add(whisper.getComputeType());
		return command;
	}

	/** openai エンジン（py -m whisper）のコマンド配列。従来どおり。 */
	private List<String> buildOpenaiCommand(Path part, Path outputDir, String model, String language) {
		List<String> command = new ArrayList<>();
		command.add(properties.getPyPath());
		command.add("-m");
		command.add("whisper");
		command.add(part.toString());
		command.add("--language");
		command.add(language);
		command.add("--model");
		command.add(model);
		command.add("--output_dir");
		command.add(outputDir.toString());
		return command;
	}

	/**
	 * cpp エンジン（whisper-cli）のコマンド配列。
	 *
	 * <p>{@code -of} には<b>拡張子を除いたパス</b>を渡す。省略すると入力ファイル名（拡張子込み）が
	 * ベースになり {@code part_000.mp3.txt} が作られてしまい、結合側（{@code part_*.txt}）と噛み合わないため。
	 *
	 * <p>{@code -l} は whisper-cli 側で小文字化されて言語名でも解決されるので、既定の {@code Japanese} を
	 * そのまま渡せる。ISO コードを強制したい場合だけ {@code transcribe.whisper.cpp.language} で上書きする。
	 *
	 * <p>{@code -mc}（max-context）は既定 0 を渡す。whisper-cli の既定（無制限）だと直前のテキストを
	 * 引きずって同じ文を繰り返す幻覚ループが多発するため（実測で重複行 78.9% → 4.1%）。
	 *
	 * <p>{@code -t} は threads が 0 のとき渡さない（whisper-cli 既定の min(4, CPU数) に任せる）。
	 */
	private List<String> buildCppCommand(Path part, String model, String language, TranscribeProperties.Cpp cpp,
			PartFormat format) {
		List<String> command = new ArrayList<>();
		command.add(cpp.getCommand());
		command.add("-m");
		command.add(resolveCppModelFile(cpp, model).toString());
		command.add("-f");
		command.add(part.toString());
		command.add("-l");
		command.add(StringUtils.hasText(cpp.getLanguage()) ? cpp.getLanguage().trim() : language);
		command.add("-otxt");
		command.add("-of");
		command.add(part.resolveSibling(partBaseName(part, format)).toString());
		// 既定 0（文脈を引き継がない）で幻覚ループを抑える。負値なら whisper-cli の既定に任せる。
		if (cpp.getMaxContext() >= 0) {
			command.add("-mc");
			command.add(String.valueOf(cpp.getMaxContext()));
		}
		if (cpp.getThreads() > 0) {
			command.add("-t");
			command.add(String.valueOf(cpp.getThreads()));
		}
		return command;
	}

	/**
	 * モデル名（例: small）から ggml モデルファイル（例: ggml-small.bin）を解決する。
	 *
	 * <p>whisper-cli はモデル「名」ではなく .bin のパスを取るため、他エンジンとの橋渡しとして
	 * 「置き場 + 命名規則」で解決する。見つからない場合は、探したパスと入手方法まで含めた例外にする
	 * （{@code TranscribeCommand} が握って「エラー: ...」として表示するので、スタックトレースにはならない）。
	 *
	 * @param cpp   cpp エンジンの設定
	 * @param model モデル名（{@code --model} の値）
	 * @return 実在する ggml モデルファイルのパス
	 * @throws IllegalStateException 置き場が未設定、またはモデルファイルが見つからない場合
	 */
	private Path resolveCppModelFile(TranscribeProperties.Cpp cpp, String model) {
		if (!StringUtils.hasText(cpp.getModelDir())) {
			throw new IllegalStateException("ggml モデルの置き場が未設定です。transcribe.whisper.cpp.model-dir を設定してください。");
		}
		String fileName = cpp.getModelFilePattern().replace("{model}", model);
		Path modelFile = Path.of(cpp.getModelDir()).resolve(fileName);
		if (!Files.isRegularFile(modelFile)) {
			String url = GGML_MODEL_BASE_URL + fileName;
			throw new IllegalStateException("ggml モデルが見つかりません: " + modelFile + System.lineSeparator()
					+ "  置き場は transcribe.whisper.cpp.model-dir、命名規則は transcribe.whisper.cpp.model-file-pattern で変更できます。"
					+ System.lineSeparator()
					+ "  入手: " + url + System.lineSeparator()
					+ "    Windows    : Invoke-WebRequest -Uri \"" + url + "\" -OutFile \"" + modelFile + "\""
					+ System.lineSeparator()
					+ "    Mac/Ubuntu : curl -L -o \"" + modelFile + "\" \"" + url + "\"");
		}
		return modelFile;
	}

	/**
	 * cpp エンジンのとき、終了コード 0 でも出力 txt が作られたかを確認する。
	 *
	 * <p>whisper-cli は言語指定が不正だと usage を出して <b>終了コード 0 のまま</b>終了し、音声のデコードに
	 * 失敗した場合も次のファイルへ進んで 0 で終わる（実機で再現確認済み）。終了コードだけでは失敗を検出できず、
	 * 後段の結合で初めて気づくことになるため、ここで出力の有無を確かめる。
	 * faster / openai は失敗時に非ゼロで終わるので対象外（従来の挙動を変えない）。
	 */
	private void verifyTxtCreated(WhisperEngine engine, Path part, Path txt) {
		if (engine != WhisperEngine.CPP || Files.exists(txt)) {
			return;
		}
		throw new IllegalStateException("whisper-cli は正常終了しましたが出力が作られていません: " + txt.getFileName()
				+ " (part=" + part.getFileName() + ")" + System.lineSeparator()
				+ "  次のいずれかが原因の可能性があります（いずれも whisper-cli は終了コード 0 で終わります）:"
				+ System.lineSeparator()
				+ "    - 言語指定が不正: --language または transcribe.whisper.cpp.language の値を確認してください。"
				+ System.lineSeparator()
				+ "    - part の長さがほぼ 0: 分割の端数で極小の part ができるとデコードに失敗します。"
				+ "その part を削除して再実行してください（" + part.getFileName() + " のサイズを確認）。"
				+ System.lineSeparator()
				+ "  詳しい理由は直前の whisper-cli の出力に表示されています。");
	}

	/**
	 * 分割の端数とみなせる極小 part なら、警告ログを出して true を返す（呼び出し側はスキップする）。
	 *
	 * <p>録音長が {@code --segment-time} の倍数に近いと、長さがほぼ 0 の part が末尾にできる。これを
	 * whisper に渡すとエンジンによってはデコードに失敗し、夜間の {@code transcribe-all} が丸ごと止まって
	 * 手作業の復旧が必要になる。1 つの端数のために全体を落とさないよう、警告を残して先へ進める。
	 *
	 * <p>エンジンで挙動を変えない（faster / openai では従来「黙って素通り」していたが、警告が出るようになる）。
	 * 判定は文字起こしを実行する前に行うので、後段の「終了コード 0 なのに txt が無い」チェックは
	 * ここを通過した part にだけ適用される。
	 *
	 * @param part         判定する part
	 * @param minPartBytes 閾値（バイト）。0 以下なら判定を行わない
	 * @return スキップすべきなら true
	 */
	private boolean isRemnantPart(Path part, long minPartBytes) {
		if (minPartBytes <= 0) {
			return false;
		}
		long size = fileSize(part);
		if (size >= minPartBytes) {
			return false;
		}
		log.warn("      {} ... skip (サイズ {} バイトが閾値 {} バイト未満。分割の端数とみなしました。"
				+ "閾値は transcribe.whisper.min-part-bytes で変更できます)", part.getFileName(), size, minPartBytes);
		return true;
	}

	/** part のファイルサイズ。取得できない場合は入出力エラーとして扱う。 */
	private long fileSize(Path part) {
		try {
			return Files.size(part);
		} catch (IOException e) {
			throw new UncheckedIOException("part のサイズ取得に失敗しました: " + part, e);
		}
	}

	/** outputDir 内の part を名前昇順で列挙する（拡張子は {@link PartFormat} が決める）。 */
	List<Path> listParts(Path outputDir, PartFormat format) {
		try (Stream<Path> stream = Files.list(outputDir)) {
			return stream
					.filter(p -> {
						String name = p.getFileName().toString();
						return name.startsWith("part_") && name.endsWith(format.getExtension());
					})
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("part の列挙に失敗しました: " + outputDir, e);
		}
	}

	/** part_000.mp3 → part_000.txt。 */
	private Path txtFor(Path part, PartFormat format) {
		return part.resolveSibling(partBaseName(part, format) + ".txt");
	}

	/** part_000.mp3 → part_000（拡張子を除いたファイル名）。 */
	private String partBaseName(Path part, PartFormat format) {
		String name = part.getFileName().toString();
		return name.substring(0, name.length() - format.getExtension().length());
	}

	/** 開始時刻（{@link System#nanoTime()}）からの経過を「3分12秒」「45秒」形式にする。 */
	private String elapsedSince(long startNanos) {
		long seconds = Math.round((System.nanoTime() - startNanos) / 1_000_000_000.0);
		long minutes = seconds / 60;
		return minutes > 0 ? minutes + "分" + (seconds % 60) + "秒" : seconds + "秒";
	}
}
