package jp.clip.transcribeshell.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link OsProfile} の単体テスト。
 *
 * <p>{@code os.name} の表記は JVM や OS のバージョンで揺れるため、実際に現れる値のパターンを固定する。
 */
class OsProfileTest {

	@Test
	void macOSの表記ゆれをどれもmacと判定する() {
		assertThat(OsProfile.from("Mac OS X")).isEqualTo(OsProfile.MAC);
		assertThat(OsProfile.from("macOS")).isEqualTo(OsProfile.MAC);
		assertThat(OsProfile.from("Darwin")).isEqualTo(OsProfile.MAC);
	}

	@Test
	void Linuxはlinuxと判定する() {
		assertThat(OsProfile.from("Linux")).isEqualTo(OsProfile.LINUX);
		assertThat(OsProfile.from("linux")).isEqualTo(OsProfile.LINUX);
	}

	@Test
	void Windowsと未知のOSはプロファイルを追加しない() {
		// Windows は既定値がそのまま効く（従来の挙動を変えないため）。未知の OS も同じ扱いにする。
		assertThat(OsProfile.from("Windows 11")).isEqualTo(OsProfile.NONE);
		assertThat(OsProfile.from("Windows Server 2022")).isEqualTo(OsProfile.NONE);
		assertThat(OsProfile.from("AIX")).isEqualTo(OsProfile.NONE);
		assertThat(OsProfile.from(null)).isEqualTo(OsProfile.NONE);
		assertThat(OsProfile.from("  ")).isEqualTo(OsProfile.NONE);
	}

	@Test
	void additionalProfilesはsetAdditionalProfilesにそのまま渡せる形で返す() {
		assertThat(OsProfile.MAC.additionalProfiles()).containsExactly("mac");
		assertThat(OsProfile.LINUX.additionalProfiles()).containsExactly("linux");
		// NONE は空配列。setAdditionalProfiles(空) は「何も追加しない」を意味する。
		assertThat(OsProfile.NONE.additionalProfiles()).isEmpty();
	}

	@Test
	void 実行中のOSでも判定が落ちない() {
		assertThat(OsProfile.from(System.getProperty("os.name"))).isNotNull();
	}
}
