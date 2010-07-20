package com.bridj;

import com.bridj.AbstractBridJRuntime;
import java.io.FileNotFoundException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import com.bridj.BridJ;
import com.bridj.BridJRuntime;
import com.bridj.Callback;
import com.bridj.MethodCallInfo;
import com.bridj.NativeEntities;
import com.bridj.NativeLibrary;
import com.bridj.NativeObject;
import com.bridj.Pointer;
import com.bridj.Demangler.Symbol;
import com.bridj.NativeEntities.Builder;
import com.bridj.ann.Struct;
import com.bridj.ann.Virtual;
import com.bridj.cpp.CPPObject;
import com.bridj.util.AutoHashMap;
import java.lang.reflect.Type;

public class CRuntime extends AbstractBridJRuntime {

	final Set<Type> registeredTypes = new HashSet<Type>();
	final CallbackNativeImplementer callbackNativeImplementer;

    public CRuntime() {
        callbackNativeImplementer = new CallbackNativeImplementer(BridJ.getOrphanEntities(), this);
    }
    public boolean isAvailable() {
        return true;
    }
    
    
	@Override
	public <T extends NativeObject> Class<? extends T> getActualInstanceClass(Pointer<T> pInstance, Type officialType) {
		return (Class<? extends T>)Utils.getClass(officialType);
	}

    public class CTypeInfo<T extends NativeObject> implements TypeInfo<T> {
        public CTypeInfo(Type type) {
            this.type = type;
            this.typeClass = (Class<T>)Utils.getClass(type);
            this.structIO = StructIO.getInstance(typeClass, typeClass, null);
            this.pointerIO = (PointerIO<T>)PointerIO.getInstance(structIO);
            //this.castClass = getTypeForCast(typeClass);
            register(typeClass);
        }
        protected final Type type;
        protected final Class<T> typeClass;
		protected final StructIO structIO;
		protected final PointerIO<T> pointerIO;
        protected Class<?> castClass;
		
        @Override
        public BridJRuntime getRuntime() {
            return CRuntime.this;
        }
        @Override
        public Type getType() {
            return type;
        }
        
        synchronized Class<?> getCastClass() {
            if (castClass == null)
                castClass = getTypeForCast(typeClass);
            return castClass;
        }

        @Override
        public T cast(Pointer peer) {
            try {
                T instance = (T)getCastClass().newInstance(); // TODO template parameters here !!!
                initialize(instance, peer);
                return instance;
            } catch (Exception ex) {
                throw new RuntimeException("Failed to cast pointer " + peer + " to instance of type " + typeClass.getName(), ex);
            }
        }

        @Override
        public void initialize(T instance) {
            if (!BridJ.isCastingNativeObjectInCurrentThread()) {
                if (instance instanceof Callback<?>)
                    setNativeObjectPeer(instance, registerCallbackInstance((Callback<?>)instance));
                else
                    initialize(instance, -1, structIO);
            } else if (instance instanceof StructObject) {
                ((StructObject)instance).io = structIO;
            }
        }
        @Override
        public void initialize(T instance, Pointer peer) {
            instance.peer = peer;
            if (instance instanceof StructObject)
                ((StructObject)instance).io = structIO;
        }

        @Override
        public void initialize(T instance, int constructorId, Object... args) {
            StructObject s = (StructObject)instance;
            if (constructorId < 0) {
                s.io = structIO; 
                instance.peer = Pointer.allocate(pointerIO, structIO.getStructSize());
            } else
                throw new UnsupportedOperationException("TODO implement structs constructors !");
        }

        @Override
        public T clone(T instance) throws CloneNotSupportedException {
            if (instance == null)
                return null;

            try {
                T clone = (T)typeClass.newInstance();
                Pointer<T> p = Pointer.allocate(pointerIO);
                Pointer.pointerTo(instance).copyTo(p);
                initialize(clone, p);
                return clone;
            } catch (Exception ex) {
                throw new RuntimeException("Failed to clone instance of type " + getType());
            }
        }
        
