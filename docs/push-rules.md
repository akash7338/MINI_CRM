# Git Push and Commit Guidelines

This document serves as a checklist and set of guidelines for the AI assistant (Antigravity) before committing or pushing any code to the repository.

---

## 🚨 Pre-Commit & Pre-Push Checklist

### 1. Ask for Explicit User Approval
* **Do not automatically push:** Even if a branch is already pushed or if a fix is small, **never** push changes to the remote repository unless the user has explicitly requested or approved it in the current turn.
* **Keep changes local:** Perform edits, verify locally, and wait for confirmation before doing `git push`.

### 2. Check for Security and Sensitive Files
* **No keys/certificates:** Ensure no private keys, certificates, or keystores (`.pem`, `.key`, `.crt`, `.p12`, `.jks`) are added or tracked in Git.
* **Verify `.gitignore` rules:** Check that any runtime-generated credentials (such as the FreeSWITCH TLS files in `docker/freeswitch/conf/tls/`) are properly ignored.

### 3. Verify the Scope of Changes
* Run `git status` and `git diff` to inspect changes.
* Ensure no accidental edits were made to unrelated files (e.g., configuration templates, database scripts, or other services like `telephony-service` or `call-service` unless explicitly requested).
* Confirm that no local test configurations (like temporary hardcoded passwords or test API keys) are accidentally committed.

### 4. Exclude Local Runtime Assets
* Do not commit local media recordings, debug logs, database dumps, or local JVM build files (`build/`, `.gradle/`, `.settings/`).
* Maintain placeholder files (like `.gitkeep`) to preserve directory structures without committing actual runtime assets.

---

## 📝 Commit Standard
* Use descriptive, semantic commit messages (e.g., `feat: ...`, `fix: ...`, `docs: ...`).
* Keep commits focused and atomic.
