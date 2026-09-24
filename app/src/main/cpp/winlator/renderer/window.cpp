#include "window.hpp"

void WindowManager::changeZOrder(int stackMode, Window *window, Window *sibling) {
    auto parent = window->parent;
    
    if (!parent)
        return;
        
    parent->children.erase(std::remove(parent->children.begin(), parent->children.end(), window), parent->children.end());
    
    if (sibling) {
        auto it = std::find(parent->children.begin(), parent->children.end(), sibling);
        
        if (it != parent->children.end()) {
            if (stackMode == 1) {
                parent->children.insert(it + 1, window);
                window->z_order = sibling->z_order + 1;
            }
            else {
                parent->children.insert(it, window);
                window->z_order = sibling->z_order - 1;
            }
            return;
        }
    }
    
    if (stackMode == 1) {
        parent->children.push_back(window);
        int max = 0;
        for (auto& child : window->parent->children) {
            if (child->z_order > max)
                max = child->z_order;
        }
        window->z_order = max + 1;
    }    
    else {
        parent->children.insert(parent->children.begin(), window);
        int min = 0;
        for (auto& child : window->parent->children) {
            if (child->z_order < min)
                min = child->z_order;
        }
        window->z_order = min - 1;
    }    
}

void WindowManager::addWindow(int id, std::unique_ptr<struct Window> window) {
    auto lock = windowLock.lock();
    this->windows[id] = std::move(window);
}

Window* WindowManager::getWindow(int id) {
    auto lock = windowLock.lock();
    auto it = windows.find(id);
    if (it == windows.end()) return nullptr;
    
    return it->second.get();
}

void WindowManager::deleteWindow(Window *window) {
    for (auto childCopy = window->children; auto& child : childCopy)
        deleteWindow(child);
        
    auto parent = window->parent;
    
    if (parent) {
        parent->children.erase(std::remove(parent->children.begin(), 
            parent->children.end(), window), parent->children.end());
        if (window->isSurface() && parent->surface == window)
            parent->surface = nullptr;
    }
    
    {
        auto lock = windowLock.lock();
        this->windows.erase(window->id);
    }
}

void WindowManager::reparentWindow(Window *window, Window *parent) {
    auto oldParent = window->parent;
    
    if (oldParent != nullptr) {
        oldParent->children.erase(std::remove(oldParent->children.begin(),
            oldParent->children.end(), window), oldParent->children.end());
    }
    
    window->parent = parent;
    parent->children.push_back(window);
}

void WindowManager::setRootWindow(Window *window) {
    this->rootWindow = window;
}

Window *WindowManager::getRootWindow() {
    return this->rootWindow;
}

std::unordered_map<int, std::unique_ptr<struct Window>>& WindowManager::getWindowTree() {
    return this->windows;
}

void WindowManager::setUnviewableWMClass(std::string className) {
    this->unviewableWMClass = className;
}

std::string WindowManager::getUnviewableWMClass() {
    return this->unviewableWMClass;
}