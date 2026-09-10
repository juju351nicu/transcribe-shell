package jp.clip.transcribeshell.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jp.clip.transcribeshell.config.PartFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code transcribe-ffm}（whisper.cpp を FFM で JVM 内から呼ぶ）の一連の流れをまとめる。
 *
 * <p>手順は {@link TranscribeService} と同じで各ステップが冪等だが、2 点だけ違う。
 * <ul>
 * <li>分割は必ず {@link PartFormat#WAV}（16kHz モノラル）。whisper-ffm は WAV しか読めないため、
 * 設定 {@code transcribe.whisper.cpp.input-format} に関係なく WAV を指定する</li>
 * <li>文字起こしは外部プロセスではなく JVM 内の {@link FfmWhisperService}</li>
 * </ul>
 * 分割は {@link FfmpegService}、結合は {@link TranscriptMergeService} をそのまま再利用する。
 *
 * <p>出力フォルダの既定は {@code transcribe-ffm_<base>}。{@code transcribe} の {@code transcribe_<base>} と
 * 分けているのは、同じ録音を両エンジンで処理して比べられるようにするためと、
 * {@code part_*.mp3} と {@code part_*.wav} が混ざらないようにするため。
 *
 * <p>結合ファイル名は {@link DatedBaseName} で日付部分を展開する（{@code 260910_1539} →
 * {@code 2026-09-10_1539_all.txt}）。{@code transcribe} 側は従来どおり {@code <base>_all.txt} で、
 * 共有している {@link TranscriptMergeService} には手を入れていない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FfmTranscribeService {

	/** 既定の出力フォルダ名の接頭辞。{@code transcribe} 系の {@code transcribe_} と区別する。 */
	static final String OUTPUT_DIR_PREFIX = "transcribe-ffm_";

	private final FfmpegService ffmpegService;
	private final FfmWhisperService ffmWhisperService;
	private final TranscriptMergeService mergeService;

	/**
	 * 文字起こしを実行し、結合ファイルと新規作業量を返す。
	 *
	 * @param file    入力 MP3 のパス
	 * @param options コマンドのオプション一式
	 * @return 結合ファイルのパスと、今回の分割実行有無・新規文字起こし part 数
	 * @throws IllegalArgumentException 入力ファイルやモデルが見つからない場合
	 * @throws IllegalStateException    分割・文字起こしの実行に失敗した場合
	 */
	public TranscribeResult run(String file, FfmOptions options) {
		Path src = Path.of(file);
		if (!Files.isRegularFile(src)) {
			throw new IllegalArgumentException("入力ファイルが見つかりません: " + src);
		}
		log.info("[1/4] 入力確認 OK: {}", src.getFileName());

		// モデルは分割より先に解決する。無いモデルのために ffmpeg を走らせない
		Path model = ffmWhisperService.resolveModel(options.model());

		String base = TranscribeService.baseName(src);
		Path outDir = resolveOutputDir(src, options.outputDir(), base);
		createDirectories(outDir);
		log.info("[2/4] 出力フォルダ: {}", outDir);

		boolean splitExecuted = ffmpegService.split(src, outDir, options.segmentTime(), PartFormat.WAV);

		int transcribedParts = ffmWhisperService.transcribeAll(outDir, model, options);

		// 結合ファイル名だけ日付を yyyy-MM-dd に展開する（260910_1539 → 2026-09-10_1539_all.txt）。
		// フォルダ名と part_*.txt は元のまま。変換は DatedBaseName の Javadoc を参照
		Path merged = mergeService.merge(outDir, DatedBaseName.of(base));
		log.info("結合完了: {}", merged);
		return new TranscribeResult(merged, splitExecuted, transcribedParts);
	}

	/**
	 * 出力フォルダを決める。指定があればそのまま、無ければ入力と同階層の {@code transcribe-ffm_<base>}。
	 */
	Path resolveOutputDir(Path src, String outputDir, String base) {
		if (StringUtils.hasText(outputDir)) {
			return Path.of(outputDir);
		}
		return src.toAbsolutePath().getParent().resolve(OUTPUT_DIR_PREFIX + base);
	}

	private void createDirectories(Path dir) {
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new UncheckedIOException("出力フォルダの作成に失敗しました: " + dir, e);
		}
	}
}
