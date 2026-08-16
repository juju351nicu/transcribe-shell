package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.clip.transcribeshell.config.TranscribeProperties;
import jp.clip.transcribeshell.process.ProcessRunner;

/**
 * {@link WhisperService} の単体テスト。{@link ProcessRunner} は Mockito でモックする。
 *
 * <p>冪等性（.txt があればスキップ、force で再実行）と whisper コマンド配列を検証する。
 */
@ExtendWith(MockitoExtension.class)
class WhisperServiceTest {

	@Mock
	private ProcessRunner processRunner;

	@Spy
	private TranscribeProperties properties = new TranscribeProperties();

	@InjectMocks
	private WhisperService service;

	/**
	 * 文字起こし対象になるダミー part を作る。
	 *
	 * <p>0 バイトだと {@code transcribe.whisper.min-part-bytes}（既定 16384）で「分割の端数」と判定されて
	 * スキップされてしまうため、閾値を超えるサイズで作る。
	 */
	private static void createPart(Path dir, String name) throws IOException {
		Files.write(dir.resolve(name), new byte[32 * 1024]);
	}

	@Test
	void txtがあるpartはスキップし_無いpartだけ_faster既定のコマンドで実行する(@TempDir Path dir) throws IOException {
		createPart(dir, "part_000.mp3");
		createPart(dir, "part_001.mp3");
		// part_000 は文字起こし済み（.txt あり）→ スキップされるはず。
		Files.createFile(dir.resolve("part_000.txt"));
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		// engine 未指定なので既定の faster（whisper-ctranslate2）で組まれる。
		int transcribed = service.transcribeAll(dir, "small", "Japanese", false);

		// part_000 はスキップ、part_001 のみ新規 → 戻り値は 1。
		assertThat(transcribed).isEqualTo(1);
		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		// part_001 の1回だけ、PYTHONUTF8=1 の env 付きで呼ばれる。
		verify(processRunner, times(1)).run(cmd.capture(), eq(dir), eq(Map.of("PYTHONUTF8", "1")));
		assertThat(cmd.getValue()).containsExactly(
				"whisper-ctranslate2", dir.resolve("part_001.mp3").toString(),
				"--language", "Japanese", "--model", "small", "--output_dir", dir.toString(),
				"--output_format", "txt", "--compute_type", "int8");
	}

