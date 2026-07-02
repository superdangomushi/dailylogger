#!/usr/bin/env python3
"""
Waseda 科目登録（時間割）を取得して AIHelper サーバーへ登録するスクレイパ。

seiseki-viewer の実績あるログイン手順（MyWaseda → Microsoft ログイン → coursereg）を流用。
科目登録は滅多に変わらないため、cron ではなく「手動実行（取得ボタン相当）」を想定。
Moodle の自動巡回（3日ごと）も、このスクリプトを cron で回して実現できる（--moodle は今後拡張）。

使い方:
  pip install -r requirements.txt   # selenium webdriver-manager beautifulsoup4 requests
  # (a) サーバー保存の資格情報を使う（各ユーザーがアカウント画面で Waseda 連携を保存済みの場合）
  AIHELPER_URL=http://localhost:3000 AIHELPER_EMAIL=you@example.com AIHELPER_TOKEN=xxxx \
  python3 waseda_scraper.py            # 資格情報取得→ログイン→時間割取得→サーバー登録
  # (b) 環境変数で直接渡す（従来どおり。こちらが優先）
  WASEDA_ID=xxxx@akane.waseda.jp WASEDA_PASSWORD=**** \
  AIHELPER_URL=http://localhost:3000 AIHELPER_EMAIL=you@example.com AIHELPER_TOKEN=xxxx \
  python3 waseda_scraper.py
  python3 waseda_scraper.py --dump timetable.html   # HTML を保存（セレクタ調整用）
  python3 waseda_scraper.py --headful  # ブラウザ表示（2FA/初回確認用）

注意: 実際の科目登録ページの HTML 構造は環境で異なるため、parse_timetable() の
セレクタは --dump で保存した HTML を見て調整してください。
"""

import os
import re
import sys
import time
import json
import requests
from bs4 import BeautifulSoup

PORTAL = "https://coursereg.waseda.jp/portal/simpleportal.php?HID_P14=JA"
LOGIN_ENTRY = "https://my.waseda.jp/login/login"
DAYS = ["月", "火", "水", "木", "金", "土", "日"]


