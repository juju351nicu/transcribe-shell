package jp.clip.transcribeshell.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link WhisperEngine} の単体テスト。設定文字列の解釈（大小・空白・未知の値）を固定する。
 */
class WhisperEngineTest {

	@Test
	void 設定文字列は大小と前後空白を無視して判定する() {
		assertThat(WhisperEngine.from("faster")).isEqualTo(WhisperEngine.FASTER);
		assertThat(WhisperEngine.from("OpenAI")).isEqualTo(WhisperEngine.OPENAI);
		assertThat(WhisperEngine.from("  cpp  ")).isEqualTo(WhisperEngine.CPP);
	}

	@Test
	void 未設定や未知の値は既定のfasterとして扱う() {
		// 設定ミスで実行そのものを止めるより、従来どおり既定エンジンで動かす方が運用事故が小さい。
		assertThat(WhisperEngine.from(null)).isEqualTo(WhisperEngine.FASTER);
		assertThat(WhisperEngine.from("")).isEqualTo(WhisperEngine.FASTER);
		assertThat(WhisperEngine.from("whisper.cpp")).isEqualTo(WhisperEngine.FASTER);
	}

	@Test
	void settingNameは設定ファイルとログで使う小文字表記を返す() {
		assertThat(WhisperEngine.FASTER.settingName()).isEqualTo("faster");
		assertThat(WhisperEngine.OPENAI.settingName()).isEqualTo("openai");
		assertThat(WhisperEngine.CPP.settingName()).isEqualTo("cpp");
	}
}
