#include "core/client_session.h"

#include <algorithm>
#include "core/window_node.h"
#include "core/display_runtime.h"
#include "native_screen.h"

namespace ludashi::x11 {

void ClientSession::ApplyPropertyCommand(u8 opcode) {
    switch (opcode) {
    case X_GetProperty: {
        const auto& request = *GetRequestData<xGetPropertyReq>();
        auto* window = GetResourceAs<WindowNode>(request.window);
        if (!window) throw BadWindowError(request.window);
        if (!server.Atoms().Contains(request.property)) throw BadAtomError(request.property);

        xGetPropertyReply reply{};
        reply.type = X_Reply;
        reply.sequenceNumber = sequence;
        const auto* property = window->GetProperty(request.property);
        if (!property) {
            WriteImmediately(&reply, sizeof(reply));
            return;
        }

        const auto bytes = property->GetData();
        reply.format = property->format;
        reply.propertyType = property->type;
        if (request.type != AnyPropertyType && request.type != property->type) {
            reply.bytesAfter = bytes.size();
            WriteImmediately(&reply, sizeof(reply));
            return;
        }

        const uint64_t offset = uint64_t{request.longOffset} * 4;
        if (offset > bytes.size()) throw BadValueError(request.longOffset);
        const size_t count = std::min<uint64_t>(uint64_t{request.longLength} * 4, bytes.size() - offset);
        const auto payload = bytes.subspan(offset, count);
        reply.length = (count + 3) / 4;
        reply.bytesAfter = bytes.size() - offset - count;
        reply.nItems = count / (reply.format / 8);
        Write(&reply, sizeof(reply));
        Write(payload.data(), payload.size());
        WritePad(-count & 3);
        Flush();

        if (request.c_delete && reply.bytesAfter == 0) {
            window->DeleteProperty(request.property);
            server.GetScreen().WindowPropertyChanged(window, request.property);
        }
        return;
    }
    case X_ChangeProperty: {
        const auto& request = *GetRequestData<xChangePropertyReq>();
        auto* window = GetResourceAs<WindowNode>(request.window);
        if (!window) throw BadWindowError(request.window);
        if (!server.Atoms().Contains(request.property)) throw BadAtomError(request.property);
        if (request.mode > PropModeAppend) throw BadValueError(request.mode);

        const auto format = request.format;
        if (format != 8 && format != 16 && format != 32) throw BadValueError(format);
        const uint64_t size = uint64_t{request.nUnits} * (format / 8);
        if ((size + 3) / 4 * 4 != request_size - sizeof(request)) throw BadLengthError();

        const auto payload = request_buffer.subspan(sizeof(request), size);
        if (!window->ModifyProperty(request.property, request.type, format, request.mode, payload))
            throw BadMatchError();

        server.GetScreen().WindowPropertyChanged(window, request.property);
        return;
    }
    case X_DeleteProperty: {
        const auto& request = *GetRequestData<xDeletePropertyReq>();
        auto* window = GetResourceAs<WindowNode>(request.window);
        if (!window) throw BadWindowError(request.window);
        if (!server.Atoms().Contains(request.property)) throw BadAtomError(request.property);

        window->DeleteProperty(request.property);
        server.GetScreen().WindowPropertyChanged(window, request.property);
        return;
    }
    default:
        throw BadRequestError();
    }
}

} // namespace ludashi::x11
