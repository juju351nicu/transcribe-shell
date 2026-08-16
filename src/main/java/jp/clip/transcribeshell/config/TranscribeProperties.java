package jp.clip.transcribeshell.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * transcribe コマンドの既定値と外部実行ファイルのパスを外部化する。
 *
 * <p>application.properties で {@code transcribe.*} として上書きできる。
 * 例: {@code transcribe.ffmpeg-path=C:\\tools\\ffmpeg.exe}
 *
 * <p>アクセサは Lombok（{@code @Getter}/{@code @Setter}）で生成する。{@code @ConfigurationProperties} は
 * setter 経由でバインドするため、生成された public アクセサでそのまま動作する。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "transcribe")
public class TranscribeProperties {

	/** ffmpeg 実行ファイル。既定は PATH 上の "ffmpeg"。 */
	private String ffmpegPath = "ffmpeg";

	/** Python ランチャ。既定は PATH 上の "py"（{@code py -m whisper} で起動）。 */
	private String pyPath = "py";

	/** Whisper モデル既定値。 */
	private String defaultModel = "small";

	/** 言語既定値。 */
	private String defaultLanguage = "Japanese";

	/** 分割秒数の既定値（600 = 10分）。 */
	private int defaultSegmentTime = 600;

	/**
	 * transcribe-all の既定走査フォルダ。
	 *
	 * <p>OS ごとに異なる録音フォルダをコードに直書きしないよう外部化する（Windows/Mac/Linux で値を変えるだけで済む）。
	 * 既定は現在のユーザーホーム直下の Music。{@code --dir} で個別に上書きできる。
	 */
	private String defaultDir = Path.of(System.getProperty("user.home"), "Music").toString();

	/** 文字起こしエンジンの設定（{@code transcribe.whisper.*}）。 */
	private Whisper whisper = new Whisper();

	/**
	 * 文字起こしエンジンの設定。
	 *
	 * <p>{@code engine} で faster-whisper 系（whisper-ctranslate2）・従来の openai-whisper・
	 * whisper.cpp（whisper-cli）を切り替える。コードを変えずに application.properties だけで
	 * 選べるようにするための外部化。
	 */
	@Getter
	@Setter
	public static class Whisper {

		/**
		 * 使用エンジン。{@code faster}（whisper-ctranslate2）/ {@code openai}（py -m whisper）/
		 * {@code cpp}（whisper-cli）。既定は faster。
		 */
		private String engine = "faster";

		/** faster エンジンの実行ファイル。既定は PATH 上の "whisper-ctranslate2"。 */
		private String command = "whisper-ctranslate2";

		/** faster エンジンの計算精度。CPU では {@code int8} が高速。openai エンジンでは未使用。 */
		private String computeType = "int8";

		/** faster エンジンの出力形式。結合には txt しか使わないため既定 txt（補助形式を作らず高速化）。 */
		private String outputFormat = "txt";

		/**
		 * 文字起こしをスキップする part の最小バイト数。0 なら無効（すべての part を処理する）。
		 *
		 * <p>録音長が {@code --segment-time} の倍数に近いと、分割の端数として長さがほぼ 0 の part
		 * （数KB）ができる。これを whisper に渡すとエンジンによってはデコードに失敗し、
		 * 夜間の {@code transcribe-all} が止まって手作業の復旧が必要になる。
		 * そうした端数を警告付きでスキップして先へ進めるための閾値。
		 *
		 * <p>既定 16384（16KB）は、192kbps の MP3 で 1 秒に満たない長さに相当する。
		 */
		private long minPartBytes = 16384;

		/** cpp エンジン（whisper.cpp）固有の設定（{@code transcribe.whisper.cpp.*}）。 */
		private Cpp cpp = new Cpp();
	}

	/**
	 * cpp エンジン（whisper.cpp の whisper-cli）固有の設定。
	 *
	 * <p>他エンジンと違い、モデルを「名前」ではなく <b>ggml の .bin ファイルパス</b>で渡す必要があるため、
	 * 「モデル置き場 + 命名規則」で {@code --model small} から実ファイルを解決する項目を持つ。
	 * Python を使わないので {@code py-path} は無関係。
	 */
	@Getter
	@Setter
	public static class Cpp {

		/**
		 * whisper.cpp の実行ファイル。既定は PATH 上の "whisper-cli"。
		 *
		 * <p>Windows の公式 zip は {@code Release\whisper-cli.exe} という階層で展開されるため、
		 * PATH を通していない場合はフルパスを指定する。
		 */
		private String command = "whisper-cli";

		/**
		 * ggml モデル（.bin）の置き場。既定は「ユーザーホーム直下の whisper-models」。
		 *
		 * <p>Windows / Mac / Ubuntu のどれでも同じ既定値が使えるようホーム基準にしている
		 * （OS ごとのプロファイル分けが不要）。
		 */
		private String modelDir = Path.of(System.getProperty("user.home"), "whisper-models").toString();

		/**
		 * モデルファイルの命名規則。{@code {model}} が {@code --model} の値に置換される。
		 *
		 * <p>量子化モデルで比較したい場合は {@code --model small-q5_1} と渡せば
		 * {@code ggml-small-q5_1.bin} が解決されるので、この設定を変えずに済む。
		 */
		private String modelFilePattern = "ggml-{model}.bin";

		/**
		 * 言語の上書き。空なら {@code --language} の値をそのまま {@code -l} に渡す。
		 *
		 * <p>whisper-cli は {@code -l} の値を内部で小文字化して言語名でも引くため、既定の
		 * {@code Japanese} がそのまま通ることを実機確認済み。よってここは既定で空にし、
		 * ISO コード（{@code ja}）を強制したい場合だけ設定する。
		 */
		private String language = "";

		/** 計算スレッド数。0 なら {@code -t} を渡さず whisper-cli の既定（min(4, CPU数)）に任せる。 */
		private int threads = 0;

		/**
		 * 直前のテキストを文脈として何トークン引き継ぐか（{@code -mc}）。負値なら {@code -mc} を渡さない。
		 *
		 * <p><b>既定を 0（引き継がない）にしている理由</b>: whisper-cli の既定（-1 = 無制限）だと、
		 * 日本語の会議録音で同じ文を延々と繰り返す幻覚ループが多発する。実測では 4.5 分の part で
		 * 「(音楽)」が16回続いて出力が 403 バイトしか出ず（重複行 78.9%）、{@code -mc 0} を足すだけで
		 * 5,039 バイト・重複行 4.1% まで改善した（faster-whisper と同等の品質）。
		 *
		 * <p>faster-whisper には繰り返しを検出して再生成する {@code compression_ratio_threshold} があるが
		 * whisper.cpp には無いため、文脈の引き継ぎを切るのが実質的な対策になる。
		 * 引き継ぎを復活させたい場合は -1（whisper-cli の既定に任せる）や正の値を設定する。
		 */
		private int maxContext = 0;

		/**
		 * 分割 part の音声形式（{@code mp3} または {@code wav}）。既定は mp3。
		 *
		 * <p>whisper-cli 1.9.2 は mp3 を直接読めるため既定は mp3（既存の作業フォルダとそのまま互換）。
		 * mp3 を読めない古いビルドに当たった場合だけ {@code wav} にする。
		 */
		private String inputFormat = "mp3";
	}
}
