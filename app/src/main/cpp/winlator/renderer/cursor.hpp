#pragma once

#include <unordered_map>

#include "drawable.hpp"
#include "renderer_jni.hpp"

struct Cursor {
    int id;
    std::unique_ptr<struct Drawable> image;
    int hotspotX;
    int hotspotY;
    bool visible;
};

struct Pointer {
    int posX;
    int posY;
};

struct CursorLock {
    std::mutex mutex;
   
   std::unique_lock<std::mutex> lock() {
       return std::unique_lock<std::mutex>(mutex);
   }
};

class CursorManager {
    private:
        std::unordered_map<int, std::unique_ptr<struct Cursor>> cursors;
        std::unique_ptr<struct Cursor> rootCursor;
        
    public: 
        Pointer pointer{0, 0};
        ASurfaceControl *control;
        CursorLock cursorLock;
        
        void setRootCursor(std::unique_ptr<struct Cursor> cursor);
        Cursor *getRootCursor();
        void addCursor(int id, std::unique_ptr<struct Cursor> cursor);
        void removeCursor(JNIEnv *env, Cursor *cursor);
        Cursor *getCursor(int id);
};