================================================================================
TRAVELBUDDY — GOOGLE PLAY STORE UPLOAD PACKAGE
================================================================================

This folder contains everything needed to upload and publish TravelBuddy on the
Google Play Console.

--------------------------------------------------------------------------------
1. FILES INCLUDED IN THIS PACKAGE
--------------------------------------------------------------------------------
1. app-release.aab
   - The production Android App Bundle.
   - Already signed with the official upload release key.
   - Ready to upload directly to: Play Console -> Release -> Production (or Testing).

2. PLAY_STORE_METADATA.txt
   - App title, short description, and full description.
   - Target SDK, min SDK, and package name (com.aistudio.carpool.kjqxcv).
   - Declared permissions and explanations for the Data Safety questionnaire.

3. HOW_TO_UPLOAD_TO_PLAY_STORE.md
   - Step-by-step checklist on creating the release and completing store setup.

--------------------------------------------------------------------------------
2. RELEASE NOTES FOR THIS VERSION (v1.0 / Code 1)
--------------------------------------------------------------------------------
Initial release of TravelBuddy corporate carpool platform:
• Secure employee onboarding & admin verification gate
• Daily commute schedules & customizable reminders
• Real-time driver seat management & passenger matching
• Corporate sustainability tracking and CO₂ savings metrics

--------------------------------------------------------------------------------
3. IMPORTANT POST-UPLOAD STEP (FOR GOOGLE PLAY APP SIGNING)
--------------------------------------------------------------------------------
After uploading this bundle:
1. In Google Play Console, go to: Setup -> App Integrity.
2. Find the "App signing key certificate" SHA-256 fingerprint.
3. Please copy and send that SHA-256 fingerprint back to the developer team so it
   can be added to the Firebase Console (required for Google Sign-In to function
   in production).
================================================================================
