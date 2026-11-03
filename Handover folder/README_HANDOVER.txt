================================================================================
TRAVELBUDDY — OFFICIAL RELEASE & GOOGLE PLAY STORE HANDOVER PACKAGE
================================================================================

This folder contains all production binaries, store graphics, and metadata required
for publishing TravelBuddy to the Google Play Store and distributing direct test
installations.

--------------------------------------------------------------------------------
1. PACKAGE CONTENTS
--------------------------------------------------------------------------------

1. app-release.aab (~27.9 MB)
   • USE CASE: Google Play Console Release Upload.
   • FORMAT  : Android App Bundle (.aab).
   • STATUS  : Production build, pre-signed with the official release key.
   • TARGET  : Drag & drop directly into Google Play Console (Release > Production).

2. app-release.apk (~28.7 MB)
   • USE CASE: Direct Device Installation & Quality Assurance.
   • FORMAT  : Android Package (.apk).
   • STATUS  : Production build, pre-signed with the release upload key.
   • TARGET  : Can be installed directly on any Android 7.0+ (API 24+) device.

3. app_icon_512x512.png (512 x 512 px, 32-bit PNG)
   • USE CASE: Official Google Play Store Hi-Res Icon.
   • FORMAT  : 32-bit PNG with alpha, exactly 512x512, meets Google Play requirements.
   • TARGET  : Upload in Google Play Console > Main Store Listing > App Icon.

4. feature_graphic_1024x500.png (1024 x 500 px, 24-bit RGB PNG)
   • USE CASE: Official Google Play Store Feature Graphic.
   • FORMAT  : 24-bit RGB PNG (no alpha), exactly 1024x500, meets Google Play requirements.
   • TARGET  : Upload in Google Play Console > Main Store Listing > Feature Graphic.

5. store_banner_1200x628.png (1200 x 628 px, RGB PNG)
   • USE CASE: Promotional / Store Banner.
   • FORMAT  : High-resolution landscape banner with TravelBuddy branding & stats.
   • TARGET  : Marketing campaigns, featured promo, and social preview assets.

6. PLAY_STORE_METADATA.txt
   • App Title, Short Description, and Full Store Listing Description.
   • Target SDK: 36 (Android 16), Min SDK: 24 (Android 7.0).
   • Application ID: com.aistudio.carpool.kjqxcv
   • Version Code: 4, Version Name: 4.0.
   • Full list of declared permissions and explanations for Data Safety declarations.

7. HOW_TO_UPLOAD_TO_PLAY_STORE.md
   • Complete step-by-step checklist for creating the app listing, completing the
     content rating & data safety forms, and submitting the release for review.

8. APP_FINGERPRINTS.txt
   • Official SHA-256 and SHA-1 certificate fingerprints.
   • Required for Firebase Authentication (Google Sign-In and Phone/SMS verification).

--------------------------------------------------------------------------------
2. RELEASE NOTES FOR THIS VERSION (v4.0 / VersionCode 4)
--------------------------------------------------------------------------------
Production release v4.0 of TravelBuddy corporate carpool platform:
• Universal Firestore Deserialization: Added type-tolerant User parser supporting both Firebase Console Timestamps and primitive Longs/Strings, resolving Member List visibility across all members.
• Google Sign-In & Authentication Hardening: Added fallback Web Client ID, updated google-services.json with release certificate hashes, and made profile reading resilient during Google login.
• App Version Update: Bumped to Version 4 (versionCode = 4, versionName = "4.0").
• Privacy & Permission Hardening: Removed all unnecessary runtime and manifest permissions:
  - Removed GPS Location permissions (ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION).
  - Removed Foreground Service Location (FOREGROUND_SERVICE_LOCATION, FOREGROUND_SERVICE).
  - Removed restricted Alarm permissions (SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM).
  - Streamlined commute notifications to use setAndAllowWhileIdle without high-risk declarations.
  - Removed location consent explanation from onboarding UI; privacy is fully respected.
• Zero-Fake-Data Leaderboard: 100% grounded in real backend Firestore users and rides.
• Automated Backend CO₂ Logging: When a shared ride completes, savings are logged
  directly to the 'co2Records' collection for both drivers and passengers.
• Live Dynamic Leaderboard: Automatically aggregates all actual CO₂ savings from completed
  rides across the platform into 'systemConfig/leaderboard' and highlights top savers.
• Authentic Zero-State: If no community rides are completed yet, shows a clean and
  motivational empty-state explaining that the spot is open for the first carpoolers.
• Dual-Role Support: Fully responsive whether in Driver or Passenger mode.
• Verified Staff Onboarding & Admin Approval Gate.
• Shift-aligned commute scheduling with customizable working-day reminders.

--------------------------------------------------------------------------------
3. CRITICAL POST-UPLOAD STEP (GOOGLE PLAY APP SIGNING)
--------------------------------------------------------------------------------
When uploading app-release.aab to Google Play Console:
1. Google Play automatically enrolls the app in Google Play App Signing.
2. Go to: Play Console > Setup > App Integrity.
3. Copy the "App signing key certificate" SHA-256 fingerprint.
4. Ensure this SHA-256 is added to Firebase Console under:
   Project Settings > General > Your Apps (com.aistudio.carpool.kjqxcv) > Add Fingerprint.
   (This ensures Google Sign-In and Firebase Auth work seamlessly for users who download
   the app from the Play Store).
================================================================================
