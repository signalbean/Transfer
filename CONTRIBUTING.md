## Contributing

Thank you for in contributing! This app have three main parts:

- **App UI** - Android activities in Kotlin and XML.
- **HTTP Server** - the HTTP Ktor server the app run.
- **Website** - Plain HTML/JS/CSS served from the app's assets.

Please follow the instructions below to get started.

---

### Website Editing

Website files are in `app/src/main/assets/` and can be edited like any regular HTML/CSS/JS project.

To simplify development:

1. Forward the app's server to your local machine (you need to run Transfer on emulator for this script to work):

```bash
adb reverse tcp:9135 tcp:8000
```

2. Start the local dev server with live reload:

```bash
python serve.py localhost:9135
```

3. Visit [http://localhost:4444](http://localhost:4444) to see your changes.

This setup live-reloads the site and forwards `/api/*` requests to the emulator.

### App UI (XML/Kotlin)

- Themes are defined in `res/values/themes.xml` and `res/values/colors.xml`.
- For dark mode: check `res/values-night/colors.xml`.
- Activities and layouts are organized under `res/layout/` and all \*Activity files in `src/main/java/...`.

### HTTP Server (Ktor)

- API endpoints (such as /api/upload): `TransferServerModule`
- Server features (like password protection): `FileServerService`

---

### PR Guidelines

- for code changes, Use commit messages with tags, e.g.:

  - `fix(server): handle large file uploads`
  - `feat(main): add new button`
  - `tests: add tests for upload endpoint`

- Fork the repo and create a branch for your change.
- Open a pull request

Looking forward to your contributions!
