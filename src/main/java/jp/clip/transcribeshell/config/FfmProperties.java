package jp.clip.transcribeshell.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code transcribe-cpp} コマンド（whisper.cpp を FFM で JVM 内から直接呼ぶ）の設定。
 *
 * <p>whisper.cpp を使う経路はこのアプリに 2 つある。混同しないよう設定プレフィックスで分けている。
 * <ul>
 * <li>{@code transcribe.whisper.cpp.*} … {@code transcribe --engine cpp}。外部バイナリ {@code whisper-cli} を起動する</li>
 * <li>{@code transcribe.ffm.*}（このクラス） … {@code transcribe-cpp}。jar 同梱のネイティブを JVM 内から呼ぶ</li>
 * </ul>
 *
 * <p>Windows は同梱の DLL がそのまま使えるため追加インストールが不要。Mac / Linux は
 * whisper.cpp のライブラリを自分でビルドして jar に同梱するまで {@code transcribe-cpp} は使えないので、
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
	 * VAD（無音区間の除去）を使うか。処理時間は半分以下になるが、whisper.cpp の VAD は発話区間を
	 * 繋ぎ合わせてから認識するため繋ぎ目付近の文をまるごと落とすことがある（2026-09-06 の実測で
	 * faster-whisper より文字数が 3 割少なく、余白やしきい値の調整でも戻らなかった）。
	 * 議事録用途では網羅性を優先し既定 false。速さが要るときだけ true にする。
	 */
	private boolean vad = false;

	/** VAD が「発話」と判定する確度のしきい値（0〜1）。whisper.cpp の既定は 0.5。下げると小声も拾うが雑音も拾う。 */
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
	 * 参加者名のヒントを使うなら、ここは既定のままにして {@link #carryInitialPrompt} を true にする。
	 */
	private int maxTextContext = 16384;

	/**
	 * 初期プロンプトを毎ウィンドウの先頭に付け直すか（{@code carry_initial_prompt}）。whisper.cpp の既定は false。
	 *
	 * <p>true にすると引き継ぎバッファが今回のウィンドウの出力だけになり、初期プロンプトは静的な別枠として
	 * 毎ウィンドウ前置される。固有名詞のヒントを音声全体に効かせたまま繰り返しの伝播を短く抑えられる。
	 * 未実測のため既定は whisper.cpp と同じ false。試すときは {@code -Dtranscribe.ffm.carry-initial-prompt=true}。
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
