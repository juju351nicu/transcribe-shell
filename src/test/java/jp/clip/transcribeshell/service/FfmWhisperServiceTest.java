package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jp.clip.transcribeshell.config.FfmProperties;
import jp.clip.whisper.SamplingStrategy;

/**
 * {@link FfmWhisperService} のうち、ネイティブライブラリを動かさずに検証できる部分の単体テスト。
 *
 * <p>実際の whisper.cpp 呼び出し（モデル読み込み・文字起こし）はネイティブとモデルファイルが必要なので
 * ローカルの手動 E2E に任せる（既存方針と同じ）。ここでは冪等性の判定・モデル解決・言語表記の正規化・
 * オプションと設定値の解決を見る。
 */
class FfmWhisperServiceTest {

	private final FfmProperties properties = new FfmProperties();

	private final FfmWhisperService service = new FfmWhisperService(properties);

	/** コマンドで何も指定しなかったときのオプション（{@code transcribe-ffm -f x.mp3} 相当）。 */
	private static FfmOptions defaultOptions() {
		return FfmOptions.builder()
				.model("small")
				.language("Japanese")
				.segmentTime(600)
				.force(false)
				.threads(0)
				.build();
	}

	@Test
	void 全partに_txtがあればモデルを読み込まずに0を返す(@TempDir Path dir) throws IOException {
		Files.createFile(dir.resolve("part_000.wav"));
		Files.createFile(dir.resolve("part_000.txt"));
		Files.createFile(dir.resolve("part_001.wav"));
		Files.createFile(dir.resolve("part_001.txt"));

		// モデルパスは存在しなくてよい。全件スキップならエンジンを開かないことの検証になる
		int transcribed = service.transcribeAll(dir, dir.resolve("no-such-model.bin"), defaultOptions());

		assertThat(transcribed).isZero();
	}

