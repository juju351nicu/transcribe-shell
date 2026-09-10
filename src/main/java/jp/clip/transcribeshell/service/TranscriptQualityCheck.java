package jp.clip.transcribeshell.service;

import java.util.List;
import java.util.Locale;

/**
 * 文字起こし結果が「議事録として使えているか」をざっと見る検査。
 *
 * <p><b>なぜ必要か。</b> whisper.cpp は非発話区間で幻聴（実際には話されていない文）を出すことがあり、
 * さらに 30 秒ウィンドウごとに前の出力を次のプロンプトへ引き継ぐため、一度その状態に入ると
 * そのまま固定される。2026-09-08 の実測では、非発話区間が 23% を占める 10 分の part で
 * <b>同じ 1 行が 55 行連続し、会話が 1 文も残らなかった</b>。このとき例外は出ず、ログは
 * 「done」と表示され、`.txt` も普通に書かれる。<b>気づけないことが一番の問題</b>なので、
 * 書き出す前に数えて警告する。
 *
 * <p><b>短い行の連続は数えない。</b>「はい。」のような相槌は自然に 5 行続くことがあり、実際に誤検知した。
 * 一方、崩壊時に繰り返される行は実測 4 例すべてが 15 文字以上（幻聴も実発話の復唱も文の形をしている）。
 * そのため既定では 8 文字以下の行の連続を無視する（{@code transcribe.ffm.repetition-min-line-length}）。
 *
 * <p><b>直さずに警告だけにしている理由。</b> 対処は VAD を有効にすることだが、VAD は逆に
 * 「発話が途切れない録音では文字数が 3 割減る」という副作用がある。崩壊の実例がまだ少ないうちに
 * 自動で切り替えると、正常な録音を壊す方に転びうる。だから判断は人に残し、ここは検知だけを行う。
 * 経緯と数値は {@code docs/ENGINE_BENCHMARK.md} を参照。
 *
 * @param repeatedLines    最も長く連続した同一行の連続回数（1 なら連続なし）
 * @param repeatedText     その同一行の内容。連続が無ければ空文字列
 * @param charsPerSecond   音声 1 秒あたりの文字数。音声長が 0 なら {@link Double#NaN}
 * @param empty            セグメントが 1 つも無かったか
 */
public record TranscriptQualityCheck(int repeatedLines, String repeatedText, double charsPerSecond, boolean empty) {

	/**
	 * 検査する。
	 *
	 * <p>行は {@code part_*.txt} に書き出すものと同じ（1 行 1 セグメント、空行は除去済み）を渡す。
	 *
	 * @param lines                   文字起こし結果の行。空でも可
	 * @param audioMs                 入力音声の長さ（ミリ秒）。0 以下なら {@link #charsPerSecond()} は NaN になる
	 * @param minRepeatedLineLength   この文字数以下の行の連続は数えない（0 以下ですべて数える）。
	 *                                「はい。」のような相槌が自然に何度も続くのを崩壊と誤判定しないため
	 * @return 検査結果
	 */
	public static TranscriptQualityCheck of(List<String> lines, long audioMs, int minRepeatedLineLength) {
		int longestRun = lines.isEmpty() ? 0 : 1;
		String longestText = "";
		int run = 1;
		for (int i = 1; i < lines.size(); i++) {
			run = lines.get(i).equals(lines.get(i - 1)) ? run + 1 : 1;
			// 短い行の連続は相槌なので数えない。長さの根拠は minRepeatedLineLength の説明を参照
			if (run > longestRun && lines.get(i).length() > minRepeatedLineLength) {
				longestRun = run;
				longestText = lines.get(i);
			}
		}

		int characters = lines.stream().mapToInt(String::length).sum();
		double perSecond = audioMs > 0L ? characters * 1000.0 / audioMs : Double.NaN;

		return new TranscriptQualityCheck(longestRun, longestText, perSecond, lines.isEmpty());
	}

	/**
	 * 人が確認した方がよい状態かとうかを返す。
	 *
	 * @param repetitionWarnLines    同一行がこの回数以上連続したら警告する
	 * @param minCharsPerAudioSecond 音声 1 秒あたりの文字数がこれを下回ったら警告する
	 * @return 警告すべきなら true
	 */
	public boolean suspicious(int repetitionWarnLines, float minCharsPerAudioSecond) {
		return this.empty || this.repetitionExceeded(repetitionWarnLines) || this.tooFewCharacters(minCharsPerAudioSecond);
	}

	/** 同一行の連続が しきい値以上か。しきい値が 0 以下ならこの指標は無効。 */
	private boolean repetitionExceeded(int repetitionWarnLines) {
		return repetitionWarnLines > 0 && this.repeatedLines >= repetitionWarnLines;
	}

	/** 音声長あたりの文字数がしきい値未満か。しきい値が 0 以下、または音声長が不明ならこの指標は無効。 */
	private boolean tooFewCharacters(float minCharsPerAudioSecond) {
		return minCharsPerAudioSecond > 0.0f
				&& !Double.isNaN(this.charsPerSecond)
				&& this.charsPerSecond < minCharsPerAudioSecond;
	}

	/**
	 * 警告としてログに出す 1 行を組み立てる。何が変だったのかと、次に何をすればよいかを書く。
	 *
	 * @param repetitionWarnLines    {@link #suspicious(int, float)} に渡したしきい値
	 * @param minCharsPerAudioSecond 同上
	 * @return 警告メッセージ。{@link #suspicious(int, float)} が false のときの内容は保証しない
	 */
	public String describe(int repetitionWarnLines, float minCharsPerAudioSecond) {
		if (this.empty) {
			return "文字起こし結果が空です";
		}

		StringBuilder message = new StringBuilder();
		if (this.repetitionExceeded(repetitionWarnLines)) {
			message.append(String.format(Locale.ROOT, "同じ行が %d 行連続しています（「%s」）", this.repeatedLines,
					abbreviate(this.repeatedText)));
		}
		if (this.tooFewCharacters(minCharsPerAudioSecond)) {
			if (!message.isEmpty()) {
				message.append(" / ");
			}
			message.append(String.format(Locale.ROOT, "音声 1 秒あたり %.2f 文字しかありません（目安 %.2f 以上）",
					this.charsPerSecond, minCharsPerAudioSecond));
		}
		return message.toString();
	}

	private static String abbreviate(String text) {
		return text.length() <= 20 ? text : text.substring(0, 20) + "…";
	}
}
