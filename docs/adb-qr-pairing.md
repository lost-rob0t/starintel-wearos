# ADB QR pairing

StarIntel's Nix pairing helper supports the same wireless-debugging QR bootstrap used by Android Studio.

## Use it

On an Android device that exposes the QR scanner:

1. Enable **Developer options → Wireless debugging**.
2. Choose **Pair device with QR code**.
3. From the repository root run:

```sh
nix run .#pair-android -- --qr
```

4. Scan the terminal QR.

The helper waits for the matching ADB pairing service, performs the TLS pairing handshake, discovers the normal wireless-debugging endpoint, connects it, and verifies that `adb devices` reports that endpoint online.

The QR path requires the Android device and workstation to be on the same Wi-Fi network so mDNS discovery can work.

## Protocol

The QR payload follows Android's ADB Wi-Fi format:

```text
WIFI:T:ADB;S:studio-<random>;P:<random-secret>;;
```

The device uses the `S:` value as the requested `_adb-tls-pairing._tcp` mDNS instance name and the `P:` value as the shared pairing secret. The helper waits for that exact instance, resolves its address/port using `adb mdns services`, and passes the secret to `adb pair` over standard input.

After pairing, the helper resolves `_adb-tls-connect._tcp` on the same device address and reuses the normal `pair-android --connect` verification path.

Reference: AOSP `packages/modules/adb/docs/dev/adb_wifi.md`.

## Security properties

- A fresh service name and pairing secret are generated for every invocation.
- The secret is encoded only into the terminal QR and is not printed as text.
- The secret is sent to `adb pair` over stdin rather than as a command-line argument.
- The QR path does not alter or bypass Android's ADB trust model; `adb` still performs the authenticated TLS pairing handshake.
- Pairing is persistent until Android forgets/revokes the workstation, exactly like normal ADB Wi-Fi pairing.

## Wear OS fallback

Many watches do not provide a practical QR scanner. Use the existing pairing-code path there:

```sh
nix run .#pair-watch -- WATCH_IP:PAIR_PORT WATCH_IP:ADB_PORT
```

`pair-watch` remains an alias of the generic Android pairing helper.

## Troubleshooting

Inspect what ADB can currently see:

```sh
nix run .#pair-android -- --diagnose
```

Restart the local ADB daemon:

```sh
nix run .#pair-android -- --reset-adb
```

The QR discovery timeout defaults to 90 seconds and can be overridden for slow networks:

```sh
STARINTEL_ADB_QR_TIMEOUT=180 nix run .#pair-android -- --qr
```

If pairing succeeds but `_adb-tls-connect._tcp` is not discovered, keep Wireless debugging enabled and use `--diagnose` to check whether multicast/mDNS is being blocked by a VPN, firewall, bridge, or network isolation.
