package jp.clip.transcribeshell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TranscriptMergeService} の単体テスト。
 *
 * <p>外部プロセスは不要。一時ディレクトリに part_*.txt を作り、
 * 結合の順序・UTF-8・part 間の区切り空行を検証する。
 */
class TranscriptMergeServiceTest {

	private final TranscriptMergeService service = new TranscriptMergeService();

	@Test
	void 名前昇順で結合し_part間に空行を入れUTF8で書き出す(@TempDir Path dir) throws IOException {
		// わざと逆順に作成し、内容ソートではなくファイル名昇順で結合されることを確認する。
		Files.writeString(dir.resolve("part_001.txt"), "世界\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("part_000.txt"), "こんにちは\n", StandardCharsets.UTF_8);

		Path merged = service.merge(dir, "sample_001");

		assertThat(merged).isEqualTo(dir.resolve("sample_001_all.txt"));
		String content = Files.readString(merged, StandardCharsets.UTF_8);
		// part_000 → part_001 の順、間に空行、末尾は改行1つ。
		assertThat(content).isEqualTo("こんにちは\n\n世界\n");
	}

	@Test
	void 各partの末尾改行を正規化してから空行区切りにする(@TempDir Path dir) throws IOException {
		// 末尾に余分な改行があっても区切りは空行1つに正規化される。
		Files.writeString(dir.resolve("part_000.txt"), "あ\n\n\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("part_001.txt"), "い", StandardCharsets.UTF_8);

		Path merged = service.merge(dir, "x");

		assertThat(Files.readString(merged, StandardCharsets.UTF_8)).isEqualTo("あ\n\nい\n");
	}

	/**
	 * {@code part_<数字>.txt} 以外は結合対象にしない。
	 *
	 * <p>2026-09-15 に、VAD の有無を比べるため手で退避した {@code part_000_vadoff.txt} が結合され、
	 * 同じ内容が二重に入った結合ファイルができた。出力フォルダは利用者が覗く場所なので、
	 * 比較用のコピーが置かれることは普通に起きる。名前の完全一致で弾く。
	 */
	@Test
	void part_連番_txt以外は結合対象にしない(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("part_000.txt"), "本体\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("part_000_vadoff.txt"), "退避\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("part_000.txt.bak"), "退避\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("part_backup.txt"), "退避\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("parts_000.txt"), "別物\n", StandardCharsets.UTF_8);

		Path merged = service.merge(dir, "x");

		assertThat(Files.readString(merged, StandardCharsets.UTF_8)).isEqualTo("本体\n");
	}

	@Test
	void 結合対象が無ければ例外(@TempDir Path dir) {	@Test
	void 結合対象が無ければ例外(@TempDir Path dir) {
		assertThatThrownBy(() -> service.merge(dir, "x"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("part_*.txt");
	}
}
