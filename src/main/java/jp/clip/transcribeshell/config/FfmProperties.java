package jp.clip.transcribeshell.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code transcribe-ffm} コマンド（whisper.cpp を FFM で JVM 内から直接呼ぶ）の設定。
 *
 * <p>whisper.cpp を使う経路はこのアプリに 2 つある。混同しないよう設定プレフィックスで分けている。
 * <ul>
 * <li>{@code transcribe.whisper.cpp.*} … {@code transcribe --engine cpp}。外部バイナリ {@code whisper-cli} を起動する</li>
 * <li>{@code transcribe.ffm.*}（このクラス） … {@code transcribe-ffm}。jar 同梱のネイティブを JVM 内から呼ぶ</li>
 * </ul>
 *
 * <p>Windows は同梱の DLL がそのまま使えるため追加インストールが不要。Mac / Linux は
 * whisper.cpp のライブラリを自分でビルドして jar に同梱するまで {@code transcribe-ffm} は使えないので、
 * それらの OS では {@code transcribe --engine cpp}（whisper-cli）を使う。
 *
 * <p>既定値の方針: <b>ライブラリ（whisper-ffm）の既定は whisper.cpp と同じ値</b>で、
 * このアプリの用途（日本語の会議録音）に合わせた判断はここで明示する。根拠は README の
 * 「デコーダの調整」と whisper-ffm の {@code docs/plan-ffm-v2.md}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "transcribe.ffm")
public class FfmProperties {

	/** ggml モデルの置き場所。ユーザー名に依存しないようホーム基準にする。 */
	private String modelDir = Path.of(System.getProperty("user.home"), "whisper-models").toString();

	/** モデル名からファイル名を組み立てる型。{@code {model}} が名前に置き換わる。whisper-cli 側と同じ規約。 */
	private String modelFilePattern = "ggml-{model}.bin";

	/**
	 * ネイティブライブラリのディレクトリ。未指定なら whisper-ffm の jar に同梱されたものを使う。
	 * GPU（Vulkan）版など別のバイナリを試すときに、その置き場所を指定する。
	 */
	private String nativeLibraryDir;

	/** 使用スレッド数。0 なら whisper.cpp の既定（4）。{@code -t} で上書きできる。 */
	private int threads = 0;

	/**
	 * VAD（無音区間の除去）を使うか。既定 false。
	 *
	 * <p><b>両刃である。</b>2026-09-08〜09 に同一音声で {@code vad} だけを振った 6 パートの実測:
	 *
	 * <ul>
	 * <li>崩壊していた 3 パートを<b>直した</b>（同一行 55 / 14 / 60 行連続 → 1 行）</li>
	 * <li>正常な 2 パートへの影響は小さかった（−0.4%、−6.5%）</li>
	 * <li>正常な 1 パートを<b>壊した</b>（音声 1 秒あたり 4.02 → 0.67 文字。−83%）</li>
	 * </ul>
	 *
	 * <p><b>どちらに転ぶかを事前に予測する方法は見つかっていない。</b>無音率では判定できず
	 * （23.2% で崩壊、0.2% でも崩壊、3.8% では VAD が壊した）、平均音量とは相関するが境界が引けず
	 * （−23〜−26 dB は無害、−29.9 dB で −83%）、{@link #vadThreshold} を 0.5 → 0.2 に下げても
	 * −74% のままだった。「音が小さいとしきい値を超えないので落ちる」という説明は成り立たない。
	 *
	 * <p>そのため既定は false のままにし、<b>品質チェックが警告を出した part だけ手で
	 * {@code --vad} を試し、悪化したら戻す</b>という運用にしている。この手順は
	 * {@link jp.clip.transcribeshell.service.TranscriptQualityCheck} が両方向を見ているから成立する
	 * （VAD off の崩壊は同一行の連続、VAD on の崩壊は音声長あたりの文字数で捕まる）。
	 * 数値と否定された仮説の一覧は {@code docs/ENGINE_BENCHMARK.md} を参照。
	 */
	private boolean vad = false;

