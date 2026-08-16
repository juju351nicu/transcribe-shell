package jp.clip.transcribeshell.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * {@link ProcessBuilder} の薄いラッパ。
 *
 * <p>子プロセスの stdout/stderr を統合して 1 行ずつ親の標準出力へ即時転送し、
 * 終了コードを返す。長時間かかる Whisper 処理でも進捗がリアルタイムに見えるようにする。
 * ワイルドカード展開はシェルに頼らないため、コマンドは配列（{@link List}）で渡す。
 */
@Component
public class ProcessRunner {

	/**
	 * 環境変数を追加せずにコマンドを実行する。{@link #run(List, Path, Map)} に空マップを渡すのと同じ。
	 *
	 * @param command    実行するコマンドと引数（シェルを介さない配列）
	 * @param workingDir 作業ディレクトリ
	 * @return 子プロセスの終了コード
	 * @throws ProcessStartException コマンドの起動自体に失敗した場合（実行ファイルが PATH に無い等）
	 * @throws UncheckedIOException  実行中の入出力に失敗した場合
	 */
	public int run(List<String> command, Path workingDir) {
		return run(command, workingDir, Map.of());
	}

	/**
	 * 環境変数を追加してコマンドを実行し、終了コードを返す。
	 *
	 * <p>{@code env} は親プロセスの環境に上書きマージされる（例: whisper に {@code PYTHONUTF8=1} を渡して
	 * 標準出力を UTF-8 で出させ、本クラスの UTF-8 読み取りと一致させる）。
	 *
	 * @param command    実行するコマンドと引数（シェルを介さない配列）
	 * @param workingDir 作業ディレクトリ
	 * @param env        追加する環境変数（キー=値）。空マップなら追加なし
	 * @return 子プロセスの終了コード
	 * @throws ProcessStartException コマンドの起動自体に失敗した場合（実行ファイルが PATH に無い等）
	 * @throws UncheckedIOException  実行中の入出力に失敗した場合
	 */
	public int run(List<String> command, Path workingDir, Map<String, String> env) {
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workingDir.toFile());
		pb.environment().putAll(env);
		pb.redirectErrorStream(true);

		Process process;
		try {
			process = pb.start();
		} catch (IOException e) {
			// 実行ファイルが見つからない等、起動そのものの失敗。呼び出し側で分かりやすく扱う。
			throw new ProcessStartException(command.isEmpty() ? "" : command.get(0), e);
		}

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				System.out.println(line);
			}
			return process.waitFor();
		} catch (IOException e) {
			process.destroy();
			throw new UncheckedIOException("子プロセスの出力読み取りに失敗しました: " + command, e);
		} catch (InterruptedException e) {
			process.destroy();
			Thread.currentThread().interrupt();
			throw new RuntimeException("子プロセスの待機が中断されました: " + command, e);
		}
	}
}
