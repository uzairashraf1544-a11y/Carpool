# Step-by-Step Guide: Uploading TravelBuddy to Google Play Store

This document explains exactly how to upload the `app-release.aab` bundle located in this folder (`Abb/`) to the Google Play Console.

---

## Prerequisites Checklist

1. [Google Play Developer Account](https://play.google.com/console/) ($25 one-time registration).
2. The signed Android App Bundle: `Abb/app-release.aab`.
3. Firebase Project Settings open (`gen-lang-client-0717949368`).

---

## Step 1: Add SHA-256 Fingerprint to Firebase Console (CRITICAL)

If you skip this step, Google Sign-in and Firebase Authentication will fail on the Play Store release!

1. Open [Firebase Console](https://console.firebase.google.com/).
2. Select your project: **`gen-lang-client-0717949368`**.
3. Click the **Gear icon ⚙️** (Project Settings) > **General** tab.
4. Scroll to **Your apps** > select **`com.aistudio.carpool.kjqxcv`**.
5. Click **Add fingerprint**.
6. Paste the SHA-256 fingerprint:
   ```text
   AA:82:22:E3:C0:E0:B4:AB:FC:AB:33:AF:E3:5A:CC:78:F8:83:08:C5:D4:10:BF:87:9B:3C:DA:92:FE:C9:75:E2
   ```
7. Click **Save**.

---

## Step 2: Create App in Google Play Console

1. Log in to [Google Play Console](https://play.google.com/console/).
2. Click **Create app** (top-right button).
3. Fill out the initial form:
   - **App name**: `TravelBuddy: Community Carpool`
   - **Default language**: English (United States)
   - **App or game**: App
   - **Free or paid**: Free
4. Accept the declarations and click **Create app**.

---

## Step 3: Complete App Content Declarations

Google Play requires completing the "Set up your app" tasks on the Dashboard:

1. **Privacy policy**: Provide a URL to your privacy notice / carpool consent policy.
2. **App access**: Select *"All functionality is restricted"* or *"Some functionality is restricted"* and provide test login credentials (e.g. a demo employee account) so Google reviewers can log in and test.
3. **Ads**: Select *"No, my app does not contain ads"*.
4. **Content rating**: Complete the questionnaire (Select "Utility / Productivity / Communication").
5. **Target audience**: Select Age 18 and over.
6. **News apps**: Select *"No"*.
7. **COVID-19 contact tracing**: Select *"No"*.
8. **Data safety form**: Declare collected data:
   - *Approximate and precise location* (used for carpool journey navigation; not shared with third parties).
   - *Personal info* (Name, email, employee ID, phone number).
   - *App info and performance* (Diagnostics/crash logs).
   - Confirm all data is encrypted in transit (HTTPS/TLS) and can be deleted upon account removal request.
9. **Government apps**: Select *"No"*.
10. **Financial features**: Select *"None"*.

---

## Step 4: Upload the Android App Bundle (`.aab`)

1. In the Play Console left menu, navigate to:
   - **Release** > **Production** (or **Testing** > **Internal testing** if you want to test with a small group of colleagues first).
2. Click **Create new release**.
3. Under **App bundles**, click **Upload** (or drag and drop):
   - Choose: `Abb/app-release.aab`
4. The system will process the bundle and automatically read:
   - **Version code**: `1`
   - **Version name**: `1.0`
5. In **Release notes**, enter:
   ```text
   Initial production release of TravelBuddy corporate carpool platform:
   - Employee onboarding & administrator verification gate
   - Daily commute reminders and schedule management
   - Live journey tracking and seat reservations
   - Corporate CO₂ savings and sustainability metrics
   ```
6. Click **Next** / **Review release**.
7. If there are no blocking errors, click **Start rollout to Production** (or rollout to Internal testing).

---

## Step 5: Post-Upload — Google Play App Signing Key in Firebase

When you opt into Google Play App Signing (which is default and mandatory for modern apps), Google generates a separate **Production App Signing Key**:

1. In Play Console, go to **Setup** > **App Integrity**.
2. Under **App signing key certificate**, copy the **SHA-256 certificate fingerprint**.
3. Return to [Firebase Console](https://console.firebase.google.com/) > Project Settings > Your apps.
4. Click **Add fingerprint** and paste Google's signing SHA-256 there as well.
5. This ensures users downloading the app from the Play Store have functioning Google Sign-In and Firebase services!

---

## Keystore Backup Reminder

Your upload keystore file is saved in:
- `Abb/my-upload-key.jks`
- Root directory: `my-upload-key.jks`

Keep this file and its password (`travelbuddy2026`) in a secure company backup. All future app bundle updates uploaded to Google Play Console must be signed with this key.
