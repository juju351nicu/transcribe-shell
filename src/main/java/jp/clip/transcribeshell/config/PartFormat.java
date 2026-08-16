package jp.clip.transcribeshell.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 分割 part の音声フォーマット。拡張子・ffmpeg の引数・ファイル名パターンを 1 箇所にまとめる。
 *
 * <p>エンジンごとに part の拡張子が変わりうるため、「分割済み判定（{@code part_000.<ext>}）」と
 * 「part の列挙フィルタ」が別々に拡張子を持つと必ずズレる。ここに集約して両者が同じ判定を通るようにしている。
 *
 * <p>既定は {@link #MP3}。whisper.cpp（whisper-cli 1.9.2）は mp3 を直接読めることを実機確認済みのため、
 * cpp エンジンでも既定は mp3 のまま（既存の {@code transcribe_<base>} フォルダとそのまま互換）。
 * mp3 を読めない古い whisper.cpp に当たった場合の逃げ道として
 * {@code transcribe.whisper.cpp.input-format=wav} で {@link #WAV} に切り替えられる。
 */
@Slf4j
@Getter
public enum PartFormat {

	/** 従来どおりの MP3。再エンコードせず {@code -c copy} で切り出すため高速。 */
	MP3(".mp3", List.of("-c", "copy")),

	/** whisper.cpp がそのまま扱える 16kHz・モノラル・16bit PCM の WAV。分割時に再エンコードが走る。 */
	WAV(".wav", List.of("-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le"));

	/** part ファイルの拡張子（ドット込み）。 */
	private final String extension;

	/** ffmpeg のコーデック指定引数。 */
	private final List<String> ffmpegArgs;

	PartFormat(String extension, List<String> ffmpegArgs) {
		this.extension = extension;
		this.ffmpegArgs = ffmpegArgs;
	}

	/** 分割済み判定に使う最初の part のファイル名（例: {@code part_000.mp3}）。 */
	public String firstPartFileName() {
		return "part_000" + extension;
	}

	/** ffmpeg の segment 出力パターン（例: {@code part_%03d.mp3}）。 */
	public String segmentPattern() {
		return "part_%03d" + extension;
	}

	/**
	 * 作業ディレクトリで実際に使う part フォーマットを決める。
	 *
	 * <p>設定から決まる形式を基本としつつ、<b>既に別形式で分割済みならそちらを優先</b>する。
	 * 既存の {@code transcribe_<base>}（{@code part_*.mp3} がある）でエンジンを cpp + wav に切り替えても、
	 * 無駄な再分割や mp3 と wav の混在が起きないようにするため。この判定を分割側と列挙側の両方が通ることで、
	 * 「wav で分割したのに mp3 を探す」といった食い違いも防げる。
	 *
	 * @param properties アプリ設定
	 * @param outputDir  作業ディレクトリ。null なら既存ファイルによる上書き判定は行わない
	 * @return 実際に使う part フォーマット
	 */
	public static PartFormat resolve(TranscribeProperties properties, Path outputDir) {
		PartFormat configured = fromProperties(properties);
		if (outputDir == null || Files.exists(outputDir.resolve(configured.firstPartFileName()))) {
			return configured;
		}
		for (PartFormat existing : values()) {
			if (existing != configured && Files.exists(outputDir.resolve(existing.firstPartFileName()))) {
				log.warn("設定は {} ですが、既存の {} があるためそちらを使います（{} で作り直す場合は出力フォルダを削除してください）",
						configured.settingName(), existing.firstPartFileName(), configured.settingName());
				return existing;
			}
		}
		return configured;
	}

	/**
	 * 設定だけから part フォーマットを決める（既存ファイルは見ない）。
	 *
	 * <p>cpp 以外のエンジンは従来どおり常に MP3。cpp のときだけ
	 * {@code transcribe.whisper.cpp.input-format} を見る。未知の値は既定の MP3 とみなす。
	 */
	private static PartFormat fromProperties(TranscribeProperties properties) {
		if (WhisperEngine.from(properties.getWhisper().getEngine()) != WhisperEngine.CPP) {
			return MP3;
		}
		String inputFormat = properties.getWhisper().getCpp().getInputFormat();
		return WAV.settingName().equalsIgnoreCase(inputFormat == null ? "" : inputFormat.trim()) ? WAV : MP3;
	}

	/** 設定ファイルやログで使う小文字表記（{@code mp3} / {@code wav}）。 */
	private String settingName() {
		return extension.substring(1);
	}
}
