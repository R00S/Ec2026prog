#!/usr/bin/env python3
"""
Eastercon 2026 programme scraper.

Usage:
    python scrape.py [BASE_URL]

Outputs JSON array of programme items to stdout.
Exits with code 0 on success, 1 on failure.

Supports Grenadine-powered event sites by trying:
  1. ICS calendar download from alt-formats page
  2. PDF download from alt-formats page
  3. Grenadine program.json API
  4. Other JSON API endpoints
  5. HTML scraping (fallback)
"""

import io
import json
import re
import sys
from datetime import datetime, timedelta
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


def _log_non_json(url, resp):
    """Log what a URL actually returned when it wasn't usable JSON."""
    ct = resp.headers.get("content-type", "(no content-type)")
    preview = resp.text.strip()[:120].replace("\n", " ")
    print(f"  {url} -> {resp.status_code} [{ct}] {preview!r}", file=sys.stderr)


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
            _log_non_json(url, resp)
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


def normalise_planz_item(raw):
    """Normalise a PlanZ/Zambia KonOpas item to our schema.

    PlanZ items use separate ``date`` (YYYY-MM-DD) and ``time`` (HH:MM)
    fields instead of a combined ISO datetime, and ``mins`` for duration.
    """
    item_id = str(raw.get("id", ""))
    title = raw.get("title", "")

    date = raw.get("date", "")
    time_str = raw.get("time", "")
    mins = raw.get("mins", 0)

    start_time = ""
    end_time = ""
    if date and time_str:
        start_time = f"{date}T{time_str}:00"
        try:
            start_dt = datetime.strptime(start_time, "%Y-%m-%dT%H:%M:%S")
            end_dt = start_dt + timedelta(minutes=int(mins))
            end_time = end_dt.strftime("%Y-%m-%dT%H:%M:%S")
        except (ValueError, TypeError):
            pass
    elif raw.get("datetime"):
        start_time = str(raw["datetime"])

    loc = raw.get("loc", [])
    location = loc[0] if isinstance(loc, list) and loc else str(raw.get("loc", ""))

    desc_raw = raw.get("desc", "") or raw.get("description", "")
    if "<" in str(desc_raw):
        try:
            description = BeautifulSoup(str(desc_raw), "lxml").get_text(separator=" ", strip=True)
        except Exception:
            description = re.sub(r"<[^>]+>", " ", str(desc_raw)).strip()
    else:
        description = str(desc_raw)

    day = infer_day(start_time, date)

    return {
        "itemId": item_id,
        "title": title,
        "startTime": start_time,
        "endTime": end_time,
        "location": location,
        "description": description,
        "day": day,
    }


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
            _log_non_json(url, resp)
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


def _parse_planz_response(text, url):
    """Try to extract a programme list from a PlanZ/KonOpas response.

    PlanZ can return data in three formats:
      1. JSON array:    [{"id": …}, …]
      2. JSON object:   {"program": […], "people": […]}
      3. JS variables:  var program = […]; var people = […];
    Returns the programme list, or None.
    """
    text = text.strip()

    # Format 3: JS variable assignment
    js_match = re.search(r'var\s+program\s*=\s*', text)
    if js_match:
        start = js_match.end()
        if start < len(text) and text[start] in ("[", "{"):
            data = _extract_json_value(text, start)
            if isinstance(data, list) and len(data) > 0:
                print(f"  Got {len(data)} items (JS var format) from {url}", file=sys.stderr)
                return data

    # Format 1 & 2: JSON
    if not (text.startswith("[") or text.startswith("{")):
        return None
    try:
        data = json.loads(text)
        if isinstance(data, list) and len(data) > 0:
            print(f"  Got {len(data)} items (JSON array) from {url}", file=sys.stderr)
            return data
        if isinstance(data, dict):
            for key in ("program", "programme", "events", "items", "sessions"):
                val = data.get(key)
                if isinstance(val, list) and len(val) > 0:
                    print(f"  Got {len(val)} items (JSON key={key}) from {url}", file=sys.stderr)
                    return val
    except (json.JSONDecodeError, ValueError):
        pass
    return None


def try_planz_api():
    """Try PlanZ/Zambia KonOpas export endpoint (used by Eastercon 2026)."""
    candidates = [
        f"{BASE_URL}/konOpas.php",
        f"{BASE_URL}/webpages/konOpas.php",
        f"{BASE_URL}/cap/program.js",
        f"{BASE_URL}/data/program.js",
        f"{BASE_URL}/program.js",
    ]
    for url in candidates:
        print(f"Trying PlanZ endpoint: {url}", file=sys.stderr)
        resp = fetch(url)
        if resp is None:
            continue
        items = _parse_planz_response(resp.text, url)
        if items:
            return items
    return None


