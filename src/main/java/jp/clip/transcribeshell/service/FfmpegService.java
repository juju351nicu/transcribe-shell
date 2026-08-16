package jp.clip.transcribeshell.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import jp.clip.transcribeshell.config.PartFormat;
import jp.clip.transcribeshell.config.TranscribeProperties;
import jp.clip.transcribeshell.process.ProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ffmpeg で MP3 を一定時間ごとに分割する。
 *
 * <p>冪等性: 出力フォルダに {@code part_000.<ext>} が既に存在すれば分割をスキップする
 * （現行 PowerShell 運用と同じ挙動）。
 *
 * <p>拡張子と再エンコード有無は {@link PartFormat} が決める。既定（cpp 以外、および cpp でも
 * {@code input-format=mp3} のとき）は従来どおり {@code -c copy} で {@code part_%03d.mp3} を作るため、
 * 既存の作業フォルダとの互換性は保たれる。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FfmpegService {

	private final ProcessRunner processRunner;
	private final TranscribeProperties properties;

	/**
	 * src を outputDir 内に segmentTime 秒ごとに分割する。既に分割済みならスキップ。
	 *
	 * @param src         入力 MP3
	 * @param outputDir   出力フォルダ（作業ディレクトリ）
	 * @param segmentTime 分割秒数
	 * @return 実際に分割を実行したら true、スキップしたら false
	 */
	public boolean split(Path src, Path outputDir, int segmentTime) {
		PartFormat format = PartFormat.resolve(properties, outputDir);
		String firstPart = format.firstPartFileName();
		if (Files.exists(outputDir.resolve(firstPart))) {
			log.info("[3/4] 分割: 既存のためスキップ ({} あり)", firstPart);
			return false;
		}

		List<String> command = new ArrayList<>();
		command.add(properties.getFfmpegPath());
		command.add("-i");
		command.add(src.toString());
		command.add("-f");
		command.add("segment");
		command.add("-segment_time");
		command.add(String.valueOf(segmentTime));
		command.addAll(format.getFfmpegArgs());
		command.add(format.segmentPattern());

		log.info("[3/4] 分割: ffmpeg 実行中... ({})", format.segmentPattern());
		int exit = processRunner.run(command, outputDir);
		if (exit != 0) {
			throw new IllegalStateException("ffmpeg が異常終了しました (exit=" + exit + ")");
		}
		return true;
	}
}