def make_driver(headful=False):
    from selenium import webdriver
    from selenium.webdriver.chrome.service import Service
    opts = webdriver.ChromeOptions()
    if not headful:
        opts.add_argument("--headless=new")
    for a in ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
              "--window-size=1920,1080", "--disable-extensions"]:
        opts.add_argument(a)
    opts.add_argument("--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    path = os.environ.get("CHROMEDRIVER_PATH")
    if path:
        service = Service(executable_path=path)
    else:
        import shutil
        sys_cd = shutil.which("chromedriver")
        if sys_cd:
            service = Service(executable_path=sys_cd)
        else:
            from webdriver_manager.chrome import ChromeDriverManager
            service = Service(ChromeDriverManager().install())
    return webdriver.Chrome(service=service, options=opts)


def login(driver, username, password):
    from selenium.webdriver.common.by import By
    from selenium.webdriver.support.ui import WebDriverWait
    from selenium.webdriver.support import expected_conditions as EC
    wait = WebDriverWait(driver, 25)

    driver.get(LOGIN_ENTRY)
    time.sleep(3)
    try:
        links = driver.find_elements(By.XPATH, "//a[contains(text(),'Login') or contains(text(),'ログイン')]")
        if links:
            links[0].click(); time.sleep(3)
    except Exception:
        pass

    try:
        wait.until(lambda d: "login.microsoftonline.com" in d.current_url or "my.waseda.jp/portal" in d.current_url)
    except Exception:
        pass

    if "login.microsoftonline.com" in driver.current_url:
        wait.until(EC.presence_of_element_located((By.NAME, "loginfmt"))).send_keys(username)
        wait.until(EC.element_to_be_clickable((By.ID, "idSIButton9"))).click()
        wait.until(EC.visibility_of_element_located((By.NAME, "passwd"))).send_keys(password)
        wait.until(EC.element_to_be_clickable((By.ID, "idSIButton9"))).click()
        try:
            wait.until(EC.element_to_be_clickable((By.ID, "idBtn_Back"))).click()  # Stay signed in? → No
        except Exception:
            pass
        try:
            wait.until(lambda d: "my.waseda.jp/portal" in d.current_url or "waseda.jp" in d.current_url)
        except Exception:
            if "login.microsoftonline.com" in driver.current_url:
                raise RuntimeError("ログイン未完了（2FA が必要か認証情報が誤り）")


def open_timetable(driver):
    """coursereg ポータルから科目登録/時間割ページを開き HTML を返す。"""
    from selenium.webdriver.common.by import By
    driver.get(PORTAL)
    time.sleep(3)
    # 「科目登録」または「時間割」等のリンクを探してクリック（表記ゆれに対応）。
    for kw in ["科目登録照会", "科目登録", "時間割", "履修"]:
        try:
            link = driver.find_element(By.XPATH, f"//a[contains(., '{kw}')]")
            link.click()
            time.sleep(4)
            # 新規ウィンドウが開く場合に切替
            if len(driver.window_handles) > 1:
                driver.switch_to.window(driver.window_handles[-1])
                time.sleep(2)
            break
        except Exception:
            continue
    return driver.page_source


def parse_timetable(html):
    """時間割 HTML から [{term, day, period, name, room}] を抽出する（要調整・ヒューリスティック）。

    多くの Waseda 時間割は「曜日(月〜土)を列、時限(1〜7)を行」とする表。
    セル内テキストから科目名と教室(〇号館〇教室 等)を推定する。
    """
    soup = BeautifulSoup(html, "html.parser")
    courses = []
    room_re = re.compile(r"([0-9０-９A-Za-z]*号館[^\s／/]*|[^\s／/]*教室|[A-Z]?\d{2,}[- ]?\d+)")

    for table in soup.find_all("table"):
        header_cells = [c.get_text(strip=True) for c in table.find_all(["th", "td"], limit=10)]
        header_text = "".join(header_cells)
        if not ("月" in header_text and "火" in header_text):
            continue  # 曜日ヘッダを持つ表だけ対象

        rows = table.find_all("tr")
        # ヘッダ行の曜日カラム位置を特定
        head = rows[0].find_all(["th", "td"])
        day_cols = {}
        for i, cell in enumerate(head):
            t = cell.get_text(strip=True)
            for d in DAYS:
                if t == d or t.startswith(d):
                    day_cols[i] = d
        if not day_cols:
            continue

        for r in rows[1:]:
            cells = r.find_all(["th", "td"])
            if not cells:
                continue
            period_txt = cells[0].get_text(" ", strip=True)
            m = re.search(r"(\d+)", period_txt)
            period = int(m.group(1)) if m else None
            for i, cell in enumerate(cells):
                if i not in day_cols:
                    continue
                text = cell.get_text("\n", strip=True)
                if not text:
                    continue
                lines = [l for l in text.split("\n") if l.strip()]
                name = lines[0] if lines else text
                room = ""
                rm = room_re.search(text)
                if rm:
                    room = rm.group(1)
                courses.append({
                    "day": day_cols[i], "period": period,
                    "name": name[:255], "room": room, "term": None,
                })
    return courses


def fetch_server_credentials():
    """AIHelper サーバーに保存された本人の Waseda ID・パスワードを取得する。

    AIHELPER_URL / AIHELPER_EMAIL / AIHELPER_TOKEN が揃っていて、
    ユーザーがアカウント画面から Waseda 連携を保存済みのときに使える。
    """
    base = os.environ.get("AIHELPER_URL")
    email = os.environ.get("AIHELPER_EMAIL")
    token = os.environ.get("AIHELPER_TOKEN")
    if not base or not email or not token:
        return None
    try:
        r = requests.get(f"{base.rstrip('/')}/api/waseda/credentials",
                         headers={"X-Account-Email": email, "Authorization": f"Bearer {token}"},
                         timeout=15)
        j = r.json()
        if r.ok and j.get("ok"):
            print(f"サーバー保存の Waseda アカウントを使用: {j['wasedaUser']}")
            return j["wasedaUser"], j["wasedaPassword"]
        print("サーバーから資格情報を取得できません:", j.get("error", f"HTTP {r.status_code}"))
    except Exception as e:
        print("サーバーへの資格情報の問い合わせに失敗:", e)
    return None


def post_courses(courses):
    base = os.environ["AIHELPER_URL"].rstrip("/")
    email = os.environ["AIHELPER_EMAIL"]
    token = os.environ["AIHELPER_TOKEN"]
    r = requests.post(f"{base}/api/courses",
                      headers={"X-Account-Email": email, "Authorization": f"Bearer {token}",
                               "Content-Type": "application/json"},
                      data=json.dumps({"courses": courses}), timeout=30)
    r.raise_for_status()
    print("サーバー登録:", r.json())


def main():
    dump = None
    headful = "--headful" in sys.argv
    if "--dump" in sys.argv:
        i = sys.argv.index("--dump")
        dump = sys.argv[i + 1] if i + 1 < len(sys.argv) else "timetable.html"

    username = os.environ.get("WASEDA_ID")
    password = os.environ.get("WASEDA_PASSWORD")
    if not username or not password:
        # 環境変数に無ければ、AIHelper サーバーに保存された本人の Waseda アカウントを使う。
        creds = fetch_server_credentials()
        if creds:
            username, password = creds
        else:
            print("環境変数 WASEDA_ID / WASEDA_PASSWORD か、"
                  "AIHELPER_URL / AIHELPER_EMAIL / AIHELPER_TOKEN（サーバー保存の資格情報）が必要です")
            sys.exit(1)

    driver = make_driver(headful=headful)
    try:
        print("ログイン中…")
        login(driver, username, password)
        print("時間割ページを取得中…")
        html = open_timetable(driver)
        if dump:
            with open(dump, "w", encoding="utf-8") as f:
                f.write(html)
            print(f"HTML を保存しました: {dump}（parse_timetable のセレクタ調整に使ってください）")
        courses = parse_timetable(html)
        print(f"抽出した科目数: {len(courses)}")
        for c in courses:
            print(" ", c)
        if courses and os.environ.get("AIHELPER_URL"):
            post_courses(courses)
        elif not courses:
            print("科目が抽出できませんでした。--dump で HTML を保存し、parse_timetable を調整してください。")
    finally:
        driver.quit()


if __name__ == "__main__":
    main()
