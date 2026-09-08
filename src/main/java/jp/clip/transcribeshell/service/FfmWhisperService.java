package jp.clip.transcribeshell.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jp.clip.transcribeshell.config.FfmProperties;
import jp.clip.whisper.SamplingStrategy;
import jp.clip.whisper.Segment;
import jp.clip.whisper.TranscriptionResult;
import jp.clip.whisper.WhisperConfig;
import jp.clip.whisper.WhisperEngine;
import jp.clip.whisper.WhisperException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * whisper.cpp を FFM（{@code jp.clip:whisper-ffm} の {@link WhisperEngine}）で呼び、part ごとに文字起こしする。
 *
 * <p>外部プロセスを起動する {@link WhisperService}（{@code whisper-cli} / {@code whisper-ctranslate2}）と違い、
 * 同じ JVM 内でネイティブライブラリを直接呼ぶ。Python も外部バイナリも要らない。
 *
 * <p>モデルの読み込みに数秒かかるため、1 回の呼び出しで 1 つのエンジンを開き、未処理 part をまとめて処理する。
 * 冪等性: part に対応する {@code .txt} が既にあればスキップする（{@code force=true} のときは既存でも再実行）。
 * 未処理 part が 1 つも無ければモデルの読み込み自体を行わない。
 *
 * <p><b>注意</b>: {@code jp.clip.whisper.WhisperEngine}（このクラスが使うライブラリ側のエンジン）と
 * {@code jp.clip.transcribeshell.config.WhisperEngine}（{@code transcribe} のエンジン種別 enum）は同名の別物。
 * このクラスは前者だけを import している。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FfmWhisperService {

	/** UTF-8 の BOM。Windows のメモ帳が先頭に付けることがある。 */
	private static final String BOM = "\uFEFF";

	/** モデル名を差し込む場所。{@code transcribe.ffm.model-file-pattern} で使う。 */
	private static final String MODEL_PLACEHOLDER = "{model}";

	private final FfmProperties properties;

	/**
	 * outputDir 内の {@code part_*.wav} を名前昇順に文字起こしし、{@code part_*.txt}（UTF-8、1 行 1 セグメント）を書き出す。
	 *
	 * @param outputDir part_*.wav が置かれた作業ディレクトリ
	 * @param model     ggml モデルファイル（{@link #resolveModel(String)} で解決済みのもの）
	 * @param options   コマンドのオプション一式
	 * @return 今回新規に文字起こしした part 数（既存 .txt でスキップしたものは数えない）
	 * @throws IllegalStateException part が無い、またはモデルの読み込み・文字起こしに失敗した場合
	 */
	public int transcribeAll(Path outputDir, Path model, FfmOptions options) {
		List<Path> parts = listParts(outputDir);
		if (parts.isEmpty()) {
			throw new IllegalStateException("分割された part_*.wav が見つかりません: " + outputDir);
		}

		List<Path> pending = parts.stream()
				.filter(part -> options.force() || !Files.exists(txtFor(part)))
				.toList();
		log.info("[4/4] 文字起こし (whisper.cpp / FFM): {}件のpartのうち{}件を処理します", parts.size(), pending.size());
		if (pending.isEmpty()) {
			return 0;
		}

		WhisperConfig config = buildConfig(model, options);
		log.info("      設定: strategy={} vad={} threads={} suppressNst={}",
				config.samplingStrategy(), config.vadEnabled(), config.threads(), config.suppressNonSpeechTokens());
		log.info("      デコーダ: bestOf={} beamSize={} temperatureInc={} entropyThold={} maxTextCtx={} carryPrompt={}",
				config.bestOf(), config.beamSize(), config.temperatureIncrement(), config.entropyThreshold(),
				config.maxTextContext(), config.carryInitialPrompt());
		if (config.initialPrompt() != null) {
			log.info("      初期プロンプト: {}", config.initialPrompt());
		}

		try (WhisperEngine engine = WhisperEngine.open(config)) {
			log.info("      モデル読み込み完了: {} / {}", model.getFileName(), engine.systemInfo());
			for (Path part : pending) {
				log.info("      {} ... 実行中", part.getFileName());
				TranscriptionResult result = engine.transcribe(part);
				writeTxt(txtFor(part), result);
				log.info("      {} ... done ({} 秒, RTF {})", part.getFileName(), result.elapsedMs() / 1000,
						String.format(Locale.ROOT, "%.2f", result.realTimeFactor()));
			}
		} catch (WhisperException e) {
			// ネイティブが読み込めなかった場合は「音声 1 件の失敗」ではなく環境の問題なので区別する。
			// whisper-ffm 2.0.2 以降は UnsatisfiedLinkError を WhisperException に包んでくれる
			if (e.getCause() instanceof LinkageError) {
				throw new NativeUnavailableException(e.getCause());
			}
			throw new IllegalStateException("whisper.cpp の処理に失敗しました: " + e.getMessage(), e);
		} catch (LinkageError e) {
			// 包まれずに出てくる経路も残る（FFM の downcall がシンボルを引けない場合など）。
			// LinkageError は Error なので上位の catch(Exception) では捕まらない。ここで例外に変換する
			throw new NativeUnavailableException(e);
		}
		return pending.size();
	}

	/**
	 * コマンドのオプションと {@code transcribe.ffm.*} の設定から {@link WhisperConfig} を組み立てる。
	 * オプションで明示された値が優先され、未指定なら設定値を使う。
	 */
	WhisperConfig buildConfig(Path model, FfmOptions options) {
		return WhisperConfig.builder()
				.model(model)
				.language(toWhisperLanguage(options.language()))
				.threads(effectiveThreads(options))
				.samplingStrategy(effectiveSamplingStrategy(options))
				.vadEnabled(effectiveVad(options))
				.vadThreshold(properties.getVadThreshold())
				.vadMinSpeechDurationMs(properties.getVadMinSpeechDurationMs())
				.vadMinSilenceDurationMs(properties.getVadMinSilenceDurationMs())
				.vadMaxSpeechDurationSeconds(properties.getVadMaxSpeechDurationSeconds())
				.vadSpeechPadMs(properties.getVadSpeechPadMs())
				.vadSamplesOverlap(properties.getVadSamplesOverlap())
				.suppressNonSpeechTokens(properties.isSuppressNonSpeechTokens())
				.bestOf(properties.getBestOf())
				.beamSize(properties.getBeamSize())
				.temperatureIncrement(properties.getTemperatureIncrement())
				.entropyThreshold(properties.getEntropyThreshold())
				.maxTextContext(properties.getMaxTextContext())
				.carryInitialPrompt(properties.isCarryInitialPrompt())
				.initialPrompt(effectiveInitialPrompt(options))
				.nativeLibraryDirectory(nativeLibraryDirectory())
				.build();
	}

	/**
	 * {@code -m} の値をモデルファイルのパスに解決する。
	 *
	 * <p>既存ファイルのパスならそのまま使い、そうでなければモデル名とみなして
	 * {@code <transcribe.ffm.model-dir>/<model-file-pattern>} を探す。
	 *
	 * @param model モデル名（例 {@code small}、{@code large-v3-turbo-q5_0}）またはファイルパス
	 * @return 存在するモデルファイルのパス
	 * @throws IllegalArgumentException モデルファイルが見つからない場合
	 */
	public Path resolveModel(String model) {
		Path direct = Path.of(model);
		if (Files.isRegularFile(direct)) {
			return direct;
		}
		Path inModelDir = Path.of(properties.getModelDir())
				.resolve(properties.getModelFilePattern().replace(MODEL_PLACEHOLDER, model));
		if (Files.isRegularFile(inModelDir)) {
			return inModelDir;
		}
		throw new IllegalArgumentException("モデルが見つかりません: " + model + "（" + inModelDir + " も無し）"
				+ "\n  whisper-ffm の scripts\\download-model.ps1 " + model + " で取得し、"
				+ "設定 transcribe.ffm.model-dir のフォルダへ置いてください。");
	}

	/**
	 * whisper.cpp が受け付ける言語表記に揃える。whisper.cpp は {@code ja} も {@code japanese} も受け付けるが、
	 * 言語名は小文字でしか照合しないため、既存コマンドの {@code Japanese} 表記をそのまま渡せるように小文字化する。
	 */
	static String toWhisperLanguage(String language) {
		return StringUtils.hasText(language) ? language.trim().toLowerCase(Locale.ROOT) : "auto";
	}

	/** スレッド数。{@code -t} が 0（未指定）なら設定 {@code transcribe.ffm.threads}。 */
	int effectiveThreads(FfmOptions options) {
		return options.threads() > 0 ? options.threads() : properties.getThreads();
	}

	/** 探索方法。{@code --beam-search} が未指定なら設定 {@code transcribe.ffm.beam-search}。 */
	SamplingStrategy effectiveSamplingStrategy(FfmOptions options) {
		boolean beamSearch = options.beamSearch() != null ? options.beamSearch() : properties.isBeamSearch();
		return beamSearch ? SamplingStrategy.BEAM_SEARCH : SamplingStrategy.GREEDY;
	}

	/** VAD。{@code --vad} が未指定なら設定 {@code transcribe.ffm.vad}。 */
	boolean effectiveVad(FfmOptions options) {
		return options.vad() != null ? options.vad() : properties.isVad();
	}

	/**
	 * 初期プロンプト。優先順は {@code --prompt} → 設定 {@code transcribe.ffm.initial-prompt}
	 * → 設定 {@code transcribe.ffm.initial-prompt-file} の内容。すべて空なら null（プロンプトを渡さない）。
	 */
	String effectiveInitialPrompt(FfmOptions options) {
		if (StringUtils.hasText(options.prompt())) {
			return options.prompt().strip();
		}
		if (StringUtils.hasText(properties.getInitialPrompt())) {
			return properties.getInitialPrompt().strip();
		}
		if (StringUtils.hasText(properties.getInitialPromptFile())) {
			return readPromptFile(Path.of(properties.getInitialPromptFile()));
		}
		return null;
	}

	/**
	 * プロンプトファイル（UTF-8）を読む。{@code #} で始まる行と空行は飛ばし、残りを連結して 1 行にする
	 * （日本語なので行の間に空白は入れない）。ファイルが無いときは警告して null を返す。
	 */
	static String readPromptFile(Path file) {
		if (!Files.isRegularFile(file)) {
			log.warn("      初期プロンプトのファイルが無いため、プロンプト無しで続けます: {}", file);
			return null;
		}
		try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
			String prompt = lines
					.map(line -> line.replace(BOM, ""))  // メモ帳の「UTF-8 (BOM 付き)」でも # 行として扱えるように
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.collect(Collectors.joining());
			return StringUtils.hasText(prompt) ? prompt : null;
		} catch (IOException e) {
			throw new UncheckedIOException("初期プロンプトのファイルを読めません: " + file, e);
		}
	}

	private Path nativeLibraryDirectory() {
		return StringUtils.hasText(properties.getNativeLibraryDir()) ? Path.of(properties.getNativeLibraryDir()) : null;
	}

	/** 1 行 1 セグメントで書き出す（whisper-cli / faster-whisper の txt 出力と同じ読みやすさにする）。 */
	private void writeTxt(Path txt, TranscriptionResult result) {
		String content = result.segments().stream()
				.map(Segment::text)
				.map(String::strip)
				.filter(StringUtils::hasText)
				.collect(Collectors.joining("\n", "", "\n"));
		try {
			Files.writeString(txt, content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("文字起こし結果の書き出しに失敗しました: " + txt, e);
		}
	}

	/** outputDir 内の part_*.wav を名前昇順で列挙する。 */
	List<Path> listParts(Path outputDir) {
		try (Stream<Path> stream = Files.list(outputDir)) {
			return stream
					.filter(p -> {
						String name = p.getFileName().toString();
						return name.startsWith("part_") && name.endsWith(".wav");
					})
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("part の列挙に失敗しました: " + outputDir, e);
		}
	}

	/** part_000.wav → part_000.txt。 */
	private Path txtFor(Path part) {
		String name = part.getFileName().toString();
		String base = name.substring(0, name.length() - ".wav".length());
		return part.resolveSibling(base + ".txt");
	}
}
