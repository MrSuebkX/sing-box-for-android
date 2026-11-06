# DualTunnel

This independent fork adds mihomo v1.19.33 to the Android client. It is not an official SagerNet or MetaCubeX application. Original copyright notices and licenses remain applicable. The modified application uses its own name and application ID and can be installed alongside SFA.

## Install and import

Download the **DualTunnel-arm64-v8a** artifact from this fork's **Android DualTunnel** Actions run. Extract the archive and install the APK. The initial build targets arm64 Android 7.0+ devices and uses Android's debug signing key. This is a development build, not a production release; debug keys on clean runners can change between builds.

Create a remote profile and paste a normal HTTP(S) subscription URL, or paste a `clash://install-config?url=<percent-encoded URL>&name=<name>` link. Browser and QR-code imports also accept `clashmeta://install-config`. The subscription is fetched with a Clash Meta user agent; a provider must return a complete Clash YAML configuration (nodes, proxy groups and rules), or a sing-box JSON configuration. Base64 node lists and individual node links are not subscription configurations.

YAML and Clash JSON are detected by their `proxies`, `proxy-providers` or `proxy-groups` fields. Sing-box JSON uses the original core. Import, manual refresh, automatic refresh and editor checks all use the matching validator. Invalid updates leave the previous configuration intact. Subscription tokens remain on the device; never paste private subscription URLs in public issues or commits.

## Runtime and controls

For a Clash profile, use **VPN mode** and connect. Mihomo executes Clash rules, providers, DNS and proxy protocols. Libbox supplies the Android VPN transport and forwards TCP/UDP to mihomo's authenticated loopback SOCKS listener; DNS is forwarded to mihomo's loopback resolver, preserving its fake-IP mapping. This is a transport bridge, not a native mihomo TUN integration or a full replacement of every SFA control API.

Open **Mihomo 节点** in the dashboard to select nodes in mihomo Selector groups. Automatic URLTest/Fallback groups continue to be selected by mihomo. The regular SFA connections/statistics describe the VPN bridge; they do not expose mihomo's internal routing decisions or its per-node delay measurements. Mihomo's listeners, TUN and controller settings in a subscription are overridden for local app management. The app UID bypasses the VPN to prevent routing mihomo's outbound sockets back into its own tunnel. Other applications still follow the VPN/per-app policy.

For sing-box JSON, existing SFA behavior is retained. YAML profiles cannot run in proxy-only or auto-redirect mode. Changing between configurations restarts the mihomo process; a failed core stops the VPN and reports an error.

## Cloud build

Pushes to `feature/mihomo-clash-subscriptions` trigger the workflow. All expensive compilation runs on GitHub-hosted Ubuntu. The libbox source is pinned to the matching `v1.15.0-alpha.13` tag; the official mihomo Android binary is pinned to v1.19.33 and verified using the release's SHA-256 digest. There are no signing or subscription secrets in the workflow. Only an APK containing both arm64 cores is distributed.

The APK requires on-device checks of VPN permission, DNS/fake-IP, TCP/UDP, per-app include/exclude, node selection, configuration update/reload, network changes, suspend/resume and stop/reconnect. A successful cloud build alone does not establish that these device behaviors work.

## Sources and licenses

- Android client: https://github.com/SagerNet/sing-box-for-android (GPL-3.0-or-later; see original LICENSE and naming restrictions).
- VPN transport: https://github.com/SagerNet/sing-box/tree/v1.15.0-alpha.13
- Mihomo: https://github.com/MetaCubeX/mihomo/tree/v1.19.33 (GPL-3.0; see upstream LICENSE and naming restrictions).
- SnakeYAML 2.4: https://bitbucket.org/snakeyaml/snakeyaml (Apache-2.0).

Corresponding fork source and the complete cloud build instructions are available in this repository.
