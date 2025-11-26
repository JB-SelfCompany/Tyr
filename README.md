# Tyr

**English | [Русский](README.ru.md)**

True P2P Email on top of Yggdrasil Network

## What is Tyr?

We're taught that email must go through servers. Why? Because the Internet was built around centralized infrastructure. Every email you send travels through multiple servers - your provider's server, maybe a few relay servers, and finally your recipient's provider's server. Each hop is a potential point of surveillance, censorship, or failure.

Even "encrypted" email solutions still rely on these centralized servers. They encrypt the message content but the metadata - who you're talking to, when, how often - is visible to anyone watching the servers.

But there is a network, called [Yggdrasil](https://yggdrasil-network.github.io/), that gives everyone a free IPv6 and doesn't need a blessing from your ISP. We finally have this possibility to use true P2P email. And moreover, this network has strong encryption to protect all data that flows from one IP to another.

So, Tyr brings true peer-to-peer email to your Android device using these unusual conditions. Unlike traditional email clients, Tyr doesn't need:

1. Centralized mail servers (the connections are straight P2P)
2. Message encryption layers (the network takes care of that)
3. Port forwarding or STUN/TURN servers (Yggdrasil handles NAT traversal)

### What does Tyr already have?

1. Full integration with DeltaChat and ArcaneChat - the best decentralized messengers
2. Local SMTP/IMAP server running on your device
3. Automatic Ed25519 key generation for your mail identity
4. Connection to the Yggdrasil Network with configurable peers
5. Auto-start on boot for always-on availability
6. Encrypted backup & restore with password protection
7. Automatic recovery from Android Keystore issues (Samsung devices)

One of Tyr's strong points is censorship circumvention: you can connect to any of hundreds of available Yggdrasil nodes, host your own, or even build a private network. Email freedom is literally in your hands.

## How does it work?

Tyr runs a complete email server right on your Android device, using the Yggdrasil network for transport. The [Yggmail](https://github.com/JB-SelfCompany/yggmail-android) mail server (built in Go) is embedded as a library inside the app and runs as a foreground service.

On top of Yggdrasil, it provides standard SMTP and IMAP protocols on localhost (127.0.0.1:1025 and 127.0.0.1:1143). Any email client can connect to these ports - but we recommend DeltaChat or ArcaneChat for the best P2P messaging experience.

Every Tyr installation generates unique Ed25519 cryptographic keys. Your mail address is derived from your public key, making it: `<64-hex-characters>@yggmail`. This means your identity is cryptographically verifiable and cannot be spoofed.

### DeltaChat/ArcaneChat Integration

DeltaChat and ArcaneChat are perfect companions for Tyr. These are messengers that use email protocols but provide modern chat interfaces. When you configure DeltaChat/ArcaneChat to use Tyr's local server:

1. DeltaChat/ArcaneChat sends messages via SMTP to Tyr
2. Tyr wraps them in Yggmail protocol and sends through Yggdrasil
3. The recipient's Tyr receives the message via Yggdrasil
4. Their DeltaChat/ArcaneChat fetches it via IMAP from their local Tyr
5. All this happens peer-to-peer, with no central servers

## Setting up DeltaChat/ArcaneChat with Tyr

### Option 1: Automatic Setup (Recommended)

1. Install Tyr and complete the onboarding (set password, configure peers)
2. Start the Yggmail service in Tyr
3. Install DeltaChat or ArcaneChat from F-Droid or Google Play
4. In Tyr's main screen, tap **"Setup DeltaChat/ArcaneChat"**
5. Tyr will automatically open DeltaChat/ArcaneChat with pre-configured settings
6. Complete the setup and start chatting!

### Option 2: Manual Setup

If automatic setup doesn't work:

1. Install Tyr and complete the onboarding (set password, configure peers)
2. Start the Yggmail service in Tyr
3. Copy your mail address from the main screen (looks like `abc123...@yggmail`)
4. Install DeltaChat or ArcaneChat from F-Droid or Google Play
5. In DeltaChat/ArcaneChat, tap **"Create a new profile"**
6. Enter a name and optionally select an avatar
7. Tap **"Use a different server"** (below the login fields)
8. Enter your Yggmail address and the password you set in Tyr
9. Tap "✓" in the top right corner to complete setup

**Important**: Tyr must be running for DeltaChat/ArcaneChat to send and receive messages. Enable auto-start in Tyr settings for seamless experience.

## Building from source

### Prerequisites
- Android Studio (latest version recommended)
- JDK 17
- Android SDK (API 23-36)
- Go 1.21+ and gomobile (only if rebuilding yggmail.aar)

### Build steps

1. Clone the repository:
```bash
git clone <repository-url>
cd Tyr
```

2. Build debug APK:
```bash
./gradlew assembleDebug
```

3. Install to connected device:
```bash
./gradlew installDebug
```

APKs will be in `app/build/outputs/apk/debug/` or `app/build/outputs/apk/release/`

### Rebuilding yggmail.aar (optional)

If you need to rebuild the Yggmail library:

```bash
cd ../yggmail/mobile
# On Windows:
..\build-android.bat
# On Unix:
gomobile bind -target=android -androidapi 23 -javapkg=com.jbselfcompany.tyr -ldflags="-checklinkname=0" -o yggmail.aar .
```

Then copy `yggmail.aar` to `Tyr/app/libs/`

## Technical details

- **Language**: Kotlin 2.2.20
- **Min SDK**: 23 (Android 6.0)
- **Target SDK**: 33 (Android 13)
- **Architecture**: Layered (UI → Service → Data)
- **Mail server**: Yggmail (Go library, embedded via gomobile)
- **Network**: Yggdrasil overlay network
- **Localization**: English, Russian

## Security Features

🔒 **Security implementation**:

- **Passwords are encrypted** using Android Keystore System (AES256-GCM encryption)
- **Automatic Keystore recovery**: Handles Android Keystore issues on Samsung and other devices automatically
- **Network encryption** provided by Yggdrasil Network for all peer-to-peer communications
- **Local-only access**: SMTP/IMAP ports (1025/1143) are bound to localhost only, not accessible from network
- **Cryptographic identity**: Ed25519 keys ensure your mail address cannot be spoofed
- **Encrypted backups**: Configuration and keys can be backed up with password protection

## Related Projects

- [Yggmail](https://github.com/JB-SelfCompany/yggmail-android): The mail transfer agent that powers Tyr
- [Mimir](https://github.com/Revertron/Mimir): P2P messenger on Yggdrasil (sister project)
- [Yggdrasil Network](https://yggdrasil-network.github.io/): The mesh network infrastructure
- [DeltaChat](https://delta.chat/): Recommended email-based messenger client
- [ArcaneChat](https://github.com/ArcaneChat/android): Alternative email-based messenger client

## License

Tyr is open source software. The Yggmail library uses Mozilla Public License v. 2.0.

See `LICENSE` file for full details