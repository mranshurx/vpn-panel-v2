# FF Capture

A no-root Android packet capture tool for Garena Free Fire.
Captures all game traffic to a `.pcap` file you can open in Wireshark.

## How it works

Free Fire's traffic is routed through a local `VpnService` TUN interface.
Every packet is copied to a libpcap file (both directions), then forwarded
onward so the game keeps working normally.

No memory injection, no file mods — so Garena cannot detect it.

## Build

```bash
./gradlew assembleDebug
