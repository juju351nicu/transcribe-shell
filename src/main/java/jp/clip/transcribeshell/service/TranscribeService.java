package jp.clip.transcribeshell.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文字起こしワークフロー全体のオーケストレーション。
 *
 * <p>手順（各ステップが冪等）:
 * <ol>
 *   <li>入力ファイルの存在チェック</li>
 *   <li>出力フォルダ作成（{@code transcribe_<base>} または指定パス）</li>
 *   <li>未分割なら ffmpeg で分割</li>
 *   <li>未処理 part だけ Whisper で文字起こし</li>
 *   <li>part_*.txt を結合</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscribeService {

	private final FfmpegService ffmpegService;
	private final WhisperService whisperService;
	private final TranscriptMergeService mergeService;

	/**
	 * 文字起こしを実行し、結果（結合ファイルと新規作業量）を返す。
	 *
	 * @param file        入力 MP3 の絶対パス
	 * @param model       Whisper モデル
	 * @param language    言語
	 * @param segmentTime 分割秒数
	 * @param outputDir   出力フォルダ。null/空なら入力と同階層に transcribe_&lt;base&gt; を生成
	 * @param force       true なら文字起こし済み part も再実行
	 * @return 結合ファイルのパスと、今回の分割実行有無・新規文字起こし part 数を持つ {@link TranscribeResult}
	 */
	public TranscribeResult run(String file, String model, String language, int segmentTime, String outputDir,
			boolean force) {
		Path src = Path.of(file);

		// 1. 入力ファイル存在チェック
		if (!Files.exists(src) || !Files.isRegularFile(src)) {
			throw new IllegalArgumentException("入力ファイルが見つかりません: " + src);
		}
		log.info("[1/4] 入力確認 OK: {}", src.getFileName());

		String base = baseName(src);

		// 2. 出力フォルダ決定・作成
		Path outDir = resolveOutputDir(src, outputDir, base);
		createDirectories(outDir);
		log.info("[2/4] 出力フォルダ: {}", outDir);

		// 3. 分割（冪等: part_000.mp3 があればスキップ）
		boolean splitExecuted = ffmpegService.split(src, outDir, segmentTime);

		// 4. 文字起こし（冪等: .txt があればスキップ、force で再実行）
		int transcribedParts = whisperService.transcribeAll(outDir, model, language, force);

		// 5. 結合
		Path merged = mergeService.merge(outDir, base);
		log.info("結合完了: {}", merged);
		return new TranscribeResult(merged, splitExecuted, transcribedParts);
	}

	/**
	 * 出力フォルダを決める。
	 * outputDir 指定時はそのパスを出力フォルダそのものとして使い、配下に transcribe_&lt;base&gt; は作らない。
	 * 省略時のみ入力と同階層に transcribe_&lt;base&gt; を生成する。
	 */
	Path resolveOutputDir(Path src, String outputDir, String base) {
		if (StringUtils.hasText(outputDir)) {
			return Path.of(outputDir);
		}
		Path parent = src.toAbsolutePath().getParent();
		return parent.resolve("transcribe_" + base);
	}

	/** 拡張子を除いたファイル名（例: sample_001.MP3 → sample_001）。 */
	static String baseName(Path src) {
		String name = src.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(0, dot);
	}

	private void createDirectories(Path dir) {
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new UncheckedIOException("出力フォルダの作成に失敗しました: " + dir, e);
		}
	}
}
