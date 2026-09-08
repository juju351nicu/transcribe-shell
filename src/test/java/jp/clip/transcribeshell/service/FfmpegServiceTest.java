package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.clip.transcribeshell.config.PartFormat;
import jp.clip.transcribeshell.config.TranscribeProperties;
import jp.clip.transcribeshell.process.ProcessRunner;

/**
 * {@link FfmpegService} の単体テスト。{@link ProcessRunner} は Mockito でモックする。
 *
 * <p>冪等性（part_000.mp3 があればスキップ）と、無い場合の ffmpeg コマンド配列を検証する。
 */
@ExtendWith(MockitoExtension.class)
class FfmpegServiceTest {

	@Mock
	private ProcessRunner processRunner;

	// 実行ファイルパス・既定値は本物（デフォルト "ffmpeg" 等）を使う。
	@Spy
	private TranscribeProperties properties = new TranscribeProperties();

	@InjectMocks
	private FfmpegService service;

	@Test
	void part_000が既にあれば分割せずスキップする(@TempDir Path dir) throws IOException {
		Files.createFile(dir.resolve("part_000.mp3"));

		boolean executed = service.split(dir.resolve("src.mp3"), dir, 600);

		assertThat(executed).isFalse();
		verifyNoInteractions(processRunner);
	}

	@Test
	void 未分割なら正しいffmpegコマンドで実行する(@TempDir Path dir) {
		Path src = dir.resolve("sample_001.MP3");
		when(processRunner.run(anyList(), eq(dir))).thenReturn(0);

		boolean executed = service.split(src, dir, 600);

		assertThat(executed).isTrue();
		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner).run(cmd.capture(), eq(dir));
		assertThat(cmd.getValue()).containsExactly(
				"ffmpeg", "-i", src.toString(), "-f", "segment",
				"-segment_time", "600", "-c", "copy", "part_%03d.mp3");
	}

	@Test
	void cppでもinput_formatが既定のmp3なら従来どおりのコマンドで分割する(@TempDir Path dir) {
		// エンジンを変えただけでは分割の挙動を変えない（whisper-cli は mp3 を直接読めるため）。
		properties.getWhisper().setEngine("cpp");
		Path src = dir.resolve("sample_001.MP3");
		when(processRunner.run(anyList(), eq(dir))).thenReturn(0);

		service.split(src, dir, 600);

		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner).run(cmd.capture(), eq(dir));
		assertThat(cmd.getValue()).containsExactly(
				"ffmpeg", "-i", src.toString(), "-f", "segment",
				"-segment_time", "600", "-c", "copy", "part_%03d.mp3");
	}

	@Test
	void cppでinput_formatがwavなら16kHzモノラルのWAVで分割する(@TempDir Path dir) {
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setInputFormat("wav");
		Path src = dir.resolve("sample_001.MP3");
		when(processRunner.run(anyList(), eq(dir))).thenReturn(0);

		boolean executed = service.split(src, dir, 600);

		assertThat(executed).isTrue();
		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner).run(cmd.capture(), eq(dir));
		assertThat(cmd.getValue()).containsExactly(
				"ffmpeg", "-i", src.toString(), "-f", "segment", "-segment_time", "600",
				"-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "part_%03d.wav");
	}

	@Test
	void 形式を明示すると設定に関係なくその形式で分割する(@TempDir Path dir) {
		// transcribe-cpp（FFM）は WAV しか読めないため、設定が mp3 のままでも WAV を指定して呼ぶ。
		Path src = dir.resolve("sample_001.MP3");
		when(processRunner.run(anyList(), eq(dir))).thenReturn(0);

		boolean executed = service.split(src, dir, 600, PartFormat.WAV);

		assertThat(executed).isTrue();
		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner).run(cmd.capture(), eq(dir));
		assertThat(cmd.getValue()).containsExactly(
				"ffmpeg", "-i", src.toString(), "-f", "segment", "-segment_time", "600",
				"-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "part_%03d.wav");
	}

	@Test
	void 形式を明示した場合もその形式のpart_000があればスキップする(@TempDir Path dir) throws IOException {
		Files.createFile(dir.resolve("part_000.wav"));

		boolean executed = service.split(dir.resolve("src.mp3"), dir, 600, PartFormat.WAV);

		assertThat(executed).isFalse();
		verifyNoInteractions(processRunner);
	}

	@Test
	void wav設定でも既存のpart_000_mp3があれば混在を避けて分割しない(@TempDir Path dir) throws IOException {
		// 既に mp3 で分割済みの作業フォルダでエンジンを cpp+wav に切り替えても、再分割せず既存を使う。
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setInputFormat("wav");
		Files.createFile(dir.resolve("part_000.mp3"));

		boolean executed = service.split(dir.resolve("src.mp3"), dir, 600);

		assertThat(executed).isFalse();
		verifyNoInteractions(processRunner);
	}
}
