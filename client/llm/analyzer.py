#!/usr/bin/env python3
"""ローカルLLM(Ollama)解析ワーカー。

サーバーの「未解析の文字起こし」と「未生成の日次要約」をポーリングし、手元の Ollama で
処理して結果を返す。Gemini APIキーを登録していないユーザーでも、このワーカーがあれば
課題・予定の抽出＋要約と日次要約がローカルで回る。

音声ワーカー（../cpp の audio-worker）が faster-whisper でやっていることの「テキスト解析版」。
思想は同じ（サーバーのジョブを poll → 処理 → 返す）だが、こちらは独立した薄い Python ツール。

役割分担:
  - プロンプト組み立て・出力の正規化・DB 保存はすべてサーバー側（server/gemini.js の共有部品）。
  - このワーカーは「サーバーから prompt を受け取り、Ollama に投げ、生の出力を返す」だけの実行プロキシ。

認証情報は音声ワーカーと同じ ../accounts.json を読んで再利用する（追加ログイン不要）。

環境変数（すべて任意）:
  AIHELPER_SERVER_URL   ... サーバーURL（accounts.json の baseUrl があればそちら優先。既定 http://localhost:3000）
  AUDIO_WORKER_CONFIG   ... accounts.json のパス（既定: このファイルの ../accounts.json）
  OLLAMA_URL            ... Ollama エンドポイント（既定 http://localhost:11434）
  OLLAMA_MODEL          ... 使用モデル（既定 qwen2.5:7b。日本語＋JSON出力が得意なものを推奨）
  LLM_WORKER_POLL_SEC   ... 仕事が無いときのポーリング間隔秒（既定 15）
  LLM_ANALYZE_TIMEOUT   ... Ollama 1回あたりのタイムアウト秒（既定 600）

使い方:
  python3 client/llm/analyzer.py
  （事前に `ollama serve` と `ollama pull <model>` が必要。依存は requests のみ）
"""

import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONFIG = os.path.join(HERE, "..", "accounts.json")

SERVER_URL_ENV = os.environ.get("AIHELPER_SERVER_URL", "http://localhost:3000")
CONFIG_PATH = os.environ.get("AUDIO_WORKER_CONFIG", DEFAULT_CONFIG)
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "qwen2.5:7b")
POLL_SEC = max(1, int(os.environ.get("LLM_WORKER_POLL_SEC", "15") or 15))
OLLAMA_TIMEOUT = max(30, int(os.environ.get("LLM_ANALYZE_TIMEOUT", "600") or 600))


def log(msg):
    """進捗ログは stderr へ（安定して見えるよう即 flush）。"""
    print(f"[llm-worker] {msg}", file=sys.stderr, flush=True)


def load_accounts():
    """accounts.json を読み、enabled なアカウントと baseUrl を返す。

    音声ワーカーがまだセットアップされていなければ空リストを返す（起動はする）。
    """
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except FileNotFoundError:
        log(f"設定ファイルが見つかりません: {CONFIG_PATH}（音声ワーカーのセットアップが先です）")
        return SERVER_URL_ENV, []
    except (json.JSONDecodeError, OSError) as e:
        log(f"設定ファイルの読み込みに失敗: {e}")
        return SERVER_URL_ENV, []

    base_url = (cfg.get("baseUrl") or SERVER_URL_ENV).rstrip("/")
    accounts = [
        {"email": a.get("email", ""), "token": a.get("token", "")}
        for a in cfg.get("accounts", [])
        if a.get("enabled") and a.get("email") and a.get("token")
    ]
    return base_url, accounts


def server_post(base_url, path, account, extra):
    """サーバーの /api/llm/* を叩く。body に auth(email+token) を載せる。

    戻り値: 成功時 JSON(dict)、失敗時 None。
    """
    body = {"auth": {"email": account["email"], "token": account["token"]}, **extra}
    url = base_url + path
    try:
        r = requests.post(url, json=body, timeout=60)
    except requests.RequestException as e:
        log(f"サーバー通信に失敗 ({path}): {e}")
        return None
    if r.status_code != 200:
        log(f"サーバーが {r.status_code} を返しました ({path}): {r.text[:200]}")
        return None
    try:
        data = r.json()
    except ValueError:
        log(f"サーバー応答が JSON ではありません ({path})")
        return None
    if not data.get("ok"):
        log(f"サーバーがエラーを返しました ({path}): {data.get('error')}")
        return None
    return data