	@Test
	void engineをopenaiにすると従来のpy_m_whisperコマンドで実行する(@TempDir Path dir) throws IOException {
		properties.getWhisper().setEngine("openai");
		createPart(dir, "part_000.mp3");
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		service.transcribeAll(dir, "small", "Japanese", false);

		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner, times(1)).run(cmd.capture(), eq(dir), eq(Map.of("PYTHONUTF8", "1")));
		assertThat(cmd.getValue()).containsExactly(
				"py", "-m", "whisper", dir.resolve("part_000.mp3").toString(),
				"--language", "Japanese", "--model", "small", "--output_dir", dir.toString());
	}

	@Test
	void faster設定のcommandとcompute_typeが反映される(@TempDir Path dir) throws IOException {
		// 実行ファイル名・compute_type・output_format が設定どおりコマンドに反映されることを確認する。
		properties.getWhisper().setCommand("wc2.exe");
		properties.getWhisper().setComputeType("int8_float16");
		properties.getWhisper().setOutputFormat("srt");
		createPart(dir, "part_000.mp3");
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		service.transcribeAll(dir, "base", "English", false);

		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner, times(1)).run(cmd.capture(), eq(dir), anyMap());
		assertThat(cmd.getValue()).containsExactly(
				"wc2.exe", dir.resolve("part_000.mp3").toString(),
				"--language", "English", "--model", "base", "--output_dir", dir.toString(),
				"--output_format", "srt", "--compute_type", "int8_float16");
	}

	@Test
	void forceのときは_txtがあっても再実行する(@TempDir Path dir) throws IOException {
		createPart(dir, "part_000.mp3");
		createPart(dir, "part_001.mp3");
		Files.createFile(dir.resolve("part_000.txt"));
		Files.createFile(dir.resolve("part_001.txt"));
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		service.transcribeAll(dir, "base", "English", true);

		// 2 part とも PYTHONUTF8=1 の env 付きで再実行される。
		verify(processRunner, times(2)).run(anyList(), eq(dir), eq(Map.of("PYTHONUTF8", "1")));
	}

	@Test
	void part_mp3が無ければ例外(@TempDir Path dir) {
		assertThatThrownBy(() -> service.transcribeAll(dir, "small", "Japanese", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("part_*.mp3");
	}

	@Test
	void engineをcppにするとwhisper_cliのコマンドで実行する(@TempDir Path dir, @TempDir Path modelDir) throws IOException {
		Files.createFile(modelDir.resolve("ggml-small.bin"));
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setModelDir(modelDir.toString());
		createPart(dir, "part_000.mp3");
		// whisper-cli は -otxt で part_000.txt を作る。実行後の出力チェックを通すため、モックでも同じ副作用を再現する。
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenAnswer(invocation -> {
			Files.createFile(dir.resolve("part_000.txt"));
			return 0;
		});

		int transcribed = service.transcribeAll(dir, "small", "Japanese", false);

		assertThat(transcribed).isEqualTo(1);
		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner, times(1)).run(cmd.capture(), eq(dir), anyMap());
		// -m は .bin の実ファイルパス、-of は拡張子なし（part_000.mp3.txt にさせないため）、
		// -mc 0 は幻覚ループ対策の既定、-t は threads=0 なので付かない。
		assertThat(cmd.getValue()).containsExactly(
				"whisper-cli",
				"-m", modelDir.resolve("ggml-small.bin").toString(),
				"-f", dir.resolve("part_000.mp3").toString(),
				"-l", "Japanese",
				"-otxt",
				"-of", dir.resolve("part_000").toString(),
				"-mc", "0");
	}

	@Test
	void cppのmax_contextを負値にすると_mcを渡さない(@TempDir Path dir, @TempDir Path modelDir) throws IOException {
		// -1 なら whisper-cli 自身の既定（文脈を無制限に引き継ぐ）に任せる。
		Files.createFile(modelDir.resolve("ggml-small.bin"));
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setModelDir(modelDir.toString());
		properties.getWhisper().getCpp().setMaxContext(-1);
		createPart(dir, "part_000.mp3");
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenAnswer(invocation -> {
			Files.createFile(dir.resolve("part_000.txt"));
			return 0;
		});

		service.transcribeAll(dir, "small", "Japanese", false);

		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner, times(1)).run(cmd.capture(), eq(dir), anyMap());
		assertThat(cmd.getValue()).doesNotContain("-mc");
	}

	@Test
	void cppのlanguageとthreadsを設定するとコマンドに反映される(@TempDir Path dir, @TempDir Path modelDir) throws IOException {
		Files.createFile(modelDir.resolve("ggml-base-q5_1.bin"));
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setModelDir(modelDir.toString());
		properties.getWhisper().getCpp().setCommand("C:\\tools\\whisper.cpp\\Release\\whisper-cli.exe");
		properties.getWhisper().getCpp().setLanguage("ja");
		properties.getWhisper().getCpp().setThreads(8);
		createPart(dir, "part_000.mp3");
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenAnswer(invocation -> {
			Files.createFile(dir.resolve("part_000.txt"));
			return 0;
		});

		// モデル名に量子化サフィックスを渡すと、命名規則そのままで ggml-base-q5_1.bin が解決される。
		service.transcribeAll(dir, "base-q5_1", "Japanese", false);

		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner, times(1)).run(cmd.capture(), eq(dir), anyMap());
		assertThat(cmd.getValue()).containsExactly(
				"C:\\tools\\whisper.cpp\\Release\\whisper-cli.exe",
				"-m", modelDir.resolve("ggml-base-q5_1.bin").toString(),
				"-f", dir.resolve("part_000.mp3").toString(),
				"-l", "ja",
				"-otxt",
				"-of", dir.resolve("part_000").toString(),
				"-mc", "0",
				"-t", "8");
	}

	@Test
	void cppでモデルファイルが無ければ入手方法を含む例外(@TempDir Path dir, @TempDir Path modelDir) throws IOException {
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setModelDir(modelDir.toString());
		createPart(dir, "part_000.mp3");

		assertThatThrownBy(() -> service.transcribeAll(dir, "small", "Japanese", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(modelDir.resolve("ggml-small.bin").toString())
				.hasMessageContaining("huggingface.co");
		// 起動する前に落とすので whisper-cli は呼ばない。
		verifyNoInteractions(processRunner);
	}

	@Test
	void 閾値未満の極小partは警告してスキップし_残りの処理は続行する(@TempDir Path dir) throws IOException {
		createPart(dir, "part_000.mp3");
		// 分割の端数でできる極小 part（既定閾値 16384 バイト未満）。ここで止まらず次へ進むこと。
		Files.write(dir.resolve("part_001.mp3"), new byte[1379]);
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		int transcribed = service.transcribeAll(dir, "small", "Japanese", false);

		// 実処理は part_000 の 1件のみ。スキップした part は数に含めない。
		assertThat(transcribed).isEqualTo(1);
		ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
		verify(processRunner, times(1)).run(cmd.capture(), eq(dir), anyMap());
		assertThat(cmd.getValue()).contains(dir.resolve("part_000.mp3").toString());
	}

	@Test
	void 閾値以上のpartは従来どおり処理する(@TempDir Path dir) throws IOException {
		// 境界値: ちょうど閾値ぴったりならスキップしない。
		Files.write(dir.resolve("part_000.mp3"), new byte[16384]);
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		int transcribed = service.transcribeAll(dir, "small", "Japanese", false);

		assertThat(transcribed).isEqualTo(1);
		verify(processRunner, times(1)).run(anyList(), eq(dir), anyMap());
	}

	@Test
	void min_part_bytesを0にすると極小partも従来どおり処理する(@TempDir Path dir) throws IOException {
		properties.getWhisper().setMinPartBytes(0);
		Files.write(dir.resolve("part_000.mp3"), new byte[1379]);
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		int transcribed = service.transcribeAll(dir, "small", "Japanese", false);

		assertThat(transcribed).isEqualTo(1);
		verify(processRunner, times(1)).run(anyList(), eq(dir), anyMap());
	}

	@Test
	void cppは終了コード0でもtxtが作られなければ例外(@TempDir Path dir, @TempDir Path modelDir) throws IOException {
		Files.createFile(modelDir.resolve("ggml-small.bin"));
		properties.getWhisper().setEngine("cpp");
		properties.getWhisper().getCpp().setModelDir(modelDir.toString());
		createPart(dir, "part_000.mp3");
		// whisper-cli は言語指定が不正だと exit=0 のまま何も出力せずに終わる（実機で再現確認済み）。
		when(processRunner.run(anyList(), eq(dir), anyMap())).thenReturn(0);

		assertThatThrownBy(() -> service.transcribeAll(dir, "small", "Japanese", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("part_000.txt");
	}
}
