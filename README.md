# Transfer

A simple, local file server app for Android. download/upload files fast across devices over Wi-Fi, with no cables or cloud.


![screenshot of the app](data/screenshot_rect3.png)

Transfer makes sharing files across your local network incredibly straightforward. Think of it as a temporary USB stick you can access from any computer or device with a web browser, but without the physical stick, powered by a clean and simple UI.

Basically, a better alternative to running `uploadserver` through Termux.

## The Problem It Solves
Imagine you need to transfer a file between your laptop and desktop - but you just want to send the file and move on:

* you don’t have a USB stick/cable handy
* you don’t want to configure SMB (enable/run Samba, then download a client)
* you don’t want to transfer that file through the cloud — either because it’s slow, unprivate, or you simply don’t have easy access to it

## Key Features

* **Effortless LAN Sharing**: Once Transfer is active, it serves files from your chosen shared folder over HTTP. Any device on the same Wi-Fi can connect using a simple web address shown in the app.
* **Configurable Security**:
    * **IP Permissions**: By default, new devices attempting to connect trigger an "Allow/Deny" popup on your phone, giving you control over who accesses your files. This can be turned off for trusted networks.
    * **Password Protection**: For an added layer, you can secure access with a password (off by default).
* **Powerful CLI Access (curl-friendly)**:
    * Transfer plays nice with command-line tools. Upload files directly using `curl -T yourfile.txt <your-phone-ip>:8000`. Also, simply `curl <phone-ip>:8000/yourfile.txt` will get your file back.
* **Dual Browse UI**: Manage and access your shared files directly within the Transfer app on your Android device, or through the intuitive web interface on any connected computer.
* **Quick In-App Transfers**:
    * **Upload**: Easily select files from your phone's storage to add them to the shared folder.
    * **Paste**: Paste text from your phone’s clipboard directly into a new `.txt` file in the shared folder with a single tap.

## Getting Started

1. Install and open Transfer on your Android device.
2. Grant necessary permissions and select a folder you wish to share (I suggest you create a new folder called `Storage` in the home folder).
3. Tap "Start Server."
4. The app will display an IP address (e.g., `http://192.168.1.X:8000`).
5. Open this address in a web browser on any other device connected to the same Wi-Fi network.
6. You're in! If IP permissions are on (default), you'll get a prompt on your phone to allow the new device.

It's designed to be that simple. Enjoy your new wireless drive.