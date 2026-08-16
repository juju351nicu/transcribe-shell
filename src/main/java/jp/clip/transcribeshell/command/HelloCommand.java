package jp.clip.transcribeshell.command;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

@Component
public class HelloCommand {

	@Command(name = "hello", description = "Spring Shellの起動確認を行います")
	public String hello() {
		return "Spring Shell is running.";
	}
}
