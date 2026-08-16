package jp.clip.transcribeshell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import jp.clip.transcribeshell.config.TranscribeProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationTests {

	@Autowired
	private TranscribeProperties properties;

	@Test
	void contextLoads() {
		assertThat(Path.of(properties.getDefaultDir()))
				.isEqualTo(Path.of(System.getProperty("user.home"), "Music"));
	}

}
