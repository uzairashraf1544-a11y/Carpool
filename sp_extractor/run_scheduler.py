import time
import sys
import os
import datetime
from sp_data_extractor import run_extraction

def main():
    print("==========================================================================")
    print("      Fatima Group EHS SharePoint Data Watcher & Extraction System        ")
    print("==========================================================================")
    print(f"[*] Started execution at: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    
    try:
        run_extraction()
        print(f"\n[+] Synchronization finished successfully at: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    except Exception as e:
        print(f"\n[-] Synchronization error: {e}")

if __name__ == "__main__":
    main()
