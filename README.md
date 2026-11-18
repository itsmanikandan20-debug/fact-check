# Fact Check

An Android app that fixes wrong facts while you type. It works with Gboard (or any keyboard).

Type a message, tap the floating ✨ bubble, and only the wrong word changes.

- "Today is Monday" → "Today is Wednesday" (checked against the phone's date, no internet needed)
- "Fight Club came in 2001" → "Fight Club came in 1999" (checked with Google Gemini + Google Search)

## Install

1. Open the **Releases** page of this repo on your phone and download `FactCheck.apk`.
2. Tap it → allow "Install unknown apps" for your browser → **Install**.
3. Open **Fact Check** → **Open Accessibility settings** → Fact Check → ON.
   - If it is greyed out: App info → ⋮ → **Allow restricted settings**, then try again.
4. (Optional) Paste a free Gemini API key from aistudio.google.com for non-date facts.

## How it works

- `FactAccessibilityService` shows the ✨ bubble. On tap, it finds the focused text box,
  reads its text, gets fixes, and replaces only the wrong words (with Undo).
- `FactEngine.localFixes` checks "today/tomorrow/yesterday is <day>" against the phone date.
- `FactEngine.aiFixes` asks Gemini (with Google Search grounding) for exact wrong → correct pairs.

The APK is built by GitHub Actions on every push to `main`.
