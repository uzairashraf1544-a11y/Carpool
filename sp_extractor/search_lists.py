import json

with open('sp_extractor_data/sp_lists_inventory.json', encoding='utf-8') as f:
    data = json.load(f)

keywords = ['ir', 'rir', 'msa', 'ram', 'ecs', 'ehs', 'mom', 'investigation', 'recommendation', 'audit']

print("=== TARGET SHAREPOINT LISTS & LIBRARIES INVENTORY ===")
for item in data:
    title = item['title']
    title_lower = title.lower()
    count = item['count']
    if any(k in title_lower for k in keywords):
        print(f"List: {title:<45} Items: {count}")
