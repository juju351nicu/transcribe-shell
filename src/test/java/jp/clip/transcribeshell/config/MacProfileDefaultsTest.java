package jp.clip.transcribeshell.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code application-mac.properties} の既定値が実際にバインドされることを確認する。
 *
 * <p>Mac 実機が無くても検証できるよう、プロファイルを明示的に有効化して起動する
 * （実行時は {@link OsProfile} が {@code os.name} から同じプロファイルを有効にする）。
 * {@code ${user.home}} のプレースホルダが解決されるかどうかもここで確かめる。
 */
@SpringBootTest
@ActiveProfiles("mac")
class MacProfileDefaultsTest {

	@Autowired
	private TranscribeProperties properties;

	@Test
	void macプロファイルではPython不要のcppエンジンが既定になる() {
		assertThat(properties.getWhisper().getEngine()).isEqualTo("cpp");
		// 幻覚ループ対策。0 でないと whisper.cpp の出力が繰り返しで崩れる（docs/ENGINE_BENCHMARK.md）。
		assertThat(properties.getWhisper().getCpp().getMaxContext()).isZero();
	}

	@Test
	void 録音フォルダとPython起動子がMac向けに差し替わる() {
		// ${user.home} が解決されること（未解決なら文字列がそのまま残る）。
		assertThat(properties.getDefaultDir()).doesNotContain("${");
		assertThat(Path.of(properties.getDefaultDir()))
				.isEqualTo(Path.of(System.getProperty("user.home"), "Music"));
		assertThat(properties.getPyPath()).isEqualTo("python3");
	}

	@Test
	void プロファイルに書いていない項目は既定値のまま残る() {
		assertThat(properties.getFfmpegPath()).isEqualTo("ffmpeg");
		assertThat(properties.getDefaultModel()).isEqualTo("small");
		assertThat(properties.getDefaultSegmentTime()).isEqualTo(600);
		// whisper-cli は brew で PATH に入るため、コマンド名の上書きは不要。
		assertThat(properties.getWhisper().getCpp().getCommand()).isEqualTo("whisper-cli");
	}
}