	/**
	 * 同じ行がこの回数以上連続したら警告する（0 以下で無効）。
	 *
	 * <p>幻聴のループは例外を出さず「done」と表示されて終わるため、気づけないまま議事録が壊れる。
	 * 2026-09-08〜09 に 3 録音 8 part を測った結果、崩壊した part は 16 / 55 / 67 行連続、
	 * 崩壊していない part の最大は 3 行だった。3 と 16 の間にデータが無いので、
	 * 両側に余裕のある 5 を既定にしている。
	 *
	 * <p><b>3 では誤検知した。</b>普通の発話文が 3 行続いただけの part を拾ってしまった
	 * （相槌の「はい。」が 2 行続くのも普通に起きる）。
	 *
	 * <p><b>この指標がないと見逃す崩壊がある。</b>67 行連続した part は短い 1 文の繰り返しだったため
	 * 総文字数が減らず、{@link #minCharsPerAudioSecond} では 5.07 文字/秒（正常値）に見えていた。
	 */
	private int repetitionWarnLines = 5;

	/**
	 * 音声 1 秒あたりの文字数がこれを下回ったら警告する（0 以下で無効）。
	 *
	 * <p>{@link #repetitionWarnLines} を補う 2 つ目の指標。会話が幻聴 1 種類だけに置き換わった part は
	 * 1.01 で、正常な part は 3.39〜5.63 だった（2026-09-08〜09、3 録音 8 part）。2.5 なら両側に余裕がある。
	 *
	 * <p>ただし<b>これだけでは崩壊を捕まえられない</b>。短い 1 文が 67 行続いた part は総文字数が減らず
	 * 5.07 文字/秒だった。連続同一行の指標と併用する前提の補助的な指標である。
	 * 沈黙が非常に長い録音では正常でも下回りうる。
	 */
	private float minCharsPerAudioSecond = 2.5f;

	/**
	 * VAD が「発話」と判定する確度のしきい値（0〜1）。whisper.cpp の既定は 0.5。下げると小声も拾うが雑音も拾う。
	 *
	 * <p><b>VAD が発話を落とす問題の対策にはならなかった。</b>平均音量 −29.9 dB の part で VAD on が
	 * −83% になったため 0.2 まで下げてみたが、404 → 621 文字（VAD off は 2,410 文字）で −74% のまま。
	 * 原因はしきい値ではなく、区間の切り出しか結合の側にあると思われる（未調査）。
	 */
	private float vadThreshold = 0.5f;

	/** これより短い発話区間は捨てる（ミリ秒）。whisper.cpp の既定は 250。 */
	private int vadMinSpeechDurationMs = 250;

	/** これより短い無音は発話の切れ目と見なさない（ミリ秒）。whisper.cpp の既定は 100。 */
	private int vadMinSilenceDurationMs = 100;

	/** 1 つの発話区間の最大長（秒）。既定は無制限。 */
	private float vadMaxSpeechDurationSeconds = Float.MAX_VALUE;

	/** 発話区間の前後に足す余白（ミリ秒）。whisper.cpp の既定は 30。大きくすると取り漏らしは減り、処理時間は少し増える。 */
	private int vadSpeechPadMs = 30;

	/** 隣接する発話区間を結合するときの重なり（秒）。whisper.cpp の既定は 0.1。 */
	private float vadSamplesOverlap = 0.1f;

	/** beam search を使うか。false なら greedy。{@code --beam-search} で回ごとに指定できる。 */
	private boolean beamSearch = false;

	/**
	 * 非発話トークンを抑制するか。ライブラリ（= whisper.cpp）の既定は false。
	 *
	 * <p>抑制されるのは whisper.cpp が持つ固定の記号リスト（{@code " # ( ) [ ] 「 」 『 』 ♪ ♫} 等）だけで
	 * <b>{@code 【} {@code 】} は含まれない</b>。「【アイテム】」のような字幕由来のハルシネーションはこの設定では止まらない
	 * （それは {@link #maxTextContext} / {@link #carryInitialPrompt} の領域）。
	 * それでも議事録として読む用途では記号の注記が邪魔なので、このアプリでは true を既定にする。
	 */
	private boolean suppressNonSpeechTokens = true;

