package jp.clip.transcribeshell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import jp.clip.transcribeshell.config.OsProfile;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

	/**
	 * アプリを起動する。
	 *
	 * <p>OS ごとに違う既定値（録音フォルダ・Python の起動子・文字起こしエンジン）を
	 * プロファイルで切り替えるため、{@code os.name} から判定した追加プロファイルを渡す。
	 * Windows では何も追加されないので、従来どおり {@code TranscribeProperties} の
	 * フィールド初期値がそのまま効く。
	 *
	 * @param args コマンドライン引数（Spring Shell のサブコマンドを含む）
	 */
	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(Application.class);
		application.setAdditionalProfiles(OsProfile.from(System.getProperty("os.name")).additionalProfiles());
		application.run(args);
	}

}
