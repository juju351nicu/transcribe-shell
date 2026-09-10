package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import jp.clip.transcribeshell.config.FfmProperties;

/**
 * {@link TranscriptQualityCheck} の単体テスト。
 *
 * <p>期待値は実測に合わせている。2026-09-08〜09 の 3 録音 8 part で、崩壊した part は同一行が
 * 16 / 55 / 67 行連続、崩壊していない part の最大は 3 行だった。音声 1 秒あたりの文字数は
 * 崩壊 1.01 に対して正常 3.39〜5.63 だが、67 行連続した part は 5.07 で捕まらなかったため、
 * 連続同一行が主の指標で文字数は補助である。
 */
class TranscriptQualityCheckTest {

	private static final int WARN_LINES = 3;
	private static final float MIN_CHARS = 2.5f;

	@Test
	void 同一行の連続を数える() {
		TranscriptQualityCheck check = TranscriptQualityCheck.of(
				List.of("あ", "い", "い", "い", "う"), 10_000L, 0);

		assertThat(check.repeatedLines()).isEqualTo(3);
		assertThat(check.repeatedText()).isEqualTo("い");
	}

	@Test
	void 連続していない繰り返しは数えない() {
		// 「はい。」が離れて何度も出るのは相槌として普通に起きる。ここで警告すると誤検知になる
		TranscriptQualityCheck check = TranscriptQualityCheck.of(
				List.of("はい。", "そうですね。", "はい。", "わかりました。", "はい。"), 10_000L, 0);

		assertThat(check.repeatedLines()).isEqualTo(1);
		assertThat(check.suspicious(WARN_LINES, 0.0f)).isFalse();
	}

	@Test
	void 崩壊したpartを検知する() {
		// 同じ 1 行だけが 55 行。実測どおりの形
		List<String> lines = java.util.Collections.nCopies(55, "幻聴の一行");
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L, 0);

