#include "cursor.hpp"

void CursorManager::releaseRootCursorImage() {
    if (rootCursor && rootCursor->image && rootCursor->image->ahb) {
        AHardwareBuffer_release(rootCursor->image->ahb);
        rootCursor->image->ahb = nullptr;
    }
}

CursorManager::~CursorManager() {
    releaseRootCursorImage();
}

void CursorManager::addCursor(int id, std::unique_ptr<struct Cursor> cursor) {
    auto lock = cursorLock.lock();
    this->cursors[id] = std::move(cursor);
}

void CursorManager::removeCursor(JNIEnv *env, Cursor *cursor) {
    env->DeleteGlobalRef(cursor->cursorObj);
    env->DeleteGlobalRef(cursor->image->drawableObj);
    
    {
        auto lock = cursorLock.lock();
        this->cursors.erase(cursor->id);
    }
}

Cursor* CursorManager::getCursor(int id) {
    auto lock = cursorLock.lock();
    return this->cursors[id].get();
}

void CursorManager::setRootCursor(std::unique_ptr<struct Cursor> cursor) {
    releaseRootCursorImage();
    this->rootCursor = std::move(cursor);
}

Cursor* CursorManager::getRootCursor() {
    return this->rootCursor.get();
}