	@Test
	void partが無ければ例外にする(@TempDir Path dir) {
		assertThatThrownBy(() -> service.transcribeAll(dir, dir.resolve("model.bin"), defaultOptions()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("part_*.wav");
	}

	@Test
	void listPartsはwavだけを名前順に返す(@TempDir Path dir) throws IOException {
		Files.createFile(dir.resolve("part_001.wav"));
		Files.createFile(dir.resolve("part_000.wav"));
		Files.createFile(dir.resolve("part_000.mp3"));
		Files.createFile(dir.resolve("other.wav"));

		assertThat(service.listParts(dir))
				.extracting(p -> p.getFileName().toString())
				.containsExactly("part_000.wav", "part_001.wav");
	}

	@Test
	void resolveModelは既存パスをそのまま返し_名前ならmodel_dir配下のggmlファイルを探す(@TempDir Path dir) throws IOException {
		Path direct = Files.createFile(dir.resolve("my-model.bin"));
		Path inDir = Files.createFile(dir.resolve("ggml-small.bin"));
		properties.setModelDir(dir.toString());

		assertThat(service.resolveModel(direct.toString())).isEqualTo(direct);
		assertThat(service.resolveModel("small")).isEqualTo(inDir);
		assertThatThrownBy(() -> service.resolveModel("large-v3"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ggml-large-v3.bin")
				.hasMessageContaining("download-model.ps1");
	}

	@Test
	void スレッド数はオプション優先_未指定なら設定値() {
		properties.setThreads(6);

		assertThat(service.effectiveThreads(defaultOptions())).isEqualTo(6);
		assertThat(service.effectiveThreads(defaultOptions().toBuilder().threads(8).build())).isEqualTo(8);
	}

	@Test
	void VADはオプションで明示した値が優先され_未指定なら設定値() {
		// 設定の既定は off（繋ぎ目の文が落ちるため）
		assertThat(service.effectiveVad(defaultOptions())).isFalse();
		assertThat(service.effectiveVad(defaultOptions().toBuilder().vad(true).build())).isTrue();

		properties.setVad(true);
		assertThat(service.effectiveVad(defaultOptions())).isTrue();
		// 明示的な false は「未指定」と区別され、設定より優先される
		assertThat(service.effectiveVad(defaultOptions().toBuilder().vad(false).build())).isFalse();
	}

	@Test
	void 探索方法もオプション優先_未指定なら設定値() {
		assertThat(service.effectiveSamplingStrategy(defaultOptions())).isEqualTo(SamplingStrategy.GREEDY);
		assertThat(service.effectiveSamplingStrategy(defaultOptions().toBuilder().beamSearch(true).build()))
				.isEqualTo(SamplingStrategy.BEAM_SEARCH);

		properties.setBeamSearch(true);
		assertThat(service.effectiveSamplingStrategy(defaultOptions())).isEqualTo(SamplingStrategy.BEAM_SEARCH);
		assertThat(service.effectiveSamplingStrategy(defaultOptions().toBuilder().beamSearch(false).build()))
				.isEqualTo(SamplingStrategy.GREEDY);
	}

	@Test
	void 初期プロンプトはオプション優先_次に設定値_最後にファイル(@TempDir Path dir) throws IOException {
		// 既定のファイルパス（ホーム直下）は存在しない前提。無ければ null で続行する
		properties.setInitialPromptFile(dir.resolve("missing.txt").toString());
		assertThat(service.effectiveInitialPrompt(defaultOptions())).isNull();

		Path file = dir.resolve("prompt.txt");
		// 先頭の U+FEFF（BOM）はメモ帳の「BOM 付き UTF-8」保存を模したもの。コメント行として無視されること
		Files.writeString(file, "\uFEFF# 参加者\n田中さん、鈴木さん、\n\n  佐藤さんの定例会議です。 \n", StandardCharsets.UTF_8);
		properties.setInitialPromptFile(file.toString());
		assertThat(service.effectiveInitialPrompt(defaultOptions())).isEqualTo("田中さん、鈴木さん、佐藤さんの定例会議です。");

		properties.setInitialPrompt("  高橋さん ");
		assertThat(service.effectiveInitialPrompt(defaultOptions())).isEqualTo("高橋さん");

		assertThat(service.effectiveInitialPrompt(defaultOptions().toBuilder().prompt(" 渡辺さん ").build()))
				.isEqualTo("渡辺さん");
	}

	@Test
	void 言語表記はwhisper_cppが照合できる小文字に揃える() {
		assertThat(FfmWhisperService.toWhisperLanguage("Japanese")).isEqualTo("japanese");
		assertThat(FfmWhisperService.toWhisperLanguage(" ja ")).isEqualTo("ja");
		assertThat(FfmWhisperService.toWhisperLanguage("")).isEqualTo("auto");
		assertThat(FfmWhisperService.toWhisperLanguage(null)).isEqualTo("auto");
	}

	@Test
	void buildConfigは設定値をそのままWhisperConfigへ写す(@TempDir Path dir) throws IOException {
		Path model = Files.createFile(dir.resolve("ggml-small.bin"));
		properties.setInitialPromptFile(dir.resolve("missing.txt").toString());
		properties.setMaxTextContext(0);
		properties.setCarryInitialPrompt(true);
		properties.setSuppressNonSpeechTokens(true);

		assertThat(service.buildConfig(model, defaultOptions()))
				.satisfies(config -> {
					assertThat(config.model()).isEqualTo(model);
					assertThat(config.language()).isEqualTo("japanese");
					assertThat(config.maxTextContext()).isZero();
					assertThat(config.carryInitialPrompt()).isTrue();
					assertThat(config.suppressNonSpeechTokens()).isTrue();
					// アプリ側の既定（実測に基づく値）が渡っていること
					assertThat(config.bestOf()).isEqualTo(-1);
					assertThat(config.beamSize()).isEqualTo(2);
					assertThat(config.temperatureIncrement()).isEqualTo(0.4f);
				});
	}
}
