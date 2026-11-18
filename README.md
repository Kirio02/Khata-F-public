# Ghanshyam Billing

A simple khata (ledger) and billing app for a small kids-wear shop that is run by one person.

The owner does the selling, the collection of payments and the deliveries all by himself, so the app is built around that: quick entries, a clear cash view, and a list of who still owes money. It runs fully on the phone, works offline, and is available in four languages.

> Built for a real shop. There is deliberately **no stock/inventory section**, because the stock changes with whatever is trending and selling.

## Features

**Khata (party ledger)**
- Add customers and suppliers, or import them from phone contacts (duplicates are skipped).
- "I Gave" / "I Got" entries with a running balance per party.
- Payments are matched to the oldest unpaid bills first, so each bill shows what is still pending.
- Vasuli list: who owes money, and who is actually overdue (past the due date, or older than 15 days).
- Send a statement or reminder on WhatsApp, SMS or call from the party screen. Invalid phone numbers are flagged.

**Billing**
- Create bills with GST details, discounts, delivery/packaging charges and a UPI QR code on the bill.
- Several bill designs, PDF invoices, and sharing the PDF in one tap.
- Past bills, cancellation, and "Mark received" for part or full payment.

**Cashbook**
- A large "cash in shop now" number, with today's money in and money out.
- Separate Cash and Bank books, a deleted-entries bin, and a bank statement check.

**Daily work for one person**
- "Aaj ki delivery": a quick list of today's deliveries.
- A daily report notification at 8:30 PM.
- Reports for the accountant (CSV and summaries).

**Voice and AI helper**
- Speak an entry, such as "Suresh ne 5 thousand diye". The app checks who and which direction before saving.
- Optional AI assistant (Gemini). The owner pastes their own API key in the app. It only proposes changes, and nothing is saved until the owner taps **Apply**. An offline keyword mode works without a key.

**Safety**
- App lock with PIN and fingerprint, and an option to block screenshots.
- Backup and restore as a zip file.
- If the app ever crashes, the next start shows the error with a Share button.

**Languages:** Hinglish, Hindi, Gujarati and English.

## Privacy

- All shop data is stored on the phone. There is no account and no server.
- The Gemini API key, if used, is stored encrypted on the phone and is never part of this repository.
- If the AI assistant is used, the text of the request is sent to Google's Gemini service to get an answer.

## Tech

Kotlin, Jetpack Compose (Material 3), Room database, WorkManager, OkHttp, encrypted preferences and biometric lock.
Android 8.0+ (minSdk 26). Built with JDK 21 and Gradle.

## Build it yourself

1. Install JDK 21 and Android Studio (or the Android SDK).
2. Create a debug signing key in the project root. Never commit it; it is ignored by `.gitignore`.

   ```
   keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```

3. Generate the Gradle wrapper and build:

   ```
   gradle wrapper
   ./gradlew assembleDebug
   ```

   The APK is in `app/build/outputs/apk/debug/`.

4. Run the tests with `./gradlew testDebugUnitTest`.

The GitHub Actions workflow in `.github/workflows/main.yml` does the same on every push. It reads the signing key from a repository secret named `DEBUG_KEYSTORE_BASE64`.

## Status

Used and improved in a real shop, one fix at a time. Bug reports and ideas are welcome as issues.

## License

No license has been chosen yet. Until one is added, all rights are reserved by the author.
