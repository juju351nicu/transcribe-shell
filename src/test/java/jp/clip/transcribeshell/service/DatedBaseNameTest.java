package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link DatedBaseName} の単体テスト。
 *
 * <p>結合ファイル名に使うので、<b>変換できない名前を落とさない</b>ことが一番大事。
 * 手元の録音は IC レコーダーの {@code YYMMDD_HHMM} と {@code YYMMDD_連番} の 2 種類だが、
 * 利用者が任意の名前を付けたファイルも普通に処理できなければならない。
 */
class DatedBaseNameTest {

	@Test
	void 日時形式のファイル名を展開する() {
		assertThat(DatedBaseName.of("260910_1539")).isEqualTo("2026-09-10_1539");
	}

	@Test
	void 連番形式のファイル名を展開する() {
		assertThat(DatedBaseName.of("260619_003")).isEqualTo("2026-06-19_003");
	}

	@Test
	void 区切りとその後ろはそのまま残す() {
		assertThat(DatedBaseName.of("260910-1539")).isEqualTo("2026-09-10-1539");
		assertThat(DatedBaseName.of("260910_定例会議")).isEqualTo("2026-09-10_定例会議");
		assertThat(DatedBaseName.of("260910_001_re")).isEqualTo("2026-09-10_001_re");
	}

	@Test
	void 日付だけの名前も展開する() {
		assertThat(DatedBaseName.of("260910")).isEqualTo("2026-09-10");
	}

	@Test
	void 月末と年末の境界を正しく扱う() {
		assertThat(DatedBaseName.of("260228_001")).isEqualTo("2026-02-28_001");
		assertThat(DatedBaseName.of("261231_001")).isEqualTo("2026-12-31_001");
		assertThat(DatedBaseName.of("260101_001")).isEqualTo("2026-01-01_001");
		// 2028 年はうるう年なので 2 月 29 日は実在する
		assertThat(DatedBaseName.of("280229_001")).isEqualTo("2028-02-29_001");
	}

	@Test
	void 実在しない日付は変換しない() {
		assertThat(DatedBaseName.of("261340_001")).isEqualTo("261340_001");
		assertThat(DatedBaseName.of("260230_001")).isEqualTo("260230_001");
		assertThat(DatedBaseName.of("260000_001")).isEqualTo("260000_001");
		// 2026 年はうるう年ではないので 2 月 29 日は実在しない
		assertThat(DatedBaseName.of("260229_001")).isEqualTo("260229_001");
	}

	@Test
	void 日付として読めない名前はそのまま返す() {
		assertThat(DatedBaseName.of("meeting")).isEqualTo("meeting");
		assertThat(DatedBaseName.of("会議録音")).isEqualTo("会議録音");
		assertThat(DatedBaseName.of("2026-09-10_1539")).isEqualTo("2026-09-10_1539");
		assertThat(DatedBaseName.of("12345")).isEqualTo("12345");
		assertThat(DatedBaseName.of("")).isEmpty();
	}

	@Test
	void 区切りがアンダースコアかハイフン以外なら変換しない() {
		// 「6 桁の数字で始まる別の意味の名前」を日付に化けさせないため、区切りを限定している
		assertThat(DatedBaseName.of("260910.1539")).isEqualTo("260910.1539");
		assertThat(DatedBaseName.of("2609101539")).isEqualTo("2609101539");
	}

	@Test
	void nullはnullのまま返す() {
		assertThat(DatedBaseName.of(null)).isNull();
	}
}
