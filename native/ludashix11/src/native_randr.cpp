#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window_manager.h"
#include "core/window.h"
#include "native_screen.h"
#include <X11/extensions/randrproto.h>

namespace xorg {
void XClient::ProcRandR() {
    auto* req = GetRequestData<xReq>();
    const auto& screen = server.GetScreenInfo();
    constexpr u32 crtc = 0x100001, output = 0x100002, modeBase = 0x100003;
    const u32 timestamp = 1;
    const u32 root = server.GetWindowManager().GetRootWindow()->id;
    auto& native = static_cast<NativeScreen&>(server.GetScreen());
    const auto& rates = native.RandrRates();
    const u16 rate = native.RandrRate();
    const u32 mode = modeBase + std::distance(rates.begin(), std::find(rates.begin(), rates.end(), rate));
    auto send = [this](auto& reply) { reply.type = X_Reply; reply.sequenceNumber = sequence; WriteImmediately(&reply, sizeof(reply)); };
    switch (req->data) {
    case X_RRQueryVersion: {
        CHECK_REQUEST_SIZE_STRICT(xRRQueryVersionReq);
        xRRQueryVersionReply reply{}; reply.majorVersion = 1; reply.minorVersion = 2;
        send(reply); return;
    }
    case X_RRSelectInput: CHECK_REQUEST_SIZE_STRICT(xRRSelectInputReq); return;
    case X_RRGetScreenSizeRange: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetScreenSizeRangeReq);
        xRRGetScreenSizeRangeReply reply{};
        reply.minWidth = reply.maxWidth = screen.width; reply.minHeight = reply.maxHeight = screen.height;
        send(reply); return;
    }
    case X_RRGetScreenInfo: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetScreenInfoReq);
        xRRGetScreenInfoReply reply{};
        const size_t body = 8 + (1 + rates.size()) * 2;
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.length = (body + 3) / 4;
        reply.root = root; reply.timestamp = reply.configTimestamp = timestamp;
        reply.nSizes = 1; reply.rotation = reply.setOfRotations = RR_Rotate_0;
        reply.rate = rate; reply.nrateEnts = 1 + rates.size();
        xScreenSizes size{screen.width, screen.height, screen.GetWidthInMillimeters(), screen.GetHeightInMillimeters()};
        const u16 count = rates.size();
        Write(&reply, sizeof(reply)); Write(&size, sizeof(size)); Write(&count, 2);
        Write(rates.data(), rates.size() * 2); WritePad((-body) & 3); Flush(); return;
    }
    case X_RRSetScreenConfig: {
        CHECK_REQUEST_SIZE(xRR1_0SetScreenConfigReq);
        auto* r = GetRequestData<xRR1_0SetScreenConfigReq>();
        xRRSetScreenConfigReply reply{}; reply.status = r->sizeID == 0 ? RRSetConfigSuccess : RRSetConfigFailed;
        if (reply.status == RRSetConfigSuccess && request_size >= sizeof(xRRSetScreenConfigReq)) {
            u16 requested = GetRequestData<xRRSetScreenConfigReq>()->rate;
            if (requested && !native.SelectRandrRate(requested)) reply.status = RRSetConfigFailed;
        }
        reply.root = root; reply.newTimestamp = reply.newConfigTimestamp = timestamp;
        send(reply); return;
    }
    case X_RRSetScreenSize: {
        CHECK_REQUEST_SIZE_STRICT(xRRSetScreenSizeReq);
        auto* r = GetRequestData<xRRSetScreenSizeReq>();
        if (r->width != screen.width || r->height != screen.height) throw BadMatchError();
        return;
    }
    case X_RRGetScreenResources:
    case X_RRGetScreenResourcesCurrent: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetScreenResourcesReq);
        std::string name = std::to_string(screen.width) + "x" + std::to_string(screen.height);
        xRRGetScreenResourcesReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence;
        const size_t namesSize = name.size() * rates.size();
        reply.length = (8 + rates.size() * sizeof(xRRModeInfo) + ((namesSize + 3) & ~3)) / 4;
        reply.timestamp = reply.configTimestamp = timestamp;
        reply.nCrtcs = reply.nOutputs = 1; reply.nModes = rates.size(); reply.nbytesNames = namesSize;
        Write(&reply, sizeof(reply)); Write(&crtc, 4); Write(&output, 4);
        for (size_t i = 0; i < rates.size(); ++i) {
            xRRModeInfo info{}; info.id = modeBase + i; info.width = screen.width; info.height = screen.height;
            info.hTotal = screen.width; info.vTotal = screen.height;
            info.dotClock = std::min<u64>(u64(screen.width) * screen.height * rates[i], UINT32_MAX);
            info.nameLength = name.size(); Write(&info, sizeof(info));
        }
        for (size_t i = 0; i < rates.size(); ++i) Write(name.data(), name.size());
        WritePad((-namesSize) & 3); Flush(); return;
    }
    case X_RRGetOutputInfo: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetOutputInfoReq);
        if (GetRequestData<xRRGetOutputInfoReq>()->output != output) throw BadValueError(0);
        const std::string name = "Ludashi";
        xRRGetOutputInfoReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.status = RRSetConfigSuccess;
        reply.length = (sizeof(reply) - 32 + 4 + rates.size() * 4 + ((name.size() + 3) & ~3)) / 4;
        reply.timestamp = timestamp; reply.crtc = crtc; reply.connection = RR_Connected;
        reply.mmWidth = screen.GetWidthInMillimeters(); reply.mmHeight = screen.GetHeightInMillimeters();
        reply.nCrtcs = reply.nPreferred = 1; reply.nModes = rates.size(); reply.nameLength = name.size();
        Write(&reply, sizeof(reply)); Write(&crtc, 4);
        for (size_t i = 0; i < rates.size(); ++i) { const u32 id = modeBase + i; Write(&id, 4); }
        Write(name.data(), name.size()); WritePad((4 - name.size() % 4) % 4); Flush(); return;
    }
    case X_RRGetCrtcInfo: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetCrtcInfoReq);
        if (GetRequestData<xRRGetCrtcInfoReq>()->crtc != crtc) throw BadValueError(0);
        xRRGetCrtcInfoReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.length = 2;
        reply.timestamp = timestamp; reply.width = screen.width; reply.height = screen.height;
        reply.mode = mode; reply.rotation = reply.rotations = RR_Rotate_0;
        reply.nOutput = reply.nPossibleOutput = 1;
        Write(&reply, sizeof(reply)); Write(&output, 4); Write(&output, 4); Flush(); return;
    }
    case X_RRSetCrtcConfig: {
        CHECK_REQUEST_SIZE(xRRSetCrtcConfigReq);
        auto* r = GetRequestData<xRRSetCrtcConfigReq>();
        xRRSetCrtcConfigReply reply{};
        reply.status = (r->crtc == crtc && r->mode >= modeBase && r->mode - modeBase < rates.size() &&
                        r->x == 0 && r->y == 0 && native.SelectRandrRate(rates[r->mode - modeBase]))
                       ? RRSetConfigSuccess : RRSetConfigFailed;
        reply.newTimestamp = timestamp; send(reply); return;
    }
    case X_RRListOutputProperties: {
        CHECK_REQUEST_SIZE_STRICT(xRRListOutputPropertiesReq);
        xRRListOutputPropertiesReply reply{}; send(reply); return;
    }
    case X_RRGetOutputProperty: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetOutputPropertyReq);
        xRRGetOutputPropertyReply reply{}; send(reply); return;
    }
    case X_RRGetCrtcGammaSize: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetCrtcGammaSizeReq);
        xRRGetCrtcGammaSizeReply reply{}; reply.size = 256; send(reply); return;
    }
    case X_RRGetCrtcGamma: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetCrtcGammaReq);
        xRRGetCrtcGammaReply reply{}; reply.size = 256; reply.length = 384;
        reply.type = X_Reply; reply.sequenceNumber = sequence;
        u16 values[256]; for (u32 i = 0; i < 256; ++i) values[i] = i * 257;
        Write(&reply, sizeof(reply)); for (int i = 0; i < 3; ++i) Write(values, sizeof(values)); Flush(); return;
    }
    case X_RRSetCrtcGamma: CHECK_REQUEST_SIZE(xRRSetCrtcGammaReq); return;
    default: throw BadRequestError();
    }
}
}