def try_js_bundle_config():
    """Find programme data URL baked into the React/SPA JS bundle and fetch programme.

    ConClár and similar React SPAs compile their config (including the data URL)
    into the JS bundle.  We fetch the HTML, collect every JS file reference, search
    each bundle for known variable names, then fetch the actual programme data.

    Variable names tried (in order of likelihood):
      PROGRAM_DATA_URL, REACT_APP_DATA_URL, PROGRAM_URL, DATA_URL,
      programDataUrl, dataUrl, scheduleUrl, programUrl
    """
    resp = fetch(BASE_URL)
    if resp is None:
        return None

    soup = BeautifulSoup(resp.text, "lxml")
    print(f"Page title: {soup.title.get_text(strip=True) if soup.title else '(none)'}", file=sys.stderr)

    # Collect candidate JS bundle URLs from <script src> and <link rel=modulepreload>
    script_srcs = []
    for script in soup.find_all("script", src=True):
        src = script.get("src", "")
        abs_src = urljoin(BASE_URL, src)
        if urlparse(abs_src).netloc != urlparse(BASE_URL).netloc:
            continue
        if any(p in abs_src for p in ("static/js", "main.", "chunk.js", ".bundle.js", "app.", "index.")):
            script_srcs.insert(0, abs_src)
        else:
            script_srcs.append(abs_src)
    for link in soup.find_all("link", rel=True):
        rel = link.get("rel", [])
        if isinstance(rel, list):
            rel = " ".join(rel)
        if "modulepreload" in rel or "preload" in rel:
            href = link.get("href", "")
            if href.endswith(".js"):
                abs_href = urljoin(BASE_URL, href)
                if urlparse(abs_href).netloc == urlparse(BASE_URL).netloc:
                    script_srcs.append(abs_href)

    print(f"JS bundle candidates: {len(script_srcs)}", file=sys.stderr)
    for s in script_srcs[:10]:
        print(f"  {s}", file=sys.stderr)

    # Also scan inline <script> blocks
    all_scripts_text = " ".join(
        s.get_text() for s in soup.find_all("script") if not s.get("src")
    )

    # Pattern: any of the known variable names followed by a URL value
    data_url_re = re.compile(
        r'(?:PROGRAM_DATA_URL|REACT_APP_DATA_URL|PROGRAM_URL|DATA_URL'
        r'|programDataUrl|dataUrl|scheduleUrl|programUrl)'
        r'["\']?\s*[:=]\s*["\']([^"\']{4,})["\']',
        re.IGNORECASE,
    )

    def _find_and_fetch_data_url(js_text, source_label):
        """Search js_text for a data URL and fetch it."""
        match = data_url_re.search(js_text)
        if not match:
            return None
        data_url = match.group(1)
        if not data_url.startswith("http"):
            data_url = urljoin(BASE_URL, data_url)
        print(f"  Found data URL in {source_label}: {data_url}", file=sys.stderr)
        data_resp = fetch(data_url)
        if data_resp is None:
            return None
        return _parse_planz_response(data_resp.text, data_url)

    # Try inline scripts first (cheap)
    result = _find_and_fetch_data_url(all_scripts_text, "inline scripts")
    if result:
        return result

    # Search JS bundles (up to 10 to be thorough)
    for js_url in script_srcs[:10]:
        print(f"Searching JS bundle: {js_url}", file=sys.stderr)
        js_resp = fetch(js_url)
        if js_resp is None:
            continue
        # Check for any of the known patterns before downloading fully
        found = data_url_re.search(js_resp.text)
        if not found:
            print(f"  No data URL pattern found in bundle", file=sys.stderr)
            continue
        result = _find_and_fetch_data_url(js_resp.text, js_url)
        if result:
            return result

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


MIN_EVENTS_IN_LIST = 5   # Minimum items to consider a list as programme data
EVENT_ID_START = 2000    # Base for auto-generated event IDs (ICS/PDF paths)

# ── BST timezone for Eastercon 2026 (Edinburgh, April) ─────────────────
try:
    import pytz as _pytz
    _BST = _pytz.timezone("Europe/London")
except ImportError:
    _BST = None


def _to_naive_local(dt):
    """Convert a possibly timezone-aware datetime to a naive local datetime.

    Uses Europe/London (BST, UTC+1 in April) for the conversion.
    """
    if dt is None:
        return None
    if not hasattr(dt, "tzinfo"):
        return dt  # already a date, not datetime
    if dt.tzinfo is None:
        return dt
    if _BST is not None:
        return dt.astimezone(_BST).replace(tzinfo=None)
    # Fallback: manually add 1 hour for BST
    from datetime import timedelta
    return dt.replace(tzinfo=None) + timedelta(hours=1)


