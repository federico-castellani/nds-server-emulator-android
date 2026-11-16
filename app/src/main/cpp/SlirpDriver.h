// User-mode NAT network driver for the emulated Wi-Fi, built on libslirp (Android).
#pragma once

#include <poll.h>

#include <cstdint>

#include <NetDriver.h>
#include <Platform.h>

struct Slirp;

namespace ndscore
{

/// How the console is addressed and whether it can reach consoles on other devices.
struct SlirpAddressing
{
    uint32_t guestIp = 0x0A400010;   // 10.64.0.16, melonDS' default
    uint32_t hostIp = 0x0A400001;    // gateway seen by the console
    uint32_t dnsIp = 0x0A400002;
    uint32_t netmask = 0xFFFFFF00;

    /// This device's LAN address; 0 disables the LAN relay.
    uint32_t lanIp = 0;
    uint16_t relayPort = 28950;

    /// Gives the console a LAN-unique address derived from the device's LAN address
    /// (192.168.1.23 -> 10.168.1.23), so consoles on different devices never collide.
    static SlirpAddressing ForLan(uint32_t lanIp, uint16_t relayPort = 28950);

    /// IPv4 address of the first non-loopback interface that is up (Wi-Fi first), or 0.
    static uint32_t DetectLanAddress();
};

/// Same driver as the Windows client (see SlirpDriver.cpp): fixes Nintendo WFC
/// peer-to-peer between players on the same network. Addresses are in host byte order.
class SlirpDriver final : public melonDS::NetDriver
{
public:
    SlirpDriver(const melonDS::Platform::SendPacketCallback& callback, const SlirpAddressing& addressing) noexcept;
    ~SlirpDriver() noexcept override;
    SlirpDriver(const SlirpDriver&) = delete;
    SlirpDriver& operator=(const SlirpDriver&) = delete;

    int SendPacket(melonDS::u8* data, int len) noexcept override;
    void RecvCheck() noexcept override;

    bool IsValid() const noexcept { return Ctx != nullptr; }
    bool RelayActive() const noexcept { return Relay >= 0; }
    const SlirpAddressing& Addressing() const noexcept { return Addr; }

    /// Hands a frame produced by slirp to the emulated Wi-Fi chip.
    void DeliverToGuest(const melonDS::u8* data, int len) noexcept
    {
        if (Callback) Callback(data, len);
    }

private:
    static constexpr int PollListMax = 256;

    static int AddPoll(int fd, int events, void* opaque) noexcept;
    static int GetREvents(int idx, void* opaque) noexcept;

    bool IsPeerConsole(uint32_t ip) const noexcept;
    bool HandleArp(const melonDS::u8* data, int len) noexcept;
    bool RelayToPeer(const melonDS::u8* data, int len) noexcept;
    void ReceiveFromPeers() noexcept;

    melonDS::Platform::SendPacketCallback Callback;
    SlirpAddressing Addr;
    ::Slirp* Ctx = nullptr;
    int Relay = -1;
    melonDS::u8 GuestMac[6] {};
    bool GuestMacKnown = false;
    uint16_t IpId = 1;
    pollfd PollList[PollListMax] {};
    int PollListSize = 0;
};

}
