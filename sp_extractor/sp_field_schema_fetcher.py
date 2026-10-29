import json
import os
import requests
from requests_negotiate_sspi import HttpNegotiateAuth

BASE_URL = "https://fgportal.fatima-group.com/sites/ffl"

TARGET_LISTS = [
    "IR01OnJob",
    "IRRecommendation",
    "RIR01",
    "RIRRecommendations",
    "MSA",
    "MSARecommendation",
    "ESHE-MOM",
    "ESHE-MOMRecommendation"
]

def fetch_list_schemas():
    session = requests.Session()
    session.verify = False
    requests.packages.urllib3.disable_warnings()

    auth = HttpNegotiateAuth()
    os.makedirs("sp_extractor_data/schemas", exist_ok=True)

    headers = {"Accept": "application/json; odata=verbose"}

    schema_summary = {}

    for list_name in TARGET_LISTS:
        print(f"\n[*] Fetching schema for list: {list_name}...")
        url = f"{BASE_URL}/_api/web/lists/getbytitle('{list_name}')/fields?$filter=Hidden eq false and ReadOnlyField eq false"
        try:
            res = session.get(url, auth=auth, headers=headers, timeout=15)
            if res.status_code == 200:
                fields = res.json().get("d", {}).get("results", [])
                field_list = []
                for f in fields:
                    field_list.append({
                        "InternalName": f.get("InternalName"),
                        "Title": f.get("Title"),
                        "TypeAsString": f.get("TypeAsString")
                    })
                schema_summary[list_name] = field_list
                print(f"[+] Retrieved {len(field_list)} non-hidden fields.")
            else:
                print(f"[-] Status {res.status_code}: {res.text[:200]}")
        except Exception as e:
            print(f"[-] Error: {e}")

    with open("sp_extractor_data/schemas/target_list_schemas.json", "w", encoding="utf-8") as f:
        json.dump(schema_summary, f, indent=2)
    print("\n[+] Saved target list schemas to sp_extractor_data/schemas/target_list_schemas.json")

if __name__ == "__main__":
    fetch_list_schemas()
