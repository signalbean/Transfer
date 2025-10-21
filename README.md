<h1 align="center">Transfer</h1>

<p align="center">
  <a href="https://github.com/matan-h/Transfer/releases">
    <img src="https://img.shields.io/badge/version-0.5.0-blue.svg" alt="Version">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-green.svg" alt="License">
  </a>
  <a href="https://developer.android.com/about/versions/10">
    <img src="https://img.shields.io/badge/platform-Android%2029+-brightgreen.svg" alt="Android">
  </a>
</p>

<p align="center"><strong>A simple local file server for Android</strong></p>

<p align="center">
  Transfer files instantly across devices over Wi-Fi – no cables, no cloud, no hassle.
</p>

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="150px" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="150px" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.png" width="150px" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.png" width="150px" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05.png" width="150px" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06.png" width="150px" />
</p>

## Features

- **One-tap server**: Start HTTP server instantly  
- **Cross-platform**: Access from any device with a web browser  
- **Secure**: Device permissions and optional password protection  
- **CLI-friendly**: Works with `curl` and command-line tools  
- **No internet required**: Works over local Wi-Fi only  

## Installation

**Recommended:**
- [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.matanh.transfer) (F-Droid)
- [Google Play Store](https://play.google.com/store/apps/details?id=com.matanh.transfer)

**Alternative:**
- [GitHub Releases](https://github.com/matan-h/Transfer/releases)

## Getting Started

1. Install and open Transfer on your Android device.
2. Grant necessary permissions and select a folder you wish to share (suggestion: create a new folder called `Storage` in your home directory).
3. Tap "Start Server."
4. The app will display an IP address (e.g., `http://192.168.1.X:8000`).
5. Open this address in a web browser on any other device connected to the same Wi-Fi network.
6. You're in! If IP permissions are on (default), you'll get a prompt on your phone to allow the new device.  

## Usage

1. Upload file(s)  
2. Download file(s)  
   - Both in the app and the web interface.  

## FAQ

**Browser shows SSL errors?** Use `http://` not `https://`  
**Need consistent IP?** Set up a [static IP](https://junipersys.com/support/article/14695) on your device  
**Files location?** Uploaded files go to your selected shared folder  

## Planned changes

- [ ] add an option to change the port in the settings
- [ ] fallback to hotspot IP in the display.
- [x] automatically update the IP when Wifi changes

## Contributing

Check [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines. Issues and PRs welcome!

## License

MIT License – see [LICENSE](LICENSE) file.