        @Override
        public void destroy(T instance) {
            if (instance instanceof Callback)
                return;        
        }
    }
    /// Needs not be fast : TypeInfo will be cached in BridJ anyway !
	@Override
	public <T extends NativeObject> TypeInfo<T> getTypeInfo(final Type type) {
        return new CTypeInfo(type);
	}
	@Override
	public void register(Type type) {
		if (!registeredTypes.add(type))
			return;

        Class typeClass = Utils.getClass(type);
        assert log(Level.INFO, "Registering type " + typeClass.getName());
        
		int typeModifiers = typeClass.getModifiers();
		
		AutoHashMap<NativeEntities, NativeEntities.Builder> builders = new AutoHashMap<NativeEntities, NativeEntities.Builder>(NativeEntities.Builder.class);
		try {
            Set<Method> handledMethods = new HashSet<Method>();
			if (StructObject.class.isAssignableFrom(typeClass)) {
				StructIO io = StructIO.getInstance(typeClass, type, this); // TODO handle differently with templates...
                io.build();
                StructIO.FieldIO[] fios = io == null ? null : io.getFields();
                if (fios != null)
                    for (StructIO.FieldIO fio : fios) {
                        NativeEntities.Builder builder = builders.get(BridJ.getOrphanEntities());

                        try {
                            {
                                MethodCallInfo getter = new MethodCallInfo(fio.getter);
                                getter.setIndex(fio.index);
                                builder.addGetter(getter);
                                handledMethods.add(fio.getter);
                            }
                            if (fio.setter != null) {
                                MethodCallInfo setter = new MethodCallInfo(fio.setter);
                                setter.setIndex(fio.index);
                                builder.addSetter(setter);
                                handledMethods.add(fio.setter);
                            }
                        } catch (Exception ex) {
                            System.err.println("Failed to register field " + fio.name + " in struct " + type);
                            ex.printStackTrace();
                        }
                    }
			}
			
			if (Callback.class.isAssignableFrom(typeClass)) {
				if (Callback.class == type)
					return;
				
				if (Modifier.isAbstract(typeModifiers))
	                callbackNativeImplementer.getCallbackImplType((Class) type);
			}
		
		
//		for (; type != null && type != Object.class; type = type.getSuperclass()) {
			try {
				NativeLibrary typeLibrary = getNativeLibrary(typeClass);
				for (Method method : typeClass.getDeclaredMethods()) {
                    if (handledMethods.contains(method))
                        continue;
					try {
						int modifiers = method.getModifiers();
						if (!Modifier.isNative(modifiers))
							continue;
						
						NativeEntities.Builder builder = builders.get(BridJ.getNativeEntities(method));
						NativeLibrary methodLibrary = BridJ.getNativeLibrary(method);
						
						registerNativeMethod(typeClass, typeLibrary, method, methodLibrary, builder);

                        handledMethods.add(method);
					} catch (Exception ex) {
						assert log(Level.SEVERE, "Method " + method.toGenericString() + " cannot be mapped : " + ex, ex);
					}
				}
			} catch (Exception ex) {
				throw new RuntimeException("Failed to register class " + typeClass.getName(), ex);
			}
//		}
		} finally {
			for (Map.Entry<NativeEntities, NativeEntities.Builder> e : builders.entrySet()) {
				e.getKey().addDefinitions(typeClass, e.getValue());
			}
			
			typeClass = typeClass.getSuperclass();
			if (typeClass != null && typeClass != Object.class)
				register(typeClass);
		}
	}

