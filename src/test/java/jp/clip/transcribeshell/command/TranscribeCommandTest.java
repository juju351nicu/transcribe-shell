package jp.clip.transcribeshell.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.shell.test.ShellScreen;
import org.springframework.shell.test.ShellTestClient;
import org.springframework.shell.test.autoconfigure.ShellTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jp.clip.transcribeshell.service.TranscribeResult;
import jp.clip.transcribeshell.service.TranscribeService;

/**
 * {@link TranscribeCommand} のオプション解析・デフォルト値を検証する。
 *
 * <p>{@link TranscribeService} は {@link MockitoBean} で差し替え、ffmpeg/whisper を実行させずに
 * Spring Shell が組み立てた引数（既定値の適用結果）だけを検証する。
 */
@SpringBootTest(properties = "spring.shell.interactive.enabled=false")
@ShellTest
class TranscribeCommandTest {

	@Autowired
	private ShellTestClient client;

	@MockitoBean
	private TranscribeService transcribeService;

	@Test
	void 必須のfileだけ指定するとその他は既定値が使われる() throws Exception {
		// --output-dir を defaultValue 無しで省略すると Spring Shell は null を渡す。
		// TranscribeService 側は StringUtils.hasText(null) で既定パス生成に分岐する（null 安全）。
		given(transcribeService.run(eq("C:\\a.mp3"), eq("small"), eq("Japanese"),
				eq(600), eq(null), eq(false)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe -f C:\\a.mp3");

		verify(transcribeService).run("C:\\a.mp3", "small", "Japanese", 600, null, false);
	}

	@Test
	void 各オプションを指定すると解析されて渡る() throws Exception {
		given(transcribeService.run(eq("C:\\b.mp3"), eq("base"), eq("English"),
				eq(300), eq("C:\\out"), eq(true)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe -f C:\\b.mp3 -m base -l English --segment-time 300 -o C:\\out --force");

		verify(transcribeService).run("C:\\b.mp3", "base", "English", 300, "C:\\out", true);
	}

	@Test
	void 位置引数でファイルパスを直接指定できる() throws Exception {
		given(transcribeService.run(eq("C:\\pos.mp3"), eq("small"), eq("Japanese"),
				eq(600), eq(null), eq(false)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe C:\\pos.mp3");

		verify(transcribeService).run("C:\\pos.mp3", "small", "Japanese", 600, null, false);
	}

	@Test
	void 位置引数と他オプションを併用できる() throws Exception {
		given(transcribeService.run(eq("C:\\pos.mp3"), eq("base"), eq("Japanese"),
				eq(300), eq(null), eq(true)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe C:\\pos.mp3 -m base --segment-time 300 --force");

		verify(transcribeService).run("C:\\pos.mp3", "base", "Japanese", 300, null, true);
	}

	@Test
	void 位置引数と_fの両方を指定するとエラーで処理は呼ばれない() throws Exception {
		ShellScreen screen = client.sendCommand("transcribe C:\\a.mp3 -f C:\\b.mp3");

		assertThat(String.join("\n", screen.lines())).contains("どちらか一方");
		verifyNoInteractions(transcribeService);
	}

	@Test
	void ファイル未指定はエラーで処理は呼ばれない() throws Exception {
		ShellScreen screen = client.sendCommand("transcribe");

		assertThat(String.join("\n", screen.lines())).contains("ファイルを指定してください");
		verifyNoInteractions(transcribeService);
	}
}
