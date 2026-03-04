# 🚀 Local Share

**Local Share** is a **high-speed Android file transfer application** that allows devices connected to the **same Wi-Fi network** to securely share files **without internet or cloud services**.

It focuses on **fast LAN transfers ⚡, simple UI 🎨, and secure PIN-based authentication 🔐**.

---

## ✨ Features

### 📡 Start Sharing (Web Sharing)

Share files from your phone to any device using a browser.

The app generates a local address such as:

```
http://192.168.x.x:8080
```

Any device connected to the same Wi-Fi network can open this address in a browser and download files.

✔ No internet required
✔ Works on laptops, tablets, and phones

---

### 👥 Team Share

Create a **shared workspace** where multiple devices can upload files.

Devices join using:

* 📷 **QR Code**
* 🔑 **PIN Authentication**

Features:

✔ Multiple devices can join
✔ Any device can upload files
✔ All connected devices can download shared files

Perfect for **classrooms, offices, and group sharing**.

---

### 📱 Phone to Phone Transfer

Direct **device-to-device file transfer** using QR connection.

**Steps:**

1️⃣ Receiver generates **QR Code + PIN**
2️⃣ Sender scans the QR code
3️⃣ Devices authenticate using the PIN
4️⃣ File transfer begins instantly ⚡

Supports:

✔ Multiple files
✔ Real-time transfer speed display
✔ Large file support

---

## 🔐 Security

Local Share uses **local network security measures** to protect transfers.

* 🔑 **PIN-based authentication**
* 📡 Works only inside the **local network**
* 🚫 No internet communication
* ☁️ No cloud storage
* 🔒 No third-party servers

Your files stay **inside your network**.

---

## ⚡ Performance

The app is optimized for **maximum LAN transfer speed** using:

* `BufferedOutputStream`
* `HttpURLConnection`
* `Chunked Streaming Mode`
* `WiFi High Performance Mode`
* `WakeLock` to prevent CPU sleep during transfer

### Expected Speeds

| Network         | Speed         |
| --------------- | ------------- |
| 📶 2.4 GHz WiFi | 5 – 20 MB/s   |
| 🚀 5 GHz WiFi   | 20 – 50+ MB/s |

Actual speed depends on **router quality and device hardware**.

---

## 📋 Requirements

* 📱 Android device
* 📶 Same Wi-Fi network for both devices
* 🤖 Android 8.0 or higher

---

## 📂 Project Structure

```
LocalShare
│
├── activities
│   ├── MainActivity
│   ├── ActivityPhoneToPhone
│   ├── ActivityP2PSender
│   └── ActivityP2PReceiver
│
├── services
│   └── P2PReceiverService
│
├── server
│   └── FileTransferServer (NanoHTTPD)
│
├── ui
│   └── layouts
│
└── utils
```

---

## 🛠 Technologies Used

* Kotlin 🧑‍💻
* Android SDK 🤖
* NanoHTTPD (embedded HTTP server) 🌐
* ZXing QR Scanner 📷
* Material UI Components 🎨

---

## 🧪 How to Test

1️⃣ Install the app on **two Android phones**
2️⃣ Connect both phones to the **same Wi-Fi network**
3️⃣ Open **Phone to Phone Transfer**
4️⃣ On one phone select **Receiver**
5️⃣ On the other phone select **Sender**
6️⃣ Scan the QR code
7️⃣ Select files and start transfer

Transfer begins immediately ⚡

---

## ⚠️ Known Limitations

* Devices must be on the **same local network**
* Transfers do not work over the internet
* Emulator IP addresses (`10.0.2.x`) are not supported

---

## 🔮 Future Improvements

* 📊 Live transfer progress bar
* 📁 Folder transfer support
* ⏸ Pause / Resume transfers
* 📜 Transfer history
* 🔎 Automatic device discovery
* 🎨 Additional UI themes

---

## 👨‍💻 Author

**Kasivishal**
https://www.instagram.com/kasivishal_/?hl=en

---

## 📜 License

This project is intended for **educational and personal use**.
