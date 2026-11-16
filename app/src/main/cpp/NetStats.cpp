// Per-session traffic summary and WFC protocol timeline for the network log.
// Same format as the Windows client, so logs from phones and PCs read alike.
#include "NetStats.h"

#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>

using melonDS::u8;

// --- Network diagnostics -------------------------------------------------------
// Counts the console's IPv4 traffic per remote endpoint, so a failed battle or trade
// shows where packets stop: sent to the other player but nothing back points at NAT or
// a firewall; ICMP errors point at the path in between.

namespace ndscore
{

struct EndpointStats
{
    uint64_t udpOut = 0, udpIn = 0, tcpOut = 0, tcpIn = 0;
    std::chrono::steady_clock::time_point first, last;
};

struct NetStats
{
    std::mutex mutex;
    uint32_t consoleIp = 0;
    std::map<std::pair<uint32_t, uint16_t>, EndpointStats> endpoints;
    uint64_t icmpToConsole = 0;
    std::string icmpLog;
    // Chronological log of WFC protocol steps (matchmaking messages, NAT negotiation,
    // first contact with each peer), which is where a failed battle or trade breaks.
    std::string timeline;
    int timelineLines = 0;
    uint64_t qr2Heartbeats = 0, qr2Keepalives = 0;
    int dumps = 0;
    std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
};

NetStats g_netStats;

uint32_t Be32(const u8* p) { return (uint32_t(p[0]) << 24) | (p[1] << 16) | (p[2] << 8) | p[3]; }
uint16_t Be16(const u8* p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }

std::string IpText(uint32_t ip)
{
    char text[16];
    snprintf(text, sizeof(text), "%u.%u.%u.%u", ip >> 24, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF, ip & 0xFF);
    return text;
}

constexpr int kTimelineMax = 600;

void AddEvent(const char* fmt, ...)
{
    if (g_netStats.timelineLines >= kTimelineMax) return;
    char text[240];
    va_list args;
    va_start(args, fmt);
    vsnprintf(text, sizeof(text), fmt, args);
    va_end(args);
    const double t = std::chrono::duration<double>(std::chrono::steady_clock::now() - g_netStats.start).count();
    char line[280];
    snprintf(line, sizeof(line), "  %7.2fs  %s\n", t, text);
    g_netStats.timeline += line;
    if (++g_netStats.timelineLines == kTimelineMax) g_netStats.timeline += "  ... (troncato)\n";
}

constexpr int kDumpMax = 40;

// Appends a hex + ASCII dump of a payload under the last timeline event.
void AddDump(const u8* p, int len, int maxBytes = 128)
{
    if (g_netStats.timelineLines >= kTimelineMax || g_netStats.dumps >= kDumpMax || len <= 0) return;
    g_netStats.dumps++;
    const int n = std::min(len, maxBytes);
    for (int off = 0; off < n; off += 16)
    {
        char line[120];
        int pos = snprintf(line, sizeof(line), "              %04X ", off);
        for (int i = 0; i < 16; i++)
            pos += snprintf(line + pos, sizeof(line) - pos, off + i < n ? " %02X" : "   ", off + i < n ? p[off + i] : 0);
        pos += snprintf(line + pos, sizeof(line) - pos, "  ");
        for (int i = 0; i < 16 && off + i < n; i++)
            line[pos++] = (p[off + i] >= 0x20 && p[off + i] < 0x7F) ? static_cast<char>(p[off + i]) : '.';
        line[pos++] = '\n';
        line[pos] = 0;
        g_netStats.timeline += line;
        g_netStats.timelineLines++;
    }
    if (len > n)
    {
        g_netStats.timeline += "              ... (" + std::to_string(len) + " byte in totale)\n";
        g_netStats.timelineLines++;
    }
}

std::string EndpointText(uint32_t ip, uint16_t port)
{
    return IpText(ip) + ":" + std::to_string(port);
}

bool IsWellKnownPort(uint16_t port)
{
    return port == 53 || port == 67 || port == 68 || port == 80 || port == 443 || port == 27900 ||
           port == 27901 || port == 28910 || port == 29900 || port == 29901;
}

const char* Qr2Name(uint8_t type, bool outgoing)
{
    if (outgoing)
    {
        switch (type)
        {
        case 0x01: return "risposta al challenge";
        case 0x03: return "heartbeat";
        case 0x07: return "conferma del messaggio di collegamento";
        case 0x08: return "keepalive";
        case 0x09: return "richiesta disponibilita";
        default: return nullptr;
        }
    }
    switch (type)
    {
    case 0x01: return "challenge";
    case 0x06: return "MESSAGGIO DI COLLEGAMENTO (un altro giocatore vuole connettersi)";
    case 0x08: return "keepalive";
    case 0x09: return "risposta disponibilita";
    case 0x0A: return "registrazione completata";
    default: return nullptr;
    }
}

const char* NatNegName(uint8_t type)
{
    static const char* names[] = {
        "INIT", "INIT_ACK", "ERT_TEST", "ERT_ACK", "STATE_UPDATE", "CONNECT", "CONNECT_ACK", "CONNECT_PING",
        "BACKUP_TEST", "BACKUP_ACK", "ADDRESS_CHECK", "ADDRESS_REPLY", "NATIFY_REQUEST", "REPORT",
        "REPORT_ACK", "PREINIT", "PREINIT_ACK",
    };
    return type < sizeof(names) / sizeof(names[0]) ? names[type] : "sconosciuto";
}

void RecordUdpPayload(uint32_t remote, uint16_t remotePort, uint16_t localPort, const u8* p, int plen, bool outgoing,
                      bool firstInDirection)
{
    const char* dir = outgoing ? "->" : "<-";
    static const u8 kNatNegMagic[6] = {0xFD, 0xFC, 0x1E, 0x66, 0x6A, 0xB2};

    if (plen >= 12 && std::memcmp(p, kNatNegMagic, 6) == 0)
    {
        const uint8_t type = p[7];
        const uint32_t cookie = Be32(p + 8);
        char extra[96] = "";
        if (type == 0x05 && plen >= 18)        // CONNECT: where the server says the peer is
            snprintf(extra, sizeof(extra), " peer=%s", EndpointText(Be32(p + 12), Be16(p + 16)).c_str());
        else if (type == 0x00 && plen >= 21)   // INIT: the console's own private address
            snprintf(extra, sizeof(extra), " tipo-porta=%u indirizzo-locale=%s", p[12],
                     EndpointText(Be32(p + 15), Be16(p + 19)).c_str());
        else if (type == 0x0D && plen >= 15)   // REPORT: port type, client index, outcome
            snprintf(extra, sizeof(extra), " esito=%u", p[14]);
        AddEvent("NATNEG %s %s %s (porta console %u, cookie %08X)%s", NatNegName(type), dir,
                 EndpointText(remote, remotePort).c_str(), localPort, cookie, extra);
        return;
    }

    if (remotePort == 27900 && plen >= 1)
    {
        // Server-to-console packets start with FE FD, followed by the type.
        const bool framed = !outgoing && plen >= 3 && p[0] == 0xFE && p[1] == 0xFD;
        const uint8_t type = framed ? p[2] : p[0];
        if (type == 0x03 && outgoing)
        {
            if (g_netStats.qr2Heartbeats++ == 0)
                AddEvent("MASTER heartbeat %s %s (porta console %u)", dir, EndpointText(remote, remotePort).c_str(), localPort);
            return;
        }
        if (type == 0x08)
        {
            g_netStats.qr2Keepalives++;
            return;
        }
        const char* name = Qr2Name(type, outgoing);
        if (name) AddEvent("MASTER %s %s %s (porta console %u, %d byte)", name, dir, EndpointText(remote, remotePort).c_str(), localPort, plen);
        else AddEvent("MASTER tipo 0x%02X %s %s (porta console %u, %d byte)", type, dir, EndpointText(remote, remotePort).c_str(), localPort, plen);
        // The connection request and its acknowledgement are what a failed battle
        // or trade hinges on; keep their bytes.
        if (type == 0x06 || type == 0x07 || !name) AddDump(p, plen);
        return;
    }

    if (firstInDirection && !IsWellKnownPort(remotePort))
        AddEvent("primo pacchetto UDP %s %s (porta console %u, %d byte)", dir,
                 EndpointText(remote, remotePort).c_str(), localPort, plen);
}

void RecordGpPayload(const u8* p, int plen, bool outgoing)
{
    // GameSpy presence uses \key\value text; \bm\<n> marks a message between friends,
    // which is how one console asks another to connect. Only the type is logged.
    for (int i = 0; i + 4 < plen; i++)
    {
        if (std::memcmp(p + i, "\\bm\\", 4) != 0) continue;
        int type = 0, j = i + 4;
        while (j < plen && p[j] >= '0' && p[j] <= '9') type = type * 10 + (p[j++] - '0');
        AddEvent("AMICI messaggio tipo %d %s", type, outgoing ? "inviato" : "ricevuto");
        // Type 1 carries the game's own matchmaking commands; status updates (100) are skipped.
        if (type != 100)
        {
            int end = j;
            while (end < plen && end - i < 400 && !(p[end] == '\\' && end + 6 < plen && std::memcmp(p + end, "\\final\\", 7) == 0)) end++;
            AddDump(p + i, end - i, 256);
        }
    }
}

void RecordFrame(const u8* frame, int len, bool outgoing)
{
    if (len < 34 || Be16(frame + 12) != 0x0800) return;
    const u8* ip = frame + 14;
    const int ihl = (ip[0] & 0x0F) * 4;
    if (len < 14 + ihl + 4) return;
    const uint8_t proto = ip[9];
    const uint32_t src = Be32(ip + 12), dst = Be32(ip + 16);
    const u8* l4 = ip + ihl;

    std::lock_guard lock(g_netStats.mutex);
    if (outgoing && src != 0) g_netStats.consoleIp = src;
    // In Npcap mode the console also sees other machines' broadcast traffic; skip it.
    if (!outgoing && g_netStats.consoleIp && dst != g_netStats.consoleIp) return;

    if (proto == 17 || proto == 6)
    {
        const uint32_t remote = outgoing ? dst : src;
        const uint16_t port = outgoing ? Be16(l4 + 2) : Be16(l4);
        auto& e = g_netStats.endpoints[{remote, port}];
        const auto now = std::chrono::steady_clock::now();
        if (e.first == std::chrono::steady_clock::time_point{}) e.first = now;
        e.last = now;

        const int ipTotal = Be16(ip + 2);
        const int l4Len = std::min(ipTotal, len - 14) - ihl;
        if (proto == 17 && l4Len >= 8)
        {
            const bool first = outgoing ? e.udpOut == 0 : e.udpIn == 0;
            const uint16_t localPort = outgoing ? Be16(l4) : Be16(l4 + 2);
            RecordUdpPayload(remote, port, localPort, l4 + 8, std::min<int>(Be16(l4 + 4), l4Len) - 8, outgoing, first);
        }
        else if (proto == 6 && port == 29900 && l4Len >= 20)
        {
            const int dataOffset = (l4[12] >> 4) * 4;
            if (l4Len > dataOffset) RecordGpPayload(l4 + dataOffset, l4Len - dataOffset, outgoing);
        }

        if (proto == 17) (outgoing ? e.udpOut : e.udpIn)++;
        else (outgoing ? e.tcpOut : e.tcpIn)++;
    }
    else if (proto == 1 && !outgoing)
    {
        g_netStats.icmpToConsole++;
        if (g_netStats.icmpLog.size() < 4000 && len >= 14 + ihl + 8 + 20)
        {
            // The ICMP payload quotes the console's original IP header + 8 bytes of UDP.
            const u8* quoted = l4 + 8;
            const int qihl = (quoted[0] & 0x0F) * 4;
            const bool hasPort = len >= 14 + ihl + 8 + qihl + 4;
            const double t = std::chrono::duration<double>(std::chrono::steady_clock::now() - g_netStats.start).count();
            char line[200];
            snprintf(line, sizeof(line), "  %7.1fs ICMP tipo %u codice %u da %s per il pacchetto verso %s:%u\n",
                     t, l4[0], l4[1], IpText(src).c_str(), IpText(Be32(quoted + 16)).c_str(),
                     hasPort ? Be16(quoted + qihl + 2) : 0);
            g_netStats.icmpLog += line;
        }
    }
}

const char* PortLabel(uint16_t port)
{
    switch (port)
    {
    case 53: return "DNS";
    case 80: return "HTTP";
    case 443: return "HTTPS";
    case 27900: return "GameSpy master";
    case 27901: return "NAT negotiation";
    case 28910: return "GameSpy server browser";
    case 29900: return "GameSpy profile";
    case 29901: return "GameSpy search";
    default: return "";
    }
}

std::string BuildNetReport(const std::string& connection)
{
    std::string out = "Connessione: " + connection + "\n";
    std::lock_guard lock(g_netStats.mutex);
    out += "Indirizzo della console: " + (g_netStats.consoleIp ? IpText(g_netStats.consoleIp) : std::string("-")) + "\n";
    const double total = std::chrono::duration<double>(std::chrono::steady_clock::now() - g_netStats.start).count();
    char line[256];
    snprintf(line, sizeof(line), "Durata sessione: %.0fs, errori ICMP ricevuti dalla console: %llu\n\n",
             total, static_cast<unsigned long long>(g_netStats.icmpToConsole));
    out += line;
    out += "Remoto            Porta   UDP inv/ric    TCP inv/ric    attivo (s)       Servizio\n";
    for (const auto& [key, e] : g_netStats.endpoints)
    {
        const double from = std::chrono::duration<double>(e.first - g_netStats.start).count();
        const double to = std::chrono::duration<double>(e.last - g_netStats.start).count();
        snprintf(line, sizeof(line), "%-16s %6u   %5llu/%-6llu   %5llu/%-6llu   %6.1f-%-8.1f %s\n",
                 IpText(key.first).c_str(), key.second,
                 static_cast<unsigned long long>(e.udpOut), static_cast<unsigned long long>(e.udpIn),
                 static_cast<unsigned long long>(e.tcpOut), static_cast<unsigned long long>(e.tcpIn),
                 from, to, PortLabel(key.second));
        out += line;
    }
    if (!g_netStats.icmpLog.empty()) out += "\nErrori ICMP:\n" + g_netStats.icmpLog;
    snprintf(line, sizeof(line), "\nCronologia WFC (heartbeat al master: %llu, keepalive: %llu):\n",
             static_cast<unsigned long long>(g_netStats.qr2Heartbeats), static_cast<unsigned long long>(g_netStats.qr2Keepalives));
    out += line;
    out += g_netStats.timeline.empty() ? "  (nessun evento)\n" : g_netStats.timeline;
    return out;
}

void ResetNetStats()
{
    std::lock_guard lock(g_netStats.mutex);
    g_netStats.consoleIp = 0;
    g_netStats.endpoints.clear();
    g_netStats.icmpToConsole = 0;
    g_netStats.icmpLog.clear();
    g_netStats.timeline.clear();
    g_netStats.timelineLines = 0;
    g_netStats.qr2Heartbeats = g_netStats.qr2Keepalives = 0;
    g_netStats.start = std::chrono::steady_clock::now();
}

}
