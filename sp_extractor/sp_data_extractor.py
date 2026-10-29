import os
import json
import sys
import requests
from requests_negotiate_sspi import HttpNegotiateAuth
import pandas as pd
from unified_mapper import create_unified_excel

BASE_URL = "https://fgportal.fatima-group.com/sites/ffl"
DATA_DIR = "sp_extractor_data/extracted"

def fetch_list_items(session, list_name, max_items=1000):
    """
    Fetches items from a SharePoint list using OData REST API with pagination.
    """
    print(f"\n[*] Extractor: Fetching items from list '{list_name}' (Cap: {max_items})...")
    url = f"{BASE_URL}/_api/web/lists/getbytitle('{list_name}')/items?$top=500"
    
    headers = {"Accept": "application/json; odata=verbose"}
    auth = HttpNegotiateAuth()
    
    all_items = []
    page = 1
    
    while url and len(all_items) < max_items:
        try:
            res = session.get(url, auth=auth, headers=headers, timeout=20)
            if res.status_code == 200:
                data = res.json().get("d", {})
                results = data.get("results", [])
                all_items.extend(results)
                print(f"    Page {page}: Fetched {len(results)} items (Total: {len(all_items)})")
                
                url = data.get("__next")
                page += 1
            else:
                print(f"    [-] Error {res.status_code}: {res.text[:200]}")
                break
        except Exception as e:
            print(f"    [-] Exception during fetch: {e}")
            break
            
    print(f"[+] Total items retrieved for '{list_name}': {len(all_items)}")
    return all_items