def ollama_generate(prompt, schema=None):
    """Ollama /api/generate を1回呼ぶ。schema があれば構造化出力(JSON)を要求する。

    戻り値: 生成テキスト（str）。失敗時は例外を投げる（呼び出し側が error として報告）。
    """
    payload = {"model": OLLAMA_MODEL, "prompt": prompt, "stream": False}
    if schema is not None:
        # Ollama の structured outputs: format に JSON schema を渡すと出力がそれに従う。
        payload["format"] = schema
    r = requests.post(f"{OLLAMA_URL}/api/generate", json=payload, timeout=OLLAMA_TIMEOUT)
    if r.status_code != 200:
        raise RuntimeError(f"Ollama が {r.status_code}: {r.text[:200]}")
    data = r.json()
    text = (data.get("response") or "").strip()
    if not text:
        raise RuntimeError("Ollama から空の応答が返りました")
    return text


def process_analyze(base_url, account):
    """未解析の文字起こしを claim して尽きるまで解析する。処理した件数を返す。"""
    done = 0
    while True:
        claim = server_post(base_url, "/api/llm/analyze/claim", account, {})
        if not claim:
            break
        job = claim.get("job")
        if not job:
            break  # このアカウントの未解析はもう無い
        tid = job["transcriptId"]
        label = job.get("filename") or f"#{tid}"
        log(f"解析: {account['email']} {label} を Ollama({OLLAMA_MODEL}) で処理中…")
        try:
            output = ollama_generate(job["prompt"], schema=job.get("schema"))
        except Exception as e:  # noqa: BLE001 - Ollama 側の失敗は error として報告して継続
            log(f"解析失敗（ロック解除して次へ）: {label}: {e}")
            server_post(base_url, "/api/llm/analyze/result", account,
                        {"transcriptId": tid, "error": str(e)[:500]})
            break  # Ollama が不調なら以降も失敗する見込み。次のポーリングに回す
        res = server_post(base_url, "/api/llm/analyze/result", account,
                          {"transcriptId": tid, "output": output})
        if res and res.get("status") == "done":
            done += 1
            log(f"解析完了: {label} -> タスク {res.get('tasks', 0)} 件")
        else:
            break
    return done


def process_daily(base_url, account):
    """今日の日次要約が必要なら生成する。生成したら 1、しなければ 0 を返す。"""
    claim = server_post(base_url, "/api/llm/daily/claim", account, {})
    if not claim:
        return 0
    job = claim.get("job")
    if not job:
        return 0
    day = job["day"]
    log(f"日次要約: {account['email']} {day} を Ollama で生成中…")
    try:
        output = ollama_generate(job["prompt"])  # 自由テキスト（schema なし）
    except Exception as e:  # noqa: BLE001
        log(f"日次要約失敗: {day}: {e}")
        server_post(base_url, "/api/llm/daily/result", account, {"day": day, "error": str(e)[:500]})
        return 0
    res = server_post(base_url, "/api/llm/daily/result", account, {"day": day, "output": output})
    if res and res.get("status") == "done":
        log(f"日次要約完了: {day}")
        return 1
    return 0


def check_ollama():
    """起動時に Ollama へ疎通確認。到達できなければ警告だけ出して続行（後で復帰し得る）。"""
    try:
        r = requests.get(f"{OLLAMA_URL}/api/tags", timeout=10)
        if r.status_code == 200:
            names = [m.get("name", "") for m in r.json().get("models", [])]
            if OLLAMA_MODEL not in names and OLLAMA_MODEL.split(":")[0] not in [n.split(":")[0] for n in names]:
                log(f"警告: モデル '{OLLAMA_MODEL}' が未取得かもしれません。`ollama pull {OLLAMA_MODEL}` を実行してください")
            else:
                log(f"Ollama 接続OK（モデル {OLLAMA_MODEL}）")
            return
    except requests.RequestException:
        pass
    log(f"警告: Ollama({OLLAMA_URL}) に接続できません。`ollama serve` を起動してください（起動後は自動で処理を開始します）")


def main():
    log(f"起動: server={SERVER_URL_ENV} ollama={OLLAMA_URL} model={OLLAMA_MODEL} poll={POLL_SEC}s")
    check_ollama()
    while True:
        base_url, accounts = load_accounts()
        if not accounts:
            time.sleep(POLL_SEC)
            continue
        worked = 0
        for account in accounts:
            worked += process_analyze(base_url, account)
            worked += process_daily(base_url, account)
        # 仕事があったらすぐ次周（滞留分を早く捌く）、無ければ POLL_SEC 待つ。
        time.sleep(0.5 if worked else POLL_SEC)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log("停止しました")
