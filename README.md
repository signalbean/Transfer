# Transfer

**Transform your Android device into a wireless, browser-accessible drive. Simple, fast, and surprisingly versatile.**

![screenshot of the app](data/screenshot_rect3.png)

Transfer makes sharing files across your local network incredibly straightforward. Think of it as a temporary USB stick you can access from any computer or device with a web browser, but without the physical stick, powered by a clean and simple UI.

Basically, a better alterntive to running `uploadserver` through termux

## The Problem It Solves
Imagine you need to transfer a file between your laptop and desktop.

* Email/Cloud - will usually take more time. additionally, you might not have accses to them.
* No USB stick.
* SMB - you dont want to sit and configure both computers to serve/listen to samba. it requires you to install (on linux/mac)/enable it on one, enable discovery, which might be block due to company policy,etc.


## Key Features

* **Effortless LAN Sharing**: Once Transfer is active, it serves files from your chosen shared folder over HTTP. Any device on the same Wi-Fi can connect using a simple web address shown in the app.
* **Configurable Security**:
    * **IP Permissions**: By default, new devices attempting to connect trigger an "Allow/Deny" popup on your phone, giving you control over who accesses your files. This can be turned off for trusted networks.
    * **Password Protection**: For an added layer, you can secure access with a password (off by default).
* **Powerful CLI Access (curl-friendly)**:
    * Transfer plays nice with command-line tools. Upload files directly using `curl -T yourfile.txt <your-phone-ip>:8000`. also, simply `curl <phone-ip>:8000/yourfile.txt` will get your file back.
* **Dual Browse**: Manage and access your shared files directly within the Transfer app on your Android device, or through the intuitive web interface on any connected computer.
* **Quick In-App Transfers**:
    * **Upload**: Easily select files from your phone's storage to add them to the shared folder.
    * **Paste**: Copy text on your phone? Paste it directly into a new `.txt` file in the shared folder with a single tap.

## Compare with Alternatives

* vs. Email/Cloud: No internet upload/download needed as devices are on the same LAN.

* vs. WebSockets: Uses standard HTTP, which is universally supported and rarely blocked on local networks. adittionally, the speed and relablity might change, dpepends of which implementation and matchers you use.

* vs. SMB: simpler setup, you normally need to enable SMB, enable it as a server/enable discovery (which might be blocked by your company), download android SMB client/server.additionally, HTTP is often faster for quick transfers too.
* vs. USB Stick / MTP File Transfer:
    * You dont need USB.


## Getting Started

1.  Install and open Transfer on your Android device.
2.  Grant necessary permissions and select a folder you wish to share (I suggest you create new folder called `Storage` in the home folder)
3.  Tap "Start Server."
4.  The app will display an IP address (e.g., `http://192.168.1.X:PORT`).
5.  Open this address in a web browser on any other device connected to the same Wi-Fi network.
6.  You're in! If IP permissions are on (default), you'll get a prompt on your phone to allow the new device.

It's designed to be that simple. Enjoy your new wireless "drive"!