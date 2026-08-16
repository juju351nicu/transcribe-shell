package jp.clip.transcribeshell.process;

/**
 * 外部プロセスの起動そのものに失敗したことを表す例外。
 *
 * <p>実行ファイル（ffmpeg / py など）が PATH に無い、といったケースで送出される。
 * Whisper が非ゼロ終了した「実行の失敗」とは区別する。
 */
public class ProcessStartException extends RuntimeException {

	private final String executable;

	public ProcessStartException(String executable, Throwable cause) {
		super("プロセスの起動に失敗しました: '" + executable + "'（PATH が通っているか確認してください）", cause);
		this.executable = executable;
	}

	/** 起動に失敗した実行ファイル名。 */
	public String getExecutable() {
		return executable;
	}
}
