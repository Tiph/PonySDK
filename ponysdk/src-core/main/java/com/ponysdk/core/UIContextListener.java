
package com.ponysdk.core;

public interface UIContextListener {

    public void onUIContextDestroyed(UIContext uiContext);

    public void onBeforeBegin(UIContext uiContext);

    public void onAfterEnd(UIContext uiContext);
}