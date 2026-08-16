package jp.clip.transcribeshell.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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

import jp.clip.transcribeshell.service.TranscribeResult;
import jp.clip.transcribeshell.service.TranscribeService;

/**
 * {@link TranscribeAllCommand} の対象抽出・除外・処理順・失敗継続を検証する。
 *
 * <p>{@link TranscribeService} は {@link MockitoBean} で差し替え、実際の ffmpeg/whisper を動かさずに
 * 「どのファイルが・どの順で run に渡るか」「1件失敗しても継続するか」だけを見る。
 */
@SpringBootTest(properties = "spring.shell.interactive.enabled=false")
@ShellTest
class TranscribeAllCommandTest {

	@Autowired
	private ShellTestClient client;

	@MockitoBean
	private TranscribeService transcribeService;

	@Test
	void 直下のmp3のみを名前昇順で処理し_transcribe_やpart_は除外する(@TempDir Path dir) throws Exception {
		// 対象（大小混在・逆順で作成）
		Files.createFile(dir.resolve("b.MP3"));
		Files.createFile(dir.resolve("a.mp3"));
		// 除外対象
		Files.createFile(dir.resolve("part_000.mp3"));          // 分割済みpartは拾わない
		Files.createFile(dir.resolve("memo.txt"));               // mp3以外
		Files.createDirectory(dir.resolve("transcribe_a"));      // 出力フォルダ（ディレクトリ）
		Files.createFile(dir.resolve("transcribe_a").resolve("part_000.mp3")); // 配下も非再帰なので対象外

		given(transcribeService.run(any(), any(), any(), anyInt(), any(), anyBoolean()))
				.willReturn(new TranscribeResult(Path.of("x"), true, 1));

		ShellScreen screen = client.sendCommand("transcribe-all -d " + dir);

		// a.mp3 → b.MP3 の順で、直下の2件だけが渡る。
		InOrder inOrder = Mockito.inOrder(transcribeService);
		inOrder.verify(transcribeService).run(eq(dir.resolve("a.mp3").toString()), any(), any(), anyInt(), eq(null), anyBoolean());
		inOrder.verify(transcribeService).run(eq(dir.resolve("b.MP3").toString()), any(), any(), anyInt(), eq(null), anyBoolean());
		verifyNoMoreInteractions(transcribeService);
		assertThat(String.join("\n", screen.lines())).contains("処理: 2件");
	}

	@Test
	void 新規作業の有無で処理とスキップを数え分ける(@TempDir Path dir) throws Exception {
		Files.createFile(dir.resolve("done.mp3"));   // スキップ相当（新規作業なし）
		Files.createFile(dir.resolve("new.mp3"));    // 新規処理あり

		given(transcribeService.run(eq(dir.resolve("done.mp3").toString()), any(), any(), anyInt(), any(), anyBoolean()))
				.willReturn(new TranscribeResult(Path.of("x"), false, 0));
		given(transcribeService.run(eq(dir.resolve("new.mp3").toString()), any(), any(), anyInt(), any(), anyBoolean()))
				.willReturn(new TranscribeResult(Path.of("y"), false, 3));

		ShellScreen screen = client.sendCommand("transcribe-all -d " + dir);

		assertThat(String.join("\n", screen.lines())).contains("処理: 1件 / スキップ: 1件 / 失敗: 0件");
	}

	@Test
	void 一件失敗してもバッチを継続し失敗件数に数える(@TempDir Path dir) throws Exception {
		Files.createFile(dir.resolve("ng.mp3"));
		Files.createFile(dir.resolve("ok.mp3"));

		given(transcribeService.run(eq(dir.resolve("ng.mp3").toString()), any(), any(), anyInt(), any(), anyBoolean()))
				.willThrow(new IllegalStateException("whisper異常終了"));
		given(transcribeService.run(eq(dir.resolve("ok.mp3").toString()), any(), any(), anyInt(), any(), anyBoolean()))
				.willReturn(new TranscribeResult(Path.of("y"), true, 1));

		ShellScreen screen = client.sendCommand("transcribe-all -d " + dir);

		// ng の後も ok が処理される（継続）。
		verify(transcribeService).run(eq(dir.resolve("ok.mp3").toString()), any(), any(), anyInt(), any(), anyBoolean());
		assertThat(String.join("\n", screen.lines())).contains("処理: 1件 / スキップ: 0件 / 失敗: 1件");
	}

	@Test
	void 存在しないフォルダはエラーを返す() throws Exception {
		ShellScreen screen = client.sendCommand("transcribe-all -d C:\\no\\such\\dir_xyz");

		assertThat(String.join("\n", screen.lines())).contains("フォルダが見つかりません");
	}
}
