# CI とフォーマッタ

ビルドが通るかどうかを GitHub Actions で自動検証し、あわせてソースが整形済みかも検査する。
**CI が実行するのは各コンポーネントの Makefile ターゲットだけ**なので、
手元で同じ `make` コマンドが通れば CI も通る。

## CI（`.github/workflows/ci.yml`）

`main` への push、すべての Pull Request、および手動実行（workflow_dispatch）で走る。
同じブランチに続けて push した場合は古い実行を打ち切る。

| ジョブ | ランナー | 実行するコマンド | 見ているもの |
| --- | --- | --- | --- |
| `server` | ubuntu-latest | `make npm-deps` → `make build` → `make test` (in `server/`) | C++ HTTPゲートウェイがビルドできるか、`node --test` の互換テストが通るか |
| `client` | ubuntu-latest | `make build` (in `client/`) | ワーカー本体 `audio-worker` がビルドできるか |
| `format` | ubuntu-latest | `make format-check` (in `server/` と `client/`) | C++/JS/Python が整形済みか |

補足:

- `server` ジョブの C++ ビルドは `-I../client/cpp/third_party` を使うため、`client/` も
  同じリポジトリに入っている前提。checkout 1回で両方揃う。
- `client` ジョブは `make system-deps` を丸ごと走らせない。ランナーには g++/make が
  最初から入っているので、足りない `libssl-dev` だけを apt で入れている。
- Android（`app/`）と iOS（`ios/`）は CI 対象外。whisper.cpp submodule と NDK / Xcode が
  必要でジョブが重いため、まずサーバーとワーカーだけを対象にしている。

## フォーマッタ

| 言語 | ツール | 設定ファイル | 対象 |
| --- | --- | --- | --- |
| C / C++ | clang-format | `.clang-format` / `.clang-format-ignore` | `server/cpp/src/`、`client/cpp/src/` |
| JavaScript | Prettier | `server/.prettierrc.json` / `server/.prettierignore` | `server/*.js`、`server/test/*.js` |
| Python | Black | `pyproject.toml`（`[tool.black]`） | `server/scraper/*.py`、`client/stt/*.py` |

借り物のコードは整形しない。`client/cpp/third_party/`（cpp-httplib / nlohmann-json）と
`app/src/main/cpp/whisper.cpp`（submodule）は `.clang-format-ignore` と
各 Makefile のファイル一覧の両方で除外している。

### 使い方

```bash
cd server        # または cd client
make format-deps # 初回のみ。フォーマッタを .venv-fmt に導入（server は Prettier も）
make format      # 整形する（ファイルを書き換える）
make format-check# 整形済みか検査する（書き換えない・CI と同じ判定）
```

`make format` を通してからコミットすれば `format` ジョブで落ちない。

### バージョン固定

clang-format は版が違うと整形結果が変わり、apt で入れると Ubuntu のバージョンごとに
違う版が入ってしまう。そのため clang-format と Black は **pip 版**を使い、
リポジトリ直下の `requirements-format.txt` で版を固定している。
`make format-deps` と CI の両方がこの同じファイルを読むので、手元と CI で結果がずれない。
Prettier も同じ理由で `server/package.json` の devDependencies に版を固定して入れている。

版を上げるときは `requirements-format.txt`（または `server/package.json`）を編集し、
`make format` をかけ直した結果を一緒にコミットする。
