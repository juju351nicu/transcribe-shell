package jp.clip.transcribeshell.service;

/**
 * whisper.cpp のネイティブライブラリを読み込めなかったことを表す例外。
 *
 * <p>「音声 1 件の処理に失敗した」のではなく「この環境ではそもそも FFM 経路が使えない」という区別を
 * 型で表すために用意した。一括コマンド（{@code transcribe-ffm-all}）は、これを受けたら残りのファイルを
 * 試さずに打ち切る。原因が環境なので、続けても全件同じ理由で失敗するだけで、ログが読みづらくなる。
 *
 * <p><b>なぜ {@link IllegalStateException} を継承するのか。</b>
 * {@code transcribe-ffm} 側は既に {@code IllegalStateException} を「実行時の失敗」として捕まえて
 * メッセージを表示している。そこに新しい catch 節を足さずに済むよう、その部分型にしてある。
 *
 * <p><b>なぜ {@code UnsatisfiedLinkError} をそのまま流さないのか。</b>
 * {@code UnsatisfiedLinkError} は {@link Error} であって {@link Exception} ではないため、
 * 「1 件失敗しても次へ進む」ための {@code catch(Exception)} では捕まらず、バッチが突き抜けて止まる。
 * ライブラリの入口（{@link FfmWhisperService}）で例外に変換しておくことで、上位は
 * {@code Exception} だけを見ればよくなる。
 */
public class NativeUnavailableException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	/** 利用者向けの案内。原因の詳細はこの後ろに付ける。 */
	static final String GUIDANCE = "whisper.cpp のネイティブライブラリを読み込めません。"
			+ "\n  この OS / アーキテクチャ用のライブラリが whisper-ffm の jar に同梱されていない可能性があります。"
			+ "\n  transcribe の engine=cpp（外部の whisper-cli）を使うか、"
			+ "whisper-ffm をこの OS でビルドして publishToMavenLocal し直してください。";

	/**
	 * @param cause ネイティブの読み込み失敗（{@code UnsatisfiedLinkError} やそれを包んだ例外）
	 */
	public NativeUnavailableException(Throwable cause) {
		super(GUIDANCE + "\n  詳細: " + cause.getMessage(), cause);
	}
}
