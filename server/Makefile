# aihelper サーバー (C++ HTTP + Node.js/Express + MySQL) 用 Makefile
# 想定環境: Ubuntu Server
#
#   make install   … これ1つで全部: C++/Node.js/MySQL 導入 → DB/ユーザー作成 → 依存導入・ビルド
#                     (apt / mysql 設定に sudo を使う)
#   make run       … サーバーを起動
#
# 個別に実行したい場合:
#   make system-deps … C++ビルド環境 + Node.js + MySQL を apt で導入し MySQL を起動 (要 sudo)
#   make db          … データベース・専用ユーザー作成 + schema.sql 適用 (要 sudo)
#   make npm-deps    … npm 依存関係のみインストール
#   make format      … ソース(C++/JS/Python)を整形
#   make format-check… 整形済みかを検査 (CI が実行するのはこれ)
#
# DB 認証やキーは変数/環境変数で上書き可。例:
#   make install DB_PASSWORD=好きなパスワード
#   make run LINE_CHANNEL_ACCESS_TOKEN=yyy
# Gemini API キーはサーバー環境変数ではなく、各ユーザーがダッシュボードで登録する。

# ---- 設定 (make VAR=値 で上書き可) ----
NODE_MAJOR  ?= 20
DB_HOST     ?= 127.0.0.1
DB_PORT     ?= 3306
DB_NAME     ?= aihelper
# サーバー(Node)が接続する専用ユーザー。fresh install の root は auth_socket で
# TCP 接続できないため、パスワード付きの専用ユーザーを作成して使う。
DB_USER     ?= aihelper
DB_PASSWORD ?= aihelper

# 公開 HTTP ソケットを受ける C++ ゲートウェイ。
CXX       ?= g++
CPPFLAGS  += -I../client/cpp/third_party \
	-DCPPHTTPLIB_HEADER_MAX_LENGTH=16384 \
	-DCPPHTTPLIB_REQUEST_URI_MAX_LENGTH=16384 \
	-DCPPHTTPLIB_LISTEN_BACKLOG=511 \
	-DCPPHTTPLIB_EXPECT_CONTENT_FROM_HEADERS \
	-DCPPHTTPLIB_RAW_SERVER_BODY \
	-DCPPHTTPLIB_STRICT_REQUEST_FRAMING \
	-DCPPHTTPLIB_STRICT_HEADER_PARSING \
	-DCPPHTTPLIB_RAW_HEADER_VALUES \
	-DCPPHTTPLIB_DISABLE_SERVER_RANGE \
	-DCPPHTTPLIB_REJECT_OVERSIZED_PAYLOAD_IMMEDIATELY \
	-DCPPHTTPLIB_STOP_CLOSES_ACTIVE_SOCKETS \
	-DCPPHTTPLIB_ABSOLUTE_READ_DEADLINE \
	-DCPPHTTPLIB_NO_AUTOMATIC_ZERO_CONTENT_LENGTH
CXXFLAGS  ?= -std=c++17 -O2 -Wall -Wextra -Wpedantic
LDLIBS    += -pthread
HTTP_BIN  := aihelper-server
HTTP_SRC  := cpp/src/main.cpp
HTTP_DEPS := ../client/cpp/third_party/httplib.h

# ---- フォーマッタ (make format / make format-check) ----
# C++ は clang-format、JS は Prettier(npm devDependency)、Python は Black。
# 生成物や借り物のコード(node_modules / third_party)は対象に含めない。
# バージョンは ../requirements-format.txt で固定する (CI も同じものを読む)。
PYTHON       ?= python3
PRETTIER     ?= ./node_modules/.bin/prettier
# make format-deps で作った専用 venv があればそれを優先し、無ければ PATH 上の物を使う。
ifneq ($(wildcard .venv-fmt/bin/clang-format),)
CLANG_FORMAT ?= .venv-fmt/bin/clang-format
BLACK        ?= .venv-fmt/bin/black
else
CLANG_FORMAT ?= clang-format
BLACK        ?= black
endif

