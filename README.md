# Socks Tether

A tiny Android app that runs a SOCKS5 proxy for a USB-attached computer, so the
computer can use the phone's internet **without Android's tethering feature**.

Why: many carriers throttle or block the system tethering path (a separate
"DUN" data bearer, sometimes IPv6-only with no working IPv4 translation). Traffic
that originates from an ordinary app uses the phone's normal data connection, and
this app is just such an app: the computer reaches it over the USB link
(RNDIS/USB-tether interface, `rndis0`) and the app makes the real outbound
connections.

## What it is

- SOCKS5 (RFC 1928), no authentication: `CONNECT` and `UDP ASSOCIATE`
  (so DNS and other UDP work through a tun-to-SOCKS bridge such as
  [tun2socks](https://github.com/xjasonlyu/tun2socks)). `BIND` is not supported.
- **Only serves clients on a USB-tether subnet** (interface names `rndis*`,
  `usb*`, `ncm*`, `eth*`) plus loopback. The Wi-Fi and mobile networks are never
  served, so listening on the wildcard address is safe.
- A foreground service that exists only while you need it. Blocking sockets on
  daemon threads: no polling, no timers. A partial wake lock is held **only
  while the service runs** so forwarding continues with the screen off.
- No Google services, no analytics, no storage; permissions are `INTERNET`,
  `FOREGROUND_SERVICE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.
- ~40 KB APK, minSdk 26.

## Use

1. Install the APK from the Releases page (`adb install socks-tether-<v>.apk`).
2. Enable USB tethering on the phone (Settings, or `adb shell svc usb setFunctions rndis`)
   so the computer gets an interface and a DHCP lease from the phone. The phone
   does not need the carrier to allow tethering for this to work; the proxy does
   not use that path.
3. Start the proxy (from the computer, over adb):

   ```
   adb shell am start-foreground-service -n com.spencerharmon.sockstether/.ProxyService
   # optional: --ei port 1080   (default 1080)
   ```

   Stop it:

   ```
   adb shell am start-foreground-service -n com.spencerharmon.sockstether/.ProxyService \
       -a com.spencerharmon.sockstether.STOP
   ```

   (There is also a Start/Stop screen in the app itself.)
4. Point the computer at `socks5://<phone address on the USB link>:1080`. For
   transparent system-wide use (TCP + UDP + DNS), run a tun-to-SOCKS bridge:

   ```
   tun2socks -device tun://phone0 -proxy socks5://<phone-ip>:1080 -interface <usb-iface>
   ip addr add 198.18.0.1/15 dev phone0 && ip link set phone0 up
   ip route add default dev phone0 metric 4000
   ```

## Build

Requires JDK 17 and the Android SDK (platform 37, build-tools 37.0.0).

```
./gradlew :app:testDebugUnitTest      # JVM tests: CONNECT, UDP ASSOCIATE, filtering
./gradlew :app:assembleRelease        # unsigned APK; sign with apksigner
```

## Limits

- UDP fragmentation (`FRAG != 0`) is dropped.
- IPv4 literals use the phone's IPv4 path (464XLAT on IPv6-only carriers);
  hostnames are resolved on the phone.
- Carriers can still detect heavy proxied traffic by other means (volume, DPI).
  Use according to your plan's terms.

MIT licensed.