def parse_ir_records(session, cap):
    print("\n================ Processing IR (Incident Reports) ================")
    ir_items = fetch_list_items(session, "IR01OnJob", max_items=cap)
    ir_recs = fetch_list_items(session, "IRRecommendation", max_items=cap)
    
    records = []
    
    for item in ir_items:
        records.append({
            "Module": "Incident (IR Header)",
            "Record ID / Ref": str(item.get("IRNumber") or item.get("Title") or item.get("ID")),
            "Title / Description": item.get("Description") or item.get("BriefIncidentDescription") or item.get("Title") or "",
            "Category / Severity": f"{item.get('IncidentCategory') or ''} / {item.get('InjuryClass') or ''}".strip(" /"),
            "Site / Location / Dept": f"{item.get('PlantUnitName') or ''} - {item.get('DepartmentName') or ''}".strip(" -"),
            "Target / Event Date": item.get("IncidentDate") or item.get("Created") or "",
            "Owner / Responsible": item.get("ReportedBy") or item.get("PreparedBy") or "",
            "Status": item.get("Status") or "Completed",
            "Recommendations / Action Items": item.get("BriefIncidentDescription") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/IR01OnJob/DispForm.aspx?ID={item.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": item.get("ID"), "IncidentCategory": item.get("IncidentCategory")})
        })

    for rec in ir_recs:
        records.append({
            "Module": "Incident (IR Recommendation)",
            "Record ID / Ref": str(rec.get("RecommendationNo") or f"IR-REC-{rec.get('ID')}"),
            "Title / Description": rec.get("Title") or "",
            "Category / Severity": "Incident Action Item",
            "Site / Location / Dept": rec.get("ResponsibleDepartmentName") or "",
            "Target / Event Date": rec.get("TargetDate") or rec.get("ClosureDate") or "",
            "Owner / Responsible": rec.get("ResponsiblePersonName") or rec.get("ResponsiblePerson") or rec.get("Assignee") or "",
            "Status": rec.get("Status") or "",
            "Recommendations / Action Items": rec.get("RecommendationDescription") or rec.get("Description") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/IRRecommendation/DispForm.aspx?ID={rec.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": rec.get("ID"), "IRID": rec.get("IRID")})
        })

    return records

def parse_rir_records(session, cap):
    print("\n================ Processing RIR (Reportable Incident Reports) ================")
    rir_items = fetch_list_items(session, "RIR01", max_items=cap)
    rir_recs = fetch_list_items(session, "RIRRecommendations", max_items=cap)
    
    records = []
    
    for item in rir_items:
        records.append({
            "Module": "Incident (RIR Header)",
            "Record ID / Ref": str(item.get("RIRNumber") or item.get("Title") or item.get("ID")),
            "Title / Description": item.get("Description") or item.get("Title") or "",
            "Category / Severity": "Reportable Incident",
            "Site / Location / Dept": f"{item.get('PlantUnitName') or ''} - {item.get('DepartmentName') or ''}".strip(" -"),
            "Target / Event Date": item.get("Created") or "",
            "Owner / Responsible": item.get("AuthorId") or "",
            "Status": item.get("Status") or "",
            "Recommendations / Action Items": item.get("Description") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/RIR01/DispForm.aspx?ID={item.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": item.get("ID")})
        })

    for rec in rir_recs:
        records.append({
            "Module": "Incident (RIR Recommendation)",
            "Record ID / Ref": str(rec.get("RecommendationNo") or f"RIR-REC-{rec.get('ID')}"),
            "Title / Description": rec.get("Title") or "",
            "Category / Severity": "RIR Action Item",
            "Site / Location / Dept": rec.get("ResponsibleDepartment") or "",
            "Target / Event Date": rec.get("TargetDate") or rec.get("ClosureDate") or "",
            "Owner / Responsible": rec.get("ResponsiblePerson") or rec.get("Assignee") or "",
            "Status": rec.get("Status") or "",
            "Recommendations / Action Items": rec.get("RecommendationDescription") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/RIRRecommendations/DispForm.aspx?ID={rec.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": rec.get("ID"), "RIRID": rec.get("RIRID")})
        })

    return records

def parse_msa_records(session, cap):
    print("\n================ Processing MSA (Management Safety Audit) ================")
    msa_items = fetch_list_items(session, "MSA", max_items=cap)
    msa_recs = fetch_list_items(session, "MSARecommendation", max_items=cap)
    
    records = []
    
    for item in msa_items:
        records.append({
            "Module": "MSA (Audit Header)",
            "Record ID / Ref": str(item.get("Title") or item.get("ID")),
            "Title / Description": item.get("AreaAuditedActual") or item.get("Title") or "",
            "Category / Severity": "Management Safety Audit",
            "Site / Location / Dept": item.get("DepartmentName") or "",
            "Target / Event Date": item.get("MSADate") or item.get("Created") or "",
            "Owner / Responsible": item.get("AuditedByName") or "",
            "Status": "Completed",
            "Recommendations / Action Items": item.get("PositivePoints") or item.get("AreaOfImprovement") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/MSA/DispForm.aspx?ID={item.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": item.get("ID")})
        })

    for rec in msa_recs:
        records.append({
            "Module": "MSA (Recommendation)",
            "Record ID / Ref": str(rec.get("RecommendationNo") or f"MSA-REC-{rec.get('ID')}"),
            "Title / Description": rec.get("Title") or "",
            "Category / Severity": f"{rec.get('ObservationCategory') or ''} - {rec.get('ObservationSubcategory') or ''}".strip(" -"),
            "Site / Location / Dept": f"{rec.get('MSAAreaAudited') or ''} - {rec.get('ResponsibleDepartmentName') or ''}".strip(" -"),
            "Target / Event Date": rec.get("TargetDate") or rec.get("ClosureDate") or "",
            "Owner / Responsible": rec.get("ResponsiblePersonName") or rec.get("ResponsiblePerson") or rec.get("Assignee") or "",
            "Status": rec.get("Status") or "",
            "Recommendations / Action Items": rec.get("RecommendationDescription") or rec.get("MSARecommendationDescription") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/MSARecommendation/DispForm.aspx?ID={rec.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": rec.get("ID"), "MSAID": rec.get("MSAID")})
        })

    return records

def parse_eshe_mom_records(session, cap):
    print("\n================ Processing EHSEC-MOM (Minutes of Meeting) ================")
    mom_items = fetch_list_items(session, "ESHE-MOM", max_items=cap)
    mom_recs = fetch_list_items(session, "ESHE-MOMRecommendation", max_items=cap)
    
    records = []
    
    for item in mom_items:
        records.append({
            "Module": "EHSEC-MOM (Meeting)",
            "Record ID / Ref": str(item.get("MeetingNo") or item.get("Title") or item.get("ID")),
            "Title / Description": item.get("Title") or item.get("MeetingAgenda") or "",
            "Category / Severity": "Executive Meeting",
            "Site / Location / Dept": item.get("Venue") or "",
            "Target / Event Date": item.get("MeetingDate") or "",
            "Owner / Responsible": item.get("InChair") or item.get("PreparedBy") or "",
            "Status": item.get("Status") or "",
            "Recommendations / Action Items": item.get("MeetingAgenda") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/ESHEMOM/DispForm.aspx?ID={item.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": item.get("ID")})
        })

    for rec in mom_recs:
        records.append({
            "Module": "EHSEC-MOM (Action Item)",
            "Record ID / Ref": str(rec.get("RecommendationNo") or f"MOM-REC-{rec.get('ID')}"),
            "Title / Description": rec.get("Title") or "",
            "Category / Severity": f"{rec.get('EHSECategory') or ''} / Risk: {rec.get('RiskLevel') or ''}".strip(" /"),
            "Site / Location / Dept": rec.get("ResponsibleDepartment") or "",
            "Target / Event Date": rec.get("TargetDate") or rec.get("ClosureDate") or "",
            "Owner / Responsible": rec.get("ResponsiblePerson") or rec.get("Assignee") or "",
            "Status": rec.get("Status") or "",
            "Recommendations / Action Items": rec.get("RecommendationDescription") or "",
            "Source Document / URL": f"{BASE_URL}/Lists/ESHEMOMRecommendation/DispForm.aspx?ID={rec.get('ID')}",
            "Raw Fields JSON": json.dumps({"ID": rec.get("ID"), "MeetingNo": rec.get("MeetingNo")})
        })

    return records

def run_extraction(cap=500):
    os.makedirs(DATA_DIR, exist_ok=True)
    session = requests.Session()
    session.verify = False
    requests.packages.urllib3.disable_warnings()

    print(f"[*] Starting SharePoint Extraction (Items cap per list: {cap})...")

    ir_records = parse_ir_records(session, cap)
    rir_records = parse_rir_records(session, cap)
    msa_records = parse_msa_records(session, cap)
    mom_records = parse_eshe_mom_records(session, cap)

    all_module_datasets = {
        "Incident Reports (IR)": ir_records,
        "Reportable Incidents (RIR)": rir_records,
        "MSA Audits": msa_records,
        "EHSEC-MOM": mom_records
    }

    master_file = create_unified_excel(all_module_datasets, "Unified_EHS_Master_Sheet.xlsx")
    print(f"\n[***] EXTRACTION AND UNIFICATION COMPLETE! Master File: {master_file}")
    return master_file

if __name__ == "__main__":
    items_cap = 500
    if len(sys.argv) > 1:
        try:
            items_cap = int(sys.argv[1])
        except ValueError:
            pass
    run_extraction(cap=items_cap)
