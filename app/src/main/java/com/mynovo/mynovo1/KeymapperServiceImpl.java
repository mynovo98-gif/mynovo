package com.mynovo.mynovo1;

import android.os.RemoteException;
import android.view.MotionEvent;
import java.lang.reflect.Method;

public class KeymapperServiceImpl extends IKeymapperService.Stub {

    public KeymapperServiceImpl() {
        super();
    }

    @Override
    public void injectMotionEvent(MotionEvent event) throws RemoteException {
        try {
            if (event != null) {
                Class<?> inputManagerCls = Class.forName("android.hardware.input.InputManager");
                Method getInstanceMethod = inputManagerCls.getDeclaredMethod("getInstance");
                getInstanceMethod.setAccessible(true);
                Object inputManagerObj = getInstanceMethod.invoke(null);

                Method injectMethod = null;
                try {
                    injectMethod = inputManagerCls.getDeclaredMethod(
                            "injectInputEvent", android.view.InputEvent.class, int.class);
                } catch (NoSuchMethodException e) {
                    injectMethod = inputManagerCls.getDeclaredMethod(
                            "injectInputEvent", android.view.InputEvent.class, int.class, int.class);
                }
                injectMethod.setAccessible(true);
                
                // INJECT_INPUT_EVENT_MODE_ASYNC = 0
                if (injectMethod.getParameterTypes().length == 3) {
                    injectMethod.invoke(inputManagerObj, event, 0, 0);
                } else {
                    injectMethod.invoke(inputManagerObj, event, 0);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}