		assertThat(check.repeatedLines()).isEqualTo(55);
		assertThat(check.suspicious(WARN_LINES, MIN_CHARS)).isTrue();
		assertThat(check.describe(WARN_LINES, MIN_CHARS))
				.contains("55 行連続")
				.contains("音声 1 秒あたり");
	}

	@Test
	void 正常なpartは警告しない() {
		// 10 分（600 秒）で 3,300 文字相当 = 5.5 文字/秒。相槌の 2 行連続を含む
		List<String> lines = new java.util.ArrayList<>();
		for (int i = 0; i < 150; i++) {
			lines.add("これは通常の発言でおよそ二十二文字ぶんの長さです" + i);
		}
		lines.add("はい。");
		lines.add("はい。");
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L, 0);

		assertThat(check.repeatedLines()).isEqualTo(2);
		assertThat(check.charsPerSecond()).isGreaterThan(MIN_CHARS);
		assertThat(check.suspicious(WARN_LINES, MIN_CHARS)).isFalse();
	}

	@Test
	void 音声長あたりの文字数で連続していない崩壊も拾う() {
		// 連続同一行は無いが、10 分で 60 文字しかない
		List<String> lines = List.of("あ".repeat(20), "い".repeat(20), "う".repeat(20));
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L, 0);

		assertThat(check.repeatedLines()).isEqualTo(1);
		assertThat(check.charsPerSecond()).isEqualTo(0.1);
		assertThat(check.suspicious(WARN_LINES, MIN_CHARS)).isTrue();
		assertThat(check.describe(WARN_LINES, MIN_CHARS)).contains("音声 1 秒あたり 0.10 文字");
	}

	@Test
	void 空の結果は必ず警告する() {
		TranscriptQualityCheck check = TranscriptQualityCheck.of(List.of(), 600_000L, 0);

		assertThat(check.empty()).isTrue();
		assertThat(check.repeatedLines()).isZero();
		// しきい値を両方無効にしても、空は警告する
		assertThat(check.suspicious(0, 0.0f)).isTrue();
		assertThat(check.describe(WARN_LINES, MIN_CHARS)).isEqualTo("文字起こし結果が空です");
	}

	@Test
	void しきい値を0以下にするとその指標は無効になる() {
		List<String> lines = java.util.Collections.nCopies(55, "幻聴の一行");
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L, 0);

		assertThat(check.suspicious(0, 0.0f)).isFalse();
		assertThat(check.suspicious(0, MIN_CHARS)).isTrue();
		assertThat(check.suspicious(WARN_LINES, 0.0f)).isTrue();
	}

	@Test
	void 音声長が不明なら文字数の指標は使わない() {
		TranscriptQualityCheck check = TranscriptQualityCheck.of(List.of("あ"), 0L, 0);

		assertThat(check.charsPerSecond()).isNaN();
		assertThat(check.suspicious(WARN_LINES, MIN_CHARS)).isFalse();
	}

	/**
	 * 既定のしきい値が実測値を正しく振り分けることを固定する。
	 *
	 * <p>2026-09-08〜09 の 3 録音 8 part で、崩壊した part は同一行が 16 / 55 / 67 行連続、
	 * 崩壊していない part の最大は 3 行だった。3 では普通の発話文の重複を誤検知したので 5 にしている。
	 * ここを緩めると崩壊を見逃し、締めると正常な part を騒がせるので、両側を明示的に固定する。
	 */
	@Test
	void 既定のしきい値は実測値を正しく振り分ける() {
		FfmProperties defaults = new FfmProperties();
		int lines = defaults.getRepetitionWarnLines();
		float chars = defaults.getMinCharsPerAudioSecond();

		// 崩壊側の最小（16 行連続）は検知する。実測の幻聴と同じ長さの行を使う
		assertThat(TranscriptQualityCheck
				.of(java.util.Collections.nCopies(16, "私は、私のビデオを紹介します。"), 600_000L,
						defaults.getRepetitionMinLineLength())
				.suspicious(lines, chars)).isTrue();

		// 崩壊していない側の最大（3 行連続 / 3.39 文字per秒）は警告しない
		List<String> normal = new java.util.ArrayList<>();
		for (int i = 0; i < 80; i++) {
			normal.add("これは通常の発言でおよそ二十五文字ぶんの長さになります" + i);
		}
		normal.add("普通の発話文");
		normal.add("普通の発話文");
		normal.add("普通の発話文");
		TranscriptQualityCheck check = TranscriptQualityCheck.of(normal, 600_000L, 0);
		assertThat(check.repeatedLines()).isEqualTo(3);
		assertThat(check.charsPerSecond()).isGreaterThan(chars);
		assertThat(check.suspicious(lines, chars)).isFalse();
	}

	/**
	 * 短い行の連続は数えない。
	 *
	 * <p>2026-09-10 に「はい。」が 5 行連続して誤検知し、3 分の再実行を無駄にした。相槌は自然に連続する。
	 * 一方、崩壊時に繰り返される行は実測 4 例すべてが 15 文字以上で、幻聴も実発話の復唱も文の形をしている。
	 * 回数を上げるのではなく長さで切るのは、<b>長い文が 5 行続くのは崩壊、相槌が 6 行続くのは自然</b>
	 * という区別をそのまま条件にできるため。
	 */
	@Test
	void 短い行の連続は数えない() {
		FfmProperties defaults = new FfmProperties();
		int minLength = defaults.getRepetitionMinLineLength();

		// 相槌が 5 行連続 → 数えない
		TranscriptQualityCheck aizuchi = TranscriptQualityCheck.of(
				java.util.Collections.nCopies(5, "はい。"), 10_000L, minLength);
		assertThat(aizuchi.repeatedLines()).isEqualTo(1);
		assertThat(aizuchi.suspicious(defaults.getRepetitionWarnLines(), 0.0f)).isFalse();

		// 文の形をした行が 5 行連続 → 数える（実測の幻聴と同じ 15 文字）
		TranscriptQualityCheck loop = TranscriptQualityCheck.of(
				java.util.Collections.nCopies(5, "私は、私のビデオを紹介します。"), 10_000L, minLength);
		assertThat(loop.repeatedLines()).isEqualTo(5);
		assertThat(loop.suspicious(defaults.getRepetitionWarnLines(), 0.0f)).isTrue();
	}

	@Test
	void 短い行を飛ばしても長い行の連続は見つける() {
		// 相槌の 6 行連続より、文の 5 行連続の方を報告すること
		List<String> lines = new java.util.ArrayList<>();
		lines.addAll(java.util.Collections.nCopies(6, "はい。"));
		lines.add("区切りの一行です。");
		lines.addAll(java.util.Collections.nCopies(5, "私は、私のビデオを紹介します。"));

		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 10_000L, 8);

		assertThat(check.repeatedLines()).isEqualTo(5);
		assertThat(check.repeatedText()).isEqualTo("私は、私のビデオを紹介します。");
	}

	@Test
	void 長さのしきい値を0にするとすべての連続を数える() {
		TranscriptQualityCheck check = TranscriptQualityCheck.of(
				java.util.Collections.nCopies(5, "はい。"), 10_000L, 0);

		assertThat(check.repeatedLines()).isEqualTo(5);
	}

	@Test
	void 長い行は警告メッセージ内で省略する() {
		List<String> lines = java.util.Collections.nCopies(5, "あ".repeat(40));
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 1_000L, 0);

		assertThat(check.describe(WARN_LINES, 0.0f)).contains("あ".repeat(20) + "…");
	}
}
