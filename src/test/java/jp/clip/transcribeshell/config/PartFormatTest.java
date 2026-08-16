package jp.clip.transcribeshell.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PartFormat} の単体テスト。
 *
 * <p>「設定どおりに決まること」と「既に別形式で分割済みならそちらを優先すること（混在防止）」を検証する。
 */
class PartFormatTest {

	private final TranscribeProperties properties = new TranscribeProperties();

	@Test
	void 既定はMP3で従来どおりのコマンド部品を返す(@TempDir Path dir) {
		assertThat(PartFormat.resolve(properties, dir)).isEqualTo(PartFormat.MP3);
		assertThat(PartFormat.MP3.getExtension()).isEqualTo(".mp3");
		assertThat(PartFormat.MP3.getFfmpegArgs()).containsExactly("-c", "copy");
		assertThat(PartFormat.MP3.firstPartFileName()).isEqualTo("part_000.mp3");
		assertThat(PartFormat.MP3.segmentPattern()).isEqualTo("part_%03d.mp3");
	}

	@Test
	void cppでinput_formatがwavならWAVになる(@TempDir Path dir) {
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setInputFormat("wav");

		assertThat(PartFormat.resolve(properties, dir)).isEqualTo(PartFormat.WAV);
		assertThat(PartFormat.WAV.getExtension()).isEqualTo(".wav");
		assertThat(PartFormat.WAV.getFfmpegArgs()).containsExactly("-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le");
		assertThat(PartFormat.WAV.firstPartFileName()).isEqualTo("part_000.wav");
		assertThat(PartFormat.WAV.segmentPattern()).isEqualTo("part_%03d.wav");
	}

	@Test
	void cpp以外のエンジンではinput_formatを無視してMP3のままにする(@TempDir Path dir) {
		// input-format は cpp 固有の設定。faster/openai の挙動は変えない。
		properties.getWhisper().getCpp().setInputFormat("wav");

		assertThat(PartFormat.resolve(properties, dir)).isEqualTo(PartFormat.MP3);
	}

	@Test
	void wav設定でも既存のpart_000_mp3があればMP3を優先する(@TempDir Path dir) throws IOException {
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setInputFormat("wav");
		Files.createFile(dir.resolve("part_000.mp3"));

		assertThat(PartFormat.resolve(properties, dir)).isEqualTo(PartFormat.MP3);
	}

	@Test
	void mp3設定でも既存のpart_000_wavがあればWAVを優先する(@TempDir Path dir) throws IOException {
		properties.getWhisper().setEngine("cpp");
		Files.createFile(dir.resolve("part_000.wav"));

		assertThat(PartFormat.resolve(properties, dir)).isEqualTo(PartFormat.WAV);
	}

	@Test
	void 未知のinput_formatは既定のMP3として扱う(@TempDir Path dir) {
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setInputFormat("flac");

		assertThat(PartFormat.resolve(properties, dir)).isEqualTo(PartFormat.MP3);
	}
}
