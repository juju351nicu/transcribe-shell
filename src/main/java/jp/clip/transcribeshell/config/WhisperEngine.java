package jp.clip.transcribeshell.config;

import java.util.Locale;

import org.springframework.util.StringUtils;

/**
 * 文字起こしエンジンの種別。{@code transcribe.whisper.engine} の文字列を型に落とす。
 *
 * <p>設定文字列の解釈（大小・前後空白の無視、未知の値の扱い）を 1 箇所に集約するために enum にしている。
 * 判定が {@code WhisperService} と {@link PartFormat} の 2 箇所に散らばると、
 * 「文字起こしは cpp なのに分割は mp3 判定」のようなズレが起きるため。
 */
public enum WhisperEngine {

	/** whisper-ctranslate2（faster-whisper）。既定。 */
	FASTER,

	/** openai-whisper（{@code py -m whisper}）。 */
	OPENAI,

	/** whisper.cpp の CLI（whisper-cli）。Python 不要。 */
	CPP;

	/**
	 * 設定文字列からエンジンを判定する。
	 *
	 * <p>未設定・空・未知の値は既定の {@link #FASTER} とみなす（従来の挙動を維持するため。
	 * 設定ミスで実行そのものが止まるより、既定エンジンで動いた方が運用上の事故が小さい）。
	 *
	 * @param engine 設定値（null 可）
	 * @return 判定したエンジン。判定できなければ {@link #FASTER}
	 */
	public static WhisperEngine from(String engine) {
		if (!StringUtils.hasText(engine)) {
			return FASTER;
		}
		String value = engine.trim();
		if (OPENAI.settingName().equalsIgnoreCase(value)) {
			return OPENAI;
		}
		if (CPP.settingName().equalsIgnoreCase(value)) {
			return CPP;
		}
		return FASTER;
	}

	/**
	 * 設定ファイルやログで使う小文字表記（{@code faster} / {@code openai} / {@code cpp}）。
	 *
	 * @return 小文字のエンジン名
	 */
	public String settingName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
