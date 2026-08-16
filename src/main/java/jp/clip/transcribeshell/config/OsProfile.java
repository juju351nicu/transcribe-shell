package jp.clip.transcribeshell.config;

import java.util.Locale;

import org.springframework.util.StringUtils;

/**
 * 実行中の OS から、追加で有効にする Spring プロファイルを決める。
 *
 * <p>録音フォルダ・Python の起動子・文字起こしエンジンの既定値は OS ごとに違うため、
 * {@code application-mac.properties} / {@code application-linux.properties} で分離している。
 * どのプロファイルを使うかを利用者に指定させると USB で持ち歩いたときに設定漏れが起きるので、
 * {@code os.name} から自動で決める。
 *
 * <p><b>Windows では何も追加しない</b>（{@link #NONE}）。プロファイル未指定＝
 * {@link TranscribeProperties} のフィールド初期値がそのまま効くため、従来の挙動が一切変わらない。
 *
 * <p>自動判定より優先したい場合は、実行時に {@code -Dtranscribe.xxx=...} で個別のプロパティを
 * 上書きすればよい（プロパティ指定の方が優先度が高い）。
 */
public enum OsProfile {

	/** macOS。{@code application-mac.properties} が効く。 */
	MAC("mac"),

	/** Linux（Ubuntu 等）。{@code application-linux.properties} が効く。 */
	LINUX("linux"),

	/** Windows など、OS 固有プロファイルを使わない環境。既定値がそのまま効く。 */
	NONE(null);

	/** 有効化するプロファイル名。null なら追加しない。 */
	private final String profileName;

	OsProfile(String profileName) {
		this.profileName = profileName;
	}

	/**
	 * {@code os.name} システムプロパティの値から判定する。
	 *
	 * <p>値の表記は JVM や OS のバージョンで揺れる（"Mac OS X" / "Darwin" など）ため、
	 * 完全一致ではなく小文字化した部分一致で判定する。判定できない OS は {@link #NONE}
	 * （既定値のまま動かす方が、未知の環境で設定が化けるより安全）。
	 *
	 * @param osName {@code os.name} の値。null / 空でも可
	 * @return 該当するプロファイル。判定できなければ {@link #NONE}
	 */
	public static OsProfile from(String osName) {
		if (!StringUtils.hasText(osName)) {
			return NONE;
		}
		String value = osName.toLowerCase(Locale.ROOT);
		if (value.contains("mac") || value.contains("darwin")) {
			return MAC;
		}
		if (value.contains("linux")) {
			return LINUX;
		}
		return NONE;
	}

	/**
	 * {@code SpringApplication#setAdditionalProfiles(String...)} にそのまま渡せる配列を返す。
	 *
	 * @return プロファイル名 1 件の配列。{@link #NONE} のときは空配列
	 */
	public String[] additionalProfiles() {
		return profileName == null ? new String[0] : new String[] { profileName };
	}
}
