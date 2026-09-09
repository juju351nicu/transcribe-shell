# transcribe-shell

MP3 音声ファイルを分割し、Whisper で文字起こしして 1 つのテキストに結合する CLI ツール（Spring Shell 製）。

PowerShell で手作業していた「FFmpeg で 10 分ごとに分割 → Whisper で各パートを文字起こし → 結合」という
ワークフローを、`transcribe` コマンド 1 つで冪等（再実行しても安全）に実行できるようにしたもの。

## 前提

以下がローカルにインストール済みで、**PATH が通っている**こと。

- **Java 25**（`java` コマンド。ビルドは同梱の `mvnw` を使うので別途 Maven は不要）
- **FFmpeg**（`ffmpeg` コマンド）
- **文字起こしエンジン**（下表のいずれか。既定は faster）

| エンジン | 既定 | Python | 必要なもの | 導入 |
| --- | --- | --- | --- | --- |
| **faster**（whisper-ctranslate2） | ○ | 要 | `whisper-ctranslate2` コマンド（faster-whisper ベース、CPU で高速） | `pip install whisper-ctranslate2` |
| **openai**（従来） | | 要 | `py -m whisper` が起動できること（openai-whisper） | `pip install -U openai-whisper` |
| **cpp**（whisper.cpp） | | **不要** | `whisper-cli` コマンドと ggml モデル（.bin） | Windows: 公式 zip / Mac・Ubuntu: `brew install whisper-cpp` |

- 既定は **faster** エンジン。CPU のままでも openai-whisper より高速・省メモリ。
- **Python を入れたくない PC（Mac / Ubuntu のノートなど）では `cpp` を使います。** 必要なものは Java・FFmpeg・
  `whisper-cli`・モデルファイルだけで、Python は一切要りません。
