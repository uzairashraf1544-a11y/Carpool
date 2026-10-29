import json

with open("sp_extractor_data/schemas/target_list_schemas.json", encoding="utf-8") as f:
    schemas = json.load(f)

for list_name, fields in schemas.items():
    print(f"\n================ {list_name} ================")
    for field in fields:
        print(f"  {field['InternalName']:<30} -> {field['Title']} ({field['TypeAsString']})")
