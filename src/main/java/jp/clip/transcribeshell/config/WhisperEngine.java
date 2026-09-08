package jp.clip.transcribeshell.config;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	 * <p>未設定・空は既定の {@link #FASTER}。<b>未知の値は例外にする。</b>
	 * 以前は未知の値も {@code FASTER} に丸めていたが、{@code engine=fasetr} のような打ち間違いや、
	 * {@code engine=ffm}（FFM は {@code transcribe-ffm} という別コマンドで、{@code engine} では選べない）
	 * という勘違いのときに、黙って別のエンジンが起動してしまう。
	 * 「意図と違うものが静かに動く」方が原因究明に時間がかかるので、その場で止める。
	 *
	 * @param engine 設定値（null 可）
	 * @return 判定したエンジン。未設定・空なら {@link #FASTER}
	 * @throws IllegalArgumentException 値が {@code faster} / {@code openai} / {@code cpp} のいずれでもない場合
	 */
	public static WhisperEngine from(String engine) {
		if (!StringUtils.hasText(engine)) {
			return FASTER;
		}
		String value = engine.trim();
		return Stream.of(values())
				.filter(candidate -> candidate.settingName().equalsIgnoreCase(value))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"設定 transcribe.whisper.engine の値が不正です: \"" + engine + "\""
								+ "（有効な値: " + settingNames() + "）"
								+ " / whisper.cpp を JVM 内で呼ぶ FFM 版は engine では選べません。transcribe-ffm コマンドを使ってください。"));
	}

	/**
	 * 有効な設定値をカンマ区切りで返す。エラーメッセージ用。
	 *
	 * @return 例 {@code "faster, openai, cpp"}
	 */
	private static String settingNames() {
		return Stream.of(values())
				.map(WhisperEngine::settingName)
				.collect(Collectors.joining(", "));
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
