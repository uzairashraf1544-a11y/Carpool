import os
import json
import asyncio
from playwright.async_api import async_playwright

BASE_URL = "https://fgportal.fatima-group.com/sites/ffl"
TARGET_URL = f"{BASE_URL}/_layouts/15/viewlsts.aspx"
REST_LISTS_URL = f"{BASE_URL}/_api/web/lists"

async def run_playwright_crawler():
    os.makedirs("sp_extractor_data", exist_ok=True)
    async with async_playwright() as p:
        print("[*] Launching Chromium browser (headless mode)...")
        # Using default credentials / system context
        browser = await p.chromium.launch(headless=False)
        context = await browser.new_context(ignore_https_errors=True)
        page = await context.new_page()

        print(f"[*] Navigating to: {TARGET_URL}")
        try:
            response = await page.goto(TARGET_URL, wait_until="networkidle", timeout=30000)
            print(f"[+] Page loaded. Status: {response.status if response else 'Unknown'}")
        except Exception as e:
            print(f"[!] Navigation error or manual sign-in required: {e}")

        await page.wait_for_timeout(3000)
        title = await page.title()
        print(f"[+] Page Title: {title}")

        # Check if auth redirect occurred
        current_url = page.url
        print(f"[+] Current URL: {current_url}")

        # Save screenshot for verification
        screenshot_path = "sp_extractor_data/page_screenshot.png"
        await page.screenshot(path=screenshot_path)
        print(f"[+] Saved screenshot to: {screenshot_path}")

        # Fetch lists using in-page fetch with authenticated session
        print("[*] Querying SharePoint REST API via browser context...")
        js_code = """
        async () => {
            try {
                const res = await fetch('/sites/ffl/_api/web/lists', {
                    headers: {'Accept': 'application/json; odata=verbose'}
                });
                if (res.ok) {
                    const data = await res.json();
                    return data.d.results;
                } else {
                    return { error: res.status, text: await res.text() };
                }
            } catch (err) {
                return { error: err.toString() };
            }
        }
        """
        lists_data = await page.evaluate(js_code)
        
        if isinstance(lists_data, list):
            print(f"[+] Found {len(lists_data)} total lists/libraries via browser fetch:")
            matched_lists = []
            targets = ["incident", "ir", "rir", "msa", "ram", "ecs", "ehs", "mom", "recommendation"]
            
            for item in lists_data:
                ltitle = item.get("Title", "")
                template = item.get("BaseTemplate", "")
                count = item.get("ItemCount", 0)
                url = item.get("RootFolder", {}).get("ServerRelativeUrl", "")
                
                ltitle_lower = ltitle.lower()
                is_target = any(t in ltitle_lower for t in targets)
                
                matched_lists.append({
                    "title": ltitle,
                    "template": template,
                    "count": count,
                    "url": url,
                    "is_target": is_target
                })
                
                flag = ">>> MATCH <<<" if is_target else "             "
                print(f"  {flag} {ltitle:<40} (Items: {count}, Template: {template})")
                
            with open("sp_extractor_data/sp_lists_inventory.json", "w", encoding="utf-8") as f:
                json.dump(matched_lists, f, indent=2)
            print("[+] Saved inventory to sp_extractor_data/sp_lists_inventory.json")
        else:
            print(f"[-] REST API response error: {lists_data}")

        await browser.close()

if __name__ == "__main__":
    asyncio.run(run_playwright_crawler())
