package jp.clip.transcribeshell.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IC レコーダーのファイル名の先頭 6 桁（{@code YYMMDD}）を {@code yyyy-MM-dd} に展開する。
 *
 * <p>結合ファイル名を人が読んで日付が分かる形にするために使う。
 *
 * <pre>
 * 260910_1539  →  2026-09-10_1539
 * 260619_003   →  2026-06-19_003
 * 260910       →  2026-09-10
 * meeting      →  meeting          （日付として読めないものはそのまま）
 * </pre>
 *
 * <p><b>日付として読めない名前はそのまま返す。</b>先頭 6 桁が数字でない、区切りが {@code _} や {@code -}
 * ではない、月日が実在しない（{@code 261340}、{@code 260230} など）といった場合は変換しない。
 * 変換できないことをエラーにはしない。ファイル名の付け方は利用者の自由で、アプリが決めることではないため。
 *
 * <p><b>世紀は {@code 20} 固定。</b>2 桁年から世紀は決められないが、このアプリが扱うのは手元の録音なので
 * {@code 20YY} で足りる。1900 年代の録音を扱うことになったら、ここを見直す。
 *
 * <p><b>出力フォルダ名には使っていない。</b>フォルダは {@code transcribe-ffm_<元のファイル名>} のままで、
 * 変換するのは中の結合ファイル名だけ。フォルダ名を変えると、既存の出力フォルダが認識されなくなり
 * 「再実行してもスキップされる」という冪等性が壊れるため。
 */
public final class DatedBaseName {

	/** {@code YYMMDD} + 任意の「区切り + 残り」。区切りは {@code _} か {@code -}。 */
	private static final Pattern RECORDER_NAME = Pattern.compile("^(\\d{2})(\\d{2})(\\d{2})(?:([_-])(.+))?$");

	private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	/** 2 桁年に足す世紀。 */
	private static final int CENTURY = 2000;

	private DatedBaseName() {
	}

	/**
	 * 先頭の {@code YYMMDD} を {@code yyyy-MM-dd} に置き換えた名前を返す。
	 *
	 * @param base 拡張子を除いたファイル名（例 {@code 260910_1539}）。null 可
	 * @return 変換後の名前。日付として読めなければ引数をそのまま返す
	 */
	public static String of(String base) {
		if (base == null) {
			return null;
		}
		Matcher matcher = RECORDER_NAME.matcher(base);
		if (!matcher.matches()) {
			return base;
		}

		LocalDate date = parseDate(matcher.group(1), matcher.group(2), matcher.group(3));
		if (date == null) {
			return base;
		}

		String separator = matcher.group(4);
		String rest = matcher.group(5);
		if (rest == null) {
			return date.format(ISO_DATE);
		}
		return date.format(ISO_DATE) + separator + rest;
	}

	/**
	 * 2 桁ずつの年月日を日付にする。実在しない日付なら null を返す。
	 */
	private static LocalDate parseDate(String year, String month, String day) {
		try {
			return LocalDate.of(CENTURY + Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
		} catch (DateTimeException e) {
			// 261340（13 月）や 260230（2 月 30 日）など。変換せず元の名前を使う
			return null;
		}
	}
}
