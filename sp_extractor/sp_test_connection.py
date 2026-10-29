import sys
import json
import os
import requests

try:
    from requests_negotiate_sspi import HttpNegotiateAuth
    HAS_SSPI = True
except ImportError:
    HAS_SSPI = False

try:
    from requests_ntlm import HttpNtlmAuth
    HAS_NTLM = True
except ImportError:
    HAS_NTLM = False

BASE_URL = "https://fgportal.fatima-group.com/sites/ffl"
VIEW_LSTS_URL = f"{BASE_URL}/_layouts/15/viewlsts.aspx"
REST_LISTS_URL = f"{BASE_URL}/_api/web/lists"

def test_connection():
    print(f"[*] Testing connection to: {BASE_URL}")
    print(f"[*] SSPI Auth Available: {HAS_SSPI}")
    print(f"[*] NTLM Auth Available: {HAS_NTLM}")

    session = requests.Session()
    session.verify = False  # Ignore self-signed cert warnings if any
    requests.packages.urllib3.disable_warnings()

    auth_methods = []
    if HAS_SSPI:
        auth_methods.append(("SSPI (Windows Integrated)", HttpNegotiateAuth()))
    if HAS_NTLM:
        auth_methods.append(("Default NTLM", None))

    success = False
    active_auth = None

    for name, auth_obj in auth_methods:
        print(f"\n[+] Trying auth method: {name}...")
        try:
            res = session.get(VIEW_LSTS_URL, auth=auth_obj, timeout=15)
            print(f"    Status Code: {res.status_code}")
            if res.status_code == 200:
                print(f"    SUCCESS! Connected using {name}")
                success = True
                active_auth = auth_obj
                break
            elif res.status_code == 401:
                print(f"    401 Unauthorized with {name}")
        except Exception as e:
            print(f"    Error with {name}: {e}")

    if not success:
        print("\n[-] Unauthenticated request attempt...")
        try:
            res = session.get(VIEW_LSTS_URL, timeout=15)
            print(f"    Status Code: {res.status_code}")
            if res.status_code == 200:
                success = True
        except Exception as e:
            print(f"    Error: {e}")

    if success:
        print("\n[*] Enumerating SharePoint Lists via REST API...")
        headers = {"Accept": "application/json; odata=verbose"}
        try:
            rest_res = session.get(REST_LISTS_URL, auth=active_auth, headers=headers, timeout=15)
            if rest_res.status_code == 200:
                data = rest_res.json()
                results = data.get("d", {}).get("results", [])
                print(f"[+] Found {len(results)} total lists/libraries:")
                
                matched_lists = []
                for item in results:
                    title = item.get("Title", "")
                    base_template = item.get("BaseTemplate", "")
                    item_count = item.get("ItemCount", 0)
                    server_relative_url = item.get("RootFolder", {}).get("ServerRelativeUrl", "")
                    
                    # Highlight target modules
                    title_lower = title.lower()
                    targets = ["incident", "ir", "rir", "msa", "ram", "ecs", "ehs", "mom", "recommendation"]
                    is_target = any(t in title_lower for t in targets)
                    
                    entry = {
                        "title": title,
                        "template": base_template,
                        "count": item_count,
                        "url": server_relative_url,
                        "is_target": is_target
                    }
                    matched_lists.append(entry)
                    
                    flag = ">>> MATCH <<<" if is_target else "             "
                    print(f"  {flag} {title:<40} (Items: {item_count}, Template: {base_template})")

                # Save list output
                os.makedirs("sp_extractor_data", exist_ok=True)
                with open("sp_extractor_data/sp_lists_inventory.json", "w", encoding="utf-8") as f:
                    json.dump(matched_lists, f, indent=2)
                print("\n[+] Saved inventory to sp_extractor_data/sp_lists_inventory.json")

            else:
                print(f"[-] REST API returned status: {rest_res.status_code}")
                print(rest_res.text[:500])
        except Exception as e:
            print(f"[-] Error querying REST API: {e}")
    else:
        print("\n[-] Could not authenticate automatically. Interactive or credential configuration needed.")

if __name__ == "__main__":
    test_connection()