def _discover_alt_format_links():
    """Fetch the alt-formats page and return (pdf_urls, ics_urls)."""
    pdf_urls = []
    ics_urls = []
    alt_resp = fetch(f"{BASE_URL}/alt-formats")
    if alt_resp is None:
        return pdf_urls, ics_urls
    soup = BeautifulSoup(alt_resp.text, "lxml")
    for a in soup.find_all("a", href=True):
        href = a["href"].strip()
        link_text = a.get_text(strip=True).lower()
        abs_href = urljoin(BASE_URL, href)
        if ".pdf" in href.lower() or "pdf" in link_text:
            if abs_href not in pdf_urls:
                pdf_urls.append(abs_href)
        if ".ics" in href.lower() or "ical" in href.lower() or "icalendar" in link_text or "calendar" in link_text:
            if abs_href not in ics_urls:
                ics_urls.append(abs_href)
    return pdf_urls, ics_urls


def try_ics():
    """Try to fetch and parse an ICS calendar file."""
    try:
        from icalendar import Calendar
    except ImportError:
        print("icalendar not installed, skipping ICS", file=sys.stderr)
        return None

    pdf_urls, ics_urls = _discover_alt_format_links()
    # Append common fallback paths
    for path in ("/schedule.ics", "/programme.ics", "/calendar.ics", "/events.ics"):
        url = BASE_URL + path
        if url not in ics_urls:
            ics_urls.append(url)

    for url in ics_urls:
        print(f"Trying ICS: {url}", file=sys.stderr)
        resp = fetch(url)
        if resp is None:
            continue
        raw = resp.content
        text_preview = raw[:200].decode("utf-8", errors="replace")
        if "BEGIN:VCALENDAR" not in text_preview and "BEGIN:VEVENT" not in text_preview:
            print(f"  Not an ICS file: {url}", file=sys.stderr)
            continue
        try:
            cal = Calendar.from_ical(raw)
            events = []
            for component in cal.walk():
                if component.name != "VEVENT":
                    continue
                uid = str(component.get("UID", ""))
                item_id = re.sub(r"@.*$", "", uid) or str(EVENT_ID_START + len(events))
                title = str(component.get("SUMMARY", "")).strip()
                if not title:
                    continue
                location = str(component.get("LOCATION", "")).strip()
                desc = str(component.get("DESCRIPTION", "")).strip()

                dtstart = component.get("DTSTART")
                dtend = component.get("DTEND")
                start_dt = _to_naive_local(dtstart.dt) if dtstart else None
                end_dt = _to_naive_local(dtend.dt) if dtend else None

                # Handle all-day events (date, not datetime)
                if start_dt is not None and not hasattr(start_dt, "hour"):
                    from datetime import datetime as _dt
                    start_dt = _dt(start_dt.year, start_dt.month, start_dt.day, 0, 0, 0)
                if end_dt is not None and not hasattr(end_dt, "hour"):
                    from datetime import datetime as _dt
                    end_dt = _dt(end_dt.year, end_dt.month, end_dt.day, 0, 0, 0)

                start_str = start_dt.strftime("%Y-%m-%dT%H:%M:%S") if start_dt else ""
                end_str = end_dt.strftime("%Y-%m-%dT%H:%M:%S") if end_dt else ""
                day = infer_day(start_str, "")

                events.append({
                    "itemId": item_id,
                    "title": title,
                    "startTime": start_str,
                    "endTime": end_str,
                    "location": location,
                    "description": desc,
                    "day": day,
                })

            if events:
                print(f"Got {len(events)} events from ICS: {url}", file=sys.stderr)
                return events
        except Exception as e:
            print(f"ICS parse error for {url}: {e}", file=sys.stderr)
    return None


