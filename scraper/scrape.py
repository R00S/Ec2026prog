#!/usr/bin/env python3
"""
Eastercon 2026 programme scraper.

Usage:
    python scrape.py [BASE_URL]

Outputs JSON array of programme items to stdout.
Exits with code 0 on success, 1 on failure.

Supports Grenadine-powered event sites by trying the program.json API
first, then falling back to HTML scraping.
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

# Eastercon 2026 dates (Fri 3 - Mon 6 April 2026)
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


def try_grenadine_api():
    """Try Grenadine program.json API endpoint (used by convention guide sites).

    See https://github.com/mcdemarco/grenadine2konopas for format details.
    """
    candidates = [
        f"{BASE_URL}/program.json",
        f"{BASE_URL}/program.json?scale=2.0",
    ]
    for url in candidates:
        print(f"Trying Grenadine API: {url}", file=sys.stderr)
        resp = fetch(url)
        if resp is None:
            continue
        ct = resp.headers.get("content-type", "")
        text = resp.text.strip()
        # Accept JSON arrays OR JSON objects (the latter may wrap the list)
        if "json" not in ct and not text.startswith("[") and not text.startswith("{"):
            continue
        try:
            data = resp.json()
            if isinstance(data, list) and len(data) > 0:
                print(f"Got {len(data)} items from Grenadine API", file=sys.stderr)
                return data
            if isinstance(data, dict):
                for key in ("programme", "program", "items", "events", "data", "sessions"):
                    if key in data and isinstance(data[key], list) and data[key]:
                        print(f"Got {len(data[key])} items from Grenadine API (key={key})", file=sys.stderr)
                        return data[key]
        except Exception as e:
            print(f"JSON parse error for {url}: {e}", file=sys.stderr)
    return None


def normalise_grenadine_item(raw):
    """Normalise a Grenadine program.json item to our schema."""
    item_id = str(raw.get("id", ""))
    title = raw.get("title", "")

    # Grenadine uses datetime/endtime in ISO format
    start_time = raw.get("datetime", "") or raw.get("startTime", "")
    end_time = raw.get("endtime", "") or raw.get("endTime", "")

    # Location is in loc array: ["Room Name", ""]
    loc = raw.get("loc", [])
    location = loc[0] if isinstance(loc, list) and loc else raw.get("location", "")

    # Description may contain HTML
    desc_raw = raw.get("desc", "") or raw.get("description", "")
    if "<" in desc_raw:
        try:
            description = BeautifulSoup(desc_raw, "lxml").get_text(separator=" ", strip=True)
        except Exception:
            description = re.sub(r"<[^>]+>", " ", desc_raw).strip()
    else:
        description = desc_raw

    day = infer_day(start_time, raw.get("date", ""))

    return {
        "itemId": item_id,
        "title": title,
        "startTime": start_time,
        "endTime": end_time,
        "location": location,
        "description": description,
        "day": day,
    }


def try_json_api():
    """Try known API endpoints that Creative Cat Apps guide sites may expose."""
    candidates = [
        f"{BASE_URL}/api/events",
        f"{BASE_URL}/api/programme",
        f"{BASE_URL}/programme.json",
        f"{BASE_URL}/events.json",
        f"{BASE_URL}/data/events.json",
        f"{BASE_URL}/api/items",
        f"{BASE_URL}/api/schedule",
        f"{BASE_URL}/schedule.json",
    ]
    for url in candidates:
        print(f"Trying JSON endpoint: {url}", file=sys.stderr)
        resp = fetch(url)
        if resp is None:
            continue
        ct = resp.headers.get("content-type", "")
        text = resp.text.strip()
        if "json" not in ct and not text.startswith("[") and not text.startswith("{"):
            continue
        try:
            data = resp.json()
            if isinstance(data, list) and len(data) > 0:
                print(f"Got {len(data)} items from {url}", file=sys.stderr)
                return data
            if isinstance(data, dict):
                for key in ("events", "programme", "items", "data", "sessions", "schedule"):
                    if key in data and isinstance(data[key], list):
                        print(f"Got {len(data[key])} items from {url} (key={key})", file=sys.stderr)
                        return data[key]
        except Exception as e:
            print(f"JSON parse error for {url}: {e}", file=sys.stderr)
    return None


def extract_item_links(soup, page_url):
    """Find all programme item links from the main page."""
    links = set()

    # Look for Grenadine-style data-session-id elements first
    for el in soup.select("[data-session-id]"):
        session_id = el.get("data-session-id")
        if session_id:
            links.add(f"{page_url.rstrip('/')}/schedule/{session_id}/")
    if links:
        return sorted(links)

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

    title = ""
    for sel in ["h1", ".event-title", ".item-title", "[class*=title]"]:
        el = soup.select_one(sel)
        if el:
            title = el.get_text(strip=True)
            if title:
                break
    if not title:
        title = soup.title.get_text(strip=True) if soup.title else ""

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

    for meta in soup.find_all("meta"):
        prop = meta.get("property", "") + meta.get("name", "")
        if "start" in prop and not start_time:
            start_time = meta.get("content", "")
        if "end" in prop and not end_time:
            end_time = meta.get("content", "")

    location = ""
    for sel in [".location", ".room", ".venue", "[class*=location]", "[class*=room]",
                "[class*=venue]", ".where", "[class*=where]"]:
        el = soup.select_one(sel)
        if el:
            location = el.get_text(strip=True)
            if location:
                break

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


MIN_EVENTS_IN_LIST = 5  # Minimum items to consider a list as programme data


def _extract_json_value(text, start_pos):
    """Extract a complete JSON value (array or object) starting at start_pos.

    Uses a simple bracket/brace counter to find the matching closing delimiter,
    handling nested structures correctly.
    Returns the parsed value, or None on failure.
    """
    if start_pos >= len(text):
        return None
    opener = text[start_pos]
    if opener not in ("[", "{"):
        return None
    closer = "]" if opener == "[" else "}"
    depth = 0
    in_string = False
    escape_next = False
    for i in range(start_pos, len(text)):
        ch = text[i]
        if escape_next:
            escape_next = False
            continue
        if ch == "\\" and in_string:
            escape_next = True
            continue
        if ch == '"' and not escape_next:
            in_string = not in_string
            continue
        if in_string:
            continue
        if ch == opener:
            depth += 1
        elif ch == closer:
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(text[start_pos:i + 1])
                except (json.JSONDecodeError, ValueError):
                    return None
    return None


def try_extract_from_page_scripts(soup):
    """Try to extract programme data embedded as JSON in page script tags.

    Many SPA/React convention guide sites pre-render or embed their data as
    a JavaScript variable or inline JSON in a <script> tag.
    """
    # Variable names that commonly hold programme/event data
    var_pattern = re.compile(
        r'(?:var|let|const|window\.)\s*'
        r'(?:programme|program|events?|schedule|sessions?|items?)'
        r'\s*=\s*',
        re.IGNORECASE,
    )
    for script in soup.find_all("script"):
        text = script.get_text()
        if not text:
            continue
        for match in var_pattern.finditer(text):
            start = match.end()
            if start < len(text) and text[start] in ("[", "{"):
                data = _extract_json_value(text, start)
                if data is None:
                    continue
                if isinstance(data, list) and len(data) >= MIN_EVENTS_IN_LIST:
                    print(f"Extracted {len(data)} items from script tag", file=sys.stderr)
                    return data
                if isinstance(data, dict):
                    for key in ("programme", "program", "events", "items", "sessions"):
                        val = data.get(key)
                        if isinstance(val, list) and len(val) >= MIN_EVENTS_IN_LIST:
                            print(f"Extracted {len(val)} items from script tag (key={key})", file=sys.stderr)
                            return val
    return None


def scrape_html():
    """Scrape programme by fetching the main page and following item links."""
    print(f"Fetching main page: {BASE_URL}", file=sys.stderr)
    resp = fetch(BASE_URL)
    if resp is None:
        return None

    soup = BeautifulSoup(resp.text, "lxml")

    # Try extracting JSON data embedded in script tags first
    script_data = try_extract_from_page_scripts(soup)
    if script_data:
        events = [normalise_grenadine_item(item) for item in script_data]
        events = [e for e in events if e["title"]]
        if events:
            return events

    links = extract_item_links(soup, BASE_URL)
    print(f"Found {len(links)} item links", file=sys.stderr)

    if not links:
        print("No item links found - trying broader extraction", file=sys.stderr)
        base_host = urlparse(BASE_URL).netloc
        broader_links = set()
        for a in soup.find_all("a", href=True):
            abs_href = urljoin(BASE_URL, a["href"])
            parsed = urlparse(abs_href)
            path = parsed.path.rstrip("/")
            if (parsed.netloc == base_host and path and path != "/"
                    and not path.endswith((".css", ".js", ".png", ".jpg"))
                    and "#" not in path):
                broader_links.add(abs_href.split("?")[0].split("#")[0])
        links = sorted(broader_links)
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
        # Also try script-tag extraction on detail pages
        event = parse_detail_page(link, detail_soup)
        if event["title"]:
            events.append(event)

    return events if events else None


def main():
    # First try Grenadine program.json API (used by convention guide sites)
    grenadine_data = try_grenadine_api()
    if grenadine_data:
        events = [normalise_grenadine_item(item) for item in grenadine_data]
        events = [e for e in events if e["title"]]
        if events:
            print(json.dumps(events, ensure_ascii=False, indent=2))
            return 0

    # Try other JSON API endpoints
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
