import os
import json
import pandas as pd
import openpyxl
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils.dataframe import dataframe_to_rows

OUTPUT_DIR = "sp_extractor_output"

UNIFIED_COLUMNS = [
    "Module",                # e.g., Incident (IR), Incident (RIR), MSA, RAMS, ECS, EHSEC-MOM
    "Record ID / Ref",       # Identifier or File Name
    "Title / Description",   # Incident/Audit/Meeting description
    "Category / Severity",   # Risk rating / incident type / classification
    "Site / Location / Dept",# Plant or department
    "Target / Event Date",   # Incident date or meeting date
    "Owner / Responsible",   # Action owner / assigned person
    "Status",                # Open, Closed, In-Progress, Pending
    "Recommendations / Action Items", # Detailed recommendations or action items
    "Source Document / URL", # Direct link or source filename
    "Raw Fields JSON"        # Backup JSON string of unmapped fields
]

def create_unified_excel(module_datasets, output_filename="Unified_EHS_Master_Sheet.xlsx"):
    """
    Combines datasets from multiple SharePoint modules into a formatted master Excel sheet.
    module_datasets: dict of {module_name: list of dicts or DataFrame}
    """
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    out_path = os.path.join(OUTPUT_DIR, output_filename)
    
    wb = openpyxl.Workbook()
    # Remove default sheet
    default_sheet = wb.active
    
    all_records = []
    
    # 1. Process each module sheet & collect master records
    for module_name, data in module_datasets.items():
        if isinstance(data, list):
            df = pd.DataFrame(data)
        else:
            df = data
            
        if df.empty:
            continue
            
        # Ensure standard columns exist
        for col in UNIFIED_COLUMNS:
            if col not in df.columns:
                df[col] = ""
                
        df_standard = df[UNIFIED_COLUMNS]
        all_records.append(df_standard)
        
        # Create module-specific tab
        ws = wb.create_sheet(title=module_name[:31]) # Excel title max 31 chars
        format_worksheet(ws, df_standard, f"{module_name} Records")

    # 2. Create Master Summary Tab
    if all_records:
        master_df = pd.concat(all_records, ignore_index=True)
    else:
        master_df = pd.DataFrame(columns=UNIFIED_COLUMNS)
        
    master_ws = wb.create_sheet(title="Unified Master Sheet", index=0)
    format_worksheet(master_ws, master_df, "Unified EHS Master Data Sheet", is_master=True)
    
    wb.remove(default_sheet)
    wb.save(out_path)
    print(f"\n[+] Unified Master Excel Sheet successfully generated: {out_path}")
    return out_path

def format_worksheet(ws, df, header_title, is_master=False):
    """
    Applies professional styling to openpyxl worksheet.
    """
    # Title Block
    ws.merge_cells(start_row=1, start_column=1, end_row=1, end_column=len(UNIFIED_COLUMNS))
    title_cell = ws.cell(row=1, column=1, value=header_title)
    title_cell.font = Font(name="Calibri", size=16, bold=True, color="FFFFFF")
    title_cell.fill = PatternFill(start_color="1F4E78" if is_master else "2F5597", fill_type="solid")
    title_cell.alignment = Alignment(horizontal="center", vertical="center")
    ws.row_dimensions[1].height = 35

    # Table Header Row
    headers = list(df.columns)
    for col_idx, h in enumerate(headers, 1):
        cell = ws.cell(row=2, column=col_idx, value=h)
        cell.font = Font(name="Calibri", size=11, bold=True, color="FFFFFF")
        cell.fill = PatternFill(start_color="366092" if is_master else "595959", fill_type="solid")
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = Border(bottom=Side(style='medium', color='000000'))
    ws.row_dimensions[2].height = 28

    # Data Rows
    thin_border = Border(
        left=Side(style='thin', color='D9D9D9'),
        right=Side(style='thin', color='D9D9D9'),
        top=Side(style='thin', color='D9D9D9'),
        bottom=Side(style='thin', color='D9D9D9')
    )

    zebra_fill = PatternFill(start_color="F2F2F2", fill_type="solid")

    for row_idx, row_data in enumerate(df.values, 3):
        for col_idx, val in enumerate(row_data, 1):
            cell = ws.cell(row=row_idx, column=col_idx, value=str(val) if pd.notna(val) else "")
            cell.font = Font(name="Calibri", size=10)
            cell.border = thin_border
            cell.alignment = Alignment(vertical="top", wrap_text=True)
            if row_idx % 2 == 0:
                cell.fill = zebra_fill
        ws.row_dimensions[row_idx].height = 22

    # Column Width Autofit
    col_widths = {
        "Module": 18,
        "Record ID / Ref": 18,
        "Title / Description": 35,
        "Category / Severity": 18,
        "Site / Location / Dept": 20,
        "Target / Event Date": 16,
        "Owner / Responsible": 20,
        "Status": 14,
        "Recommendations / Action Items": 45,
        "Source Document / URL": 30,
        "Raw Fields JSON": 20
    }

    for col_idx, h in enumerate(headers, 1):
        col_letter = openpyxl.utils.get_column_letter(col_idx)
        ws.column_dimensions[col_letter].width = col_widths.get(h, 20)

if __name__ == "__main__":
    # Test sample data generation
    sample_data = {
        "Incident_IR": [
            {
                "Module": "Incident (IR)",
                "Record ID / Ref": "IR-2026-001",
                "Title / Description": "Pressure relief valve leak during maintenance",
                "Category / Severity": "Medium / Process Safety",
                "Site / Location / Dept": "FFL Plant 1 - Ammonia",
                "Target / Event Date": "2026-06-15",
                "Owner / Responsible": "John Doe",
                "Status": "Open",
                "Recommendations / Action Items": "Replace gasket and recalibrate safety valve.",
                "Source Document / URL": "https://fgportal.fatima-group.com/sites/ffl/Lists/IR/DispForm.aspx?ID=1",
                "Raw Fields JSON": "{}"
            }
        ],
        "EHSEC_MOM": [
            {
                "Module": "EHSEC-MOM",
                "Record ID / Ref": "MOM-2026-Q2",
                "Title / Description": "Q2 EHSEC Action Tracker & Review",
                "Category / Severity": "Executive Review",
                "Site / Location / Dept": "Corporate EHS",
                "Target / Event Date": "2026-07-01",
                "Owner / Responsible": "Jane Smith",
                "Status": "In-Progress",
                "Recommendations / Action Items": "Review all MSA high-risk audit findings by Q3.",
                "Source Document / URL": "EHSEC_MOM_Q2_2026.xlsx",
                "Raw Fields JSON": "{}"
            }
        ]
    }
    create_unified_excel(sample_data, "Test_Unified_Master_Sheet.xlsx")