def _parse_grenadine_pdf_bytes(pdf_bytes):
    """Parse a Grenadine-style programme PDF using pdfplumber.

    Grenadine PDFs are typically a list of events sorted by day and time.
    Each event occupies one or more lines: time range, title, room, description.
    """
    import pdfplumber

    # Regexes for parsing
    time_re = re.compile(
        r'(\d{1,2}:\d{2})\s*(?:am|pm)?\s*[-–]\s*(\d{1,2}:\d{2})\s*(?:am|pm)?',
        re.IGNORECASE,
    )
    day_re = re.compile(r'\b(Friday|Saturday|Sunday|Monday)\b', re.IGNORECASE)
    day_map = {
        "Friday": "2026-04-03",
        "Saturday": "2026-04-04",
        "Sunday": "2026-04-05",
        "Monday": "2026-04-06",
    }

    events = []
    current_day = "Friday"
    event_id_counter = EVENT_ID_START

    with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
        for page in pdf.pages:
            text = page.extract_text(x_tolerance=3, y_tolerance=3) or ""
            lines = [ln.strip() for ln in text.splitlines() if ln.strip()]

            i = 0
            while i < len(lines):
                line = lines[i]

                # Detect day headers
                day_match = day_re.search(line)
                if day_match and len(line) < 60:
                    current_day = day_match.group(1).capitalize()
                    i += 1
                    continue

                # Detect time ranges — each starts a new event
                time_match = time_re.search(line)
                if time_match:
                    start_hhmm = time_match.group(1)
                    end_hhmm = time_match.group(2)
                    date_str = day_map.get(current_day, "2026-04-03")

                    def to_iso(hhmm, date):
                        """Convert HH:MM to ISO datetime string."""
                        try:
                            h, m = map(int, hhmm.split(":"))
                            return f"{date}T{h:02d}:{m:02d}:00"
                        except ValueError:
                            return ""

                    start_iso = to_iso(start_hhmm, date_str)
                    end_iso = to_iso(end_hhmm, date_str)

                    # Title is on the same line (after the time) or the next non-time line
                    title_part = line[time_match.end():].strip().lstrip("-–").strip()
                    if not title_part and i + 1 < len(lines) and not time_re.search(lines[i + 1]):
                        i += 1
                        title_part = lines[i]

                    # Location heuristic: the next short line that's not a time range
                    location = ""
                    if (i + 1 < len(lines)
                            and not time_re.search(lines[i + 1])
                            and not day_re.match(lines[i + 1])
                            and len(lines[i + 1]) < 60):
                        i += 1
                        location = lines[i]

                    # Collect description lines until next time/day
                    desc_lines = []
                    while (i + 1 < len(lines)
                           and not time_re.search(lines[i + 1])
                           and not day_re.match(lines[i + 1])):
                        i += 1
                        desc_lines.append(lines[i])

                    description = " ".join(desc_lines)

                    if title_part:
                        events.append({
                            "itemId": str(event_id_counter),
                            "title": title_part,
                            "startTime": start_iso,
                            "endTime": end_iso,
                            "location": location,
                            "description": description,
                            "day": current_day,
                        })
                        event_id_counter += 1

                i += 1

    return events


def try_pdf():
    """Try to fetch and parse a PDF programme from the alt-formats page."""
    try:
        import pdfplumber
    except ImportError:
        print("pdfplumber not installed, skipping PDF", file=sys.stderr)
        return None

    pdf_urls, _ics = _discover_alt_format_links()
    # Append common fallback paths
    for path in ("/programme.pdf", "/guide.pdf", "/full-programme.pdf", "/schedule.pdf"):
        url = BASE_URL + path
        if url not in pdf_urls:
            pdf_urls.append(url)

    for url in pdf_urls:
        print(f"Trying PDF: {url}", file=sys.stderr)
        resp = fetch(url)
        if resp is None:
            continue
        # Verify it really is a PDF
        if not (resp.content[:4] == b"%PDF" or "pdf" in resp.headers.get("content-type", "")):
            print(f"  Not a PDF: {url}", file=sys.stderr)
            continue
        try:
            events = _parse_grenadine_pdf_bytes(resp.content)
            if events:
                print(f"Got {len(events)} events from PDF: {url}", file=sys.stderr)
                return events
            print(f"  PDF parsed but no events found: {url}", file=sys.stderr)
        except Exception as e:
            print(f"PDF parse error for {url}: {e}", file=sys.stderr)
    return None


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
    # Try ICS calendar first — most structured and reliable format
    ics_events = try_ics()
    if ics_events:
        print(json.dumps(ics_events, ensure_ascii=False, indent=2))
        return 0

    # Try PDF from alt-formats page
    pdf_events = try_pdf()
    if pdf_events:
        print(json.dumps(pdf_events, ensure_ascii=False, indent=2))
        return 0

    # Try PlanZ/Zambia KonOpas endpoint (used by Eastercon 2026)
    planz_data = try_planz_api()
    if planz_data:
        events = [normalise_planz_item(item) for item in planz_data]
        events = [e for e in events if e["title"]]
        if events:
            print(json.dumps(events, ensure_ascii=False, indent=2))
            return 0

    # Try Grenadine program.json API (used by convention guide sites)
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

    # Try extracting PROGRAM_DATA_URL from the React JS bundle (ConClár support)
    js_config_data = try_js_bundle_config()
    if js_config_data:
        events = [normalise_planz_item(item) for item in js_config_data]
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
