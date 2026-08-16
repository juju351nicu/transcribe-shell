package jp.clip.transcribeshell.service;

import java.nio.file.Path;

/**
 * 1ファイルの文字起こし処理の結果。
 *
 * <p>一括処理（transcribe-all）が「処理／スキップ」を判定できるよう、結合ファイルのパスに加えて
 * 「今回どれだけ新規作業をしたか」を持つ。{@link #anyWorkDone()} が false なら、分割も文字起こしも
 * すべて済みで新規処理が無かった＝スキップ相当。
 *
 * @param mergedFile      結合結果（{@code <base>_all.txt}）のパス
 * @param splitExecuted   今回 ffmpeg 分割を実行したら true（既存でスキップなら false）
 * @param transcribedParts 今回新規に文字起こしした part 数（既存 .txt でスキップした分は含まない）
 */
public record TranscribeResult(Path mergedFile, boolean splitExecuted, int transcribedParts) {

	/** 今回このファイルで新規作業（分割 or 文字起こし）が発生したか。false ならスキップ相当。 */
	public boolean anyWorkDone() {
		return splitExecuted || transcribedParts > 0;
	}
}
