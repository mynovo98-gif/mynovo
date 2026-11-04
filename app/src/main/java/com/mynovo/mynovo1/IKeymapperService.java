package com.mynovo.mynovo1;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.MotionEvent;

public interface IKeymapperService extends IInterface {
    void injectMotionEvent(MotionEvent event) throws RemoteException;

    abstract class Stub extends Binder implements IKeymapperService {
        private static final String DESCRIPTOR = "com.mynovo.mynovo1.IKeymapperService";

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static IKeymapperService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && iin instanceof IKeymapperService) {
                return (IKeymapperService) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            String descriptor = DESCRIPTOR;
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(descriptor);
                    return true;
                case TRANSACTION_injectMotionEvent:
                    data.enforceInterface(descriptor);
                    MotionEvent arg0;
                    if (data.readInt() != 0) {
                        arg0 = MotionEvent.CREATOR.createFromParcel(data);
                    } else {
                        arg0 = null;
                    }
                    this.injectMotionEvent(arg0);
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IKeymapperService {
            private IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            @Override
            public void injectMotionEvent(MotionEvent event) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeString(DESCRIPTOR);
                    if (event != null) {
                        data.writeInt(1);
                        event.writeToParcel(data, 0);
                    } else {
                        data.writeInt(0);
                    }
                    mRemote.transact(TRANSACTION_injectMotionEvent, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }

        static final int TRANSACTION_injectMotionEvent = (IBinder.FIRST_CALL_TRANSACTION + 0);
    }
}