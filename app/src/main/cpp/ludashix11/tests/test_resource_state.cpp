#include "base/owned_descriptor.h"
#include "core/atom_catalog.h"
#include "core/client_id_ranges.h"

#include <cassert>
#include <fcntl.h>
#include <string>
#include <sys/wait.h>
#include <unistd.h>

int main() {
    ludashi::x11::AtomCatalog first;
    ludashi::x11::AtomCatalog second;
    assert(!first.Contains(0));
    assert(first.Find("WM_CLASS") == 67);
    const auto custom = first.Intern("_LUDASHI_RESOURCE_TEST");
    const auto name = first.Name(custom);
    for (int index = 0; index < 10000; ++index) first.Intern(std::to_string(index));
    assert(name == "_LUDASHI_RESOURCE_TEST");
    assert(first.Find(name) == static_cast<int32_t>(custom));
    assert(second.Find(name) == -1);
    assert(second.Count() == 69);

    for (uint32_t capacity : {2, 3, 4, 127, 128, 129}) {
        ludashi::x11::ClientIdRanges ids(capacity);
        std::vector<uint32_t> allocated;
        for (uint32_t index = 1; index < capacity; ++index) {
            const auto base = ids.Acquire();
            assert(ids.IsInInterval(base | ids.GetMask(), base));
            assert(!ids.IsInInterval(base ^ (ids.GetMask() + 1), base));
            allocated.push_back(base);
        }
        bool exhausted = false;
        try { (void)ids.Acquire(); } catch (const std::runtime_error&) { exhausted = true; }
        assert(exhausted);
        for (auto base : allocated) ids.Recycle(base);
        for (auto base : allocated) assert(ids.Acquire() == base);
        ids.Recycle(allocated[0]);
        bool duplicate = false;
        try { ids.Recycle(allocated[0]); } catch (const std::logic_error&) { duplicate = true; }
        assert(duplicate);
    }

    ludashi::x11::ClientIdRanges ranges(4);
    const auto firstBase = ranges.Acquire();
    ranges.Recycle(firstBase);
    assert(ranges.Acquire() > firstBase);
    assert(ranges.Acquire() > firstBase);
    assert(ranges.Acquire() == firstBase);

    // Descriptor zero is a valid owned descriptor. Isolate stdin changes.
    const auto child = fork();
    assert(child >= 0);
    if (child == 0) {
        close(STDIN_FILENO);
        int fd = open("/dev/null", O_RDONLY);
        if (fd != 0) _exit(2);
        {
            ludashi::x11::OwnedDescriptor owned(fd);
            owned.Reset(fd);
            auto moved = std::move(owned);
            if (owned || moved.Get() != fd || fcntl(fd, F_GETFD) < 0) _exit(3);
            owned = std::move(moved);
            if (moved || owned.Get() != fd) _exit(5);
        }
        if (fcntl(fd, F_GETFD) >= 0) _exit(4);
        _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