- エンジンは `application.properties` で切り替えられます（[エンジンの切り替え](#エンジンの切り替え)）。導入済みの方を選べば OK。
- 具体的な導入手順は [前提ツールのインストール（Windows）](#前提ツールのインストールwindows) を参照。

> 実行ファイルのパスは設定で上書きできます（後述）。

> **要約機能（任意）を使う場合のみ**、別途 Ollama とモデルが必要です（[要約機能の準備](#要約機能の準備ollama任意)）。
> 文字起こし（`transcribe` / `transcribe-all`）には Ollama は不要で、要約を使わなければ動作に一切影響しません。

### 動作確認済み環境（参考）

開発環境で以下を確認済み（`ffmpeg -version` / `py -m whisper --help`）。バージョンは参考値。

| ツール | 確認したバージョン / 事実 |
| --- | --- |
| FFmpeg | `8.1.1-full_build`（gyan.dev, Windows）。`--enable-whisper` 込みだが、本アプリは FFmpeg 内蔵 Whisper は使わず外部の文字起こし CLI を別プロセスで呼ぶ |
| faster（whisper-ctranslate2） | `0.5.7`（faster-whisper 1.2.1 / ctranslate2 4.8.1）。`--model/--language/--output_dir/--output_format/--compute_type/--device` を確認。`--output_format txt` で `part_000.txt` のみ出力されることを実行確認済み |
| openai（py -m whisper） | `py -m whisper` で起動可。`--language` は言語名（`Japanese`）でも ISO コード（`ja`）でも指定可 |
| cpp（whisper-cli） | `whisper.cpp version: 1.9.2`（公式 `whisper-bin-x64.zip`）。ヘルプに `supported audio formats: flac, mp3, ogg, wav` と明記されており、**MP3 を直接読める**（内部で 16kHz・モノラルへ自動変換）。出力 txt は **BOM なし UTF-8**（日本語も正常）で、`-c copy` で分割した `part_*.mp3` をそのまま処理できることを実機確認済み |

**エンジン別の挙動メモ**（本アプリの動作に影響）:

- **faster**: 既定で `--output_format txt` と `--compute_type int8` を付与。出力は `part_000.txt` のみ（補助形式を作らないので速い）。
- **openai**: 従来どおり `--output_format` を付けないため、各パートで全形式（txt / vtt / srt / tsv / json）が生成される。結合は `.txt` のみ読むので動作に支障はないが、補助形式のファイルも残る。
- **cpp**: モデルを「名前」ではなく **ggml の .bin ファイルパス**で渡す仕様のため、`--model small` を
  `<model-dir>/ggml-small.bin` に読み替えて起動する。`-of` に拡張子なしのベース名を渡すので、出力は
  他エンジンと同じ `part_000.txt` になる（渡さないと `part_000.mp3.txt` になってしまう）。
  `-l` は whisper-cli 側で小文字化されて言語名でも解決されるため、既定の `Japanese` がそのまま通る。
- **cpp の注意**: whisper-cli は言語指定が不正だと **終了コード 0 のまま何も出力せずに終わる**ため、
  本アプリは終了コードに加えて `part_*.txt` が実際に作られたかを確認し、無ければエラーにする。
- **分割の端数（極小 part）**: 録音長が `--segment-time` の倍数に近いと、末尾に長さがほぼ 0 の part
  （数KB）ができることがある。これを渡すとエンジンによってはデコードに失敗するため、
  **`transcribe.whisper.min-part-bytes`（既定 16384）未満の part は警告を出してスキップ**し、
  残りの処理を続行する（1つの端数で夜間の `transcribe-all` 全体が止まらないようにするため）。
  全 part を必ず処理したい場合は `transcribe.whisper.min-part-bytes=0` で無効化できる。
- どのエンジンでも `--model small` 等をアプリが明示的に渡すため、素の CLI の既定モデルには依存しない。
- 進捗表示の文字化け対策として、子プロセスに `PYTHONUTF8=1` を渡している（出力ファイルは元々 UTF-8 なので内容には影響しない）。
- 文字起こし完了時に **エンジン名・モデル・経過時間**をログに出す（エンジンごとの速度を実測で比較するため）。

## 前提ツールのインストール（Windows）

本アプリは **FFmpeg** と **文字起こし CLI** を外部プロセスとして呼びます。以下を導入し、いずれも **PATH を通して**ください。

### 1. FFmpeg

パッケージマネージャがあれば簡単です（いずれか）。

```powershell
winget install Gyan.FFmpeg
# または: choco install ffmpeg
# または: scoop install ffmpeg
```

手動で入れる場合は [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) 等の full build を展開し、`bin` フォルダ
（`ffmpeg.exe` がある場所）を PATH に追加します。確認:

```powershell
ffmpeg -version
```

### 2. Python（`py` ランチャ）

文字起こし CLI は Python 製です。[python.org](https://www.python.org/downloads/) からインストールし、
**「Add python.exe to PATH」にチェック**を入れてください。確認:

```powershell
py --version
py -m pip --version
```

### 3. 文字起こしエンジン（どちらか。既定は faster）

**faster（既定・推奨）— whisper-ctranslate2**

```powershell
pip install whisper-ctranslate2
```

確認（このアプリと同様、日本語ヘルプは cp932 で落ちるので `PYTHONUTF8=1` を付ける）:

```powershell
$env:PYTHONUTF8=1; whisper-ctranslate2 --help
```

**openai（従来）— openai-whisper**（faster を使うなら不要）

```powershell
pip install -U openai-whisper
py -m whisper --help
```

- モデル本体（`small` 等）は**初回実行時に自動ダウンロード**されるので、事前取得は不要です。
- どちらのエンジンを使うかは `application.properties` の `transcribe.whisper.engine` で切り替えます
  （[エンジンの切り替え](#エンジンの切り替え)）。
- `pip` が見つからない場合は `py -m pip install ...` の形でも実行できます。

> Mac / Linux でも考え方は同じです（FFmpeg は `brew install ffmpeg` / `apt install ffmpeg`、
> Python の起動子は `py` の代わりに `python3`）。その場合は `transcribe.py-path=python3` を設定してください。

### 4. cpp エンジン（whisper.cpp）— Python を使わない場合

**Python を入れずに文字起こししたい PC ではこれを使います。** 必要なのは `whisper-cli` 実行ファイルと
ggml モデル（.bin）の 2 つだけです。

**Windows（公式のビルド済み zip。ビルド作業は不要）**

```powershell
# 1. 取得して展開（zip の中身は Release\ サブフォルダに入っている点に注意）
New-Item -ItemType Directory -Force C:\tools\whisper.cpp | Out-Null
Invoke-WebRequest -Uri "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/whisper-bin-x64.zip" -OutFile "$env:TEMP\whisper-bin-x64.zip"
Expand-Archive "$env:TEMP\whisper-bin-x64.zip" -DestinationPath C:\tools\whisper.cpp -Force

# 2. 確認（whisper.cpp version: 1.9.2 と出れば OK）
C:\tools\whisper.cpp\Release\whisper-cli.exe --version
```

`C:\tools\whisper.cpp\Release` を PATH に通すか、通さない場合は設定でフルパスを指定します。

```properties
transcribe.whisper.cpp.command=C:\\tools\\whisper.cpp\\Release\\whisper-cli.exe
```

> `whisper-bin-x64.zip`（CPU 版・約 8MB）のほか、`whisper-blas-bin-x64.zip`（約 20MB）もあります。
> 速度差は環境によるので、気になる場合は両方で実測してください。

**Mac / Ubuntu**

```bash
brew install whisper-cpp   # whisper-cli が入る
whisper-cli --version
```

> Intel Mac の場合、Homebrew のビルド済みボトルは Sonoma 版までです。Sequoia 以降ではソースからの
> ビルドが走ります（cmake で数分かかりますが失敗しにくい）。Ubuntu は brew のほか、公式リリースの
> `whisper-bin-ubuntu-x64.tar.gz` を展開しても使えます。

**ggml モデルの入手（必須）**

Windows の zip にも Homebrew にも**モデルは含まれません**。[Hugging Face](https://huggingface.co/ggerganov/whisper.cpp)
から `.bin` を取得し、モデル置き場（既定は**ユーザーホーム直下の `whisper-models`**）に置きます。

```powershell
# Windows
New-Item -ItemType Directory -Force "$env:USERPROFILE\whisper-models" | Out-Null
Invoke-WebRequest -Uri "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin" -OutFile "$env:USERPROFILE\whisper-models\ggml-small.bin"
```

```bash
# Mac / Ubuntu
mkdir -p ~/whisper-models
curl -L -o ~/whisper-models/ggml-small.bin "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
```

| モデル | サイズ | 用途 |
| --- | --- | --- |
| `ggml-tiny.bin` | 74MB | 疎通確認・動作テスト用 |
| `ggml-small.bin` | 465MB | **通常はこれ**（`--model small` に対応） |
| `ggml-small-q5_1.bin` | 181MB | 量子化版。軽いが精度は落ちる場合あり（`--model small-q5_1` で使える） |
| `ggml-medium.bin` | 1.4GB | 精度重視 |

- ファイル名は `ggml-<モデル名>.bin` の規則で解決されます（`transcribe.whisper.cpp.model-file-pattern`）。
  そのため `--model small-q5_1` と渡せば `ggml-small-q5_1.bin` が使われます。
- モデルが見つからない場合は、**探したパスとダウンロード用コマンドを含むエラーメッセージ**が表示されます。

## Mac / Ubuntu でのセットアップ

**Python は不要です。** Mac（Intel 想定）と Ubuntu（x86_64 想定）では、OS を自動判定して
`engine=cpp`（whisper.cpp）と録音フォルダ `~/Music` が既定になります。**設定ファイルの編集は要りません。**

### 1. JDK 25 を入れる

```bash
# Mac の例（Homebrew の cask）
brew install --cask temurin@25

# Ubuntu の例（SDKMAN）
curl -s "https://get.sdkman.io" | bash && source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem
```

```bash
java -version   # 25 が表示されることを確認
```

### 2. FFmpeg と whisper-cli を入れる

```bash
# Mac
brew install ffmpeg whisper-cpp

# Ubuntu（ffmpeg は apt、whisper-cli は Homebrew か公式リリースの tar.gz）
sudo apt install ffmpeg
brew install whisper-cpp
```

```bash
ffmpeg -version
whisper-cli --version   # whisper.cpp version: 1.9.2 など
```

> Intel Mac の場合、Homebrew のビルド済みボトルは Sonoma 版までです。Sequoia 以降ではソースから
> ビルドが走ります（cmake で数分。失敗しにくい）。
> Ubuntu で brew を使いたくない場合は、[公式リリース](https://github.com/ggml-org/whisper.cpp/releases)の
> `whisper-bin-ubuntu-x64.tar.gz` を展開し、`whisper-cli` に PATH を通してください。

### 3. ggml モデルを置く

```bash
mkdir -p ~/whisper-models
curl -L -o ~/whisper-models/ggml-small.bin \
  "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
```

置き場の既定は **`~/whisper-models`** なので、ここに置けば設定は不要です
（モデルの種類とサイズは [cpp エンジン（whisper.cpp）](#4-cpp-エンジンwhispercpp--python-を使わない場合) の表を参照）。

### 4. jar を作る

```bash
chmod +x mvnw          # USB(exFAT) 経由で持ってきた場合は必須（実行ビットが落ちるため）
./mvnw -DskipTests package
```

### 5. 実行

```bash
chmod +x transcribe.sh transcribe-all.sh
./transcribe.sh "/path/to/xxx.mp3"
./transcribe.sh "/path/to/xxx.mp3" -m base --force
./transcribe-all.sh                       # ~/Music 直下の未処理 MP3 をまとめて処理
./transcribe-all.sh -d "/path/to/folder"
```

- `.sh` は**どこから呼んでも動きます**（スクリプト自身の位置から jar を探します）。
- スペースを含むパスもそのまま渡せます。
- jar が無い場合は、パッケージ用のコマンドを添えたエラーを表示して終了します。

### OS ごとの既定値（自動で切り替わるもの）

| 設定 | Windows（既定） | Mac / Ubuntu |
| --- | --- | --- |
| `transcribe.whisper.engine` | `faster` | **`cpp`**（Python 不要） |
| `transcribe.whisper.cpp.max-context` | `0` | `0`（明示。幻覚ループ対策） |
| `transcribe.default-dir` | `%USERPROFILE%\Music` | `~/Music` |
| `transcribe.py-path` | `py` | `python3` |

`os.name` から `application-mac.properties` / `application-linux.properties` が自動で有効になります
（Windows では何も追加されないため、**従来の挙動は一切変わりません**）。個別に変えたいときは
実行時に上書きできます。

```bash
./transcribe.sh "/path/to/xxx.mp3"   # 既定（cpp）
java "-Dspring.shell.interactive.enabled=false" "-Dtranscribe.whisper.engine=faster" \
  -jar target/transcribe-shell-0.0.1-SNAPSHOT.jar transcribe "/path/to/xxx.mp3"
```

> **改行コードの注意**: `.sh` は必ず **LF** で保存してください（CRLF だと `bad interpreter` エラーになります）。
> `.gitattributes` に `*.sh text eol=lf` を指定済みなので、Git 経由なら自動で LF になります。

## 他のPCで使う場合（ポータビリティ）

本体は Java 製なので、別のPC（Windows / Mac / Linux）でも動きます。必要なものは用途で分かれます。

- **文字起こしだけ使う**: そのPCに **Java 25 / FFmpeg / 文字起こしエンジン** を入れれば、今までどおり
  `transcribe` / `transcribe-all` が動きます。**Ollama は不要**です。
  - **Python を入れたくない PC では `cpp` エンジン**（whisper.cpp）を使います。必要なのは
    `whisper-cli` と ggml モデルだけで、Windows は公式のビルド済み zip、Mac / Ubuntu は
    `brew install whisper-cpp` で入ります（**各 OS でのビルド作業は不要**）。
- **要約機能も使う**: 追加で **Ollama とモデル** を入れます（[要約機能の準備](#要約機能の準備ollama任意)）。
  要約を使いたいPCにだけ入れればよく、入れていないPCでは要約を使わなければよいだけです。

要するに **Ollama は要約専用の任意要素**で、文字起こしのポータビリティには関係しません。
Mac / Linux では Python 起動子が `py` でなく `python3` なので、openai エンジン利用時は
`transcribe.py-path=python3` を設定します（faster エンジンはコマンド名 `whisper-ctranslate2` で共通）。
cpp エンジンなら Python 自体が不要なので、この設定も要りません。

## ビルド

```powershell
./mvnw clean package
```

## 起動方法

### 1. 対話モード（通常はこちら）

```powershell
./mvnw spring-boot:run
```

起動後、シェルプロンプトでコマンドを入力します。

```text
transcribe --file "C:\Users\<ユーザー>\Music\sample_001.MP3"
```

### 2. ワンショット実行（自動化・スクリプト向け）

対話シェルを立ち上げず、コマンドを 1 回だけ実行して終了します。
このアプリは既定で対話モードになるため、`spring.shell.interactive.enabled=false` を付けて非対話実行します。

```powershell
java "-Dspring.shell.interactive.enabled=false" -jar target/transcribe-shell-0.0.1-SNAPSHOT.jar transcribe "C:\Users\<ユーザー>\Music\sample_001.MP3"
```

> この長いコマンドを毎回打たなくて済むよう、ランチャ用バッチ（`transcribe.bat`）を同梱しています。
> 普段の手動運用はそちらが手軽です → [かんたん起動](#かんたん起動バッチ--ドラッグドロップ--path-登録)。

## 使い方

入力ファイルは **先頭に直接（位置引数）** でも **`-f`/`--file`** でも指定できます。

```text
transcribe "C:\...\sample_001.MP3"               # 位置引数（先頭に直接パス）※おすすめ
transcribe --file "C:\...\sample_001.MP3"        # -f/--file オプションでも可
transcribe "C:\...\sample_001.MP3" -m base        # モデルを base に変更
transcribe "C:\...\sample_001.MP3" --force        # 文字起こし済みパートも再実行
help transcribe                                    # オプション一覧
```

- 位置引数と `-f/--file` の**両方**を指定するとエラーになります（どちらか一方）。
- どちらも指定しないと「ファイルを指定してください」を表示します。
- **ドラッグ＆ドロップ**: 位置引数対応により、MP3 を実行用バッチ（`transcribe.bat` 等）のアイコンにドロップすると、
  そのパスが先頭引数として渡り文字起こしが走ります（バッチ例は下記「[かんたん起動](#かんたん起動バッチ--ドラッグドロップ--path-登録)」）。

### オプション

| オプション | 短縮 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| （位置引数） | | | なし | 先頭に直接書く入力 MP3 の絶対パス（`-f` の代わり） |
| `--file` | `-f` | | なし | 入力 MP3 の絶対パス（位置引数と一方のみ） |
| `--model` | `-m` | | `small` | Whisper モデル（tiny/base/small/medium/large） |
| `--language` | `-l` | | `Japanese` | 言語 |
| `--segment-time` | | | `600` | 分割秒数（600 = 10 分） |
| `--output-dir` | `-o` | | 入力と同じ階層の `transcribe_<base>` | 出力フォルダ |
| `--force` | | | `false` | 文字起こし済みパートも再実行する |

- `<base>` は拡張子を除いたファイル名（例: `sample_001`）。結合結果は `<base>_all.txt`（UTF-8 / BOM なし）。
- `--output-dir` を**指定した場合はそのパスを出力フォルダそのものとして使い**、配下に `transcribe_<base>` は作りません。
  省略時のみ入力ファイルと同じ階層に `transcribe_<base>` を自動生成します。

## フォルダ一括処理（transcribe-all）

フォルダ**直下**の未処理 MP3 をまとめて文字起こしします。冪等性があるので、毎回フォルダ全体を対象にしても
**新しい録音だけ**が実処理されます（毎晩の自動実行に向く）。

```text
transcribe-all                               # 既定フォルダ（transcribe.default-dir）を走査
transcribe-all -d "C:\Users\<ユーザー>\Music"   # フォルダ指定
transcribe-all -d "C:\...\Music" -m base --force  # モデル指定・全パート再実行
help transcribe-all
```

- 走査は**直下のみ（非再帰）**。`*.mp3`/`*.MP3` を大小無視で収集し、**名前昇順**で処理します。
- 出力フォルダ `transcribe_*`（ディレクトリ）や分割済みの `part_*.mp3` は自動的に対象外。
- 各ファイルの出力は既存仕様どおり、そのファイルと同階層の `transcribe_<base>` に作られます。
- **1ファイルが失敗してもバッチは止まらず**、失敗をログに残して次へ進みます。
- 完了時に `処理: n件 / スキップ: m件 / 失敗: k件` を表示します（**スキップ** = 分割も文字起こしも全部済みで新規処理が無かったファイル）。

### transcribe-all のオプション

| オプション | 短縮 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `--dir` | `-d` | | 設定 `transcribe.default-dir` | 走査するフォルダ（直下のみ） |
| `--model` | `-m` | | `small` | Whisper モデル |
| `--language` | `-l` | | `Japanese` | 言語 |
| `--segment-time` | | | `600` | 分割秒数 |
| `--force` | | | `false` | 文字起こし済みパートも再実行する |

`--dir` の既定値は設定 `transcribe.default-dir` で変更できます
（OS ごとに録音フォルダが異なるため、コードに直書きせず設定で持たせています）。

## かんたん起動（バッチ / シェルスクリプト / ドラッグ＆ドロップ / PATH 登録）

毎回 `java -jar ...` と打たなくて済むよう、ランチャを同梱しています。位置引数対応により
**MP3 をバッチにドラッグ＆ドロップするだけ**でも文字起こしが走ります。

| OS | ランチャ | 用途 |
| --- | --- | --- |
| Windows | `transcribe.bat` / `transcribe-all.bat` | ドラッグ＆ドロップ対応 |
| Mac / Linux | `transcribe.sh` / `transcribe-all.sh` | 端末から実行（[セットアップ](#mac--ubuntu-でのセットアップ)） |

いずれも **jar をスクリプト自身の位置から解決**するので、フォルダごとコピーしても、どこから呼んでも
そのフォルダの jar が起動します。

### 0. 事前にパッケージ化（初回・コード変更時のみ）

```powershell
./mvnw -DskipTests package
```
`target/transcribe-shell-0.0.1-SNAPSHOT.jar` が作られます。

### 1. transcribe.bat（リポジトリ直下に同梱済み）

1ファイル用のランチャです。**jar のパスは `%~dp0`（バッチ自身のフォルダ）から解決**するので、
環境に合わせた書き換えは不要です。

```bat
@echo off
rem transcribe launcher: run  transcribe "path\to.mp3"  (or drag a .mp3 onto this file).
rem The literal "transcribe" subcommand is required; %* forwards the dropped/typed path.
setlocal
set "JAR=%~dp0target\transcribe-shell-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" (
  echo jar not found: %JAR%
  echo Run "mvnw.cmd -DskipTests package" in %~dp0 first.
  pause
  exit /b 1
)
java "-Dspring.shell.interactive.enabled=false" -jar "%JAR%" transcribe %*
pause
endlocal
```

- **重要**: jar に渡す引数は必ず `transcribe`（サブコマンド）で始めること。これが無いと
  Spring Shell が「コマンド未検出（CommandNotFoundException）」になります。
- **ドラッグ＆ドロップ**: MP3 を `transcribe.bat` のアイコンにドロップすると、そのパスが先頭引数（位置引数）
  として渡り処理が始まります。`pause` があるので、完了後もウィンドウは開いたまま結果を確認できます。
- コマンドラインからは `transcribe.bat "C:\...\x.MP3" -m base` のようにオプションも足せます。

使用例（このバッチ経由）:

```powershell
.\transcribe "C:\Users\<ユーザー>\Music\sample_001.MP3"
```

### 2. transcribe-all.bat（フォルダ一括・同梱済み）

フォルダをまとめて処理する版です（サブコマンドが `transcribe-all` になっているだけ）。

```bat
@echo off
setlocal
set "JAR=%~dp0target\transcribe-shell-0.0.1-SNAPSHOT.jar"
java "-Dspring.shell.interactive.enabled=false" -jar "%JAR%" transcribe-all %*
pause
endlocal
```

### 3. PATH に登録してどこからでも実行

`transcribe.bat` を置いたフォルダを PATH に追加すると、任意の場所で `transcribe "C:\...\x.MP3"` と打てます。

```powershell
# 例: バッチを C:\work\bin に置いた場合（現在のユーザー PATH に追記）
setx PATH "$($env:PATH);C:\work\bin"
```

> - `setx` の反映には**新しいターミナルの起動**が必要です。
> - `java` コマンドにも PATH が通っている必要があります（前提参照）。

### 4. 初回実行の注意（モデルの自動ダウンロード）

faster エンジンは初回実行時にモデル（既定 `small`）を自動ダウンロードします
（`C:\Users\<ユーザー>\.cache\huggingface` にキャッシュ。数百MB・**初回のみ**）。
`HF Hub ...` や `symlinks ...` の警告が出ますが**無視して問題ありません**。2回目以降はダウンロードをスキップします。

## 出力フォルダの構成

```text
transcribe_sample_001/
├── part_000.mp3        # FFmpeg で分割
├── part_000.txt        # Whisper の文字起こし結果
├── part_001.mp3
├── part_001.txt
├── ...
└── sample_001_all.txt  # 全パートを結合した最終成果物
```

## 動作確認（小さいMP3で試す）

初めて使うときや環境確認のための手動テスト手順。発話入りの短い MP3 があればそれが最適ですが、
無ければ FFmpeg で無音の短い MP3 を生成しても、分割 → 文字起こし → 結合の一連の流れを確認できます。

### 1. 前提コマンドの確認

```powershell
ffmpeg -version
py -m whisper --help
```

### 2. テスト用の短いMP3を作成（任意）

無音 25 秒の MP3 を生成します（分割を試すため後で `--segment-time` を小さく指定します）。

```powershell
ffmpeg -f lavfi -i anullsrc=r=16000:cl=mono -t 25 -q:a 9 "C:\work\sample.mp3"
```

> 無音だと文字起こし結果はほぼ空になりますが、処理フローとファイル生成は確認できます。

### 3. 実行（対話モード）

```powershell
./mvnw spring-boot:run
```

シェル起動後、10 秒ごとに分割・最速モデルで動かします（25 秒の音声が 3 パートに分かれます）。

```text
transcribe --file "C:\work\sample.mp3" --segment-time 10 --model tiny
```

- `--model tiny` … 最速。動作確認向け。
- `--segment-time 10` … 複数パートの処理が確認できる。

### 4. 実行（ワンショット）

```powershell
./mvnw -DskipTests package
java "-Dspring.shell.interactive.enabled=false" -jar target/transcribe-shell-0.0.1-SNAPSHOT.jar transcribe -f "C:\work\sample.mp3" --segment-time 10 -m tiny
```

### 5. 結果の確認

```powershell
dir "C:\work\transcribe_sample"
type "C:\work\transcribe_sample\sample_all.txt"
```

生成物: `part_000.mp3`〜 と各 `.txt`、および結合済みの `sample_all.txt`。

### 6. 冪等性の確認

同じコマンドをもう一度実行 → `分割: 既存のためスキップ` と各パートの `skip (txtあり)` が表示されれば正常。
再実行させたい場合は `--force` を付けます。

### 7. 途中失敗からのやり直し

分割が途中で止まった等でおかしくなったら、出力フォルダを消して再実行します。

```powershell
Remove-Item -Recurse -Force "C:\work\transcribe_sample"
```

### hello コマンド（開発用）

シェルの起動確認だけしたいときは `hello` を実行します（`Spring Shell is running.` と表示）。

```text
hello
```

## 冪等性（再実行しても安全）

同じコマンドを再実行した場合、完了済みの処理はスキップされます。

- `part_000.mp3` が既にあれば **分割をスキップ**。
- 各パートに対応する `.txt` が既にあれば **文字起こしをスキップ**（`--force` を付けると再実行）。

> **途中で失敗したときは、出力フォルダ（`transcribe_<base>`）を丸ごと削除してから再実行してください。**
> 分割が途中で中断すると `part_000.mp3` だけが残り、以降が欠けたまま「分割済み」と判定されるためです
> （現行の PowerShell 運用と同じ割り切りです）。

## エンジンの切り替え

文字起こしエンジンは `application.properties`（`src/main/resources/`）だけで切り替えられます。**コード変更は不要**です。

### faster（whisper-ctranslate2）を使う（既定）

```properties
transcribe.whisper.engine=faster
```

導入していない場合:

```powershell
pip install whisper-ctranslate2
```

初回実行時にモデル（`small` など）が自動ダウンロードされます。CPU では `--compute_type int8`（既定）が高速です。

### openai（従来の py -m whisper）に戻す

whisper-ctranslate2 を入れていない・従来挙動に戻したい場合:

```properties
transcribe.whisper.engine=openai
```

### cpp（whisper.cpp / Python 不要）を使う

```properties
transcribe.whisper.engine=cpp
```

導入は [cpp エンジン（whisper.cpp）](#4-cpp-エンジンwhispercpp--python-を使わない場合) を参照。
設定ファイルを書き換えずに 1 回だけ試すこともできます。

```powershell
java "-Dspring.shell.interactive.enabled=false" "-Dtranscribe.whisper.engine=cpp" -jar target/transcribe-shell-0.0.1-SNAPSHOT.jar transcribe "C:\Users\<ユーザー>\Music\sample_001.MP3"
```

### どのエンジンを選ぶか（実測）

実際の会議録音（34.5分 / `--model small` / 同一 CPU / 4 part）で計測した結果です。

| | faster (int8) | cpp（`-mc 0` 込み・既定） | cpp（whisper-cli 素の既定） |
| --- | --- | --- | --- |
| 所要時間 | **13分7秒** | 13分37秒 | 15分2秒 |
| 文字数 | 10,982 | 11,198 | 12,192 |
| **圧縮比**（低いほど自然） | **3.18** | **3.21** | 4.54 |
| 行内の繰り返し | 0 行 | 1 行（全体の1.5%） | 1 行 + 行単位の重複38% |
| Python | 必要 | **不要** | 不要 |

- **速度・品質とも faster と cpp はほぼ互角**です（圧縮比 3.18 対 3.21）。
- Python が使える PC なら **faster のままで問題ありません**。Python を入れたくない PC では
  **cpp で実用水準**に達します。
- cpp は素の既定のままだと幻覚ループで品質が大きく崩れるため、本アプリは `-mc 0` を既定で渡しています
  （下記 `max-context` を参照）。

> 測定環境・素材・実際の出力の比較・チューニングの経緯（1変数ずつの検証）まで含めた詳細は
> **[docs/ENGINE_BENCHMARK.md](docs/ENGINE_BENCHMARK.md)** を参照してください。
> Mac / Ubuntu で同じ手順を回せば、そのまま比較できるように書いてあります。

## whisper.cpp を JVM 内で呼ぶ `transcribe-ffm`（FFM）

whisper.cpp を使う経路はこのアプリに **2 つ**あります。名前が似ているので、まずここで区別してください。

| | `transcribe --engine cpp` | `transcribe-ffm` |
| --- | --- | --- |
| 呼び方 | 外部バイナリ `whisper-cli` を起動 | JVM 内から FFM（Panama）で直接呼ぶ |
| 必要なもの | ffmpeg + whisper-cli + モデル | ffmpeg + モデル（Windows はこれだけ） |
| ライブラリ | なし | [whisper-ffm](https://github.com/juju351nicu/whisper-ffm)（`jp.clip:whisper-ffm`） |
| 設定 | `transcribe.whisper.cpp.*` | `transcribe.ffm.*` |
| 分割 part | 設定次第（mp3 / wav） | 常に wav（16kHz モノラル） |
| 出力フォルダ | `transcribe_<base>` | `transcribe-ffm_<base>` |
| 対応 OS | whisper-cli を入れた OS すべて | 現状 Windows のみ（下記） |

> **旧名 `transcribe-cpp` について。** どちらも中身は whisper.cpp なので、`transcribe-cpp` と
> `transcribe --engine cpp` が別物であることが名前から分からず紛らわしかったため `transcribe-ffm` に
> 改名しました。旧名は `@Command(alias = ...)` で残してあるので `transcribe-cpp` でも動きます。
> ただし**出力フォルダの既定は `transcribe-ffm_<base>` に変わりました**。改名前に作った
> `transcribe-cpp_*` フォルダは認識されず作り直しになるので、残しておきたい場合は先に改名してください。
>
> ```powershell
> Get-ChildItem "C:\Users\<user>\Music" -Directory -Filter 'transcribe-cpp_*' |
>     Rename-Item -NewName { $_.Name -replace '^transcribe-cpp_', 'transcribe-ffm_' }
> ```

**使い分けの目安**: Windows では `transcribe-ffm` が追加インストール不要で速い（i5-1335U / small で
faster-whisper の約 2.3〜3.5 倍）。Mac / Linux では whisper-ffm の jar に同梱されているネイティブが
Windows 用だけなので、`transcribe --engine cpp`（whisper-cli）を使ってください。OS プロファイルで
Mac / Linux は `engine=cpp` が既定になっています。

### 準備（whisper-ffm をローカル Maven に入れる）

Maven Central には公開していないため、**各マシンで一度だけ**ローカルリポジトリに入れます。

```bash
git clone https://github.com/juju351nicu/whisper-ffm
cd whisper-ffm
./gradlew installNatives publishToMavenLocal      # Windows は .\gradlew.bat
```

Windows 以外では `installNatives` 用のネイティブが無くても jar は作れます（ビルドの依存解決だけ通ります）。
その状態で `transcribe-ffm` を実行すると起動時に失敗するので、その OS では `transcribe` を使ってください。

### 使い方

```powershell
# transcribe と同じ使い方。出力は入力と同階層の transcribe-ffm_<base>\<base>_all.txt
.\transcribe-ffm.bat "C:\Users\<user>\Music\sample_001.MP3"

# フォルダ一括（transcribe-all の FFM 版）
.\transcribe-ffm-all.bat -d "C:\Users\<user>\Music"
```

Mac / Linux では `./transcribe-ffm.sh` / `./transcribe-ffm-all.sh` です（ネイティブを用意した場合）。

| オプション | 短縮 | 既定 | 説明 |
| --- | --- | --- | --- |
| （位置引数） / `--file` | `-f` | — | 入力 MP3 |
| `--model` | `-m` | `small` | ggml モデル名（`transcribe.ffm.model-dir` の `ggml-<名前>.bin`）またはパス |
| `--language` | `-l` | `Japanese` | `Japanese` / `ja` / `auto` |
| `--segment-time` | | `600` | 分割秒数 |
| `--output-dir` | `-o` | `transcribe-ffm_<base>` | 出力フォルダ |
| `--force` | | `false` | 済み part も再実行 |
| `--threads` | `-t` | `0` → `transcribe.ffm.threads` | スレッド数 |
| `--vad` | | 設定 `transcribe.ffm.vad`（既定 false） | `--vad` で有効、`--vad false` で無効。省略時は設定に従う |
| `--beam-search` | | 設定 `transcribe.ffm.beam-search`（既定 false） | 同上。beam search は遅い |
| `--prompt` | `-p` | 設定 `transcribe.ffm.initial-prompt(-file)` | 初期プロンプト（固有名詞のヒント） |

### 初期プロンプト（固有名詞の取り違え対策）

whisper は「直前に話されていた文字列」を初期プロンプトとして受け取り、その語彙・文体に寄った出力をします。
参加者名の取り違えは、モデルサイズよりこのプロンプトの方が効きます（実測: 姓C 4→7 回、姓D 0→5 回、
姓E 2→3 回が正しく出た）。

> 実測値は実際の会議録音で取っているため、**録音のファイル名は `sample-a` 等に、参加者の姓は
> 「姓A」〜「姓E」に匿名化**してあります（同じ記号は同じ人物。数値は実測値そのままです）。ただし人数が多いと 1 人あたりの効きが薄まるので、**その会議に出る人だけ、
10 人前後**に絞ります（43 名全員を入れた版は成績が落ちました）。

参加者名を書くファイルなので、リポジトリには置かず**ホーム直下**に置くのが既定です
（`~/whisper-prompt.txt`）。書き方とサンプルは **[docs/whisper-prompt.sample.txt](docs/whisper-prompt.sample.txt)**。

### 繰り返しループ対策（`transcribe.ffm.max-text-context` / `carry-initial-prompt`）

whisper.cpp は 30 秒ウィンドウごとに前の出力を次のプロンプトへ引き継ぐため、これが繰り返しループの
伝播経路になります。`transcribe --engine cpp` 側は `-mc 0` で引き継ぎを切っていますが、
**FFM 側で同じことをすると初期プロンプトも無効になります**（whisper.cpp のプロンプト構築が
`if (n_max_text_ctx > 0)` の中にあるため）。参加者名のヒントを使っているので、この手は取れません。

代わりに `transcribe.ffm.carry-initial-prompt=true`（初期プロンプトを毎ウィンドウ前置し、引き継ぎを
今回のウィンドウの出力だけに限定する）を試しましたが、**採用しませんでした**。実測結果は下記のとおりです。

| 条件（sample-c / 12分38秒 / small / VAD off / プロンプトあり） | 文字数 | 行数 | 時間 | 3行以上の繰り返し | 姓A / 姓B |
| --- | --- | --- | --- | --- | --- |
| 既定 | 3,542 | 93 | 257秒 | 0 行 | 2 / 0 |
| `carry-initial-prompt=true` | 4,713 | 166 | 271秒 | **42 行** | **7 / 1** |

固有名詞には効きました（姓A 2→7 回、姓B 0→1 回）。しかし**初期プロンプト自身が出力に漏れ出し**、
初期プロンプトを言い換えたような 1 行が 42 行連続しました。文字数が 33% 増えたのは中身ではなくこの水増しです。
プロンプトの影響力を上げる機構であって、上げすぎると復唱を招く、というのが結論です。
**繰り返しループの対策には使えません。** 既定（false）のままにしてください。

未検証の中間案として、プロンプトを「〜です。」という文ではなく名前の羅列だけにする、極端に短くする、
といった方向があります。試す場合は必ず同一音声で A/B を取ってください。

### 崩壊した part は自動で警告されます

幻聴のループは例外を出さないので、`完了:` と表示されて終わります。気づけないのが一番困るため、
part を書き出すたびに 2 つの指標を数え、超えたら WARN を出します（**`.txt` は必ず書き出します**。
直すかどうかは人が決める形にしてあります）。

| 指標 | 既定 | 設定キー |
| --- | --- | --- |
| 同一行が連続した回数 | 5 行以上で警告 | `transcribe.ffm.repetition-warn-lines` |
| 音声 1 秒あたりの文字数 | 2.5 未満で警告 | `transcribe.ffm.min-chars-per-audio-second` |
| 結果が空 | 常に警告 | — |

```
WARN  part_000.wav ... 要確認: 同じ行が 55 行連続しています（「…」） / 音声 1 秒あたり 1.01 文字しかありません（目安 2.50 以上）
WARN  要確認: 1件のpartに品質の警告があります (part_000.wav)。該当する .txt を消して --vad を付けて流し直すと直ることがあります（沈黙の多い録音で有効）
```

既定値の根拠は実測です。3 録音 8 part で、崩壊した part は同一行が 16 / 55 / 67 行連続、
崩壊していない part の最大は 3 行でした（`docs/ENGINE_BENCHMARK.md`）。3 では普通の発話文の重複を
誤検知したので 5 にしてあります。0 以下を指定するとその指標を無効にできます。

**文字数の指標だけでは足りません。** 67 行連続した part は繰り返しが短い 1 文だったため総文字数が減らず、
5.07 文字per秒（正常値）に見えていました。連続同一行の方が主で、文字数は補助です。

**自動で `--vad` に切り替えることはしません。** VAD は発話が途切れない録音では文字数を 3 割落とします。
しかも `--vad` が効いたのは無音の多い 1 例だけで、**無音率 0% でも 67 行連続した実例がある**ため、
そもそも万能な対処ではありません。

### 沈黙の多い録音では `--vad` を付ける

2026-09-08 に、非発話区間が 23%（−40dB / 1 秒以上で 600 秒中 139 秒）を占める 10 分の part で、
**既定（VAD off）だと幻聴の 1 行が 55 行連続し、会話が 1 文も残りませんでした。** 同じ音声に
`--vad` を付けると繰り返しが消え、2,296 文字・149 行が取れました（処理も 180 秒 → 104 秒）。
初期プロンプトを外してもループしたので、プロンプトは原因ではありません。

会議前の待ち時間が入っている録音や、沈黙の長い少人数の会議では `--vad` を付けてください。

> **ただし無音率は崩壊の条件ではありません。** その後の測定で、**無音率 0.0% の part でも
> 67 行連続・16 行連続が発生**しました。`--vad` が効いたのは上の 1 例だけで、無音の無い part に
> 効く保証はありません。そちらの対処は未解決です（`docs/ENGINE_BENCHMARK.md`）。

```powershell
.\transcribe-ffm.bat "C:\...\x.MP3" --vad
```

無音の割合は `ffmpeg` で測れます。

```powershell
ffmpeg -i "C:\...\part_000.wav" -af "silencedetect=noise=-40dB:d=1.0" -f null -
```

part 単位で作り直せます。`part_NNN.txt` を消して再実行すると、その part だけ処理されます
（他の part はスキップされ、結合し直されます）。数値と切り分けの経緯は
**[docs/ENGINE_BENCHMARK.md](docs/ENGINE_BENCHMARK.md)** にあります。

### VAD とデコーダの既定値（実測に基づく）

`transcribe.ffm.*` の既定は、ライブラリ（whisper-ffm）の既定 = whisper.cpp の既定とは意図的に違います。
根拠は実測で、詳細は whisper-ffm の `docs/plan-ffm-v2.md`「(a) の実測」にあります。

| 設定 | このアプリの既定 | whisper.cpp の既定 | 理由 |
| --- | --- | --- | --- |
| `vad` | `false` | `false` | on だと速いが、発話区間を繋ぎ合わせる方式のため繋ぎ目の文をまるごと落とす（文字数が 3 割減）。**ただし沈黙の多い録音では逆転する**（前述） |
| `best-of` | `-1` | `5` | 5 にしても処理時間は同じで文字数はむしろ減り、ループ抑制効果も確認できなかった |
| `temperature-increment` | `0.4` | `0.2` | 同上 |
| `beam-size` | `2` | `5` | 速度優先 |
| `suppress-non-speech-tokens` | `true` | `false` | 議事録では記号の注記が邪魔（ただし `【】` は whisper.cpp の抑制対象外） |

再ビルドせずに A/B できます。

```powershell
$jar = "target\transcribe-shell-0.0.1-SNAPSHOT.jar"
java --enable-native-access=ALL-UNNAMED "-Dspring.shell.interactive.enabled=false" `
  "-Dtranscribe.ffm.carry-initial-prompt=true" `
  -jar $jar transcribe-ffm "C:\...\x.MP3" -o "C:\...\transcribe-ffm_x_carry"
```

ログの「設定: …」「デコーダ: …」の行に実際の値が出るので、狙った条件で走っているか確認できます。

### エンジン関連の設定項目

```properties
transcribe.whisper.engine=faster                 # faster | openai | cpp（既定 faster）
transcribe.whisper.command=whisper-ctranslate2   # faster の実行ファイル（PATH 前提・上書き可）
transcribe.whisper.compute-type=int8             # faster のみ。CPU は int8 が速い
transcribe.whisper.output-format=txt             # faster のみ。結合は txt のみ使うため既定 txt
transcribe.whisper.min-part-bytes=16384          # 全エンジン共通。これ未満の part は警告してスキップ（0 で無効）

# cpp エンジン固有（transcribe.whisper.cpp.*）
transcribe.whisper.cpp.command=whisper-cli               # PATH に無ければフルパスを指定
transcribe.whisper.cpp.model-dir=                        # 既定は <ユーザーホーム>/whisper-models
transcribe.whisper.cpp.model-file-pattern=ggml-{model}.bin
transcribe.whisper.cpp.language=                         # 空なら --language の値をそのまま使う
transcribe.whisper.cpp.threads=0                         # 0 なら whisper-cli の既定（min(4, CPU数)）
transcribe.whisper.cpp.max-context=0                     # 幻覚ループ対策（下記）。負値なら -mc を渡さない
transcribe.whisper.cpp.input-format=mp3                  # mp3 | wav（下記参照）
```

- `--device` は既定 `auto` 据え置き（本アプリからは明示しない）。
- openai エンジンの起動には `transcribe.py-path`（既定 `py`）を使います。cpp エンジンでは使いません。
- **`max-context` について（重要）**: whisper.cpp は既定で直前のテキストを文脈として引きずるため、
  日本語の会議録音で**同じ文を延々と繰り返す幻覚ループ**が多発します。本アプリは既定で `-mc 0` を渡して
  これを抑えています。実測（4.5分の part / small）では次のとおりでした。

  | | whisper-cli の既定 | `-mc 0`（本アプリの既定） |
  | --- | --- | --- |
  | 出力 | 403 バイト | 5,039 バイト |
  | 重複行の割合 | 78.9%（「(音楽)」が16回連続） | **4.1%** |
  | 所要時間 | 79.6秒 | 117.2秒 |

  ※既定の方が速いのは、幻覚ループで早々に処理を打ち切っていたためで、品質を伴った速さではありません。
  実際、34.5分の録音全体では `-mc 0` を付けた方が**速く終わります**（15分2秒 → 13分37秒）。
  faster-whisper には繰り返しを検出して再生成する `compression_ratio_threshold` がありますが、
  whisper.cpp には無いため、文脈の引き継ぎを切るのが実質的な対策になります
  （検証の詳細は [docs/ENGINE_BENCHMARK.md](docs/ENGINE_BENCHMARK.md)）。
- **`input-format` について**: whisper-cli 1.9.2 は MP3 を直接読めるので既定は `mp3` のままで構いません
  （分割は従来どおり `-c copy` なので高速、既存の作業フォルダともそのまま互換）。MP3 を読めない古い
  whisper.cpp に当たった場合だけ `wav` にすると、分割時に 16kHz・モノラルの WAV を作ります。
- **形式が混在した場合**: 既に `part_*.mp3` がある作業フォルダで `wav` に切り替えても、**既存の形式が
  優先**され再分割は起きません（無駄な変換とファイルの混在を避けるため）。WAV で作り直したい場合は
  出力フォルダ（`transcribe_<base>`）を削除してから実行してください。

## 設定（任意）

`src/main/resources/application.properties` で既定値や実行ファイルのパスを上書きできます。

```properties
# 実行ファイルのパス（PATH に無い場合や別バイナリを使う場合）
transcribe.ffmpeg-path=C:\\tools\\ffmpeg\\bin\\ffmpeg.exe
transcribe.py-path=py

# 既定値
transcribe.default-model=small
transcribe.default-language=Japanese
transcribe.default-segment-time=600

# transcribe-all の既定走査フォルダ（OS ごとに変更可。例: Mac なら ~/Music）
transcribe.default-dir=${user.home}\\Music

# 文字起こしエンジン（詳細は「エンジンの切り替え」を参照）
transcribe.whisper.engine=faster
transcribe.whisper.command=whisper-ctranslate2
transcribe.whisper.compute-type=int8
transcribe.whisper.output-format=txt

# cpp エンジンを使う場合のみ
transcribe.whisper.cpp.command=C:\\tools\\whisper.cpp\\Release\\whisper-cli.exe
transcribe.whisper.cpp.model-dir=${user.home}\\whisper-models
```

## 補足

- Whisper が CPU 実行時に出す `FP16 is not supported on CPU; using FP32 instead` は警告であり、エラーではありません
  （終了コードで成否を判定しています）。
- 長時間かかる文字起こしの進捗は、子プロセスの出力をそのまま画面へリアルタイム表示します。

## 要約機能の準備（Ollama・任意）

要約機能（`all.txt` から要点・TODO 等を自動生成）を使う場合のみ、ローカル LLM **Ollama** と
モデルを準備します。**文字起こしには不要**なので、要約を使わない／要約を使わない PC では入れなくて構いません。
テキストを外部に送らずローカルで要約するため、社内会話などでも安心して使えます。

### 導入手順（Windows）

1. [ollama.com](https://ollama.com/download) から Ollama をインストール（インストール後はバックグラウンドで
   起動し、`http://localhost:11434` で待ち受けます）。確認:

   ```powershell
   ollama --version
   ```

2. 日本語が扱えるモデルを 1 つ取得（ディスクと相談。名前・サイズは [ollama.com/library](https://ollama.com/library) で最新を確認）。

   ```powershell
   ollama pull qwen2.5:7b     # 約 4.7GB・日本語も比較的得意（おすすめ）
   # 省スペース版: ollama pull qwen2.5:3b   # 約 2GB・軽いが品質は控えめ
   ```

3. 動作確認:

   ```powershell
   ollama run qwen2.5:7b "次の文を一行で要約して: 今日は打ち合わせで納期を確認した。"
   # 確認できたら /bye で終了
   curl http://localhost:11434/api/tags   # 導入済みモデル一覧
   ```

- 使うモデルはアプリ設定 `transcribe.summary.model` で指定します。
- モデルは C ドライブ（`C:\Users\<ユーザー>\.ollama`）に保存されます。空き容量に注意
  （目安: 7B 級で約 5GB）。不要になったモデルは `ollama rm <model>` で削除できます。

> 要約コマンド（`summarize` / `transcribe --summarize`）自体の使い方は、要約機能を実装した際に
> この README へ追記されます。本節はその**前提となる Ollama の準備**です。

## 今後の拡張（ロードマップ）

毎日の運用負担を段階的に減らし、その先で「見直しの質」を上げていく方針。近いものから並べる。

### 足元の自動化（優先）

1. **完了後の自動オープン** — `--open` オプションで結合ファイル `<base>_all.txt` を既定アプリで開く。
   録音して見直す運用なら、処理後すぐ本文が開いて便利。TranscribeCommand への小改修。
2. **毎晩の自動実行** — Windows タスクスケジューラで `transcribe-all` を夜間に自動起動
   （コード変更不要。バッチをスケジューラに登録するだけ）。冪等性があるので、録音を
   フォルダに置いておけば朝には文字起こしが揃う。
3. **要約の自動生成** — 文字起こし後に LLM で要点・決定事項・確認事項・TODO 等まで生成し、
   `<base>_要約.md` として出力する。ChatGPT に貼る手作業がなくなる。

> 毎日 1〜数本という規模なら、現実的には **「自動化 ＋ 要約」まででほとんどの価値が取れる**。
> 以下は「欲しくなったら」で十分。

### 見直しの質を上げる（必要になったら）

4. **話者分離** — 誰が話したかを分ける（pyannote 等）。会議の議事録化で効く。
5. **過去の文字起こしを横断検索** — 貯まった議事録から「あの案件どうなった？」を引けるようにする。
6. **通知連携** — 生成した要約を Slack・メール・Notion 等へ自動投稿する。

### 規模が大きくなったら

7. **GPU / クラウドで高速化** — 処理量が増えて CPU が厳しくなったら、GPU や
   クラウド（EC2 等）に処理を移す。1〜数本/日の規模では不要。
8. **RAG** — 蓄積が増えたら、過去の全文字起こしに対してチャットで質問できるようにする。

> いずれの拡張も現状の土台にそのまま乗る。着手時は設計確認後に実装し、
> 外部 API を使う変更は実ビルドでも確認する。

---

## ライセンス

Apache License, Version 2.0（[LICENSE](LICENSE)）。同梱する依存の帰属表示は [NOTICE](NOTICE) にあります。

`ffmpeg` / `whisper-ctranslate2` / `openai-whisper` / `whisper-cli` は別プロセスとして呼ぶだけで、
このリポジトリには含まれません。各自でインストールし、それぞれのライセンスに従ってください。
