package jp.clip.transcribeshell.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code application-linux.properties} の既定値が実際にバインドされることを確認する。
 *
 * <p>Ubuntu 実機が無くても検証できるよう、プロファイルを明示的に有効化して起動する。
 */
@SpringBootTest
@ActiveProfiles("linux")
class LinuxProfileDefaultsTest {

	@Autowired
	private TranscribeProperties properties;

	@Test
	void linuxプロファイルではPython不要のcppエンジンが既定になる() {
		assertThat(properties.getWhisper().getEngine()).isEqualTo("cpp");
		assertThat(properties.getWhisper().getCpp().getMaxContext()).isZero();
	}

	@Test
	void 録音フォルダとPython起動子がLinux向けに差し替わる() {
		assertThat(properties.getDefaultDir()).doesNotContain("${");
		assertThat(Path.of(properties.getDefaultDir()))
				.isEqualTo(Path.of(System.getProperty("user.home"), "Music"));
		assertThat(properties.getPyPath()).isEqualTo("python3");
	}
}
