#!/usr/bin/env python3
"""
Eastercon 2026 programme scraper.

Usage:
    python scrape.py [BASE_URL]

Outputs JSON array of programme items to stdout.
Exits with code 0 on success, 1 on failure.
"""

import json
import re
import sys
from datetime import datetime
from urllib.parse import urljoin, urlparse

try:
    import requests
    from bs4 import BeautifulSoup
except ImportError:
    print("Missing dependencies. Run: pip install requests beautifulsoup4 lxml", file=sys.stderr)
    sys.exit(1)

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "https://guide.eastercon2026.org"
BASE_URL = BASE_URL.rstrip("/")

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": "Ec2026prog/1.0 (+https://github.com/R00S/Ec2026prog)",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
})
TIMEOUT = 30

# Eastercon 2026 dates (Fri 3 – Mon 6 April 2026)
DATE_TO_DAY = {
    "2026-04-03": "Friday",
    "2026-04-04": "Saturday",
    "2026-04-05": "Sunday",
    "2026-04-06": "Monday",
}
DAY_NAMES = {
    "fri": "Friday", "friday": "Friday",
    "sat": "Saturday", "saturday": "Saturday",
    "sun": "Sunday", "sunday": "Sunday",
    "mon": "Monday", "monday": "Monday",
}


def fetch(url, **kwargs):
    """GET a URL, returning a Response or None on error."""
    try:
        resp = SESSION.get(url, timeout=TIMEOUT, **kwargs)
        resp.raise_for_status()
        return resp
    except requests.exceptions.ConnectionError as e:
        print(f"Connection error fetching {url}: {e}", file=sys.stderr)
        return None
    except requests.exceptions.HTTPError as e:
        print(f"HTTP error fetching {url}: {e}", file=sys.stderr)
        return None
    except requests.exceptions.Timeout:
        print(f"Timeout fetching {url}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"Error fetching {url}: {e}", file=sys.stderr)
        return None


def try_json_api():
    """Try known API endpoints that Creative Cat Apps guide sites may expose."""
    candidates = [
        f"{BASE_URL}/api/events",
        f"{BASE_URL}/api/programme",
        f"{BASE_URL}/programme.json",
        f"{BASE_URL}/events.json",
        f"{BASE_URL}/data/events.json",
        f"{BASE_URL}/api/items",
    ]
    for url in candidates:
        print(f"Trying JSON endpoint: {url}", file=sys.stderr)
        resp = fetch(url)
        if resp is None:
            continue
        ct = resp.headers.get("content-type", "")
        if "json" not in ct and not resp.text.strip().startswith("[") and not resp.text.strip().startswith("{"):
            continue
        try:
            data = resp.json()
            if isinstance(data, list) and len(data) > 0:
                print(f"Got {len(data)} items from {url}", file=sys.stderr)
                return data
            if isinstance(data, dict):
                for key in ("events", "programme", "items", "data"):
                    if key in data and isinstance(data[key], list):
                        print(f"Got {len(data[key])} items from {url} (key={key})", file=sys.stderr)
                        return data[key]
        except Exception as e:
            print(f"JSON parse error for {url}: {e}", file=sys.stderr)
    return None


def extract_item_links(soup, page_url):
    """Find all programme item links from the main page."""
    links = set()
    for a in soup.find_all("a", href=True):
        href = a["href"].strip()
        abs_href = urljoin(page_url, href)
        parsed = urlparse(abs_href)
        if parsed.netloc != urlparse(BASE_URL).netloc:
            continue
        path = parsed.path.rstrip("/")
        # Match /id/NUMBER or just /NUMBER
        if re.match(r"^/(id/)?[0-9]+$", path):
            links.add(abs_href.split("?")[0].split("#")[0])
    return sorted(links)


def infer_day(start_time_str, page_text=""):
    """Infer the day of week from a date string or page text."""
    if start_time_str:
        # Strip timezone info and fractional seconds before parsing
        cleaned = start_time_str.strip().replace("Z", "").split("+")[0].split(".")[0]
        for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M",
                    "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
            try:
                # Trim input to the expected length for this format to avoid trailing chars
                expected_len = len(datetime(2000, 1, 1, 0, 0, 0).strftime(fmt))
                dt = datetime.strptime(cleaned[:expected_len], fmt)
                date_str = dt.strftime("%Y-%m-%d")
                if date_str in DATE_TO_DAY:
                    return DATE_TO_DAY[date_str]
                dow = dt.weekday()
                return ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"][dow]
            except (ValueError, TypeError):
                continue

    # Try day name in text
    text_lower = (start_time_str + " " + page_text).lower()
    for abbr, day in DAY_NAMES.items():
        pattern = r'\b' + abbr + r'\b'
        if re.search(pattern, text_lower):
            return day

    return "Friday"


def parse_detail_page(url, soup):
    """Parse an individual event detail page into a dict."""
    item_id_match = re.search(r"/(\d+)(?:/|$)", url)
    item_id = item_id_match.group(1) if item_id_match else url.rstrip("/").split("/")[-1]

    # Title
    title = ""
    for sel in ["h1", ".event-title", ".item-title", "[class*=title]"]:
        el = soup.select_one(sel)
        if el:
            title = el.get_text(strip=True)
            if title:
                break
    if not title:
        title = soup.title.get_text(strip=True) if soup.title else ""

    # Start / end time
    start_time = ""
    end_time = ""
    for sel in [".start-time", ".event-start", "[class*=start]", "time[datetime]",
                ".time", "[class*=time]", ".when", ".schedule-time"]:
        el = soup.select_one(sel)
        if el:
            val = el.get("datetime") or el.get_text(strip=True)
            if val and not start_time:
                start_time = val
    for sel in [".end-time", ".event-end", "[class*=end]"]:
        el = soup.select_one(sel)
        if el:
            val = el.get("datetime") or el.get_text(strip=True)
            if val:
                end_time = val
                break

    # Meta tags fallback
    for meta in soup.find_all("meta"):
        prop = meta.get("property", "") + meta.get("name", "")
        if "start" in prop and not start_time:
            start_time = meta.get("content", "")
        if "end" in prop and not end_time:
            end_time = meta.get("content", "")

    # Location / room
    location = ""
    for sel in [".location", ".room", ".venue", "[class*=location]", "[class*=room]",
                "[class*=venue]", ".where", "[class*=where]"]:
        el = soup.select_one(sel)
        if el:
            location = el.get_text(strip=True)
            if location:
                break

    # Description
    description = ""
    for sel in [".description", ".event-description", ".item-description",
                "[class*=description]", ".content", ".body", "article",
                ".event-body", ".programme-item-description", ".detail"]:
        el = soup.select_one(sel)
        if el:
            description = el.get_text(separator=" ", strip=True)
            if description:
                break
    if not description:
        for tag in soup.find_all(["nav", "header", "footer", "script", "style"]):
            tag.decompose()
        main = soup.select_one("main, #main, #content, .main") or soup.body
        if main:
            description = main.get_text(separator=" ", strip=True)

    page_text = soup.get_text(separator=" ")
    day = infer_day(start_time, page_text)

    return {
        "itemId": item_id,
        "title": title,
        "startTime": start_time,
        "endTime": end_time,
        "location": location,
        "description": description,
        "day": day,
    }


def normalise_json_item(raw):
    """Normalise a raw JSON API item to our schema."""
    def get(*keys):
        for k in keys:
            if k in raw:
                return str(raw[k])
        return ""

    item_id = get("id", "itemId", "item_id")
    title = get("title", "name", "summary")
    start_time = get("startTime", "start_time", "start", "begins_at", "datetime_start")
    end_time = get("endTime", "end_time", "end", "ends_at", "datetime_end")
    location = get("location", "room", "venue", "place")
    description = get("description", "body", "abstract", "content", "detail")
    day_raw = get("day", "weekday", "date")
    day = DAY_NAMES.get(day_raw.lower()[:3], "") or infer_day(start_time, day_raw)

    return {
        "itemId": item_id,
        "title": title,
        "startTime": start_time,
        "endTime": end_time,
        "location": location,
        "description": description,
        "day": day,
    }


def scrape_html():
    """Scrape programme by fetching the main page and following item links."""
    print(f"Fetching main page: {BASE_URL}", file=sys.stderr)
    resp = fetch(BASE_URL)
    if resp is None:
        return None

    soup = BeautifulSoup(resp.text, "lxml")
    links = extract_item_links(soup, BASE_URL)
    print(f"Found {len(links)} item links", file=sys.stderr)

    if not links:
        print("No item links found – trying broader extraction", file=sys.stderr)
        # Broader: any internal link that looks like a page
        base_host = urlparse(BASE_URL).netloc
        for a in soup.find_all("a", href=True):
            abs_href = urljoin(BASE_URL, a["href"])
            parsed = urlparse(abs_href)
            path = parsed.path.rstrip("/")
            if (parsed.netloc == base_host and path and path != "/"
                    and not path.endswith((".css", ".js", ".png", ".jpg"))
                    and "#" not in path):
                links.add(abs_href.split("?")[0].split("#")[0])
        links = sorted(links)
        print(f"Broader extraction found {len(links)} links", file=sys.stderr)

    if not links:
        return None

    events = []
    for i, link in enumerate(links, 1):
        print(f"Fetching {i}/{len(links)}: {link}", file=sys.stderr)
        detail_resp = fetch(link)
        if detail_resp is None:
            continue
        detail_soup = BeautifulSoup(detail_resp.text, "lxml")
        event = parse_detail_page(link, detail_soup)
        if event["title"]:
            events.append(event)

    return events if events else None


def main():
    # First try JSON API endpoints
    json_data = try_json_api()
    if json_data:
        events = [normalise_json_item(item) for item in json_data]
        events = [e for e in events if e["title"]]
        if events:
            print(json.dumps(events, ensure_ascii=False, indent=2))
            return 0

    # Fall back to HTML scraping
    events = scrape_html()
    if not events:
        print("Failed to fetch any programme items", file=sys.stderr)
        return 1

    print(json.dumps(events, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