	/**
	 * 温度フォールバック時に引き直す候補の本数（{@code greedy.best_of}）。whisper.cpp の既定は 5。
	 *
	 * <p>2026-09-07 の実測（sample-b / sample-c）では、5 にしても処理時間は同じで文字数はむしろ減り、
	 * 繰り返しループの抑制効果も確認できなかった。実測に基づき -1（= 候補 1 本）を既定にする。
	 */
	private int bestOf = -1;

	/** beam search の beam 幅（{@code beam_search.beam_size}）。whisper.cpp の既定は 5 だが、速度優先で 2 を既定にする。 */
	private int beamSize = 2;

	/** 温度フォールバックの刻み（{@code temperature_inc}）。whisper.cpp の既定は 0.2 だが、実測で 0.4 の方が良かった。 */
	private float temperatureIncrement = 0.4f;

	/** フォールバック判定のエントロピー閾値（{@code entropy_thold}）。whisper.cpp と同じ 2.4。 */
	private float entropyThreshold = 2.4f;

	/**
	 * 直前までのテキストをプロンプトとして何トークンまで使うか（{@code n_max_text_ctx}）。whisper.cpp の既定は 16384。
	 *
	 * <p>whisper.cpp は 30 秒ウィンドウごとに前の出力を次のプロンプトへ引き継ぐため、これが繰り返しループの
	 * 伝播経路になる。0 にすると引き継ぎは止まるが、<b>{@link #initialPrompt} も無効になる</b>
	 * （whisper.cpp のプロンプト構築が {@code if (n_max_text_ctx > 0)} の中にあるため）。
	 * 参加者名のヒントを使うなら、ここは既定のままにする（{@link #carryInitialPrompt} は実測の結果
	 * 不採用。理由はそちらの Javadoc を参照）。
	 */
	private int maxTextContext = 16384;

	/**
	 * 初期プロンプトを毎ウィンドウの先頭に付け直すか（{@code carry_initial_prompt}）。whisper.cpp の既定は false。
	 *
	 * <p>true にすると引き継ぎバッファが今回のウィンドウの出力だけになり、初期プロンプトは静的な別枠として
	 * 毎ウィンドウ前置される。
	 *
	 * <p><b>2026-09-08 に実測して不採用にした。</b>固有名詞は確かに良くなった（姓A 2→7 件、姓B 0→1 件）が、
	 * 毎ウィンドウ前置される初期プロンプトそのものが出力に混ざり、同じ行が 42 回繰り返された。
	 * そのため既定は whisper.cpp と同じ false のまま。固有名詞だけを狙うなら
	 * {@link #initialPrompt} を 10 名前後に絞る方が効く。
	 * 経緯と数値は {@code docs/ENGINE_BENCHMARK.md} を参照。
	 */
	private boolean carryInitialPrompt = false;

	/**
	 * 初期プロンプト。{@code --prompt} が未指定のときに使う。空なら {@link #initialPromptFile} を見る。
	 *
	 * <p>{@code .properties} は ISO-8859-1 で読まれるため日本語を直接書くと化ける。日本語は
	 * {@link #initialPromptFile} 側に置く。
	 */
	private String initialPrompt;

	/**
	 * 初期プロンプトを書いた UTF-8 テキストファイルのパス。{@code #} で始まる行はコメント、残りの行は連結する。
	 * ファイルが無ければ警告を出してプロンプト無しで続ける。
	 *
	 * <p>参加者名を書くファイルなので、<b>リポジトリには置かずホーム直下に置く</b>のが既定
	 * （公開リポジトリに同僚の氏名が入るのを避けるため）。書き方の例は {@code docs/whisper-prompt.sample.txt}。
	 */
	private String initialPromptFile = Path.of(System.getProperty("user.home"), "whisper-prompt.txt").toString();
}
