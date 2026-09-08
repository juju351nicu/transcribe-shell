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

import jp.clip.transcribeshell.service.FfmOptions;
import jp.clip.transcribeshell.service.FfmTranscribeService;
import jp.clip.transcribeshell.service.TranscribeResult;

/**
 * {@link TranscribeFfmCommand} のオプション解析・デフォルト値を検証する。
 *
 * <p>{@link FfmTranscribeService} は {@link MockitoBean} で差し替え、ffmpeg / whisper.cpp を動かさずに
 * Spring Shell が組み立てた {@link FfmOptions} だけを検証する。
 */
@SpringBootTest(properties = "spring.shell.interactive.enabled=false")
@ShellTest
class TranscribeFfmCommandTest {

	@Autowired
	private ShellTestClient client;

	@MockitoBean
	private FfmTranscribeService ffmTranscribeService;

	/** 何も指定しなかったときに渡るはずのオプション。vad / beamSearch / prompt は null（= 設定に従う）。 */
	private static FfmOptions defaults() {
		return FfmOptions.builder()
				.model("small")
				.language("Japanese")
				.segmentTime(600)
				.force(false)
				.threads(0)
				.build();
	}

	@Test
	void 必須のfileだけ指定するとその他は既定値が使われる() throws Exception {
		FfmOptions expected = defaults();
		given(ffmTranscribeService.run(eq("C:\\a.mp3"), eq(expected)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe-ffm -f C:\\a.mp3");

		verify(ffmTranscribeService).run("C:\\a.mp3", expected);
	}

	/**
	 * 旧名 {@code transcribe-cpp} も {@code @Command(alias = ...)} で受け付けること。
	 * 1 週間の試用で手元のメモやバッチに旧名が残っているので、黙って使えなくなると困る。
	 */
	@Test
	void 旧名のtranscribe_cppでも同じコマンドが動く() throws Exception {
		FfmOptions expected = defaults();
		given(ffmTranscribeService.run(eq("C:\\alias.mp3"), eq(expected)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe-cpp -f C:\\alias.mp3");

		verify(ffmTranscribeService).run("C:\\alias.mp3", expected);
	}

	@Test
	void 各オプションを指定すると解析されて渡る() throws Exception {
		FfmOptions expected = FfmOptions.builder()
				.model("large-v3-turbo-q5_0")
				.language("ja")
				.segmentTime(300)
				.outputDir("C:\\out")
				.force(true)
				.threads(8)
				.vad(true)
				.beamSearch(true)
				.prompt("田中さん")
				.build();
		given(ffmTranscribeService.run(eq("C:\\b.mp3"), eq(expected)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe-ffm -f C:\\b.mp3 -m large-v3-turbo-q5_0 -l ja --segment-time 300"
				+ " -o C:\\out --force -t 8 --vad true --beam-search true -p 田中さん");

		verify(ffmTranscribeService).run("C:\\b.mp3", expected);
	}

	@Test
	void vadは値付きで明示的にfalseにできる() throws Exception {
		FfmOptions expected = defaults().toBuilder().vad(false).build();
		given(ffmTranscribeService.run(eq("C:\\c.mp3"), eq(expected)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe-ffm C:\\c.mp3 --vad false");

		verify(ffmTranscribeService).run("C:\\c.mp3", expected);
	}

	@Test
	void vadは値なしのフラグ形式でも指定できる() throws Exception {
		// Spring Shell が Boolean オプションを arity 0..1 として扱うことの確認。
		// もしここだけ落ちるなら --vad を「値必須」に変えるか boolean + 別オプションに分ける。
		FfmOptions expected = defaults().toBuilder().vad(true).build();
		given(ffmTranscribeService.run(eq("C:\\flag.mp3"), eq(expected)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe-ffm C:\\flag.mp3 --vad");

		verify(ffmTranscribeService).run("C:\\flag.mp3", expected);
	}

	@Test
	void 位置引数でファイルパスを直接指定できる() throws Exception {
		FfmOptions expected = defaults();
		given(ffmTranscribeService.run(eq("C:\\pos.mp3"), eq(expected)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe-ffm C:\\pos.mp3");

		verify(ffmTranscribeService).run("C:\\pos.mp3", expected);
	}

	@Test
	void 空白や句読点を含むプロンプトは引用符で囲めば1つの値として渡る() throws Exception {
		String prompt = "田中さん、鈴木さんが参加する 社内の定例会議です。";
		FfmOptions expected = defaults().toBuilder().prompt(prompt).build();
		given(ffmTranscribeService.run(eq("C:\\d.mp3"), eq(expected)))
				.willReturn(new TranscribeResult(Path.of("dummy"), true, 1));

		client.sendCommand("transcribe-ffm C:\\d.mp3 --prompt \"" + prompt + "\"");

		verify(ffmTranscribeService).run("C:\\d.mp3", expected);
	}

	@Test
	void 位置引数と_fの両方を指定するとエラーで処理は呼ばれない() throws Exception {
		ShellScreen screen = client.sendCommand("transcribe-ffm C:\\a.mp3 -f C:\\b.mp3");

		assertThat(String.join("\n", screen.lines())).contains("どちらか一方");
		verifyNoInteractions(ffmTranscribeService);
	}

	@Test
	void ファイル未指定はエラーで処理は呼ばれない() throws Exception {
		ShellScreen screen = client.sendCommand("transcribe-ffm");

		assertThat(String.join("\n", screen.lines())).contains("ファイルを指定してください");
		verifyNoInteractions(ffmTranscribeService);
	}
}
