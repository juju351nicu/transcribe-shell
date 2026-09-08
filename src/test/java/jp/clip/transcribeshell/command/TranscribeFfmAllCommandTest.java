package jp.clip.transcribeshell.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.shell.test.ShellScreen;
import org.springframework.shell.test.ShellTestClient;
import org.springframework.shell.test.autoconfigure.ShellTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jp.clip.transcribeshell.service.FfmOptions;
import jp.clip.transcribeshell.service.FfmTranscribeService;
import jp.clip.transcribeshell.service.NativeUnavailableException;
import jp.clip.transcribeshell.service.TranscribeResult;

/**
 * {@link TranscribeFfmAllCommand} の対象抽出・処理順・失敗継続・オプションの受け渡しを検証する。
 *
 * <p>{@link FfmTranscribeService} は {@link MockitoBean} で差し替え、ffmpeg / whisper.cpp は動かさない。
 */
@SpringBootTest(properties = "spring.shell.interactive.enabled=false")
@ShellTest
class TranscribeFfmAllCommandTest {

	@Autowired
	private ShellTestClient client;

	@MockitoBean
	private FfmTranscribeService ffmTranscribeService;

	@Test
	void 直下のmp3のみを名前昇順で処理し_出力フォルダやpart_は除外する(@TempDir Path dir) throws Exception {
		Files.createFile(dir.resolve("b.MP3"));
		Files.createFile(dir.resolve("a.mp3"));
		Files.createFile(dir.resolve("part_000.mp3"));
		Files.createFile(dir.resolve("memo.txt"));
		Files.createDirectory(dir.resolve("transcribe-ffm_a"));
		Files.createFile(dir.resolve("transcribe-ffm_a").resolve("part_000.wav"));

		given(ffmTranscribeService.run(any(), any())).willReturn(new TranscribeResult(Path.of("x"), true, 1));

		ShellScreen screen = client.sendCommand("transcribe-ffm-all -d " + dir);

		// 出力フォルダは null（各ファイルの同階層に transcribe-ffm_<base>）、その他は既定値
		FfmOptions expected = FfmOptions.builder()
				.model("small")
				.language("Japanese")
				.segmentTime(600)
				.force(false)
				.threads(0)
				.build();
		InOrder inOrder = Mockito.inOrder(ffmTranscribeService);
		inOrder.verify(ffmTranscribeService).run(dir.resolve("a.mp3").toString(), expected);
		inOrder.verify(ffmTranscribeService).run(dir.resolve("b.MP3").toString(), expected);
		verifyNoMoreInteractions(ffmTranscribeService);
		assertThat(String.join("\n", screen.lines())).contains("処理: 2件");
	}

	@Test
	void オプションは全ファイルに同じ内容で渡る(@TempDir Path dir) throws Exception {
		Files.createFile(dir.resolve("a.mp3"));
		given(ffmTranscribeService.run(any(), any())).willReturn(new TranscribeResult(Path.of("x"), true, 1));

		client.sendCommand("transcribe-ffm-all -d " + dir
				+ " -m large-v3-turbo-q5_0 -l ja -t 8 --vad true --beam-search true --force -p 田中さん");

		FfmOptions expected = FfmOptions.builder()
				.model("large-v3-turbo-q5_0")
				.language("ja")
				.segmentTime(600)
				.force(true)
				.threads(8)
				.vad(true)
				.beamSearch(true)
				.prompt("田中さん")
				.build();
		verify(ffmTranscribeService).run(dir.resolve("a.mp3").toString(), expected);
	}

	@Test
	void 一件失敗してもバッチを継続し失敗件数に数える(@TempDir Path dir) throws Exception {
		Files.createFile(dir.resolve("ng.mp3"));
		Files.createFile(dir.resolve("ok.mp3"));

		given(ffmTranscribeService.run(eq(dir.resolve("ng.mp3").toString()), any()))
				.willThrow(new IllegalStateException("whisper.cpp の処理に失敗しました"));
		given(ffmTranscribeService.run(eq(dir.resolve("ok.mp3").toString()), any()))
				.willReturn(new TranscribeResult(Path.of("y"), false, 0));

		ShellScreen screen = client.sendCommand("transcribe-ffm-all -d " + dir);

		verify(ffmTranscribeService).run(eq(dir.resolve("ok.mp3").toString()), any());
		assertThat(String.join("\n", screen.lines())).contains("処理: 0件 / スキップ: 1件 / 失敗: 1件");
	}

	/**
	 * ネイティブが読み込めない場合は 1 件目で打ち切ること。
	 *
	 * <p>以前は {@code catch(Exception)} だけだったため、{@code UnsatisfiedLinkError}（{@code Error}）が
	 * 突き抜けてバッチが異常終了していた。{@link NativeUnavailableException} に変換したうえで、
	 * 残りを試さずに中断する。
	 */
	@Test
	void ネイティブが読み込めない場合は残りを試さず中断する(@TempDir Path dir) throws Exception {
		Files.createFile(dir.resolve("a.mp3"));
		Files.createFile(dir.resolve("b.mp3"));

		given(ffmTranscribeService.run(eq(dir.resolve("a.mp3").toString()), any()))
				.willThrow(new NativeUnavailableException(new UnsatisfiedLinkError("whisper.dll がありません")));

		ShellScreen screen = client.sendCommand("transcribe-ffm-all -d " + dir);

		// 2 件目は呼ばれない
		verify(ffmTranscribeService).run(eq(dir.resolve("a.mp3").toString()), any());
		verifyNoMoreInteractions(ffmTranscribeService);
		assertThat(String.join("\n", screen.lines())).contains("中断");
	}

	/** 旧名 {@code transcribe-cpp-all} も alias で受け付けること。 */
	@Test
	void 旧名のtranscribe_cpp_allでも同じコマンドが動く(@TempDir Path dir) throws Exception {
		Files.createFile(dir.resolve("a.mp3"));
		given(ffmTranscribeService.run(any(), any())).willReturn(new TranscribeResult(Path.of("x"), true, 1));

		ShellScreen screen = client.sendCommand("transcribe-cpp-all -d " + dir);

		verify(ffmTranscribeService).run(eq(dir.resolve("a.mp3").toString()), any());
		assertThat(String.join("\n", screen.lines())).contains("処理: 1件");
	}

	@Test
	void 存在しないフォルダはエラーを返す() throws Exception {
		ShellScreen screen = client.sendCommand("transcribe-ffm-all -d C:\\no\\such\\dir_xyz");

		assertThat(String.join("\n", screen.lines())).contains("フォルダが見つかりません");
	}
}
