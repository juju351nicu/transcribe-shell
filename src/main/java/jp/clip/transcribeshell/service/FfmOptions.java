package jp.clip.transcribeshell.service;

import lombok.Builder;

/**
 * {@code transcribe-cpp} コマンドのオプション一式。
 *
 * <p>record なので値で等価比較でき、コマンドのテストで「解析結果がそのまま渡ったか」を 1 つの {@code eq} で検証できる。
 * 要素が 4 個を超えるため {@link Builder} を付けてある。位置引数の羅列
 * （{@code new FfmOptions("small", "Japanese", 600, null, false, 0, null, null, null)}）では、
 * 半年後に末尾の null が何なのか読めないため。{@code toBuilder = true} にしてあるので、
 * テストで「1 項目だけ変えた別のオプション」を作れる。
 *
 * <p>{@code threads} が 0、{@code vad} / {@code beamSearch} / {@code prompt} が null のときは
 * {@code transcribe.ffm.*} の設定値を使う（{@link FfmWhisperService} で解決）。
 * {@code vad} と {@code beamSearch} を {@code Boolean} にしているのは、
 * 「未指定（設定に従う）」と「明示的に false」を区別するため。
 *
 * @param model       モデル名またはモデルファイルのパス
 * @param language    言語（{@code Japanese} / {@code ja} / {@code auto}）
 * @param segmentTime 分割秒数
 * @param outputDir   出力フォルダ。null/空なら入力と同階層に {@code transcribe-cpp_<base>} を生成
 * @param force       true なら文字起こし済み part も再実行
 * @param threads     スレッド数。0 なら設定 {@code transcribe.ffm.threads}
 * @param vad         VAD を使うか。null なら設定 {@code transcribe.ffm.vad}
 * @param beamSearch  beam search を使うか。null なら設定 {@code transcribe.ffm.beam-search}
 * @param prompt      初期プロンプト。null/空なら設定 {@code transcribe.ffm.initial-prompt(-file)}
 */
@Builder(toBuilder = true)
public record FfmOptions(String model, String language, int segmentTime, String outputDir, boolean force,
		int threads, Boolean vad, Boolean beamSearch, String prompt) {
}
