package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link TranscriptQualityCheck} の単体テスト。
 *
 * <p>期待値は実測に合わせている。2026-09-08 に崩壊した part は同一行 55 行連続・音声 1 秒あたり 1.01 文字、
 * 正常な part は最長 2 行・3.83〜5.63 文字だった。既定のしきい値（3 行 / 2.5 文字）がその両方を
 * 正しく振り分けることを固定する。
 */
class TranscriptQualityCheckTest {

	private static final int WARN_LINES = 3;
	private static final float MIN_CHARS = 2.5f;

	@Test
	void 同一行の連続を数える() {
		TranscriptQualityCheck check = TranscriptQualityCheck.of(
				List.of("あ", "い", "い", "い", "う"), 10_000L);

		assertThat(check.repeatedLines()).isEqualTo(3);
		assertThat(check.repeatedText()).isEqualTo("い");
	}

	@Test
	void 連続していない繰り返しは数えない() {
		// 「はい。」が離れて何度も出るのは相槌として普通に起きる。ここで警告すると誤検知になる
		TranscriptQualityCheck check = TranscriptQualityCheck.of(
				List.of("はい。", "そうですね。", "はい。", "わかりました。", "はい。"), 10_000L);

		assertThat(check.repeatedLines()).isEqualTo(1);
		assertThat(check.suspicious(WARN_LINES, 0.0f)).isFalse();
	}

	@Test
	void 崩壊したpartを検知する() {
		// 同じ 1 行だけが 55 行。実測どおりの形
		List<String> lines = java.util.Collections.nCopies(55, "幻聴の一行");
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L);

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
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L);

		assertThat(check.repeatedLines()).isEqualTo(2);
		assertThat(check.charsPerSecond()).isGreaterThan(MIN_CHARS);
		assertThat(check.suspicious(WARN_LINES, MIN_CHARS)).isFalse();
	}

	@Test
	void 音声長あたりの文字数で連続していない崩壊も拾う() {
		// 連続同一行は無いが、10 分で 60 文字しかない
		List<String> lines = List.of("あ".repeat(20), "い".repeat(20), "う".repeat(20));
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L);

		assertThat(check.repeatedLines()).isEqualTo(1);
		assertThat(check.charsPerSecond()).isEqualTo(0.1);
		assertThat(check.suspicious(WARN_LINES, MIN_CHARS)).isTrue();
		assertThat(check.describe(WARN_LINES, MIN_CHARS)).contains("音声 1 秒あたり 0.10 文字");
	}

	@Test
	void 空の結果は必ず警告する() {
		TranscriptQualityCheck check = TranscriptQualityCheck.of(List.of(), 600_000L);

		assertThat(check.empty()).isTrue();
		assertThat(check.repeatedLines()).isZero();
		// しきい値を両方無効にしても、空は警告する
		assertThat(check.suspicious(0, 0.0f)).isTrue();
		assertThat(check.describe(WARN_LINES, MIN_CHARS)).isEqualTo("文字起こし結果が空です");
	}

	@Test
	void しきい値を0以下にするとその指標は無効になる() {
		List<String> lines = java.util.Collections.nCopies(55, "幻聴の一行");
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 600_000L);

		assertThat(check.suspicious(0, 0.0f)).isFalse();
		assertThat(check.suspicious(0, MIN_CHARS)).isTrue();
		assertThat(check.suspicious(WARN_LINES, 0.0f)).isTrue();
	}

	@Test
	void 音声長が不明なら文字数の指標は使わない() {
		TranscriptQualityCheck check = TranscriptQualityCheck.of(List.of("あ"), 0L);

		assertThat(check.charsPerSecond()).isNaN();
		assertThat(check.suspicious(WARN_LINES, MIN_CHARS)).isFalse();
	}

	@Test
	void 長い行は警告メッセージ内で省略する() {
		List<String> lines = java.util.Collections.nCopies(5, "あ".repeat(40));
		TranscriptQualityCheck check = TranscriptQualityCheck.of(lines, 1_000L);

		assertThat(check.describe(WARN_LINES, 0.0f)).contains("あ".repeat(20) + "…");
	}
}