	protected NativeLibrary getNativeLibrary(Class<?> type) throws FileNotFoundException {
		return BridJ.getNativeLibrary(type);
	}
	protected void registerNativeMethod(Class<?> type, NativeLibrary typeLibrary, Method method, NativeLibrary methodLibrary, Builder builder) throws FileNotFoundException {
        MethodCallInfo mci = new MethodCallInfo(method);
		if (Callback.class.isAssignableFrom(type)) {
            log(Level.INFO, "Registering java -> native callback : " + method);
            builder.addJavaToNativeCallback(mci);
        } else {
            Symbol symbol = methodLibrary == null ? null : methodLibrary.getSymbol(method);
            if (symbol == null)
            {
//                for (Demangler.Symbol symbol : methodLibrary.getSymbols()) {
//                    if (symbol.matches(method)) {
//                        address = symbol.getAddress();
//                        break;
//                    }
//                }
//                if (address == null) {
                    log(Level.SEVERE, "Failed to get address of method " + method);
                    return;
//                }
            }
            mci.setForwardedPointer(symbol.getAddress());
            builder.addFunction(mci);
            log(Level.INFO, "Registering " + method + " as C function " + symbol.getName());
        }
	}
	
	public <T extends NativeObject> Pointer<T> allocate(Class<T> type, int constructorId, Object... args) {
	    if (Callback.class.isAssignableFrom(type)) {
        	if (constructorId != -1 || args.length != 0)
        		throw new RuntimeException("Callback should have a constructorId == -1 and no constructor args !");
        	return null;//newCallbackInstance(type);
        }
        throw new RuntimeException("Cannot allocate instance of type " + type.getName() + " (unhandled NativeObject subclass)");
	}
	
	static final int defaultObjectSize = 128;
	public static final String PROPERTY_bridj_c_defaultObjectSize = "bridj.c.defaultObjectSize";
	
	public int getDefaultStructSize() {
		String s = System.getProperty(PROPERTY_bridj_c_defaultObjectSize);
    	if (s != null)
    		try {
    			return Integer.parseInt(s);
	    	} catch (Throwable th) {
	    		log(Level.SEVERE, "Invalid value for property " + PROPERTY_bridj_c_defaultObjectSize + " : '" + s + "'");
	    	}
    	return defaultObjectSize;
	}
	protected int sizeOf(Class<? extends StructObject> structClass, Type structType, StructIO io) {
		if (io == null)
			io = StructIO.getInstance(structClass, structType, this);
		int size;
		if (io == null || (size = io.getStructSize()) == 0)
			return getDefaultStructSize();
		return size;	
    }

    static Method getUniqueAbstractCallbackMethod(Class type) {
        Class<?> parent = null;
    	while ((parent = type.getSuperclass()) != null && parent != Callback.class) {
    		type = parent;
    	}

    	Method method = null;
    	for (Method dm : type.getDeclaredMethods()) {
    		int modifiers = dm.getModifiers();
    		if (!Modifier.isAbstract(modifiers))
    			continue;

    		method = dm;
    		break;
    	}
    	if (method == null)
    		throw new RuntimeException("Type doesn't have any abstract method : " + type.getName());
    	return method;
    }

    public <T extends NativeObject> Class<? extends T> getTypeForCast(Type type) {
        Class<?> typeClass = Utils.getClass(type);
        if (Callback.class.isAssignableFrom(typeClass))
            return callbackNativeImplementer.getCallbackImplType((Class) typeClass);
        else
            return (Class<? extends T>)typeClass;
    }


    private <T extends Callback<?>> Pointer<T> registerCallbackInstance(T instance) {
		try {
            Class<?> c = instance.getClass();
			MethodCallInfo mci = new MethodCallInfo(getUniqueAbstractCallbackMethod(c));
            mci.setDeclaringClass(c);
            mci.setJavaCallback(instance);
            final long handle = JNI.createCToJavaCallback(mci);
            long peer = JNI.getActualCToJavaCallback(handle);
            return (Pointer)Pointer.pointerToAddress(peer, c, new Pointer.Releaser() {

                @Override
                public void release(Pointer<?> pointer) {
                    JNI.freeCToJavaCallback(pointer.getPeer());
                }
            });
		} catch (FileNotFoundException e) {
			throw new RuntimeException("Failed to register callback instance of type " + instance.getClass().getName(), e);
		}
	}

    protected void setNativeObjectPeer(NativeObject instance, Pointer<? extends NativeObject> peer) {
        instance.peer = peer;
    }
}