FMT_CPP := $(HTTP_SRC)
FMT_JS  := $(wildcard *.js) $(wildcard test/*.js)
FMT_PY  := $(wildcard scraper/*.py)

# これらは Node サーバー(process.env)と mysql クライアント双方へ渡す。
export DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD

# DB とユーザーを「最初の一回だけ」作るための管理者接続。
# root は使わず、Ubuntu の保守用アカウント(debian-sys-maint)を既定にする。
# （新規ユーザーが自分自身を作ることはできないため、初回のみ管理者権限が要る）
# debian.cnf が無い/使えない環境では上書きする。例:
#   make db MYSQL_ADMIN='mysql -u root -p'   … どうしても root で入れる場合(対話でパスワード)
MYSQL_ADMIN ?= sudo mysql --defaults-file=/etc/mysql/debian.cnf

# 作成後の通常操作(schema 適用など)は、作った aihelper ユーザー自身で行う。
MYSQL_APP := mysql -h $(DB_HOST) -P $(DB_PORT) -u $(DB_USER) -p$(DB_PASSWORD)

.DEFAULT_GOAL := help
.PHONY: help install system-deps db db-user db-schema npm-deps python-deps build test run clean \
	format format-check format-deps

help: ## 使い方を表示
	@echo "aihelper サーバー Makefile (C++ HTTP + Node.js UI/API)"
	@echo ""
	@echo "  make install       C++/Node.js/MySQL 導入 → DB作成 → 依存導入・ビルド (sudo 使用)"
	@echo "  make build         C++ HTTP ゲートウェイをビルド"
	@echo "  make test          HTTP 互換テストを実行"
	@echo "  make run           サーバーを起動 (既定 http://localhost:$${PORT:-3000})"
	@echo "  make system-deps   C++ビルド環境・Node.js $(NODE_MAJOR).x・MySQL を導入 (要 sudo)"
	@echo "  make db            DB($(DB_NAME))・ユーザー($(DB_USER))作成 + schema 適用 (要 sudo)"
	@echo "  make npm-deps      npm 依存関係のみインストール"
	@echo "  make python-deps   時間割スクレイパの Python 依存を scraper/.venv に導入 (要 sudo)"
	@echo "  make format        C++/JS/Python を整形"
	@echo "  make format-check  整形済みかを検査 (書き換えない・CI と同じ)"
	@echo "  make format-deps   clang-format / Prettier / Black を導入 (要 sudo)"
	@echo "  make clean         C++ バイナリ・node_modules・整形用 venv を削除"

# ワンショット・セットアップ。前提ターゲットは記載順に実行される。
install: system-deps db npm-deps python-deps build ## これ1つでサーバーを動かせる状態にする
	@echo ""
	@echo "✅ セットアップ完了。'make run' で起動できます。"
	@echo "   DB: $(DB_USER)@$(DB_HOST):$(DB_PORT)/$(DB_NAME)"

system-deps: ## C++ビルド環境 + Node.js + MySQL を導入し MySQL を起動 (要 sudo)
	@echo ">> C++ build tools、Node.js $(NODE_MAJOR).x (NodeSource)、MySQL Server を導入します"
	curl -fsSL https://deb.nodesource.com/setup_$(NODE_MAJOR).x | sudo -E bash -
	sudo apt-get install -y build-essential nodejs mysql-server
	sudo systemctl enable --now mysql
	@echo ">> Node: $$(node -v) / npm: $$(npm -v)"

db: db-user db-schema ## DB/専用ユーザー作成(管理者・初回のみ) → schema 適用(aihelper ユーザー)
	@echo ">> DB 準備完了"

db-user: ## DB と専用ユーザー aihelper を作成し権限付与 (初回のみ管理者権限が必要・root不使用)
	@echo ">> DB '$(DB_NAME)' と ユーザー '$(DB_USER)' を作成します (管理者: $(MYSQL_ADMIN))"
	# 識別子はシンプル名、文字列は "..." (既定は非ANSI_QUOTES)。
	# ポイント:
	#  - mysql2 は 127.0.0.1 へ TCP 接続するが、MySQL 側で localhost に逆引きされることがあるため
	#    'localhost' と '%' の両方のアカウントを作る。
	#  - 認証は caching_sha2_password（MySQL 8.4 既定）を明示。
	#    MySQL 8.4 では mysql_native_password が既定でロードされず ERROR 1524 になる。
	#    現行 mysql2(v3) は caching_sha2_password を非TLSのTCPでもサポートする。
	#  - 壊れた既存アカウントを避けるため一度 DROP してから作り直す。
	$(MYSQL_ADMIN) -e 'CREATE DATABASE IF NOT EXISTS $(DB_NAME) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; \
	  DROP USER IF EXISTS "$(DB_USER)"@"localhost"; \
	  DROP USER IF EXISTS "$(DB_USER)"@"%"; \
	  CREATE USER "$(DB_USER)"@"localhost" IDENTIFIED WITH caching_sha2_password BY "$(DB_PASSWORD)"; \
	  CREATE USER "$(DB_USER)"@"%" IDENTIFIED WITH caching_sha2_password BY "$(DB_PASSWORD)"; \
	  GRANT ALL PRIVILEGES ON $(DB_NAME).* TO "$(DB_USER)"@"localhost"; \
	  GRANT ALL PRIVILEGES ON $(DB_NAME).* TO "$(DB_USER)"@"%"; \
	  FLUSH PRIVILEGES;'

db-schema: ## schema.sql を aihelper ユーザー自身で適用 (管理者不要)
	@echo ">> schema.sql を $(DB_USER) で適用します"
	$(MYSQL_APP) $(DB_NAME) < schema.sql

npm-deps: ## npm 依存関係をインストール (package-lock.json に厳密一致)
	npm ci

# Ubuntu 23.04+ は pip のシステム全体インストールを禁止 (PEP 668) しているため、
# scraper/.venv に閉じて導入する。server.js は .venv があれば自動でそちらの python を使う。
python-deps: ## 時間割スクレイパの Python 依存 (bs4/selenium 等) を venv に導入 (要 sudo)
	@echo ">> scraper/.venv に Python 依存 (requests/bs4/selenium) を導入します"
	sudo apt-get install -y python3-venv
	python3 -m venv scraper/.venv
	scraper/.venv/bin/pip install -r scraper/requirements.txt
	@echo ">> selenium 用の Chromium ブラウザと chromedriver を導入します"
	sudo apt-get install -y chromium-browser chromium-chromedriver

build: $(HTTP_BIN) ## C++ HTTP ゲートウェイをビルド

$(HTTP_BIN): $(HTTP_SRC) $(HTTP_DEPS)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(HTTP_SRC) -o $@ $(LDFLAGS) $(LDLIBS)

test: build ## C++ HTTP ゲートウェイの互換テストを実行
	node --test test/http-gateway.test.js test/process-supervision.test.js

run: build ## C++ゲートウェイ + 内部Nodeサーバーを起動
	./$(HTTP_BIN)

# 整形は CI の format-check ジョブと同じコマンドを使う。ローカルで make format を
# 通しておけば CI で落ちない。
format: ## C++/JS/Python を整形する (ファイルを書き換える)
	$(CLANG_FORMAT) -i $(FMT_CPP)
	$(PRETTIER) --write $(FMT_JS)
	$(BLACK) $(FMT_PY)

format-check: ## 整形済みかを検査する (書き換えない・CI 用)
	$(CLANG_FORMAT) --dry-run --Werror $(FMT_CPP)
	$(PRETTIER) --check $(FMT_JS)
	$(BLACK) --check --diff $(FMT_PY)

format-deps: ## clang-format / Black を .venv-fmt に、Prettier を npm で導入 (要 sudo)
	@echo ">> .venv-fmt に clang-format / Black を導入します"
	sudo apt-get install -y python3-venv
	$(PYTHON) -m venv .venv-fmt
	.venv-fmt/bin/pip install --upgrade pip
	.venv-fmt/bin/pip install -r ../requirements-format.txt
	@echo ">> Prettier (devDependency) を導入します"
	npm ci

clean: ## C++バイナリ・node_modules・整形用 venv を削除
	rm -rf node_modules .venv-fmt $(HTTP_BIN)
