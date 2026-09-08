package jp.clip.transcribeshell.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
	void 未設定と空は既定のfasterとして扱う() {
		// 「設定していない」は正常な状態。既定エンジンで動く
		assertThat(WhisperEngine.from(null)).isEqualTo(WhisperEngine.FASTER);
		assertThat(WhisperEngine.from("")).isEqualTo(WhisperEngine.FASTER);
		assertThat(WhisperEngine.from("   ")).isEqualTo(WhisperEngine.FASTER);
	}

	/**
	 * 未知の値は例外にする。
	 *
	 * <p>以前は faster に丸めていたが、{@code engine=fasetr} と打ち間違えても、
	 * {@code engine=ffm} と勘違いして書いても、黙って faster-whisper が起動していた。
	 * 意図と違うエンジンが静かに動く方が原因究明に時間がかかる。
	 */
	@Test
	void 未知の値は有効値を示して例外にする() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> WhisperEngine.from("fasetr"))
				.withMessageContaining("faster, openai, cpp");

		// FFM は engine では選べない（transcribe-ffm という別コマンド）ことを案内する
		assertThatIllegalArgumentException()
				.isThrownBy(() -> WhisperEngine.from("ffm"))
				.withMessageContaining("transcribe-ffm");

		assertThatIllegalArgumentException().isThrownBy(() -> WhisperEngine.from("whisper.cpp"));
	}

	@Test
	void settingNameは設定ファイルとログで使う小文字表記を返す() {
		assertThat(WhisperEngine.FASTER.settingName()).isEqualTo("faster");
		assertThat(WhisperEngine.OPENAI.settingName()).isEqualTo("openai");
		assertThat(WhisperEngine.CPP.settingName()).isEqualTo("cpp");
	}
}
